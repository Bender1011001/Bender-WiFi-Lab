# Validation - 0.2.0-preview5

Completed local build, synthetic protocol/supervisor checks, independent review
and disposable Android 15/API 35 emulator validation on 2026-11-15. No internet,
physical radio experiment or real capture deletion was performed. The user
additionally authorized phone testing; the physical ADB device disconnected
before handset validation and remained unavailable. No phone install or live
recovery/receive claim is made. Earlier results are preserved in
[VALIDATION-0.2.0-preview4.md](VALIDATION-0.2.0-preview4.md).

## Exact artifact

- Package dev.sir.benderwifi, version 0.2.0-preview5, code 7; min SDK 30/target 35.
- APK: 210,411,132 bytes; SHA-256 `b6fd9618a7c016b249c3a931a4751750126e23ffbe7280f3b497f9a62c9a5ced`.
- Debug signed using the existing key; previous APKs remain intact.
- All 95 source/build/test inputs match the APK manifest. All 16 runtime source
  assets and the runtime manifest/image match (18 checked assets).
- Offline assembleDebug, assembleDebugAndroidTest, testDebugUnitTest and lintDebug
  passed. Lint: 0 errors, 5 warnings, 1 information.

## Final checks

Evidence paths below are under validation/fixes-preview5-20261115/ unless stated.

| Check | Result | Evidence |
| --- | --- | --- |
| Protocol engine with PC TShark 4.6.6 | 31 passed | engine.log |
| Runtime, supervisor, installer, privacy and channel checks | 181 passed | runtime.log |
| Android JVM | 45 passed | app/build/test-results/testDebugUnitTest/ |
| Exact-APK Android data/UI/current-vault tests | 32 passed | emulator-data-ui.log |
| Exact-APK legacy authenticated vault migration | 1 passed | emulator-crypto.log |
| Bundled ARM64 TShark 4.0.17 engine platform repeat | 31 passed, freshly rerun | arm64-engine.log |
| Linux installer platform repeat | Prior 17 passes retained with unchanged input hashes | retained-checks.json, linux-installer.log |
| Independent corrective review | No remaining actionable findings in scope | review/REVIEW.md, review/source-hashes.json |

290 distinct final tests passed, no failures or skips. Platform/reviewer repeats
are not additional distinct tests. APK identity is recorded in tested-build.json.
radar.png and workshop.png were collected from that APK and visually inspected.

## Six corrections and regression coverage

Explicit failed FCS metadata no longer supplies AP/client/handshake summaries.
Raw captures remain intact until explicit deletion. Counts distinguish verified,
not flagged, unknown and excluded frames. Real-decoder fixtures cover complete
open, WPA, WPA2, mixed PSK/SAE and malformed/incomplete advertisements; security
variants preserve protocol/authentication/ciphers/PMF/quality. Legacy WPA CCMP
selectors now decode in their correct protocol and selector-kind namespace.

New observation imports project typed fields before Room persistence. Export
schema 2 reprojects older records too, excluding unknown aliases and arbitrary
text and preserving observation/experiment session joins. A real in-memory Room
Android test checks both new imports and a synthetic pre-upgrade row.

The root supervisor removes raw duplicates only after byte-matched handoff;
failed handoffs retain evidence and report retention. Explicit deletion/cleanup
covers scoped Bender raw files, preserves recovery/intent/summary data, rejects
unsafe paths, handles owner death/cancellation and reports partial completion.
These shell tests use a disposable mocked filesystem; actual Android root/SELinux
retention behavior is not yet qualified on the phone.

Startup explicitly shows recovery state as unchecked. A root-journal query does
not require the decoder or Wi-Fi module. Saved app state cannot establish current
root state; no interrupted operation is replayed. Source/Android UI/mock supervisor
checks cover this; physical app-data clearing was not performed.

## Review and preserved policy

The reviewer independently exercised six protocol cases, ten retention cases and
a JVM projection proof. Four implementation regressions were found and fixed:
UTF-8 text corruption, invalid timestamp acceptance, broken exported session
joins and cleanup cancellation. A final compact-banner/scroll-test follow-up was
also reviewed. Final hashes cover 16 reviewed source/test files.

The preview4 choices remain: compatible unpinned drivers; no target age/provenance
or ownership-checkbox gate; screenshots; easy encrypted reveal/copy with background
hiding; preserved legacy vault keys. Root/privacy/channel checks, serialization,
cooldown and no automatic transmission replay remain. Six policy-file hashes
match preview4 in review/preserved-policy-hashes.json. Other driver recovery and
rootless operation remain unproven.

## Preserved failed runs and limits

pre-ui-adjustment-emulator-data-ui.log records three offscreen-navigation test
failures for candidate 27fb04ec2d382c82aaa429479e27a38dc4ce909aecdc02c23ad68b513ddb169c.
The notice was compacted and tests now scroll to controls; all final UI tests pass.
pre-unlock-emulator-crypto.log records the missing emulator unlock precondition.
After an actual PIN unlock the unchanged legacy-vault test passed. These failures
were not suppressed or converted to skips. Earlier build/test logs are retained.

No RF experiment, handset receive coverage, client disconnect, negotiated PMF or
PMF enforcement is inferred from these checks. Historical internal-radio research
remains separate from qualification of this exact APK.
