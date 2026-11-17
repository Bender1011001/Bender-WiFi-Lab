"""Structured PDML ingestion. Never treats absent packets as a successful test.

RSN/WPA selectors are read within each IE, preserving repeated IEs and order.
Only explicitly selected metadata leaves the decoder; packet payloads, passwords,
EAPOL MIC/key data/nonces and WPS credential attributes are not serialized.
"""
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from . import identity

# This bounds temporary decoder output, not resident memory: packets are parsed
# incrementally. A measured 1 MiB / 11,290-frame survey expands above 270 MiB.
MAX_XML_BYTES = 512 * 1024 * 1024
MAX_PACKETS = 100000
MAX_FIELDS = 12000
MAX_SECONDS = 120
MAC = re.compile(r"^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$")
AKMS = {1: "802.1X", 2: "PSK", 3: "FT-802.1X", 4: "FT-PSK", 5: "802.1X-SHA256", 6: "PSK-SHA256", 8: "SAE", 9: "FT-SAE", 11: "802.1X-Suite-B", 12: "802.1X-Suite-B-192", 18: "OWE"}
CIPHERS = {0: "Use-group", 1: "WEP-40", 2: "TKIP", 4: "CCMP-128", 5: "WEP-104", 6: "BIP-CMAC-128", 8: "GCMP-128", 9: "GCMP-256", 10: "CCMP-256", 11: "BIP-GMAC-128", 12: "BIP-GMAC-256", 13: "BIP-CMAC-256"}


class AnalysisCancelled(RuntimeError):
    pass


def check_budget(deadline, cancel_file=None):
    if cancel_file is not None and Path(cancel_file).is_file():
        raise AnalysisCancelled("Analysis cancelled")
    if time.monotonic() >= deadline:
        raise ValueError("Shared analysis time budget exceeded")


def fields(element, name):
    return [x for x in element.iter("field") if x.get("name") == name]


def owned_view(element, in_tag=False):
    """Copy only this BSS/IE's fields; nested profiles are separate evidence.

    PDML flattens some dissector containers but retains nested wlan.tag owners.
    Per-STA MLO subelements also cannot supply the enclosing common-info identity.
    """
    clone = ET.Element(element.tag, element.attrib)
    in_tag = in_tag or element.get("name") == "wlan.tag"
    for child in element:
        name = child.get("name", "")
        if (in_tag and name == "wlan.tag") or name.startswith("wlan.eht.multi_link.subelem"):
            continue
        clone.append(owned_view(child, in_tag))
    return clone


def nested_security(packet):
    """Retain nested security without guessing a nontransmitted BSSID."""
    result = []
    def visit(node, owners=()):
        if node.get("name") == "wlan.tag":
            path = owners + (len(paths),)
            paths.append(path)
            if owners:
                wrapper = ET.Element("profile")
                wrapper.append(owned_view(node))
                for ie in security_ies(wrapper):
                    result.append({**ie, "owner_tag_path": list(path), "bssid": None,
                                   "scope": "nested_profile_identity_unresolved"})
            owners = path
        for child in node:
            visit(child, owners)
    paths = []
    visit(packet)
    return result


def station_addresses(packet, bssid, subtype):
    """Logical SA/DA may be wired peers. Only explicit radio roles qualify."""
    ta, ra = mac(packet, "wlan.ta"), mac(packet, "wlan.ra")
    if subtype is not None and 32 <= subtype <= 47:
        ds = (number(packet, "wlan.fc.tods"), number(packet, "wlan.fc.fromds"))
        address = ta if ds == (1, 0) and ra == bssid else ra if ds == (0, 1) and ta == bssid else None
    elif subtype in (0, 1, 2, 3, 4, 5, 10, 11, 12, 13):
        address = ra if ta == bssid else ta if ra == bssid else None
    else:
        address = None
    return [address] if address and address != bssid else []


def value(element, name, default=None, raw=False):
    found = fields(element, name)
    return found[0].get("value" if raw else "show", default) if found else default


