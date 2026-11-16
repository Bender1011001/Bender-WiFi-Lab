# Validation — 0.1.1 research preview

Validated offline and on a dedicated Android 15/API 35 emulator on 2026-11-13.
The physical OnePlus 13 was not queried, installed to, or used for radio tests.

## Delivered build

- Package `dev.sir.benderwifi`, version `0.1.1-research` (code 2), min SDK 30, target 35.
- APK and source archive: `dist/`; SHA-256 hashes: `dist/manifest.json`.
- Debug signing is used for this personal research preview. Reproducible binary
  builds and a production release-signing process have not been established.
- Final Gradle build: assembleDebug, testDebugUnitTest, assembleDebugAndroidTest,
  lintDebug all passed. Log: `validation/review-20261113/android.log`.
- Lint: **0 errors, 16 warnings, 1 informational finding**. Version-update and
  other remaining lint notices are retained in `app/build/reports/lint-results-debug.html`.
- The APK source manifest matches 66 current build/test inputs. Its 14 executable
  assets are compared byte-for-byte with current runtime/engine source during
  packaging. Android's packaged manifest has no INTERNET,
  READ_PHONE_STATE or privileged phone-identifier permission.

## Tests and evidence

| Check | Result | Evidence |
|---|---|---|
| Protocol engine | 25 passed, no skips | `validation/review-20261113/engine.log` |
| Runtime, profile, guard and research | 148 passed, no skips | `validation/review-20261113/runtime.log` |
| Android JVM | 26 passed, no skips | `app/build/test-results/testDebugUnitTest/` |
| Room and data-boundary instrumentation | 10 passed | `validation/review-20261113/emulator-data-ui.log` |
| Final navigation and preparation UI | 3 passed | `validation/review-20261113/emulator-data-ui.log` |
| Final visual/secure-window instrumentation | 1 passed | `validation/review-20261113/emulator-data-ui.log` |
| Actual Keystore round trip and tamper rejection | 1 passed after emulator credential unlock | `validation/review-20261113/emulator-crypto.log` |
| Retained capture replay | 1,947 frames across 3 captures | `validation/review-20261113/replay.json`; prior summary `engine/OFFLINE_VALIDATION.json` |
| Firmware artifact/predicate audit | Static checks passed | `research/firmware-audit-result.json` |

These are **214 distinct passing tests**, not handset or RF experiments. There are
no final failures or skips. The previous preview's 199 passes remain historical
evidence. A Gradle Kotlin DSL import error during the review build was corrected;
the failed attempt is retained in `android-attempt1.log`. The cold emulator's
credential-encrypted storage initially prevented a UI dump; the unlock helper now
uses shell-accessible temporary storage and the actual crypto test passed afterwards.

The emulator is `BenderWifiLabTest`, serial `emulator-5586`. UI tests triggered no
radio or connectivity mutations. The preview test only disables FLAG_SECURE inside
a debug emulator with an empty inventory and vault, then restores and verifies it
in `finally`. Production activity code always sets FLAG_SECURE. Final screenshots
are `validation/review-20261113/radar.png` and `workshop.png`; they were visually reviewed.

## What the tests establish

- Mixed PSK/SAE advertisements survive decoding. Incomplete RSN data cannot
  establish definitive SAE/PMF summaries. Interleaved handshake grouping, missing
  data, structured WPS attributes and decoded link evidence are exercised.
- Runtime tests exercise the actual supervisor control flow in a mocked filesystem,
  including owner loss, cancellation, competing locks, uncertain submission and
  separate saved-state recovery. They do not execute a physical radio.
- An uncertain submission also blocks another app job for the same target and
  capture. A new targeted capture is required; choosing a new job ID cannot bypass
  this protection.
- The privacy profile journals before mutation, verifies its own first-position
  IPv4/IPv6 filter chains, preserves unrelated rules, rolls back failures, and retains
  a restore obligation when cleanup is incomplete. Existing names or malformed facts
  block. Tests simulate Android settings, firewall commands, interruption and recovery.
- Identity inspection is local and bounded. It checks Android context, cellular and
  Bluetooth settings, the current MAC, interfaces, addresses, routes and any verified
  ordinary-IP egress lock. No IMEI/SIM identifiers or public-IP requests are collected.
- Imports are bounded by size, nesting and node count; known credential/key fields
  and nested JSON strings are sanitized before persistence. History is retained and
  imported timestamps cannot replace a live capture's authorization provenance.
  Committed decoder output is ingested on reconciliation without radio replay;
  one atomic Room transaction per session makes repeated ingestion idempotent.
- The review regressions cover all four foreign-owner markers for both privacy
  mutations, real TShark ToDS/FromDS and nested-BSSID PCAPs, malformed timestamps,
  process-death artifact ingestion, delayed vault decrypts, and service completion
  ordering. Lifecycle unit tests establish application ordering; Android/firmware
  behavior during actual root sessions still requires handset qualification.
- Actual Android Keystore testing establishes authenticated decryption and rejection
  of changed AAD/record ID, ciphertext, IV and wrapped key. A correct decrypt before
  and after each failed tamper case distinguishes integrity rejection from expired auth.

## Remaining qualification

The new APK's root prompts, SELinux file access, chroot path, OnePlus shell commands,
dual-stack firewall behavior, radio lifecycle, restoration, battery/thermal behavior
and independent RF/client outcomes remain **unqualified on the handset**. The app
labels these capabilities experimental and blocks absent evidence; emulator success
does not establish handset compatibility or anonymity.

The historical directed-request evidence is V61 on the recorded stock driver/build,
with router-correlated client effects. It does not qualify this new app. Firmware
flag rejection is established statically; initialization, loaded-context state and
modified-image acceptance remain unresolved. No firmware patch was made or loaded.

Live WPS enrollment/recovery, SoftAP/evil-twin, availability stress, general injection,
PMF bypass and MLO exploit modules remain research catalog entries. The WPS engine
is a transcript analyzer, not a password recovery implementation. The isolation tool
establishes controlled nonce delivery only, not a particular forwarding vulnerability.

Roku's retained capture includes nested WPS advertisements. The earlier claim of no
WPS advertisements was corrected; no enrollment exchange or recovered secret exists
in these captures. Private captures and firmware are not packaged in the app/archive.

The sibling `hijacker-qca` checkout retains the same 17 pre-existing modified files.
Its source, existing APKs and captured evidence were not replaced by this project.
