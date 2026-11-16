"""Host-only tests. Never open a netlink socket or transmit a frame."""
import importlib.util
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch, MagicMock

ASSETS = Path(__file__).resolve().parents[2] / 'runtime'
sys.path.insert(0, str(ASSETS))
import identity_guard
SPEC = importlib.util.spec_from_file_location('backend', ASSETS / 'qca-backend.py')
B = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(B)
AP = '02:11:22:33:44:50'
CLIENT = '02:11:22:33:44:60'
EPOCH = 1794225601.0  # 2026-11-09T12:00:01Z


class BackendTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.capture = self.base / 'capture_1'
        self.capture.mkdir()
        self.csv = self.capture / 'capture-01.csv'
        self.request = dict(channel=6, frequency=2437, ap=AP, client=CLIENT,
                            capture_session='capture_1', authorized_owned_hardware=True)
        self.write_capture(2437)
        self.job = self.base / 'lab_1'
        self.job.mkdir()
        (self.job / 'owner.pid').write_text(str(os.getpid()))
        (self.job / 'cancel.path').write_text(str(self.job / 'android-cancel'))
        (self.job / 'device.json').write_text(json.dumps({'auth_deauth_random_ta': True}))
        self.interface_fields = {B.C['NL80211_ATTR_WIPHY']: B.u32(0)}
        self.phy_info = 'Wiphy phy0\n\tBand 1:\n\t\tFrequencies:\n' + ''.join(
            f'\t\t\t* {frequency} MHz [{channel}] (20.0 dBm)\n'
            for frequency, channel in [(2412, 1), (2437, 6), (2462, 11), (2484, 14),
                                       (5180, 36), (5935, 2), (5955, 1), (6455, 101), (7115, 233)])
        iw_patch = patch.object(B.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout=self.phy_info))
        self.iw = iw_patch.start()
        self.addCleanup(iw_patch.stop)
        # os.kill(pid, 0) is an existence check on Android but a console signal
        # on Windows. Host tests model owner presence without host signaling.
        owner_patch = patch.object(B.os, 'kill', return_value=None)
        owner_patch.start()
        self.addCleanup(owner_patch.stop)
        # Explicit passing privileged facts; no Android commands on the host.
        self.private_snapshot = {
            'android_context': True,
            'settings': {'airplane_mode_on': '1', 'mobile_data': '0', 'bluetooth_on': '0'},
            'wlan_mac': '02:11:22:33:44:55',
            'links': ['1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536', '2: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500'],
            'addresses': ['1: lo inet 127.0.0.1/8 scope host lo'],
            'routes_v4': ['127.0.0.0/8 dev lo table local proto kernel scope host src 127.0.0.1'],
            'routes_v6': []}
        privacy_patch = patch.object(identity_guard, 'inspect', side_effect=lambda: identity_guard.evaluate(self.private_snapshot))
        self.private_inspect = privacy_patch.start()
        self.addCleanup(privacy_patch.stop)


    def write_capture(self, frequency):
        channel = B.frequency_channel(frequency)
        self.request.update(frequency=frequency, channel=channel)
        self.csv.write_text('BSSID, First time seen, Last time seen, channel, Speed, Privacy, Cipher, Authentication, Power, # beacons, # IV, LAN IP, ID-length, ESSID, Key, Frequency\n'
                            f'{AP}, 2026-11-09 12:00:00, 2026-11-09 12:00:01, {channel}, 54, WPA2, CCMP, PSK, -40, 5, 3, 0, 3, Lab, , {frequency}\n'
                            f'\nStation MAC, header\n{CLIENT}, 2026-11-09 12:00:00, 2026-11-09 12:00:01, -51, 5, {AP}, \n')
        (self.capture / 'request.json').write_text(json.dumps(dict(bssid=AP, frequency=frequency, channel=channel)))

    def tearDown(self):
        self.tmp.cleanup()

    def test_privacy_operations_export_profile_and_post_identity(self):
        for action, function, state in [('prepare', 'prepare', 'prepared'), ('privacy_restore', 'restore', 'restored'), ('privacy_status', 'status', 'pending')]:
            with self.subTest(action=action):
                (self.job / 'request.json').write_text('{}')
                report = {'schema_version':1, 'state':state, 'pending':state != 'restored', 'active':state == 'prepared', 'restored':state == 'restored'}
                # Returning to normal connectivity may correctly block the TX gate.
                with patch.object(B.privacy_profile, function, return_value=report) as operation, patch.object(B, 'inspect_identity', return_value={'allowed':False}), patch.object(sys, 'argv', ['backend', action, str(self.job)]), patch('builtins.print'):
                    self.assertEqual(0, B.main())
                operation.assert_called_once_with(self.job)
                self.assertEqual(report, json.loads((self.job / 'privacy-state.json').read_text()))
                self.assertFalse(json.loads((self.job / 'identity-state.json').read_text())['allowed'])
                self.assertTrue(json.loads((self.job / 'result.json').read_text())['ok'])

    def test_privacy_restore_without_journal_succeeds_without_restoration_claim(self):
        (self.job / 'request.json').write_text('{}')
        report = {'state':'idle','pending':False,'active':False,'restored':False}
        with patch.object(B.privacy_profile, 'restore', return_value=report), patch.object(B, 'inspect_identity', return_value={'allowed':False}), patch.object(sys, 'argv', ['backend', 'privacy_restore', str(self.job)]), patch('builtins.print'):
            self.assertEqual(0, B.main())
        result = json.loads((self.job / 'result.json').read_text())
        self.assertTrue(result['ok'])
        self.assertFalse(result['restored'])

    def test_blocked_privacy_preparation_is_not_retried(self):
        (self.job / 'request.json').write_text('{}')
        with patch.object(B.privacy_profile, 'prepare', return_value={'state':'blocked','pending':True}) as operation, patch.object(B, 'inspect_identity', return_value={'allowed':False}), patch.object(sys, 'argv', ['backend', 'prepare', str(self.job)]), patch('builtins.print'):
            self.assertEqual(1, B.main())
        operation.assert_called_once()
        self.assertFalse(json.loads((self.job / 'result.json').read_text())['ok'])

    def test_privacy_cancel_before_mutation_prevents_executor(self):
        (self.job / 'request.json').write_text('{}')
        (self.job / 'cancel').touch()
        with patch.object(B.privacy_profile, 'prepare') as operation, patch.object(sys, 'argv', ['backend', 'prepare', str(self.job)]), patch('builtins.print'):
            self.assertEqual(1, B.main())
        operation.assert_not_called()

    def test_slow_identity_check_does_not_expire_operator_selection(self):
        clock = [EPOCH]
        def privacy_check():
            clock[0] += 121
            return identity_guard.evaluate(self.private_snapshot)
        self.private_inspect.side_effect = privacy_check
        nl = MagicMock(); nl.command.return_value = []
        with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', side_effect=lambda: clock[0]), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl):
            self.assertEqual(1, B.single_frame(self.job, self.request)['userspace_submissions'])
        nl.command.assert_called_once()
        self.assertEqual(2, self.private_inspect.call_count)

    def test_identity_failure_cannot_be_overridden_by_request_boolean(self):
        self.private_snapshot['settings']['airplane_mode_on'] = '0'
        self.request['private_context_verified'] = True
        nl = MagicMock()
        with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl):
            with self.assertRaisesRegex(RuntimeError, 'identity preflight blocked'):
                B.single_frame(self.job, self.request)
        nl.command.assert_not_called()
        self.assertFalse((self.job / 'attempt.json').exists())
        self.assertFalse(json.loads((self.job / 'identity-state.json').read_text())['allowed'])

    def test_identity_change_after_intent_prevents_submission(self):
        nl = MagicMock()
        real_write = Path.write_text
        def change_after_intent(path, *args, **kwargs):
            result = real_write(path, *args, **kwargs)
            if path.name == 'attempt.json':
                self.private_snapshot['settings']['bluetooth_on'] = '1'
            return result
        with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl), patch.object(Path, 'write_text', new=change_after_intent):
            with self.assertRaisesRegex(RuntimeError, 'identity preflight blocked'):
                B.single_frame(self.job, self.request)
        nl.command.assert_not_called()
        self.assertTrue((self.job / 'attempt.json').exists())
        self.assertTrue((self.base / 'last-attempt.json').exists())

    def test_valid_explicit_selection(self):
        self.assertEqual((AP, CLIENT), B.validate_target(self.request))

    def test_frequency_channel_requires_explicit_band_and_grid(self):
        for frequency, channel in [(2412, 1), (2437, 6), (2472, 13), (2484, 14), (5180, 36),
                                   (5895, 179), (5935, 2), (5955, 1), (6455, 101), (7115, 233)]:
            with self.subTest(frequency=frequency):
                self.assertEqual(channel, B.frequency_channel(frequency))
                B.validate_request(dict(frequency=frequency, channel=channel), lab=True)
        for invalid in [None, True, 2437.0, '2437', 0, 2407, 2413, 2477, 5000, 5900, 5930, 5950, 7116]:
            with self.subTest(frequency=invalid), self.assertRaises(ValueError):
                B.frequency_channel(invalid)
        for channel in [None, True, 6.0, '6', 0, 11]:
            with self.subTest(channel=channel), self.assertRaises(ValueError):
                B.validate_request(dict(frequency=2437, channel=channel), lab=True)
        with self.assertRaises(ValueError):
            B.validate_request(dict(channel=6), lab=True)

    def test_capture_accepts_microsecond_and_nanosecond_pcap(self):
        summary = dict(stations=1, frames=5, planned_frequencies=[2437],
                       visits=[dict(frequency=2437, ok=True)], received_frequencies=[2437])
        (self.capture / 'scan-summary.json').write_text(json.dumps(summary))
        mode = self.base / 'mode'
        mode.write_text('0\n')
        for endian, magic in [('<', 0xa1b2c3d4), ('>', 0xa1b2c3d4),
                              ('<', 0xa1b23c4d), ('>', 0xa1b23c4d)]:
            with self.subTest(endian=endian, magic=hex(magic)):
                (self.capture / 'capture-01.cap').write_bytes(
                    struct.pack(endian + 'IHHIIII', magic, 2, 4, 0, 0, 262144, 127))
                with patch.object(B, 'MODE', mode):
                    result = B.capture_result(self.capture)
                self.assertEqual(1, result['associated_stations'])
                self.assertEqual([2437], result['frequencies_visited'])
                self.assertTrue(result['managed_mode'])

    def test_capture_rejects_truncated_and_unknown_headers(self):
        for header in [b'', b'\xd4\xc3\xb2\xa1', b'\x4d\x3c\xb2\xa1', b'\x00' * 24]:
            with self.subTest(header=header):
                (self.capture / 'capture-01.cap').write_bytes(header)
                with self.assertRaisesRegex(ValueError, 'No valid pcap header'):
                    B.capture_result(self.capture)

    def test_saved_selection_does_not_require_capture_files_or_timestamps(self):
        self.csv.unlink()
        self.request.update(capture_session='imported:old', client='02:11:22:33:44:62')
        with patch.object(B, 'observations', side_effect=AssertionError('No provenance lookup')):
            self.assertEqual((AP, self.request['client']), B.validate_target(self.request))

    def test_no_ownership_checkbox_but_only_well_formed_unicast_targets(self):
        self.request.pop('authorized_owned_hardware')
        self.assertEqual((AP, CLIENT), B.validate_target(self.request))
        for value in ['ff:ff:ff:ff:ff:ff', '01:11:22:33:44:55', '00:00:00:00:00:00', 'x;touch /tmp/a']:
            with self.assertRaises(ValueError): B.mac(value)

    def test_wrong_channel_and_equal_targets_rejected_before_netlink(self):
        with patch.object(B, 'Netlink') as netlink:
            self.request['channel'] = 11
            with self.assertRaises(ValueError): B.single_frame(self.job, self.request)
            self.request.update(channel=6, client=AP)
            with self.assertRaises(ValueError): B.single_frame(self.job, self.request)
            netlink.assert_not_called()

    def test_selected_bssid_and_explicit_frequency_remain_consistent(self):
        self.request['bssid']='02:11:22:33:44:70'
        with self.assertRaisesRegex(ValueError, 'BSSID'): B.validate_target(self.request)
        self.request.pop('bssid');self.request.pop('frequency')
        with self.assertRaises(ValueError): B.validate_target(self.request)

    def test_single_submission_and_matching_status(self):
        nl = MagicMock()
        nl.command.return_value = [(1, b'\x00' * 4 + B.a('COOKIE', struct.pack('=Q', 256)), 1)]
        frame = struct.pack('<HH', 0xc0, 0) + bytes.fromhex(CLIENT.replace(':', '')) + bytes.fromhex(AP.replace(':', '')) * 2 + struct.pack('<HH', 0, 3)
        nl.tx_status.return_value = dict(cookie=256, ack=True, ifindex=47, frame_hex=frame.hex())
        with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl):
            result = B.single_frame(self.job, self.request)
            self.assertTrue(result['matching_ack'])
            nl.command.assert_called_once()
            self.assertEqual('FRAME', nl.command.call_args.args[0])
            self.assertTrue((self.job / 'attempt.json').exists())
            with self.assertRaises(RuntimeError): B.single_frame(self.job, self.request)
            self.assertEqual(1, nl.command.call_count)

    def test_selected_observed_mhz_reaches_frame_attribute_in_each_band(self):
        for frequency in (2412, 2462, 2484, 5180, 5935, 5955, 6455, 7115):
            with self.subTest(frequency=frequency):
                self.write_capture(frequency)
                (self.base / 'last-attempt.json').unlink(missing_ok=True)
                nl = MagicMock()
                nl.command.return_value = [(1, b'\x00' * 4 + B.a('COOKIE', struct.pack('=Q', 256)), 1)]
                nl.tx_status.return_value = None
                with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl):
                    result = B.single_frame(self.job, self.request)
                nl.command.assert_called_once()
                name, payload = nl.command.call_args.args
                self.assertEqual('FRAME', name)
                self.assertEqual(frequency, B.number(B.attrs(payload)[B.C['NL80211_ATTR_WIPHY_FREQ']]))
                attempt = json.loads((self.job / 'attempt.json').read_text())
                self.assertEqual(frequency, attempt['frequency'])
                self.assertEqual(frequency, attempt['frequency_permission']['frequency'])
                self.assertEqual(frequency, result['frequency'])
                self.assertEqual(['iw', 'phy', 'phy0', 'info'], self.iw.call_args.args[0])
                self.assertEqual('C', self.iw.call_args.kwargs['env']['LC_ALL'])

    def test_driver_restrictions_block_before_intent_or_frame(self):
        for flags in ['(disabled)', '(no IR)', '(no-IR)', '(NO_IR)', '(passive scanning)',
                      '(radar detection)', '(no 20MHz)', '\n\t\t\t\tDFS state: available (for 60 sec)']:
            with self.subTest(flags=flags):
                self.iw.return_value.stdout = 'Wiphy phy0\n\tBand 1:\n\t\tFrequencies:\n\t\t\t* 2437 MHz [6] (20.0 dBm) ' + flags + '\n'
                nl = MagicMock()
                with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl):
                    with self.assertRaisesRegex(ValueError, 'transmit-restricted'):
                        B.single_frame(self.job, self.request)
                nl.command.assert_not_called()
                nl.sock.close.assert_called_once()
                self.assertFalse((self.job / 'attempt.json').exists())
                self.assertFalse((self.base / 'last-attempt.json').exists())

    def test_no_ibss_alone_does_not_block_managed_transmit(self):
        self.iw.return_value.stdout = 'Wiphy phy0\n\t* 2437 MHz [6] (20.0 dBm) (no IBSS)\n'
        nl = MagicMock()
        nl.command.return_value = [(1, b'\x00' * 4 + B.a('COOKIE', struct.pack('=Q', 256)), 1)]
        nl.tx_status.return_value = None
        with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl):
            B.single_frame(self.job, self.request)
        nl.command.assert_called_once()
        self.assertEqual('FRAME', nl.command.call_args.args[0])

    def test_missing_ambiguous_or_wrong_channel_driver_entry_blocks(self):
        for rows in ['', '\t* 2412 MHz [1] (20.0 dBm)\n', '\t* 2437 MHz [11] (20.0 dBm)\n',
                     '\t* 2437 MHz [6] (20.0 dBm)\n\t* 2437 MHz [6] (20.0 dBm)\n']:
            with self.subTest(rows=rows):
                self.iw.return_value.stdout = 'Wiphy phy0\n' + rows
                nl = MagicMock()
                with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl):
                    with self.assertRaisesRegex(ValueError, 'absent or ambiguous'):
                        B.single_frame(self.job, self.request)
                nl.command.assert_not_called()
                self.assertFalse((self.job / 'attempt.json').exists())

    def test_driver_query_failure_or_wrong_phy_cannot_submit(self):
        for condition in ('timeout', 'missing_iw', 'exit_failure', 'wrong_phy'):
            with self.subTest(condition=condition):
                self.iw.side_effect = None
                self.iw.return_value = SimpleNamespace(returncode=0, stdout=self.phy_info)
                if condition == 'timeout': self.iw.side_effect = B.subprocess.TimeoutExpired('iw', 8)
                if condition == 'missing_iw': self.iw.side_effect = FileNotFoundError('iw')
                if condition == 'exit_failure': self.iw.return_value.returncode = 1
                if condition == 'wrong_phy': self.iw.return_value.stdout = self.phy_info.replace('Wiphy phy0', 'Wiphy phy1')
                nl = MagicMock()
                with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl):
                    with self.assertRaises(RuntimeError): B.single_frame(self.job, self.request)
                nl.command.assert_not_called()
                nl.sock.close.assert_called_once()
                self.assertFalse((self.job / 'attempt.json').exists())

    def test_missing_current_phy_identity_blocks_before_query(self):
        nl = MagicMock()
        with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, {})), patch.object(B, 'Netlink', return_value=nl):
            with self.assertRaisesRegex(RuntimeError, 'PHY identity'): B.single_frame(self.job, self.request)
        self.iw.assert_not_called()
        nl.command.assert_not_called()
        self.assertFalse((self.job / 'attempt.json').exists())

    def test_capture_session_is_metadata_and_never_a_backend_file_path(self):
        self.request['capture_session']='../../not-a-file'
        nl=MagicMock();nl.command.return_value=[]
        with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl), patch.object(B, 'observations', side_effect=AssertionError('No file lookup')):
            self.assertEqual(1, B.single_frame(self.job,self.request)['userspace_submissions'])
        nl.command.assert_called_once()

    def test_cancel_during_phy_validation_prevents_intent_and_submission(self):
        nl = MagicMock()
        def cancelled_phy(*args):
            (self.job / 'android-cancel').touch()
            return {'phy': 0}
        with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl), patch.object(B,'validate_transmit_frequency',side_effect=cancelled_phy):
            with self.assertRaisesRegex(RuntimeError,'Cancelled'): B.single_frame(self.job,self.request)
        nl.command.assert_not_called()
        self.assertFalse((self.job / 'attempt.json').exists())

    def test_owner_death_after_validation_prevents_intent(self):
        nl = MagicMock()
        with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl), patch.object(B.os,'kill',side_effect=ProcessLookupError):
            with self.assertRaisesRegex(RuntimeError,'owner is absent'): B.single_frame(self.job,self.request)
        nl.command.assert_not_called()
        self.assertFalse((self.job / 'attempt.json').exists())

    def test_cancel_after_intent_never_submits_or_retries(self):
        nl=MagicMock(); original=Path.write_text
        def cancel_after_intent(path,*args,**kwargs):
            written=original(path,*args,**kwargs)
            if path.name == 'attempt.json':
                (self.job / 'android-cancel').touch()
            return written
        with patch.object(B,'BASE',self.base), patch.object(B.time,'time',return_value=EPOCH), patch.object(B,'interface',return_value=(47,self.interface_fields)), patch.object(B,'Netlink',return_value=nl), patch.object(Path,'write_text',new=cancel_after_intent):
            with self.assertRaisesRegex(RuntimeError,'Cancelled'): B.single_frame(self.job,self.request)
        nl.command.assert_not_called()
        self.assertTrue((self.job / 'attempt.json').exists())
        self.assertTrue((self.base / 'last-attempt.json').exists())

    def test_timeout_records_intent_without_retry(self):
        nl = MagicMock(); nl.command.side_effect = TimeoutError('test')
        with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl):
            with self.assertRaises(TimeoutError): B.single_frame(self.job, self.request)
        self.assertEqual(1, nl.command.call_count)
        self.assertTrue((self.job / 'attempt.json').exists())
        nl.sock.close.assert_called_once()

    def test_assigned_sequence_preserves_all_other_bytes_and_correlation(self):
        sent = struct.pack('<HH', 0xc0, 0) + bytes.fromhex(CLIENT.replace(':', '')) + bytes.fromhex(AP.replace(':', '')) * 2 + struct.pack('<HH', 0, 3)
        reported = sent[:22] + bytes.fromhex('7034') + sent[24:]
        status = dict(cookie=256, ifindex=1405, ack=True, frame_hex=reported.hex())
        self.assertEqual('sequence_assigned', B.status_match(status, [256], 1405, sent))
        self.assertIsNone(B.status_match(status, [257], 1405, sent))
        self.assertIsNone(B.status_match(status, [256], 1406, sent))
        for offset in list(range(22)) + [24, 25]:
            changed = bytearray(reported); changed[offset] ^= 1
            self.assertIsNone(B.status_match({**status, 'frame_hex': changed.hex()}, [256], 1405, sent), offset)
        changed = bytearray(reported); changed[22] ^= 1
        self.assertIsNone(B.status_match({**status, 'frame_hex': changed.hex()}, [256], 1405, sent))
        for invalid in ('zz', None, reported.hex()+'00'):
            self.assertIsNone(B.status_match({**status, 'frame_hex': invalid}, [256], 1405, sent))

    def test_missing_or_unrelated_ack_does_not_become_success_evidence(self):
        frame = struct.pack('<HH', 0xc0, 0) + bytes.fromhex(CLIENT.replace(':', '')) + bytes.fromhex(AP.replace(':', '')) * 2 + struct.pack('<HH', 0, 3)
        correct = dict(cookie=256, ack=True, ifindex=47, frame_hex=frame.hex())
        for status in [None, {**correct, 'cookie': 257}, {**correct, 'ifindex': 48},
                       {**correct, 'frame_hex': '00'}, {**correct, 'ack': False}]:
            with self.subTest(status=status):
                (self.base / 'last-attempt.json').unlink(missing_ok=True)
                nl = MagicMock()
                nl.command.return_value = [(1, b'\x00' * 4 + B.a('COOKIE', struct.pack('=Q', 256)), 1)]
                nl.tx_status.return_value = status
                with patch.object(B, 'BASE', self.base), patch.object(B.time, 'time', return_value=EPOCH), patch.object(B, 'interface', return_value=(47, self.interface_fields)), patch.object(B, 'Netlink', return_value=nl):
                    result = B.single_frame(self.job, self.request)
                self.assertTrue(result['kernel_accepted'])
                self.assertFalse(result['matching_ack'])
                nl.command.assert_called_once()


