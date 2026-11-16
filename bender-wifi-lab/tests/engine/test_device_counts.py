"""Device inventory regressions; synthetic frames, no radio operations."""
from pathlib import Path
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ET

import test_observations as fixtures
from test_observations import AP, STA, TSHARK, field, packet
from bender_engine.analysis import analyze_pdml

OTHER = "02:00:00:00:00:03"


def control(subtype=27, ta=STA, ra=AP, frame=1, timestamp=None, frequency=None):
    p = packet(frame, subtype, sa=ta, da=ra, timestamp=timestamp)
    for item in list(p):
        if item.get("name") in ("wlan.bssid", "wlan.sa", "wlan.da", "wlan.fc.tods", "wlan.fc.fromds"):
            p.remove(item)
    if frequency is not None:
        field(p, "radiotap.channel.freq", frequency)
    return p


def load(p, count, length=5):
    tag = field(p, "wlan.tag")
    field(tag, "wlan.tag.number", 11)
    field(tag, "wlan.tag.length", length)
    field(tag, "wlan.qbss.scount", count)
    return tag


class DeviceCountTests(unittest.TestCase):
    def analyze(self, packets):
        with tempfile.TemporaryDirectory() as directory:
            root = ET.Element("pdml")
            root.extend(packets)
            path = Path(directory) / "inventory.pdml"
            ET.ElementTree(root).write(path)
            return analyze_pdml(path)

    def test_control_peers_resolve_after_beacon_and_deduplicate(self):
        packets = [control(subtype=t, frame=i) for i, t in enumerate((24, 25, 26, 27), 1)]
        result = self.analyze(packets + [packet(5)])
        self.assertEqual([STA], [c["mac"] for c in result["clients"]])
        client = result["clients"][0]
        self.assertEqual("ap:" + AP, client["ap_id"])
        self.assertEqual(4, len(client["relations"]))
        self.assertTrue(all(r["kind"] == "observed_control_peer" and r["negotiated_pmf"] == "unknown" for r in client["relations"]))

    def test_unknown_ap_ap_to_ap_ack_cts_and_multicast_do_not_invent_clients(self):
        for frames in ([control()], [packet(), packet(2, ap=STA, sa=STA), control()],
                       [packet(), control(28)], [packet(), control(29)],
                       [packet(), control(ta="ff:ff:ff:ff:ff:ff")]):
            with self.subTest(frames=len(frames)):
                self.assertEqual([], self.analyze(frames)["clients"])

    def test_bad_fcs_and_different_channel_control_peers_are_excluded(self):
        beacon = packet()
        field(beacon, "radiotap.channel.freq", 2412)
        bad = control(frame=2, frequency=2412)
        field(bad, "radiotap.flags.badfcs", 1)
        malformed = control(frame=4)
        field(malformed, "_ws.malformed")
        self.assertEqual([], self.analyze([beacon, bad, control(frame=3, frequency=5180), malformed])["clients"])

    def test_repeated_control_traffic_retains_bounded_evidence_and_first_seen(self):
        result = self.analyze([packet()] + [control(frame=i) for i in range(2, 1002)])
        client = result["clients"][0]
        self.assertEqual(2, client["first_seen"])
        self.assertEqual(1001, client["last_seen"])
        self.assertEqual(1, len(client["relations"]))
        self.assertEqual(1000, client["relations"][0]["frame_count"])

    def test_older_control_or_data_cannot_replace_newer_ap_relation(self):
        newer = packet(2, 32, ap=OTHER, sa=STA, da=OTHER, timestamp=20)
        older = packet(3, 32, sa=STA, da=AP, timestamp=5)
        result = self.analyze([packet(), newer, older, control(frame=4, timestamp=10)])
        client = result["clients"][0]
        self.assertEqual("ap:" + OTHER, client["ap_id"])
        self.assertEqual(20, client["last_seen"])
        self.assertEqual([5, 10, 20], [r["time"] for r in client["relations"]])

    def test_latest_valid_ap_load_preserves_zero_and_timestamp(self):
        old, new = packet(1, timestamp=10), packet(2, timestamp=20)
        load(old, 12)
        load(new, 0)
        result = self.analyze([new, old, packet(3, timestamp=30)])
        self.assertEqual({"station_count": 0, "time": 20.0, "frame": 2}, result["networks"][0]["bss_load"])
        self.assertEqual([], result["clients"])

    def test_invalid_duplicate_and_nested_ap_load_are_unknown(self):
        for count, length in ((-1, 5), (65536, 5), (2, 4)):
            p = packet()
            load(p, count, length)
            self.assertNotIn("bss_load", self.analyze([p])["networks"][0])
        malformed = packet()
        load(malformed, 6)
        field(malformed, "_ws.malformed")
        duplicate = packet()
        load(duplicate, 1)
        load(duplicate, 2)
        nested = packet()
        parent = field(nested, "wlan.tag")
        load(parent, 99)
        for p in (malformed, duplicate, nested, packet()):
            self.assertNotIn("bss_load", self.analyze([p])["networks"][0])

    @unittest.skipUnless(TSHARK, "TShark is not installed")
    def test_real_decoder_sees_control_only_station_and_ap_total(self):
        ap, sta = (bytes.fromhex(m.replace(":", "")) for m in (AP, STA))
        beacon = struct.pack("<HH", 0x0080, 0) + b"\xff" * 6 + ap + ap + b"\0\0"
        beacon += struct.pack("<QHH", 0, 100, 1) + b"\x00\x03Lab\x0b\x05" + struct.pack("<HBH", 4, 10, 0)
        rts = struct.pack("<HH", 0x00b4, 0) + ap + sta
        result = fixtures.ObservationTests().decoded_frames([rts, beacon])
        self.assertEqual([STA], [c["mac"] for c in result["clients"]])
        self.assertEqual(4, result["networks"][0]["bss_load"]["station_count"])


if __name__ == "__main__":
    unittest.main()
