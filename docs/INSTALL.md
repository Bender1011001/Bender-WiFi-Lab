# Install Bender Wi-Fi Lab preview

This is a rooted-phone research preview, package `dev.sir.benderwifi`, version `0.5.2-device-count-preview2` (15). The exact APK passed build, lint, emulator and package checks, then an in-place OnePlus installation, runtime check and full passive survey on November 16. See [handset scope and results](HANDSET-VALIDATION.md). The headline three-client experiment used earlier V61 terminal tooling.

## Download and install

1. Download [the APK](https://github.com/Bender1011001/Bender-WiFi-Lab/releases/download/v0.5.2-device-count-preview2/Bender-WiFi-Lab-0.5.2-device-count-preview2.apk). The APK includes its ARM64 runtime, so first setup needs about 1 GB free storage and no runtime download.
2. Verify its SHA-256 against [SHA256SUMS](../SHA256SUMS). On Windows, `Get-FileHash .\Bender-WiFi-Lab-0.5.2-device-count-preview2.apk -Algorithm SHA256` should return `812fd140e9368a19ea7b67517f428bce7e2c43393f61eccb604c50fa10877425`.
3. Open the APK on the phone and allow installation from that source when Android asks. It installs alongside Hijacker/NetHunter. For an existing Bender installation, install as an update; do not uninstall to resolve a signing mismatch, because uninstalling removes app data.
4. Open **Tools → Install runtime**, grant root and wait for completion. Existing compatible NetHunter users can try **Check radio** first. Then run **Check radio** and check any recovery banner before proceeding.

The Wi-Fi backend targets the OnePlus `qca_cld3_peach_v2` interface. Other driver binaries are not hash-blocked, but compatibility and restoration are not guaranteed. This APK is signed with an Android debug key and is intended as a personal research preview, not an authenticated production distribution.

## First useful workflow

Open **Networks → Scan networks & devices**, then select an AP. Scanning temporarily changes radio mode and can interrupt the phone's Wi-Fi connection. The dossier contains **Actions, Devices, Saved work and Details**.

- **Devices → Look for devices** listens on the selected channel for up to 120 seconds; **Finish & save** ends it early. AP-reported station totals and identified addresses are distinct, timestamped observations. Scan again after this update to populate newly decoded fields.
- **Capture handshake** listens for authentication traffic. Reconnect an owned client after the app says **Listening**. The app saves the capture and separately identified exchanges under the selected network. Message coverage alone does not establish a usable password-checking capture. WPA wordlist cracking is not implemented in this preview.
- Directed deauth is a separate action for a selected observed device. Use only where authorized. Its one-request behavior, cooldown, outcome record and restoration are separate from passive capture; no automatic retry or combined deauth/capture success is promised.
- Add network notes and revisit **Saved work** or **Evidence** to inspect or export results. Raw PCAPs and ordinary evidence can contain identifiers; the public launch figures use synthetic data instead.

Privacy preparation runs automatically for applicable active operations and retains a restoration journal. It verifies a locally administered Wi-Fi MAC and applies ordinary IPv4/IPv6 output filtering on the testing interface. Cellular/Bluetooth remain unchanged by default; optional strict isolation pauses them. These controls are not an anonymity guarantee and do not prevent RF fingerprinting or every driver-originated transmission.

## If setup or restoration fails

Read the specific **Check radio** or installer error before trying another operation. A root denial, missing capability and an installation failure are different problems. Save the app's result locally; do not publish a raw log without checking identifiers and secrets.

Use **Restore Wi-Fi** for unresolved monitor/firmware state, then **Restore normal connectivity** for remaining privacy settings. Keep the recovery banner unresolved until readback succeeds. Do not repeatedly rerun an operation whose submission outcome is unknown. Recovery is tested for specific cases, not guaranteed for every driver/build.

## Source

The [publication source archive](https://github.com/Bender1011001/Bender-WiFi-Lab/releases/download/v0.5.2-device-count-preview2/Bender-WiFi-Lab-0.5.2-device-count-preview2-public-source.zip) contains the app, runtime source, tests, pinned native dependency sources and build instructions. It replaces the private workspace checkpoint. Preview2 fixes source packaging: internal aliases omitted by the earlier packager are included and every build input is verified against the APK's embedded manifest. It also rebuilds native helpers with neutral compiler-generated file paths. Read the archive README for the development build steps and dependency versions.
