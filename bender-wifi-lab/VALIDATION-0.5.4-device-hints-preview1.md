# Device identification preview â€” 2026-11-16

Built `0.5.4-device-hints-preview1` (versionCode 17). Per-network device rows now
show the best available manufacturer, reported name/model or qualified type clue.
Device details explain the result and expand to show source fields, timestamps,
frame numbers and capture sessions. Existing saved addresses gain offline vendor
lookup immediately; richer identity metadata requires a suitable new capture.

## Evidence and attribution

- Bundled 56,975 manufacturer prefixes from the locally installed Wireshark 4.6.6
  compiled table. Longest-prefix lookup supports more specific allocations. The
  generator excludes local/group/zero prefixes and unsafe display text. Snapshot
  provenance and hashes are in `runtime/device-vendors-source.json`.
- Allowlisted WPS device names/manufacturers/models/types are attributed to the
  transmitter. DHCP client names/software require a matching station/request.
  mDNS service types require responses with advertised PTR records. This version
  extracts IP clues only from explicitly unprotected station-to-AP data frames.
- AP metadata, nested profiles, bad-FCS and malformed frames, AP downlink traffic
  and service-discovery questions cannot supply client identity through these paths.
- Up to 24 private clues survive sparse later scans with their original provenance.
  Conflicting models/types remain visible. Metadata cannot create or merge client
  identities or change which network an address was independently observed on.
- Shared evidence exports omit the entire identity-hints field. Arbitrary payloads,
  serial numbers and mDNS TXT data are not retained by this feature.

The prefix owner may be a module manufacturer rather than the retail brand. WPS
fields are self-reported and services can be proxied. Locally administered MACs
receive no inferred factory-vendor match. Quiet/encrypted devices may remain
unknown. No new discovery traffic, cloud lookup, radio action or ownership prompt
was introduced. Earlier preview4 preferences and the WPS progress update remain.

## Validation of the packaged APK

| Check | Result |
| --- | --- |
| Capture engine | 48 passed; includes real TShark decoding of synthetic WPS, DHCP and mDNS frames |
| Runtime/installer | 205 passed |
| JVM | 91 passed |
| API35 emulator | 60 passed, including packaged vendor-table loading, Room history, network device navigation, clue provenance and vault migration |
| Total | 404 passed; no skipped tests |
| Android lint | 0 errors, 18 warnings |
| Package/source qualification | 1,155 source inputs and 27 assets verified against the APK; runtime/native bytes verified |
| Update signing certificate | Matches the preceding 0.5.3 preview |

Logs are stored beside this report. `device-identity.png` is a synthetic isolated
Compose fixture, using the test's default Material theme; it verifies readable
clue content rather than the full app's dark theme. No handset RF test was run,
and no actual device model was independently verified by this validation.

APK: `dist/Bender-WiFi-Lab-0.5.4-device-hints-preview1.apk` (212,436,152 bytes).

SHA-256: `f5aa6eb977501427773635a13f87a608821f19e397fba58b6e8db1c00aa8f5c2`.

`artifact.json` contains the build qualification. `taildrop-result.json` records
successful Taildrop delivery at 2026-11-16 14:58:23 local (exit 0); transfer success
does not establish installation on the phone. The disposable emulator was stopped.
Previously published GitHub release files were preserved and not updated.

Primary field/source references: [TShark manufacturer dump](https://www.wireshark.org/docs/man-pages/tshark),
[WPS fields](https://www.wireshark.org/docs/dfref/w/wps.html),
[DHCP fields](https://www.wireshark.org/docs/dfref/d/dhcp.html).
WPS category constants were checked against the vendored hostap `wps_defs.h`.

Subsequent November 17 handset qualification is documented in [the current handset report](https://github.com/Bender1011001/Bender-WiFi-Lab/blob/main/docs/HANDSET-VALIDATION-054.md).
