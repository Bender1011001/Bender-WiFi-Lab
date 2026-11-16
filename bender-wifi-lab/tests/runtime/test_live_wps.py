import json
import itertools
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch

from test_privacy_profile import FakeHost, P
import live_wps as W


class LiveWpsTests(unittest.TestCase):
    def test_common_pins_stop_after_confirmed_rejections(self):
        collector = W.Collector.__new__(W.Collector)
        attempted = []
        collector.check = lambda: None
        collector.progress = lambda phase: None
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
