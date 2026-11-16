"""Offline evidence tools: bounded requests and diagnostics that cannot leak secrets."""
import json
from pathlib import Path
import socket
import sys
import threading
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from research import wps, isolation, firmware_audit

class ResearchTests(unittest.TestCase):
    def test_wps_redacts_all_unrecognized_fields(self):
        secrets=["wifi-password", "secret-ssid", "12345670", "raw-secret-error"]
        captured=[]
        result=wps.analyze([{"type":"M1","pin":secrets[2]},
          {"type":"CREDENTIAL","network_key":secrets[0],"ssid":secrets[1]},
          {"type":"ERROR","message":secrets[3]},
          {"type":secrets[0],"nested":{"password":secrets[0]}}],captured.append)
        serialized=json.dumps(result)
        for secret in secrets: self.assertNotIn(secret,serialized)
        self.assertEqual("error",result["state"])
        self.assertEqual(1,result["credential_count"])
        self.assertEqual(secrets[0],captured[0].key)
        self.assertEqual("Credential(<redacted>)",repr(captured[0]))
    def test_wps_terminal_states_and_budget(self):
        for event,state in [("NACK","rejected"),("TIMEOUT","timeout"),("CANCELLED","cancelled"),("DONE","complete")]:
            self.assertEqual(state,wps.analyze([{"type":event}])["state"])
        result=wps.analyze([{"type":"M2"},{"type":"M1"}],max_events=1)
        self.assertIn("event_budget_exceeded",result["warnings"])
        self.assertIn("out_of_order_message",result["warnings"])
    def test_real_loopback_nonce_echo(self):
        # Bind a real controlled receiver first; no device or radio interface.
        ready=threading.Event(); result={}; nonce="ab"*32
        def receiver():
            with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
                sock.bind(("127.0.0.1",0)); sock.settimeout(2)
                result["port"]=sock.getsockname()[1]; ready.set()
                data,peer=sock.recvfrom(257); result["nonce"]=data
                sock.sendto(data,peer)
        t=threading.Thread(target=receiver); t.start(); self.assertTrue(ready.wait(2))
        outcome=isolation.send("127.0.0.1",result["port"],nonce,timeout=2)
        t.join(3); self.assertFalse(t.is_alive())
        self.assertEqual(nonce.encode(),result["nonce"])
        self.assertEqual("round_trip_proven",outcome["delivery"])
        self.assertEqual("unknown",outcome["path"])
        self.assertEqual(1,outcome["requests"])
    def test_socket_sender_never_retries(self):
        with patch.object(isolation.socket,"socket") as factory:
            sock=factory.return_value.__enter__.return_value
            sock.recv.side_effect=socket.timeout
            outcome=isolation.send("127.0.0.1",12345,"ab"*32,timeout=.1)
            self.assertEqual("unknown",outcome["delivery"])
            sock.send.assert_called_once()
    def test_receiver_rejects_wrong_nonce_and_does_not_echo(self):
        with patch.object(isolation.socket,"socket") as factory:
            sock=factory.return_value.__enter__.return_value
            sock.recvfrom.return_value=(b"wrong",("127.0.0.1",12345))
            result=isolation.receive("127.0.0.1",12345,"ab"*32,timeout=.1)
            self.assertEqual("nonce_mismatch",result["state"])
            sock.sendto.assert_not_called()
    def test_invalid_budget_before_socket_creation(self):
        with patch.object(isolation.socket,"socket") as factory:
            for budget in [0,-1,11]:
                with self.assertRaises(ValueError): isolation.send("127.0.0.1",1,timeout=budget)
            factory.assert_not_called()
    def test_catalog_does_not_enable_unsubstantiated_actions(self):
        catalog=json.loads((Path(__file__).resolve().parents[2]/"runtime/catalog.json").read_text())
        for action in catalog["actions"]:
            if action["operation"] in {"probe","capture","lab","restore"}:
                self.assertEqual("experimental",action["status"])
            if action["id"] in {"wps","isolation","management-txrx","pmf","softap","mlo","firmware"}:
                self.assertIsNone(action["operation"])
    def test_firmware_hash_mismatch_blocks_predicate_claims(self):
        manifest={"artifacts":{"firmware":{"path":__file__,"sha256":"0"*64}}}
        result=firmware_audit.audit(manifest)
        self.assertEqual("rejected_hash_mismatch",result["status"])
        self.assertNotIn("predicates",result)
