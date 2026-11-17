"""Identity inference input tests: attribution and data minimization, no network traffic."""
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ET

from test_observations import field, packet, AP, STA
from bender_engine import identity
from bender_engine.analysis import analyze_pdml, analyze_pcap


class IdentityTests(unittest.TestCase):
    def analyze(self, packets):
        root = ET.Element('pdml'); root.extend(packets)
        with tempfile.TemporaryDirectory() as name:
            file = Path(name)/'capture.xml'; ET.ElementTree(root).write(file)
            return analyze_pdml(file)

    def test_ap_model_never_becomes_receiving_client_model(self):
        beacon = packet(); field(beacon, 'wps.model_name', 'RouterModel')
        reply = packet(2, 5, da=STA); field(reply, 'wps.model_name', 'RouterModel')
        station = packet(3, 0, sa=STA, da=AP); field(station, 'wps.model_name', 'CameraModel')
        out = self.analyze([beacon, reply, station])
        self.assertEqual([x['value'] for x in out['networks'][0]['identity_hints']], ['RouterModel'])
        self.assertEqual([x['value'] for x in out['clients'][0]['identity_hints']], ['CameraModel'])

    def test_nested_profile_and_corrupt_frames_cannot_name_a_device(self):
        beacon = packet()
        tag = field(beacon, 'wlan.tag'); nested = field(tag, 'wlan.tag')
        field(nested, 'wps.model_name', 'NestedRouter')
        good = packet(2, 0, sa=STA, da=AP)
        bad = packet(3, 0, sa=STA, da=AP); field(bad, 'radiotap.flags.badfcs', 1); field(bad, 'wps.model_name', 'BadModel')
        malformed = packet(4, 0, sa=STA, da=AP); field(malformed, '_ws.malformed'); field(malformed, 'wps.device_name', 'TruncatedName')
        out = self.analyze([beacon, good, bad, malformed])
        self.assertNotIn('identity_hints', out['clients'][0])
        self.assertNotIn('identity_hints', out['networks'][0])

    def test_query_and_ap_downlink_do_not_identify_the_station(self):
        for response, downlink, expected in [(0, False, []), (1, True, []), (1, False, ['_ipp._tcp.local'])]:
            p = packet(2, 32, sa=AP if downlink else STA, da=STA if downlink else AP)
            field(p, 'wlan.fc.protected', 0); field(p, 'udp.srcport', 5353); field(p, 'dns.flags.response', response)
            answer = field(p, 'dns.answer'); field(answer, 'dns.resp.name', '_ipp._tcp.local'); field(answer, 'dns.resp.type', 12)
            hints = identity.extract(p, 32, AP if downlink else STA, AP if downlink else STA, {'frame': 2, 'time': 2})
            self.assertEqual([h['value'] for h in hints], expected)

    def test_dhcp_requires_request_matching_client_and_cleartext(self):
        for operation, address, protected, expected in [(1, STA, 0, ['lamp-local']), (2, STA, 0, []), (1, AP, 0, []), (1, STA, 1, [])]:
            p = packet(2, 32, sa=STA, da=AP)
            field(p, 'wlan.fc.protected', protected); field(p, 'dhcp.type', operation)
            field(p, 'dhcp.hw.mac_addr', address); field(p, 'dhcp.option.hostname', 'lamp-local')
            field(p, 'dhcp.option.client_id', 'PRIVATE_IDENTIFIER')
            hints = identity.extract(p, 32, STA, STA, {'frame': 2, 'time': 2})
            self.assertEqual([h['value'] for h in hints], expected)

    def test_bad_type_namespace_and_untrusted_strings_are_excluded(self):
        p = packet(2, 0, sa=STA, da=AP)
        field(p, 'wps.primary_device_type', raw='0001aabbccdd0005')
        field(p, 'wps.device_name', 'hidden\u202eexe')
        field(p, 'wps.manufacturer', 'x'*161)
        field(p, 'wps.serial_number', 'PRIVATE_SERIAL')
        self.assertEqual(identity.extract(p, 0, STA, STA, {'frame': 2, 'time': 2}), [])

    def test_metadata_does_not_create_or_merge_clients(self):
        probe = packet(2, 4, ap='ff:ff:ff:ff:ff:ff', sa=STA, da='ff:ff:ff:ff:ff:ff')
        field(probe, 'wps.device_name', 'Same name')
        other = packet(3, 0, sa='02:00:00:00:00:03', da=AP); field(other, 'wps.device_name', 'Same name')
        out = self.analyze([packet(), probe, other])
        self.assertEqual([c['mac'] for c in out['clients']], ['02:00:00:00:00:03'])

    def test_repeated_hints_are_bounded_and_keep_latest_provenance(self):
        old = [{'hint': 'wps_name', 'value': str(i), 'time': i, 'frame': i+1} for i in range(30)]
        new = {'hint': 'wps_name', 'value': '29', 'time': 99, 'frame': 100}
        merged = identity.merge(old, [new])
        self.assertEqual(len(merged), 24); self.assertEqual(merged[0], new)

    @unittest.skipUnless(shutil.which('tshark'), 'TShark required for real decoder test')
    def test_real_decoder_wps_dhcp_and_mdns_inputs(self):
        ap = bytes.fromhex(AP.replace(':', '')); sta = bytes.fromhex(STA.replace(':', ''))
        def wlan(fc, destination=ap): return struct.pack('<HH', fc, 0)+ap+sta+destination+b'\x10\x00'
        def attr(kind, data): return struct.pack('>HH',kind,len(data))+data
        wps = b'\x00\x50\xf2\x04'+attr(0x1021,b'Lab Camera')+attr(0x1023,b'LensOne')+attr(0x1011,b'Desk Camera')+attr(0x1054,bytes.fromhex('00040050f2040001'))
        association = wlan(0)+b'\x01\x00\x0a\x00'+b'\x00\x03Lab'+b'\x01\x01\x82'+bytes([221,len(wps)])+wps
        def data(payload, source_port, dest_port, dest_ip):
            udp = struct.pack('!HHHH', source_port,dest_port,8+len(payload),0)+payload
            ip = bytearray(struct.pack('!BBHHHBBH4s4s',0x45,0,20+len(udp),1,0,64,17,0,bytes([192,0,2,2]),dest_ip))
            total=sum(struct.unpack('!10H',ip)); total=(total&0xffff)+(total>>16);total=(total&0xffff)+(total>>16)
            ip[10:12]=struct.pack('!H',(~total)&0xffff)
            return wlan(0x0108)+bytes.fromhex('aaaa030000000800')+bytes(ip)+udp
        bootp=bytearray(236);bootp[:4]=b'\x01\x01\x06\x00';bootp[28:34]=sta
        host=b'camera-local';vendor=b'android-dhcp-14'
        dhcp=bytes(bootp)+bytes.fromhex('63825363')+b'\x35\x01\x01'+bytes([12,len(host)])+host+bytes([60,len(vendor)])+vendor+b'\xff'
        def name(value): return b''.join(bytes([len(part)])+part.encode() for part in value.split('.'))+b'\0'
        target=name('Desk._ipp._tcp.local')
        dns=struct.pack('!6H',0,0x8400,0,1,0,0)+name('_ipp._tcp.local')+struct.pack('!HHIH',12,1,120,len(target))+target
        frames=[association,data(dhcp,68,67,bytes([255]*4)),data(dns,5353,5353,bytes([224,0,0,251]))]
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'identity.pcap'
            with p.open('wb') as out:
                out.write(struct.pack('<IHHIIII',0xa1b2c3d4,2,4,0,0,65535,127))
                for i,frame in enumerate(frames,1):
                    raw=bytes.fromhex('0000080000000000')+frame
                    out.write(struct.pack('<IIII',i,0,len(raw),len(raw))+raw)
            result=analyze_pcap(p,tshark=shutil.which('tshark'))
        clues={h['hint']:h['value'] for h in result['clients'][0]['identity_hints']}
        self.assertEqual(clues['wps_model'],'LensOne')
        self.assertEqual(clues['wps_type'],'00040050f2040001')
        self.assertEqual(clues['dhcp_hostname'],'camera-local')
        self.assertEqual(clues['mdns_service'],'_ipp._tcp.local')


if __name__ == '__main__': unittest.main()
