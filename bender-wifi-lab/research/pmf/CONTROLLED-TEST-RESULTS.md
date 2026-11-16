# Controlled PMF counter test — 2026-11-14

**One group deauthentication frame without an MMIE was submitted to the owned
guest BSS. All four queried counters remained zero. Counter increment accuracy
and actual handset rejection remain unresolved.** The phone remained associated
at the sampled checks and subsequently received replies to all three gateway pings.
This result establishes neither broken counters nor a PMF bypass.

## Setup and controls

The receiver was the CPH2655 internal managed interface, Android 16, driver
5.2.1.119O, on the user's WNC-CR200A guest network Bender-PMF at 5805 MHz. The
user enabled only its 5 GHz band and reported connecting only the handset.
Full handset versions and installed module hash are in
[the earlier readout record](COUNTER-LIVE-RESULTS.json).

The sender was an AWUS036ACS (RTL8811AU, USB 0bda:0811), passed into WSL Ubuntu.
The running kernel stayed 6.6.87.2-microsoft-standard-WSL2. The rtw88 driver was
built against matching kernel sources and loaded normally. All 203 cfg80211 and
340 mac80211 imported-symbol CRC comparisons matched the installed modules.
No forced module load or new running kernel was used. Source commits:

- Kernel: `427645e3db3a8896714f22a3d3fe0c3f7b317ad4`.
- rtw88: `f1dd7c3a5b87478ab23c3dbb1f8f0d6b82f3f71c`.

A passive scan found the exact guest BSS; normal beacon processing made 5805 MHz
available for transmission. The global regulatory domain remained 00. One directed
probe request elicited an AP response to the Alfa's temporary locally administered
address, plus three AP retries sharing one sequence number. This qualifies the
probe path, not arrival of the later PMF frame at the phone.

The captured phone association selected SAE (AKM 8), advertised MFPC=1 and
MFPR=0, and received a successful response. Android then reported a completed
SAE connection to the same guest BSS. These connection observations do not
independently prove cryptographic enforcement. An older Android `RequirePmf`
record is not substituted for the captured bits. The phone joined its existing
saved network through the Android framework; no password was extracted or changed.

The sampler held the shared radio lock, checked recovery/privacy state, required
six stable baseline readings, and checked the exact connection during the window.
The SAE gate qualifies this experiment; SAE is not a universal PMF prerequisite.
Six offline sampler fault tests passed. Sender fixtures were independently dissected.

## Trial and observations

The completed trial ran at 18:29:24–18:29:40 PST on 2026-11-14. It made one
application-level submission: a 26-byte group deauthentication MPDU, reason 3,
without an MMIE, using the owned guest AP as source/BSSID. Radiotap requested
6 Mb/s and no ACK. The socket accepted 38 bytes including radiotap. Transmission
intent was persisted first. No automatic transmission retry occurred.

| Field | Six baseline readings | Nineteen subsequent readings |
|---|---|---|
| BIP MIC error, attribute 39 | 0 | 0 |
| BIP replay, attribute 40 | 0 | 0 |
| Beacon MIC error, attribute 41 | 0 | 0 |
| Beacon replay, attribute 42 | 0 | 0 |

The first-to-last sample span was 13.755 seconds on the handset clock. All 25
queries returned the fields and the sampler completed without an abort. This
frame did not exercise replay accounting or beacon protection.

The sender capture contains 146 records and reports zero kernel capture drops.
Two records contain the identical test MPDU: the injection copy and a later
transmit-status record with zero reported retries. Its radiotap format matches
the mac80211 transmit-status path in the matching kernel source. These are two
local records of one submission, not independent handset reception. Neither
record supplies receiver signal strength.

A prior attempt stopped after six baseline readings, before creating transmission
intent. Its readiness calculation incorrectly included sampler startup time.
An earlier clock-sync marker and unbuffered stream handling corrected the check;
the completed attempt's conservative readiness age was 0.184 seconds. Both
attempts are retained. The first was known to have submitted no test frame.

## Limits, restoration and evidence

There is no independent observation of this frame arriving at the handset and no
handset drop reason. Staying connected alone cannot establish rejection. The
retained Qualcomm `wma_process_bip` source also has a short-frame rejection before
its MIC verifier. The tested frame could take that earlier path. Actual firmware
offload behavior and exact source equivalence with the installed binary remain
unestablished. A zero delta therefore cannot establish counter failure.

A further experiment should add receiver/drop evidence and select a stimulus
that reaches the intended integrity-check path, with a fresh no-send control and
one explicitly counted trial. No additional transmission was performed.

The phone remained on the guest BSS at 5805 MHz; all three gateway pings succeeded.
The sampler exited and its lock was released. The Alfa's original interface name
and address were restored, its interface was brought down, and USB bus 1-3 was
returned to Shared/not-attached-to-WSL. Temporary rtw88 modules and newly loaded
dependencies were removed; original cfg80211 presence was preserved. The firmware
search path is empty again. No capture remains running.

Build files, installed compiler packages and private evidence remain locally.
The out-of-tree-module kernel taint remains 4096 until restart; no WSL restart
was performed. The guest network remains enabled and the phone connected to it.
Router-page automation was blocked because Computer Use could not verify the
page URL. Restoring the guest network's originally disabled state remains a
manual router action.

- [Redacted machine-readable result](CONTROLLED-TEST-RESULTS.json).
- Private local records: `validation/pmf-20261114/controlled/`, including
  `trial-01/`, `trial-02/`, `phone-evidence/` and `setup-evidence/`.
- Private sender source/build records: WSL `/opt/bender-pmf-20261114/`.
- The handset evidence archive's SHA-256 matched before and after retrieval.
  `controlled/evidence-manifest.json` records retained artifact hashes.

No released APK or app capability classification changed. Readout availability
is validated under the recorded conditions; increment behavior, freshness,
reset semantics and PMF enforcement remain unresolved.