def number(element, name):
    item = value(element, name)
    try:
        if item.lower() in ("true", "false"):
            return int(item.lower() == "true")
        return int(item, 16 if item.lower().startswith("0x") else 10)
    except (ValueError, TypeError, AttributeError):
        return None


def real(element, name):
    try:
        parsed = float(value(element, name))
        return parsed if math.isfinite(parsed) else None
    except (ValueError, TypeError):
        return None


def selectors(tag, name, kind):
    result = []
    for item in fields(tag, name):
        raw = item.get("value", "").replace(":", "").lower()
        if len(raw) != 8 or not re.fullmatch("[0-9a-f]{8}", raw):
            continue
        oui, typ = raw[:6], int(raw[6:], 16)
        # WPA and RSN own different selector namespaces. WPA authentication
        # defines types 1/2, while its cipher namespace also defines CCMP/WEP104.
        wpa = name.startswith("wlan.wfa.ie.wpa.")
        known = (oui == "0050f2" and typ in ((1, 2) if kind == "akm" else (0, 1, 2, 4, 5))) if wpa else oui == "000fac"
        result.append({"oui": ":".join(oui[i:i+2] for i in (0, 2, 4)), "type": typ,
                       "name": (AKMS if kind == "akm" else CIPHERS).get(typ, "unknown") if known else "unknown", "raw_hex": raw})
    return result


def security_ies(packet):
    result = []
    malformed = any(x.get("name") == "_ws.malformed" for x in packet.iter())
    for index, tag in enumerate(fields(packet, "wlan.tag")):
        rsn = bool(fields(tag, "wlan.rsn.version")) or number(tag, "wlan.tag.number") == 48
        wpa = bool(fields(tag, "wlan.wfa.ie.wpa.version"))
        if not rsn and not wpa:
            continue
        prefix = "wlan.rsn" if rsn else "wlan.wfa.ie.wpa"
        akms = selectors(tag, prefix + ".akms", "akm")
        pair = selectors(tag, prefix + (".pcs" if rsn else ".ucs"), "cipher")
        caps = number(tag, prefix + ".capabilities") if rsn else None
        expected_akm = number(tag, prefix + ".akms.count")
        expected_pair = number(tag, prefix + (".pcs.count" if rsn else ".ucs.count"))
        group = selectors(tag, prefix + (".gcs" if rsn else ".mcs"), "cipher")
        invalid_caps = caps is not None and (not 0 <= caps <= 65535 or (caps & 64 and not caps & 128))
        complete = (not malformed and not invalid_caps and number(tag, prefix + ".version") == 1
                    and expected_akm is not None and expected_akm > 0 and expected_akm == len(akms)
                    and expected_pair is not None and expected_pair > 0 and expected_pair == len(pair) and len(group) == 1)
        result.append({"ie_index": index, "protocol": "RSN" if rsn else "WPA", "version": number(tag, prefix + ".version"),
                       "akms": akms, "pairwise_ciphers": pair,
                       "group_ciphers": group,
                       "group_management_ciphers": selectors(tag, prefix + ".gmcs", "cipher"),
                       "capabilities": caps, "mfpc": bool(caps & 128) if caps is not None else None,
                       "mfpr": bool(caps & 64) if caps is not None else None,
                       "pmf": "unknown" if caps is None else "required" if caps & 64 else "capable" if caps & 128 else "disabled",
                       "parse_status": "malformed" if malformed or invalid_caps else "decoded" if complete else "incomplete", "declared_akm_count": expected_akm,
                       "declared_pairwise_count": expected_pair})
    return result


def unique(items):
    seen, result = set(), []
    for item in items:
        key = json.dumps(item, sort_keys=True)
        if key not in seen:
            seen.add(key)
            result.append(item)
    return result


def mac(element, name):
    item = (value(element, name) or "").lower()
    return item if MAC.fullmatch(item) and not (int(item[:2], 16) & 1) and item != "00:00:00:00:00:00" else None


def frame_integrity(packet):
    """Missing metadata is unverified, never an inferred checksum success."""
    bad = number(packet, "radiotap.flags.badfcs")
    status = number(packet, "wlan.fcs.status")  # Wireshark: 0 bad, 1 good, 2 unverified.
    if bad == 1 or status == 0:
        return "failed"
    if status == 1:
        return "verified"
    return "not_flagged" if bad == 0 else "unknown"


