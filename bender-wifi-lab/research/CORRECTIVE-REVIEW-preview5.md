# Independent corrective review: preview 5 source

Date: 2026-11-15. Scope: the six findings in the preview-3 technical review and
linked critique, reproduced against preview 4, plus regressions introduced by
the corresponding fixes. Reviewed source hashes are in `source-hashes.json`.

Verdict: no remaining actionable findings in this scoped source review after
the four issues reported during review were corrected. This is not a claim that
the entire app is bug-free or that a handset/RF capability has been qualified.
The final APK still needs to contain the reviewed source/assets and complete the
parent's build and device/emulator validation.

## Review findings resolved

1. Windows-locale reads introduced UTF-8 mojibake in visible app/scanner strings.
   The affected strings were repaired and explicit UTF-8 reads adopted.
2. Typed projection discarded invalid timestamp strings before repository
   validation, turning invalid timestamps into missing/zero values. Private
   import now rejects present invalid timestamps; sharing can omit them.
3. Session IDs received different aliases in observation.session_id and
   experiment.id, breaking exported provenance joins. Both now use the same
   session namespace, including imported:bw_* origins.
4. New destructive cleanup operations ignored cancellation and owner death.
   The supervisor now checks before deletion, between files/directories, and
   before successful completion, reporting interrupted cleanup and its count
   without retrying automatically.

The initial counterexamples are retained in `ExportRelationProof.java`,
`export-relations.log`, `cleanup_cancel_proof.py`, and `cleanup-cancel.log`.
Those proof scripts assert the previous bugs; they are historical reproducers,
not current success tests. Positive rechecks are listed below.

## Checks completed independently

- Six current analyzer/security regression tests passed with the installed
  TShark and synthetic frames: checksum failures, missing/verified metadata,
  complete open/WPA/WPA2/mixed advertisements, incomplete/malformed cases,
  conflicting advertisements, and protocol/selector-kind-aware WPA ciphers.
  Log: `security-regressions.log`.
- Ten current supervisor retention tests passed in the project's disposable
  filesystem fixture: verified handoff, failed handoff retention, app/root
  deletion, orphan cleanup, foreign-data preservation, symlink/traversal
  rejection, pending recovery, absent runtime with surviving journals,
  pre-cancellation/owner death and interrupted cleanup.
  Log: `retention-regressions.log`.
- A separate Java proof against current compiled JVM classes passed session
  alias joins, imported-session joins, invalid timestamp rejection, nested
  secret aliases, and type-confused export values.
  Sources/output: `FinalProjectionProof.java`, `final-projection.log`.
- Source inspection confirmed explicit unchecked recovery UI, a root-status
  query independent of a working decoder, preserved recovery journals, and no
  operation replay during reconciliation. Retained-capture status now enters
  saved session evidence and the completion phase.
- Hash comparisons against the preview-4 APK source manifest show unchanged
  MainActivity, SecretVault, qca-backend, identity_guard, privacy_profile and
  target-policy tests. The driver/target choices, screenshots and easy encrypted
  vault behavior were not reversed. See `preserved-policy-hashes.json`.

## Limits

No product files were edited by the reviewer. No network, ADB, phone, emulator,
router, RF operation, or real capture deletion was used by this reviewer.
Python supervisor tests model shell control flow and filesystem ownership; they
do not establish Android SELinux behavior or actual radio recovery. The Java
proof does not replace Android import/export UI validation. The parent owns
full build, packaging and newly authorized handset testing.

## Follow-up: compact recovery notice and scroll-aware UI tests

Reviewed the subsequent LabApp recovery-banner layout change, workshop-list
semantic tag, and LabNavigationTest/PreviewScreensTest/NetworkWorkflowTest
scroll adjustments. The compact Row preserves the explicit unchecked state,
root-journal explanation, enabled/busy behavior, and check action. It adds no
automatic root operation or new gate. The UI tests still assert the intended
controls and outcomes; they scroll the existing lazy lists before checking
controls that may legitimately be below the viewport. Existing screenshot,
privacy/deauth-disabled, and navigation assertions remain.

No additional actionable finding in this follow-up. Of the twelve previously
hashed files, only LabApp.kt changed. source-hashes.json now covers the current
UI plus the four inspected instrumentation test files. This follow-up was source
inspection only; the parent will rerun instrumentation on the exact rebuilt APK.

Local proof files referenced above remain under validation/fixes-preview5-20261115/review/.