class CompatibilityTests(unittest.TestCase):
    def test_reference_is_a_profile_match_not_device_validation(self):
        result = B.compatibility(B.REFERENCE_MODEL, B.REFERENCE_BUILD, 'different-kernel', B.REFERENCE_DRIVER_SHA256)
        self.assertEqual('reference_profile_match', result['tier'])
        self.assertTrue(result['operations_candidate'])
        self.assertFalse(result['this_device_validated'])
        self.assertEqual('different-kernel', result['kernel'])

    def test_matching_driver_is_not_model_or_build_pinned(self):
        for model in ('CPH2653', 'CPH2649', 'PJZ110', 'custom-ROM-model'):
            for build in ('Android-15-example', 'Android-16-example', 'custom-ROM'):
                with self.subTest(model=model, build=build):
                    result = B.compatibility(model, build, 'kernel-example', B.REFERENCE_DRIVER_SHA256)
                    self.assertEqual('matching_driver_unverified_environment', result['tier'])
                    self.assertTrue(result['operations_candidate'])
                    self.assertFalse(result['this_device_validated'])

    def test_unknown_hash_is_unverified_but_not_a_binary_allowlist(self):
        for digest in ('', 'different', '0'*64, B.REFERENCE_DRIVER_SHA256+' extra'):
            with self.subTest(digest=digest):
                result = B.compatibility(B.REFERENCE_MODEL, B.REFERENCE_BUILD, 'k', digest)
                self.assertEqual('unverified_driver', result['tier'])
                self.assertTrue(result['operations_candidate'])
                self.assertFalse(result['this_device_validated'])

    def test_inventory_preserves_unknowns_and_records_variation(self):
        with tempfile.TemporaryDirectory() as tmp:
            job = Path(tmp)
            (job/'model.txt').write_text('different model\n')
            (job/'driver-srcversion.txt').write_text('loaded-version\n')
            (job/'firmware-sha256.txt').write_text('firmware-digest\n')
            result = B.write_compatibility(job)
            self.assertEqual('', result['driver_sha256'])
            self.assertEqual('unverified_driver', result['tier'])
            self.assertEqual('loaded-version', result['loaded_driver_srcversion'])
            self.assertEqual('firmware-digest', result['stock_firmware_sha256'])
            self.assertEqual(result, json.loads((job/'compatibility.json').read_text()))


if __name__ == '__main__':
    unittest.main()
