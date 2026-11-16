#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Passive libpcap capture, frequency hopping, and observed AP/client associations.

TShark decodes radiotap/802.11. No packet is generated or transmitted by this module.
The Android supervisor exclusively owns monitor-mode transitions and restoration.
"""
import datetime
import argparse
import html
import json
import os
from pathlib import Path
import queue
import re
import signal
import subprocess
import sys
import threading
import time

FIELDS = ('frame.time_epoch', 'wlan.fc.type_subtype', 'wlan.fc.tods',
          'wlan.fc.fromds', 'wlan.bssid', 'wlan.ta', 'wlan.ra',
          'radiotap.channel.freq', 'radiotap.dbm_antsignal', 'wlan.ssid',
          'wlan.fixed.capabilities.privacy', 'wlan.rsn.version',
          'wlan.rsn.akms.type', 'wlan.rsn.pcs.type', 'wlan.ds.current_channel',
          'wlan.ht.info.primarychannel', 'radiotap.flags.badfcs', 'wlan.fcs.status',
          'wlan.wfa.ie.wpa.version', 'wlan.rsn.capabilities.mfpc',
          'wlan.rsn.capabilities.mfpr', 'eapol.type', 'wlan_rsna_eapol.keydes.msgnr')


def band(frequency):
    return '6' if frequency >= 5935 else '5' if frequency >= 5000 else '2.4'


def individual(value):
    return bool(re.fullmatch(r'(?:[0-9a-f]{2}:){5}[0-9a-f]{2}', value)
                and not int(value[:2], 16) & 1 and value != '00:00:00:00:00:00')


def channel(frequency):
    if type(frequency) is not int:
        return -1
    if frequency == 2484:
        return 14
    if 2412 <= frequency <= 2472 and (frequency - 2407) % 5 == 0:
        return (frequency - 2407) // 5
    if frequency == 5935:
        return 2
    if 5955 <= frequency <= 7115 and (frequency - 5950) % 5 == 0:
        return (frequency - 5950) // 5
    if 5005 <= frequency <= 5895 and frequency % 5 == 0:
        return (frequency - 5000) // 5
    return -1


def frequencies(info):
    """Use the driver's current regulatory list, including passive/no-IR channels."""
    result = []
    for line in info.splitlines():
        match = re.search(r'\*\s+(\d+)(?:\.0+)?\s+MHz\s+\[\d+\]', line)
        if match and 'disabled' not in line.lower():
            freq = int(match[1])
            if channel(freq) > 0 and freq not in result:
                result.append(freq)
    return result


def tuned_frequency(info):
    match = re.search(r'channel\s+\d+\s+\((\d+)(?:\.0+)?\s+MHz\)', info)
    return int(match[1]) if match else None


def tune_status(frequency, result, current):
    actual = tuned_frequency(current.stdout)
    # This QCA profile omits channel data from GET_INTERFACE. Keep that absence
    # distinct from a rejected tune or a channel report contradicting the request.
    return dict(frequency=frequency, actual_frequency=actual,
                ok=result.returncode == 0 and actual in (None, frequency),
                error=result.stderr.strip(), epoch=time.time())


def run_iw(*args):
    return subprocess.run(['iw', *args], capture_output=True, text=True, timeout=3)


def radio_phy():
    name = Path('/sys/class/net/wlan0/phy80211').resolve(strict=True).name
    if not re.fullmatch(r'phy[0-9]+', name):
        raise RuntimeError('Cannot identify the wlan0 radio')
    return name


