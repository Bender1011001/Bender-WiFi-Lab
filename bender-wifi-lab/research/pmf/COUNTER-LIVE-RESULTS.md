# Live PMF counter readout — 2026-11-14

**The counter readout works on the tested OnePlus build.** Five completed
`GET_STA_INFO` requests each returned all four counter attributes, with values
of zero. This establishes field availability and decoding under these conditions.
Counter increment accuracy, freshness, reset semantics and PMF enforcement remain
unverified; no known integrity failure or replay was introduced.

## Device and execution

- CPH2655, Android 16, kernel
  `6.6.89-android15-8-g97a9aaefab9a-ab14519050-4k`.
- Driver readback `5.2.1.119O`; firmware reports
  `WLAN.GNG.1.0-03342-QCAGNGSWPL_V1.0_V2.0_SILICONZ-1.81206.153`.
- Internal `wlan0`, managed mode, associated at 6295 MHz. Final width: 160 MHz.
- Held the existing shared radio lock. No configuration setters, monitor-mode
  transitions, beacon-reporting changes or management-frame submissions.
- Sent vendor version query 61 and station-info query 186. Station requests used
  an empty vendor-data attribute carrying the nested flag. No receive-registration
  or spectral experiment was repeated.
- Requested sampling gaps were 350 ms, 350 ms, 1 s and 1 s. Actual start-to-start
  intervals were 406.6, 369.1, 1028.1 and 1024.1 ms, including query/guard overhead.

## Returned values

| Response attribute | Meaning described by reference header | Returned in | Values |
|---|---|---|---|
| 39 | Group robust-management MIC/missing-integrity-element errors | 5/5 responses | 0 each time |
| 40 | Group robust-management replay count | 5/5 responses | 0 each time |
| 41 | Beacon-protection MIC/missing-integrity-element errors | 5/5 responses | 0 each time |
| 42 | Beacon-protection replay count | 5/5 responses | 0 each time |

Every response contained one vendor message with all four fields decoded as
32-bit unsigned integers. There were no errors in the five station-info queries.
The raw responses are preserved privately, including other returned attributes.

An initial harness setup attempt stopped before sending vendor queries because
the app's small constants file did not include vendor-command constants. Those
four constants were added from the archived survey definitions; the failed
attempt was retained separately. This was not a driver rejection. The final
three probe inputs passed SHA-256 checks on the phone before execution. Offline
decoder assertions covered absent values, zero, the u32 maximum, malformed widths
and duplicate fields. No released-app code was changed.

## What this does and does not establish

The API returns the fields on this running build. Their repeated presence is
compatible with the source's validity/refresh path, but identical zero samples
cannot distinguish freshly collected zero from cached or unpopulated values.
The source clears a cached-validity flag during response construction; this does
not establish whether underlying firmware counters reset or how they are scoped.

These group-frame counters do not cover every PMF failure or unicast-management
rejection. Zero beacon errors do not establish that beacon protection is enabled.
The ordinary station query returned no MFP/PMF state field. The separately supplied
scan-cache advertisement remains advertisement evidence, so negotiated PMF is
still unknown in this result.

Next validation: use a controlled AP/client with independently established PMF
and an independently observed rejection event, then check counter changes and
event attribution. Qualify reset/association boundaries separately. This run did
not perform that experiment, and it establishes no PMF bypass.

## Final condition and evidence

The same AP and 6295 MHz frequency were present throughout the sampling and final
checks. Power-save readback was unchanged. Final interface width was 160 MHz;
three gateway pings were transmitted and all three were received with zero loss.
All probe sockets/processes completed and the shared lock was released.

- Redacted results, versions and hashes: [COUNTER-LIVE-RESULTS.json](COUNTER-LIVE-RESULTS.json).
- Local private scripts/logs/raw responses: `validation/pmf-20261114/live/`.
  Rebuild the redacted report with that directory's `summarize.py`.
- Private handset evidence: `/data/local/nhsystem/kali-arm64/tmp/bender-pmf-counter-20261114-02/`;
  the earlier setup-failure evidence is in the corresponding `-01/` directory.
- Installed module hash is recorded; exact equivalence with the retained source
  or firmware internals remains unestablished.

## Separate security-feature evidence supplied by the user

All 25 hashes in the archived security-feature manifest matched. Offline checking
reproduced the 50 beacon reports, one BSSID, 6295 MHz frequency, 100 TU interval,
increasing AP TSF and zero events during the recorded baseline/after-stop windows.
The raw host boot-time span is 5015 while AP TSF spans 5,017,600 microseconds;
host-field units remain unresolved and must not be converted as documented
nanoseconds. Reports lack full beacon IEs and RSSI. They support a connected-AP
metadata timeline, not a validated attack detector or general capture.

The separate spectral start was denied with EPERM; archived original/final
configurations match and final status has no active flags. The archived managed
RX-registration failures remain EINVAL. None of those live tests was repeated
here. See [SECURITY-FEATURE-EVIDENCE.json](SECURITY-FEATURE-EVIDENCE.json).
