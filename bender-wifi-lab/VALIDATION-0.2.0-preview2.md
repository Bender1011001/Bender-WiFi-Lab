# Validation — 0.2.0-preview2

Validated offline and on a disposable Android 15/API 35 emulator on 2026-11-14.
The user explicitly requested offline work. No physical-handset installation,
radio transmission, router change, network download or runtime image update was
performed. The earlier delivery record is retained in
[VALIDATION-0.2.0-preview1.md](VALIDATION-0.2.0-preview1.md).

## Delivered build

- Package `dev.sir.benderwifi`, version `0.2.0-preview2` (code 4), min SDK 30, target 35.
- APK and source archive are in `dist/`; exact hashes are in `dist/manifest.json`.
  Previous APKs and versioned manifests remain intact.
- Personal preview signed with the existing Android debug key. Production signing
  and byte-for-byte reproducible builds are not claimed.
- Gradle ran offline: assembleDebug, assembleDebugAndroidTest, testDebugUnitTest
  and lintDebug passed. Lint: 0 errors, 5 warnings, 1 information, unchanged counts.
- APK contains hashes of 84 source/build/test inputs. Packaging compares 16
  runtime/engine assets byte-for-byte and verifies the runtime manifest and image
  (18 checked assets total).
- APK size: 210,283,920 bytes. SHA-256:
  `258291116d4a4474fd1f11ffdb1cebcb98fbbed9ccfb0ca14fdf33973ac2c5ba`.

## Final checks

Paths below are relative to `validation/review-product-20261114/` unless specified.

| Check | Result | Evidence |
|---|---|---|
| Protocol engine on Windows/TShark | 25 passed | `engine.log` |
| Runtime, guards, supervisor, installer and mounts | 167 passed | `runtime.log` |
| Android JVM | 35 passed | `app/build/test-results/testDebugUnitTest/` |
| Emulator data, migration, UI and bundled asset staging | 26 passed | `emulator-data-ui.log` |
| Android Keystore roundtrip and four tamper cases | 1 passed | `emulator-crypto.log` |
| ARM64 engine under QEMU using bundled Python/TShark | 25 repeated checks passed | `arm64-engine.log` |
| Linux installer/mount control flow with real flock | 17 repeated checks passed | `linux-installer.log` |
| Build and lint | Passed | `android.log` |
| Independent final review | All three clear in reviewed scope | `installer-round2.md`, `workflow-round2.md`, `state-round2.md` |

The primary suites contain **254 distinct tests**, with no final failures or skips.
Repeated platform checks are not extra distinct tests or RF experiments.
`tested-build.json` binds emulator results to the APK SHA-256.

## What the checks establish

[REVIEW.md](REVIEW.md) maps all 13 first-pass findings and the follow-up corrections
to their delivered changes. The final review has no remaining actionable findings
within the installer, workflow and state-recovery scope. It does not establish an
absence of other bugs.

- Mocked installer tests reproduce failure reporting on an unwritable destination,
  partial unmount cleanup, preservation of a preexisting stage, verified orphan
  mount adoption and retained unknown/busy mounts. Linux repeats use real flock.
- Supervisor tests finish a capture early, restore, retain the PCAP and complete
  analysis; stale scanning output is suppressed. Missing Python invalidates runtime
  readiness for probe, identity, analyze, preparation, privacy restore/status and
  capture. No test invokes a real wireless interface.
- JVM regressions cover uncertain submission after intent or missing results,
  stale readiness, targeted-capture UI prerequisites, Workshop routing and cancelled
  import copying. The existing protocol/security suites remain passing.
- Emulator recovery tests reconcile terminal files into an old running database
  row, backfill legacy target links, retain richer evidence and original timestamps,
  and link client-only observations to the AP workspace. A blocked import stream
  reserves the controller; competing operations cannot start and cancellation
  closes the stream and removes its temporary file.
- Compose tests exercise the production network workflow with isolated synthetic
  data, distinct Finish/Cancel callbacks, runtime-failure setup controls, private
  PCAP export and export visibility after controller release without replacing the
  lazy-list row. Empty Radar/Workshop screenshots were visually inspected.
- Instrumentation stages and verifies the full bundled image from the APK. The
  Keystore test follows an actual emulator credential unlock and verifies both
  correct decryption and rejection of changed record IDs, ciphertext, IVs and
  wrapped keys. All final instrumentation checks ran without skips.

`runtime-first.log` retains the initial new-test assertion error (`completed` vs
the established `complete` analysis state). The corrected targeted test and final
full runtime run pass. Initial build/emulator logs remain as separate evidence.

## Unchanged runtime and live limits

The pinned Debian ARM64 image is unchanged from preview 1: 153 packages,
150,626,800 compressed bytes and 457,993,064 unpacked regular-file bytes. SHA-256:
`e9d161353e0bdd013530234513c895a9886e42f4f2b08e6b2f4841589e0693d1`.
Exact packages, repository metadata and licenses are preserved in the runtime;
see `runtime-bundle/manifest.json` and [tools/BUILD-RUNTIME.md](tools/BUILD-RUNTIME.md).
All 22 scanner fields were previously checked against this same TShark build;
that retained result is `validation/product-20261114/runtime-decoder-fields.json`.

The exact APK still needs OnePlus qualification: root installation and SELinux,
mount teardown, scan/capture/cancel/process-death restoration, private PCAP export,
and bounded owned-network transmission with independent outcome evidence. Historical
driver acceptance, ACK and client-effect observations remain separate results.
Passive handshake capture requires a client reconnect after Listening starts;
simultaneous monitor capture and managed transmission remains unproven. No new
WPS recovery, PMF bypass, evil-twin or general injection capability is established.
