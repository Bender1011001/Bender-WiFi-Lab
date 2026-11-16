"""Real-decoder regressions for review findings; all frames are synthetic."""
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import test_observations as fixtures
from test_observations import packet, field, rsn, eapol, TSHARK
from bender_engine.analysis import analyze_pcap, selectors

ROOT = Path(__file__).resolve().parents[2]
AP = bytes.fromhex("020000000001")
STA = bytes.fromhex("020000000010")
WPA = bytes.fromhex("dd160050f20101000050f20401000050f20401000050f202")
PSK = bytes.fromhex("30140100000fac040100000fac040100000fac020000")
MIXED = bytes.fromhex("30180100000fac040100000fac040200000fac02000fac088000")


def beacon(security=b"", private=False, ap=AP):
    return (struct.pack("<HH", 0x0080, 0) + b"\xff"*6 + ap*2 + b"\0\0"
            + struct.pack("<QHH", 0, 100, 0x0401 | (0x10 if private else 0))
            + b"\x00\x09SYNTHETIC\x03\x01\x06" + security)


def handshake(msg):
    replay, nonce, info = {1:(5,b"\x11"*32,0x008a), 2:(5,b"\x22"*32,0x010a),
                           3:(6,b"\x11"*32,0x13ca), 4:(6,b"\0"*32,0x030a)}[msg]
    from_ap = msg in (1,3)
    header = struct.pack("<HH",0x0208 if from_ap else 0x0108,0)+(STA+AP if from_ap else AP+STA)+AP+b"\0\0"
    data = PSK if msg==2 else b""
    key = b"\x02"+struct.pack(">HHQ",info,16,replay)+nonce+b"\0"*32+b"\x55"*16+struct.pack(">H",len(data))+data
    return header+bytes.fromhex("aaaa03000000888e")+struct.pack(">BBH",2,3,len(key))+key


def write_capture(path, frames, radiotap=False):
    with path.open("wb") as stream:
        stream.write(struct.pack("<IHHIIII",0xa1b2c3d4,2,4,0,0,65535,127 if radiotap else 105))
        for i,raw in enumerate(frames,1):
            stream.write(struct.pack("<IIII",i,0,len(raw),len(raw)));stream.write(raw)


class SecurityIntegrityTests(unittest.TestCase):
    analyze = fixtures.ObservationTests.analyze

    def test_real_decoder_open_wpa_wpa2_and_mixed_labels(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"catalog.pcap"
            write_capture(path,[beacon(sec,bool(sec),AP[:-1]+bytes([i])) for i,sec in enumerate((b"",WPA,PSK,MIXED),1)])
            data=analyze_pcap(path,TSHARK)
        self.assertEqual(["Open","WPA","WPA2-Personal","WPA2/WPA3-Personal"],[n["security"] for n in data["networks"]])
        ie=data["networks"][1]["advertisements"][0]["security_ies"][0]
        self.assertEqual("decoded",ie["parse_status"])
        self.assertEqual("CCMP-128",ie["pairwise_ciphers"][0]["name"])
        self.assertEqual("CCMP-128",ie["group_ciphers"][0]["name"])

    def test_real_decoder_bad_beacon_and_m2_excluded_in_both_paths(self):
        spec=importlib.util.spec_from_file_location("scanner_review",ROOT/"runtime/qca-scan.py")
        scanner=importlib.util.module_from_spec(spec);spec.loader.exec_module(scanner)
        radio=lambda bad:struct.pack("<BBHIB",0,0,9,2,0x40 if bad else 0)
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"bad.pcap"
            write_capture(path,[radio(True)+beacon(PSK,True)]+[radio(m==2)+handshake(m) for m in range(1,5)],True)
            before=path.read_bytes()
            data=analyze_pcap(path,TSHARK)
            scanner.analyze_capture(path,Path(tmp)/"scan",TSHARK,False)
            scanned=json.loads((Path(tmp)/"scan/capture-report.json").read_text())
            self.assertEqual(before,path.read_bytes())
        self.assertEqual([],data["networks"])
        self.assertFalse(any(x["coverage"]=="four_messages_observed" for x in data["handshakes"]))
        self.assertEqual(2,data["source"]["frame_integrity"]["failed"])
        self.assertEqual(0,data["source"]["frame_integrity"]["verified"])
        self.assertEqual(2,scanned["bad_fcs_excluded"])
        self.assertEqual([1,3,4],scanned["eapol"][0]["message_numbers_observed"])

    def test_absent_good_and_bad_metadata_are_distinct(self):
        packets=[packet(i) for i in range(1,5)]
        field(packets[1],"radiotap.flags.badfcs",0)
        field(packets[2],"wlan.fcs.status",1)
        field(packets[3],"radiotap.flags.badfcs",0);field(packets[3],"wlan.fcs.status",0)
        data=self.analyze(packets)
        self.assertEqual({"unknown":1,"not_flagged":1,"verified":1,"failed":1},data["source"]["frame_integrity"])
        self.assertEqual(3,len(data["networks"][0]["advertisements"]))

    def test_missing_truncated_or_malformed_frame_is_not_open(self):
        for mode in ("missing_length","truncated","malformed"):
            p=packet();field(p,"wlan.fixed.capabilities.privacy",0)
            if mode!="missing_length":field(p,"frame.len",100);field(p,"frame.cap_len",80 if mode=="truncated" else 100)
            if mode=="malformed":field(p,"_ws.malformed")
            self.assertEqual("unknown",self.analyze([p])["networks"][0]["security"])

    def test_conflicting_advertisements_preserved(self):
        packets=[]
        for i,akm in enumerate((2,8),1):
            p=packet(i);field(p,"frame.len",100);field(p,"frame.cap_len",100);field(p,"wlan.fixed.capabilities.privacy",1);rsn(p,(akm,));packets.append(p)
        net=self.analyze(packets)["networks"][0]
        self.assertTrue(net["security_conflict"])
        self.assertEqual(2,len(net["security_variants"]))
        self.assertTrue(net["security"].startswith("Conflicting: "))
        self.assertEqual("unknown",net["negotiated_pmf"])

    def test_selector_protocol_and_kind_are_separate(self):
        p=packet();tag=rsn(p,(8,),wpa=True)
        self.assertEqual("unknown",selectors(tag,"wlan.wfa.ie.wpa.akms","akm")[0]["name"])
        self.assertEqual(["CCMP-128","unknown"],[s["name"] for s in selectors(tag,"wlan.wfa.ie.wpa.ucs","cipher")])
        field(tag,"wlan.rsn.pcs",raw="0050f204")
        self.assertEqual("unknown",selectors(tag,"wlan.rsn.pcs","cipher")[0]["name"])
