"""Reversible, journaled Android lab profile. No phone access at import time."""
from contextlib import contextmanager
import json
import os
import re
from pathlib import Path
import shlex
import stat
import subprocess
import time

from identity_guard import _execute, _cancellation_guard, verified_lock_rules

JOURNAL_DIR = Path('/proc/1/root/data/local/tmp/hijacker-qca/bender-privacy')
CHAIN = 'BWL_PRIVATE'
KEYS = ('airplane_mode_on', 'mobile_data', 'bluetooth_on', 'wifi_on')
DESIRED = dict(zip(KEYS, (True, False, False, True)))
HOST = ['/usr/sbin/chroot', '/proc/1/root']
SETTINGS_SCRIPT = 'set -e\nexport PATH=/system/bin:/system/xbin\n' + '\n'.join('/system/bin/settings get global ' + k for k in KEYS)
CONTEXT_SCRIPT = ('set -e\n'
    'host_ns=$(/system/bin/readlink /proc/1/ns/net)\n'
    'current_ns=$(/system/bin/readlink /proc/self/ns/net)\n'
    'test -n "$host_ns"\ntest "$host_ns" = "$current_ns"\n'
    '/system/bin/getprop ro.build.version.sdk\n/system/bin/getprop ro.build.fingerprint')
_OWN_RULES = [('-A', CHAIN, '-o', 'lo', '-j', 'RETURN'), ('-A', CHAIN, '-j', 'DROP')]
_WIFI_RULES = [('-A', CHAIN, '-o', 'wlan0', '-j', 'DROP'), ('-A', CHAIN, '-j', 'RETURN')]
_WPS_RULES = [_WIFI_RULES[0], ('-A', CHAIN, '-o', 'wlan1', '-j', 'DROP'), _WIFI_RULES[1]]


def _rules(profile):
    return _WPS_RULES if profile == 'wps' else _WIFI_RULES if profile == 'wifi' else _OWN_RULES


def _tokens(raw):
    if not isinstance(raw, str):
        raise ValueError('firewall evidence unavailable')
    lines = [tuple(shlex.split(line)) for line in raw.splitlines() if line.strip()]
    if not lines or any(not line or line[0] not in ('-P', '-N', '-A') for line in lines):
        raise ValueError('unsupported firewall evidence')
    # An empty or truncated listing cannot establish both built-in chains.
    if not all(any(len(line) == 3 and line[:2] == ('-P', c) for line in lines) for c in ('OUTPUT', 'FORWARD')):
        raise ValueError('incomplete firewall evidence')
    return lines


def _owned(lines):
    return [line for line in lines if CHAIN in line]


def verify_rules(raw):
    """Verify exact owned chain and first-position hooks from privileged -S."""
    return verified_lock_rules(raw)


class HostRunner:
    """Fixed host commands, each <=3 seconds; each transaction <=30 seconds."""
    def __init__(self):
        self.deadline = time.monotonic() + 30

    def _run(self, args):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('profile deadline')
        try:
            return _execute(HOST + list(args), min(3.0, remaining))
        except (OSError, ValueError, TimeoutError, subprocess.SubprocessError) as error:
            raise RuntimeError('host command unavailable') from error

    def context(self):
        if getattr(os, 'geteuid', lambda: -1)() != 0:
            raise RuntimeError('root host required')
        lines = self._run(('/system/bin/sh', '-c', CONTEXT_SCRIPT)).splitlines()
        if len(lines) != 2 or not lines[0].isdigit() or not lines[1].strip():
            raise ValueError('unsupported Android context')

    def settings(self):
        values = self._run(('/system/bin/sh', '-c', SETTINGS_SCRIPT)).splitlines()
        if len(values) != len(KEYS) or any(v not in ('0', '1') for v in values):
            raise ValueError('settings unavailable')
        return dict(zip(KEYS, (v == '1' for v in values)))

    def set_setting(self, key, value):
        if key not in KEYS or type(value) is not bool:
            raise ValueError('unsupported setting')
        action = 'enable' if value else 'disable'
        args = {
            'airplane_mode_on': ('/system/bin/cmd', 'connectivity', 'airplane-mode', action),
            'mobile_data': ('/system/bin/cmd', 'phone', 'data', action),
            'bluetooth_on': ('/system/bin/cmd', 'bluetooth_manager', action),
            'wifi_on': ('/system/bin/cmd', 'wifi', 'set-wifi-enabled', 'enabled' if value else 'disabled'),
        }
        self._run(args[key])

    def interface_ready(self):
        return self._run(('/system/bin/sh', '-c',
                         'if [ -r /sys/class/net/wlan0/type ] && [ "$(cat /sys/class/net/wlan0/type)" = 1 ]; then echo ready; else echo waiting; fi')).strip() == 'ready'

    def rules(self, family):
        return self.firewall(family, '-S')

    def firewall(self, family, *args):
        if family not in (4, 6):
            raise ValueError('unsupported family')
        allowed = {('-S',), ('-N', CHAIN), ('-X', CHAIN),
                   ('-A', CHAIN, '-o', 'lo', '-j', 'RETURN'), ('-A', CHAIN, '-j', 'DROP'),
                   ('-D', CHAIN, '-o', 'lo', '-j', 'RETURN'), ('-D', CHAIN, '-j', 'DROP')}
        allowed.update((operation, *line[1:]) for line in _WPS_RULES for operation in ('-A', '-D'))
        allowed.update((operation, chain, *position, '-j', CHAIN)
                       for chain in ('OUTPUT', 'FORWARD')
                       for operation, position in (('-I', ('1',)), ('-D', ())))
        if args not in allowed:
            raise ValueError('unsupported firewall mutation')
        binary = '/system/bin/iptables' if family == 4 else '/system/bin/ip6tables'
        return self._run((binary, '-w', '1', '-t', 'filter', *args))


