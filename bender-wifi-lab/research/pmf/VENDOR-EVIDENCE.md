# Vendor survey incorporated into PMF research — 2026-11-14

The user supplied a separate live-handset survey. Its archived files were found
at `E:/code.projects/oneplus13-build-cache/wifi-command-survey-20261114/vendor-state/`
and reviewed offline. This review did not access the phone, execute the archived
scripts, or repeat a radio experiment. The archive is historical evidence, not
a check of the phone's current state. The released APK remains unchanged.

## Confirmed in the archive

- All five files in `source-sha256.json` match their recorded hashes.
- All 15 selected configuration queries have successful decoded responses.
  Driver version readback is `5.2.1.119O`.
- Roam-reason reporting and listen-interval readbacks show 0 → 1 → 0.
- The first dynamic-bandwidth cycle explicitly fails its restoration check:
  immediate reads show 0 after setting 1, then 1 after restoring 0. Four later
  reads show 0. The repeat uses 350 ms spacing and records four 1s after the set
  and four 0s after restoration. This demonstrates those readbacks in that run;
  it does not establish a universal 350 ms settling time or physical bandwidth
  behavior.
- Initial/final link records identify the same AP at 6295 MHz, with 160 MHz
  width in the interface records. Final power saving is on; gateway ping reports
  3 transmitted, 3 received and zero loss. Network identifiers remain in the
  original private files.
- The two off-channel logs report exit 0. No independent dwell/event evidence
  was supplied. Feature/logger replies are advertisements; logging was not
  enabled by this survey.

The saved `iw-vendor.c:119` puts payload bytes into vendor data without the
nested flag. The corrected-encoding stderr records `expected nested data`;
`nested-vendor-survey.py:105` sets the flag and its archived responses succeed.
Earlier CLI parsing failures therefore must not be classified as unsupported
driver commands. The source alone is not proof of the installed executable's
identity; the matching diagnostics and live responses support this diagnosis.

Derived checks and file hashes are recorded in [VENDOR-EVIDENCE.json](VENDOR-EVIDENCE.json).

## New PMF telemetry lead

The saved Qualcomm header defines `GET_STA_INFO` as subcommand 186. Its response
attributes 39/40 describe group robust-management MIC/missing-integrity-element
errors and replay counts; 41/42 describe separate beacon-protection counters.
These are **candidate diagnostic observations**, not proof of current PMF
negotiation or a tested handset capability. Beacon protection remains separate
from basic PMF. The same header defines SA Query timeout as a disconnect reason,
but this pass did not qualify its runtime reporting path.

The retained `qcacld-3.0/core/hdd/src/wlan_hdd_station_info.c` contains more than
header definitions:

| Source location | Observed behavior | Consequence |
|---|---|---|
| 2033 and 2064 | Counters are included only if `pmf_bcn_stats_valid` is set. | An absent counter is unavailable/unknown, not zero. |
| 2067–2075 | Response construction clears that validity flag, then adds four counter fields. | A query consumes cached validity; this does not prove that firmware counters themselves reset. Preserve the first response and qualify refresh/reset semantics before computing deltas. |
| 2561 and 2605 | The station-info path requests statistics and then packages available PMF counters. | A successful vendor response alone does not guarantee fresh PMF fields. The producer/offload timing remains unresolved. |
| 2688–2692 and 2709–2713 | The command rejects global monitor/FTM mode; STA/P2P-client handling uses the adapter's default link. | Validate this in managed mode and do not generalize default-link observations to every MLO link. |

No `GET_STA_INFO` counter response from the installed handset was found in the
supplied survey. Source-to-loaded-binary equivalence remains unestablished.

Subsequent live qualification is now recorded in [COUNTER-LIVE-RESULTS.md](COUNTER-LIVE-RESULTS.md):
five queries returned all four fields, each zero. This qualifies readout
availability on the tested build; counter accuracy, freshness and PMF enforcement
remain untested. The original survey's scope and offline audit above are unchanged.

## Consequences for the app and next experiment

1. Keep vendor metadata, accepted operations, stable readbacks and independently
   measured radio effects as separate capability states. A selected successful
   survey does not validate every vendor command.
2. For any future state control, save the original value before mutation, use a
   bounded settling/readback phase, and retain a recovery obligation until
   restoration is observed. Treat 350 ms as one measured spacing, not a guarantee;
   do not repeat a write merely because an immediate read disagrees.
3. Add a future managed-mode telemetry qualification step to the ordinary
   PMF-required association experiment: record whether attributes 39–42 are
   present, capture their provenance and freshness, and compare with trusted
   endpoint state. Keep missing values unknown and separate beacon counters.
   Do not enable an enforcement verdict from zero counters alone.
4. Roam-reason configuration is a possible attribution aid, but its readback
   does not show that a reassociation IE was emitted. Off-channel acceptance
   does not establish simultaneous RX/TX or dwell. Both need their own evidence.

The 6295 MHz association is a useful candidate for the next complete association
capture. Frequency, successful ping and vendor configuration values do not by
themselves establish negotiated PMF. No PMF bypass result changes as a consequence
of this survey, and no new controls were enabled in the APK.
