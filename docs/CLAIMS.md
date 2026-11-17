# Claim and evidence map

Updated November 17, 2026. Each result belongs to its recorded artifact, build and experiment.

| Claim | Evidence | Boundary |
|---|---|---|
| Managed-interface directed deauth reached the air after reboot | [Fresh-boot record](../evidence/fresh-boot-air-20261116.json) and [two-packet appendix](../evidence/two-packet-appendix-20261116.json): one request, independent Alfa reception, ACK, target reconnection exchange and client-query interruption/recovery | One rooted CPH2655/build and target; separate terminal tooling, not this APK's active-feature qualification. The ACK has no transmitter address. |
| Three earlier clients had separate router corroboration | [November 13 trials](../evidence/router-corroborated-20261113.json): matching ACKs and router disconnect/reconnect events, with suitable client probes for WiZ and Roku | Different experiments from the fresh-boot capture; no new router log in the November 16 trial and no success-rate study |
| 0.5.4 passes its software/package checks | [Build record](../evidence/bender-validation-054.json): 48 engine, 205 runtime, 91 JVM and 60 emulator tests; 1,155 source inputs and 27 assets verified | Synthetic/software coverage is not hardware interoperability or an external security audit |
| The released APK has current installation/passive-workflow evidence | [November 17 phone record](../evidence/handset-preview054-20261117.json): in-place update, survey, saved IDs, vendor UI and restoration | No live WPS recovery or app-driven deauth in this pass; one historical oversized import still warns |
| Device hints have explicit provenance | Vendor registry, parser exclusions and UI tests in the matching source; vendor display checked on phone | Prefix owner may be a module maker; captured fields are self-reported, services may be proxied, and no retail model was independently verified |
| Observed addresses and AP totals are different | [Retained replay](../evidence/device-count-replay.json), decoder tests and timestamped UI fields | Neither a complete physical-device census nor proof that an AP's report is correct |
| WPS progress reflects collector/solver stages | Runtime/JVM/emulator tests and matching source | Visible activity is not a recovered PIN/password or successful AP enrollment |
| General injection remains unresolved | [Diagnostic](../evidence/general-injection-summary.json): a send reached firmware and completed DISCARD with ACK false | Exact rejecting branch and a working firmware change remain unknown |
| Corrected terminal-harness mounts stay isolated in tested cases | [21 Linux tests](../evidence/harness-isolation-linux-20261117.txt) and [three phone exit cases](../evidence/harness-isolation-phone-20261117.json), unchanged parent/init mount tables | Not a privilege sandbox or proof about every descendant lifetime; it does not retroactively change the original harness |

The original fresh-boot trial used an earlier wrapper that left duplicate mounts and required a cleanup reboot. The write-up retains that failure and the final restoration evidence. The corrected helper is qualified separately.

The 0.5.2 release and its dated evidence remain available. Source hashes identify bytes; they do not independently authenticate experimental observations. Redacted summaries cannot replace private originals when investigating a discrepancy.