def plan(job, request):
    info = run_iw('phy', radio_phy(), 'info')
    if info.returncode:
        raise RuntimeError('Cannot read the radio frequency list: ' + info.stderr.strip())
    enabled = frequencies(info.stdout)
    requested = request.get('frequency', 0)
    if type(requested) is not int or (requested and requested not in enabled):
        raise ValueError('Requested frequency is not enabled by this radio')
    selected = [requested] if requested else enabled
    bands = request.get('bands', ['2.4', '5', '6'])
    if (not isinstance(bands, list) or not bands
            or any(value not in ('2.4', '5', '6') for value in bands)):
        raise ValueError('Survey bands must be 2.4, 5 or 6')
    selected = [freq for freq in selected if band(freq) in bands]
    if not selected:
        raise ValueError('The radio advertises no enabled Wi-Fi frequencies')
    duration = request.get('duration_seconds', 60 if requested else 120)
    if type(duration) is not int or not 1 <= duration <= 120:
        raise ValueError('Capture duration must be between 1 and 120 seconds')
    bssid = request.get('bssid', 'all')
    if bssid != 'all' and (not isinstance(bssid, str) or not individual(bssid)):
        raise ValueError('Capture BSSID must be an individual MAC address')
    data = {'frequencies_mhz': selected, 'duration_seconds': duration,
            'dwell_seconds': 1, 'passive': True}
    (job / 'scan-plan.json').write_text(json.dumps(data, indent=2))
    return data


def stamp(epoch):
    return datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).strftime('%Y-%m-%d %H:%M:%S')


def ssid_text(value):
    try:
        value = bytes.fromhex(value).decode('utf-8', errors='replace')
    except ValueError:
        return ''
    return ''.join(c if c >= ' ' and c != '\x7f' else ' ' for c in value).strip('\x00')


