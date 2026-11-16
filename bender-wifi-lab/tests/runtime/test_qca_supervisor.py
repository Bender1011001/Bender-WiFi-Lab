"""Run the actual supervisor control flow in a temporary mocked filesystem.

No root, chroot, Android settings, real process-group signals or radio operations.
Absolute device paths in a disposable script copy point only inside TemporaryDirectory.
The mocks model control flow, not firmware timing or SELinux on Android.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[2] / 'runtime'
GIT_BASH = Path('C:/Program Files/Git/bin/bash.exe')
BASH = str(GIT_BASH) if os.name == 'nt' and GIT_BASH.exists() else shutil.which('bash')


def shell_path(path):
    text = Path(path).resolve().as_posix()
    return '/' + text[0].lower() + text[2:] if os.name == 'nt' else text


MOCKS = r'''
toybox() {
    [ "$1" = flock ] || return 99
    shift
    [ "${MOCK_FLOCK_FAIL:-0}" = 0 ] || return 1
    # Windows tests model exclusion; Linux tests exercise the real kernel lock.
    case "$(uname -s)" in Linux) command flock "$@";; *) return 0;; esac
}
id() { echo 0; }
mkdir() {
    if [ "${1:-}" = -m ]; then shift 2; fi
    command mkdir "$@"
}
stat() {
    if [ "$2" = %u ]; then echo "${MOCK_PATH_UID:-0}"
    elif [ "$2" = %a ] && [ -n "${MOCK_PATH_MODE:-}" ]; then echo "$MOCK_PATH_MODE"
    else command stat "$@"; fi
}
getprop() {
    case "$1" in
        ro.product.model) echo "${MOCK_MODEL:-CPH2655}";;
        ro.build.display.id) echo "${MOCK_BUILD:-CPH2655_16.0.8.301(EX01)}";;
    esac
}
sha256sum() { echo "${MOCK_HASH:-d6bd3fac600aaeba858e83292df167bc410457c07e422047c1a181c31d24d3df}  fixture"; }
settings() { cat "$MOCK_ROOT/wifi"; }
svc() {
    if [ "$2" = enable ]; then
        [ "${MOCK_RESTORE_FAIL:-0}" = 0 ] || return 1
        echo 1 > "$MOCK_ROOT/wifi"; echo 1 > "$MOCK_ROOT/type"
    else echo 0 > "$MOCK_ROOT/wifi"; fi
}
ip() {
    if [ "$4" = up ] && [ "$(cat "$PARAM")" = 4 ]; then
        [ "${MOCK_START_FAIL:-0}" = 0 ] || return 1
        echo 803 > "$MOCK_ROOT/type"
    fi
}
iw() { :; }
chcon() { :; }
sleep() {
    if [ "${MOCK_CANCEL:-0}" = 1 ] && [ "$(cat "$PARAM")" = 4 ]; then touch "$OUT/cancel"; fi
    command sleep 0.01
}
kill() {
    if [ "$1" = -0 ] && [ "$2" = 999999 ]; then [ ! -f "$MOCK_ROOT/owner-dead" ]; return; fi
    builtin kill "$@"
}
chroot() {
    case "$*" in
        *'bender-worker.py analyze '*|*'bender-worker.py capture-analysis '*)
            if [ "${MOCK_CHECK_WORKER_PATH:-0}" = 1 ]; then
                case "$*" in *'/usr/bin/env PATH=/usr/sbin:/usr/bin:/sbin:/bin '*)
                    printf 'verified\n' > "$MOCK_ROOT/worker-path-checked";;
                    *) return 31;;
                esac
            fi
            [ "$(cat "$PARAM")" = 0 ] || return 98
            if [ "${MOCK_ANALYSIS_CANCEL:-0}" = 1 ]; then touch "$OUT/cancel"; exec sleep 0.2; fi
            if [ "${MOCK_ANALYSIS_OWNER_DEATH:-0}" = 1 ]; then touch "$MOCK_ROOT/owner-dead"; exec sleep 0.2; fi
            [ "${MOCK_ANALYSIS_FAIL:-0}" = 0 ] || return 9
            printf '{"schema_version":1}' > "$JOB/observations-v1.json";;
        *'qca-scan.py scan '*)
            printf 'BSSID, header\n02:11:22:33:44:50, fixture\n' > "$JOB/capture-01.csv"
            printf 'mock pcap data' > "$JOB/capture-01.cap"
            printf '{"schema":1,"frames":7,"networks":[],"clients":[],"eapol":[]}' > "$JOB/observations.json"
            [ "${MOCK_SCAN_FAIL:-0}" = 0 ] || return 9
            if [ "${MOCK_CANCEL_SCAN:-0}" = 1 ]; then touch "$OUT/cancel"; fi
            if [ "${MOCK_FINISH_SCAN:-0}" = 1 ]; then
                printf 'PHASE:Scanning 5180 MHz\n' > "$JOB/capture.log"
                touch "$OUT/finish"
                for _ in $(seq 1 100); do
                    if [ -f "$JOB/finish" ]; then command sleep 0.16; return 0; fi
                    command sleep 0.02
                done
                return 19
            fi
            # exec means the supervisor owns this exact process, not a grandchild.
            exec sleep 0.2;;
        *'qca-scan.py plan '*)
            [ "${MOCK_PLAN_FAIL:-0}" = 0 ] || return 1
            printf '{"frequencies_mhz":[2412,5180,5955]}' > "$JOB/scan-plan.json";;
        *'bender-worker.py session_prepare '*)
            [ ! -e "$BASE/bender-privacy/pending.json" ] || return 9
            command mkdir -p "$BASE/bender-privacy"
            printf '{"owner":"s1"}' > "$BASE/bender-privacy/pending.json"
            printf '{"state":"prepared","pending":true,"active":true}' > "$JOB/privacy-state.json"
            [ "${MOCK_AUTO_CANCEL:-0}" = 0 ] || touch "$OUT/cancel";;
        *'bender-worker.py session_restore '*)
            [ "${MOCK_AUTO_RESTORE_FAIL:-0}" = 0 ] || return 9
            rm -f "$BASE/bender-privacy/pending.json"
            printf '{"state":"restored","pending":false,"restored":true}' > "$JOB/privacy-state.json";;
        *'bender-worker.py prepare '*)
            command mkdir -p "$BASE/bender-privacy"
            printf '{"saved":true}' > "$BASE/bender-privacy/pending.json"
            printf '{"state":"pending","pending":true}' > "$JOB/privacy-state.json"
            [ "${MOCK_PRIVACY_FAIL:-0}" = 0 ] || return 9
            if [ "${MOCK_PRIVACY_CANCEL:-0}" = 1 ]; then touch "$OUT/cancel"; exec sleep 0.2; fi
            if [ "${MOCK_PRIVACY_OWNER_DEATH:-0}" = 1 ]; then touch "$MOCK_ROOT/owner-dead"; exec sleep 0.2; fi
            printf '{"ok":true,"action":"prepare"}' > "$JOB/result.json"
            printf '{"state":"prepared","pending":true,"active":true}' > "$JOB/privacy-state.json";;
        *'bender-worker.py privacy_restore '*)
            rm -f "$BASE/bender-privacy/pending.json"
            printf '{"ok":true,"action":"privacy_restore"}' > "$JOB/result.json"
            printf '{"state":"restored","pending":false,"restored":true}' > "$JOB/privacy-state.json";;
        *'bender-worker.py privacy_status '*)
            printf '{"ok":true,"action":"privacy_status"}' > "$JOB/result.json"
            printf '{"state":"pending","pending":true}' > "$JOB/privacy-state.json";;
        *' identity '*)
            if [ "${MOCK_IDENTITY_CANCEL:-0}" = 1 ]; then touch "$OUT/cancel"; exec sleep 0.2; fi
            if [ "${MOCK_IDENTITY_OWNER_DEATH:-0}" = 1 ]; then touch "$MOCK_ROOT/owner-dead"; exec sleep 0.2; fi
            printf '{"ok":true,"action":"identity"}\n' > "$JOB/result.json"; printf '{"allowed":true}' > "$JOB/identity-state.json";;
        *' probe '*) printf '{"ok":true,"action":"probe"}\n' > "$JOB/result.json";;
        *' capture '*) printf '{"ok":true,"action":"capture"}\n' > "$JOB/result.json";;
        *' lab '*)
            if [ "${MOCK_LAB_CANCEL_VALIDATION:-0}" = 1 ]; then touch "$OUT/cancel"; exec sleep 0.2; fi
            printf '{"state":"prepared; outcome unconfirmed"}' > "$JOB/attempt.json"
            if [ "${MOCK_LAB_CANCEL_INTENT:-0}" = 1 ]; then touch "$OUT/cancel"; exec sleep 0.2; fi
            return 9;;
        *) echo 'Unexpected mocked chroot action' >&2; return 1;;
    esac
}
'''


@unittest.skipUnless(BASH, 'bash is required for shell control-flow tests')
class SupervisorTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='hijacker-supervisor-')
        self.root = Path(self.tmp.name)
        self.posix = shell_path(self.root)
        for path in ('app/s1', 'vendor', 'odm', 'kali/usr/bin', 'kali/usr/sbin', 'kali/tmp'):
            (self.root / path).mkdir(parents=True)
        for name, value in [('mode', '0\n'), ('firmware', '\n'), ('wifi', '1\n'), ('type', '1\n'),
                            ('vendor/amss20.bin', 'mock'), ('app/s1/request.json', '{"channel":6}')]:
            (self.root / name).write_text(value)
        for name in ('python3', 'tshark'):
            executable = self.root / 'kali/usr/bin' / name
            executable.write_text('#!/bin/sh\nexit 99\n'); executable.chmod(0o700)
        for name in ('qca-backend.py', 'qca-scan.py', 'netlink.py', 'nl80211-constants.json', 'bender-engine.py', 'bender-worker.py', 'identity_guard.py', 'privacy_profile.py', 'privacy-session.py'):
            (self.root / name).write_text('mock helper; never executed')
        (self.root / 'bender_engine').mkdir()
        (self.root / 'bender_engine/__init__.py').write_text('# mock')
        script = (ASSETS / 'qca-runner.sh').read_text()
        substitutions = {
            '/data/local/tmp/hijacker-qca': self.posix + '/state',
            '/data/local/nhsystem/kali-arm64': self.posix + '/kali',
            '/sys/module/qca_cld3_peach_v2/parameters/con_mode': self.posix + '/mode',
            '/sys/module/qca_cld3_peach_v2/parameters/monitor_tx_ifname': self.posix + '/candidate-param',
            '/data/local/tmp/nethunter-internal-wifi.lock': self.posix + '/internal-lock',
            '/sys/module/firmware_class/parameters/path': self.posix + '/firmware',
            '/sys/class/net/wlan0/type': self.posix + '/type',
            '/data/user/0/dev.sir.benderwifi/files/qca-sessions': self.posix + '/app',
            '/data/local/tmp/benderlab/lock': self.posix + '/other-lock',
            '/data/local/tmp/benderlab/recovery/pending': self.posix + '/other-pending',
            '/vendor/firmware_mnt/image/peach': self.posix + '/vendor',
            '/odm/etc/wifi/peach': self.posix + '/odm',
        }
        for before, after in substitutions.items(): script = script.replace(before, after)
        self.script = self.root / 'runner.sh'
        self.script.write_text('#!/bin/bash\n' + MOCKS + script.partition('\n')[2], newline='\n')

    def tearDown(self):
        self.tmp.cleanup()

    def run_supervisor(self, action='capture', sid='s1', **overrides):
        env = {**os.environ, 'MOCK_ROOT': self.posix, **overrides}
        result = subprocess.run([BASH, shell_path(self.script), action, sid, self.posix + '/app/' + sid, '999999', 'all'],
                                env=env, capture_output=True, text=True, timeout=25)
        self.output = result.stdout + result.stderr
        return result.returncode

    def assert_restored(self):
        self.assertEqual('0', (self.root / 'mode').read_text().strip(), self.output)
        self.assertEqual('\n', (self.root / 'firmware').read_text(), self.output)
        self.assertEqual('1', (self.root / 'wifi').read_text().strip(), self.output)
        self.assertFalse((self.root / 'state/recovery/pending').exists(), self.output)
        self.assertFalse((self.root / 'state/lock').exists(), self.output)

    def test_prepare_preserves_radio_and_exports_separate_pending(self):
        self.assertEqual(0, self.run_supervisor('prepare'), self.output)
        self.assert_restored()
        state = json.loads((self.root / 'app/s1/radio-state.json').read_text())
        self.assertFalse(state['recovery_pending'])
        self.assertTrue(state['privacy_pending'])
        profile = json.loads((self.root / 'app/s1/privacy-state.json').read_text())
        self.assertEqual('prepared', profile['state'])

    def test_unexpected_prepare_failure_retains_profile_obligation(self):
        self.assertEqual(9, self.run_supervisor('prepare', MOCK_PRIVACY_FAIL='1'), self.output)
        self.assertTrue((self.root / 'state/bender-privacy/pending.json').exists())
        profile = json.loads((self.root / 'app/s1/privacy-state.json').read_text())
        self.assertEqual('pending', profile['state'])
        state = json.loads((self.root / 'app/s1/radio-state.json').read_text())
        self.assertTrue(state['privacy_pending'])
        self.assertFalse(state['recovery_pending'])
        self.assert_restored()

    def test_prepare_refuses_pending_radio_recovery(self):
        pending = self.root / 'state/recovery'
        pending.mkdir(parents=True)
        (pending / 'pending').write_text('retain')
        self.assertEqual(1, self.run_supervisor('prepare'), self.output)
        self.assertTrue((pending / 'pending').exists())
        self.assertFalse((self.root / 'state/bender-privacy/pending.json').exists())

    def test_privacy_restore_retains_radio_recovery_and_mode(self):
        pending = self.root / 'state/recovery'
        pending.mkdir(parents=True)
        (pending / 'pending').write_text('retain')
        profile = self.root / 'state/bender-privacy'
        profile.mkdir()
        (profile / 'pending.json').write_text('retain')
        (self.root / 'mode').write_text('4\n')
        self.assertEqual(0, self.run_supervisor('privacy_restore'), self.output)
        self.assertTrue((pending / 'pending').exists())
        self.assertEqual('4', (self.root / 'mode').read_text().strip())
        self.assertFalse((profile / 'pending.json').exists())
        state = json.loads((self.root / 'app/s1/radio-state.json').read_text())
        self.assertTrue(state['recovery_pending'])
        self.assertFalse(state['privacy_pending'])

    def test_privacy_status_is_read_only_with_pending_radio_recovery(self):
        pending = self.root / 'state/recovery'
        pending.mkdir(parents=True)
        (pending / 'pending').write_text('retain')
        self.assertEqual(0, self.run_supervisor('privacy_status'), self.output)
        state = json.loads((self.root / 'app/s1/radio-state.json').read_text())
        self.assertTrue(state['privacy_pending'])  # Reported owned hooks can outlive a journal.
        self.assertTrue((pending / 'pending').exists())
        self.assertEqual('0', (self.root / 'mode').read_text().strip())

    def test_prepare_cancel_and_owner_death_keep_durable_profile(self):
        for trigger in ('MOCK_PRIVACY_CANCEL', 'MOCK_PRIVACY_OWNER_DEATH'):
            with self.subTest(trigger=trigger):
                # A fresh session per explicit action; cancelled jobs never replay.
                sid = 'p' + str(len(trigger))
                (self.root / 'app' / sid).mkdir()
                self.assertEqual(130, self.run_supervisor('prepare', sid=sid, **{trigger:'1'}), self.output)
                self.assertTrue((self.root / 'state/bender-privacy/pending.json').exists())
                result = json.loads((self.root / 'app' / sid / 'result.json').read_text())
                self.assertTrue(result['cancelled'])
                self.assert_restored()
                (self.root / 'app' / sid / 'cancel').unlink(missing_ok=True)
                (self.root / 'owner-dead').unlink(missing_ok=True)

    def test_identity_inventory_keeps_recovery_without_radio_mutation(self):
        pending = self.root / 'state/recovery'
        pending.mkdir(parents=True)
        (pending / 'pending').write_text('retain')
        (self.root / 'mode').write_text('4\n')
        self.assertEqual(0, self.run_supervisor('identity', MOCK_HASH='unknown'), self.output)
        self.assertEqual('4', (self.root / 'mode').read_text().strip())
        self.assertTrue((pending / 'pending').exists())
        self.assertTrue((self.root / 'app/s1/identity-state.json').exists())
        state = json.loads((self.root / 'app/s1/radio-state.json').read_text())
        self.assertFalse(state['restoration_verified'])
        self.assertTrue(state['recovery_pending'])

    def test_identity_precancelled_does_not_run_worker(self):
        (self.root/'app/s1/cancel').touch()
        self.assertEqual(130,self.run_supervisor('identity'),self.output)
        self.assertFalse((self.root/'app/s1/identity-state.json').exists())
        self.assertFalse((self.root/'kali/tmp/hijacker-qca/s1').exists())
        self.assertEqual('0',(self.root/'mode').read_text().strip())

    def test_identity_cancel_and_owner_death_stop_worker_without_mutation(self):
        for trigger in ('MOCK_IDENTITY_CANCEL','MOCK_IDENTITY_OWNER_DEATH'):
            with self.subTest(trigger=trigger):
                fixture=SupervisorTests('test_identity_cancel_and_owner_death_stop_worker_without_mutation')
                fixture.setUp()
                try:
                    self.assertEqual(130,fixture.run_supervisor('identity',**{trigger:'1'}),fixture.output)
                    result=json.loads((fixture.root/'app/s1/result.json').read_text())
                    self.assertTrue(result['cancelled']);self.assertFalse(result['radio_mutated'])
                    self.assertFalse((fixture.root/'app/s1/identity-state.json').exists())
                    self.assertEqual('0',(fixture.root/'mode').read_text().strip())
                    self.assertFalse((fixture.root/'state/lock').exists())
                finally:fixture.tearDown()

    def test_analysis_workers_receive_private_runtime_path(self):
        for operation in ('analyze','capture'):
            with self.subTest(operation=operation):
                fixture=SupervisorTests('test_analysis_workers_receive_private_runtime_path')
                fixture.setUp()
                try:
                    (fixture.root/'app/s1/input.pcap').write_bytes(b'pcap fixture')
                    self.assertEqual(0,fixture.run_supervisor(operation,MOCK_CHECK_WORKER_PATH='1'),fixture.output)
                    self.assertTrue((fixture.root/'worker-path-checked').exists())
                    self.assertEqual('complete',json.loads((fixture.root/'app/s1/analysis-state.json').read_text())['status'])
                finally:fixture.tearDown()

    def test_probe_exports_root_privacy_recovery_without_a_worker_report(self):
        directory=self.root/'state/bender-privacy';directory.mkdir(parents=True)
        (directory/'pending.json').write_text('{"saved":true}')
        self.assertEqual(0,self.run_supervisor('probe'),self.output)
        self.assertTrue(json.loads((self.root/'app/s1/radio-state.json').read_text())['privacy_pending'])
        self.assertFalse((self.root/'app/s1/privacy-state.json').exists())
        self.assertTrue((directory/'pending.json').exists())

    def test_completed_capture_restores_and_exports(self):
        self.assertEqual(0, self.run_supervisor(), self.output)
        self.assert_restored()
        self.assertTrue((self.root / 'app/s1/capture.pcap').exists(), self.output)
        state = json.loads((self.root / 'app/s1/radio-state.json').read_text())
        self.assertTrue(state['managed_mode']); self.assertFalse(state['recovery_pending'])
        self.assertFalse(state['privacy_pending'])
        self.assertTrue(state['restoration_verified'])
        self.assertEqual('saved_state_restored',state['restoration_evidence'])
        self.assertIn('CSV_BEGIN', self.output)
        observations = json.loads((self.root / 'app/s1/observations.json').read_text())
        self.assertEqual(7, observations['frames'])
        self.assertIn('OBS_BEGIN', self.output)
        import base64
        encoded = self.output.split('OBS_BEGIN\n', 1)[1].split('OBS_END', 1)[0]
        self.assertEqual(observations, json.loads(base64.b64decode(encoded)))

    def test_finish_early_restores_and_analyzes_saved_capture(self):
        self.assertEqual(0,self.run_supervisor(MOCK_FINISH_SCAN='1'),self.output)
        self.assert_restored()
        self.assertTrue((self.root/'app/s1/capture.pcap').exists())
        self.assertTrue((self.root/'app/s1/observations-v1.json').exists())
        self.assertEqual('complete',json.loads((self.root/'app/s1/analysis-state.json').read_text())['status'])
        self.assertTrue(json.loads((self.root/'app/s1/result.json').read_text())['ok'])
        self.assertIn('Analyzing saved capture',self.output)
        self.assertNotIn('PHASE:Scanning',self.output)

    def test_missing_runtime_overrides_previous_ready_state(self):
        for operation in ('probe','identity','analyze','prepare','privacy_restore','privacy_status','capture'):
            with self.subTest(operation=operation):
                fixture=SupervisorTests('test_missing_runtime_overrides_previous_ready_state')
                fixture.setUp()
                try:
                    (fixture.root/'kali/usr/bin/python3').unlink()
                    self.assertNotEqual(0,fixture.run_supervisor(operation),fixture.output)
                    self.assertFalse(json.loads((fixture.root/'app/s1/runtime-state.json').read_text())['runtime_ready'])
                finally:fixture.tearDown()

    def test_analysis_failure_is_separate_from_radio_state(self):
        self.assertEqual(0, self.run_supervisor(MOCK_ANALYSIS_FAIL='1'), self.output)
        self.assert_restored()
        analysis = json.loads((self.root / 'app/s1/analysis-state.json').read_text())
        self.assertEqual('failed', analysis['status'])
        state = json.loads((self.root / 'app/s1/radio-state.json').read_text())
        self.assertFalse(state['recovery_pending'])

    def test_import_analysis_does_not_touch_radio_or_recovery(self):
        (self.root / 'app/s1/input.pcap').write_bytes(b'pcap fixture')
        (self.root / 'state/recovery').mkdir(parents=True)
        (self.root / 'state/recovery/pending').touch()
        self.assertEqual(0,self.run_supervisor('analyze', MOCK_HASH='unknown'),self.output)
        self.assertEqual('0', (self.root / 'mode').read_text().strip())
        self.assertTrue((self.root / 'state/recovery/pending').exists())
        self.assertNotIn('Restoring normal Wi-Fi',self.output)
        result=json.loads((self.root / 'app/s1/result.json').read_text())
        self.assertFalse(result['radio_mutated'])

    def test_import_decoder_failure_has_no_restore_attempt(self):
        (self.root / 'app/s1/input.pcap').write_bytes(b'pcap fixture')
        self.assertEqual(1,self.run_supervisor('analyze', MOCK_ANALYSIS_FAIL='1'),self.output)
        self.assertNotIn('Restoring normal Wi-Fi',self.output)
        state=json.loads((self.root / 'app/s1/radio-state.json').read_text())
        self.assertFalse(state['recovery_pending'])

    def test_restore_without_saved_state_reports_observation_only(self):
        self.assertEqual(0,self.run_supervisor('restore'),self.output)
        state=json.loads((self.root / 'app/s1/radio-state.json').read_text())
        result=json.loads((self.root / 'app/s1/result.json').read_text())
        self.assertFalse(state['restoration_verified'])
        self.assertFalse(result['restoration_verified'])
        self.assertEqual('managed_mode_observed_no_saved_state',state['restoration_evidence'])
        self.assertEqual(state['restoration_evidence'],result['restoration_evidence'])
        self.assertNotIn('Restoring normal Wi-Fi',self.output)

    def test_import_cancel_after_launch_retains_pending_recovery(self):
        (self.root / 'app/s1/input.pcap').write_bytes(b'pcap fixture')
        (self.root / 'state/recovery').mkdir(parents=True)
        (self.root / 'state/recovery/pending').touch()
        self.assertEqual(130,self.run_supervisor('analyze',MOCK_ANALYSIS_CANCEL='1'),self.output)
        self.assertTrue((self.root / 'state/recovery/pending').exists())
        self.assertNotIn('Restoring normal Wi-Fi',self.output)
        result=json.loads((self.root / 'app/s1/result.json').read_text())
        self.assertTrue(result['cancelled']);self.assertFalse(result['radio_mutated'])
        self.assertFalse((self.root / 'app/s1/observations-v1.json').exists())

    def test_import_owner_death_after_launch_cancels(self):
        (self.root / 'app/s1/input.pcap').write_bytes(b'pcap fixture')
        self.assertEqual(130,self.run_supervisor('analyze',MOCK_ANALYSIS_OWNER_DEATH='1'),self.output)
        result=json.loads((self.root / 'app/s1/result.json').read_text())
        self.assertTrue(result['cancelled']);self.assertFalse(result['radio_mutated'])

    def test_lab_cancel_during_validation_has_no_intent(self):
        self.assertEqual(130,self.run_supervisor('lab',MOCK_LAB_CANCEL_VALIDATION='1'),self.output)
        result=json.loads((self.root / 'app/s1/result.json').read_text())
        self.assertTrue(result['cancelled']);self.assertFalse(result['submission_may_have_occurred'])
        self.assertFalse((self.root / 'app/s1/attempt.json').exists())

    def test_lab_cancel_after_intent_is_uncertain(self):
        self.assertEqual(130,self.run_supervisor('lab',MOCK_LAB_CANCEL_INTENT='1'),self.output)
        result=json.loads((self.root / 'app/s1/result.json').read_text())
        self.assertTrue(result['cancelled']);self.assertTrue(result['submission_may_have_occurred'])
        self.assertTrue((self.root / 'app/s1/attempt.json').exists())

    def test_cancel_during_transition_restores(self):
        self.assertEqual(130, self.run_supervisor(MOCK_CANCEL='1'), self.output)
        self.assert_restored()

    def test_capture_start_failure_restores(self):
        self.assertEqual(1, self.run_supervisor(MOCK_START_FAIL='1'), self.output)
        self.assertIn('Restoring normal Wi-Fi', self.output)
        self.assert_restored()

    def test_decoder_failure_restores(self):
        self.assertEqual(1, self.run_supervisor(MOCK_SCAN_FAIL='1'), self.output)
        self.assertIn('Passive scanner failed', self.output)
        self.assert_restored()

    def test_cancel_while_scanner_runs_restores(self):
        self.assertEqual(130, self.run_supervisor(MOCK_CANCEL_SCAN='1'), self.output)
        self.assert_restored()

    def test_invalid_frequency_plan_fails_before_radio_change(self):
        self.assertEqual(1, self.run_supervisor(MOCK_PLAN_FAIL='1'), self.output)
        self.assertNotIn('Preparing passive scan', self.output)
        self.assert_restored()

    def test_failed_restore_keeps_record_then_manual_recovery_succeeds(self):
        self.assertEqual(1, self.run_supervisor(MOCK_CANCEL='1', MOCK_RESTORE_FAIL='1'), self.output)
        self.assertTrue((self.root / 'state/recovery/pending').exists(), self.output)
        # Recovery must remain available even if the normal profile check would fail.
        self.assertEqual(0, self.run_supervisor('restore', MOCK_MODEL='different-build'), self.output)
        self.assert_restored()

    def test_different_build_with_matching_driver_is_not_label_blocked(self):
        self.assertEqual(0, self.run_supervisor(MOCK_MODEL='CPH2653', MOCK_BUILD='different-build'), self.output)
        self.assert_restored()

    def test_unknown_driver_can_attempt_capture_and_restore(self):
        self.assertEqual(0, self.run_supervisor(MOCK_HASH='unknown-driver'), self.output)
        self.assert_restored()
        self.assertIn('Untested driver',self.output)
        self.assertTrue((self.root/'app/s1/result.json').exists())

    def test_unknown_driver_allows_readonly_readiness(self):
        self.assertEqual(0, self.run_supervisor('probe', MOCK_MODEL='different', MOCK_HASH='unknown-driver'), self.output)
        self.assert_restored()

    def test_unknown_driver_reaches_lab_worker_and_keeps_uncertain_result(self):
        # The mock worker records intent then returns 9, independently of hash.
        self.assertNotEqual(0,self.run_supervisor('lab',MOCK_HASH='unknown-driver'),self.output)
        self.assertTrue((self.root/'app/s1/attempt.json').exists(),self.output)
        self.assertIn('Untested driver',self.output)
        self.assert_restored()

    def test_missing_chroot_dependency_retains_readonly_inventory(self):
        (self.root/'kali/usr/bin/tshark').unlink()
        self.assertEqual(1, self.run_supervisor('probe', MOCK_BUILD='different-build'), self.output)
        inventory = (self.root/'app/s1/environment.txt').read_text()
        self.assertIn('build=different-build', inventory)
        self.assertIn('installed_driver_sha256=', inventory)
        self.assert_restored()

    def test_firmware_override_blocks_operation_before_radio_change(self):
        (self.root / 'firmware').write_text('/another-tool\n')
        self.assertEqual(1, self.run_supervisor(), self.output)
        self.assertIn('firmware search override', self.output)
        self.assertEqual('/another-tool\n', (self.root / 'firmware').read_text())
        self.assertEqual('0', (self.root / 'mode').read_text().strip())
        self.assertFalse((self.root / 'state/recovery/pending').exists())

    def test_cache_symlink_cannot_overwrite_outside_file(self):
        cache = self.root / 'kali/tmp/hijacker-qca/bin'; cache.mkdir(parents=True)
        sentinel = self.root / 'unrelated'; sentinel.write_text('preserve me')
        try: (cache / 'qca-backend.py').symlink_to(sentinel)
        except OSError: self.skipTest('Host does not allow symlinks')
        self.assertEqual(1, self.run_supervisor('probe'))
        self.assertIn('Refusing symlink', self.output)
        self.assertEqual('preserve me', sentinel.read_text())
        self.assert_restored()

    def test_nonroot_owned_path_is_not_adopted(self):
        self.assertEqual(1, self.run_supervisor('probe', MOCK_PATH_UID='1000'))
        self.assertIn('root-owned', self.output)
        self.assertEqual('0', (self.root / 'mode').read_text().strip())

    def test_permission_check_retains_every_group_and_other_write_restriction(self):
        # The real runner must accept private/read-only sharing and reject any
        # group/other write bit before touching the radio. Android shell execution
        # is a separate device regression because Bash treats leading zeros differently.
        for group in range(8):
            for other in range(8):
                mode = f'7{group}{other}'
                denied = group in (2, 3, 6, 7) or other in (2, 3, 6, 7)
                with self.subTest(mode=mode):
                    sid = 'permissions_' + mode
                    out = self.root / 'app' / sid
                    out.mkdir()
                    (out / 'request.json').write_text('{}')
                    self.assertEqual(1 if denied else 0,
                                     self.run_supervisor('probe', sid=sid, MOCK_PATH_MODE=mode), self.output)
                    self.assertEqual('0', (self.root / 'mode').read_text().strip())
                    self.assertFalse((self.root / 'state/recovery/pending').exists())

    def test_missing_or_busy_flock_refuses_before_radio_change(self):
        self.assertEqual(1, self.run_supervisor('probe', MOCK_FLOCK_FAIL='1'))
        self.assertIn('mutex is busy', self.output)
        self.assertFalse((self.root / 'state/lock').exists())
        self.assertEqual('0', (self.root / 'mode').read_text().strip())

    def test_cancelled_lab_does_not_export_probe_as_transmit_result(self):
        (self.root / 'app/s1/cancel').touch()
        self.assertEqual(130, self.run_supervisor('lab'), self.output)
        self.assertTrue(json.loads((self.root / 'app/s1/result.json').read_text())['cancelled'])
        self.assertFalse((self.root / 'app/s1/attempt.json').exists())
        self.assert_restored()

    def test_abrupt_lab_exit_exports_intent_without_stale_probe_result(self):
        self.assertEqual(9, self.run_supervisor('lab'), self.output)
        self.assertFalse((self.root / 'app/s1/result.json').exists())
        self.assertTrue((self.root / 'app/s1/attempt.json').exists())
        self.assert_restored()

    def foreign_recovery(self):
        state = self.root / 'state/recovery'
        state.mkdir(parents=True)
        for name, value in [('pending', 'retained'), ('firmware', '\n'), ('wifi', '1\n')]:
            (state / name).write_text(value)
        (self.root / 'mode').write_text('4\n')
        (self.root / 'wifi').write_text('0\n')

    def assert_foreign_radio_untouched(self):
        self.assertEqual('4', (self.root / 'mode').read_text().strip(), self.output)
        self.assertEqual('0', (self.root / 'wifi').read_text().strip(), self.output)
        self.assertEqual('retained', (self.root / 'state/recovery/pending').read_text())
        self.assertFalse((self.root / 'state/lock').exists(), self.output)
        self.assertNotIn('Restoring normal Wi-Fi', self.output)
        self.assertFalse(json.loads((self.root / 'app/s1/radio-state.json').read_text())['managed_mode'])

    def test_internal_lock_refuses_capture_without_radio_change(self):
        lock = self.root / 'internal-lock'
        lock.mkdir()
        (lock / 'state').write_text('foreign recovery')
        self.assertEqual(1, self.run_supervisor(), self.output)
        self.assertIn('Internal Wi-Fi session holds the radio', self.output)
        self.assert_restored()
        self.assertEqual('foreign recovery', (lock / 'state').read_text())
        self.assertFalse(json.loads((self.root / 'app/s1/radio-state.json').read_text())['managed_mode'])

    def test_privacy_mutations_respect_every_foreign_owner(self):
        for action in ('prepare', 'privacy_restore'):
            for marker in ('internal-lock', 'other-lock', 'other-pending', 'candidate-param'):
                with self.subTest(action=action, marker=marker):
                    fixture = SupervisorTests()
                    fixture.setUp()
                    try:
                        (fixture.root / marker).write_text('foreign state')
                        profile = fixture.root / 'state/bender-privacy'
                        profile.mkdir(parents=True)
                        (profile / 'pending.json').write_text('{"saved":true}')
                        self.assertEqual(1, fixture.run_supervisor(action), fixture.output)
                        self.assertFalse((fixture.root / 'app/s1/result.json').exists())
                        self.assertFalse((fixture.root / 'kali/tmp/hijacker-qca/s1').exists())
                        self.assertEqual('{"saved":true}', (profile / 'pending.json').read_text())
                        self.assertEqual('foreign state', (fixture.root / marker).read_text())
                        self.assertEqual('1', (fixture.root / 'wifi').read_text().strip())
                        self.assertEqual('0', (fixture.root / 'mode').read_text().strip())
                        self.assertTrue(json.loads((fixture.root / 'app/s1/radio-state.json').read_text())['privacy_pending'])
                    finally:
                        fixture.tearDown()

    def test_foreign_owner_still_allows_read_only_privacy_checks(self):
        (self.root / 'internal-lock').mkdir()
        self.assertEqual(0, self.run_supervisor('privacy_status'), self.output)
        self.assertEqual('1', (self.root / 'wifi').read_text().strip())
        self.assertEqual('0', (self.root / 'mode').read_text().strip())

    def test_restore_refuses_internal_lock_and_retains_recovery(self):
        self.foreign_recovery()
        (self.root / 'internal-lock').mkdir()
        self.assertEqual(1, self.run_supervisor('restore'), self.output)
        self.assert_foreign_radio_untouched()
        self.assertTrue((self.root / 'internal-lock').is_dir())

    def test_loaded_candidate_refuses_restore_even_without_lock(self):
        self.foreign_recovery()
        (self.root / 'candidate-param').write_text('wlan0\n')
        self.assertEqual(1, self.run_supervisor('restore'), self.output)
        self.assertIn('Experimental driver is loaded', self.output)
        self.assert_foreign_radio_untouched()
        self.assertEqual('wlan0\n', (self.root / 'candidate-param').read_text())

    def test_restore_refuses_bender_lock_and_retains_recovery(self):
        self.foreign_recovery()
        (self.root / 'other-lock').mkdir()
        self.assertEqual(1, self.run_supervisor('restore'), self.output)
        self.assertIn('Bender Lab holds the radio', self.output)
        self.assert_foreign_radio_untouched()
        self.assertTrue((self.root / 'other-lock').is_dir())

    def test_restore_refuses_bender_pending_and_retains_both_records(self):
        self.foreign_recovery()
        (self.root / 'other-pending').write_text('bender recovery')
        self.assertEqual(1, self.run_supervisor('restore'), self.output)
        self.assertIn('Bender Lab has unresolved recovery', self.output)
        self.assert_foreign_radio_untouched()
        self.assertEqual('bender recovery', (self.root / 'other-pending').read_text())


    def test_automatic_cleanup_runs_even_with_cancel_file_present(self):
        self.assertEqual(130,self.run_supervisor(MOCK_AUTO_CANCEL='1'),self.output)
        state=json.loads((self.root/'app/s1/radio-state.json').read_text())
        self.assertFalse(state['privacy_pending'])
        self.assertFalse((self.root/'state/bender-privacy/pending.json').exists())
        self.assertFalse((self.root/'app/s1/attempt.json').exists())

    def test_failed_automatic_cleanup_keeps_obligation_and_restored_radio(self):
        self.assertNotEqual(0,self.run_supervisor(MOCK_AUTO_RESTORE_FAIL='1'),self.output)
        state=json.loads((self.root/'app/s1/radio-state.json').read_text())
        self.assertTrue(state['privacy_pending'])
        self.assertTrue(state['managed_mode'])
        self.assertFalse(state['recovery_pending'])
        self.assertTrue((self.root/'state/bender-privacy/pending.json').exists())

if __name__ == '__main__':
    unittest.main()
