# Bender 0.5.4-device-hints-preview1

Version code 17. Built November 16 and installed and checked on the OnePlus on November 17, 2026. This is a new prerelease; the published 0.5.2 artifacts remain unchanged.

## App changes

- WPS collection and solving report their actual stages, elapsed time, deadlines, last activity and stall state. Progress and outcomes remain separate; live password recovery is not established by this release.
- Device rows and details show the best available manufacturer, reported name/model or qualified type clue, with source fields and uncertainty. The bundled registry has 56,975 vendor prefixes. Local/random addresses do not receive a factory-vendor guess; a prefix owner may be a component manufacturer.
- Existing network records, device-count distinctions, encrypted storage, recovery behavior and preview-4 policy choices are retained. No new ownership prompt or exact-driver-hash lock is introduced.

## Validation

The exact APK passed 48 engine, 205 runtime, 91 JVM and 60 API35 emulator tests: **404 passed, zero skipped**. Android lint reported zero errors and 18 warnings. All 1,155 source inputs and 27 packaged assets matched the build manifest. The update certificate matches 0.5.3.

On the OnePlus, the in-place update, startup, Check radio, a full 120-second passive survey, new-capture import, preservation of previous record IDs, vendor-guess display and restoration passed. The survey recorded 2,285 frames, 16 AP records, 9 client addresses and no handshakes. The phone returned to its original AP/frequency with no recovery pending, runtime mounts removed and gateway replies 3/3. No new crash entries appeared. See [handset validation](HANDSET-VALIDATION-054.md) and the [software record](../evidence/bender-validation-054.json).

One older completed capture still exceeds the evidence JSON node budget and displays an import warning after restart. Its original evidence remains available. This release does not fix that historical import-size issue; the new capture imported successfully.

## Research update

The [consolidated write-up](TECHNICAL-WRITEUP.md) adds a fresh-boot managed-interface transmission, independent Alfa reception, client observations and a two-packet appendix with original lengths, redacted bytes, FCS verification results and timing calculations. The phone transmitted internally; the separate Alfa was a receiver on the PC. Raw captures remain private.

The experimental terminal harness now uses a private mount namespace before creating runtime bind mounts. Its 21 Linux tests passed; phone probes for normal exit, exit 23 and forced termination left both caller and Android-init mount tables unchanged. The helper and its [qualification](../tools/isolated-runtime/README.md) are published separately. This corrects the research harness and is not a change to the APK's runtime implementation.

General injection remains unresolved. App-driven deauth, live WPS recovery and Bluetooth interoperability were not tested in this handset pass. The terminal experiment does not transfer those qualifications to the APK.

Install as an update when signatures match. See [installation](INSTALL.md), [claim boundaries](CLAIMS.md) and [upstream credit](PRIOR-WORK.md).
