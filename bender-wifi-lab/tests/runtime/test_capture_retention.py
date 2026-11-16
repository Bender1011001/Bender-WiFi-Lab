"""Disposable mock-filesystem tests; never invokes ADB or a real radio."""
import json
from pathlib import Path
import unittest
import test_qca_supervisor as fixtures


class CaptureRetentionTests(unittest.TestCase):
    def setUp(self):
        self.fixture=fixtures.SupervisorTests();self.fixture.setUp()
        self.root=self.fixture.root

    def tearDown(self):
        self.fixture.tearDown()

    def test_successful_capture_removes_only_verified_root_duplicates(self):
        self.assertEqual(0,self.fixture.run_supervisor(),self.fixture.output)
        self.assertTrue((self.root/'app/s1/capture.pcap').is_file())
        self.assertFalse((self.root/'kali/tmp/hijacker-qca/s1/capture-01.cap').exists())
        self.assertTrue((self.root/'kali/tmp/hijacker-qca/s1/result.json').is_file())
        report=json.loads((self.root/'app/s1/retention-state.json').read_text())
        self.assertFalse(report['runtime_capture_retained'])

    def test_import_duplicate_is_removed_but_app_original_retained(self):
        (self.root/'app/s1/input.pcap').write_bytes(b'SYNTHETIC')
        self.assertEqual(0,self.fixture.run_supervisor('analyze'),self.fixture.output)
        self.assertEqual(b'SYNTHETIC',(self.root/'app/s1/input.pcap').read_bytes())
        self.assertFalse((self.root/'kali/tmp/hijacker-qca/s1/input.pcap').exists())

    def test_failed_handoff_keeps_raw_source_and_reports_retention(self):
        script=self.fixture.script.read_text()
        fragment='cat "$JOB/capture-01.cap" > "$OUT/capture.pcap" || return 1'
        self.assertIn(fragment,script)
        self.fixture.script.write_text(script.replace(fragment,'return 1'),newline='\n')
        self.assertNotEqual(0,self.fixture.run_supervisor())
        self.assertTrue((self.root/'kali/tmp/hijacker-qca/s1/capture-01.cap').is_file())
        self.assertTrue(json.loads((self.root/'app/s1/retention-state.json').read_text())['handoff_failed'])

    def seed(self,name='bw_123_012345abcdef'):
        root=self.root/'kali/tmp/hijacker-qca'/name
        root.mkdir(parents=True)
        (root/'capture-01.cap').write_bytes(b'SYNTHETIC')
        (root/'attempt.json').write_text('{"intent":"preserve"}')
        return root

    def test_explicit_delete_removes_both_copies_keeps_summaries_and_intent(self):
        name='bw_123_012345abcdef';root=self.seed(name)
        app=self.root/'app'/name;app.mkdir()
        (app/'capture.pcap').write_bytes(b'SYNTHETIC');(app/'observations-v1.json').write_text('{}')
        (self.root/'app/s1/delete-target').write_text(name)
        self.assertEqual(0,self.fixture.run_supervisor('delete_capture'),self.fixture.output)
        self.assertFalse((root/'capture-01.cap').exists());self.assertFalse((app/'capture.pcap').exists())
        self.assertTrue((root/'attempt.json').exists());self.assertTrue((app/'observations-v1.json').exists())

    def test_orphan_cleanup_does_not_touch_other_tools_or_app_copy(self):
        owned=self.seed();foreign=self.seed('other-tool-session')
        (self.root/'app/s1/capture.pcap').write_bytes(b'APP COPY')
        self.assertEqual(0,self.fixture.run_supervisor('cleanup_captures'),self.fixture.output)
        self.assertFalse((owned/'capture-01.cap').exists());self.assertTrue((foreign/'capture-01.cap').exists())
        self.assertTrue((owned/'attempt.json').exists());self.assertEqual(b'APP COPY',(self.root/'app/s1/capture.pcap').read_bytes())

    def test_symlink_and_traversal_targets_are_rejected(self):
        root=self.seed()
        (self.root/'app/s1/delete-target').write_text('../other-tool-session')
        self.assertNotEqual(0,self.fixture.run_supervisor('delete_capture'))
        self.assertTrue((root/'capture-01.cap').exists())
        (root/'capture-01.cap').unlink()
        other=self.root/'private.pcap';other.write_bytes(b'UNRELATED')
        (root/'capture-01.cap').symlink_to(other)
        self.assertNotEqual(0,self.fixture.run_supervisor('cleanup_captures'))
        self.assertEqual(b'UNRELATED',other.read_bytes())

    def test_pending_recovery_blocks_deletion_and_remains_reported(self):
        root=self.seed();state=self.root/'state/recovery';state.mkdir(parents=True)
        (state/'pending').write_text('preserve')
        self.assertNotEqual(0,self.fixture.run_supervisor('cleanup_captures'))
        self.assertTrue((root/'capture-01.cap').is_file());self.assertTrue((state/'pending').is_file())

    def test_recovery_query_detects_journals_without_runtime_or_saved_app_state(self):
        state=self.root/'state/bender-privacy';state.mkdir(parents=True)
        (state/'pending.json').write_text('{}')
        (self.root/'kali/usr/bin/python3').unlink();(self.root/'mode').unlink()
        self.assertEqual(0,self.fixture.run_supervisor('recovery_status'),self.fixture.output)
        report=json.loads((self.root/'app/s1/radio-state.json').read_text())
        self.assertTrue(report['privacy_pending'])
        self.assertTrue((state/'pending.json').is_file())

    def test_preexisting_cancel_or_owner_death_prevents_cleanup(self):
        root=self.seed()
        for marker in ('app/s1/cancel','owner-dead'):
            (self.root/marker).write_text('stop')
            self.assertEqual(130,self.fixture.run_supervisor('cleanup_captures'),self.fixture.output)
            self.assertTrue((root/'capture-01.cap').is_file())
            report=json.loads((self.root/'app/s1/result.json').read_text())
            self.assertTrue(report['cancelled']);self.assertEqual(0,report['raw_capture_files_deleted'])
            (self.root/marker).unlink()

    def test_mid_cleanup_cancel_reports_partial_deletion_and_does_not_continue(self):
        root=self.seed();(root/'input.pcap').write_bytes(b'SYNTHETIC SECOND')
        script=self.fixture.script.read_text()
        script=script.replace('rm -f "$FILE" || return 1','rm -f "$FILE" || return 1\n            touch "$OUT/cancel"')
        self.fixture.script.write_text(script,newline='\n')
        self.assertEqual(130,self.fixture.run_supervisor('cleanup_captures'),self.fixture.output)
        self.assertFalse((root/'capture-01.cap').exists());self.assertTrue((root/'input.pcap').exists())
        report=json.loads((self.root/'app/s1/result.json').read_text())
        self.assertFalse(report['raw_capture_deletion_complete']);self.assertEqual(1,report['raw_capture_files_deleted'])