def _validate_settings(settings):
    if not isinstance(settings, dict) or set(settings) != set(KEYS) or any(type(x) is not bool for x in settings.values()):
        raise ValueError('invalid settings evidence')
    return settings


@contextmanager
def _lock(directory, testing=False):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    if directory.is_symlink():
        raise ValueError('journal directory symlink')
    if not testing and directory.stat().st_uid != 0:
        raise ValueError('journal directory not root owned')
    directory.chmod(0o700)
    descriptor = os.open(directory / 'operation.lock', os.O_RDWR | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0), 0o600)
    with os.fdopen(descriptor, 'a+b') as handle:
        metadata = os.fstat(handle.fileno())
        if not stat.S_ISREG(metadata.st_mode) or (not testing and metadata.st_uid != 0):
            raise ValueError('invalid operation lock')
        try:
            import fcntl
        except ImportError:
            if not testing:
                raise RuntimeError('host locking unavailable')
        else:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def _durable(path, value):
    temporary = path.with_suffix('.tmp')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as handle:
        if hasattr(os, 'fchmod'):
            os.fchmod(handle.fileno(), 0o600)
        json.dump(value, handle, sort_keys=True)
        handle.flush()
        os.fsync(handle.fileno())
    temporary.replace(path)
    _sync_directory(path.parent)


def _sync_directory(path):
    if os.name == 'posix':
        descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def _load(path, testing=False):
    metadata = path.stat()
    if (path.is_symlink() or not stat.S_ISREG(metadata.st_mode) or metadata.st_size > 16384
            or (not testing and os.name == 'posix' and (metadata.st_uid != 0 or metadata.st_mode & 0o077))):
        raise ValueError('invalid journal')
    data = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(data, dict) or type(data.get('schema_version')) is not int:
        raise ValueError('invalid journal')
    if data['schema_version'] == 1:
        if set(data) != {'schema_version', 'original'}:
            raise ValueError('invalid legacy journal')
    elif data['schema_version'] == 2:
        if (set(data) != {'schema_version', 'original', 'profile', 'owner'} or data['profile'] not in ('wifi', 'strict', 'wps')
                or not isinstance(data['owner'], str) or not re.fullmatch(r'bw_[0-9]+_[0-9a-f]{12}', data['owner'])):
            raise ValueError('invalid session journal')
    else:
        raise ValueError('unsupported journal')
    _validate_settings(data['original'])
    return data


