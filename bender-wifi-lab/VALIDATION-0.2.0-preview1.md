# Validation — 0.2.0 preview

Validated offline and on a disposable Android 15/API 35 emulator on 2026-11-14.
The physical OnePlus was absent from ADB throughout this implementation pass.
No new radio transmission, router change or physical-handset installation occurred.
The user explicitly chose to continue offline; handset qualification is deferred.

## Delivered build

- Package `dev.sir.benderwifi`, version `0.2.0-preview1` (code 3), min SDK 30, target 35.
- APK and source archive are in `dist/`; exact hashes are in `dist/manifest.json`.
- Personal preview signed with the existing Android debug key. Production signing
  and byte-for-byte reproducible builds are not claimed.
- Gradle assembleDebug, assembleDebugAndroidTest, testDebugUnitTest and lintDebug
  passed. Lint: 0 errors, 5 warnings, 1 information. Remaining notices concern the
  target API, explicit data extraction rules, static context fields, using allocatable
  rather than currently usable storage, and a boxed Compose state value.
- The APK embeds a source manifest covering 78 code/build/test inputs. Packaging
  compares 16 runtime/engine assets byte-for-byte and verifies the bundled manifest
  and runtime image (18 checked assets total).
- APK size: 209,792,272 bytes. APK SHA-256:
  `d461824446b315c8efe44f07291086c45177692c86a5dcbb0d8e195a0b8d124b`.

## Final checks

All paths below are relative to `validation/product-20261114/` unless specified.

| Check | Result | Evidence |
|---|---|---|
| Protocol engine on Windows/TShark | 25 passed | `engine.log` |
| Runtime, guard, recovery and installer control flow | 158 passed | `runtime.log` |
| Android JVM | 29 passed | `app/build/test-results/testDebugUnitTest/` |
| Emulator data, migration, UI and packaged asset staging | 19 passed | `emulator-data-ui.log` |
| Real Android Keystore roundtrip and four tamper cases | 1 passed | `emulator-crypto.log` |
| ARM64 engine under QEMU using bundled Python/TShark | 25 passed, repeated platform checks | `arm64-engine.log` |
| Installer control flow on Linux with real flock | 10 passed, repeated platform checks | `linux-installer.log` |
| Scanner field compatibility with bundled TShark | All 22 fields available | `runtime-decoder-fields.json` |
| Build and lint | Passed | `android.log` |

The primary suites contain **232 distinct tests**, with no final failures or skips.
The additional ARM64 and Linux runs repeat existing tests on different platforms;
they are not additional distinct cases or RF experiments. `tested-build.json`
binds the emulator checks to the APK hash. Prior validation remains in
[VALIDATION-0.1.1.md](VALIDATION-0.1.1.md).

## What changed and what the checks establish

- A bundled Debian ARM64 runtime removes the existing NetHunter installation
  prerequisite. Setup verifies its APK asset, copies it into private root storage,
  verifies that copy again, checks executables, unmounts its temporary mounts and
  commits a ready marker. Existing runtimes are preserved. Tests cover tampering,
  cancellation, dead owner, competing lock, unresolved radio recovery, low storage,
  wrong architecture, self-check failure, clean retry and failed mount cleanup.
- Android instrumentation actually stages the complete pinned image from the APK,
  checks its size and tests cancellation without overwriting an existing staged
  file. Root extraction and Android SELinux behavior still need handset testing.
- Room migration retains old observations and adds network/session links. Tests
  close and reopen the database, migrate the prior six-table schema, preserve action
  timestamps and notes, and verify separation between different BSSIDs.
- The production UI is exercised with an isolated synthetic database: network
  selection, handshake dialog, client selection, deauth dialog, imported-evidence
  blocking, per-network exchange/action counts and persistent notes. It performs
  no root or radio command. Existing preparation, navigation and evidence tests pass.
- Empty Radar and Workshop screenshots were visually inspected. The screenshot
  test is restricted to a debug emulator with an empty inventory and vault, and
  restores FLAG_SECURE in `finally`. Production windows remain secure.
- Credential checks use the real Android Keystore after an actual emulator PIN
  unlock. Changed record IDs, ciphertext, IVs and wrapped keys are rejected while
  the original remains decryptable. Authentication expiry is never counted as
  evidence of tamper rejection.

## Runtime provenance

The image was built with debootstrap using authenticated Debian repositories, then
updated from bookworm, bookworm-updates and bookworm-security. It contains 153
packages; exact versions and repository metadata hashes are in
`runtime-bundle/manifest.json`. It includes Python 3.11, TShark 4.0.17-0+deb12u3,
iw 5.19-1 and libpcap 1.10.3-1. Package copyright files remain in the image.
Build instructions: [tools/BUILD-RUNTIME.md](tools/BUILD-RUNTIME.md).

Compressed image: 150,626,800 bytes. Unpacked regular-file content: 457,993,064 bytes.
Image SHA-256: `e9d161353e0bdd013530234513c895a9886e42f4f2b08e6b2f4841589e0693d1`.

An early check caught AAPT expanding and renaming the `.gz` asset. The image is now
stored as an opaque, uncompressed `rootfs.bundle` APK entry. Verification reads the
actual APK entry and hashes its bytes; the final Android staging test passes.
`emulator-packaging-failure.log` retains the original failure. The older TShark
also exposed a synthetic M2 frame missing its RSN IE. That fixture was corrected;
the engine passes with both decoder versions. This does not validate the synthetic
MIC or recover a password.

The first emulator runs occurred before credential-encrypted storage was unlocked;
those failures are retained as `emulator-data-ui-{first,second}.log`. A crypto run
without fresh authentication was skipped and retained in
`emulator-crypto-auth-expired.log`; the final crypto run has no skip.

## Remaining live qualification

- Install this exact APK on the rooted OnePlus, run offline setup and Check radio,
  and verify mount cleanup and existing-NetHunter coexistence under its SELinux policy.
- Confirm scan discovery, target capture, cancellation, process death and restoration
  against the exact loaded driver/firmware. Current transmission support remains
  pinned to the previously investigated driver binary.
- On owned hardware, separately qualify a bounded directed deauth and its observed
  effect. Driver acceptance, acknowledgement, client effect and restoration remain
  separate evidence. Historical radio results do not qualify this APK.
- Capture a real owned-client reconnect after the Listening status, confirm separate
  EAPOL exchanges, reopen its network workspace, and export/read the saved PCAP.
  A four-message observation is not password verification or proof of crackability.
- Managed transmission and monitor capture remain separate operations. No simultaneous
  TX/RX or automatic deauth-to-handshake workflow has been established. There is no
  WPA password-cracking module in this preview.

The app retains fail-closed identity checks, frequency restrictions, serialized radio
ownership and protection against automatic replay after an uncertain transmission.
A privacy check is not an anonymity guarantee. Raw PCAP export retains private
identifiers and authentication traffic; the separate redacted metadata export
omits credentials and aliases identifiers. No new live capability is claimed by
these offline and emulator checks.