def bss_load(packet, provenance):
    """One complete, directly owned BSS Load IE; never a nested AP's total."""
    tags = [tag for tag in fields(packet, "wlan.tag") if number(tag, "wlan.tag.number") == 11]
    if len(tags) != 1 or number(tags[0], "wlan.tag.length") != 5:
        return None
    if any(x.get("name") == "_ws.malformed" for x in packet.iter()):
        return None
    counts = fields(tags[0], "wlan.qbss.scount")
    count = number(tags[0], "wlan.qbss.scount")
    if len(counts) != 1 or count is None or not 0 <= count <= 65535:
        return None
    return {"station_count": count, **provenance}


def record_client(clients, address, bssid, stamp, signal, transmitter, relation):
    client = clients.setdefault(address, {"id": "sta:" + address, "mac": address, "ap_id": None,
        "first_seen": stamp, "last_seen": stamp, "signal_dbm": None, "relations": []})
    client["first_seen"] = min(client["first_seen"], relation.get("first_seen", stamp))
    # Capture order need not equal timestamp order; controls are resolved after
    # AP discovery. Older evidence must not move a newer client relationship.
    if stamp >= client["last_seen"]:
        client["last_seen"] = stamp
        client["ap_id"] = "ap:" + bssid
    if transmitter == address and stamp >= client.get("_signal_seen", -1):
        client["signal_dbm"], client["_signal_seen"] = signal, stamp
    client["relations"].append(relation)


def security_summary(packet, ies):
    privacy = number(packet, "wlan.fixed.capabilities.privacy")
    malformed = any(x.get("name") == "_ws.malformed" for x in packet.iter())
    length, captured = number(packet, "frame.len"), number(packet, "frame.cap_len")
    complete_frame = length is not None and length > 0 and captured == length and not malformed
    qualified = [ie for ie in ies if ie["parse_status"] == "decoded"]
    quality = "malformed" if malformed else "incomplete" if not complete_frame or any(ie["parse_status"] != "decoded" for ie in ies) or privacy not in (0, 1) else "decoded"
    auth = sorted({item["name"] for ie in qualified for item in ie["akms"]})
    protocols = sorted({ie["protocol"] for ie in qualified})
    labels = []
    if "WPA" in protocols:
        labels.append("WPA")
    if "RSN" in protocols:
        rsn_auth = {item["name"] for ie in qualified if ie["protocol"] == "RSN" for item in ie["akms"]}
        sae = bool(rsn_auth & {"SAE", "FT-SAE"})
        psk = bool(rsn_auth & {"PSK", "FT-PSK", "PSK-SHA256"})
        labels.append("WPA2/WPA3-Personal" if sae and psk else "WPA3-Personal" if sae else "WPA2-Personal" if psk else "OWE" if rsn_auth == {"OWE"} else "RSN-Enterprise" if rsn_auth and all("802.1X" in a for a in rsn_auth) else "RSN (unknown AKM)")
    if privacy == 0 and ies:
        quality = "conflicting"
    label = " + ".join(labels) or ("Open" if quality == "decoded" and privacy == 0 else "Privacy set (protocol unknown)" if privacy == 1 and quality == "decoded" else "unknown")
    return {"label": label, "protocols": protocols, "authentication": auth,
            "pairwise_ciphers": sorted({c["name"] for ie in qualified for c in ie["pairwise_ciphers"]}),
            "group_ciphers": sorted({c["name"] for ie in qualified for c in ie["group_ciphers"]}),
            "pmf": sorted({ie["pmf"] for ie in qualified if ie["protocol"] == "RSN"}),
            "privacy": privacy, "quality": quality}


