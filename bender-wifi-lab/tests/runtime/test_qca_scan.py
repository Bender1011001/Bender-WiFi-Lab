"""Passive scanner regression tests. Synthetic headers only; no radio operations."""
import importlib.util
import json
from pathlib import Path
import tempfile
import shutil
import struct
import unittest
from unittest.mock import patch

ASSET = Path(__file__).resolve().parents[2] / 'runtime/qca-scan.py'
SPEC = importlib.util.spec_from_file_location('qca_scan', ASSET)
S = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(S)
AP = '02:11:22:33:44:50'
CLIENT = '02:11:22:33:44:60'


def frame(subtype, ta=AP, ra=CLIENT, to_ds='0', from_ds='0', **extra):
    values = dict(zip(S.FIELDS, [''] * len(S.FIELDS)))
    values.update({'frame.time_epoch': '1794225601', 'wlan.fc.type_subtype': hex(subtype),
                   'wlan.fc.tods': to_ds, 'wlan.fc.fromds': from_ds, 'wlan.bssid': AP,
                   'wlan.ta': ta, 'wlan.ra': ra, 'radiotap.channel.freq': '5180',
                   'radiotap.dbm_antsignal': '-44', 'radiotap.flags.badfcs': 'False'})
    values.update(extra)
    return '\t'.join(values[f] for f in S.FIELDS) + '\n'