class Observations:
    def __init__(self, bssid='all'):
        self.bssid = bssid.lower()
        self.aps, self.stations = {}, {}
        self.frames = 0
        self.received_frequencies = set()
        self.eapol = {}
        self.bad_fcs_excluded = 0

    def ap(self, address, epoch, frequency):
        return self.aps.setdefault(address, dict(first=epoch, last=epoch, frequency=frequency,
                    power=-1, beacons=0, data=0, ssid='', privacy='', cipher='', auth='', pmf='unknown'))

    def observe(self, line):
        values = line.rstrip('\r\n').split('\t')
        if len(values) != len(FIELDS):
            return
        v = dict(zip(FIELDS, values))
        try:
            epoch = float(v['frame.time_epoch'])
            subtype = int(v['wlan.fc.type_subtype'], 0)
            freq = int(v['radiotap.channel.freq'] or 0)
            power = int(v['radiotap.dbm_antsignal'] or -1)
        except ValueError:
            return
        if v['radiotap.flags.badfcs'].lower() in ('true', '1') or v['wlan.fcs.status'] == '0':
            self.bad_fcs_excluded += 1
            return
        self.frames += 1
        if freq:
            self.received_frequencies.add(freq)
        ta, ra, bssid = (v[name].lower() for name in ('wlan.ta', 'wlan.ra', 'wlan.bssid'))
        if v['eapol.type'] and individual(bssid) and self.bssid in ('all', bssid):
            client = ra if ta == bssid else ta if ra == bssid else ''
            if individual(client) and client != bssid:
                exchange = self.eapol.setdefault((bssid, client), dict(frames=0, messages=set()))
                exchange['frames'] += 1
                if v['wlan_rsna_eapol.keydes.msgnr'] in ('1', '2', '3', '4'):
                    exchange['messages'].add(int(v['wlan_rsna_eapol.keydes.msgnr']))
        if subtype in (5, 8) and individual(bssid) and self.bssid in ('all', bssid):
            # Adjacent-channel beacons can be heard; prefer the advertised primary.
            primary = v['wlan.ds.current_channel'] or v['wlan.ht.info.primarychannel']
            if primary.isdigit() and 1 <= int(primary) <= 196 and freq < 5950:
                primary = int(primary)
                freq = 2484 if primary == 14 else (2407 if freq < 3000 else 5000) + 5 * primary
            ap = self.ap(bssid, epoch, freq)
            ap.update(last=epoch, frequency=freq, power=power)
            ap['beacons'] += int(subtype == 8)
            name = ssid_text(v['wlan.ssid'])
            if name:
                ap['ssid'] = name
            if v['wlan.rsn.version']:
                akm = v['wlan.rsn.akms.type']
                ap['privacy'] = 'OWE' if akm == '18' else 'WPA3' if akm in ('8', '9') else 'WPA2'
                ap['auth'] = {'1': 'MGT', '2': 'PSK', '8': 'SAE', '9': 'SAE', '18': 'OWE'}.get(akm, '')
                ap['cipher'] = {'2': 'TKIP', '4': 'CCMP', '8': 'GCMP', '9': 'GCMP-256'}.get(v['wlan.rsn.pcs.type'], '')
                ap['pmf'] = ('required' if v['wlan.rsn.capabilities.mfpr'] in ('True', '1')
                             else 'capable' if v['wlan.rsn.capabilities.mfpc'] in ('True', '1')
                             else 'not advertised' if v['wlan.rsn.capabilities.mfpc'] in ('False', '0') else 'unknown')
            elif v['wlan.wfa.ie.wpa.version']:
                ap.update(privacy='WPA', cipher='', auth='')
            else:
                ap.update(privacy='PRIVACY' if v['wlan.fixed.capabilities.privacy'] in ('True', '1') else 'OPN', cipher='', auth='')
        elif subtype >> 4 == 2:
            to_ds = v['wlan.fc.tods'] in ('True', '1')
            from_ds = v['wlan.fc.fromds'] in ('True', '1')
            if to_ds == from_ds:
                return  # Four-address WDS and ad-hoc frames do not establish this association.
            ap_mac, client = (ra, ta) if to_ds else (ta, ra)
            if not individual(ap_mac) or self.bssid not in ('all', ap_mac):
                return
            ap = self.ap(ap_mac, epoch, freq)
            ap['last'] = epoch
            ap['data'] += 1
            if from_ds:
                ap['power'] = power
            if not individual(client) or client == ap_mac:
                return  # Broadcast/multicast destinations are not clients.
            st = self.stations.setdefault(client, dict(first=epoch, last=epoch, frames=0, power=-1, ap=None, probes=set()))
            st.update(last=epoch, ap=ap_mac)
            st['frames'] += 1
            if to_ds:
                st['power'] = power
        elif subtype == 4 and self.bssid == 'all' and individual(ta):
            st = self.stations.setdefault(ta, dict(first=epoch, last=epoch, frames=0, power=-1, ap=None, probes=set()))
            st.update(last=epoch, power=power)
            st['frames'] += 1
            name = ssid_text(v['wlan.ssid'])
            if name:
                st['probes'].add(name)

    def save(self, job):
        temporary = job / 'observations.json.tmp'
        temporary.write_text(json.dumps(self.snapshot(), ensure_ascii=True), encoding='utf-8')
        temporary.replace(job / 'observations.json')
        lines = ['BSSID, First time seen, Last time seen, channel, Speed, Privacy, Cipher, Authentication, Power, # beacons, # IV, LAN IP, ID-length, ESSID, Key, Frequency']
        for address, ap in self.aps.items():
            name = ap['ssid'].encode('utf-8')[:32].decode('utf-8', errors='ignore')
            values = [address, stamp(ap['first']), stamp(ap['last']), channel(ap['frequency']), 0,
                      ap['privacy'], ap['cipher'], ap['auth'], ap['power'], ap['beacons'], ap['data'],
                      '0.0.0.0', len(name.encode('utf-8')), name, '', ap['frequency']]
            lines.append(', '.join(map(str, values)))
        lines += ['', 'Station MAC, First time seen, Last time seen, Power, # packets, BSSID, Probed ESSIDs']
        for address, st in self.stations.items():
            lines.append(', '.join(map(str, [address, stamp(st['first']), stamp(st['last']), st['power'],
                         st['frames'], st['ap'] or '(not associated)', ', '.join(sorted(st['probes']))])))
        target = job / 'capture-01.csv'
        temp = target.with_suffix('.tmp')
        temp.write_text('\n'.join(lines) + '\n', encoding='utf-8')
        temp.replace(target)

    def snapshot(self, redacted=False):
        aps = sorted(self.aps)
        clients = sorted(set(self.stations) | {client for _, client in self.eapol})
        ap_ids = {address: f'Network {i+1}' for i, address in enumerate(aps)}
        client_ids = {address: f'Client {i+1}' for i, address in enumerate(clients)}
        client_counts = {}
        for station in self.stations.values():
            client_counts[station['ap']] = client_counts.get(station['ap'], 0) + 1
        networks = []
        for address in aps:
            ap = self.aps[address]
            networks.append(dict(bssid=ap_ids[address] if redacted else address,
                ssid=ap_ids[address] if redacted else ap['ssid'], frequency=ap['frequency'],
                band=band(ap['frequency']) if ap['frequency'] else 'unknown', channel=channel(ap['frequency']),
                signal_dbm=ap['power'] if ap['power'] != -1 else None, security=ap['privacy'] or 'unknown',
                auth=ap['auth'], pmf=ap['pmf'], beacons=ap['beacons'], data_frames=ap['data'],
                clients=client_counts.get(address, 0)))
        stations = [dict(client=client_ids[address] if redacted else address,
                         ap=ap_ids.get(st['ap'], 'unassociated') if redacted else st['ap'],
                         frames=st['frames'], signal_dbm=st['power'] if st['power'] != -1 else None)
                    for address, st in sorted(self.stations.items())]
        exchanges = [dict(ap=ap_ids.get(ap, 'unidentified AP') if redacted else ap,
                          client=client_ids[client] if redacted else client,
                          frames=record['frames'], message_numbers_observed=sorted(record['messages']))
                     for (ap, client), record in sorted(self.eapol.items())]
        return dict(schema=1, redacted=redacted, frames=self.frames, bad_fcs_excluded=self.bad_fcs_excluded,
                    received_frequencies=sorted(self.received_frequencies), networks=networks,
                    clients=stations, eapol=exchanges,
                    limits='Observed EAPOL message numbers may span retries or separate exchanges. No complete, crackable or cryptographically verified handshake claim. Security/PMF are advertised observations, not attack or protection tests.')


