# Bender Wi-Fi Lab

I built Bender Wi-Fi Lab for my own rooted OnePlus 13. It's an Android app for keeping Wi-Fi surveys, device observations, captures and notes together on the phone. I’m sharing the personal preview as I use it.

[Preview release 0.5.5](https://github.com/Bender1011001/Bender-WiFi-Lab/releases/tag/v0.5.5-latest-scan-preview1) · [Install or update](docs/INSTALL.md) · [Browse the app source](bender-wifi-lab/) · [Release notes](docs/RELEASE-NOTES.md)

## Working with a survey

**Latest scan** lists the networks and device addresses observed during the selected capture window. Open a network to see its access points, observed devices and saved work. An observed-address count depends on what the radio heard. An access point’s reported station count is displayed separately.

**Saved history** holds earlier encounters, imports and notes. Starting a new survey clears the current results while it runs. Opening history lets you consult past observations without putting those devices back into the current scan. The app keeps data locally and uses an encrypted vault for stored credentials.

![Network inventory in the earlier preview with synthetic data](figures/app-networks-synthetic.png)

*The screenshot shows synthetic networks in the 0.5.2 emulator preview. The current build and its validation are documented below.*

## Current preview and validation

Version `0.5.5-latest-scan-preview1` (version code 18) uses a development signature and requires root for the Wi-Fi backend. The release includes the APK, corresponding source archive and checksums. The [installation guide](docs/INSTALL.md) covers in-place updates and signature compatibility.

On November 17, the exact APK was installed over 0.5.4 on a rooted CPH2655 running Android 16 build `CPH2655_16.0.8.301(EX01)`. Both inventory views matched the stored counts. A new passive survey cleared the old results. USB disconnected before the finished survey and final restoration could be observed. The same build passed 96 JVM and 62 emulator tests. The [handset report](docs/HANDSET-VALIDATION-055.md) and [app evidence map](docs/CLAIMS.md) give the observations and limits for this preview.

## Project records

[Source and attribution](docs/ATTRIBUTION.md) · [Runtime sources](docs/RUNTIME-SOURCES.md) · [Repository manifest](manifest.json) · [Original release manifest](https://github.com/Bender1011001/Bender-WiFi-Lab/blob/v0.5.5-latest-scan-preview1/manifest.json)

The OnePlus 13 radio measurements from the separate terminal experiment are maintained in the [internal Wi-Fi research repository](https://github.com/Bender1011001/oneplus13-internal-wifi-research). This project's handset reports identify which behaviors were observed in the APK.