def analyze_pdml(path, artifact_sha256="unknown", max_packets=MAX_PACKETS, max_fields=MAX_FIELDS, *, deadline=None, cancel_file=None):
    deadline = time.monotonic() + MAX_SECONDS if deadline is None else deadline
    check_budget(deadline, cancel_file)
    path = Path(path)
    if path.stat().st_size > MAX_XML_BYTES:
        raise ValueError("PDML byte budget exceeded")
    # No DTD/entity processing, even if supplied outside the trusted decoder.
    with path.open("rb") as stream:
        tail = b""
        while block := stream.read(65536):
            check_budget(deadline, cancel_file)
            chunk = (tail + block).upper()
            if b"<!DOCTYPE" in chunk or b"<!ENTITY" in chunk:
                raise ValueError("PDML declarations are prohibited")
            tail = block[-16:]
    result = {"schema_version": 1, "source": {"artifact_sha256": artifact_sha256, "decoder": "tshark-pdml"},
              "networks": [], "clients": [], "handshakes": [], "observations": [],
              "limits": ["Passive capture only; missing evidence is unknown", "EAPOL message coverage does not verify MICs or a password", "Negotiated PMF is not inferred from beacon capabilities", "Incomplete/malformed security IEs retain partial evidence but do not qualify security summaries", "One cooperative wall-time deadline covers hash, decode and parse; CLI serialization shares that deadline"]}
    networks, clients, exchanges = {}, {}, []
    controls, ap_frequencies = {}, {}
    identity_hints = {}
    integrity_counts = {key: 0 for key in ("failed", "verified", "not_flagged", "unknown")}
    count = 0
    pdml_stream = path.open("rb")
    try:
        parser = ET.iterparse(pdml_stream, events=("start", "end"))
        _, root = next(parser)
        events = 0
        for event, packet in parser:
            events += 1
            if events % 256 == 0:
                check_budget(deadline, cancel_file)
            if event != "end" or packet.tag != "packet":
                continue
            count += 1
            check_budget(deadline, cancel_file)
            if count > max_packets or sum(1 for _ in packet.iter()) > max_fields:
                raise ValueError("PDML packet/field/time budget exceeded")
            frame, stamp = number(packet, "frame.number") or count, real(packet, "frame.time_epoch")
            if stamp is None:
                raise ValueError("Packet has no timestamp")
            provenance = {"frame": frame, "time": stamp}
            integrity = frame_integrity(packet)
            integrity_counts[integrity] += 1
            if integrity == "failed":
                result["observations"].append({"kind": "excluded_bad_fcs", **provenance})
                root.clear()
                continue
            malformed = any(x.get("name") == "_ws.malformed" for x in packet.iter())
            if malformed:
                result["observations"].append({"kind": "malformed_frame", **provenance,
                                               "interpretation": "Decoder marked frame malformed; decoded fields may be partial"})
            profile_ies = nested_security(packet)
            packet = owned_view(packet)
            bssid = mac(packet, "wlan.bssid")
            subtype = number(packet, "wlan.fc.type_subtype")
            ies = security_ies(packet)
            signal = number(packet, "radiotap.dbm_antsignal")
            if bssid and subtype in (8, 5):
                ssid = value(packet, "wlan.ssid", "", raw=True).lower()
                try:
                    raw_ssid = bytes.fromhex(ssid)
                except ValueError:
                    raw_ssid, ssid = b"", ""
                net = networks.setdefault(bssid, {"id": "ap:" + bssid, "bssid": bssid, "ssid_hex": ssid,
                    "ssid_present": bool(fields(packet, "wlan.ssid")),
                    "ssid_display": raw_ssid.decode("utf-8", errors="replace"), "frequency_mhz": None, "channel": None,
                    "signal_dbm": None, "first_seen": stamp, "last_seen": stamp, "security": "unknown", "advertised_akms": [],
                    "pmf_advertised": "unknown", "wps": None, "mlo": None, "negotiated_security": "unknown",
                    "negotiated_pmf": "unknown", "advertisements": []})
                net["last_seen"] = max(net["last_seen"], stamp)
                net["first_seen"] = min(net["first_seen"], stamp)
                net["signal_dbm"] = signal
                net["frequency_mhz"] = number(packet, "radiotap.channel.freq") or number(packet, "wlan_radio.frequency")
                net["channel"] = number(packet, "wlan.ds.current_channel") or number(packet, "wlan_radio.channel")
                if mac(packet, "wlan.ta") == bssid:
                    ap_frequencies.setdefault(bssid, set()).add(net["frequency_mhz"])
                load = bss_load(packet, provenance)
                if load is not None and stamp >= net.get("bss_load", {}).get("time", -1):
                    net["bss_load"] = load
                if ssid:
                    net["ssid_hex"], net["ssid_display"] = ssid, raw_ssid.decode("utf-8", errors="replace")
                    net["ssid_present"] = True
                advert = {**provenance, "ssid_hex": ssid, "ssid_present": bool(fields(packet, "wlan.ssid")),
                          "security_ies": ies, "nested_profile_security_ies": profile_ies,
                          "privacy": number(packet, "wlan.fixed.capabilities.privacy"),
                          "frame_integrity": integrity, "security_summary": security_summary(packet, ies)}
                net["advertisements"].append(advert)
                qualified = [ie for ie in ies if ie["parse_status"] == "decoded"]
                net["advertised_akms"] = unique(net["advertised_akms"] + [a for ie in qualified for a in ie["akms"]])
                variants = unique(net.get("security_variants", []) + [advert["security_summary"]])
                net["security_variants"] = variants
                decoded = [v for v in variants if v["quality"] == "decoded"]
                conflict = len(decoded) > 1 or any(v["quality"] == "conflicting" for v in variants)
                net["security_conflict"] = conflict
                net["security_quality"] = "conflicting" if conflict else "partial" if any(v["quality"] != "decoded" for v in variants) else "decoded"
                labels = sorted({v["label"] for v in variants if v["label"] != "unknown"})
                net["security"] = ("Conflicting: " if conflict else "") + (" | ".join(labels) or "unknown")
                pmfs = set(net.get("pmf_observed_states", [])) | {ie["pmf"] for ie in qualified if ie["protocol"] == "RSN"}
                net["pmf_observed_states"] = sorted(pmfs)
                net["pmf_advertised"] = next(iter(pmfs)) if len(pmfs) == 1 else "unknown"
                if len(pmfs) > 1:
                    net["pmf_advertisement_conflict"] = True
                wps_fields = ("wps.version", "wps.config_methods", "wps.selected_registrar", "wps.wifi_protected_setup_state", "wps.ap_setup_locked")
                wps_groups = []
                for tag in fields(packet, "wlan.tag"):
                    if any(x.get("name", "").startswith("wps.") for x in tag.iter("field")):
                        wps_groups.append({name: [x.get("show") for x in fields(tag, name)] for name in wps_fields if fields(tag, name)})
                if wps_groups:
                    net["wps"] = {"present": True, "ie_groups": wps_groups, **provenance}
                mlo = [x for x in packet.iter("field") if "multi_link" in x.get("name", "") or ".mld" in x.get("name", "")]
                if mlo or any(number(tag, "wlan.ext_tag.number") == 107 for tag in fields(packet, "wlan.tag")):
                    # MLD identity is deliberately not conflated with a link BSSID.
                    mld = mac(packet, "wlan.eht.multi_link.common_info.mld_mac_address")
                    link = number(packet, "wlan.eht.multi_link.common_info.link_id_subfield.link_id")
                    net["mlo"] = {"present": True, "status": "decoded_common_info" if mld and link is not None else "unknown",
                                  "mld_mac": mld, "link_id": link, "link_bssid": bssid,
                                  "relationship_scope": "advertised_common_info_only", **provenance}
            if bssid:
                for address in station_addresses(packet, bssid, subtype):
                    # Each relation is scoped to this frame, not an everlasting association.
                    kind = "association_request" if subtype in (0, 2) else "association_response" if subtype in (1, 3) else "observed_addressing"
                    relation = {"ap_id": "ap:" + bssid, "kind": kind, **provenance, "negotiated_pmf": "unknown"}
                    relation["logical_source"] = mac(packet, "wlan.sa")
                    relation["logical_destination"] = mac(packet, "wlan.da")
                    if subtype in (0, 1, 2, 3):
                        relation["security_ies"] = ies
                        relation["status_code"] = number(packet, "wlan.fixed.status_code")
                        relation["interpretation"] = "Frame selections/capabilities only; negotiation not established"
                    record_client(clients, address, bssid, stamp, signal, mac(packet, "wlan.ta"), relation)
                _eapol(packet, bssid, stamp, frame, exchanges)
            # RTS, Block ACK/request and PS-Poll carry both radio endpoints.
            # ACK/CTS have only one address and cannot identify an AP/client pair.
            if subtype in (24, 25, 26, 27) and not malformed:
                ta, ra = mac(packet, "wlan.ta"), mac(packet, "wlan.ra")
                if ta and ra and ta != ra:
                    frequency = number(packet, "radiotap.channel.freq") or number(packet, "wlan_radio.frequency")
                    key = (ta, ra, subtype, frequency)
                    group = controls.setdefault(key, {"first_seen": stamp, "last_seen": stamp,
                                                      "frame": frame, "signal": signal, "frame_count": 0})
                    group["first_seen"] = min(group["first_seen"], stamp)
                    group["frame_count"] += 1
                    if stamp >= group["last_seen"]:
                        group.update(last_seen=stamp, frame=frame, signal=signal)
            if not malformed:
                transmitter = mac(packet, "wlan.ta")
                if transmitter and (transmitter in identity_hints or len(identity_hints) < 4096):
                    clues = identity.extract(packet, subtype, transmitter, mac(packet, "wlan.sa"), provenance)
                    if clues: identity_hints[transmitter] = identity.merge(identity_hints.get(transmitter, []), clues)
            root.clear()
    except ET.ParseError as error:
        raise ValueError("Malformed/truncated PDML") from error
    finally:
        pdml_stream.close()
    # A control frame may precede its AP's beacon in the capture. Resolve only
    # pairs with exactly one directly advertised AP; AP-to-AP links stay unknown.
    for index, ((ta, ra, subtype, frequency), group) in enumerate(controls.items()):
        if index % 256 == 0:
            check_budget(deadline, cancel_file)
        if (ta in ap_frequencies) == (ra in ap_frequencies):
            continue
        ap, client = (ta, ra) if ta in ap_frequencies else (ra, ta)
        known_frequencies = ap_frequencies[ap] - {None}
        if frequency is not None and known_frequencies and frequency not in known_frequencies:
            continue
        stamp = group["last_seen"]
        relation = {"ap_id": "ap:" + ap, "kind": "observed_control_peer", "frame": group["frame"],
                    "time": stamp, "first_seen": group["first_seen"], "frame_count": group["frame_count"],
                    "frame_subtype": subtype, "negotiated_pmf": "unknown"}
        record_client(clients, client, ap, stamp, group["signal"], ta, relation)
    for client in clients.values():
        client.pop("_signal_seen", None)
        client["relations"].sort(key=lambda relation: (relation["time"], relation["frame"]))
        if client["mac"] in identity_hints: client["identity_hints"] = identity_hints[client["mac"]]
    for address, network in networks.items():
        if address in identity_hints: network["identity_hints"] = identity_hints[address]
    result["source"]["frame_count"] = count
    result["source"]["frame_integrity"] = integrity_counts
    result["networks"], result["clients"] = list(networks.values()), list(clients.values())
    result["handshakes"] = [{k: v for k, v in x.items() if not k.startswith("_")} for x in exchanges]
    check_budget(deadline, cancel_file)
    return result


