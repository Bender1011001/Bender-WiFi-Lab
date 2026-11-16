"""Synthetic structured-dissection tests; no private captures required."""
import json
import shutil
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "engine"))
from bender_engine.analysis import AnalysisCancelled, analyze_pdml, analyze_pcap

AP = "02:00:00:00:00:01"
STA = "02:00:00:00:00:02"
TSHARK = shutil.which("tshark") or ("C:/Program Files/Wireshark/tshark.exe" if Path("C:/Program Files/Wireshark/tshark.exe").exists() else None)


def field(parent, name, show=None, raw=None):
    attrs = {"name": name}
    if show is not None:
        attrs["show"] = str(show)
    if raw is not None:
        attrs["value"] = str(raw)
    return ET.SubElement(parent, "field", attrs)


def packet(frame=1, subtype=8, ap=AP, sa=AP, da="ff:ff:ff:ff:ff:ff", timestamp=None):
    p = ET.Element("packet")
    for name, val in (("frame.number", frame), ("frame.time_epoch", frame if timestamp is None else timestamp),
                      ("wlan.fc.type_subtype", subtype), ("wlan.bssid", ap), ("wlan.sa", sa), ("wlan.da", da),
                      ("wlan.ta", sa), ("wlan.ra", da), ("wlan.fc.tods", int(subtype >= 32 and sa != ap)),
                      ("wlan.fc.fromds", int(subtype >= 32 and sa == ap))):
        field(p, name, val)
    field(p, "wlan.ssid", raw="41ff0042")
    return p


def rsn(p, akms=(2, 8), caps=128, declared=None, wpa=False):
    tag = field(p, "wlan.tag")
    field(tag, "wlan.tag.number", 221 if wpa else 48)
    prefix = "wlan.wfa.ie.wpa" if wpa else "wlan.rsn"
    oui = "0050f2" if wpa else "000fac"
    field(tag, prefix + ".version", 1)
    field(tag, prefix + ".akms.count", len(akms) if declared is None else declared)
    for typ in akms:
        field(tag, prefix + ".akms", raw=oui + f"{typ:02x}")
    field(tag, prefix + (".ucs.count" if wpa else ".pcs.count"), 2)
    for typ in (4, 8):
        field(tag, prefix + (".ucs" if wpa else ".pcs"), raw=oui + f"{typ:02x}")
    field(tag, prefix + (".mcs" if wpa else ".gcs"), raw=oui + "04")
    if caps is not None and not wpa:
        field(tag, prefix + ".capabilities", caps)
    return tag


def eapol(frame, msg, replay, nonce="11" * 32, timestamp=None):
    p = packet(frame, 32, sa=AP if msg in (1, 3) else STA, da=STA if msg in (1, 3) else AP, timestamp=timestamp)
    field(p, "wlan_rsna_eapol.keydes.msgnr", msg)
    field(p, "wlan_rsna_eapol.keydes.key_info.key_type", 1)
    if replay is not None:
        field(p, "eapol.keydes.replay_counter", replay)
    field(p, "wlan_rsna_eapol.keydes.nonce", raw=nonce)
    field(p, "wlan_rsna_eapol.keydes.key_mic", raw="private-mic-must-not-leak")
    return p


