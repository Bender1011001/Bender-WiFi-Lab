"""Transactional simulated-host tests. No Android commands are executed."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

RUNTIME = Path(__file__).resolve().parents[2] / 'runtime'
sys.path.insert(0, str(RUNTIME))
import privacy_profile as P


class FakeHost:
    def __init__(self, journal):
        self.journal = journal
        self.values = dict(zip(P.KEYS, (False, True, True, False)))
        self.original = dict(self.values)
        self.tables = {f: [('-P', 'INPUT', 'ACCEPT'), ('-P', 'OUTPUT', 'ACCEPT'),
                           ('-P', 'FORWARD', 'DROP'), ('-N', 'UNRELATED'),
                           ('-A', 'OUTPUT', '-j', 'UNRELATED'),
                           ('-A', 'UNRELATED', '-d', '192.168.0.0/16', '-j', 'ACCEPT')]
                       for f in (4, 6)}
        self.baseline = {f: list(v) for f, v in self.tables.items()}
        self.mutations = 0
        self.fail_at = None
        self.failure = RuntimeError
        self.persistent = False
        self.unsupported = False
        self.lie_settings = False

    def context(self):
        if self.unsupported:
            raise ValueError('host identifier must not escape')

    def interface_ready(self):
        return self.values['wifi_on']

    def settings(self):
        return dict(self.original if self.lie_settings else self.values)

    def _before_mutation(self):
        if not self.journal.exists():
            raise AssertionError('mutation before journal')
        saved = json.loads(self.journal.read_text())
        if saved['original'] != self.original:
            raise AssertionError('original states overwritten')
        self.mutations += 1
        if self.fail_at is not None and self.mutations >= self.fail_at:
            if not self.persistent:
                self.fail_at = None
            raise self.failure('secret error details')

    def set_setting(self, key, value):
        self._before_mutation()
        self.values[key] = value

    def rules(self, family):
        return '\n'.join(' '.join(line) for line in self.tables[family]) + '\n'

    def firewall(self, family, *args):
        self._before_mutation()
        table = self.tables[family]
        op = args[0]
        if op == '-N':
            if ('-N', args[1]) in table:
                raise RuntimeError('exists')
            table.append(args)
        elif op == '-A':
            table.append(args)
        elif op == '-I':
            chain = args[1]
            index = next((i for i, line in enumerate(table) if line[:2] == ('-A', chain)), len(table))
            table.insert(index, ('-A', chain, *args[3:]))
        elif op == '-D':
            table.remove(('-A', *args[1:]))
        elif op == '-X':
            if any(line[:2] == ('-A', args[1]) or line[-2:] == ('-j', args[1]) for line in table):
                raise RuntimeError('referenced')
            table.remove(('-N', args[1]))
        else:
            raise AssertionError(args)


class ProfileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.directory = self.root / 'private'
        self.job = self.root / 'job'
        self.job.mkdir()
        self.path = self.directory / 'pending.json'
        self.host = FakeHost(self.path)

    def tearDown(self):
        self.temp.cleanup()

    def call(self, action):
        return action(self.job, runner=self.host, journal_dir=self.directory)

    def test_prepare_leaves_restore_obligation_and_egress_lock(self):
        report = self.call(P.prepare)
        self.assertEqual('prepared', report['state'])
        self.assertTrue(report['active'])
        self.assertTrue(report['pending'])
        self.assertFalse(report['restored'])
        self.assertEqual(P.DESIRED, self.host.values)
        self.assertTrue(all(P.verify_rules(self.host.rules(f)) for f in (4, 6)))
        self.assertEqual(self.host.original, json.loads(self.path.read_text())['original'])

    def test_restore_round_trip_preserves_unrelated_rules(self):
        self.call(P.prepare)
        report = self.call(P.restore)
        self.assertTrue(report['restored'])
        self.assertFalse(report['pending'])
        self.assertFalse(report['active'])
        self.assertFalse(self.path.exists())
        self.assertEqual(self.host.original, self.host.values)
        self.assertEqual(self.host.baseline, self.host.tables)

    def test_pending_journal_cannot_be_overwritten(self):
        self.call(P.prepare)
        original = self.path.read_bytes()
        count = self.host.mutations
        report = self.call(P.prepare)
        self.assertEqual('pending', report['state'])
        self.assertEqual(original, self.path.read_bytes())
        self.assertEqual(count, self.host.mutations)

    def test_unsupported_host_has_no_mutation_or_journal(self):
        self.host.unsupported = True
        report = self.call(P.prepare)
        self.assertEqual('blocked', report['state'])
        self.assertEqual(0, self.host.mutations)
        self.assertFalse(self.path.exists())
        self.assertNotIn('identifier', json.dumps(report))

    def test_name_collision_is_not_modified(self):
        self.host.tables[6].append(('-N', P.CHAIN))
        before = list(self.host.tables[6])
        report = self.call(P.prepare)
        self.assertFalse(report['active'])
        self.assertEqual(['firewall_name_collision'], report['blockers'])
        self.assertEqual(0, self.host.mutations)
        self.assertEqual(before, self.host.tables[6])

    def test_each_apply_command_failure_rolls_back(self):
        # Two families * five mutations, then four setting commands.
        for step in range(1, 15):
            with self.subTest(step=step):
                self.host = FakeHost(self.path)
                self.host.fail_at = step
                report = self.call(P.prepare)
                self.assertEqual('blocked', report['state'])
                self.assertFalse(self.path.exists())
                self.assertEqual(self.host.original, self.host.values)
                self.assertEqual(self.host.baseline, self.host.tables)

    def test_incomplete_rollback_retains_journal(self):
        self.host.fail_at = 7
        self.host.persistent = True
        report = self.call(P.prepare)
        self.assertTrue(report['pending'])
        self.assertTrue(self.path.exists())
        self.assertEqual(['restore_incomplete'], report['blockers'])
        self.host.fail_at = None
        self.assertTrue(self.call(P.restore)['restored'])

    def test_cancellation_attempts_rollback_then_propagates(self):
        self.host.fail_at = 8
        self.host.failure = KeyboardInterrupt
        with self.assertRaises(KeyboardInterrupt):
            self.call(P.prepare)
        self.assertFalse(self.path.exists())
        self.assertEqual(self.host.baseline, self.host.tables)

    def test_restart_recovers_partial_chain_from_durable_journal(self):
        self.directory.mkdir()
        P._durable(self.path, {'schema_version': 1, 'original': self.host.original})
        self.host.firewall(4, '-N', P.CHAIN)
        self.host.firewall(4, *P._OWN_RULES[0])
        self.host.values = dict(P.DESIRED)
        self.assertTrue(self.call(P.restore)['restored'])
        self.assertEqual(self.host.baseline, self.host.tables)

    def test_changed_owned_rules_are_not_flushed(self):
        self.call(P.prepare)
        unexpected = ('-A', P.CHAIN, '-d', '1.2.3.4', '-j', 'ACCEPT')
        self.host.tables[4].append(unexpected)
        report = self.call(P.restore)
        self.assertTrue(report['pending'])
        self.assertIn(unexpected, self.host.tables[4])
        self.assertTrue(self.path.exists())

    def test_changed_settings_make_status_inactive_pending(self):
        self.call(P.prepare)
        self.host.values['mobile_data'] = True
        report = self.call(P.status)
        self.assertFalse(report['active'])
        self.assertTrue(report['pending'])
        self.assertEqual('pending', report['state'])

    def test_status_is_read_only_and_public_report_redacted(self):
        self.call(P.prepare)
        count = self.host.mutations
        report = self.call(P.status)
        self.assertEqual(count, self.host.mutations)
        self.assertEqual({'schema_version', 'checked_at', 'pending', 'active', 'restored', 'state', 'message', 'blockers'}, set(report))
        self.assertNotIn('original', json.dumps(report))
        self.assertNotIn('192.168', json.dumps(report))

    def test_corrupt_journal_retained_and_blocks(self):
        self.directory.mkdir()
        for malformed in ('not json', '1', 'null', '[]', '{"schema_version": 1}'):
            self.path.write_text(malformed)
            self.assertTrue(self.call(P.restore)['pending'])
            self.assertTrue(self.path.exists())
            self.assertEqual(0, self.host.mutations)

    def test_missing_journal_is_idle_without_restoration_claim(self):
        for action in (P.restore, P.status):
            report = self.call(action)
            self.assertEqual('idle', report['state'])
            self.assertFalse(report['pending'])
            self.assertFalse(report['restored'])
            self.assertEqual(0, self.host.mutations)

    def test_missing_journal_with_owned_rules_cannot_claim_idle(self):
        self.host.tables[4].append(('-N', P.CHAIN))
        report = self.call(P.restore)
        self.assertEqual('pending', report['state'])
        self.assertFalse(report['restored'])
        self.assertTrue(report['pending'])

    def test_ipv6_backend_unavailable_prevents_any_mutation(self):
        original_rules = self.host.rules
        def rules(family):
            if family == 6:
                raise RuntimeError('unsupported IPv6')
            return original_rules(family)
        self.host.rules = rules
        self.assertEqual('blocked', self.call(P.prepare)['state'])
        self.assertEqual(0, self.host.mutations)
        self.assertFalse(self.path.exists())

    def test_actual_setting_mismatch_prevents_prepared_claim(self):
        self.host.lie_settings = True
        with patch.object(P.time, 'sleep'):
            report = self.call(P.prepare)
        self.assertEqual('blocked', report['state'])
        self.assertFalse(report['active'])

    def test_manual_state_changes_do_not_clear_pending_journal(self):
        self.call(P.prepare)
        self.host.values = dict(self.host.original)
        self.host.tables = {f: list(v) for f, v in self.host.baseline.items()}
        report = self.call(P.status)
        self.assertTrue(report['pending'])
        self.assertFalse(report['restored'])
        self.assertTrue(self.path.exists())

    def test_exact_firewall_order_required(self):
        self.call(P.prepare)
        raw = self.host.rules(4)
        self.assertTrue(P.verify_rules(raw))
        self.assertFalse(P.verify_rules(raw.replace('-o lo -j RETURN', '-o wlan0 -j RETURN')))
        table = self.host.tables[4]
        hook = ('-A', 'OUTPUT', '-j', P.CHAIN)
        table.remove(hook)
        table.append(hook)
        self.assertFalse(P.verify_rules(self.host.rules(4)))

    def test_host_runner_allows_only_fixed_commands(self):
        runner = P.HostRunner()
        with patch.object(P, '_execute', return_value='') as execute:
            runner.set_setting('wifi_on', True)
            runner.set_setting('mobile_data', False)
            runner.firewall(6, '-I', 'OUTPUT', '1', '-j', P.CHAIN)
            self.assertEqual('/usr/sbin/chroot', execute.call_args.args[0][0])
            with self.assertRaises(ValueError):
                runner.firewall(4, '-F', 'OUTPUT')
            with self.assertRaises(ValueError):
                runner.set_setting('imei', True)


if __name__ == '__main__':
    unittest.main()
