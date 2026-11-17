"""Reduce a local `tshark -G manuf` snapshot into an offline longest-prefix table."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import unicodedata


def convert(text):
    rows = {}
    for line in text.splitlines():
        parts = line.split('\t')
        if len(parts) != 3:
            continue
        prefix, short, name = (p.strip() for p in parts)
        if not re.fullmatch(r'(?:[0-9A-Fa-f]{2}:){2,5}[0-9A-Fa-f]{2}(?:/[0-9]{2})?', prefix):
            continue
        address, _, mask = prefix.partition('/')
        octets = address.split(':')
        bits = int(mask) if mask else len(octets)*8
        if not 24 <= bits <= 48 or int(octets[0], 16) & 3:
            continue
        full = int(''.join(octets).ljust(12, '0'), 16)
        value = full >> (48-bits)
        if value == 0 or not name or len(name) > 200 or any(unicodedata.category(c).startswith('C') or c in '\u2028\u2029' for c in name):
            continue
        key = (bits, value)
        if key in rows and rows[key] != name:
            raise ValueError('Conflicting manufacturer prefix')
        rows[key] = name
    assert len(rows) > 40000, 'Incomplete manufacturer snapshot'
    return ''.join(f'{bits}\t{value:x}\t{name}\n' for (bits, value), name in sorted(rows.items()))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('input', type=Path)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = args.input.read_bytes()
    data = convert(source.decode('utf-8')).encode('utf-8')
    target = root/'runtime/device-vendors.tsv'
    target.write_bytes(data)
    metadata = {'schema_version': 1, 'source': 'Wireshark compiled manufacturer table; tshark -G manuf',
                'decoder_version': args.version, 'snapshot_date': '2026-11-16',
                'reference': 'https://www.wireshark.org/docs/man-pages/tshark',
                'source_sha256': hashlib.sha256(source).hexdigest(),
                'table_sha256': hashlib.sha256(data).hexdigest(), 'entries': len(data.splitlines()),
                'limits': 'Registered address-prefix assignee, not confirmed product brand or device model. Locally administered and group prefixes excluded.'}
    (root/'runtime/device-vendors-source.json').write_text(json.dumps(metadata, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(metadata))


if __name__ == '__main__': main()
