"""Bounded local Android privacy preflight. Never contacts an external service."""
from __future__ import annotations

import ipaddress
import json
import os
from pathlib import Path
import re
import selectors
import shlex
import signal
import subprocess
import threading
import time
from contextlib import contextmanager

TIMEOUT_SECONDS = 3.0
MAX_OUTPUT_BYTES = 1024 * 1024
_KILL_SIGNAL = getattr(signal, "SIGKILL", 9)  # NetHunter is Linux; supports offline host tests.
_SECTIONS = ("sdk", "fingerprint", "airplane_mode_on", "mobile_data", "bluetooth_on",
             "wlan_mac", "links", "addresses", "routes_v4", "routes_v6", "firewall_v4", "firewall_v6")
_COMMANDS = (
    # chroot alone does not change network namespaces. Require the Android
    # init namespace before interpreting inherited netlink observations.
    'host_ns=$(/system/bin/readlink /proc/1/ns/net)\n'
    'current_ns=$(/system/bin/readlink /proc/self/ns/net)\n'
    'test -n "$host_ns"\n'
    'test "$host_ns" = "$current_ns"\n'
    '/system/bin/getprop ro.build.version.sdk',
    "/system/bin/getprop ro.build.fingerprint",
    "/system/bin/settings get global airplane_mode_on",
    "/system/bin/settings get global mobile_data",
    "/system/bin/settings get global bluetooth_on",
    "/system/bin/cat /sys/class/net/wlan0/address",
    "/system/bin/ip -o link show",
    "/system/bin/ip -o addr show",
    "/system/bin/ip -o -4 route show table all",
    "/system/bin/ip -o -6 route show table all",
    "/system/bin/iptables -w 1 -t filter -S 2>/dev/null || printf 'UNAVAILABLE\\n'",
    "/system/bin/ip6tables -w 1 -t filter -S 2>/dev/null || printf 'UNAVAILABLE\\n'",
)
# Every command and path is fixed; no job, network or user text enters this script.
_SCRIPT = "set -e\nexport PATH=/system/bin:/system/xbin\n" + "\n".join(
    "printf 'BEGIN_" + name + "\\n'\n" + command +
    "\nprintf '\\nEND_" + name + "\\n'"
    for name, command in zip(_SECTIONS, _COMMANDS)
)
_SAFE4 = tuple(ipaddress.ip_network(x) for x in
               ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8"))
_SAFE6 = tuple(ipaddress.ip_network(x) for x in ("fc00::/7", "fe80::/10", "::1/128"))
_CELLULAR = re.compile(r"^(?:rmnet|r_rmnet|rev_rmnet|v4-rmnet|ccmni|pdp|wwan|cellular|mhi|sipa)", re.I)
_CHECK_IDS = ("android_context", "airplane_mode", "mobile_data", "bluetooth",
              "wlan_mac", "assigned_addresses", "ipv4_routes", "ipv6_routes", "cellular_links")


def _safe_network(network):
    return any(network.subnet_of(safe) for safe in (_SAFE4 if network.version == 4 else _SAFE6))


def _lines(snapshot, key):
    value = snapshot.get(key)
    if not isinstance(value, list) or any(not isinstance(x, str) or not x.strip() for x in value):
        raise ValueError("missing or malformed")
    return value


def _link(line):
    match = re.match(r"^\d+: ([^\s:]+): <([A-Z0-9_,]*)>(?:\s|$)", line)
    if not match:
        raise ValueError("malformed link")
    return match[1].split("@")[0], set(match[2].split(","))


def _addresses(lines, locked=False):
    safe = True
    for line in lines:
        match = re.match(r"^\d+: \S+\s+(inet6?)\s+(\S+)\s+", line)
        if not match:
            raise ValueError("malformed address")
        address = ipaddress.ip_interface(match[2]).ip
        if address.version != (6 if match[1] == "inet6" else 4):
            raise ValueError("wrong address family")
        if not _safe_network(ipaddress.ip_network(str(address))):
            safe = False
    return safe or locked


def _routes(lines, version, locked=False):
    safe = True
    for line in lines:
        tokens = line.split()
        if not tokens:
            raise ValueError("empty route")
        route_type = tokens.pop(0) if tokens[0] in {
            "unicast", "local", "broadcast", "multicast", "blackhole", "unreachable", "prohibit", "throw"
        } else "unicast"
        if not tokens:
            raise ValueError("missing destination")
        destination = tokens.pop(0)
        if destination == "default":
            destination = "0.0.0.0/0" if version == 4 else "::/0"
        network = ipaddress.ip_network(destination, strict=False)
        if network.version != version:
            raise ValueError("wrong route family")
        # Accept only understood iproute metadata. Unknown syntax is not proof
        # that all egress paths were inspected (including policy/VPN tables).
        options = list(tokens)
        while options:
            key = options.pop(0)
            if key in {"onlink", "linkdown", "dead", "pervasive", "cache"}:
                continue
            if key not in {"dev", "proto", "scope", "src", "via", "metric", "table", "pref", "expires", "uid", "mtu", "advmss", "hoplimit"} or not options:
                raise ValueError("unsupported route metadata")
            value = options.pop(0)
            if key in {"src", "via"}:
                if key == "via" and value in {"inet", "inet6"}:
                    if not options:
                        raise ValueError("missing gateway")
                    value = options.pop(0)
                address = ipaddress.ip_address(value.split("%")[0])
                if not _safe_network(ipaddress.ip_network(str(address))):
                    safe = False
            elif key in {"metric", "uid", "mtu", "advmss", "hoplimit"} and not value.isdecimal():
                raise ValueError("malformed route number")
        # Non-forwarding routes cannot provide egress, but are still parsed above.
        if route_type in {"blackhole", "unreachable", "prohibit", "throw"}:
            continue
        if not tokens:
            raise ValueError("missing route metadata")
        if _safe_network(network):
            continue
        if route_type == "broadcast" and version == 4 and network == ipaddress.ip_network("255.255.255.255/32"):
            continue
        if route_type == "multicast" and version == 6 and network.subnet_of(ipaddress.ip_network("ff00::/8")):
            continue
        safe = False
    return safe or locked


def verified_lock_rules(raw, wireless=False, wps=False):
    """Exact Bender-owned filter lock, independent of any request or journal."""
    try:
        lines = [tuple(shlex.split(line)) for line in raw.splitlines() if line.strip()]
        chain = 'BWL_PRIVATE'
        own_rules = ([('-A', chain, '-o', 'wlan0', '-j', 'DROP'), ('-A', chain, '-j', 'RETURN')]
                     if wireless else [('-A', chain, '-o', 'lo', '-j', 'RETURN'), ('-A', chain, '-j', 'DROP')])
        if wps:
            own_rules = [('-A', chain, '-o', 'wlan0', '-j', 'DROP'),
                         ('-A', chain, '-o', 'wlan1', '-j', 'DROP'), ('-A', chain, '-j', 'RETURN')]
        expected = {('-N', chain), *own_rules,
                    ('-A', 'OUTPUT', '-j', chain), ('-A', 'FORWARD', '-j', chain)}
        relevant = [line for line in lines if chain in line]
        if (not lines or any(not line or line[0] not in ('-P', '-N', '-A') for line in lines)
                or len(relevant) != len(expected) or set(relevant) != expected):
            return False
        if not all(any(len(line) == 3 and line[:2] == ('-P', c) for line in lines) for c in ('OUTPUT', 'FORWARD')):
            return False
        if [line for line in lines if line[:2] == ('-A', chain)] != own_rules:
            return False
        return all(next((line for line in lines if line[:2] == ('-A', c)), None)
                   == ('-A', c, '-j', chain) for c in ('OUTPUT', 'FORWARD'))
    except (AttributeError, ValueError, TypeError):
        return False


def evaluate(snapshot):
    """Evaluate internal snapshot data; return only fixed, identifier-free checks.

    Missing or malformed evidence is unknown and blocks. This is a local policy
    gate, not an anonymity guarantee or a measurement of cellular RF emissions.
    """
    if not isinstance(snapshot, dict):
        snapshot = {}
    checks = []
    lock = snapshot.get('egress_lock')
    locked = isinstance(lock, dict) and lock.get('ipv4') is True and lock.get('ipv6') is True

    def check(identifier, fn):
        try:
            result = fn()
            if result is not True and result is not False:
                raise ValueError("unknown")
            checks.append({"id": identifier, "status": "pass" if result else "blocked",
                           "reason": "policy_satisfied" if result else "policy_not_satisfied"})
        except (KeyError, ValueError, TypeError, AttributeError):
            checks.append({"id": identifier, "status": "unknown", "reason": "evidence_unavailable_or_malformed"})

    check("android_context", lambda: snapshot["android_context"] if type(snapshot["android_context"]) is bool else None)

    def setting(key, expected):
        value = snapshot["settings"][key]
        if value not in ("0", "1") or not isinstance(value, str):
            raise ValueError("malformed setting")
        return value == expected

    check("airplane_mode", lambda: setting("airplane_mode_on", "1"))
    check("mobile_data", lambda: setting("mobile_data", "0"))
    check("bluetooth", lambda: setting("bluetooth_on", "0"))

    def mac():
        value = snapshot["wlan_mac"]
        if not isinstance(value, str) or not re.fullmatch(r"(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}", value):
            raise ValueError("malformed MAC")
        octets = bytes.fromhex(value.replace(":", ""))
        return (octets[0] & 3) == 2 and value.lower() != "02:00:00:00:00:00"

    check("wlan_mac", mac)
    check("assigned_addresses", lambda: _addresses(_lines(snapshot, "addresses"), locked))
    check("ipv4_routes", lambda: _routes(_lines(snapshot, "routes_v4"), 4, locked))
    check("ipv6_routes", lambda: _routes(_lines(snapshot, "routes_v6"), 6, locked))
    if locked:
        for result in checks:
            if result['id'] in {'assigned_addresses', 'ipv4_routes', 'ipv6_routes'} and result['status'] == 'pass':
                result['reason'] = 'configured_but_egress_blocked'

    def cellular():
        lines = _lines(snapshot, "links")
        if not lines:
            raise ValueError("missing links")
        links = [_link(line) for line in lines]
        if not any(name == "wlan0" for name, flags in links):
            raise ValueError("missing wlan0")
        return not any(_CELLULAR.match(name) and "UP" in flags for name, flags in links)

    check("cellular_links", cellular)
    return {"schema_version": 1, "checked_at": time.time(),
            "allowed": all(c["status"] == "pass" for c in checks), "checks": checks}


def _kill_group(process):
    try:
        os.killpg(process.pid, _KILL_SIGNAL)
    except (ProcessLookupError, PermissionError):
        pass
    finally:
        try:
            process.wait(timeout=0.25)
        except subprocess.TimeoutExpired:
            pass  # SIGKILL was issued; do not turn teardown into an unbounded wait.


@contextmanager
def _cancellation_guard():
    previous = {}
    if threading.current_thread() is threading.main_thread():
        def cancel(signum, frame):
            raise KeyboardInterrupt("host command cancelled")
        for sig in (signal.SIGTERM, signal.SIGINT):
            previous[sig] = signal.signal(sig, cancel)
    try:
        yield
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def _execute(argv, timeout=TIMEOUT_SECONDS):
    """Bounded fixed-argument host executor shared with the reversible profile."""
    with _cancellation_guard():
        return _execute_group(argv, timeout)


def _execute_group(argv, timeout):
    deadline = time.monotonic() + timeout
    process = subprocess.Popen(
        argv,
        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    chunks = bytearray()
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("host snapshot timeout")
                events = selector.select(remaining)
                if not events:
                    raise TimeoutError("host snapshot timeout")
                chunk = os.read(process.stdout.fileno(), min(65536, MAX_OUTPUT_BYTES + 1 - len(chunks)))
                if not chunk:
                    break
                chunks.extend(chunk)
                if len(chunks) > MAX_OUTPUT_BYTES:
                    raise ValueError("host snapshot too large")
        remaining = deadline - time.monotonic()
        if remaining <= 0 or process.wait(timeout=remaining) != 0:
            raise ValueError("host snapshot failed")
        return chunks.decode("utf-8", errors="strict")
    except BaseException:
        _kill_group(process)
        raise
    finally:
        process.stdout.close()


def _collect():
    """Read at most 1 MiB and kill the complete host-query group on failure."""
    return _execute(["/usr/sbin/chroot", "/proc/1/root", "/system/bin/sh", "-c", _SCRIPT])


def _parse(raw):
    sections = {}
    lines = raw.splitlines()
    cursor = 0
    for name in _SECTIONS:
        if cursor >= len(lines) or lines[cursor] != "BEGIN_" + name:
            raise ValueError("missing section")
        cursor += 1
        values = []
        while cursor < len(lines) and lines[cursor] != "END_" + name:
            values.append(lines[cursor])
            cursor += 1
        if cursor >= len(lines):
            raise ValueError("unfinished section")
        cursor += 1
        sections[name] = [x for x in values if x.strip()]
    if cursor != len(lines):
        raise ValueError("unexpected output")
    for name in _SECTIONS[:6]:
        if len(sections[name]) != 1:
            raise ValueError("missing scalar")
    sdk = sections["sdk"][0]
    if not re.fullmatch(r"[1-9][0-9]{0,2}", sdk) or not sections["fingerprint"][0].strip():
        raise ValueError("unsupported Android context")
    return {"android_context": True,
            "settings": {k: sections[k][0] for k in _SECTIONS[2:5]},
            "wlan_mac": sections["wlan_mac"][0],
            **{k: sections[k] for k in _SECTIONS[6:10]},
            'egress_lock': {'ipv4': verified_lock_rules('\n'.join(sections['firewall_v4'])),
                            'ipv6': verified_lock_rules('\n'.join(sections['firewall_v6']))},
            'wireless_lock': {'ipv4': verified_lock_rules('\n'.join(sections['firewall_v4']), True),
                              'ipv6': verified_lock_rules('\n'.join(sections['firewall_v6']), True)}}


def evaluate_wireless(snapshot):
    """Operation policy: preserve other radios; verify wlan0 configuration only.

    IP filter rules do not cover AF_PACKET/firmware traffic or identity carried
    in association frames. No hostname suppression or RF anonymity is claimed.
    """
    base = evaluate(snapshot)
    checks = [c for c in base['checks'] if c['id'] in ('android_context', 'wlan_mac')]
    locks = snapshot.get('wireless_lock', {})
    for family in ('ipv4', 'ipv6'):
        good = locks.get(family) is True
        checks.append({'id': 'wlan_' + family, 'status': 'pass' if good else 'blocked',
                       'reason': 'wlan_filter_verified' if good else 'wlan_filter_unverified'})
    return {'schema_version': 2, 'profile': 'wifi', 'checked_at': time.time(),
            'allowed': all(c['status'] == 'pass' for c in checks), 'checks': checks,
            'limits': ['IP stack filter only; raw DHCP, firmware and association traffic are outside this check.',
                       'Cellular and Bluetooth remain unchanged; this does not establish anonymity.']}


def inspect(profile='strict'):
    """Inspect the Android host from NetHunter, without retaining raw output."""
    try:
        snapshot = _parse(_collect())
        return evaluate_wireless(snapshot) if profile == 'wifi' else evaluate(snapshot)
    except (OSError, ValueError, TimeoutError, subprocess.SubprocessError):
        return evaluate_wireless({}) if profile == 'wifi' else evaluate({})


def require_private_context(job: Path):
    """Persist a redacted policy result and refuse submission unless all pass."""
    # Only a root-owned journal for this exact session selects the new policy.
    from privacy_profile import owns_session
    report = inspect('wifi') if owns_session(job) else inspect()
    destination = Path(job) / "identity-state.json"
    temporary = destination.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    temporary.replace(destination)
    if not report["allowed"]:
        raise RuntimeError("identity preflight blocked")
    return report
