# Claim and evidence map

Prepared November 15, 2026. Version-specific observations must not be promoted into claims about every release, device, band or client.

| Claim | Evidence and scope | What it does not establish |
|---|---|---|
| Internal-radio directed deauth produced separately observed client effects | V61, one rooted CPH2655, stock driver hash in the [three-trial record](../evidence/router-corroborated-20261113.json), one request per client at 2437 MHz; matching ACKs and router disconnect/reconnect sequences | General injection, all bands, all clients, a measured success rate, or current APK radio qualification |
| WiZ and Roku had observable connectivity interruption | Same record: 8 consecutive WiZ query failures and 9 consecutive Roku ping failures, clean initial baselines and later recovery | Exact RF timing or exact outage duration; Samsung ping was not a suitable independent effect indicator |
| Router corroboration is separate from the sending tool | CR200A event records retained privately and summarized with source hashes | Independent laboratory replication or an independent RF capture |
| Passive internal capture and restoration have handset evidence | Historical Hijacker Preview 5 [capture/recovery record](../evidence/preview5-capture-recovery.json): selected-channel captures, 72-frame partial capture after cancellation, managed restoration and settled gateway 3/3 | Simultaneous capture and managed TX, automatic deauth-to-handshake workflow, or a crackable handshake in every capture |
| The new decoder finds more addresses in a retained capture | [Offline replay](../evidence/device-count-replay.json): 11,290 frames, 6 to 12 addresses, 6 AP station totals; qualifying controls grouped into 54 provenance records | A complete census or independent verification of an AP's advertised total |
| Bender 0.5.2 passed software/package checks | [Validation summary](../evidence/bender-validation.json); 40 host engine, 201 runtime, 76 JVM, 54 emulator tests; earlier 40 synthetic ARM64 cases retained with unchanged-input hashes; build/lint/package pass | New live RF qualification, fresh handset installation, exhaustive correctness or an external security audit |
| This exact APK has handset installation and passive-workflow evidence | [November 16 handset record](../evidence/handset-preview2-20261116.json): in-place update, startup/runtime checks, full passive scan, inventory preservation, original connection restored and gateway 3/3 | Current deauth or WPS qualification, a physical-device census, process-death/timeout behavior, or all-device compatibility |
| The installer has retained Linux checks | 17 earlier Linux installer tests retained with unchanged input hashes in the validation summary | A fresh rerun of those 17 tests in this release pass or universal clean-device installation |
| General monitor injection is still blocked in the tested diagnostic | [November 15 summary](../evidence/general-injection-summary.json): host send reached firmware, ACK false, completion DISCARD | Exact root cause, a working firmware patch, or proof that every possible path fails |
| PMF was not bypassed in the published deauth trials | IoT AP advertised PMF disabled; Roku AP advertised optional PMF and negotiated client state was not established | PMF bypass or validation of PMF rejection counters |
| Live WPS is an experimental module | Current source implements managed collection and solver flow; handset evidence only establishes temporary-interface creation/removal in the recorded validation | Live Pixie Dust success, a recovered real network password, or factory-PIN inference |
| The UI keeps work by network | Room-backed records, synthetic workflow/emulator tests and source review | Device identity across randomized MACs; observations are per BSSID/address |

## Version boundaries

- **V61 terminal delivery:** source hashes preserved in the three-client evidence. This is the version used for the headline RF result.
- **Hijacker Preview 5/6:** earlier phone capture/recovery and ACK-path checks. Preview 6 footage contains private identifiers and is not included.
- **Hijacker Preview 8 / terminal 0.2.0-preview3:** current reference source snapshots. Their later changes do not inherit complete V61 handset qualification.
- **Bender 0.5.2, version code 15:** new app APK/source in this release. It passed the listed software checks and the November 16 installation/passive-workflow checks; active-feature qualification remains separate. It is a different app from Hijacker.

Source hashes identify retained bytes. They do not authenticate an experiment by themselves. Public summaries omit identifiers and do not substitute for private originals when investigating a discrepancy.
