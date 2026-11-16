# Bender 0.5.2-device-count-preview2

Version code 15. Prepared November 15-16, 2026; installed and checked on the OnePlus on November 16. This preview is supplied alongside a report of earlier V61 radio experiments.

## Device-count correction

The previous decoder missed qualified RTS, Block ACK/request and PS-Poll relationships. It now accepts these two-address controls only when exactly one endpoint is a directly advertised AP, excludes bad/malformed or conflicting observations, and groups repetitive provenance. ACK/CTS alone cannot identify a client relationship. Relationship ordering follows observation timestamps.

Complete, directly owned BSS Load station counts are retained, including explicit zero. Missing, malformed, duplicate or nested-only fields stay unknown. The UI separates the AP's timestamped total from identified addresses, keeps discovery available when a list is nonempty, and permits up to 120 seconds of listening with early completion. Replaying the same retained capture changed 6 identified addresses to 12; this is a decoder comparison, not a new live census.

## Validation

| Check | Result | Scope |
|---|---|---|
| Host engine | 40 passed | Synthetic protocol inputs and regression cases |
| Runtime | 201 passed | Host-side runtime regressions |
| Android JVM | 76 passed | App logic |
| Android emulator | 54 passed | Final instrumentation set, including legacy vault migration |
| Earlier bundled ARM64 engine | 40 retained passes | Same synthetic cases and unchanged parser/test/runtime hashes; not rerun for preview2; no RF |
| Build, lint, packaged source/assets | Passed | Exact APK/source inputs |
| Earlier Linux installer set | 17 retained passes | Unchanged input hashes; not rerun this pass |
| Current APK handset installation/passive survey | Passed November 16 | Startup, runtime readiness, one full survey, saved inventory and restoration; [details](HANDSET-VALIDATION.md) |

The final set had no failures or skips. Earlier failures are retained in private logs: an initial CPU run used the wrong NetHunter environment, and an emulator assertion assumed a heading would remain visible after returning to a scrolled list. The environment was corrected and the UI test now explicitly scrolls to the heading before asserting it. Feature assertions were retained.

The first preview2 emulator run still had synthetic showcase data, causing an empty-inventory screenshot check to skip; PasswordInputTest also encountered a Compose runtime error. The runner was corrected to clear only the verified disposable emulator's app/test data before the full suite. The clean full run passed. The Compose error did not recur in that run; its precise cause is not established. Both the initial and final logs are retained, and no feature assertion was removed.

Existing WPS, Bluetooth, privacy, vault, screenshot and recovery policies are preserved. No new general-injection support, PMF result or live WPS success is added. The maintainer upgraded in place and preserved existing records; other users should also install as an update when signatures match. Install as an upgrade and scan again; old saved records are not silently rewritten to manufacture station totals.

See [machine-readable validation](../evidence/bender-validation.json), [offline replay](../evidence/device-count-replay.json) and [claim boundaries](CLAIMS.md).

## Publication corrections

The publication audit found two build-manifest headers missing from preview1's source ZIP because the packager skipped symlinks. Preview2 includes internal file and directory aliases as ordinary files and verifies every embedded build-input hash. The packager rejects external symlinks and alias cycles, and refuses archives missing a manifest input; five focused packaging tests pass.

The audit also found the build machine's workspace path in compiler-generated native error strings. Both helper executables were rebuilt for ARM64 and x86-64 with project-root path mapping. Protocol logic is unchanged, but these are new binaries and a new APK/version code; fresh emulator tests qualify the publication APK. The public source copy differs from the packaged preview2 source only in its sanitized checkpoint document. Use `public-source.zip` for this release. Preview1 artifacts remain retained privately.