def _report(job, *, pending, active=False, restored=False, state='unknown', blocker=None):
    messages = {'prepared': 'Lab profile is active. Restore normal connectivity when finished.',
                'restored': 'Saved connectivity settings restored and owned firewall hooks removed.',
                'idle': 'No saved lab profile is pending.',
                'pending': 'Connectivity restoration is pending. Use Restore normal connectivity.',
                'blocked': 'Lab profile could not be applied.', 'unknown': 'Profile evidence is unavailable.'}
    report = {'schema_version': 1, 'checked_at': time.time(), 'pending': pending,
              'active': active, 'restored': restored, 'state': state,
              'message': messages[state], 'blockers': [blocker] if blocker else []}
    destination = Path(job) / 'privacy-state.json'
    temporary = destination.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    temporary.replace(destination)
    return report


def _wait_settings(runner, expected):
    for attempt in range(4):
        if _validate_settings(runner.settings()) == expected:
            return
        if attempt < 3:
            time.sleep(0.2)
    raise ValueError('settings did not converge')


def _remove_owned(runner, family, profile='strict'):
    lines = _tokens(runner.rules(family))
    relevant = _owned(lines)
    if not relevant:
        return
    # Partial application is recoverable, but unexpected rules/references are
    # never flushed or deleted. Leave the obligation for explicit recovery.
    own_rules = _rules(profile)
    allowed = {('-N', CHAIN), *own_rules,
               ('-A', 'OUTPUT', '-j', CHAIN), ('-A', 'FORWARD', '-j', CHAIN)}
    if len(relevant) != len(set(relevant)) or any(line not in allowed for line in relevant):
        raise ValueError('owned chain changed')
    for chain in ('OUTPUT', 'FORWARD'):
        if ('-A', chain, '-j', CHAIN) in relevant:
            runner.firewall(family, '-D', chain, '-j', CHAIN)
    for line in reversed(own_rules):
        if line in relevant:
            runner.firewall(family, '-D', *line[1:])
    if ('-N', CHAIN) in relevant:
        runner.firewall(family, '-X', CHAIN)
    if _owned(_tokens(runner.rules(family))):
        raise ValueError('owned rules remain')


def _restore_transaction(runner, journal, path):
    runner.context()
    profile = journal.get('profile', 'strict')
    # Restore settings while locks still protect ordinary IP traffic.
    keys = KEYS if profile == 'strict' else ('wifi_on',)
    for key in keys:
        if profile != 'strict' and runner.settings()[key] == journal['original'][key]:
            continue
        runner.set_setting(key, journal['original'][key])
    expected = journal['original'] if profile == 'strict' else {**runner.settings(), 'wifi_on': journal['original']['wifi_on']}
    _wait_settings(runner, expected)
    failures = []
    for family in (4, 6):
        try:
            _remove_owned(runner, family, profile)
        except (OSError, ValueError, RuntimeError, TimeoutError):
            failures.append(family)
    if failures:
        raise ValueError('firewall restore incomplete')
    # Recheck actual settings and both rule sets before clearing the obligation.
    if any(_validate_settings(runner.settings())[k] != journal['original'][k] for k in keys) or any(
            _owned(_tokens(runner.rules(f))) for f in (4, 6)):
        raise ValueError('restore verification failed')
    path.unlink()
    _sync_directory(path.parent)