class ScanTests(unittest.TestCase):
    def test_survey_band_selection_uses_only_enabled_frequencies(self):
        info = type('Result', (), dict(returncode=0, stdout='* 2437 MHz [6]\n* 5180 MHz [36]\n* 5955 MHz [1]', stderr=''))()
        with tempfile.TemporaryDirectory() as directory, patch.object(S, 'run_iw', return_value=info), patch.object(S, 'radio_phy', return_value='phy1'):
            self.assertEqual([5955], S.plan(Path(directory), dict(bands=['6']))['frequencies_mhz'])
            for bands in ([], ['7'], '5', [None]):
                with self.assertRaises(ValueError): S.plan(Path(directory), dict(bands=bands))

    def test_security_pmf_and_eapol_are_observations_not_verified_handshakes(self):
        observed = S.Observations()
        observed.observe(frame(8, **{'wlan.ssid': 'Lab, <script>'.encode().hex(), 'wlan.rsn.version': '1',
                                    'wlan.rsn.akms.type': '18', 'wlan.rsn.capabilities.mfpr': '1'}))
        for number in ('1', '2', '3', '4', '4'):
            observed.observe(frame(0x28, from_ds='1', **{'eapol.type': '3', 'wlan_rsna_eapol.keydes.msgnr': number}))
        private = observed.snapshot()
        self.assertEqual('OWE', private['networks'][0]['security'])
        self.assertEqual('required', private['networks'][0]['pmf'])
        self.assertEqual([1, 2, 3, 4], private['eapol'][0]['message_numbers_observed'])
        self.assertEqual(5, private['eapol'][0]['frames'])
        self.assertIn('No complete', private['limits'])
        self.assertNotIn('<script>', S.report_html(private))
        public = observed.snapshot(redacted=True)
        for identifier in (AP, CLIENT, 'Lab, <script>'):
            self.assertNotIn(identifier, json.dumps(public))
            self.assertNotIn(identifier, S.report_html(public))
        with tempfile.TemporaryDirectory() as directory:
            observed.save(Path(directory))
            saved = json.loads((Path(directory) / 'observations.json').read_text())
            self.assertEqual('Lab, <script>', saved['networks'][0]['ssid'])

    def test_report_decoder_failure_never_creates_success_report(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'fixture.cap'; source.write_bytes(b'bad pcap')
            output = Path(directory) / 'report'
            failure = type('Result', (), dict(returncode=2, stdout='', stderr='bad format'))()
            with patch.object(S.subprocess, 'run', return_value=failure):
                with self.assertRaisesRegex(RuntimeError, 'could not decode'): S.analyze_capture(source, output)
            self.assertFalse(output.exists())

    def test_offline_report_uses_existing_pcap_and_no_interface(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'fixture.cap'; source.write_bytes(b'fixture')
            output = Path(directory) / 'report'
            decoded = type('Result', (), dict(returncode=0, stdout=frame(8, **{'wlan.ssid': '4c6162'}), stderr=''))()
            with patch.object(S.subprocess, 'run', return_value=decoded) as run:
                report = S.analyze_capture(source, output)
                command = run.call_args.args[0]
                self.assertIn('-r', command); self.assertNotIn('-i', command)
                self.assertTrue(report['redacted'])
                self.assertTrue((output / 'index.html').is_file())
                with self.assertRaises(ValueError): S.analyze_capture(source, output)

    def test_synthetic_radiotap_pcap_with_real_tshark(self):
        tshark = shutil.which('tshark')
        installed = Path('C:/Program Files/Wireshark/tshark.exe')
        if not tshark and installed.exists(): tshark = str(installed)
        if not tshark: self.skipTest('Real TShark decoder is not installed')
        # Synthetic over-the-air-format headers, never transmitted.
        ap = bytes.fromhex(AP.replace(':', '')); client = bytes.fromhex(CLIENT.replace(':', ''))
        rt = struct.pack('<BBHIHHb', 0, 0, 13, 0x28, 2437, 0x00a0, -44)
        header = lambda fc, dest, src: struct.pack('<HH', fc, 0) + dest + src + ap + b'\x10\x00'
        rsn = b'\x01\x00\x00\x0f\xac\x04\x01\x00\x00\x0f\xac\x04\x01\x00\x00\x0f\xac\x02\xc0\x00'
        name = b'Synthetic, lab'
        beacon = rt + header(0x80, b'\xff'*6, ap) + struct.pack('<QHH', 0, 100, 0x11) + bytes([0,len(name)]) + name + b'\x03\x01\x06' + bytes([48,len(rsn)]) + rsn
        packets = [beacon]
        for info, fc, dest, src in ((0x008a, 0x0208, client, ap), (0x010a, 0x0108, ap, client)):
            key = b'\x02' + struct.pack('>HHQ', info, 16, 1) + b'\x11'*32 + b'\x00'*16 + b'\x00'*8 + b'\x00'*8 + b'\x22'*16 + b'\x00\x00'
            eapol = struct.pack('>BBH', 2, 3, len(key)) + key
            packets.append(rt + header(fc, dest, src) + b'\xaa\xaa\x03\x00\x00\x00\x88\x8e' + eapol)
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'synthetic.cap'
            with source.open('wb') as stream:
                stream.write(struct.pack('<IHHIIII', 0xa1b2c3d4, 2, 4, 0, 0, 65535, 127))
                for index, packet in enumerate(packets):
                    stream.write(struct.pack('<IIII', 1700000000+index, 0, len(packet), len(packet)) + packet)
            report = S.analyze_capture(source, Path(directory) / 'report', tshark, redacted=False)
            self.assertEqual(3, report['frames'])
            self.assertEqual([2437], report['received_frequencies'])
            self.assertEqual('Synthetic, lab', report['networks'][0]['ssid'])
            self.assertEqual('required', report['networks'][0]['pmf'])
            self.assertEqual([1,2], report['eapol'][0]['message_numbers_observed'])

    def test_report_preserves_host_root_traversal_for_decoder(self):
        # Model the Linux magic-link normalization on all test hosts. A separate
        # local Linux check covers actual proc-root path semantics, never a phone.
        from pathlib import PurePosixPath
        magic = '/proc/1/root/data/user/0/app/files/capture.pcap'
        class HostCapture:
            def is_file(self): return True
            def stat(self): return type('Stat', (), dict(st_size=32))()
            def absolute(self): return PurePosixPath(magic)
            def resolve(self): return PurePosixPath('/data/user/0/app/files/capture.pcap')
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'report'
            decoded = type('Result', (), dict(returncode=0, stdout='', stderr=''))()
            with patch.object(S, 'Path', side_effect=lambda path: HostCapture() if path == magic else Path(path)), patch.object(S.subprocess, 'run', return_value=decoded) as run:
                S.analyze_capture(magic, output)
            command = run.call_args.args[0]
            self.assertEqual(magic, command[command.index('-r')+1])
            self.assertTrue((output / 'index.html').is_file())

    def test_current_radio_is_discovered_after_driver_reload(self):
        with patch.object(S.Path, 'resolve', return_value=Path('/sys/class/ieee80211/phy268')):
            self.assertEqual('phy268', S.radio_phy())
        with patch.object(S.Path, 'resolve', return_value=Path('/sys/class/ieee80211/unexpected')):
            with self.assertRaises(RuntimeError): S.radio_phy()

    def test_capture_filter_limits_saved_packets_to_selected_ap(self):
        command = S.capture_command(Path('/tmp/session'), AP)
        self.assertEqual(f'wlan addr1 {AP} or wlan addr2 {AP} or wlan addr3 {AP}', command[command.index('-f')+1])
        self.assertNotIn('-f', S.capture_command(Path('/tmp/session')))
        for value in ('ff:ff:ff:ff:ff:ff', AP+' or ip', None):
            with self.assertRaises(ValueError): S.capture_command(Path('/tmp/session'), value)

    def test_terminal_duration_is_bounded_without_changing_app_defaults(self):
        with tempfile.TemporaryDirectory() as d:
            info = type('Result', (), dict(returncode=0, stdout='* 2437 MHz [6]', stderr=''))()
            with patch.object(S, 'run_iw', return_value=info) as iw, patch.object(S, 'radio_phy', return_value='phy268'):
                self.assertEqual(60, S.plan(Path(d), dict(frequency=2437))['duration_seconds'])
                iw.assert_called_with('phy', 'phy268', 'info')
                self.assertEqual(30, S.plan(Path(d), dict(frequency=2437, duration_seconds=30, bssid=AP))['duration_seconds'])
                for duration in (0, 121, True, '30'):
                    with self.assertRaises(ValueError): S.plan(Path(d), dict(frequency=2437, duration_seconds=duration))

    def test_frequency_plan_uses_enabled_channels_across_three_bands(self):
        info = '* 2412.0 MHz [1] (20 dBm)\n* 2467.0 MHz [12] (disabled)\n* 5260 MHz [52] (no IR, radar detection)\n* 5955.0 MHz [1] (no IR)\n'
        self.assertEqual([2412, 5260, 5955], S.frequencies(info))
        self.assertEqual(1, S.channel(2412))
        self.assertEqual(1, S.channel(5955))
        self.assertEqual(233, S.channel(7115))

    def test_tuned_frequency_accepts_integer_and_decimal_iw_formats(self):
        self.assertEqual(2412, S.tuned_frequency('channel 1 (2412 MHz), width: 20 MHz'))
        self.assertEqual(5955, S.tuned_frequency('channel 1 (5955.0 MHz), width: 20 MHz'))
        self.assertIsNone(S.tuned_frequency('type managed'))

    def test_special_6ghz_channel_and_invalid_frequency_grid(self):
        info = '* 5935 MHz [2] (no IR)\n* 2438 MHz [6]\n* 5956 MHz [1]\n'
        self.assertEqual([5935], S.frequencies(info))
        self.assertEqual(2, S.channel(5935))
        self.assertEqual('6', S.band(5935))
        for frequency in (True, 2437.0, '2437', 2438, 2490, 5000, 5896, 5930, 5956, 7116):
            with self.subTest(frequency=frequency):
                self.assertEqual(-1, S.channel(frequency))

    def test_special_6ghz_frequency_is_selected_by_6ghz_survey(self):
        result = type('Result', (), dict(returncode=0, stdout='* 5935 MHz [2] (no IR)\n* 5220 MHz [44]', stderr=''))()
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(S, 'run_iw', return_value=result), patch.object(S, 'radio_phy', return_value='phy268'):
                selected = S.plan(Path(directory), dict(frequency=0, bands=['6']))
                self.assertEqual([5935], selected['frequencies_mhz'])

    def test_missing_channel_report_is_distinct_from_rejected_or_mismatched_tune(self):
        accepted = type('Result', (), dict(returncode=0, stderr=''))()
        rejected = type('Result', (), dict(returncode=1, stderr='Invalid argument'))()
        absent = type('Report', (), dict(stdout='type monitor'))()
        mismatch = type('Report', (), dict(stdout='channel 6 (2437 MHz)'))()
        visit = S.tune_status(5955, accepted, absent)
        self.assertTrue(visit['ok'])
        self.assertIsNone(visit['actual_frequency'])
        self.assertFalse(S.tune_status(5955, rejected, absent)['ok'])
        self.assertFalse(S.tune_status(5955, accepted, mismatch)['ok'])

    def test_disabled_target_rejected_before_scan_plan_is_written(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            result = type('Result', (), dict(returncode=0, stdout='* 2412 MHz [1]\n* 2467 MHz [12] (disabled)', stderr=''))()
            with patch.object(S, 'run_iw', return_value=result), patch.object(S, 'radio_phy', return_value='phy268'):
                with self.assertRaises(ValueError):
                    S.plan(path, {'frequency': 2467})
                self.assertFalse((path / 'scan-plan.json').exists())
                self.assertEqual([2412], S.plan(path, {'frequency': 0})['frequencies_mhz'])

    def test_client_to_ap_and_ap_to_client_data_establish_association(self):
        observations = S.Observations()
        observations.observe(frame(0x28, ta=CLIENT, ra=AP, to_ds='1'))
        observations.observe(frame(0x28, from_ds='1'))
        self.assertEqual(AP, observations.stations[CLIENT]['ap'])
        self.assertEqual(2, observations.stations[CLIENT]['frames'])
        self.assertEqual(-44, observations.stations[CLIENT]['power'])

    def test_null_and_qos_null_frames_are_client_evidence(self):
        for subtype in (0x24, 0x2c):
            with self.subTest(subtype=subtype):
                observations = S.Observations()
                observations.observe(frame(subtype, ta=CLIENT, ra=AP, to_ds='1'))
                self.assertEqual(AP, observations.stations[CLIENT]['ap'])

    def test_group_destinations_wds_and_adhoc_do_not_create_clients(self):
        observations = S.Observations()
        observations.observe(frame(0x28, ra='ff:ff:ff:ff:ff:ff', from_ds='1'))
        observations.observe(frame(0x28, to_ds='1', from_ds='1'))
        observations.observe(frame(0x28))
        self.assertEqual({}, observations.stations)

    def test_probe_does_not_erase_observed_ap_association(self):
        observations = S.Observations()
        observations.observe(frame(0x28, from_ds='1'))
        observations.observe(frame(4, ta=CLIENT, ra='ff:ff:ff:ff:ff:ff', **{'wlan.ssid': '4c6162'}))
        self.assertEqual(AP, observations.stations[CLIENT]['ap'])
        self.assertEqual({'Lab'}, observations.stations[CLIENT]['probes'])

    def test_roaming_updates_current_association(self):
        observations = S.Observations()
        observations.observe(frame(0x28, from_ds='1'))
        other = '02:11:22:33:44:52'
        observations.observe(frame(0x28, ta=other, from_ds='1', **{'wlan.bssid': other}))
        self.assertEqual(other, observations.stations[CLIENT]['ap'])
        counts = {row['bssid']: row['clients'] for row in observations.snapshot()['networks']}
        self.assertEqual(0, counts[AP])
        self.assertEqual(1, counts[other])

    def test_bad_fcs_and_unrelated_ap_are_filtered(self):
        observations = S.Observations('02:11:22:33:44:52')
        observations.observe(frame(0x28, from_ds='1'))
        self.assertFalse(observations.stations)
        observations = S.Observations()
        observations.observe(frame(0x28, from_ds='1', **{'radiotap.flags.badfcs': 'True'}))
        self.assertFalse(observations.stations)

    def test_beacon_after_data_populates_existing_ap_and_retains_client(self):
        observations = S.Observations()
        observations.observe(frame(0x28, from_ds='1'))
        observations.observe(frame(8, **{'wlan.ssid': '436166c3a92c20726f6f6d', 'radiotap.channel.freq': '5955',
                                   'wlan.rsn.version': '1', 'wlan.rsn.akms.type': '8', 'wlan.rsn.pcs.type': '4'}))
        self.assertEqual('Café, room', observations.aps[AP]['ssid'])
        self.assertEqual(5955, observations.aps[AP]['frequency'])
        self.assertEqual('WPA3', observations.aps[AP]['privacy'])
        with tempfile.TemporaryDirectory() as directory:
            observations.save(Path(directory))
            csv = (Path(directory) / 'capture-01.csv').read_text(encoding='utf-8')
            self.assertIn('11, Café, room, , 5955', csv)
            self.assertIn(CLIENT, csv)
            self.assertIn(AP, csv)


if __name__ == '__main__':
    unittest.main()
