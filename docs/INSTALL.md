# Install Bender Wi-Fi Lab preview

This is a rooted-phone research preview, package `dev.sir.benderwifi`, version `0.5.5-latest-scan-preview1` (18). The exact APK passed build, lint, emulator and package checks, then an in-place OnePlus installation and inventory-tab/count checks on November 17; a new survey started, but USB disconnected before its final outcome could be collected. See [handset scope and results](HANDSET-VALIDATION-055.md). The directed-transmission findings use separate terminal tooling; they are not active-feature qualification of this APK.

## Download and install

1. Download [the APK](https://github.com/Bender1011001/Bender-WiFi-Lab/releases/download/v0.5.5-latest-scan-preview1/Bender-WiFi-Lab-0.5.5-latest-scan-preview1.apk). The APK includes its ARM64 runtime, so first setup needs about 1 GB free storage and no runtime download.
2. Verify its SHA-256 against [SHA256SUMS](../SHA256SUMS). On Windows, `Get-FileHash .\Bender-WiFi-Lab-0.5.5-latest-scan-preview1.apk -Algorithm SHA256` should return `db4c4d45ac487a9172d33d63c44452dd4fdc19a5c8b42c427051cc0859673649`.
3. Open the APK on the phone and allow installation from that source when Android asks. It installs alongside Hijacker/NetHunter. For an existing Bender installation, install as an update; do not uninstall to resolve a signing mismatch, because uninstalling removes app data.
4. Open **Tools → Install runtime**, grant root and wait for completion. Existing compatible NetHunter users can try **Check radio** first. Then run **Check radio** and check any recovery banner before proceeding.

The Wi-Fi backend targets the OnePlus `qca_cld3_peach_v2` interface. Other driver binaries are not hash-blocked, but compatibility and restoration are not guaranteed. This APK is signed with an Android debug key and is intended as a personal research preview, not an authenticated production distribution.

## First useful workflow

Open **Networks → Scan networks & devices**. **Latest scan** shows only that capture; **Saved history** contains older networks and devices. Select an AP from either view. Scanning temporarily changes radio mode and can interrupt the phone's Wi-Fi connection. The dossier contains **Actions, Devices, Saved work and Details**.

- **Devices → Look for devices** listens on the selected channel for up to 120 seconds; **Finish & save** ends it early. AP-reported station totals and identified addresses are distinct, timestamped observations. A targeted capture becomes the latest snapshot and is labeled **Selected-channel capture**. Run a full scan again for wider coverage.
- Select a device to see its best available vendor/name/type clue and the evidence behind it. Unknown or conflicting identities remain visible; an observed address is not necessarily a unique physical device.
- **WPS · Pixie Dust** now shows actual collector/solver stages, elapsed time, deadlines and stall information. A progress event does not prove a successful recovery. Live WPS recovery remains experimental.
- **Capture handshake** listens for authentication traffic. Reconnect an owned client after the app says **Listening**. The app saves the capture and separately identified exchanges under the selected network. Message coverage alone does not establish a usable password-checking capture. WPA wordlist cracking is not implemented in this preview.
- Directed deauth is a separate action for a selected observed device. Use only where authorized. Its one-request behavior, cooldown, outcome record and restoration are separate from passive capture; no automatic retry or combined deauth/capture success is promised.
- Add network notes and revisit **Saved work** or **Evidence** to inspect or export results. Raw PCAPs and ordinary evidence can contain identifiers; the public launch figures use synthetic data instead.

Privacy preparation runs automatically for applicable active operations and retains a restoration journal. It verifies a locally administered Wi-Fi MAC and applies ordinary IPv4/IPv6 output filtering on the testing interface. Cellular/Bluetooth remain unchanged by default; optional strict isolation pauses them. These controls are not an anonymity guarantee and do not prevent RF fingerprinting or every driver-originated transmission.

## If setup or restoration fails

Read the specific **Check radio** or installer error before trying another operation. A root denial, missing capability and an installation failure are different problems. Save the app's result locally; do not publish a raw log without checking identifiers and secrets.

Use **Restore Wi-Fi** for unresolved monitor/firmware state, then **Restore normal connectivity** for remaining privacy settings. Keep the recovery banner unresolved until readback succeeds. Do not repeatedly rerun an operation whose submission outcome is unknown. Recovery is tested for specific cases, not guaranteed for every driver/build.

An older oversized capture may show **Saved observations need attention** after restart. Preserve its raw evidence and inspect the recorded import error; repeating an active operation will not fix an import-size limit. This warning was retained in the November 17 check, while the new passive capture imported successfully.

## Source

The [publication source archive](https://github.com/Bender1011001/Bender-WiFi-Lab/releases/download/v0.5.5-latest-scan-preview1/Bender-WiFi-Lab-0.5.5-latest-scan-preview1-public-source.zip) contains the app, runtime source, tests, pinned native dependency sources and build instructions. Every build input is checked against the APK manifest. The complete source ZIP includes the bundled runtime image omitted from Git, pinned dependency sources, notices and build instructions. The current validation and handset reports distinguish software coverage from live hardware observations.
