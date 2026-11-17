# Bender 0.5.5-latest-scan-preview1

Version code 18, built and installed November 17, 2026. The published 0.5.2
release and its assets remain unchanged.

## App changes

**Latest scan** is now the default: its networks, device counts, AP relationships, signals and AP-reported totals come from one live capture. **Saved history** keeps earlier observations and saved work. Imports cannot become current; running, failed and empty scans do not silently show older results. Selected-channel captures are labeled separately. The timestamp describes an observation window, not continuous presence or a complete device census.

The release also includes the clearer WPS collection/solver stages and qualified
device-identity hints prepared in 0.5.3/0.5.4. The bundled registry has 56,975
vendor prefixes; names, models and service clues retain their provenance and
uncertainty. Neither a progress event nor a vendor hint proves password recovery
or a particular retail device model.

Existing notes, captures, encrypted storage, recovery behavior and preview-4
choices remain. No database schema migration, new ownership prompt or exact
driver-hash lock was added. Screenshot behavior is unchanged.

## Validation

This build passed **96 JVM and 62 API35 emulator tests**, with no skips. The prior 48 engine and 205 runtime passes are retained after verifying all 36 relevant source/test inputs are unchanged; those suites were not rerun. Lint reported zero errors and 18 warnings. All 1,158 source inputs and 27 packaged assets match the APK, and its update certificate is unchanged.

The exact APK was installed over 0.5.4 on the OnePlus. Startup and both inventory tabs passed: Latest scan displayed 16 networks and 9 device addresses from the preceding capture; Saved history displayed 100 networks and 59 addresses. Both matched the database. A fresh passive scan cleared old results and showed the pending-results explanation. **USB disconnected before completion could be collected**, so the new capture's final counts and restoration remain unverified. The earlier [0.5.4 handset survey and restoration](HANDSET-VALIDATION-054.md) retain their separate build/date scope.

See the [current handset report](HANDSET-VALIDATION-055.md) and
[software record](../evidence/bender-validation-055.json). An older completed
capture retains its evidence-node-budget warning; this change does not alter
that importer limit.

## Research update

The [consolidated write-up](TECHNICAL-WRITEUP.md) adds a fresh-boot managed-interface transmission, independent Alfa reception, client observations and a two-packet appendix with original lengths, redacted bytes, FCS verification results and timing calculations. The phone transmitted internally; the separate Alfa was a receiver on the PC. Raw captures remain private.

The experimental terminal harness now uses a private mount namespace before creating runtime bind mounts. Its 21 Linux tests passed; phone probes for normal exit, exit 23 and forced termination left both caller and Android-init mount tables unchanged. The helper and its [qualification](../tools/isolated-runtime/README.md) are published separately. This corrects the research harness and is not a change to the APK's runtime implementation.

General injection remains unresolved. App-driven deauth, live WPS recovery and Bluetooth interoperability were not tested in this handset pass. The terminal experiment does not transfer those qualifications to the APK.

Install as an update when signatures match. See [installation](INSTALL.md), [claim boundaries](CLAIMS.md) and [upstream credit](PRIOR-WORK.md).
