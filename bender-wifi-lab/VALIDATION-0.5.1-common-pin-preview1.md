# Validation — 0.5.1-common-pin-preview1

## Scope

This offline preview adds a separate selected-AP action that tries three fixed,
valid-checksum WPS PIN candidates: `00000000`, `12345670`, `11111115`. The app
uses the internal radio's temporary managed interface and the existing privacy
and recovery supervisor. Each candidate gets a fresh scan and a fresh native
registrar process. The next candidate is attempted only after an authenticated
M5 or M7 PIN hash mismatch. An AP setup-lock advertisement, timeout, malformed
reply, target/frequency change, cancellation or process uncertainty ends the
run. Interrupted transmissions are not replayed. A successful WPS AP-settings
exchange saves the PIN and supplied password encrypted in Vault; association
with that password is not asserted.

These are examples, not an exhaustive factory-PIN list. A BSSID does not
reliably identify the factory PIN. For the owned WNC-CR200A, the official
manual points to Basic → Wi-Fi → Wi-Fi Protected Setup for the current
Gateway PIN. No router PIN or password was copied into this release.

## Offline evidence

- Native ARM64 and x86_64 supplicant builds succeeded. The x86_64 executable
  returned `Bender managed WPS protocol 1` on the Android 35 emulator.
- `validation/common-pins-20261115/`: engine 32, runtime 201, JVM 73,
  emulator 53. The emulator set includes the new explicit PIN action and
  encrypted-vault coverage. Final set has no failures or skips. Android lint
  and debug builds succeeded. `tools/package_release.py` verified 1,140
  source inputs and 24 executable assets against the APK.
- The first emulator pass found that the synthetic Bluetooth screenshot helper
  could receive no external files directory on a fresh boot. It now uses the
  app-private files directory as fallback. The exact rebuilt APK then passed
  the full emulator set.
- The resulting APK is `dist/Bender-WiFi-Lab-0.5.1-common-pin-preview1.apk`,
  SHA-256 `74cf071a5ada67cf22269b7b57324525ba48057b4bb4dcfe8ad98faa5c595477`.
  The matching source archive is in `dist/`; its hash is recorded in
  `dist/manifest-0.5.1-common-pin-preview1.json`.

## Limits

No new phone or router transmission was performed for this preview. Native
supplicant startup, second-interface association, AP responses, PIN rejection
classification, lockout signaling, credential retrieval and recovery still
require a controlled handset/AP test. The WNC scan records previously seen
were cached advertisements; they do not prove the current WPS state. A
candidate-set miss is not a security verdict. The app does not infer a PIN
from an SSID, MAC, manufacturer or model.