def _eapol(packet, ap, stamp, frame, exchanges):
    msg = number(packet, "wlan_rsna_eapol.keydes.msgnr")
    if msg not in (1, 2, 3, 4) or number(packet, "wlan_rsna_eapol.keydes.key_info.key_type") != 1:
        return
    sa, da = mac(packet, "wlan.sa"), mac(packet, "wlan.da")
    client = da if sa == ap else sa if da == ap else None
    if not client or (msg in (1, 3)) != (sa == ap):
        return
    replay = number(packet, "eapol.keydes.replay_counter")
    nonce = value(packet, "wlan_rsna_eapol.keydes.nonce", "", raw=True)
    nonce = nonce.replace(":", "").lower()
    if len(nonce) != 64 or not re.fullmatch("[0-9a-f]{64}", nonce):
        nonce = ""
    candidates = [x for x in exchanges if x["ap_id"] == "ap:" + ap and x["client_id"] == "sta:" + client and 0 <= stamp - x["last_seen"] <= 10]
    matching = []
    for x in candidates:
        if replay is None or x["_replay"] is None:
            continue
        if msg == 1 and x["_replay"] == replay and x["_anonce"] == nonce and 1 in x["messages"]:
            matching.append(x)
        elif msg == 2 and x["_replay"] == replay and 1 in x["messages"] and 3 not in x["messages"] and nonce and set(nonce) != {"0"} and (not x["_snonce"] or x["_snonce"] == nonce):
            matching.append(x)
        elif msg == 3 and replay == x["_replay"] + 1 and 2 in x["messages"] and nonce and nonce == x["_anonce"]:
            matching.append(x)
        elif msg == 4 and replay == x["_replay"] + 1 and 3 in x["messages"] and (not nonce or set(nonce) == {"0"} or nonce == x["_snonce"]):
            matching.append(x)
    # Ambiguity is retained as separate partial evidence, never arbitrarily merged.
    if len(matching) == 1:
        exchange = matching[0]
    else:
        exchange = {"id": "eapol:" + str(frame), "ap_id": "ap:" + ap, "client_id": "sta:" + client,
                    "first_seen": stamp, "last_seen": stamp, "messages": [], "frames": [],
                    "coverage": "partial", "password_verified": False, "mic_verified": False,
                    "_replay": replay, "_anonce": nonce if msg == 1 else "", "_snonce": ""}
        exchanges.append(exchange)
    if msg == 2:
        exchange["_snonce"] = nonce
    exchange["last_seen"] = stamp
    exchange["messages"] = sorted(set(exchange["messages"] + [msg]))
    exchange["frames"].append({"frame": frame, "time": stamp, "message": msg, "replay_counter": replay})
    if exchange["messages"] == [1, 2, 3, 4] and exchange["_anonce"] and set(exchange["_anonce"]) != {"0"}:
        exchange["coverage"] = "four_messages_observed"


