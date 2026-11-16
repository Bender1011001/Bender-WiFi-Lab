"""Exercise the shipped installer with real archives and mocked Android mounts.

No device is contacted. Hashing, extraction, locking (Linux), and atomic rename
are real; mocks cannot establish SELinux or handset execution compatibility.
"""
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest

GIT_BASH = Path('C:/Program Files/Git/bin/bash.exe')
BASH = str(GIT_BASH) if os.name == 'nt' and GIT_BASH.exists() else shutil.which('bash')
SCRIPT = Path(__file__).resolve().parents[2] / 'runtime/runtime-installer.sh'


def shell_path(path):
    value = Path(path).resolve().as_posix()
    return '/' + value[0].lower() + value[2:] if os.name == 'nt' else value


MOCKS = r'''
id(){ echo 0; }
mkdir(){ if [ "${1:-}" = -m ];then shift 2;fi;command mkdir "$@"; }
uname(){ if [ "$1" = -m ]; then echo "${MOCK_ARCH:-aarch64}";else command uname "$@";fi; }
stat(){ case "$2" in %u) echo 0;; %a) echo 700;; *) command stat "$@";; esac; }
kill(){ if [ "$1" = -0 ] && [ "$2" = 999999 ];then [ "${MOCK_OWNER:-1}" = 1 ];else builtin kill "$@";fi; }
df(){ printf 'Filesystem 1K-blocks Used Available Use%% Mounted\nfixture 999999999 0 %s 0%% /\n' "${MOCK_FREE:-999999999}"; }
toybox(){ [ "$1" = flock ] || return 99;shift;[ "${MOCK_LOCK:-1}" = 1 ] || return 1;
    case "$(command uname -s)" in Linux) command flock "$@";; *) return 0;; esac; }
sleep(){ command sleep 0.01; }
mount(){ echo "mount ${@: -1}" >> "$MOCK_ROOT/mount.log";[ "${MOCK_MOUNT:-1}" = 1 ] || return 1;touch "$MOCK_ROOT/mounted-$(basename "${@: -1}")"; }
umount(){
    echo "umount $1" >> "$MOCK_ROOT/mount.log"
    [ "${MOCK_UMOUNT:-1}" = 1 ] || return 1
    name=$(basename "$1")
    if [ "${MOCK_UNMOUNT_ONCE:-}" = "$name" ] && [ ! -e "$MOCK_ROOT/unmount-failed-once" ];then touch "$MOCK_ROOT/unmount-failed-once";return 1;fi
    [ -f "$MOCK_ROOT/mounted-$name" ] || return 1
    rm "$MOCK_ROOT/mounted-$name"
}
chroot(){ echo "$*" >> "$MOCK_ROOT/selfcheck.log";[ "${MOCK_CHECK:-1}" = 1 ]; }
timeout(){ shift;"$@"; }
'''


