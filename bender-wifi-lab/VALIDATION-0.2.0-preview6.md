# Validation - 0.2.0-preview6

Completed offline build/tests, independent review and CPH2655 handset validation
on 2026-11-15. The user authorized phone testing after the original offline pass.
No internet downloads or remote-access payload work were performed. Previous
results are preserved in VALIDATION-0.2.0-preview5.md and earlier reports.

## Exact artifact

- Package dev.sir.benderwifi; version 0.2.0-preview6, code 8; debug signed with the existing key.
- APK: 209,904,336 bytes; SHA-256 `776dfb2a1a6c75765293c684c65d895d422505582c32814473a73a999306fc6f`.
- The installed handset APK hash matches the tested build. Source/assets are checked again by the release packager.
- Phone: CPH2655, Android 16/API 36, build CPH2655_16.0.8.301(EX01).
- Kernel: 6.6.89-android15-8-g97a9aaefab9a-ab14519050-4k.
- Installed driver SHA-256: `d6bd3fac600aaeba858e83292df167bc410457c07e422047c1a181c31d24d3df`. This identifies the installed file, not proof of equivalence to every loaded component.
- Bundled runtime image remains unchanged. NetHunter was preserved.

## Final automated checks

Evidence is under validation/phone-preview6-20261115/.

| Check | Final result | Evidence |
| --- | --- | --- |
| Protocol engine with host TShark | 32 passed | engine.log |
| Runtime/supervisor/installer/privacy/channel regressions | 181 passed | runtime.log |
| Android JVM | 45 passed | app/build/test-results/testDebugUnitTest/ |
| Exact-APK emulator data/UI/current vault | 33 passed | emulator-data-ui.log |
| Exact-APK authenticated legacy vault migration | 1 passed | emulator-crypto.log |
| Bundled ARM64 decoder platform repeat | 32 passed | arm64-engine.log |
| Selected isolated tests on physical phone | 8 passed | phone/final-isolated-instrumentation.stdout |
| Linux installer platform repeat | Prior 17 passes retained with unchanged input hashes | retained-checks.json |
| Independent follow-up review | No remaining actionable findings in scope | review/REVIEW.md |

292 distinct automated tests passed; no final failures/skips. ARM64, handset and
reviewer repeats are separate platform evidence, not extra distinct test cases.
Offline assembleDebug, assembleDebugAndroidTest, testDebugUnitTest and lintDebug
passed. Final lint has no errors; pre-existing warnings remain in the report.

## What the handset established

Installation and an in-place upgrade succeeded without clearing app data. The
user's bundled-runtime installation completed. Recovery-state checks and radio
probe succeeded after the shell fix. Magisk's approval activity was blocked on
this phone (activity result -91); existing ADB root access was used to grant only
the verified Bender package UID, with Magisk logging and notifications enabled.
This is not evidence that an unattended first-install root prompt works on every
Magisk/OxygenOS combination.

A final 120-second passive survey captured 9,714 frames. On-phone analysis saved
16 APs and 7 observed client addresses. No real EAPOL exchanges were observed.
The metadata did not independently verify checksums: all 9,714 frames were
not flagged as failed. Missing/unflagged integrity is not proof of valid FCS.

The raw capture hash matches observation provenance. Verified duplicate raw data
was removed from the root runtime while the app copy remained. Inventory survived
force-stop/relaunch (20 APs/8 client addresses including four APs/one client from
clearly named SYNTHETIC fixtures). Network SSID matched the baseline after Wi-Fi
reconnected; Android selected a different BSSID. Managed mode, firmware-path and
Wi-Fi settings matched the baseline; the local gateway answered 3/3 pings. Final
root recovery/privacy journals were clear. No deauth/DoS, PMF, WPS or password
recovery experiment was conducted in this pass.

Synthetic PCAP import was also repeated on the final APK: Open, WPA,
WPA2-Personal and WPA2/WPA3-Personal labels were preserved; two failed-FCS frames
were excluded; a damaged M2 did not become a complete four-message handshake.
Per-session deletion was exercised through the actual app confirmation: only the
selected synthetic raw capture/log files were removed, summaries remained, and
real captured files retained their hashes. JSON import/export filtering and
provenance joins passed the isolated in-memory Room test on the phone.

Temporary USB stay-awake settings were restored, staged Downloads/UI-dump files
removed, and the disposable emulator stopped. Synthetic test entries remain
clearly named in app history. Raw logs/captures contain private identifiers and
are excluded from the source distribution.

## Defects found only after handset testing

1. The bottom tabs overlapped Android's three-button navigation. Insets and
   equal-width tab buttons now keep all five tabs visible and operable.
2. qca-runner.sh had CRLF line endings and Android rejected it before radio access.
   It now uses LF; .gitattributes, a build guard and an actual Android shell-parser
   test prevent recurrence. The rest of the supervisor is unchanged.
3. A 1,011,808-byte / 11,290-frame capture exceeded the old 128 MiB temporary PDML
   limit. Decoder output exceeded 270 MiB although final observation JSON was
   about 259 KB. The temporary-file limit is now 512 MiB, with the existing shared
   120-second deadline, packet/field limits and cancellation retained. This is a
   disk-output bound, not a hard RAM ceiling. The first raw capture was retained;
   the final on-phone survey completed under the new limit.
4. Independent review reproduced a completed-decoder race in the stderr-size
   check. Both closed output files are now rechecked after process exit. A fast
   completed decoder cannot bypass either bound; both regression subcases pass.

Failed/intermediate runs remain in the private validation directories. An early
ADB-server interruption was recovered before the affected action started. A
radio-state comparison initially failed because it compared raw newline formats;
parsing the four individual settings verified restoration. No failed result was
silently converted into a pass. Preview4's driver compatibility, target policy,
screenshot and easy encrypted-vault choices remain unchanged.
