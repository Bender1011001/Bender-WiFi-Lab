"""Linux integration tests. Uses empty temporary roots; never accesses radios.

Run as root on a development Linux host, not on the phone. Test fixtures that
need mounts are themselves inside a private namespace and cannot alter the
caller's mount table. The launcher is exercised with real mount/chroot syscalls.
"""
import ctypes
import os
from pathlib import Path
import select
import shutil
import signal
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
TARGETS = ('proc', 'sys', 'dev', 'tmp/fresh-boot-air-20261116/shared')
LIBC = ctypes.CDLL(None, use_errno=True)
LIBC.mount.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p,
                       ctypes.c_ulong, ctypes.c_void_p]
MS_BIND, MS_REC, MS_PRIVATE, MS_SHARED = 4096, 16384, 262144, 1048576


def mount(source, target, flags):
    if LIBC.mount(os.fsencode(source) if source else None, os.fsencode(target),
                  None, flags, None):
        raise OSError(ctypes.get_errno(), os.strerror(ctypes.get_errno()))


def fixture_namespace():
    if LIBC.unshare(0x20000):
        raise OSError(ctypes.get_errno(), 'unshare fixture')
    mount(None, '/', MS_PRIVATE | MS_REC)


def fixture_shared():
    fixture_namespace()
    mount(None, '/', MS_SHARED)  # Deliberately adversarial parent.


class IsolationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.geteuid() != 0:
            raise RuntimeError('run tests as root on the development Linux host')
        cls.build_temp = tempfile.TemporaryDirectory(prefix='bender-mount-build-')
        cls.build = Path(cls.build_temp.name)
        cls.helper = cls.build / 'isolated-runtime'
        cls.probe = cls.build / 'probe'
        cls.faults = cls.build / 'faults.so'
        for args in (
            ['-O2', str(HERE / 'isolated-runtime.c'), '-o', str(cls.helper)],
            ['-O2', '-static', str(HERE / 'test_probe.c'), '-o', str(cls.probe)],
            ['-O2', '-shared', '-fPIC', str(HERE / 'test_faults.c'), '-ldl', '-o', str(cls.faults)],
        ):
            subprocess.run(['gcc', '-std=c11', '-Wall', '-Wextra', '-Werror', *args], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.build_temp.cleanup()

    def setUp(self):
        self.base = Path(tempfile.mkdtemp(prefix='bender-mount-fixture-'))
        self.root = self.base / 'runtime'
        self.shared = self.base / 'shared'
        self.shared.mkdir()
        for target in TARGETS:
            (self.root / target).mkdir(parents=True)
        shutil.copyfile(self.probe, self.root / 'probe')
        (self.root / 'probe').chmod(0o755)
        self.original_mounts = Path('/proc/self/mountinfo').read_bytes()
        self.original_namespace = os.stat('/proc/self/ns/mnt').st_ino

    def tearDown(self):
        # A leaked mount is not safe to remove recursively. Leave fixture for
        # diagnosis if this assertion fails; do not traverse its mounted trees.
        current = Path('/proc/self/mountinfo').read_bytes()
        self.assertEqual(current, self.original_mounts, 'parent mount table changed')
        self.assertEqual(os.stat('/proc/self/ns/mnt').st_ino, self.original_namespace)
        shutil.rmtree(self.base)

    def command(self, argument='0'):
        return [str(self.helper), str(self.root), str(self.shared), '--', '/probe', argument]

    def run_helper(self, argument='0', fault=None, fixture=None):
        env = os.environ.copy()
        if fault:
            env.update(LD_PRELOAD=str(self.faults), BENDER_TEST_FAULT=fault)
        return subprocess.run(self.command(argument), env=env, preexec_fn=fixture,
                              text=True, capture_output=True, timeout=10)

    def assert_success(self, result):
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('four bindings verified', result.stderr)
        observed = dict(item.split('=') for item in result.stdout.split())
        self.assertNotEqual(int(observed['namespace']), self.original_namespace)
        identity = self.shared.stat()
        self.assertEqual(observed['shared'], f'{identity.st_dev}:{identity.st_ino}')

    def test_success(self):
        self.assert_success(self.run_helper())

    def test_shared_parent_success(self):
        self.assert_success(self.run_helper(fixture=fixture_shared))

    def test_command_exit_is_preserved(self):
        result = self.run_helper('23')
        self.assertEqual(result.returncode, 23, result.stderr)
        self.assertIn('four bindings verified', result.stderr)

    def test_missing_executable(self):
        (self.root / 'probe').unlink()
        result = self.run_helper()
        self.assertEqual(result.returncode, 125)
        self.assertIn('execute runtime command', result.stderr)

    def test_process_killed(self):
        proc = subprocess.Popen(self.command('wait'), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True)
        try:
            ready, _, _ = select.select([proc.stdout], [], [], 10)
            self.assertTrue(ready, 'child did not report readiness')
            self.assertIn('namespace=', proc.stdout.readline())
            proc.kill()
            out, err = proc.communicate(timeout=10)
            self.assertEqual(proc.returncode, -signal.SIGKILL, out + err)
        finally:
            if proc.poll() is None:
                proc.kill()
            proc.communicate(timeout=10)

    def test_parallel_children(self):
        children = [subprocess.Popen(self.command(), stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True) for _ in range(4)]
        results = [child.communicate(timeout=10) for child in children]
        for child, (out, err) in zip(children, results):
            self.assertEqual(child.returncode, 0, out + err)

    def test_unshare_error(self):
        result = self.run_helper(fault='unshare-error')
        self.assertEqual(result.returncode, 125)
        self.assertIn('unshare mount namespace', result.stderr)

    def test_unshare_false_success(self):
        result = self.run_helper(fault='unshare-noop')
        self.assertEqual(result.returncode, 125)
        self.assertIn('did not change namespace', result.stderr)

    def test_propagation_error(self):
        result = self.run_helper(fault='private-error', fixture=fixture_shared)
        self.assertEqual(result.returncode, 125)
        self.assertIn('make mount tree private', result.stderr)

    def test_propagation_false_success(self):
        result = self.run_helper(fault='private-noop', fixture=fixture_shared)
        self.assertEqual(result.returncode, 125)
        self.assertIn('propagation is not private', result.stderr)

    def test_failure_after_one_binding(self):
        result = self.run_helper(fault='second-bind-error')
        self.assertEqual(result.returncode, 125)
        self.assertIn('bind runtime directory', result.stderr)

    def test_binding_false_success(self):
        result = self.run_helper(fault='bind-noop')
        self.assertEqual(result.returncode, 125)
        self.assertIn('identity mismatch', result.stderr)

    def test_existing_mount_refused(self):
        def existing():
            fixture_namespace()
            mount('/proc', self.root / 'proc', MS_BIND)
        result = self.run_helper(fixture=existing)
        self.assertEqual(result.returncode, 125)
        self.assertIn('already mounted', result.stderr)

    def test_nested_mount_refused(self):
        (self.root / 'dev/nested').mkdir()
        def existing():
            fixture_namespace()
            mount('/dev', self.root / 'dev/nested', MS_BIND)
        result = self.run_helper(fixture=existing)
        self.assertEqual(result.returncode, 125)
        self.assertIn('nested mount', result.stderr)

    def test_target_symlink_refused(self):
        (self.root / 'sys').rmdir()
        (self.root / 'sys').symlink_to('/sys')
        result = self.run_helper()
        self.assertEqual(result.returncode, 125)
        self.assertNotIn('four bindings verified', result.stderr)

    def test_root_symlink_refused(self):
        alias = self.base / 'alias'
        alias.symlink_to(self.root)
        self.root = alias
        result = self.run_helper()
        self.assertEqual(result.returncode, 125)
        self.assertIn('canonical directory', result.stderr)

    def test_missing_target_refused(self):
        (self.root / 'dev').rmdir()
        result = self.run_helper()
        self.assertEqual(result.returncode, 125)
        self.assertNotIn('four bindings verified', result.stderr)

    def test_publicly_writable_target_refused(self):
        (self.root / 'dev').chmod(0o777)
        result = self.run_helper()
        self.assertEqual(result.returncode, 125)
        self.assertIn('not group/world writable', result.stderr)

    def test_untrusted_target_refused(self):
        os.chown(self.root / 'dev', 65534, 65534)
        result = self.run_helper()
        self.assertEqual(result.returncode, 125)
        self.assertIn('root-owned', result.stderr)

    def test_untrusted_ancestor_pinned(self):
        # Mirrors a root-owned helper directory below Android's shell-owned tmp.
        os.chown(self.base, 2000, 2000)
        self.assert_success(self.run_helper())

    def test_spaces_in_path(self):
        renamed = self.base / 'runtime with spaces'
        self.root.rename(renamed)
        self.root = renamed
        self.assert_success(self.run_helper())


if __name__ == '__main__':
    unittest.main(verbosity=2)
