# Published source snapshot

This directory comes from the reviewed preview2 source archive. For a complete buildable tree, use the [full source release ZIP](https://github.com/Bender1011001/Bender-WiFi-Lab/releases/download/v0.5.2-device-count-preview2/Bender-WiFi-Lab-0.5.2-device-count-preview2-public-source.zip), which includes the large `runtime-bundle/rootfs.tar.gz` omitted from Git. The [current handset report](../docs/HANDSET-VALIDATION.md) supersedes historical pending-status notes in this source snapshot.

# Bender Wi-Fi Lab

Native Android research app for the OnePlus 13 internal radios. Version
`0.5.2-device-count-preview2` is a personal preview, with Kotlin/Compose screens,
local Room history, an encrypted credential vault, a structured packet
observation engine and a serialized root supervisor. It installs alongside the
existing apps as `dev.sir.benderwifi`.

This revision fixes device inventory undercounts by decoding qualified two-address control traffic and retaining AP-advertised BSS Load station totals. The interface separates identified addresses from the AP-reported total and shows unknown when no total was captured. Targeted device discovery remains available for nonempty lists and listens for up to 120 seconds. See [device-count validation](VALIDATION-0.5.2-device-count-preview2.md).

Publication preview2 also removes build-machine paths from compiler-generated native error strings and fixes omitted source aliases in release ZIPs. Every archived build input is checked against the APK manifest. Earlier artifacts remain preserved; the subsequent November 16 handset installation/passive survey passed; active-feature qualification remains separate.

The preceding revision added a bounded three-candidate WPS PIN check to the experimental
managed-interface collector, PIN-based AP settings retrieval, automatic Wi-Fi
privacy sessions and bundled local Pixiewps solver. Scanning, directed deauth, passive handshake capture and saved
network work remain available. A private ARM64 runtime
is embedded in the APK. The prior
review fixes are retained. Preview 4 removes the exact driver hash lock and target
capture-age/ownership gates, enables screenshots, and simplifies encrypted
credential reveal/copy. See [REVIEW.md](REVIEW.md), [VALIDATION.md](VALIDATION.md)
and the [root, privacy and channel investigation](research/ROOT-PRIVACY-CHANNELS.md).
Preview 5 fixes checksum-failure handling, advertised security classification,
legacy WPA ciphers, typed evidence export, raw-capture retention and startup
recovery awareness. The preview 4 policy choices remain in place.
Preview 6 corrects Android shell line endings, bottom navigation insets and the
temporary decoder-output limit exposed during OnePlus 13 testing. See the exact
handset scope and limits in [VALIDATION.md](VALIDATION.md). Earlier artifacts remain
in `dist/`. A passive scan does not qualify deauth, PMF enforcement or password recovery.

The separate **Bluetooth** workspace is preserved: bounded BLE discovery,
observed-address dossiers, encounter history, notes, GATT service metadata,
before/after comparisons and metadata export. Bluetooth uses Android APIs and
does **not** require root or the Wi-Fi runtime. Offline tests do not establish
handset Bluetooth interoperability or any target vulnerability. See the
[assessment priorities and phone qualification plan](research/bluetooth-20261115/ASSESSMENT-PRIORITIES.md).

## Network-first Wi-Fi workflow

The start screen is **Networks**. One scan surveys available bands and saves APs
and observed device addresses. Each AP shows a timestamped AP-reported station total when captured, plus the number of identified addresses; an unavailable total is unknown rather than zero. Selecting it opens actions, devices and saved work. **Devices > Look for devices** listens on that AP's channel for up to 120 seconds, with **Finish & save** available early. Unlinked devices have a separate list. Counts are saved observations, not a live router census. **Tools** holds setup, imports and the full
capability catalog. Unsupported modules explain what is missing.

See the historical [network workflow coverage](research/network-workflow-20261115/FEATURE-COVERAGE.md)
and current [privacy and WPS implementation](research/privacy-wps-20261115/IMPLEMENTATION.md).
WPA wordlist cracking and general injection remain unavailable.

## WPS / Pixie Dust

Select a saved 2.4 or 5 GHz network → **WPS · Pixie Dust** → **Collect and run
Pixie Dust**. The app creates a temporary managed interface, checks the selected
AP in a fresh scan, collects one WPS exchange and runs the local solver. If it
verifies a PIN, it starts one separate credential request. The root supervisor
removes the temporary interface and restores the privacy session. The handset
has only demonstrated creation and removal of the second interface so far;
actual association, WPS collection, PIN retrieval and restoration remain to be
tested on the phone and owned AP. Use the screen's Restore Wi-Fi action if
recovery is pending.

**Check 3 common PINs** is a separate experimental action for the selected AP.
It tests `00000000`, `12345670` and `11111115`, with at most one WPS exchange
per candidate. It advances only after the private supplicant confirms an
authenticated PIN hash mismatch. A timeout, missing reply, target change or
setup lock stops the run; interrupted transmissions are never replayed. If the
AP supplies WPS settings, the PIN and password are encrypted in Vault. These
three examples are not an exhaustive or model-specific factory PIN database.
For a factory PIN, first inspect the device label and local router admin page.
WPS advertisements identify capabilities and sometimes a model or lock state,
but do not broadcast the PIN. This action has no live handset qualification.
For the owned WNC-CR200A, [Verizon's manual](https://www.verizon.com/support/pdf/user_guide/Verizon-Internet-Gateway-WNC-CR200A-User-Manual-v2.pdf)
says the current Gateway PIN is shown under Basic → Wi-Fi → Wi-Fi Protected
Setup. The router may change that value; it should not be inferred from its MAC.

The same action is in Tools. Offline import remains available: import one
Reaver-style log or six-field JSON file. The offline solver needs no root or
Internet access.
It independently verifies both WPS hashes before saving a recovered PIN in the
encrypted Vault and linking the result to the selected network. Raw inputs and
solver output are kept only in memory; your original imported file is unchanged.

Searches stop after 120 seconds, on Cancel, or when the screen backgrounds. A PIN
is not a Wi-Fi password. A failed or timed-out search is not proof of security.
Live collection and AP settings retrieval are experimental. The app records PIN
verification, AP-provided settings and restoration as separate results. It does
not claim that a password was tested by joining the AP. A normal WPA handshake
or WPS beacon cannot replace the six WPS fields.

## Bluetooth quick start

Install `dist/Bender-WiFi-Lab-0.5.2-device-count-preview2.apk`, then tap **Bluetooth**
in the Wi-Fi header. Grant Nearby devices permission and enable Bluetooth in
Android settings. **Discover BLE** runs for 15 seconds; select your observed bulb
or TV to save notes or **Connect & inspect** its GATT metadata for up to 20 seconds.
Leaving the screen stops the operation. Saved Wi-Fi privacy preparation must be
restored first; the app does not silently change your radio settings.

Inspection does not read characteristic values, write settings, recover passwords,
request pairing, send keyboard input or run CVEs. The Android stack may show a
pairing prompt. Bond state and read/write property flags do not prove authorization
or link encryption. Metadata may come from Android's cache. Research stage labels
are your notes, not verified state transitions. Select an inspection as a baseline
to compare another saved inspection of the same observed address.

This preview covers BLE, not complete Classic Bluetooth discovery. Android can
filter some beacon types because the app does not derive location on Android 12+.
Rotating addresses remain separate; matching names do not merge identities.
Discovery can transmit scan requests and is not a promiscuous packet capture.

## Wi-Fi quick start

1. Install the APK above. In **Tools**, press
   **Install runtime** and grant root. Setup runs offline, checks its image and
   executables, and preserves NetHunter. Allow about 1 GB of free storage for
   setup. Existing NetHunter users can try **Check radio** first. The radio backend
   targets the OnePlus `qca_cld3_peach_v2` interface. Other driver binaries may be
   tried; required runtime capabilities still apply and recovery is not guaranteed.
2. Press **Check radio**, then return to **Networks → Scan networks & devices**. Select an AP
   to open **Actions, Devices, Saved work and Details**. A scan temporarily switches the internal radio into monitor mode,
   then restores saved state. Discovery shows observed clients, not a guaranteed
   census of every associated device. JSON imports and saved dossiers work offline.
3. Press **Capture handshake** for a 120-second capture on that AP's channel.
   Reconnect an owned client after the app says **Listening**. Captures, separate
   EAPOL exchanges and results are saved in that network's workspace. Inspect the
   observed messages and export the PCAP there. **Finish & save** ends listening
   early and runs analysis; **Cancel** stops without starting that analysis.
   **Evidence** also offers private PCAP export for saved captures with no AP
   record or a failed/cancelled session. Message coverage does not verify
   a password or promise a crackable capture. This preview does not crack WPA keys.
4. Select **Devices** within a network, choose a device, then press
   **Deauth selected device**. Saved and imported
   selections work without a capture-age deadline or ownership checkbox. Check the
   addresses and frequency; **Refresh AP / client evidence** is optional. Use only
   where authorized. Preparation now runs automatically: a journal records the
   original settings, IPv4/IPv6 filters block ordinary output on wlan0, and the
   current locally administered Wi-Fi MAC is verified before transmission.
   Cellular and Bluetooth stay unchanged by default. Tools offers optional
   **Strict radio isolation**, which temporarily pauses those radios too.
   The root backend repeats privacy and current-channel checks. A test makes one
   directed request, has a 30-second cooldown and is never automatically retried.
5. Add **Network notes** in the dossier. Sessions, actions and handshake observations
   remain linked to the AP across app restarts and upgrades. Networks with the same
   name are kept separate by observed BSSID; randomized client addresses are not
   silently merged.
6. Normal completion, failure and cancellation automatically restore the session.
   If restoration cannot be verified, a persistent banner and saved journal remain.
   Use **Restore Wi-Fi** for unresolved monitor/firmware state, then **Restore normal
   connectivity** for remaining privacy settings. Interrupted transmissions are
   never replayed. A pre-existing manual profile must be restored before a new test.

Deauth and capture are separate operations. The current internal-radio path
switches modes between managed transmission and monitor capture. Simultaneous
operation and an automatic deauth-to-handshake workflow have not been validated.

The installer, preparation path and OnePlus-specific radio operations require handset
qualification. If preparation is unsupported, the UI supplies settings shortcuts
and explains the remaining checks; it does not silently bypass them.

## What is implemented

| Area | This preview |
|---|---|
| Setup | Offline bundled ARM64 runtime, image integrity checks, executable self-checks, exclusive setup lock, cancellation and clean retry after ordinary failures |
| Radar and dossiers | AP/client inventory, search and band filtering, raw observations, evidence-linked history, target-specific action catalog |
| Network workspace | Linked sessions and actions, separately grouped handshake exchanges, persistent notes, private PCAP export, v1-to-v2 database migration |
| Protocol observations | Advertised RSN suites, PMF and WPS attributes scoped to their BSS, separate nested-profile security evidence, radio-role client attribution, association observations, separately grouped EAPOL exchanges and decoded MLO metadata |
| Radio | Backend probe, bounded passive capture, guarded single directed management request, stop and saved-state restoration; adapted from the existing QCA supervisor |
| Identity | Local fail-closed checks before active submission, reversible preparation, no IMEI/SIM collection or external public-IP lookup |
| Vault | Encrypted ingestion and manual entry; per-record AES-GCM keys wrapped by Android Keystore; one-tap reveal/copy, no fixed timer, hide on background; legacy keys retained for migration |
| Evidence | Persistent encounters and experiments, local capture/JSON imports, private PCAP export, secret stripping and redacted metadata export |
| Research | WPS transcript state analyzer, bounded controlled-endpoint isolation probe, pinned firmware audit and experiment specifications |
| WPS | Bundled Pixiewps solver plus experimental managed-interface WPS collection and AP settings retrieval; encrypted network-linked PIN/password storage with distinct verification labels |

Validated live WPS password recovery, completed enrollment, evil-twin/SoftAP operation, availability
stress testing, PMF bypass, arbitrary injection and MLO exploitation are **not
implemented live modules**. Their catalog entries describe prerequisites and
specific evidence gaps. There is no generic command execution field in the app.

## Evidence means what it says

- Advertised WPA2/WPA3 or PMF is not proof of the negotiated client security.
  Incomplete or malformed advertisements retain partial evidence without being
  promoted into a definitive security summary.
- Driver acceptance, matching acknowledgement, observed client effect and
  restoration are separate results. No disconnect is labelled a PMF bypass.
- Saved-state restoration is different from observing managed mode. Restoration
  of Wi-Fi settings does not establish reassociation or internet connectivity.
- A privacy pass establishes the listed local checks at that moment. It is not an
  anonymity guarantee. A locally administered MAC can remain linkable. IMEI is a
  cellular identifier, not something this app changes. Root software and firmware
  are outside a guarantee that ordinary IP traffic is blocked.
- Default privacy isolation covers the host IP stack on wlan0, not all radio or
  layer-two traffic. Raw DHCP, firmware traffic, association identifiers and RF
  fingerprints can remain visible. This release does not claim hostname suppression
  or live RF isolation measurements. Offline analysis changes no radio settings.
- The three retained captures contain 1,947 frames and no association/EAPOL
  exchanges. Roku advertises PSK plus SAE and contains nested WPS advertisements;
  those are not WPS enrollment exchanges or recovered credentials.

## Build and validate

Install JDK 17 and Android SDK 35. Build or retain the verified image under
`runtime-bundle/`; [tools/BUILD-RUNTIME.md](tools/BUILD-RUNTIME.md) describes its build.
Set `ANDROID_HOME`, or create a local
`local.properties` with `sdk.dir`. Dependency versions and the Gradle wrapper
checksum are pinned in the project.

```powershell
./gradlew.bat --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python -m unittest discover -s tests/engine -p 'test_*.py'
python -m unittest discover -s tests/runtime -p 'test_*.py'
```

The APK embeds hashes of its source and test inputs. `tools/package_release.py`
checks those hashes, packaged runtime bytes and the selected validation logs before
creating a release. This detects stale local builds; it is not a reproducible-build
or signed source-attestation claim.

For instrumentation, use a dedicated emulator and an explicit `ANDROID_SERIAL`.
The legacy migration test needs a device credential and a recent actual unlock.
The current vault test needs an unlocked device but no extra prompt; it rejects
changed record IDs, ciphertext, IVs and wrapped keys. Skipped checks do not qualify
cryptography or migration.

`VALIDATION.md` records the delivered build, checks, limitations and evidence paths.
`CONTRACT.md` describes the versioned interfaces. `research/` and `engine/README.md`
describe repeatable offline work and the remaining live experiment gates.

## Storage and coexistence

Screenshots and screen recording are permitted. Credential copies are marked
sensitive for the Android clipboard; old authentication-bound entries can request
one device unlock when first opened, then migrate without changing their metadata.

All app data is local. There is no cloud dependency, app internet permission,
telemetry or backup of the vault. Captures and full inventory are private local
evidence. **Export redacted** uses schema 2 with selected typed metadata only.
Unknown fields, free text, names, secrets, raw bytes and private paths are omitted,
including in older saved records. Radio/session aliases preserve joins within
an export. Timestamps and technical details can still be identifying. **Export PCAP** deliberately preserves the raw capture,
including network identifiers and authentication traffic; it is a private evidence
export. The Android document picker controls its destination.

The bundled runtime lives at `/data/local/bender-wifi/runtime-arm64-v1`, outside
app storage. Removing the APK does not remove that root-owned runtime or legacy
radio recovery records. This preview does not overwrite a different runtime image.
Verified raw-capture duplicates are removed from the root runtime after handoff.
Failed handoffs and older versions can leave root copies. Evidence offers
**Delete raw capture** for a session (app and root copies) and **Clear runtime
capture copies** for older Bender runtime copies. Clearing can remove the only
raw copy left by a failed handoff; summaries, intent records and recovery journals
are preserved. Interrupted cleanup reports partial completion and never retries
automatically. At launch, **Recovery state unchecked** stays visible until a
fresh root-journal check succeeds; saved app sessions alone cannot clear it.

The runtime deliberately shares the existing QCA radio mutex and recovery
namespace so that the old and new apps cannot mutate the radio concurrently.
Existing apps, captures, firmware and documentation are not replaced. Recovery
journals are not credentials and never authorize automatic retransmission.

Project code is GPL-3.0-or-later; see `COPYING` and `runtime/ATTRIBUTION.md`.
