# Device-count update: 0.5.2-device-count-preview1

The prior decoder ignored two-address control traffic and discarded BSS Load station totals. This could leave APs with zero identified clients even when received control frames exposed peer addresses. The app also presented the identified-address count as its prominent device total.

## Changes

- Decode RTS, Block ACK/request and PS-Poll peer relationships only when exactly one endpoint is a directly advertised AP in the capture. Exclude failed FCS, malformed controls, known frequency conflicts, AP-to-AP and unknown pairs. ACK/CTS alone remain insufficient.
- Group repetitive control evidence, retaining first/last time, last frame, subtype and frame count. Preserve latest relationships by timestamp, including out-of-order captures.
- Retain complete directly owned BSS Load station counts with provenance. Preserve an explicit zero; missing, malformed, duplicate or nested-only reports stay unknown.
- Display timestamped AP totals separately from identified addresses. Keep targeted discovery visible for populated lists; listen for up to 120 seconds with early finish.
- Preserve prior WPS, Bluetooth, root/privacy, screenshot, vault and radio recovery behavior.

## Validation

40 host engine tests, 201 runtime tests, 76 JVM tests and 54 emulator instrumentation tests passed without skips in the final set. Android build, lint and source/asset verification passed. The same 40 engine tests passed in the OnePlus app's bundled ARM64 runtime; these were CPU-only tests with synthetic frames, not live Wi-Fi transmissions. An initial invocation used the separate NetHunter environment with an unsuitable inherited PATH; it failed discovery of TShark and was corrected to the actual bundled runtime. The original log is retained. The first UI run found an obsolete test assumption that the page heading stayed onscreen after returning to a scrolled device list; the test now scrolls to the heading before asserting visibility. Feature assertions were retained.

Replaying the retained 11,290-frame phone capture yields **12 identified addresses versus 6 previously**, with six AP station totals. There are 4,524 qualified control frames grouped into 54 provenance records; exported analysis is 296,325 bytes. This replay does not establish the true number of connected physical devices. The current phone inventory was read via a stable, hash-verified database snapshot: 27 APs, 15 addresses and five sessions. No historical inventory was deleted or rewritten.

APK SHA-256: `aaf1b13535e2bc95ff34a34e4043284abeac5cfeecd2d5589ba3f5022ea1b7f9`.

Evidence: `validation/device-count-20261115/`. Raw replay and database snapshot are private. This release has not yet had a fresh live survey or UI installation check on the phone; the user is charging it. After installing, scan again to populate the new report fields. An AP's reported station total is time-scoped, per BSSID, not independently verified, and is not a count of unique physical devices across bands.