def capture_command(job, bssid='all'):
    command = ['/usr/bin/tshark', '-n', '-l', '-i', 'wlan0', '-B', '64', '-F', 'pcap',
               '-w', str(job / 'capture-01.cap'), '-P', '-T', 'fields', '-E', 'occurrence=f']
    if bssid != 'all':
        if not isinstance(bssid, str) or not re.fullmatch(r'(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}', bssid) or not individual(bssid):
            raise ValueError('Capture BSSID must be an individual MAC address')
        # Filter the saved PCAP too, not only the displayed AP/client table.
        command += ['-f', f'wlan addr1 {bssid} or wlan addr2 {bssid} or wlan addr3 {bssid}']
    for field in FIELDS:
        command += ['-e', field]
    return command


def scan(job, request):
    scan_plan = json.loads((job / 'scan-plan.json').read_text())
    observations = Observations(request.get('bssid', 'all'))
    visits = []
    stopped = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stopped.set())
    command = capture_command(job, request.get('bssid', 'all'))
    pending = queue.Queue(maxsize=32768)
    with (job / 'decoder.log').open('w') as log:
        child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=log, text=True,
                                 encoding='utf-8', errors='replace', start_new_session=True)
        (job / 'decoder.pid').write_text(str(child.pid))

        def read():
            for line in child.stdout:
                pending.put(line)
            pending.put(None)

        reader = threading.Thread(target=read, daemon=True)
        reader.start()
        deadline = time.monotonic() + scan_plan['duration_seconds']
        next_hop = next_save = 0
        index = 0
        decoder_done = False
        try:
            while time.monotonic() < deadline and not stopped.is_set() and not (job / 'finish').exists():
                now = time.monotonic()
                if now >= next_hop:
                    freq = scan_plan['frequencies_mhz'][index % len(scan_plan['frequencies_mhz'])]
                    result = run_iw('dev', 'wlan0', 'set', 'freq', str(freq))
                    current = run_iw('dev', 'wlan0', 'info')
                    visit = tune_status(freq, result, current)
                    visits.append(visit)
                    if visit['ok']:
                        current_band = band(freq)
                        print(f'PHASE:Scanning {current_band} GHz · CH {channel(freq)} · {index % len(scan_plan["frequencies_mhz"]) + 1}/{len(scan_plan["frequencies_mhz"])} frequencies', flush=True)
                    index += 1
                    # A fixed-channel capture needs one tune. Reissuing it while
                    # receiving can stall this stock QCA driver.
                    next_hop = (float('inf') if len(scan_plan['frequencies_mhz']) == 1
                                else time.monotonic() + scan_plan['dwell_seconds'])
                # Bound each batch so a busy radio cannot starve hopping/cancellation.
                for _ in range(4096):
                    try:
                        line = pending.get(timeout=0.05 if pending.empty() else 0)
                    except queue.Empty:
                        break
                    if line is None:
                        decoder_done = True
                        raise RuntimeError('Packet decoder exited; inspect decoder.log')
                    observations.observe(line)
                if now >= next_save:
                    observations.save(job)
                    next_save = now + 2
        finally:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGINT)
            finish = time.monotonic() + 5
            while not decoder_done and time.monotonic() < finish:
                try:
                    line = pending.get(timeout=0.1)
                    if line is None:
                        decoder_done = True
                    else:
                        observations.observe(line)
                except queue.Empty:
                    pass
            try:
                child.wait(timeout=1)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
            observations.save(job)
            for visit in visits:
                visit['received_during_scan'] = visit['frequency'] in observations.received_frequencies
            summary = dict(planned_frequencies=scan_plan['frequencies_mhz'], visits=visits,
                           received_frequencies=sorted(observations.received_frequencies),
                           frames=observations.frames, aps=len(observations.aps),
                           stations=len(observations.stations),
                           associated_stations=sum(s['ap'] is not None for s in observations.stations.values()),
                            cancelled=stopped.is_set(), finished_early=(job / 'finish').exists(), decoder_exit=child.returncode)
            (job / 'scan-summary.json').write_text(json.dumps(summary, indent=2))
            (job / 'decoder.pid').unlink(missing_ok=True)
    if not any(v['ok'] for v in visits):
        raise RuntimeError('No requested frequency tune was accepted')


