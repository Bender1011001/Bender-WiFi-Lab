# Validation - 0.3.0-bluetooth-preview1

2026-11-15: offline Bluetooth implementation and validation. No physical phone,
router, bulb, TV, live Bluetooth radio or SSH setup was used during this goal.
The new APK has not been installed on the handset. Earlier OnePlus observations
remain historical evidence, not qualification of this Bluetooth implementation.

## Exact build

- Package `dev.sir.benderwifi`; version `0.3.0-bluetooth-preview1`; code 9.
- APK size: 210,573,722 bytes.
- SHA-256: `ab36dd07c163a9a7526e2adb962f10ab1474eee8f615fe71392d5af2a4d0d5f8`.
- Existing Android debug signing key; APK Signature Scheme v2 verification passed.
- APK matches 108 source/test/build input hashes and 18 packaged executable assets,
  including the unchanged ARM64 runtime. This is source correspondence, not a
  reproducible-build or public release claim.
- Delivery names: `dist/Bender-WiFi-Lab-0.3.0-bluetooth-preview1.apk` and matching
  `-source.zip`; hashes and retained-check provenance are in the versioned manifest.

## Final checks

Evidence directory: `validation/bluetooth-preview1-20261115/`.

| Check | Result | Evidence |
| --- | --- | --- |
| Host protocol engine | 32 passed | `engine.log` |
| Runtime/supervisor/privacy/channel/installer regressions | 181 passed | `runtime.log` |
| Android JVM tests | 66 passed, including 21 new Bluetooth tests | Gradle XML reports and `android-delivery.log` |
| Exact-APK emulator data/UI/current vault | 44 passed, including 11 Bluetooth checks | `emulator-data-ui.log` and `.json` |
| Exact-APK legacy vault migration after real emulator credential unlock | 1 passed | `emulator-crypto.log` and `.json` |
| Offline build and lint | Passed; 0 errors, 6 warnings | `android.log`, `android-delivery.log`, lint report |
| Signature | v2 verification passed | `apk-signature-delivery.log` |
| Prior ARM64 engine repeat | 32 passed, retained without rerun | `arm64-engine.log`, `retained-checks.json` |
| Prior Linux installer/mount checks | 17 passed, retained without rerun | `linux-installer.log`, `retained-checks.json` |

**324 distinct checks passed in the final host/JVM/emulator set**, with no failures
or skips. The retained platform repeats are not counted again. Ten exact unchanged
input hashes and byte-identical prior logs justify retaining those platform checks.
The disposable emulator was QEMU/API35, explicitly addressed as `emulator-5586`.
It used synthetic devices and a fake BLE transport; it was stopped after validation.

Lint retains the prior target-SDK, backup, application-context and storage-space
warnings. The additional warning says `neverForLocation` is ignored below API31;
Android11 uses its separate location-permission path. No lint errors were waived.
The BLE permission constants use an SDK-parameter branch, with a local documented
InlinedApi suppression because runtime callers pass `Build.VERSION.SDK_INT`.

## Behavior exercised

- Permission-denied and unsupported-hardware screens, actual activity entry/return,
  discovery-to-dossier workflow using synthetic observations, saved notes, metadata
  inspection, baseline selection and the full comparison dialog.
- Room reopen/persistence, separate same-name addresses, per-device encounter and
  inspection history, older sightings, duplicate-finalization rejection, interrupted
  operation reconciliation and evidence export with aliases and explicit size limits.
- Cancellation before dispatch, cancellation during a scan, late callbacks, capacity
  limits, stale timestamps, malformed addresses, repeated starts, scan failures,
  connection deadlines, failed initial/final saves and reservation release.
- Both directions of competing Wi-Fi/Bluetooth operations. A foreign owner cannot
  release another operation's reservation. Existing Wi-Fi UI, data, recovery,
  encrypted vault and runtime asset checks remain in the final suite.
- Export omits names, addresses, raw advertisement data, notes, unknown fields and
  arbitrary errors. It reads one payload at a time, bounds output to 8 MiB/1,000
  operations and reports included/available counts and any reached limit.

Rendered empty, synthetic dossier and synthetic comparison screenshots are under
`screenshots/`. They were visually inspected for readable controls and layout;
they are not handset photographs or live Bluetooth findings. Final review also
made long comparisons scrollable, included before/after property values, and
preserved edits made while a note save is pending. No independent subagent review
was requested or performed for this Bluetooth pass.

## Preservation and limits

Five prior APK input files changed: Gradle version, manifest, the Wi-Fi header entry,
controller reservation and a competing-operation regression assertion. The other
90 preview6 input hashes match; 13 Bluetooth source/test files are additional.
The Wi-Fi data schema, vault, runtime, firmware research and accepted preview4
driver/target/screenshot/credential policies remain intact. Prior preview artifacts
and the preview6 report are retained. The delivery audit checks archived source,
documentation, hashes, ZIP integrity and previous preview4/5/6 artifacts.

BLE discovery and GATT interoperability on the actual OnePlus remain untested.
Android scanning may transmit scan requests and filter some beacon types. GATT
metadata may be cached. Bond state and characteristic properties do not establish
authorization or encryption. The stack may present a pairing prompt during
discovery; cancelling the app operation does not undo a separately approved bond.
No characteristic values, credentials, HID input, firmware modifications, CVE
exploitation, network pivot or target vulnerability were demonstrated.

Phone qualification and model-specific experiments are in
`research/bluetooth-20261115/ASSESSMENT-PRIORITIES.md`. Exact bulb/TV models and
firmware are still needed before choosing vulnerability tests.

## Earlier diagnostic results

Intermediate logs are preserved. The first wrapper call used a Windows batch path
form that did not execute Gradle. The first compilation run caught a JUnit method
with a non-void inferred return; the next pass caught the missing paired coarse
location declaration for the Android11 path. Both were corrected. One final
emulator attempt stopped before instrumentation because the local ADB server was
temporarily unavailable; an explicit emulator-only connectivity probe succeeded,
and the complete final suite was then run successfully. No phone/server reset was
performed. Earlier successful intermediate APK results are labelled separately and
are not substituted for the final artifact's results.