@unittest.skipUnless(BASH, 'Bash is required for installer control-flow tests')
class RuntimeInstallerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='bender-setup-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.posix = shell_path(self.root)
        self.sid = 'bw_123456789_abcdef123456'
        self.out = self.root/'app/qca-sessions'/self.sid
        self.out.mkdir(parents=True)
        self.archive = self.root/'app/runtime-package/rootfs.tar.gz'
        self.archive.parent.mkdir()
        with tarfile.open(self.archive, 'w:gz') as tar:
            for name in ('proc', 'sys', 'dev', 'usr', 'usr/bin', 'usr/sbin'):
                entry = tarfile.TarInfo(name); entry.type = tarfile.DIRTYPE; entry.mode = 0o755
                tar.addfile(entry)
            for name in ('usr/bin/python3', 'usr/bin/tshark', 'usr/sbin/iw'):
                entry = tarfile.TarInfo(name); entry.size = 7; entry.mode = 0o755
                tar.addfile(entry, io.BytesIO(b'fixture'))
        self.digest = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.final = self.root/'private/runtime-arm64-v1'
        self.stage = self.root/'private'/('stage-'+self.sid)
        source = SCRIPT.read_text()
        for before, after in {
            '/data/local/tmp/hijacker-qca': self.posix+'/radio',
            '/data/local/bender-wifi': self.posix+'/private',
            '/data/user/0/dev.sir.benderwifi/files': self.posix+'/app',
            '/data/data/dev.sir.benderwifi/files': self.posix+'/app',
        }.items():
            source = source.replace(before, after)
        self.script = self.root/'install.sh'
        self.script.write_text('#!/bin/bash\n'+MOCKS+source.partition('\n')[2], newline='\n')

    def run_installer(self, **overrides):
        completed = subprocess.run([BASH, shell_path(self.script), self.sid, shell_path(self.out),
                                    '999999', shell_path(self.archive), self.digest, '1024'],
                                   env={**os.environ, 'MOCK_ROOT': self.posix, **overrides},
                                   text=True, capture_output=True, timeout=15)
        self.output = completed.stdout+completed.stderr
        return completed.returncode

    def test_install_checks_and_commits_image(self):
        self.assertEqual(0, self.run_installer(), self.output)
        self.assertEqual(self.digest, (self.final/'.bender-ready').read_text().strip())
        self.assertFalse(self.stage.exists())
        self.assertEqual(3, len((self.root/'selfcheck.log').read_text().splitlines()))
        calls = (self.root/'mount.log').read_text().splitlines()
        self.assertEqual(6, len(calls))
        self.assertTrue(json.loads((self.out/'runtime-state.json').read_text())['runtime_ready'])

    def test_existing_matching_runtime_is_preserved(self):
        self.assertEqual(0, self.run_installer(), self.output)
        (self.final/'sentinel').write_text('keep')
        self.assertEqual(0, self.run_installer(), self.output)
        self.assertTrue(json.loads((self.out/'result.json').read_text())['already_installed'])
        self.assertEqual('keep', (self.final/'sentinel').read_text())

    def test_existing_different_runtime_is_never_replaced(self):
        self.final.mkdir(parents=True)
        (self.final/'.bender-ready').write_text('another runtime')
        self.assertNotEqual(0, self.run_installer(), self.output)
        self.assertIn('explicit upgrade', self.output)
        self.assertEqual('another runtime', (self.final/'.bender-ready').read_text())
        self.assertFalse(self.stage.exists())

    def test_tampered_archive_is_not_extracted(self):
        self.archive.write_bytes(self.archive.read_bytes()+b'tampered')
        self.assertNotEqual(0, self.run_installer(), self.output)
        self.assertIn('integrity verification', self.output)
        self.assertFalse(self.final.exists())
        self.assertFalse(self.stage.exists())
        self.assertFalse((self.root/'mount.log').exists())

    def test_cancel_and_dead_owner_do_not_install(self):
        (self.out/'cancel').touch()
        self.assertEqual(130, self.run_installer(), self.output)
        self.assertTrue(json.loads((self.out/'result.json').read_text())['cancelled'])
        (self.out/'cancel').unlink()
        self.assertEqual(130, self.run_installer(MOCK_OWNER='0'), self.output)
        self.assertFalse(self.final.exists())

    def test_lock_and_recovery_block_installation(self):
        self.assertNotEqual(0, self.run_installer(MOCK_LOCK='0'), self.output)
        self.assertIn('Another radio or setup operation', self.output)
        recovery = self.root/'radio/recovery'
        recovery.mkdir()
        (recovery/'pending').touch()
        self.assertNotEqual(0, self.run_installer(), self.output)
        self.assertIn('Restore the previous', self.output)
        self.assertFalse(self.stage.exists())

    def test_low_disk_and_wrong_architecture_do_not_extract(self):
        self.assertNotEqual(0, self.run_installer(MOCK_FREE='1'), self.output)
        self.assertIn('Not enough storage', self.output)
        self.assertNotEqual(0, self.run_installer(MOCK_ARCH='x86_64'), self.output)
        self.assertIn('ARM64 phone', self.output)
        self.assertFalse(self.stage.exists())

    def test_mount_failure_cleans_staging_without_committing(self):
        self.assertNotEqual(0, self.run_installer(MOCK_MOUNT='0'), self.output)
        self.assertIn('prepare the runtime mounts', self.output)
        self.assertFalse(self.stage.exists())
        self.assertFalse(self.final.exists())

    def test_failed_selfcheck_unmounts_and_allows_retry(self):
        self.assertNotEqual(0, self.run_installer(MOCK_CHECK='0'), self.output)
        self.assertIn('Python runtime self-check failed', self.output)
        self.assertFalse(self.stage.exists())
        self.assertFalse(self.final.exists())
        self.assertEqual(0, self.run_installer(), self.output)

    def test_failed_unmount_retains_stage_and_never_commits(self):
        self.assertNotEqual(0, self.run_installer(MOCK_UMOUNT='0'), self.output)
        self.assertTrue(self.stage.exists())
        self.assertFalse(self.final.exists())

    def test_failed_result_write_still_cleans_stage(self):
        (self.out/'result.json').mkdir()  # Deterministic unwritable destination.
        self.assertNotEqual(0, self.run_installer(MOCK_CHECK='0'), self.output)
        self.assertIn('Could not save setup result', self.output)
        self.assertFalse(self.stage.exists())
        self.assertFalse(self.final.exists())

    def test_partial_unmount_retries_only_remaining_mounts(self):
        self.assertNotEqual(0, self.run_installer(MOCK_UNMOUNT_ONCE='sys'), self.output)
        self.assertFalse(self.stage.exists(), self.output)
        calls=(self.root/'mount.log').read_text().splitlines()
        self.assertEqual(1,sum(x.startswith('umount ') and x.endswith('/dev') for x in calls))
        self.assertEqual(2,sum(x.startswith('umount ') and x.endswith('/sys') for x in calls))
        self.assertFalse(list(self.root.glob('mounted-*')))

    def test_preexisting_stage_is_not_deleted_by_rejected_install(self):
        self.stage.mkdir(parents=True)
        (self.stage/'sentinel').write_text('preserve')
        self.assertNotEqual(0,self.run_installer(),self.output)
        self.assertIn('staging directory already exists',self.output)
        self.assertEqual('preserve',(self.stage/'sentinel').read_text())


if __name__ == '__main__':
    unittest.main()
