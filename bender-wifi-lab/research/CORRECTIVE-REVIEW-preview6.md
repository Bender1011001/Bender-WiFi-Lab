# Independent preview 6 follow-up review

Date: 2026-11-15. Verdict: no remaining actionable findings in the scoped changes.
The review covered navigation-bar overlap, shell line endings, decoder temporary
output limits, and truthful handset-validation packaging. No ADB, device,
emulator, network, build, production edits, or real cleanup was performed.

## Navigation and packaged shell fixes

The bottom navigation now applies navigationBarsPadding, gives the five tabs
equal width, and uses 11sp single-line labels with reduced horizontal padding.
Navigation targets, vault reveal locking on tab changes, and existing recovery
controls are unchanged. Actual Android 16 layout remains a handset acceptance
check, not a claim made by this source review.

All 593 CR bytes were removed from runtime/qca-runner.sh. Its bytes are identical
to preview 5 after CRLF-to-LF normalization; no shell logic changed. All three
runtime shell sources contain zero CR bytes. The new .gitattributes specifies
LF for shell files. Asset staging rejects CR bytes, and the shell sources are
task inputs. RuntimePackageTest adds an actual packaged-asset check using
/system/bin/sh -n; this is parse-only and does not run root or radio operations.
Version code 8 / 0.2.0-preview6 is consistent with the intended release.

The initial comparison covered 89 code/build/test archive entries, with four
expected navigation, shell, staging/version, and instrumentation changes.
That snapshot is in comparison.json and preview6-source.diff. The subsequent
analyzer, analyzer-test, and packaging-script changes are compared separately
in analysis-release-followup.diff (line endings normalized for readable diffs).

## Decoder bounds and corrected completion race

The analyzer now permits 512 MiB of temporary PDML output. This is a temporary-file
output allowance, not a fixed RAM allocation or a hard resident-memory ceiling.
The streaming parser clears packets as they are processed, although an individual
packet is built before its field-count check. Existing shared 120-second,
100,000-packet, 12,000-field and 1 MiB diagnostic-output limits remain. No decoder
field filter or changes to evidence interpretation were introduced. Temporary
decode files are removed by the context manager, and raw captures are retained.

Review found that an immediately finished decoder could bypass the diagnostic
size check between polls. A synthetic 1,048,577-byte stderr reproduction succeeded
before the fix. That historical proof is retained in decoder_limit_proof.py and
decoder-limit.log; it asserts the old behavior and is not a passing final test.

The final code rechecks both closed output files after decoder completion and
before parsing. Independently reran the new
test_finished_decoder_cannot_bypass_output_limits_between_polls regression:
both buffered oversized XML and oversized diagnostic subcases pass, without
killing an already completed process. Evidence: decoder-limit-fixed.log.
The new cap's actual handset performance remains the parent's live-validation
check; this review did not read the private capture or reproduce its identifiers.

## Handset report packaging

The optional --handset-report path requires completed status, the current APK's
SHA-256, and an actual Boolean radio_tested field before publication. The manifest
then records handset_tested and radio_tested_this_pass separately and preserves
the supplied report. Omitting it leaves both flags false.

Six independent bounded packaging cases passed: omitted report, completed report
without radio, completed report with radio, incomplete report rejection, wrong
APK rejection, and non-Boolean radio-scope rejection. The fixture uses dummy APK,
source and test inputs only under this review directory, isolating the new
metadata gate. It does not certify the real APK, source verification, or handset
results. Evidence: packaging_report_proof.py and packaging-report-final.log.

## Prior fixes and preferences

ObservationSchema, ShareRedactor, SecretVault, MainActivity, qca-backend,
identity_guard, privacy_profile, RadioController and RadioAssessment remain
byte-identical to preview 5. The analyzer's only changes are the output allowance,
error specificity and final size checks; the prior bad-FCS exclusion, security
labels, cipher interpretation and observation behavior remain intact. Shell
normalization preserves scoped capture cleanup, cancellation, restoration and
no-replay behavior. The navigation change preserves the recovery notice.

No exact driver pin, target-evidence restriction, screenshot restriction or extra
vault authentication was introduced. preservation-checks.json records these
comparisons. source-hashes.json identifies the 17 changed or preservation-critical
files reviewed; source-hashes-before-analysis.json preserves the preceding
15-file snapshot. The final APK should match the reviewed source. Parent-owned
build, emulator and handset checks remain separate evidence.

Raw review proofs are retained under validation/phone-preview6-20261115/review/ in the private workspace.
