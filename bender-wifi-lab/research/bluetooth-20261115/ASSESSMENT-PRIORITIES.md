# Bluetooth assessment priorities

Research date: 2026-11-15. Implementation and validation after the user's departure
are offline only. The existing phone observations in `FINDINGS.md` were not repeated.
The user owns nearby smart bulbs and a smart TV; exact models and firmware remain
unknown. A Roku player is not necessarily the television, and a Wi-Fi bulb is not
necessarily a BLE device.

## Useful first deliverable

An Android-native, repeatable BLE assessment notebook: discovery, observed-address
dossiers, GATT metadata, researcher-labelled state comparisons, retained failures,
and portable evidence. A scanner alone is not novel. Nordic's nRF Connect already
provides BLE discovery and service exploration; Bender's emphasis is a coherent
assessment history alongside the existing internal-radio Wi-Fi workflow.

- Android's [BLE overview](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview)
  describes native discovery, GATT service discovery and communication APIs.
- [Nordic nRF Connect](https://github.com/nordicsemi/Android-nRF-Connect/blob/main/README.md)
  is an existing mobile baseline, not a dependency bundled in this preview.
- The existing phone report found root HCI socket access but zero registered Linux
  HCI controllers. Generic BlueZ tools inside a chroot therefore remain unqualified
  for this handset. Root is neither necessary for the native BLE baseline nor
  sufficient evidence of raw controller access.

## Experiments worth pursuing after target identification

| Priority | Hypothesis and control | Evidence needed | Stop and restoration |
| --- | --- | --- | --- |
| 1. Exposure across states | A device changes its exposed service metadata after pairing or restart. Compare the same observed endpoint before/after a documented transition. | Advertisements, discovery status, service/characteristic instances and properties, Android bond state, target model/firmware and actual transition record. Cache and address rotation remain alternative explanations. | Bounded connection; close on completion, timeout, cancellation or background. No automatic reconnect. |
| 2. Authorization of a harmless operation | A documented noncritical action requires the expected pairing/application authority. Use the vendor app as the authorized control and a separate unprivileged session as the comparison. | Independently observed device response, protocol transcript and actual pairing/app-session state. A readable property bit alone proves nothing. | Select a reversible action and one owned target; stop after the preregistered attempt or any unexpected effect. Restore its original setting. Not implemented in this preview. |
| 3. Provisioning and ownership | An already claimed device rejects unauthorized Wi-Fi setup or ownership changes. Factory setup provides the control. | Exact provisioning protocol; proof of possession/application authentication; controlled test-network credentials; target configuration and router-side evidence. Do not equate changing Wi-Fi setup with gaining a LAN foothold. | Do not reset or reprovision a working device before its recovery path is known. No credentials or provisioning writes in this preview. |
| 4. Revocation | Removing a bond or transferring ownership actually revokes previous access. Compare authorized control, revoked actor and newly authorized actor. | Device and application state, test operation and independently observed effect. Android's local bond list alone is insufficient. | Fixed attempts, no retry loop; retain the original owner and recovery procedure. Deferred until exact devices are known. |
| 5. Update and CVE applicability | A reachable vulnerable implementation exists on the target's exact build. Affected version ranges and vendor backports are the control. | Model, firmware, vendor advisory, affected component and reachable profile. Age and Bluetooth specification version are insufficient. | Begin with identification and documented checks; no speculative exploit spray or firmware flashing. |

The strongest plausible cross-radio research target is **Bluetooth-assisted Wi-Fi
provisioning and ownership enforcement**. This is a research hypothesis, not a
finding about the user's devices. Espressif's [protocomm documentation](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-reference/provisioning/protocomm.html)
provides a concrete example of BLE/SoftAP transport with application-layer security
options. It does not establish that these bulbs or this TV use ESP-IDF, expose an
insecure mode, disclose passwords, or permit access to the LAN.

## What this preview actually implements

- Explicit 15-second Android BLE discovery, bounded to 256 observed addresses per
  run. Updates to existing addresses remain accepted at capacity. Raw advertisement
  bytes are bounded to 1,650 bytes per latest sighting. Android scanning can send
  scan requests; it is not described as RF-passive capture.
- Explicit GATT service discovery on a selected address, up to 20 seconds, with
  bounded metadata. No characteristic value reads, writes, notification subscription,
  pairing request, unpairing, keyboard input, credential recovery or exploit module.
  Android can still show a pairing prompt during discovery; app cancellation does
  not undo a bond created through an OS prompt.
- Android bond state and GATT properties are retained as observations, not treated
  as proof of link encryption or application authorization. Android's GATT cache
  may affect results. A fresh API callback is not independent over-the-air evidence.
- Room encounter history, snapshots, per-address notes and explicit comparisons.
  Names never merge identities; rotating addresses remain separate. User stage
  labels are not verified state changes. Instance/handle changes are metadata
  differences, not security conclusions.
- Foreground-only ownership coordinated with Wi-Fi operations. Wi-Fi privacy
  preparation must be restored first because it disables Bluetooth. Bluetooth
  does not enable radios or alter Wi-Fi privacy controls automatically. Android
  controls Bluetooth identity; this module does not claim anonymity.
- Android 12+ Nearby devices permission; Android 11 location permission/services.
  The `neverForLocation` declaration avoids collecting location on Android 12+
  but can filter some beacon types. This is a documented coverage limit, not a
  neighborhood-wide sniffer or complete Bluetooth Classic inventory.
- Local metadata export excludes names, addresses, raw advertisements, notes and
  arbitrary error text. UUIDs, timestamps and other metadata can still identify
  devices. Export processes one saved payload at a time on a worker dispatcher,
  stops at 1,000 operations or an 8 MiB output budget, and reports included/available
  counts and whether a limit was reached. Export is not an anonymity guarantee.

## Later phone qualification

1. Install the exact packaged APK, preserving the Wi-Fi data. Confirm version and
   root-independent Bluetooth permission and settings paths.
2. Identify one owned bulb by its label/app model and firmware. If it only advertises
   during setup, use a documented temporary discovery state; avoid factory reset.
3. Run one discovery window, confirm the intended device independently, then one
   metadata inspection. Verify closure and absence of an automatic reconnect.
4. Repeat only the comparison selected in advance, saving target and control state.
   Test backgrounding, cancellation and Bluetooth-off handling. Check Wi-Fi workflow
   coexistence and permission denial/revocation on the actual OnePlus build.
5. Treat TV Classic/HID support as a separate capability question. Do not infer it
   from a BLE advertisement, nearby device name, or a prior Roku capture.

No handset radio behavior or target vulnerability is validated by offline unit
tests, emulator UI tests, or synthetic screenshots.