def analyze_pcap(path, tshark="tshark", *, deadline=None, cancel_file=None):
    deadline = time.monotonic() + MAX_SECONDS if deadline is None else deadline
    check_budget(deadline, cancel_file)
    path = Path(path).resolve(strict=True)
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            check_budget(deadline, cancel_file)
            digest.update(block)
    with tempfile.TemporaryDirectory(prefix="bender-observation-") as directory:
        xml = Path(directory) / "dissection.xml"
        errors = Path(directory) / "decoder.stderr"
        with xml.open("wb") as output, errors.open("wb") as stderr:
            check_budget(deadline, cancel_file)
            process = subprocess.Popen([str(tshark), "-n", "-r", str(path), "-c", str(MAX_PACKETS + 1), "-T", "pdml"], stdout=output, stderr=stderr)
            try:
                while process.poll() is None:
                    check_budget(deadline, cancel_file)
                    if xml.stat().st_size > MAX_XML_BYTES:
                        raise RuntimeError("Decoder output exceeded the 512 MiB temporary-file limit; raw capture retained")
                    if errors.stat().st_size > 1024 * 1024:
                        raise RuntimeError("Decoder diagnostic output exceeded the 1 MiB limit; raw capture retained")
                    time.sleep(0.02)
                if process.returncode:
                    raise RuntimeError("TShark rejected capture (exit " + str(process.returncode) + ")")
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()
        # A fast decoder can finish between polls. Check closed outputs too;
        # neither size limit depends on observing the process while running.
        if xml.stat().st_size > MAX_XML_BYTES:
            raise RuntimeError("Decoder output exceeded the 512 MiB temporary-file limit; raw capture retained")
        if errors.stat().st_size > 1024 * 1024:
            raise RuntimeError("Decoder diagnostic output exceeded the 1 MiB limit; raw capture retained")
        return analyze_pdml(xml, digest.hexdigest(), deadline=deadline, cancel_file=cancel_file)
