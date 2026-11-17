import json
import itertools
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch, MagicMock

from test_privacy_profile import FakeHost, P
import live_wps as W


class LiveWpsTests(unittest.TestCase):
    def test_protocol_events_drop_addresses_keys_and_unknown_text(self):
        self.assertEqual(W.protocol_event(b'<3>CTRL-EVENT-CONNECTED aa:bb:cc:dd:ee:ff key=private'), 'associated')
        self.assertEqual(W.protocol_event(b'<3>WPS-FAIL msg=private pin=12345670'), 'wps_failed')
        self.assertIsNone(W.protocol_event(b'<3>WPS-CRED-RECEIVED secret'))
        self.assertIsNone(W.protocol_event(b'BENDER_CREDENTIAL {"key":"secret"}'))
        self.assertIsNone(W.protocol_event(b'<3>WPS-FAILURE-FAKE secret'))

    def test_error_codes_cannot_include_native_replies_or_credentials(self):
        self.assertEqual(W.failure_code(TimeoutError('wps_exchange_timed_out')), 'wps_exchange_timed_out')
        self.assertEqual(W.failure_code(ValueError('PIN=12345670 password=private')), 'worker_error')
        self.assertEqual(W.failure_code(ValueError({'secret': 'private'})), 'worker_error')

    def test_worker_heartbeat_is_separate_from_protocol_activity(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            (root / 'proc/42').mkdir(parents=True)
            collector = W.Collector.__new__(W.Collector)
            collector.owner = 42; collector.cancel = root / 'cancel'
            collector.bridge = object(); collector.last_heartbeat = 0
            collector.phase = 'collecting_exchange'
            collector.monitor = MagicMock()
            collector.monitor.recv.side_effect = [b'<3>CTRL-EVENT-EAP-STARTED address=private', BlockingIOError(), BlockingIOError()]
            with (patch.object(W, 'HOST', root), patch.object(W.time, 'monotonic', return_value=10),
                  patch.object(W, 'send_message') as send):
                collector.check(); collector.check()
            self.assertEqual([c.args[1] for c in send.call_args_list],
                             [{'type': 'heartbeat'}, {'type': 'activity', 'code': 'eap_started'}])
            self.assertEqual(collector.phase, 'collecting_exchange')
            collector.cancel.touch()
            with patch.object(W, 'HOST', root), patch.object(W, 'send_message') as send:
                with self.assertRaises(InterruptedError): collector.check()
                send.assert_not_called()

    def test_fresh_ap_lookup_emits_stages_and_closes_event_socket_on_lockout(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            job = root / 'bw_100_0123456789ab'; job.mkdir()
            (job / 'chroot.txt').write_text('/data/local/bender-wifi/runtime-arm64-v1')
            collector = W.Collector.__new__(W.Collector)
            collector.job = job; collector.native = '/data/app/bender/libbender_wpa.so'
            collector.bssid = '02:00:00:00:00:01'; collector.frequency = 2412
            collector.journal = {}; collector.monitor = None; collector.check = lambda: None
            activity = []
            collector.detail = lambda code, remaining_ms=None: activity.append(code)
            process = MagicMock(); process.pid = 42; process.poll.return_value = None
            def start(*args, **kwargs):
                control = root / 'data/local/tmp/hijacker-qca/bender-wps/0123456789ab/c'
                (control / W.IFACE).touch()
                return process
            ctrl, monitor = MagicMock(), MagicMock()
            ctrl.recv.side_effect = [b'PONG', b'OK', b'OK', b'OK',
                b'bssid=02:00:00:00:00:01\nfreq=2412\nflags=[WPS]\nwps_ap_setup_locked=1']
            monitor.recv.return_value = b'OK'
            with (patch.object(W, 'HOST', root), patch.object(W.subprocess, 'Popen', side_effect=start),
                  patch.object(W, 'process_start', return_value=100), patch.object(W, 'store_journal'),
                  patch.object(W.socket, 'AF_UNIX', 1, create=True),
                  patch.object(W.socket, 'socket', side_effect=[ctrl, monitor]),
                  patch.object(W.selectors, 'DefaultSelector')):
                with self.assertRaisesRegex(RuntimeError, '^ap_setup_locked$'):
                    collector.exchange('capture', '12345670')
            self.assertEqual(activity, ['starting_supplicant', 'control_ready', 'scanning_ap'])
            self.assertFalse(any(call.args[0].startswith(b'WPS_REG') for call in ctrl.send.call_args_list))
            monitor.send.assert_called_once_with(b'ATTACH')
            monitor.close.assert_called_once()
            process.kill.assert_called_once()
            self.assertIsNone(collector.journal['child'])

    def test_common_pins_stop_after_confirmed_rejections(self):
        collector = W.Collector.__new__(W.Collector)
        attempted = []
        collector.check = lambda: None
        collector.progress = lambda phase: None
        collector.detail = lambda code, remaining_ms=None: None
        collector.exchange = lambda mode, pin, index: attempted.append(pin) or {'pin_rejected_half': 1}
        with (patch.object(W.time, 'sleep', lambda _: None),
              patch.object(W.time, 'monotonic', side_effect=itertools.count())):
            result = collector.run_common_pins({'ok': False})
        self.assertEqual(attempted, list(W.TRIAL_PINS))
        self.assertEqual(result['state'], 'common_pins_rejected')
        self.assertEqual(result['attempts'], len(W.TRIAL_PINS))

    def test_common_pins_never_retry_uncertain_attempt(self):
        collector = W.Collector.__new__(W.Collector)
        attempted = []
        collector.check = lambda: None
        collector.progress = lambda phase: None
        def uncertain(mode, pin, index):
            attempted.append(pin)
            raise TimeoutError('no authenticated result')
        collector.exchange = uncertain
        with self.assertRaises(TimeoutError): collector.run_common_pins({'ok': False})
        self.assertEqual(attempted, [W.TRIAL_PINS[0]])

    def test_secondary_interface_is_filtered_before_radio_use_and_restored(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            job = root / 'bw_100_0123456789ab'; job.mkdir()
            directory = root / 'privacy'
            host = FakeHost(directory / 'pending.json')
            prepared = P.prepare(job, runner=host, journal_dir=directory, profile='wps', session=True)
            self.assertTrue(prepared['active'])
            for family in (4, 6):
                rules = host.rules(family)
                self.assertIn('-A BWL_PRIVATE -o wlan0 -j DROP', rules)
                self.assertIn('-A BWL_PRIVATE -o wlan1 -j DROP', rules)
            self.assertEqual(P.restore(job, runner=host, journal_dir=directory, owner_only=True)['state'], 'restored')

    def test_target_rejects_group_address_and_six_ghz(self):
        with self.assertRaises(ValueError): W.request_target({'bssid': '01:00:00:00:00:00', 'frequency': 2412})
        with self.assertRaises(ValueError): W.request_target({'bssid': '02:00:00:00:00:00', 'frequency': 6135})
        self.assertEqual(W.request_target({'bssid': '02:00:00:00:00:00', 'frequency': 5805})[1], 5805)

    def test_private_bridge_rejects_oversized_messages(self):
        left, right = socket.socketpair()
        try:
            W.send_message(left, {'type': 'hello'})
            self.assertEqual(W.receive_message(right), {'type': 'hello'})
            left.sendall((W.MAX_MESSAGE + 1).to_bytes(4, 'big'))
            with self.assertRaises(ValueError): W.receive_message(right)
        finally:
            left.close(); right.close()

    def test_recovery_refuses_unidentified_interface(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            iface = root / 'sys/class/net/wlan1'; iface.mkdir(parents=True)
            (iface / 'ifalias').write_text('different-owner', encoding='utf-8')
            journal = root / 'pending.json'
            journal.write_text(json.dumps({'schema_version': 1, 'owner': 'bw_100_0123456789ab',
                                           'binary': '/data/app/bender', 'child': None,
                                           'interface': {'index': 20, 'phy': 1, 'mac': '02:00:00:00:00:01'},
                                           'alias': 'bender-wps:bw_100_0123456789ab'}), encoding='utf-8')
            with (patch.object(W, 'HOST', root), patch.object(W, 'JOURNAL', journal),
                  patch.object(W, 'interface_info', return_value={'index': 20, 'phy': 1, 'mac': '02:00:00:00:00:01'})):
                with self.assertRaises(ValueError): W.recover()
            self.assertTrue(journal.exists())


if __name__ == '__main__': unittest.main()
