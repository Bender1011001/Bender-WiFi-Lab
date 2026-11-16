"""Offline synthetic evidence tests; no root, network or radio access."""
from pathlib import Path
import json
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "tests/engine"))
from inspector import inspect_pdml
from test_observations import AP, STA, field, packet, rsn

OTHER = "02:00:00:00:00:03"
TSHARK = shutil.which("tshark") or ("C:/Program Files/Wireshark/tshark.exe" if Path("C:/Program Files/Wireshark/tshark.exe").exists() else None)


def mgmt(frame=1, subtype=12, sa=AP, da=STA, protected=0, timestamp=None):
    p = packet(frame, subtype, sa=sa, da=da, timestamp=timestamp)
    field(p, "wlan.fc.protected", protected)
    if subtype in (10, 12):
        field(p, "wlan.fixed.reason_code", 3)
    return p


def query(frame, action, sa, da, tid=123, timestamp=None):
    p = mgmt(frame, 13, sa, da, timestamp=timestamp)
    field(p, "wlan.fixed.category_code", 8)
    field(p, "wlan.fixed.action_code", action)
    field(p, "wlan.fixed.transaction_id", tid)
    return p


class PmfInspectorTests(unittest.TestCase):
    def inspect(self, packets, **kwargs):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"capture.xml"
            root = ET.Element("pdml")
            root.extend(packets)
            ET.ElementTree(root).write(path, encoding="utf-8")
            return inspect_pdml(path, **kwargs)

    def test_optional_beacon_is_never_per_client_or_enforcement_proof(self):
        p = packet(); rsn(p, (2, 8), 128)
        result = self.inspect([p])
        self.assertEqual("capable", result["networks"][0]["pmf_advertised"])
        self.assertEqual("unknown", result["networks"][0]["negotiated_pmf"])
        self.assertEqual("not_tested", result["enforcement"])
        self.assertEqual("not_established", result["pmf_bypass"])
        self.assertEqual([], result["association_attempts"])

    def test_successful_association_is_a_hint_not_installed_keys(self):
        request = packet(1, 0, sa=STA, da=AP); rsn(request, (8,), 128)
        response = packet(2, 1, sa=AP, da=STA); rsn(response, (8,), 128)
        field(response, "wlan.fixed.status_code", 0)
        attempt = self.inspect([request, response])["association_attempts"][0]
        self.assertEqual(1, attempt["request_frame"])
        self.assertEqual("both_parties_advertised_mfpc", attempt["pmf_hint"])
        self.assertEqual("unknown", attempt["keys_installed"])
        self.assertEqual("unknown", attempt["negotiated_pmf"])

    def test_non_pmf_station_request_is_separate_from_capable_ap(self):
        request = packet(1, 0, sa=STA, da=AP); rsn(request, (2,), 0)
        response = packet(2, 1, sa=AP, da=STA); rsn(response, (2,), 128)
        field(response, "wlan.fixed.status_code", 0)
        attempt = self.inspect([request, response])["association_attempts"][0]
        self.assertEqual("station_requested_without_mfpc", attempt["pmf_hint"])

    def test_missing_rejected_or_wrong_peer_association_stays_unknown(self):
        for status, destination in ((31, STA), (0, OTHER)):
            with self.subTest(status=status, destination=destination):
                request = packet(1, 0, sa=STA, da=AP); rsn(request)
                response = packet(2, 1, sa=AP, da=destination); rsn(response)
                field(response, "wlan.fixed.status_code", status)
                self.assertEqual("unknown", self.inspect([request, response])["association_attempts"][0]["pmf_hint"])
        self.assertEqual(1, self.inspect([request])["unmatched_association_requests"])

    def test_retries_and_old_requests_do_not_get_arbitrarily_paired(self):
        for times in ((1, 2, 3), (1, 2, 10)):
            requests = [packet(i+1, 0, sa=STA, da=AP, timestamp=times[i]) for i in (0, 1)]
            for p in requests: rsn(p)
            response = packet(3, 1, sa=AP, da=STA, timestamp=times[2]); rsn(response)
            field(response, "wlan.fixed.status_code", 0)
            attempt = self.inspect(requests+[response])["association_attempts"][0]
            self.assertIsNone(attempt["request_frame"])
            self.assertEqual("unknown", attempt["pmf_hint"])

    def test_protected_bit_is_not_cryptographic_or_receiver_proof(self):
        row = self.inspect([mgmt(protected=1)])["management_frames"][0]
        self.assertEqual("protected_unicast_format_unverified", row["format"])
        self.assertIsNone(row["integrity_verified"])
        self.assertIsNone(row["receiver_accepted"])

    def test_group_mmie_can_have_clear_protected_bit_without_being_unprotected(self):
        p = mgmt(da="ff:ff:ff:ff:ff:ff")
        field(p, "wlan.mmie.keyid", 4); field(p, "wlan.mmie.ipn", 1)
        field(p, "wlan.mmie.mic", raw="aa"*8)
        result = self.inspect([p], capture_layer="raw_monitor")
        row = result["management_frames"][0]
        self.assertEqual("group_mmie_structure_unverified", row["format"])
        self.assertIsNone(row["integrity_verified"])
        self.assertNotIn("aaaaaaaaaaaaaaaa", json.dumps(result))

    def test_missing_stripped_or_short_mmie_does_not_prove_bypass(self):
        p = mgmt(da="ff:ff:ff:ff:ff:ff")
        result = self.inspect([p], capture_layer="managed_postprocessed")
        self.assertEqual("group_no_mmie_observed", result["management_frames"][0]["format"])
        self.assertEqual("not_established", result["pmf_bypass"])
        field(p, "wlan.mmie.keyid", 4); field(p, "wlan.mmie.ipn", 1); field(p, "wlan.mmie.mic", raw="aa")
        self.assertEqual("group_mmie_incomplete", self.inspect([p])["management_frames"][0]["format"])

    def test_duplicate_or_invalid_mmie_fields_are_not_qualified(self):
        for ipn, duplicate in ((-1, False), (2**48, False), (1, True)):
            with self.subTest(ipn=ipn, duplicate=duplicate):
                p = mgmt(da="ff:ff:ff:ff:ff:ff")
                field(p, "wlan.mmie.keyid", 4); field(p, "wlan.mmie.ipn", ipn)
                field(p, "wlan.mmie.mic", raw="aa"*8)
                if duplicate: field(p, "wlan.mmie.keyid", 5)
                self.assertEqual("group_mmie_incomplete", self.inspect([p])["management_frames"][0]["format"])

    def test_public_action_and_encrypted_opaque_action_are_not_sa_query_evidence(self):
        p = mgmt(subtype=13); field(p, "wlan.fixed.category_code", 4)
        opaque = mgmt(2, subtype=13, protected=1)
        result = self.inspect([p, opaque])
        self.assertEqual("public_action_outside_basic_pmf", result["management_frames"][0]["format"])
        self.assertEqual([], result["sa_query_pairs"])

    def test_sa_query_matching_never_means_authenticated(self):
        result = self.inspect([query(1, 0, AP, STA), query(2, 1, STA, AP)])
        pair = result["sa_query_pairs"][0]
        self.assertEqual(1, pair["request_frame"])
        self.assertEqual("matching_ids_and_direction", pair["pairing"])
        self.assertIsNone(pair["authenticated_exchange"])
        for peer, tid, stamp in ((OTHER, 123, 2), (STA, 124, 2), (STA, 123, 5)):
            pair = self.inspect([query(1, 0, AP, STA), query(2, 1, peer, AP, tid, stamp)])["sa_query_pairs"][0]
            self.assertIsNone(pair["request_frame"])

    def test_malformed_and_nested_profiles_cannot_qualify_primary_evidence(self):
        beacon = packet(); container = field(beacon, "wlan.tag"); field(container, "wlan.tag.number", 71)
        rsn(container, (8,), 192)
        malformed = mgmt(2, protected=1); ET.SubElement(malformed, "proto", {"name":"_ws.malformed"})
        result = self.inspect([beacon, malformed])
        self.assertEqual("unknown", result["networks"][0]["pmf_advertised"])
        self.assertEqual("unknown_or_malformed", result["management_frames"][0]["format"])

    def test_budget_and_untrusted_xml_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "budget"):
            self.inspect([packet()], deadline=0)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"bad.xml"
            for text in ('<!DOCTYPE pdml [<!ENTITY x "bad">]><pdml/>', '<pdml><packet>'):
                path.write_text(text)
                with self.assertRaises(ValueError): inspect_pdml(path)

    @unittest.skipUnless(TSHARK, "TShark is required for actual PCAP dissection")
    def test_actual_decoder_group_mmie_and_sa_query_fields(self):
        ap, sta = (bytes.fromhex(m.replace(":", "")) for m in (AP, STA))
        def header(fc, da, sa): return struct.pack("<HH", fc, 0)+da+sa+ap+b"\0\0"
        frames = [header(0x00c0, b"\xff"*6, ap)+struct.pack("<H", 3)+bytes([76, 16])+struct.pack("<H", 4)+b"\x01"+b"\0"*5+b"\xaa"*8,
                  header(0x00d0, sta, ap)+b"\x08\x00\x12\x34",
                  header(0x00d0, ap, sta)+b"\x08\x01\x12\x34"]
        with tempfile.TemporaryDirectory() as directory:
            capture, pdml = Path(directory)/"synthetic.pcap", Path(directory)/"synthetic.xml"
            with capture.open("wb") as stream:
                stream.write(struct.pack("<IHHIIII", 0xa1b2c3d4, 2, 4, 0, 0, 65535, 105))
                for i, raw in enumerate(frames, 1):
                    stream.write(struct.pack("<IIII", i, 0, len(raw), len(raw))); stream.write(raw)
            with pdml.open("wb") as stream:
                subprocess.run([TSHARK, "-n", "-r", str(capture), "-T", "pdml"], stdout=stream, stderr=subprocess.PIPE, check=True, timeout=15)
            result = inspect_pdml(pdml)
            self.assertEqual("group_mmie_structure_unverified", result["management_frames"][0]["format"])
            self.assertEqual("matching_ids_and_direction", result["sa_query_pairs"][0]["pairing"])
            self.assertEqual("not_established", result["pmf_bypass"])


if __name__ == "__main__":
    unittest.main()