def report_html(report):
    def table(rows, columns):
        head = ''.join('<th>' + html.escape(label) + '</th>' for _, label in columns)
        body = ''.join('<tr>' + ''.join('<td>' + html.escape(str(row.get(key, ''))) + '</td>'
                                      for key, _ in columns) + '</tr>' for row in rows)
        return '<table><thead><tr>' + head + '</tr></thead><tbody>' + body + '</tbody></table>' if rows else '<p>None observed.</p>'
    networks = table(report['networks'], [('ssid', 'Network'), ('bssid', 'AP'), ('band', 'GHz'),
                    ('channel', 'Channel'), ('signal_dbm', 'dBm'), ('security', 'Advertised security'),
                    ('pmf', 'Advertised PMF'), ('clients', 'Clients'), ('data_frames', 'Data frames')])
    stations = table(report['clients'], [('client', 'Client'), ('ap', 'Observed AP'), ('frames', 'Frames'), ('signal_dbm', 'dBm')])
    eapol = table(report['eapol'], [('ap', 'AP'), ('client', 'Client'), ('frames', 'EAPOL frames'),
                                  ('message_numbers_observed', 'Message numbers seen')])
    label = 'Identifiers redacted' if report['redacted'] else 'PRIVATE — contains network identifiers'
    coverage = ', '.join(map(str, report['received_frequencies'])) or 'none'
    return f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Internal Wi-Fi capture report</title><style>
