# Validation — current 0.5.0-live-wps-preview1

See [the current exact-build report](VALIDATION-0.5.0-live-wps-preview1.md).
The previous 0.4.0 report follows unchanged below.

# Validation - 0.4.0-privacy-wps-preview1

2026-11-15. Package `dev.sir.benderwifi`, versionCode 11. Personal debug-signed
preview. No phone installation, router changes or live radio tests in this pass.

APK: `dist/Bender-WiFi-Lab-0.4.0-privacy-wps-preview1.apk`
SHA256: `1e77ac22568f7df1cd7cbc6314f0f2e76af68ca0313ebfbc3e6e35b59546d169`
Bytes: 210650179

## Implemented

Automatic operation-owned Wi-Fi privacy preparation/restoration replaces the
manual airplane-mode prerequisite for capture and directed tests. Default mode
filters normal WLAN IPv4/IPv6 traffic and preserves cellular/Bluetooth settings.
Optional Strict radio isolation uses the earlier all-interface/radio profile.
Preparation waits for wlan0 before probing it. Cancellation still performs
restoration; failed or uncertain restoration retains a recoverable journal.
Root validates the current MAC and exact filter rules before transmission.

Network and Tools pages open a bundled local Pixiewps solver. One imported WPS
exchange stays in memory and feeds an isolated native process through stdin.
Both WPS hashes are independently recomputed before an encrypted PIN and its
network-linked result are committed together. Search deadline, cancellation,
background cancellation and startup reconciliation prevent automatic replay.
The synthetic Android fixture recovered PIN 12345678; it is not a router finding
or a Wi-Fi password. Live WPS collection/enrollment is unimplemented.

## Passing checks

| Check | Result | Evidence under validation/privacy-wps-preview1-20261115 |
|---|---:|---|
| Packet engine | 32 | engine.log |
| Runtime and supervisor | 195 | runtime.log |
| Android JVM | 73 | android.log; jvm-results/ |
| Android emulator app/data/UI/native solver | 51 | emulator-data-ui.log / .json |
| Android emulator legacy credential migration | 1 | emulator-crypto.log / .json |
| Total | 352 | Final set: no failures or skips |

Offline Gradle build passed; lint 0 errors / 6 warnings. APK signature
verification is recorded in apk-signature.log. Exact APK/source correspondence:
205 source/test/build inputs and 21 packaged executable/runtime
assets. Against the network preview, 85 inputs are unchanged,
25 changed and 95 added; none removed. Bluetooth production
code and both runtime installer scripts are unchanged. After the final catalog
wording change, test_research.py was rechecked in catalog-release.log; this repeat
is not counted as additional tests. Prior preview 4 policy
choices, network navigation and historical release artifacts are preserved.

Runtime coverage includes exact IPv4/IPv6 rules, session ownership, legacy
journals, application/restore failures, interface readiness, cancelled cleanup,
pending recovery and current-MAC policy. These use simulated host responses.
Native emulator coverage runs the actual x86_64 executable with a public
synthetic exchange, independent hash verification, encrypted PIN storage,
network attribution, interrupted-result reconciliation and an enforced timeout.
ARM64 is cross-compiled and packaged, not executed on the OnePlus in this pass.

Retained ARM64 engine32/Linux installer17 logs are unchanged platform repeats,
not fresh executions or part of the total. The ten relevant unchanged input
hashes and exact original log bytes are checked in retained-checks.json.

## Intermediate failures and test environment

The first runtime pass exposed a legacy mock call-shape mismatch and an existing
live-WPS catalog ID reused for offline analysis. Both were corrected; live and
offline WPS now have separate catalog entries. Initial C99 compilation failed on
upstream x86 assembly; the native build uses GNU99.

The disposable API35 x86_64 emulator could not bind its previous port; it moved
to port 5580. Cold-boot credential storage required the UI dump under /data/local/tmp
until a real synthetic-PIN unlock. Two startup attempts hit Android code-loading
ANRs before app initialization. The final functional runs used 4 GiB guest RAM
and explicit Android package compilation. Those results do not measure cold-start
performance. See startup-anr.log and emulator-compile-release.log.

The first complete 51-test run found an InterruptedIOException from closing the
native output pipe during timeout. The reader now has supervised cancellation,
preserving the timeout result. The final suite passes that regression. A reviewed
save/cancel race was also fixed: a committed PIN is not subsequently relabelled
cancelled. Earlier failed logs are retained; only final passing logs are counted.

## Qualification limits

The automatic privacy profile needs OnePlus driver/firmware and independent
router/packet observation before live qualification. Filter configuration does
not establish coverage of AF_PACKET DHCP, firmware traffic, association fields
or RF correlation. MAC randomization is not anonymity, and no IMEI/SIM identifiers
are read. Recovery of settings is not proof of reassociation or Internet access.

Imported WPS fields are attributed to the user's selected network; the input is
not cryptographically bound to that BSSID. One synthetic vulnerable exchange does
not establish compatibility with all upstream modes or real access points.
Live internal-radio WPS exchanges, using a recovered PIN to obtain a password,
new PMF behavior and the new ARM64 solver have not been tested on the handset.
No screenshots were blocked, no exact-driver hash lock or target ownership/age
gates were added, and existing easy encrypted reveal/copy remains available.

See [implementation](research/privacy-wps-20261115/IMPLEMENTATION.md) for the
profile, limits, input format and [upstream Pixiewps](https://github.com/wiire-a/pixiewps).
