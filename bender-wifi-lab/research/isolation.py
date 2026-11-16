"""Ordinary UDP controlled endpoint proof. No raw sockets, route manipulation or retries."""
import argparse
import hmac
import json
import secrets
import socket
import time

MAX_TIMEOUT = 10

def check(timeout):
    if not 0 < timeout <= MAX_TIMEOUT:
        raise ValueError("Timeout must be within (0,10] seconds")

def receive(bind_host, port, nonce, timeout=3):
    check(timeout)
    expected = nonce.encode("ascii")
    if len(expected) != 64 or any(c not in "0123456789abcdef" for c in nonce):
        raise ValueError("Expected a 256-bit hex nonce")
    deadline = time.monotonic() + timeout
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind((bind_host, port))
        sock.settimeout(timeout)
        try:
            data, peer = sock.recvfrom(257)
        except socket.timeout:
            return {"state":"timeout", "delivery":"unknown", "path":"unknown"}
        valid = hmac.compare_digest(data, expected)
        if valid:
            sock.sendto(data, peer)
        return {"state":"nonce_received" if valid else "nonce_mismatch", "delivery":"proven" if valid else "unknown", "path":"unknown", "elapsed_within_budget":time.monotonic() <= deadline}

def send(host, port, nonce=None, timeout=3):
    check(timeout)
    nonce = nonce or secrets.token_hex(32)
    if len(nonce) != 64 or any(c not in "0123456789abcdef" for c in nonce):
        raise ValueError("Expected a 256-bit hex nonce")
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(timeout)
        # Connected UDP restricts accepted replies to the requested endpoint.
        sock.connect((host, port))
        sock.send(nonce.encode("ascii"))
        try:
            reply = sock.recv(257)
        except (socket.timeout, ConnectionRefusedError):
            return {"state":"no_reply", "delivery":"unknown", "path":"unknown", "requests":1}
        valid = hmac.compare_digest(reply, nonce.encode("ascii"))
        return {"state":"nonce_echo" if valid else "nonce_mismatch", "delivery":"round_trip_proven" if valid else "unknown", "path":"unknown", "requests":1}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("mode", choices=["send","receive"])
    p.add_argument("--host", required=True); p.add_argument("--port", required=True,type=int)
    p.add_argument("--nonce", required=True); p.add_argument("--timeout",type=float,default=3)
    a=p.parse_args()
    print(json.dumps((send if a.mode == "send" else receive)(a.host,a.port,a.nonce,a.timeout)))
if __name__ == "__main__": main()
