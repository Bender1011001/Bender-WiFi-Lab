"""Offline PMF evidence inspector. No radio commands, keys or bypass verdicts.

Input is TShark PDML. Reuses the reviewed engine's bounds and BSS/IE ownership.
Capture syntax, association hints and matching SA Query IDs are observations;
none establish cryptographic validity, installed keys or receiver enforcement.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "engine"))
from bender_engine.analysis import (MAX_XML_BYTES, MAC, analyze_pdml, check_budget,
                                   fields, number, owned_view, real, security_ies, value)


def address(packet, field):
    result = (value(packet, field) or "").lower()
    return result if MAC.fullmatch(result) and result != "00:00:00:00:00:00" else None


def group(address_value):
    return bool(address_value and int(address_value[:2], 16) & 1)


def rsn_hint(packet):
    rsns = [ie for ie in security_ies(packet) if ie["protocol"] == "RSN"]
    # Duplicate/conflicting RSNs are not arbitrarily resolved into one negotiation.
    if len(rsns) != 1 or rsns[0]["parse_status"] != "decoded":
        return {"mfpc": None, "mfpr": None, "akms": [], "status": "unknown"}
    ie = rsns[0]
    return {"mfpc": ie["mfpc"], "mfpr": ie["mfpr"], "akms": ie["akms"], "status": "decoded"}


def management_format(packet, subtype, destination, protected, malformed):
    category = number(packet, "wlan.fixed.category_code") if subtype == 13 else None
    robust = subtype in (10, 12) or (subtype == 13 and category == 8)
    mmie = bool(fields(packet, "wlan.mmie.keyid") or fields(packet, "wlan.mmie.ipn") or fields(packet, "wlan.mmie.mic"))
    mic = value(packet, "wlan.mmie.mic", "", raw=True).replace(":", "")
    ipn = number(packet, "wlan.mmie.ipn")
    complete_mmie = (all(len(fields(packet, name)) == 1 for name in ("wlan.mmie.keyid", "wlan.mmie.ipn", "wlan.mmie.mic"))
                     and number(packet, "wlan.mmie.keyid") in (4, 5)
                     and ipn is not None and 0 <= ipn < 2**48
                     and len(mic) in (16, 32)
                     and all(c in "0123456789abcdefABCDEF" for c in mic))
    if malformed or destination is None or protected not in (0, 1):
        kind = "unknown_or_malformed"
    elif subtype == 13 and category == 4:
        kind = "public_action_outside_basic_pmf"
    elif group(destination):
        kind = ("unexpected_group_protected_bit" if protected else
                "group_mmie_structure_unverified" if robust and complete_mmie else
                "group_mmie_incomplete" if mmie else "group_no_mmie_observed")
    elif protected:
        kind = "protected_unicast_format_unverified"
    elif robust:
        kind = "unprotected_robust_format_observed"
    else:
        kind = "action_category_unclassified"
    return {"format": kind, "mmie_present": mmie,
            "mmie_key_id": number(packet, "wlan.mmie.keyid") if mmie else None,
            "integrity_verified": None, "receiver_accepted": None}


def inspect_pdml(path, capture_layer="unspecified", *, deadline=None):
    if capture_layer not in ("unspecified", "raw_monitor", "managed_postprocessed"):
        raise ValueError("Unknown capture layer")
    deadline = time.monotonic() + 120 if deadline is None else deadline
    path = Path(path)
    if path.stat().st_size > MAX_XML_BYTES:
        raise ValueError("PDML byte budget exceeded")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(65536):
            check_budget(deadline)
            digest.update(block)
    base = analyze_pdml(path, digest.hexdigest(), deadline=deadline)
    management, attempts, queries = [], [], []
    requests, query_requests = defaultdict(list), defaultdict(list)
    with path.open("rb") as stream:
        parser = ET.iterparse(stream, events=("start", "end"))
        _, root = next(parser)
        for event, original in parser:
            if event != "end" or original.tag != "packet":
                continue
            check_budget(deadline)
            malformed = any(x.get("name") == "_ws.malformed" for x in original.iter())
            p = owned_view(original)
            frame, stamp = number(p, "frame.number"), real(p, "frame.time_epoch")
            subtype, protected = number(p, "wlan.fc.type_subtype"), number(p, "wlan.fc.protected")
            bssid, ta, ra = (address(p, key) for key in ("wlan.bssid", "wlan.ta", "wlan.ra"))
            if group(bssid):
                bssid = None
            provenance = {"frame": frame, "time": stamp, "bssid": bssid, "transmitter": ta, "receiver": ra}
            if subtype in (0, 2) and not malformed and bssid and ra == bssid and ta and not group(ta) and ta != bssid:
                key = (bssid, ta, subtype)
                pending = [r for r in requests[key] if 0 <= stamp - r["time"] <= 5]
                requests[key] = (pending + [{**provenance, "rsn": rsn_hint(p)}])[-32:]
            elif subtype in (1, 3) and bssid and ta == bssid and ra and not group(ra) and ra != bssid:
                key = (bssid, ra, subtype - 1)
                pending = [r for r in requests[key] if 0 <= stamp - r["time"] <= 5]
                response_rsn, request = rsn_hint(p), pending[0] if len(pending) == 1 else None
                status = number(p, "wlan.fixed.status_code")
                hint = "unknown"
                if not malformed and status == 0 and request:
                    if request["rsn"]["mfpc"] is True and response_rsn["mfpc"] is True:
                        hint = "both_parties_advertised_mfpc"
                    elif request["rsn"]["mfpc"] is False:
                        hint = "station_requested_without_mfpc"
                attempts.append({**provenance, "status_code": status,
                                 "request_frame": request["frame"] if request else None,
                                 "pairing": "single_request_in_window" if request else "ambiguous_or_missing_request",
                                 "pmf_hint": hint, "negotiated_pmf": "unknown", "keys_installed": "unknown"})
                requests.pop(key, None)
            elif subtype in (10, 12, 13):
                category = number(p, "wlan.fixed.category_code") if subtype == 13 else None
                action = number(p, "wlan.fixed.action_code") if subtype == 13 else None
                row = {**provenance, "subtype": subtype, "protected_bit": protected,
                       "category": category, "action": action,
                       "reason_code": number(p, "wlan.fixed.reason_code"),
                       **management_format(p, subtype, ra, protected, malformed)}
                management.append(row)
                tid = number(p, "wlan.fixed.transaction_id")
                if not malformed and category == 8 and tid is not None and action in (0, 1) and bssid and ta and ra and not group(ta) and not group(ra):
                    if action == 0:
                        key = (bssid, ta, ra, tid)
                        pending = [r for r in query_requests[key] if 0 <= stamp-r["time"] <= 2]
                        query_requests[key] = (pending + [row])[-32:]
                    else:
                        key = (bssid, ra, ta, tid)
                        pending = [r for r in query_requests[key] if 0 <= stamp-r["time"] <= 2]
                        queries.append({"response_frame": frame,
                                        "request_frame": pending[0]["frame"] if len(pending) == 1 else None,
                                        "pairing": "matching_ids_and_direction" if len(pending) == 1 else "ambiguous_or_missing_request",
                                        "authenticated_exchange": None,
                                        "note": "Two-second observation window, not a configured SA Query timeout"})
                        query_requests.pop(key, None)
            root.clear()
    check_budget(deadline)
    return {"schema_version": 1, "pdml_sha256": digest.hexdigest(),
            "capture_layer_reported_by_caller": capture_layer,
            "frame_count": base["source"]["frame_count"],
            "networks": [{"id": n["id"], "pmf_advertised": n["pmf_advertised"],
                          "advertised_akms": n["advertised_akms"], "negotiated_pmf": "unknown"}
                         for n in base["networks"]],
            "association_attempts": attempts, "unmatched_association_requests": sum(map(len, requests.values())),
            "management_frames": management, "management_format_counts": dict(Counter(r["format"] for r in management)),
            "sa_query_pairs": queries, "eapol_exchange_count": len(base["handshakes"]),
            "enforcement": "not_tested", "pmf_bypass": "not_established",
            "limits": ["Protected bit/MMIE structure does not verify cryptography or receiver acceptance",
                       "Optional AP policy does not determine an individual client's negotiated PMF",
                       "Association pairing is a bounded observation hint, not authenticated negotiation",
                       "Encrypted action bodies may conceal SA Query category and transaction ID",
                       "Managed receive paths may strip crypto metadata; capture layer is not independently verified",
                       "No keys, payload bytes, MMIE MICs, SSIDs or credential attributes are exported",
                       "Existing capture cannot prove loss, rejection, disconnection causality or a PMF bypass"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdml", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--capture-layer", choices=("unspecified", "raw_monitor", "managed_postprocessed"), default="unspecified")
    args = parser.parse_args()
    if args.pdml.resolve() == args.output.resolve():
        parser.error("Input and output must differ")
    deadline = time.monotonic()+120
    result = inspect_pdml(args.pdml, args.capture_layer, deadline=deadline)
    encoded = json.dumps(result, indent=2)
    check_budget(deadline)
    if len(encoded) > 10_000_000:
        raise ValueError("Report exceeds 10 MB; split the capture")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=args.output.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(encoded+"\n")
        check_budget(deadline)
        os.replace(temporary, args.output)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    print(json.dumps({"frames": result["frame_count"], "enforcement": result["enforcement"], "pmf_bypass": result["pmf_bypass"]}))


if __name__ == "__main__":
    main()
