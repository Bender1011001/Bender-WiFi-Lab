# Validation - 0.2.0-preview4

Offline source review, implementation and disposable Android 15/API 35 emulator
validation on 2026-11-15. No physical phone, router, RF transmission, Internet
lookup or runtime-image update was performed. Earlier evidence is retained in
[VALIDATION-0.2.0-preview3.md](VALIDATION-0.2.0-preview3.md).

## Exact build

- Package `dev.sir.benderwifi`, version `0.2.0-preview4`, code 6, min SDK 30/target 35.
- APK: 210,317,304 bytes; SHA-256 `83797f686ff1fd80a8b015a28fd26deef6fc6205ef84444eb1f99774ab29af64`.
- Personal preview using the existing Android debug signing key. Earlier APKs and
  versioned manifests remain preserved; this is not production signing.
- Offline assembleDebug, assembleDebugAndroidTest, testDebugUnitTest and lintDebug
  pass. Lint has 0 errors, 5 warnings and 1 information, unchanged counts.
- All 89 embedded source/build/test hashes match. All 16 runtime source assets
  match byte-for-byte, and the runtime manifest/image match too (18 assets total).

## Final checks

Paths are relative to `validation/policy-20261115/` unless noted.

| Check | Result | Evidence |
|---|---|---|
| Protocol engine | 25 passed | `engine.log` |
| Runtime/supervisor/installer/privacy/channel checks | 171 passed | `runtime.log` |
| Android JVM | 38 passed | `app/build/test-results/testDebugUnitTest/` |
| Exact-APK data/UI, screenshot, vault workflow and current-key crypto checks | 29 passed | `emulator-data-ui.log` |
| Exact-APK legacy authenticated credential migration | 1 passed | `emulator-crypto.log` |
| Source/assets comparison | 89 source inputs / 18 assets match | `package.log` |
| ARM64 engine and Linux installer platform repeats | Prior 25 + 17 passes retained; inputs unchanged | `retained-checks.json`, `arm64-engine.log`, `linux-installer.log` |

**264 distinct current-pass tests**, zero final failures or skips. Platform repeats
are retained evidence, not newly rerun or extra distinct tests. Their relevant
input hashes were compared with preview 3 before reuse. `tested-build.json` binds
all 30 final instrumentation checks to the exact APK hash above.

## Changed behavior exercised

- A different driver hash reaches the existing capture/lab worker and keeps the
  normal restoration and uncertain-result behavior. This uses controlled command
  mocks, not another physical driver or proof that arbitrary builds recover.
- Saved/imported selections no longer expire or require target-capture files or
  an ownership checkbox. Tests retain explicit MAC/frequency consistency, current
  PHY restrictions, capability/privacy checks, cancellation, owner-process death,
  single submission and no automatic retry.
- Screenshots are enabled on the actual Activity. Empty Radar/Workshop captures
  were inspected. The vault UI saves, reveals and copies a synthetic secret
  without another prompt; the clipboard is marked sensitive and backgrounding
  removes the revealed value. No real credential was entered or exported.
- Real Android Keystore tests verify current-key roundtrip and rejection of
  altered record ID, ciphertext, IV and wrapped key. Legacy tests follow an actual
  emulator device unlock, preserve metadata while migrating, retain the original
  key, preserve corrupt encrypted records and prevent a deleted row being recreated.
- No fixed reveal timer remains. New keys created on API 35+ require the phone
  to be unlocked; earlier Android versions retain the app's device-lock check
  without the Android 12-14 unlocked-key deletion issue described in SDK sources.
  Only API 35 was exercised here.

The first emulator pass had two test-fixture failures: an obsolete expectation
that screenshots must be blocked, and replacing an Activity's existing Compose
content through the wrong test-rule API. Both were corrected. Original failures
are preserved in `first-emulator-data-ui.log`. The final wording rebuild says
Built for OnePlus 13 rather than implying this APK was handset-tested; intermediate
build hashes and successful checks remain separate from the final hash.

## Investigation and remaining hardware questions

[ROOT-PRIVACY-CHANNELS.md](research/ROOT-PRIVACY-CHANNELS.md) distinguishes app policy
from kernel privilege and regulatory state, Android MAC randomization from anonymity,
and retained recovery procedures from guarantees. Kernel source was retrieved only
from cached local Git objects; missing objects were not downloaded. Android API 35
SDK source and local QCA driver source were inspected. Hashes are in
`kernel-source.json` and `policy-source-hashes.json`.

Source confirms this implementation uses root and a privileged management-frame
interface. No rootless path or unprivileged handset transmission was demonstrated.
Other driver hashes are allowed to try the same QCA interface, not claimed to work.
The privacy and conservative channel checks remain enabled. Physical OnePlus
installation, actual Developer Options behavior, radio outcomes and restoration
on other builds are still unqualified. There was no new independent subagent
review of preview 4; the review loop recorded in REVIEW.md belongs to preview 3.

The bundled ARM64 image is unchanged: 153 packages, 150,626,800 compressed bytes,
SHA-256 `e9d161353e0bdd013530234513c895a9886e42f4f2b08e6b2f4841589e0693d1`.
