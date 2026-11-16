"""Bounded structured WPS event analyzer. Never emits raw transcript text.

Caller supplies decoded event dictionaries; this is not an active WPS engine.
Credentials go only to the explicitly supplied in-memory sink. No file interface.
"""
from collections import Counter

EVENTS = {"M1", "M2", "M2D", "M3", "M4", "M5", "M6", "M7", "M8", "ACK", "NACK", "DONE", "TIMEOUT", "CANCELLED", "ERROR", "CREDENTIAL"}
class Credential:
    __slots__ = ("ssid", "key", "authentication")
    def __init__(self, ssid, key, authentication):
        self.ssid, self.key, self.authentication = ssid, key, authentication
    def __repr__(self):
        return "Credential(<redacted>)"

def analyze(events, credential_sink=None, max_events=4096):
    if not 1 <= max_events <= 4096:
        raise ValueError("Invalid event budget")
    counts, progression = Counter(), []
    state, expected, credential_count, warnings = "unknown", 1, 0, []
    for index, event in enumerate(events):
        if index >= max_events:
            warnings.append("event_budget_exceeded")
            break
        # Strict output allowlist: no arbitrary names, error text or nested fields.
        name = event.get("type") if isinstance(event, dict) else None
        name = name if isinstance(name, str) and name in EVENTS else "UNKNOWN"
        counts[name] += 1
        if state in {"complete", "rejected", "timeout", "cancelled", "error"}:
            warnings.append("event_after_terminal")
            continue
        if name.startswith("M") and name[1:].isdigit():
            step = int(name[1:])
            if step != expected:
                warnings.append("out_of_order_message")
            expected = max(expected, step + 1)
            state = "in_progress"
        elif name == "CREDENTIAL":
            credential_count += 1
            if credential_sink is not None:
                credential_sink(Credential(event.get("ssid"), event.get("network_key"), event.get("authentication")))
        elif name in {"DONE", "NACK", "TIMEOUT", "CANCELLED", "ERROR"}:
            state = {"DONE":"complete", "NACK":"rejected", "TIMEOUT":"timeout", "CANCELLED":"cancelled", "ERROR":"error"}[name]
        elif name == "M2D":
            state = "deferred"
        progression.append({"index": index, "event": name})
    return {"schema_version": 1, "state": state, "events": progression,
            "counts": dict(counts), "credential_count": credential_count,
            "warnings": sorted(set(warnings)),
            "limits": ["Decoded structured transcript only; DONE does not prove association or usable credentials."]}
