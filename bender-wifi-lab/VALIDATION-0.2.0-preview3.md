# Validation - 0.2.0-preview3

Validated offline and on a disposable Android 15/API 35 emulator on 2026-11-15.
No physical handset, router, RF operation, network download or runtime-image
update was used. The previous delivery record is retained in
[VALIDATION-0.2.0-preview2.md](VALIDATION-0.2.0-preview2.md).

## Delivered build

- Package `dev.sir.benderwifi`, version `0.2.0-preview3` (code 5), min SDK 30, target 35.
- APK and matching source ZIP are in `dist/`; versioned manifests record hashes.
  Earlier artifacts are preserved. Personal preview signed with the existing
  Android debug key; production signing and reproducible builds are not claimed.
- Offline Gradle assembleDebug, assembleDebugAndroidTest, testDebugUnitTest and
  lintDebug passed. Lint: 0 errors, 5 warnings and 1 information (unchanged counts).
- All 87 source/build/test hashes in the APK match current files. All 16 packaged
  runtime source assets match byte-for-byte; the runtime manifest and image also
  pass verification (18 checked assets total).
- APK size: 210,319,144 bytes. SHA-256:
  `a4613f4d55f9358b10371ffee0f184cfd28be4887cbd9283394bca5ba7e2cb82`.

## Final checks

Evidence paths below are relative to `validation/apk-loop-20261115/` unless noted.

| Check | Result | Evidence |
|---|---|---|
| Protocol engine on Windows/TShark | 25 passed | `engine.log` |
| Runtime, guards, supervisor, installer and mounts | 171 passed | `runtime.log` |
| Android JVM | 38 passed | `app/build/test-results/testDebugUnitTest/` |
| Emulator data, migration, UI, password input and asset staging | 27 passed | `emulator-data-ui.log` |
| Authenticated Keystore roundtrip and four tamper cases | 1 passed | `emulator-crypto.log` |
| ARM64 engine under QEMU using bundled Python/TShark | 25 repeated checks passed | `arm64-engine.log` |
| Linux installer/mount control flow with real flock | 17 repeated checks passed | `linux-installer.log` |
| Actual ARM64 worker old/fixed PATH comparison | All four expected outcomes | `arm64-worker-proof.json` |
| Native notification permission/settings workflow | Passed | `notification-ui.json`, `notification-ui-final.log` |
| Build and lint | Passed | `android.log` |
| Independent final APK review | No actionable findings in reviewed scope | `round5.md` |

The primary suites contain **262 distinct tests**, with no final failures or
skips. Repeated platform checks and the worker control cases are listed separately;
they are not RF experiments. `tested-build.json` binds the final emulator results
to the APK SHA-256. The final Kotlin-only cleanup change did not alter the runtime
sources covered by the complete 171-test runtime run.

## What changed and what the checks establish

[REVIEW.md](REVIEW.md) maps six product findings and a test-fixture correction
across five review rounds. The independent reviewer inspected the exact APK,
matching source and saved validation evidence. The final verdict is bounded to
that scope and artifact, not a guarantee of no other bugs.

- JVM and supervisor regressions preserve root privacy-restoration obligations
  even when app-local history is missing, and exercise notification permission,
  denial and blocked-channel decisions. Identity cancellation and owner death
  reach the supervised worker rather than silently completing successfully.
- The actual packaged worker, ARM64 Python, engine and TShark were run in the
  prepared runtime using an empty synthetic PCAP. Both analysis entry points fail
  with Android PATH and succeed with the supplied Linux PATH. No wireless interface
  was accessed. Mock dispatch regressions also check the explicit worker PATH.
- The exact APK's import-cancellation regression verifies that cancellation closes
  a blocked input stream, preserves exclusive ownership through cleanup and removes
  the temporary copy before idle is visible. A deletion failure is disclosed.
- The password-field instrumentation checks real Android editor flags without
  typing a secret. Keystore testing follows an actual emulator credential unlock
  and rejects altered record IDs, ciphertext, IVs and wrapped keys.
- Native notification denial and grant were exercised on an earlier candidate
  with the same notification implementation. The final APK shows enabled status,
  opens Android app notification settings and refreshes after returning. The
  helper was corrected to scroll to the caption and accept API 35's activity-dump
  field name. Those helper assumptions were not app defects.
- Existing production workflow, recovery, migration, network links, export and
  bundled-image staging checks remain passing. Final Radar and Workshop captures
  were visually inspected. The actual phone's root/SELinux behavior is not modeled
  by the emulator or command mocks.

Failed first runs remain available: `runtime-first.log` and
`supervisor-round1.log` record the corrected test-fixture parent-directory error;
`emulator-import-cleanup-race.log` and `emulator-round4-failure.log` preserve the
actual import-cleanup race. Earlier review reports and candidate build hashes
remain alongside the final evidence.

## Unchanged runtime and remaining handset qualification

The pinned Debian ARM64 image is unchanged: 153 packages, 150,626,800 compressed
bytes and 457,993,064 unpacked regular-file bytes. SHA-256:
`e9d161353e0bdd013530234513c895a9886e42f4f2b08e6b2f4841589e0693d1`.
Exact packages, repository metadata and licenses are preserved in the runtime;
see `runtime-bundle/manifest.json` and [tools/BUILD-RUNTIME.md](tools/BUILD-RUNTIME.md).
The earlier 22-field scanner compatibility result uses this same TShark build:
`validation/product-20261114/runtime-decoder-fields.json`.

The exact APK still needs OnePlus qualification: root installation and SELinux,
mount teardown, scan/capture/cancel/process-death restoration, private PCAP export
and bounded owned-network transmission with independent outcome evidence.
Historical driver acceptance, acknowledgement and client effects remain separate
results. Passive handshake capture needs an owned client reconnect after Listening
starts; simultaneous monitor capture and managed transmission remains unproven.
No new WPS recovery, PMF bypass, evil-twin or general injection capability is
established by this offline review.
