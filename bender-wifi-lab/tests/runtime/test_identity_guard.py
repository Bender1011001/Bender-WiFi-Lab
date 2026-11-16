"""Synthetic policy and bounded collector checks; never accesses a phone."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

SPEC = importlib.util.spec_from_file_location(
    "identity_guard", Path(__file__).resolve().parents[2] / "runtime" / "identity_guard.py")
G = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(G)


def snapshot():
    return {
        "android_context": True,
        "settings": {"airplane_mode_on": "1", "mobile_data": "0", "bluetooth_on": "0"},
        "wlan_mac": "02:11:22:33:44:55",
        "links": ["1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536",
                  "2: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500"],
        "addresses": ["1: lo inet 127.0.0.1/8 scope host lo",
                      "2: wlan0 inet 192.168.1.3/24 brd 192.168.1.255 scope global wlan0",
                      "2: wlan0 inet6 fe80::1234/64 scope link"],
        "routes_v4": ["192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.3",
                      "local 127.0.0.1 dev lo table local proto kernel scope host src 127.0.0.1"],
        "routes_v6": ["fe80::/64 dev wlan0 proto kernel metric 256 pref medium"],
    }


class IdentityTests(unittest.TestCase):
    def test_private_snapshot_passes(self):
        report = G.evaluate(snapshot())
        self.assertTrue(report["allowed"])
        self.assertGreater(report["checked_at"], 0)
        self.assertEqual(set(G._CHECK_IDS), {c["id"] for c in report["checks"]})

    def test_missing_each_field_fails_closed(self):
        for key in snapshot():
            with self.subTest(key=key):
                value = snapshot()
                del value[key]
                self.assertFalse(G.evaluate(value)["allowed"])

    def test_missing_setting_and_unsupported_values_block(self):
        for setting in snapshot()["settings"]:
            for value in (None, "null", "", "2", 0, True):
                candidate = snapshot()
                candidate["settings"][setting] = value
                self.assertFalse(G.evaluate(candidate)["allowed"])

    def test_every_unsafe_setting_blocks(self):
        for setting, value in (("airplane_mode_on", "0"), ("mobile_data", "1"), ("bluetooth_on", "1")):
            candidate = snapshot()
            candidate["settings"][setting] = value
            self.assertFalse(G.evaluate(candidate)["allowed"])

    def test_mac_global_multicast_placeholder_and_malformed_block(self):
        for mac in ("00:11:22:33:44:55", "03:11:22:33:44:55", "02:00:00:00:00:00", "xx", None):
            candidate = snapshot()
            candidate["wlan_mac"] = mac
            self.assertFalse(G.evaluate(candidate)["allowed"])

    def test_ipv4_defaults_in_all_policy_tables_block(self):
        for route in ("default via 192.168.1.1 dev wlan0", "default dev tun0 table 100",
                      "0.0.0.0/0 dev tun0 table 200", "0.0.0.0/1 dev tun0", "128.0.0.0/1 dev tun0",
                      "8.8.8.8 dev wlan0 table 100", "192.168.1.0/24 via 8.8.8.8 dev wlan0"):
            candidate = snapshot()
            candidate["routes_v4"].append(route)
            self.assertFalse(G.evaluate(candidate)["allowed"], route)

    def test_ipv6_defaults_public_and_split_vpn_routes_block(self):
        for route in ("default via fe80::1 dev wlan0", "::/0 dev tun0 table 100",
                      "::/1 dev tun0 table 100", "8000::/1 dev tun0 table 100",
                      "2000::/3 dev wlan0", "2606:4700:4700::1111 dev wlan0"):
            candidate = snapshot()
            candidate["routes_v6"].append(route)
            self.assertFalse(G.evaluate(candidate)["allowed"], route)

    def test_assigned_public_or_unrecognized_addresses_block(self):
        for address in ("inet 8.8.8.8/24", "inet6 2606:4700::1/64", "inet 0.0.0.0/32", "inet rubbish"):
            candidate = snapshot()
            candidate["addresses"].append("3: tun0 " + address + " scope global tun0")
            self.assertFalse(G.evaluate(candidate)["allowed"])

    def test_malformed_routes_and_wrong_families_block(self):
        for route in ("garbage", "192.168.1.0/24", "192.168.1.0/24 garbage value",
                      "192.168.1.0/24 dev", "192.168.1.0/24 metric nan", "fe80::/64 dev wlan0"):
            candidate = snapshot()
            candidate["routes_v4"].append(route)
            self.assertFalse(G.evaluate(candidate)["allowed"], route)

    def test_no_routes_and_no_addresses_are_valid_evidence(self):
        candidate = snapshot()
        for key in ("routes_v4", "routes_v6", "addresses"):
            candidate[key] = []
        self.assertTrue(G.evaluate(candidate)["allowed"])

    def test_active_cellular_links_block_even_without_ip(self):
        for interface in ("rmnet_data0", "ccmni0", "pdp0", "wwan0", "v4-rmnet_data0", "r_rmnet_data0"):
            candidate = snapshot()
            candidate["links"].append("9: " + interface + ": <UP> mtu 1500")
            self.assertFalse(G.evaluate(candidate)["allowed"])

    def test_down_cellular_link_and_private_policy_route_pass(self):
        candidate = snapshot()
        candidate["links"].append("9: rmnet_data0: <> mtu 1500")
        candidate["routes_v4"].append("10.0.0.0/8 dev tun0 table 100 metric 50")
        self.assertTrue(G.evaluate(candidate)["allowed"])

    def test_missing_wlan_or_malformed_links_block(self):
        for links in ([], ["bad"], ["1: lo: <UP> mtu 65536"]):
            candidate = snapshot()
            candidate["links"] = links
            self.assertFalse(G.evaluate(candidate)["allowed"])

    def test_report_contains_only_fixed_fields_and_reasons(self):
        candidate = snapshot()
        candidate["wlan_mac"] = "secret-identifier"
        report = G.evaluate(candidate)
        self.assertEqual({"schema_version", "checked_at", "allowed", "checks"}, set(report))
        for check in report["checks"]:
            self.assertEqual({"id", "status", "reason"}, set(check))
            self.assertIn(check["status"], {"pass", "blocked", "unknown"})
        serialized = json.dumps(report)
        for secret in ("secret-identifier", "192.168.1.3", "fe80::1234"):
            self.assertNotIn(secret, serialized)

    def test_inspection_failure_is_unknown_and_blocks(self):
        for failure in (OSError("identifier"), TimeoutError("identifier"), ValueError("identifier")):
            with patch.object(G, "_collect", side_effect=failure):
                report = G.inspect()
            self.assertFalse(report["allowed"])
            self.assertTrue(all(c["status"] == "unknown" for c in report["checks"]))
            self.assertNotIn("identifier", json.dumps(report))

    def test_report_is_written_before_refusal(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(G, "inspect", return_value=G.evaluate({})):
            with self.assertRaisesRegex(RuntimeError, "identity preflight blocked"):
                G.require_private_context(Path(directory))
            report = json.loads((Path(directory) / "identity-state.json").read_text())
            self.assertFalse(report["allowed"])
            self.assertFalse((Path(directory) / "identity-state.json.tmp").exists())

    def test_allowed_report_is_persisted(self):
        report = G.evaluate(snapshot())
        with tempfile.TemporaryDirectory() as directory, patch.object(G, "inspect", return_value=report):
            self.assertEqual(report, G.require_private_context(Path(directory)))

    def test_marker_parser_requires_complete_android_snapshot(self):
        data = snapshot()
        sections = {"sdk": ["36"], "fingerprint": ["vendor/device/product:16/release"],
                    **{k: [v] for k, v in data["settings"].items()},
                    "wlan_mac": [data["wlan_mac"]],
                    **{k: data[k] for k in G._SECTIONS[6:10]},
                    'firewall_v4': ['UNAVAILABLE'], 'firewall_v6': ['UNAVAILABLE']}
        raw = "".join("BEGIN_" + k + "\n" + "\n".join(sections[k]) + "\nEND_" + k + "\n" for k in G._SECTIONS)
        self.assertTrue(G.evaluate(G._parse(raw))["allowed"])
        for malformed in (raw.replace("END_routes_v6", ""), raw + "extra\n", raw.replace("36\n", "\n", 1)):
            with self.assertRaises(ValueError):
                G._parse(malformed)

    def test_both_verified_egress_locks_allow_configured_public_routes(self):
        candidate = snapshot()
        candidate['addresses'].append('3: tun0 inet 8.8.8.8/32 scope global tun0')
        candidate['routes_v4'].append('default dev tun0 table 100')
        candidate['routes_v6'].append('default via fe80::1 dev wlan0')
        candidate['egress_lock'] = {'ipv4': True, 'ipv6': True}
        report = G.evaluate(candidate)
        self.assertTrue(report['allowed'])
        self.assertTrue(all(c['reason'] == 'configured_but_egress_blocked'
                            for c in report['checks'] if c['id'] in {'assigned_addresses', 'ipv4_routes', 'ipv6_routes'}))
        for lock in ({'ipv4': True, 'ipv6': False}, {'ipv4': True}, {'ipv4': 1, 'ipv6': 1}):
            candidate['egress_lock'] = lock
            self.assertFalse(G.evaluate(candidate)['allowed'])

    def test_lock_does_not_bypass_missing_or_malformed_evidence(self):
        for key, value in (('routes_v4', ['default dev tun0', 'bad']), ('routes_v6', None),
                           ('addresses', ['2: wlan0 inet invalid scope global wlan0']),
                           ('wlan_mac', '00:11:22:33:44:55')):
            candidate = snapshot()
            candidate['egress_lock'] = {'ipv4': True, 'ipv6': True}
            candidate[key] = value
            self.assertFalse(G.evaluate(candidate)['allowed'])

    def test_cancellation_guard_restores_signal_handlers(self):
        before = G.signal.getsignal(G.signal.SIGTERM)
        with self.assertRaises(KeyboardInterrupt):
            with G._cancellation_guard():
                G.signal.getsignal(G.signal.SIGTERM)(G.signal.SIGTERM, None)
        self.assertEqual(before, G.signal.getsignal(G.signal.SIGTERM))

    def test_collector_cancellation_kills_group_and_propagates(self):
        process = MagicMock()
        selector = MagicMock()
        selector.__enter__.return_value = selector
        selector.select.side_effect = KeyboardInterrupt('cancelled')
        with patch.object(G.subprocess, 'Popen', return_value=process), \
             patch.object(G.selectors, 'DefaultSelector', return_value=selector), \
             patch.object(G.os, 'killpg', create=True) as kill:
            with self.assertRaises(KeyboardInterrupt):
                G._collect()
        kill.assert_called_once_with(process.pid, G._KILL_SIGNAL)

    def test_collector_timeout_kills_process_group(self):
        process = MagicMock()
        process.pid = 987654
        selector = MagicMock()
        selector.__enter__.return_value = selector
        selector.select.return_value = []
        with patch.object(G.subprocess, "Popen", return_value=process) as spawn, \
             patch.object(G.selectors, "DefaultSelector", return_value=selector), \
             patch.object(G.os, "killpg", create=True) as kill:
            with self.assertRaises(TimeoutError):
                G._collect()
        kill.assert_called_once_with(process.pid, G._KILL_SIGNAL)
        self.assertEqual(["/usr/sbin/chroot", "/proc/1/root", "/system/bin/sh", "-c"], spawn.call_args.args[0][:4])
        self.assertTrue(spawn.call_args.kwargs["start_new_session"])

    def test_collector_caps_output_and_kills_group(self):
        process = MagicMock()
        selector = MagicMock()
        selector.__enter__.return_value = selector
        selector.select.return_value = [(None, None)]
        with patch.object(G.subprocess, "Popen", return_value=process), \
             patch.object(G.selectors, "DefaultSelector", return_value=selector), \
             patch.object(G.os, "read", return_value=b"x" * 33), \
             patch.object(G, "MAX_OUTPUT_BYTES", 32), patch.object(G.os, "killpg", create=True) as kill:
            with self.assertRaisesRegex(ValueError, "too large"):
                G._collect()
        kill.assert_called_once()


if __name__ == "__main__":
    unittest.main()
