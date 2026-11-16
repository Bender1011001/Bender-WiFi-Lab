# Validation - 0.3.1-network-preview1

2026-11-15. Package `dev.sir.benderwifi`, versionCode 10. Personal debug-signed
preview; no publishing or handset installation in this pass.

APK: `dist/Bender-WiFi-Lab-0.3.1-network-preview1.apk`
SHA256: `129577629f5124d27ce0e2b755fe12a8ee8698eba86e70118b9eb1ab0048ebe5`
Bytes: 210919354

## Changes

Networks is the start screen: scan, observed device counts, filters and sorting,
then per-network Actions / Devices / Saved work / Details. Devices have separate
action pages. Unlinked addresses remain visible without fabricated AP links.
Same-name APs remain separate. Back navigation preserves the selected network
section and inventory filters. Tools contains installation, imports and the full
catalog; Vault, Evidence and Bluetooth remain available.

Eight missing feature families were added to the catalog, with null operations
and explicit implementation/hardware blockers. No new radio path, password
recovery or vulnerability is claimed. See
[feature coverage](research/network-workflow-20261115/FEATURE-COVERAGE.md).

## Passing checks

| Check | Result | Evidence under validation/network-preview1-20261115 |
|---|---:|---|
| Packet engine | 32 | engine.log |
| Runtime | 181 | runtime.log |
| Android JVM | 66 | android.log and current Gradle XML |
| Emulator app/data/UI | 47 | emulator-data-ui.log / .json |
| Emulator legacy credential migration | 1 | emulator-crypto.log / .json |
| Total | 327 | No failures or skips in final set |

Gradle offline build passed. Lint: 0 errors / 6 warnings. APK v2 signature verifies.
The exact installed emulator APK hash matched the build. Source correspondence:
110 source/test/build inputs and 18 bundled assets verified. 101 prior input hashes
are unchanged; 7 changed, 2 added. All Bluetooth production code, radio ownership,
root/identity/privacy/backend/scanner/engine paths are unchanged.

The three new inventory tests cover same-name AP separation and counts, filter
restoration, unlinked device action scope, and unavailable actions without radio
requests. The production workflow test covers handshake/deauth dialogs, device
back navigation and saved network notes. Native emulator screenshots were inspected
for layout and Bender colors. They use synthetic APs; shared-suite status text may
show the preceding negative Bluetooth ownership test, not a live Bluetooth session.

Retained ARM64 engine32 and Linux installer17 are platform repeats, not additional
fresh checks. Their original log bytes and ten relevant unchanged input hashes
were verified in `retained-checks.json`. They are not included in the 327 total.

## Intermediate failures and limits

The first emulator suite had one assertion expecting the old Radar subtitle after
returning from Bluetooth. It was updated to the new Networks subtitle. That run is
preserved in `emulator-data-ui-first.log`; it is not the final result.

Two later attempts stopped before instrumentation because the disposable emulator
was still at its lock screen. Inspection showed the wake/swipe timing problem. The
runner now waits for an awake display and an actual PIN input surface before
entering the emulator's synthetic credential, then verifies unlocked state. Final
full and legacy-crypto runs passed. No phone credential was read or used.

No physical phone or live radio test was performed. The user's later instruction
authorized read-only inspection of the owned CR200A admin UI in Chrome. Existing
logs exposed the OnePlus DHCP hostname, addresses and association metadata; these
are baseline observations, not effects of this APK. No router setting or log was
changed. Private addresses and modem identifiers were omitted from project notes.
See [privacy-session proposal and baseline](research/network-workflow-20261115/OPSEC-SESSIONS.md).

The current airplane-mode/privacy guard is unchanged. Automatic operation-specific
privacy sessions are a proposal requiring separate implementation and qualification.
The new APK is ready for user review; on-phone workflow and RF qualification remain
separate. Prior releases and their validation files are preserved.
