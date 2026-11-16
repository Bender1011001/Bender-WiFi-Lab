# Bluetooth preview checkpoint

Goal set 2026-11-15: deliver an offline-tested Bluetooth assessment preview.
The user is away; physical phone, SSH, live radios and targets are out of scope.

## Decisions

- Keep preview6 Wi-Fi implementation and accepted preview4 policies intact.
- Separate native Bluetooth activity, reached from the Wi-Fi header. No root or
  bundled Linux runtime required for Android BLE discovery/service inspection.
- Explicit foreground operations only. Stop on leaving the screen/background;
  cancellation closes resources; process death never restarts a connection.
- Serialize with the existing Wi-Fi controller. Saved Wi-Fi privacy preparation
  must be restored before starting Bluetooth; do not switch radios automatically.
- Room stores Bluetooth encounters, notes and operation evidence separately from
  the existing Wi-Fi database. Observed addresses are not physical identities.
- GATT inspection discovers service/characteristic/descriptor metadata only.
  Properties and bonding state do not prove authorization or encryption.
- Save comparison snapshots and a research catalog. Model-specific exploit work,
  HID input, writes, credential reads, fuzzing and firmware changes are deferred.

## Evidence

- Existing phone feasibility report: `FINDINGS.md`; not repeated here.
- Existing exact-APK Wi-Fi delivery: `../../validation/phone-preview6-20261115/`.
- Primary Android BLE, Nordic nRF Connect and Espressif provisioning documentation
  consulted before offline-only steering. Research brief will retain those links.

## Current work

Implemented native BLE transport/coordinator, Room inventory/encounters/notes,
GATT snapshots/comparisons, permission and settings flow, history/export/research
screens, and the Wi-Fi controller reservation. Version code9,
`0.3.0-bluetooth-preview1`. Bluetooth-off/background/cancellation close the
operation; no automatic restart. New Bluetooth database leaves Wi-Fi schema intact.

Validation: `../../validation/bluetooth-preview1-20261115/`. Final host engine32,
runtime181, JVM66 and emulator45 checks pass: 324 total, no failures or skips.
Build/lint pass (0 errors,6 warnings); source correspondence covers108 inputs and
18 packaged assets. Eleven final emulator checks cover Bluetooth, including the
full comparison UI and bounded metadata export. The other34 retain Wi-Fi/vault
coverage. Screenshots are synthetic or the empty emulator screen, visually checked.
The disposable target was explicitly `emulator-5586` (QEMU/API35) and is stopped.
Runner: `run_emulator_tests.py`; exact results: `emulator-data-ui.json` and
`emulator-crypto.json`; final Gradle log: `android-delivery.log` (`android.log` copy).

Final APK SHA256: ab36dd07c163a9a7526e2adb962f10ab1474eee8f615fe71392d5af2a4d0d5f8;
210573722 bytes, code9, existing debug signer verified with APK v2. Source/ZIP,
document and prior-artifact verification is recorded in `delivery-audit.json`.

ARM64 engine32 and Linux installer17 checks are retained, with ten unchanged input
hashes and log equality verified by `retain_platform_checks.py`. No ARM64 runtime
or physical phone test was repeated. Exactly five prior APK input files changed;
the other90 preview6 input hashes match. New Bluetooth files are additional.

Delivery includes the APK, matching source ZIP, versioned manifest, VALIDATION.md
and ASSESSMENT-PRIORITIES.md. Final review fixed long comparison scrolling,
before/after property details, preservation of edits during note saves, and
bounded exports. Earlier diagnostic failures and successful intermediate APKs
are retained separately; the final artifact has its own complete passing results.

Next user-led step: qualify the exact APK on the OnePlus, identify one owned
bulb/TV model and firmware, then select a documented comparison from the research
brief. No remaining live action is authorized while the offline-only instruction
is in force. No target vulnerability or general raw-controller path is established.

## Blockers and limits

TV and bulb models/firmware unknown. No target vulnerability established.
Actual BLE reception, GATT interoperability and handset permissions need later
phone validation. Never count emulator or synthetic evidence as RF evidence.
