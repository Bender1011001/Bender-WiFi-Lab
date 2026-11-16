"""Automatic-session ownership, selective settings and failure rollback."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from test_privacy_profile import FakeHost, P
import identity_guard as G


class SessionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.root=Path(self.temp.name)
        self.job=self.root/'bw_100_0123456789ab';self.job.mkdir()
        self.directory=self.root/'journal';self.path=self.directory/'pending.json'
        self.host=FakeHost(self.path)
    def tearDown(self):self.temp.cleanup()
    def prepare(self,profile='wifi'):
        return P.prepare(self.job,runner=self.host,journal_dir=self.directory,profile=profile,session=True)
    def restore(self,job=None):
        return P.restore(job or self.job,runner=self.host,journal_dir=self.directory,owner_only=True)
    def test_default_preserves_other_radios_and_verifies_both_filters(self):
        self.assertTrue(self.prepare()['active'])
        self.assertEqual({**self.host.original,'wifi_on':True},self.host.values)
        for family in (4,6):
            self.assertTrue(G.verified_lock_rules(self.host.rules(family),True))
            self.assertFalse(G.verified_lock_rules(self.host.rules(family)))
        self.assertTrue(P.owns_session(self.job,self.directory,True))
        self.assertTrue(self.restore()['restored'])
        self.assertEqual(self.host.original,self.host.values)
        self.assertEqual(self.host.baseline,self.host.tables)
    def test_never_overwrites_another_session(self):
        self.prepare();count=self.host.mutations;original=self.path.read_bytes()
        other=self.root/'bw_200_0123456789ab';other.mkdir()
        self.assertEqual('different_session_owner',self.restore(other)['blockers'][0])
        self.assertEqual(count,self.host.mutations);self.assertEqual(original,self.path.read_bytes())
    def test_legacy_manual_profile_is_not_adopted(self):
        P.prepare(self.job,runner=self.host,journal_dir=self.directory)
        self.assertEqual('restore_required',self.prepare()['blockers'][0])
        self.assertEqual('different_session_owner',self.restore()['blockers'][0])
        self.assertTrue(P.restore(self.job,runner=self.host,journal_dir=self.directory)['restored'])
    def test_other_radios_changed_by_user_are_not_restored_in_default_profile(self):
        self.prepare();self.host.values['bluetooth_on']=False;self.host.values['mobile_data']=False
        self.assertTrue(self.restore()['restored'])
        self.assertFalse(self.host.values['bluetooth_on']);self.assertFalse(self.host.values['mobile_data'])
    def test_strict_session_is_automatically_recoverable(self):
        self.assertTrue(self.prepare('strict')['active'])
        self.assertEqual(P.DESIRED,self.host.values)
        self.assertEqual(self.job.name,json.loads(self.path.read_text())['owner'])
        self.assertTrue(self.restore()['restored']);self.assertEqual(self.host.original,self.host.values)
    def test_every_apply_failure_rolls_back(self):
        for step in range(1,12):
            with self.subTest(step=step):
                self.host=FakeHost(self.path);self.host.fail_at=step
                self.assertFalse(self.prepare()['active'])
                self.assertFalse(self.path.exists());self.assertEqual(self.host.baseline,self.host.tables)
                self.assertEqual(self.host.original,self.host.values)
    def test_failed_restore_retains_journal(self):
        self.prepare();self.host.fail_at=self.host.mutations+1;self.host.persistent=True
        self.assertTrue(self.restore()['pending']);self.assertTrue(self.path.exists())
    def test_interface_failure_rolls_back_enabled_wifi_and_filters(self):
        with patch.object(self.host,'interface_ready',return_value=False),patch.object(P.time,'sleep'):
            self.assertFalse(self.prepare()['active'])
        self.assertFalse(self.path.exists());self.assertEqual(self.host.original,self.host.values)
        self.assertEqual(self.host.baseline,self.host.tables)
    def test_unrecognized_filter_is_not_removed(self):
        self.prepare();self.host.tables[4].append(('-A',P.CHAIN,'-p','udp','-j','ACCEPT'))
        self.assertTrue(self.restore()['pending']);self.assertTrue(self.path.exists())
        self.assertIn(('-A',P.CHAIN,'-p','udp','-j','ACCEPT'),self.host.tables[4])
    def test_runtime_policy_does_not_require_airplane_and_bluetooth_off(self):
        snapshot={'android_context':True,'wlan_mac':'02:11:22:33:44:55','wireless_lock':{'ipv4':True,'ipv6':True}}
        result=G.evaluate_wireless(snapshot)
        self.assertTrue(result['allowed']);self.assertEqual(2,result['schema_version'])
        snapshot['wireless_lock']['ipv6']=False
        self.assertFalse(G.evaluate_wireless(snapshot)['allowed'])
    def test_factory_and_placeholder_addresses_still_block(self):
        for address in ('00:11:22:33:44:55','02:00:00:00:00:00','malformed'):
            result=G.evaluate_wireless({'android_context':True,'wlan_mac':address,'wireless_lock':{'ipv4':True,'ipv6':True}})
            self.assertFalse(result['allowed'])
    def test_gate_uses_root_journal_and_repeats_snapshot(self):
        with patch.object(P,'owns_session',return_value=True),patch.object(G,'inspect',return_value={'allowed':True}) as inspect:
            G.require_private_context(self.job);inspect.assert_called_once_with('wifi')


if __name__=='__main__':unittest.main()
