"""Bounded, self-reported identity clues. No discovery traffic or identity merging."""
import re
import unicodedata

MAX_HINTS = 24
SERVICE = re.compile(r'^_[a-z0-9_-]{1,48}\._(?:tcp|udp)\.local\.?$', re.I)
WPS_TEXT = {'wps.device_name': 'wps_name', 'wps.manufacturer': 'wps_manufacturer',
            'wps.model_name': 'wps_model', 'wps.model_number': 'wps_model_number'}


def text(value):
    if not isinstance(value, str): return None
    value = value.strip()
    if not value or len(value) > 160 or any(unicodedata.category(c).startswith('C') or c in '\u2028\u2029' for c in value): return None
    return value


def values(packet, name):
    return [node.get('show', '') for node in packet.iter('field') if node.get('name') == name]


def integer(packet, name):
    found = values(packet, name)
    try:
        val = found[0].lower()
        return int(val == 'true') if val in ('true', 'false') else int(val, 16 if val.startswith('0x') else 10)
    except (IndexError, ValueError, AttributeError): return None


def extract(packet, subtype, transmitter, source, provenance):
    """Caller supplies an owned (non-nested) view of a non-malformed good frame.

    WPS management attributes belong to their transmitter. IP hints require an
    unprotected station-to-AP data frame with the station as logical source.
    AP downlink traffic, relay/proxy forwarding and service queries are excluded.
    A device may still advertise on behalf of another host: these remain clues.
    """
    if not transmitter or transmitter != source: return []
    result = []
    def add(kind, val):
        clean = text(val)
        if clean is not None: result.append({'hint': kind, 'value': clean, **provenance})
    if subtype in (0, 2, 4, 5, 8):
        for field, kind in WPS_TEXT.items():
            for val in values(packet, field)[:4]: add(kind, val)
        for node in packet.iter('field'):
            if node.get('name') != 'wps.primary_device_type': continue
            raw = node.get('value', '').replace(':', '').lower()
            if re.fullmatch(r'[0-9a-f]{16}', raw) and raw[4:12] == '0050f204': add('wps_type', raw)
    elif subtype is not None and 32 <= subtype <= 47 and integer(packet, 'wlan.fc.tods') == 1 and integer(packet, 'wlan.fc.fromds') == 0 and integer(packet, 'wlan.fc.protected') == 0:
        if integer(packet, 'dhcp.type') == 1 and values(packet, 'dhcp.hw.mac_addr') == [transmitter]:
            for field, kind in [('dhcp.option.hostname', 'dhcp_hostname'), ('dhcp.option.vendor_class_id', 'dhcp_vendor')]:
                for val in values(packet, field)[:4]: add(kind, val)
        if integer(packet, 'udp.srcport') == 5353 and integer(packet, 'dns.flags.response') == 1:
            # A PTR answer advertises a service. A question asking for a printer
            # must not label the querying phone as a printer.
            for record in packet.iter():
                direct = {child.get('name'): child.get('show', '') for child in record if child.tag == 'field'}
                service = direct.get('dns.resp.name', '').lower()
                if direct.get('dns.resp.type') == '12' and SERVICE.fullmatch(service): add('mdns_service', service.rstrip('.'))
    return merge([], result)


def merge(previous, current):
    unique = {}
    for item in previous + current:
        key = (item['hint'], item['value'])
        if key not in unique or item['time'] >= unique[key]['time']: unique[key] = item
    return sorted(unique.values(), key=lambda row: (row['time'], row['frame']), reverse=True)[:MAX_HINTS]
