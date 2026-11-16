# Publication preview: 0.5.2-device-count-preview2

Version code 15, prepared November 16, 2026. Retains the device-count correction documented in [preview1 validation](VALIDATION-0.5.2-device-count-preview1.md).

## Changes since preview1

- Map the project root to `.` in compiler-generated native file strings. Both Pixiewps and the WPS helper are rebuilt for ARM64 and x86-64, removing the maintainer's workspace path from their binaries. No protocol logic was changed.
- Include internal file and directory aliases in source ZIPs as ordinary files. The prior packager omitted two headers listed in the APK build manifest. Reject external aliases/cycles and verify every archived build-input hash. Five focused packaging regressions passed.
- Preserve prior APK/source artifacts. This publication preview is a new version; it has not been installed on the OnePlus.

## Validation scope

Fresh host engine 40 and runtime 201 regressions passed. Android build, JVM 76 and lint passed. Fresh emulator instrumentation passed 54 tests (53 full-suite plus one separate legacy-vault case), with no failures or skips in the final set. The prior 40 ARM64 engine checks and 17 Linux installer checks are explicitly retained using unchanged-input hashes, not presented as new phone tests. No RF experiment is part of this release pass.

An initial emulator run retained the prior synthetic showcase inventory: the empty-inventory screenshot test skipped, and PasswordInputTest encountered a Compose runtime error. The runner now clears only the verified disposable emulator's app/test data before the full suite. The clean 53-test run passed without failures or skips; no feature assertion was removed. The Compose error was not reproduced in that run, and its precise cause is not established. Initial logs remain retained. Legacy vault migration passed separately.

Logs and exact test/package inputs are retained locally under `validation/publication-preview2-20261116/`. Public evidence summaries and reviewed downloads are prepared separately in the publication workspace. Existing network inventory/captures were not modified. The user is sleeping and charging the phone; fresh handset installation/live survey remain pending.