def prepare(job, *, runner=None, journal_dir=None, profile='strict', session=False):
    testing = runner is not None
    runner = runner or HostRunner()
    directory = Path(journal_dir) if journal_dir is not None else JOURNAL_DIR
    path = directory / 'pending.json'
    try:
        with _lock(directory, testing), _cancellation_guard():
            if path.exists():
                return _report(job, pending=True, state='pending', blocker='restore_required')
            runner.context()
            original = _validate_settings(runner.settings())
            # Refuse pre-existing names in either family before journaling/mutation.
            if any(_owned(_tokens(runner.rules(f))) for f in (4, 6)):
                return _report(job, pending=False, state='blocked', blocker='firewall_name_collision')
            journal = {'schema_version': 1, 'original': original}
            if profile not in ('wifi', 'strict', 'wps'):
                raise ValueError('unknown profile')
            if profile in ('wifi', 'wps') or session:
                if not re.fullmatch(r'bw_[0-9]+_[0-9a-f]{12}', Path(job).name):
                    raise ValueError('invalid session owner')
                journal.update(schema_version=2, profile=profile, owner=Path(job).name)
            _durable(path, journal)
            _report(job, pending=True, state='pending')
            try:
                for family in (4, 6):
                    runner.firewall(family, '-N', CHAIN)
                    for line in _rules(profile):
                        runner.firewall(family, *line)
                    for chain in ('OUTPUT', 'FORWARD'):
                        runner.firewall(family, '-I', chain, '1', '-j', CHAIN)
                if not all(verified_lock_rules(runner.rules(f), wireless=profile == 'wifi', wps=profile == 'wps') for f in (4, 6)):
                    raise ValueError('egress verification failed')
                desired = DESIRED if profile == 'strict' else {**original, 'wifi_on': True}
                for key in (KEYS if profile == 'strict' else ('wifi_on',)):
                    if profile == 'strict' or original[key] != desired[key]:
                        runner.set_setting(key, desired[key])
                _wait_settings(runner, desired)
                if session:
                    for attempt in range(20):
                        if runner.interface_ready():
                            break
                        if attempt < 19:
                            time.sleep(0.25)
                    else:
                        raise ValueError('Wi-Fi interface did not become ready')
                if not all(verified_lock_rules(runner.rules(f), wireless=profile == 'wifi', wps=profile == 'wps') for f in (4, 6)):
                    raise ValueError('egress verification failed')
                return _report(job, pending=True, active=True, state='prepared')
            except BaseException as failure:
                try:
                    # A new real runner provides an independent bounded rollback
                    # budget; injected fakes preserve their simulated host state.
                    recovery = runner if testing else HostRunner()
                    _restore_transaction(recovery, journal, path)
                except BaseException:
                    _report(job, pending=True, state='pending', blocker='restore_incomplete')
                else:
                    _report(job, pending=False, restored=True, state='blocked', blocker='prepare_rolled_back')
                if isinstance(failure, (KeyboardInterrupt, SystemExit)):
                    raise
                return json.loads((Path(job) / 'privacy-state.json').read_text())
    except (OSError, ValueError, RuntimeError, TimeoutError):
        return _report(job, pending=path.exists(), state='pending' if path.exists() else 'blocked', blocker='profile_evidence_unavailable')


def restore(job, *, runner=None, journal_dir=None, owner_only=False):
    testing = runner is not None
    runner = runner or HostRunner()
    directory = Path(journal_dir) if journal_dir is not None else JOURNAL_DIR
    path = directory / 'pending.json'
    try:
        with _lock(directory, testing), _cancellation_guard():
            if not path.exists():
                runner.context()
                if any(_owned(_tokens(runner.rules(f))) for f in (4, 6)):
                    return _report(job, pending=True, state='pending', blocker='journal_missing')
                return _report(job, pending=False, state='idle', restored=False)
            journal = _load(path, testing)
            if owner_only and journal.get('owner') != Path(job).name:
                return _report(job, pending=True, state='pending', blocker='different_session_owner')
            _report(job, pending=True, state='pending')
            _restore_transaction(runner, journal, path)
            return _report(job, pending=False, restored=True, state='restored')
    except (OSError, ValueError, RuntimeError, TimeoutError):
        return _report(job, pending=path.exists(), state='pending' if path.exists() else 'unknown', blocker='restore_incomplete')


def status(job, *, runner=None, journal_dir=None):
    testing = runner is not None
    runner = runner or HostRunner()
    directory = Path(journal_dir) if journal_dir is not None else JOURNAL_DIR
    path = directory / 'pending.json'
    try:
        with _lock(directory, testing):
            runner.context()
            pending = path.exists()
            journal = _load(path, testing) if pending else {}
            settings = _validate_settings(runner.settings())
            rules = [runner.rules(f) for f in (4, 6)]
            owned = any(_owned(_tokens(raw)) for raw in rules)
            wireless = journal.get('profile') in ('wifi', 'wps')
            active = pending and (settings['wifi_on'] if wireless else settings == DESIRED) and all(verified_lock_rules(raw, wireless, journal.get('profile') == 'wps') for raw in rules)
            state = 'prepared' if active else 'pending' if pending or owned else 'idle'
            return _report(job, pending=pending or owned, active=active,
                           restored=False, state=state)
    except (OSError, ValueError, RuntimeError, TimeoutError):
        return _report(job, pending=path.exists(), state='unknown', blocker='profile_evidence_unavailable')


def owns_session(job, directory=None, testing=False):
    try:
        journal = _load(Path(directory or JOURNAL_DIR) / 'pending.json', testing)
        return journal.get('profile') == 'wifi' and journal.get('owner') == Path(job).name
    except (OSError, ValueError, RuntimeError):
        return False