class ObservationTests(unittest.TestCase):
    def decoded_frames(self, frames):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "synthetic.pcap"
            with path.open("wb") as stream:
                stream.write(struct.pack("<IHHIIII", 0xa1b2c3d4, 2, 4, 0, 0, 65535, 105))
                for index, raw in enumerate(frames, 1):
                    stream.write(struct.pack("<IIII", index, 0, len(raw), len(raw)))
                    stream.write(raw)
            return analyze_pcap(path, TSHARK)

    @unittest.skipUnless(TSHARK, "TShark is not installed")
    def test_real_ds_frames_do_not_invent_wired_stations(self):
        ap, sta, wired = (bytes.fromhex(m.replace(":", "")) for m in (AP, STA, "02:00:00:00:00:03"))
        llc = bytes.fromhex("aaaa030000000800") + b"\x45" + b"\0" * 39
        frames = [struct.pack("<HH", 0x0108, 0) + ap + sta + wired + b"\0\0" + llc,
                  struct.pack("<HH", 0x0208, 0) + sta + ap + wired + b"\0\0" + llc]
        result = self.decoded_frames(frames)
        self.assertEqual([STA], [c["mac"] for c in result["clients"]])
        relations = result["clients"][0]["relations"]
        self.assertEqual("02:00:00:00:00:03", relations[0]["logical_destination"])
        self.assertEqual("02:00:00:00:00:03", relations[1]["logical_source"])

    @unittest.skipUnless(TSHARK, "TShark is not installed")
    def test_real_nested_bssid_rsn_is_owned_once_and_not_by_parent(self):
        ap = bytes.fromhex(AP.replace(":", ""))
        def ie(n, data): return bytes([n, len(data)]) + data
        rsn_bytes = struct.pack("<H", 1) + bytes.fromhex("000fac04") + struct.pack("<H", 1) + bytes.fromhex("000fac04") + struct.pack("<H", 1) + bytes.fromhex("000fac02") + struct.pack("<H", 128)
        profile = ie(83, struct.pack("<H", 0x0011)) + ie(0, b"private") + ie(85, b"\x01") + ie(48, rsn_bytes)
        beacon = struct.pack("<HH", 0x0080, 0) + b"\xff" * 6 + ap + ap + b"\0\0" + struct.pack("<QHH", 0, 100, 1) + ie(0, b"open") + ie(1, b"\x82") + ie(71, b"\x01" + bytes([0, len(profile)]) + profile)
        net = self.decoded_frames([beacon])["networks"][0]
        self.assertEqual("open", net["ssid_display"])
        self.assertEqual([], net["advertised_akms"])
        self.assertEqual("unknown", net["pmf_advertised"])
        advert = net["advertisements"][0]
        self.assertEqual([], advert["security_ies"])
        self.assertEqual(1, len(advert["nested_profile_security_ies"]))
        nested = advert["nested_profile_security_ies"][0]
        self.assertEqual(["PSK"], [a["name"] for a in nested["akms"]])
        self.assertIsNone(nested["bssid"])

    def test_nested_wps_mlo_and_ssid_cannot_supply_parent_metadata(self):
        p = packet()
        container = field(p, "wlan.tag")
        field(container, "wlan.tag.number", 71)
        child = field(container, "wlan.tag")
        field(child, "wps.version", "0x20")
        field(child, "wlan.ssid", raw="6368696c64")
        field(child, "wlan.ext_tag.number", 107)
        field(child, "wlan.eht.multi_link.common_info.mld_mac_address", "02:11:22:33:44:55")
        field(child, "wlan.eht.multi_link.common_info.link_id_subfield.link_id", 3)
        rsn(child, (2,), 192)
        rsn(p, (8,), 128)
        net = self.analyze([p])["networks"][0]
        self.assertEqual("41ff0042", net["ssid_hex"])
        self.assertEqual("WPA3-Personal", net["security"])
        self.assertEqual("capable", net["pmf_advertised"])
        self.assertIsNone(net["wps"]); self.assertIsNone(net["mlo"])
        self.assertEqual(1, len(net["advertisements"][0]["nested_profile_security_ies"]))

    def test_wds_ibss_and_missing_ds_evidence_do_not_infer_ap_clients(self):
        for ds in ((0, 0), (1, 1), (None, None)):
            with self.subTest(ds=ds):
                p = packet(subtype=32, sa=STA, da=AP)
                for item in list(p):
                    if item.get("name") in ("wlan.fc.tods", "wlan.fc.fromds"):
                        p.remove(item)
                for name, val in zip(("wlan.fc.tods", "wlan.fc.fromds"), ds):
                    if val is not None: field(p, name, val)
                self.assertEqual([], self.analyze([p])["clients"])

    def analyze(self, packets, **kwargs):
        root = ET.Element("pdml")
        root.extend(packets)
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "fixture.xml"
            ET.ElementTree(root).write(path, encoding="utf-8")
            return analyze_pdml(path, **kwargs)

    def test_transition_mode_preserves_akms_ciphers_and_bytes(self):
        p = packet()
        rsn(p)
        n = self.analyze([p])["networks"][0]
        self.assertEqual([2, 8], [x["type"] for x in n["advertised_akms"]])
        self.assertEqual([4, 8], [x["type"] for x in n["advertisements"][0]["security_ies"][0]["pairwise_ciphers"]])
        self.assertEqual("41ff0042", n["ssid_hex"])
        self.assertEqual("capable", n["pmf_advertised"])
        self.assertEqual("unknown", n["negotiated_pmf"])

    def test_repeated_and_reordered_ies_are_not_flattened(self):
        p = packet()
        rsn(p, (8, 2))
        rsn(p, (2,), caps=192)
        rsn(p, (1, 2), wpa=True)
        n = self.analyze([p])["networks"][0]
        ies = n["advertisements"][0]["security_ies"]
        self.assertEqual(3, len(ies))
        self.assertEqual([8, 2], [x["type"] for x in ies[0]["akms"]])
        self.assertEqual([2], [x["type"] for x in ies[1]["akms"]])
        self.assertEqual("WPA", ies[2]["protocol"])
        self.assertEqual("unknown", n["pmf_advertised"])
        self.assertTrue(n["pmf_advertisement_conflict"])

    def test_missing_and_truncated_ie_unknown(self):
        p = packet()
        rsn(p, (), None, declared=2)
        n = self.analyze([p])["networks"][0]
        self.assertEqual("incomplete", n["advertisements"][0]["security_ies"][0]["parse_status"])
        self.assertEqual("unknown", n["pmf_advertised"])
        self.assertEqual("unknown", self.analyze([packet()])["networks"][0]["security"])

    def test_partial_or_malformed_security_never_qualifies_summary(self):
        for variant in ("short_akm", "malformed_tag", "malformed_packet", "invalid_caps", "missing_group"):
            with self.subTest(variant=variant):
                p = packet()
                tag = rsn(p, (8,), 64 if variant == "invalid_caps" else 192,
                          declared=2 if variant == "short_akm" else 1)
                if variant == "malformed_tag":
                    field(tag, "_ws.malformed")
                elif variant == "malformed_packet":
                    ET.SubElement(p, "proto", {"name": "_ws.malformed"})
                elif variant == "missing_group":
                    for child in list(tag):
                        if child.get("name") == "wlan.rsn.gcs":
                            tag.remove(child)
                n = self.analyze([p])["networks"][0]
                self.assertEqual("unknown", n["security"])
                self.assertEqual("unknown", n["pmf_advertised"])
                self.assertEqual([], n["advertised_akms"])
                ie = n["advertisements"][0]["security_ies"][0]
                self.assertEqual(8, ie["akms"][0]["type"])
                self.assertEqual(64 if variant == "invalid_caps" else 192, ie["capabilities"])
                self.assertNotEqual("decoded", ie["parse_status"])

    def test_analysis_shared_deadline_and_cancellation_before_decoder(self):
        with tempfile.TemporaryDirectory() as d:
            capture = Path(d) / "input.pcap"
            capture.write_bytes(b"not a real capture")
            with patch("bender_engine.analysis.time.monotonic", return_value=5), patch("bender_engine.analysis.subprocess.Popen") as launch:
                with self.assertRaisesRegex(ValueError, "budget"):
                    analyze_pcap(capture, deadline=4)
                launch.assert_not_called()
            cancel = Path(d) / "cancel"
            cancel.touch()
            with self.assertRaises(AnalysisCancelled):
                analyze_pcap(capture, cancel_file=cancel)

    def test_cli_serialization_expiry_does_not_replace_output(self):
        from bender_engine.__main__ import main
        with tempfile.TemporaryDirectory() as d:
            output = Path(d) / "output.json"
            output.write_text("existing evidence")
            with patch("sys.argv", ["bender_engine", "analyze", "--pcap", str(Path(d) / "input.pcap"), "--output", str(output)]), \
                    patch("bender_engine.__main__.analyze_pcap", return_value={"schema_version": 1}), \
                    patch("bender_engine.__main__.check_budget", side_effect=ValueError("time budget exceeded")):
                self.assertEqual(2, main())
            self.assertEqual("existing evidence", output.read_text())
            self.assertFalse(output.with_name(output.name + ".tmp").exists())

    def test_finished_decoder_cannot_bypass_output_limits_between_polls(self):
        for oversized in ("xml", "diagnostics"):
            with self.subTest(oversized=oversized), tempfile.TemporaryDirectory() as d:
                capture=Path(d)/"input.pcap";capture.write_bytes(b"synthetic")
                process=Mock(returncode=0);process.poll.return_value=0
                def launch(*args, stdout, stderr, **kwargs):
                    stdout.write(b"<pdml/>" + (b" "*64 if oversized=="xml" else b""))
                    stderr.write(b"x"*(1024*1024+1) if oversized=="diagnostics" else b"")
                    return process
                with patch("bender_engine.analysis.MAX_XML_BYTES",64), patch("bender_engine.analysis.subprocess.Popen",side_effect=launch):
                    with self.assertRaisesRegex(RuntimeError,"output exceeded"):
                        analyze_pcap(capture)
                process.kill.assert_not_called()

    def test_hash_and_decoder_time_reduce_the_parser_budget(self):
        with tempfile.TemporaryDirectory() as d:
            capture = Path(d) / "input.pcap"
            capture.write_bytes(b"synthetic")
            process = Mock(returncode=0)
            process.poll.return_value = 0
            # Start, pre-hash check, hash cost, pre-decode check, parser check.
            with patch("bender_engine.analysis.time.monotonic", side_effect=[0, 0, 60, 80, 121]), \
                    patch("bender_engine.analysis.subprocess.Popen", return_value=process):
                with self.assertRaisesRegex(ValueError, "Shared analysis time budget"):
                    analyze_pcap(capture)

    def test_cancellation_during_decoder_kills_owned_process(self):
        with tempfile.TemporaryDirectory() as d:
            capture, cancel = Path(d) / "input.pcap", Path(d) / "cancel"
            capture.write_bytes(b"synthetic")
            process = Mock()
            process.poll.return_value = None
            def launch(*args, **kwargs):
                cancel.touch()
                return process
            with patch("bender_engine.analysis.subprocess.Popen", side_effect=launch):
                with self.assertRaises(AnalysisCancelled):
                    analyze_pcap(capture, cancel_file=cancel)
            process.kill.assert_called_once()
            process.wait.assert_called_once()

    def test_association_is_per_client_and_not_negotiation(self):
        p = packet(subtype=0, sa=STA, da=AP)
        rsn(p, (8,), 192)
        c = self.analyze([p])["clients"][0]
        self.assertEqual("ap:" + AP, c["ap_id"])
        self.assertEqual("association_request", c["relations"][0]["kind"])
        self.assertEqual("unknown", c["relations"][0]["negotiated_pmf"])
        self.assertEqual(1.0, c["relations"][0]["time"])

    def test_observed_roaming_retains_both_time_scoped_relations(self):
        other = "02:00:00:00:00:03"
        d = self.analyze([packet(1, 32, sa=STA, da=AP), packet(2, 32, ap=other, sa=STA, da=other)])
        self.assertEqual(["ap:" + AP, "ap:" + other], [r["ap_id"] for r in d["clients"][0]["relations"]])

    def test_wps_metadata_filters_credentials_and_preserves_groups(self):
        p = packet()
        for ver in ("0x10", "0x20"):
            tag = field(p, "wlan.tag")
            field(tag, "wps.version", ver)
            field(tag, "wps.network_key", "do-not-export-password")
        d = self.analyze([p])
        self.assertEqual(2, len(d["networks"][0]["wps"]["ie_groups"]))
        self.assertNotIn("do-not-export", json.dumps(d))

    def test_unknown_mlo_and_decoded_common_info(self):
        p = packet()
        tag = field(p, "wlan.tag")
        field(tag, "wlan.ext_tag.number", 107)
        self.assertEqual("unknown", self.analyze([p])["networks"][0]["mlo"]["status"])
        field(tag, "wlan.eht.multi_link.common_info.mld_mac_address", "02:11:22:33:44:55")
        field(tag, "wlan.eht.multi_link.common_info.link_id_subfield.link_id", 3)
        mlo = self.analyze([p])["networks"][0]["mlo"]
        self.assertEqual("decoded_common_info", mlo["status"])
        self.assertEqual(AP, mlo["link_bssid"])

    def test_four_messages_are_coverage_not_password_proof(self):
        d = self.analyze([eapol(1, 1, 5), eapol(2, 2, 5, "22" * 32), eapol(3, 3, 6), eapol(4, 4, 6, "00" * 32)])
        h = d["handshakes"][0]
        self.assertEqual("four_messages_observed", h["coverage"])
        self.assertFalse(h["password_verified"])
        self.assertFalse(h["mic_verified"])
        self.assertNotIn("private-mic", json.dumps(d))
        self.assertNotIn("11111111111111111111", json.dumps(d))

    def test_interleaved_nonce_mismatch_cannot_complete(self):
        d = self.analyze([eapol(1, 1, 5), eapol(2, 2, 5, "22" * 32), eapol(3, 3, 6, "33" * 32), eapol(4, 4, 6, "00" * 32)])
        self.assertTrue(all(h["coverage"] == "partial" for h in d["handshakes"]))

    def test_distinct_interleaved_sessions_never_mix(self):
        d = self.analyze([eapol(1, 1, 5), eapol(2, 1, 20, "33" * 32), eapol(3, 2, 5, "22" * 32), eapol(4, 3, 21, "33" * 32), eapol(5, 4, 21, "00" * 32)])
        self.assertTrue(all(h["coverage"] == "partial" for h in d["handshakes"]))

    def test_stale_or_missing_replay_evidence_stays_partial(self):
        for packets in ([eapol(1, 1, None), eapol(2, 2, 5), eapol(3, 3, 6)],
                        [eapol(1, 1, 5), eapol(2, 2, 5, "22" * 32, timestamp=50), eapol(51, 3, 6), eapol(52, 4, 6, "00" * 32)]):
            self.assertTrue(all(h["coverage"] == "partial" for h in self.analyze(packets)["handshakes"]))

    def test_ambiguous_same_replay_sessions_are_not_merged(self):
        d = self.analyze([eapol(1, 1, 5), eapol(2, 1, 5, "33" * 32), eapol(3, 2, 5, "22" * 32), eapol(4, 3, 6), eapol(5, 4, 6, "00" * 32)])
        self.assertTrue(all(h["coverage"] == "partial" for h in d["handshakes"]))

    def test_group_key_exchange_is_not_a_pairwise_handshake(self):
        p = eapol(1, 1, 5)
        for f in p.iter("field"):
            if f.get("name") == "wlan_rsna_eapol.keydes.key_info.key_type":
                f.set("show", "0")
        self.assertEqual([], self.analyze([p])["handshakes"])

    def test_packet_and_field_budgets(self):
        with self.assertRaisesRegex(ValueError, "budget"):
            self.analyze([packet(), packet(2)], max_packets=1)
        with self.assertRaisesRegex(ValueError, "budget"):
            self.analyze([packet()], max_fields=1)
        with patch("bender_engine.analysis.time.monotonic", side_effect=[0, 121]), self.assertRaisesRegex(ValueError, "budget"):
            self.analyze([packet()])

    def test_xml_security_truncation_and_byte_budget(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "bad.xml"
            for text in ("<!DOCTYPE pdml [<!ENTITY x 'test'>]><pdml/>", "<pdml><packet>"):
                path.write_text(text)
                with self.assertRaises(ValueError):
                    analyze_pdml(path)
            path.write_text("<pdml/>")
            with patch("bender_engine.analysis.MAX_XML_BYTES", 2), self.assertRaisesRegex(ValueError, "budget"):
                analyze_pdml(path)

    @unittest.skipUnless(TSHARK, "TShark is not installed")
    def test_actual_tshark_eapol_dissection(self):
        ap, sta = bytes.fromhex(AP.replace(":", "")), bytes.fromhex(STA.replace(":", ""))
        frames = []
        for msg, replay, nonce, info in ((1, 5, b"\x11" * 32, 0x008a), (2, 5, b"\x22" * 32, 0x010a),
                                          (3, 6, b"\x11" * 32, 0x13ca), (4, 6, b"\0" * 32, 0x030a)):
            from_ap = msg in (1, 3)
            header = struct.pack("<HH", 0x0208 if from_ap else 0x0108, 0) + (sta + ap if from_ap else ap + sta) + ap + b"\0\0"
            # A WPA2 M2 carries the association RSN IE. An empty synthetic M2
            # is ambiguous to older supported TShark decoders (reported as M4).
            key_data = bytes.fromhex("30140100000fac040100000fac040100000fac020000") if msg == 2 else b""
            key = (b"\x02" + struct.pack(">HHQ", info, 16, replay) + nonce + b"\0" * 32
                   + b"\x55" * 16 + struct.pack(">H", len(key_data)) + key_data)
            frames.append(header + bytes.fromhex("aaaa03000000888e") + struct.pack(">BBH", 2, 3, len(key)) + key)
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "synthetic.pcap"
            with path.open("wb") as stream:
                stream.write(struct.pack("<IHHIIII", 0xa1b2c3d4, 2, 4, 0, 0, 65535, 105))
                for index, raw in enumerate(frames):
                    stream.write(struct.pack("<IIII", index + 1, 0, len(raw), len(raw)))
                    stream.write(raw)
            result = analyze_pcap(path, TSHARK)
            self.assertEqual(4, result["source"]["frame_count"])
            self.assertEqual("four_messages_observed", result["handshakes"][0]["coverage"])


if __name__ == "__main__":
    unittest.main()
