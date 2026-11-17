"""Produce two redacted packet records from the hash-identified private original.

The output is JSON, not a replacement PCAP. Address and FCS bytes are withheld.
No credentials, handshake frames, SSIDs or unrelated packets are exported.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zlib

EXPECTED = '7979f08c36b420d76b9de15b373fffed1fbaf3cf5986532350bf0954c739dc4a'


def extract(path):
    blob = path.read_bytes()
    if hashlib.sha256(blob).hexdigest() != EXPECTED:
        raise ValueError('not the retained original capture')
    if blob[:4] != b'\xd4\xc3\xb2\xa1' or struct.unpack_from('<I', blob, 20)[0] != 127:
        raise ValueError('expected little-endian microsecond PCAP with radiotap')
    offset, count, selected = 24, 0, {}
    while offset < len(blob):
        sec, usec, captured, original = struct.unpack_from('<IIII', blob, offset)
        offset += 16
        raw = blob[offset:offset+captured]
        offset += captured
        if len(raw) != captured or captured != original:
            raise ValueError('truncated packet')
        count += 1
        if count in (272, 273):
            header_length = struct.unpack_from('<H', raw, 2)[0]
            selected[count] = (raw[:header_length], raw[header_length:], sec*1000000+usec)
    if offset != len(blob) or count != 1293:
        raise ValueError('unexpected capture structure')
    records = []
    for number, expected_len, frame_control in ((272, 30, b'\xc0\x00'), (273, 14, b'\xd4\x00')):
        rt, frame, epoch_us = selected[number]
        if len(frame) != expected_len or frame[:2] != frame_control:
            raise ValueError('unexpected frame shape')
        if struct.unpack('<I', frame[-4:])[0] != zlib.crc32(frame[:-4]):
            raise ValueError('original FCS verification failed')
        # Two presence words, including a namespace reset, then alignment padding.
        # Verify exact layout before interpreting fixed radiotap offsets.
        present = struct.unpack_from('<I', rt, 4)[0]
        if len(rt) != 36 or rt[:16] != bytes.fromhex('000024002f4000a02008000000000000'):
            raise ValueError(f'unexpected radiotap layout: {len(rt)} {present:#x}')
        if rt[24] & 0x40 or not rt[24] & 0x10:
            raise ValueError('bad FCS flag or FCS not included')
        masked = [f'{value:02x}' for value in frame]
        spans = [(4, 22, 'addresses'), (26, 30, 'FCS')] if number == 272 else [(4, 10, 'receiver address'), (10, 14, 'FCS')]
        for start, end, role in spans:
            masked[start:end] = ['xx'] * (end-start)
        records.append(dict(packet=number, captured_bytes=len(rt)+len(frame),
                            radiotap_bytes=len(rt), radiotap_hex=rt.hex(' '),
                            radiotap_present_hex=f'0x{present:08x}',
                            radiotap_second_present_hex='0x00000820',
                            tsft_us=struct.unpack_from('<Q', rt, 16)[0],
                            rate_mbps=rt[25]/2, short_preamble=bool(rt[24] & 2),
                            frequency_mhz=struct.unpack_from('<H', rt, 26)[0],
                            ieee80211_bytes_including_fcs=len(frame),
                            ieee80211_redacted_hex=' '.join(masked),
                            redacted_ranges=[dict(start=s, end_exclusive=e, field=r) for s,e,r in spans],
                            duration_us=struct.unpack_from('<H', frame, 2)[0],
                            original_fcs_verified=True))
    deauth, ack = selected[272][1], selected[273][1]
    if deauth[10:16] != deauth[16:22] or ack[4:10] != deauth[10:16]:
        raise ValueError('unexpected address relationship')
    if struct.unpack_from('<H', deauth, 22)[0] != 1008*16 or deauth[24:26] != b'\x03\x00':
        raise ValueError('unexpected sequence or reason')
    delta = records[1]['tsft_us']-records[0]['tsft_us']
    if delta != 442 or records[0]['duration_us'] != 314:
        raise ValueError('unexpected timing')
    return dict(schema_version=1, source_capture_sha256=EXPECTED,
                source_capture_bytes=len(blob), source_capture_packets=count,
                description='Redacted transcription of two original packets, not a PCAP or independently verifiable FCS proof.',
                address_roles={'272.destination': 'client', '272.source': 'AP',
                               '272.bssid': 'AP', '273.receiver': 'AP'},
                redaction='xx marks a withheld byte; the FCS checks above were made on the unchanged private original.',
                packets=records, tsft_delta_us=delta,
                pcap_delivery_delta_us=selected[273][2]-selected[272][2],
                timing={'deauth_exchange_us': '192 + 30*8 + 10 = 442',
                        'reserved_ack_exchange_us': '10 + 192 + 14*8 = 314',
                        'assumptions': '1 Mbps; long preamble/header 192 us; SIFS 10 us; FCS included in byte counts'},
                limits=['The ACK has no explicit transmitter address; sender attribution is inferred.',
                        'Redacted address and FCS bytes prevent independent FCS recomputation from this file.',
                        'The observed Duration/ID change does not identify the software or firmware layer that made it.',
                        'Requested off-channel duration is not a measured dwell time.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    data = extract(args.capture)
    args.output.write_bytes((json.dumps(data, indent=2)+'\n').encode())
    print(json.dumps({'packets': [r['packet'] for r in data['packets']],
                      'original_fcs_verified': True, 'tsft_delta_us': data['tsft_delta_us'],
                      'radiotap_lengths': [r['radiotap_bytes'] for r in data['packets']]}))


if __name__ == '__main__':
    main()