body{{font:16px system-ui;background:#101923;color:#e8f0f7;margin:0;padding:28px;max-width:1200px}}h1{{font-size:32px}}h2{{margin-top:32px}}
.status{{color:#7be1c7}}.cards{{display:flex;gap:16px;flex-wrap:wrap}}.card{{background:#1b2b3b;padding:18px;border-radius:12px;min-width:140px}}
strong{{font-size:26px}}table{{border-collapse:collapse;width:100%;font-size:14px}}th,td{{padding:10px;text-align:left;border-bottom:1px solid #344454;overflow-wrap:anywhere}}
th{{color:#95d5f3}}.scroll{{overflow:auto}}a{{color:#95d5f3}}p{{line-height:1.6}}.limits{{border-left:4px solid #e9b56a;padding:12px;background:#1b2b3b}}</style>
<p class="status">ONEPLUS INTERNAL WI-FI · CAPTURE ANALYSIS</p><h1>What the radio observed</h1><p>{label}</p>
<div class="cards"><div class="card"><strong>{report['frames']}</strong><br>decoded Wi-Fi frames</div>
<div class="card"><strong>{len(report['networks'])}</strong><br>observed networks</div>
<div class="card"><strong>{len(report['clients'])}</strong><br>observed clients</div>
<div class="card"><strong>{sum(e['frames'] for e in report['eapol'])}</strong><br>EAPOL frames</div></div>
<p>Received radiotap frequencies (MHz): {coverage}. This is reception evidence, not a channel-utilization measurement.</p>
<p>Analysis packet limit reached: {report.get('packet_limit_reached', False)}. Bad-FCS frames are excluded from observations.</p>
<h2>Networks</h2><div class="scroll">{networks}</div><h2>Clients</h2><div class="scroll">{stations}</div>
<h2>Authentication traffic</h2><div class="scroll">{eapol}</div><p class="limits">{html.escape(report['limits'])}</p>
<p>No packets were transmitted by this analysis. <a href="capture-report.json">Download report JSON</a>.</p></html>'''


def analyze_capture(pcap, output, tshark='/usr/bin/tshark', redacted=True):
    pcap, output = Path(pcap), Path(output)
    if not pcap.is_file() or pcap.stat().st_size > 256 * 1024 * 1024:
        raise ValueError('Choose an existing PCAP no larger than 256 MiB')
    if output.exists():
        raise ValueError('Choose a new report directory; existing files are never overwritten')
    # /proc/1/root deliberately traverses out of NetHunter's chroot. Resolving
    # that magic link textually strips its namespace traversal before TShark opens it.
    command = [str(tshark), '-n', '-r', str(pcap.absolute()), '-c', '100000', '-T', 'fields', '-E', 'occurrence=f']
    for field in FIELDS:
        command += ['-e', field]
    decoded = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
    if decoded.returncode:
        raise RuntimeError('TShark could not decode this capture: ' + decoded.stderr[-2000:])
    observations = Observations()
    lines = decoded.stdout.splitlines()
    for line in lines:
        observations.observe(line)
    report = observations.snapshot(redacted=redacted)
    report['packet_limit_reached'] = len(lines) >= 100000
    output.mkdir(mode=0o700, parents=False)
    (output / 'capture-report.json').write_text(json.dumps(report, indent=2, ensure_ascii=True), encoding='utf-8')
    (output / 'index.html').write_text(report_html(report), encoding='utf-8')
    return report


def main():
    if len(sys.argv) > 1 and sys.argv[1] == 'analyze':
        parser = argparse.ArgumentParser(description='Offline Wi-Fi capture report; no device/root or transmission required')
        parser.add_argument('action', choices=['analyze'])
        parser.add_argument('--pcap', required=True)
        parser.add_argument('--output', required=True)
        parser.add_argument('--tshark', default='/usr/bin/tshark')
        parser.add_argument('--private', action='store_true', help='Keep real network/client identifiers in the report')
        args = parser.parse_args()
        report = analyze_capture(args.pcap, args.output, args.tshark, not args.private)
        print(json.dumps(dict(frames=report['frames'], networks=len(report['networks']),
                             clients=len(report['clients']), redacted=report['redacted'], output=args.output)))
        return
    action, directory = sys.argv[1:]
    job = Path(directory)
    request = json.loads((job / 'request.json').read_text())
    if action == 'plan':
        plan(job, request)
    elif action == 'scan':
        scan(job, request)
    else:
        raise ValueError('Unknown passive scanner action')


if __name__ == '__main__':
    main()
