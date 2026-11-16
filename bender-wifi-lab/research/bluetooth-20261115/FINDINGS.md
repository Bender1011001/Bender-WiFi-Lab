# OnePlus 13 Bluetooth feasibility

Inspection date: 2026-11-15 (America/Los_Angeles). This report summarizes the command results observed in this session; it is not an independent capture of all raw command output.

## Phone observations

| Observation | Result | Meaning and limit |
| --- | --- | --- |
| Product / OS | CPH2655 / Android 16 | The connected physical handset was inspected. |
| Build fingerprint | `OnePlus/CPH2655/OP5D55L1:16/BP2A.250605.015/V.R4T3.26073a0-1df561-1f78cb:user/release-keys` | Identifies the tested build, not every OnePlus 13. |
| Reported security patch | `2026-06-01` | A reported OS patch level; not a complete component audit. |
| Root | `uid=0`, `u:r:magisk:s0` | Root worked for this diagnostic. |
| Bluetooth state | Enabled / ON | State was not changed. |
| Package-manager features | `android.hardware.bluetooth`, `android.hardware.bluetooth_le` | Classic and LE support advertised; no RF reception test performed. |
| Bluetooth service dump | GATT, HID_DEVICE, HID_HOST; GattService, HidDeviceService, HidHostService | Supports investigating normal BLE/GATT and paired HID use. Service presence does not prove a successful TV connection or HID registration. |
| Vendor component | `ro.bluetooth.library_name=libbluetooth_qti.so`; Qualcomm Bluetooth HAL library filenames present | Vendor implementation is present. Filenames alone do not prove its complete transport/call path or chipset identity. |
| Kernel | `CONFIG_BT=m`; HCI/RFCOMM/L2CAP/SCO entries in `/proc/net/protocols` | Linux Bluetooth protocol support exists. |
| Linux controllers | `/sys/class/bluetooth` empty; root HCIGETDEVLIST returns zero | The active Android radio was not exposed as a registered Linux HCI controller during this check. |
| Existing log header | `62 74 73 6e 6f 6f 70 00 00 00 00 01 00 00 03 ea` | A cached `.cfa` file has a BTSnoop header. No payload, completeness, current logging configuration or decoder compatibility was checked. |

The read-only `hci_socket_probe.c` opens an HCI socket and requests the kernel's controller list. It does not bind to a controller, send HCI commands or transmit ACL packets. Built with Android NDK 27.1.12297006 for ARM64/API30 with warnings treated as errors.

Observed output:

```text
shell user:
hci_raw_socket=error errno=13 (Permission denied)
root user:
hci_raw_socket=opened
hci_controller_count=0
```

The temporary executable was removed from `/data/local/tmp` after the check. No scan, pairing, HID input, CVE attempt or radio configuration change was performed.

## What the results support

- A native Android BLE observation feature is plausible using the public scanner and permission APIs. Reception of the user's bulbs remains untested, and visibility depends on the particular model and operating state.
- A normal paired keyboard demo is plausible through Android's HID device API. The phone lists that profile, but application registration, actual pairing, TV HID host support and delivered input remain untested. Android documents that registering a HID application temporarily disables the HID host service and that `connect` targets a paired device; lifecycle cleanup matters.
- HCI logging can help observe the phone's own host/controller exchanges. It is not an over-the-air sniffer for every nearby Bluetooth connection.
- Root can open a Linux HCI socket here, but no controller is registered through that route. Opening a socket alone does not establish an injection path into the active controller. The vendor HAL route needs separate evidence; no attempt was made to take over its transport.

## Corrections to the original architecture proposal

`PF_BLUETOOTH` is a socket family, not Netlink. HCI is the interface between a host and its local controller; a radio peer does not receive arbitrary host HCI packets unchanged. The Bluetooth controller manages Link Manager Protocol operations. Shared Wi-Fi/Bluetooth coexistence resources do not by themselves demonstrate a shared vulnerable parser or a path between a Bluetooth peer and the LAN. Changing a device class or local authentication field does not by itself establish that a remote device trusts it. These are separate hypotheses requiring target-specific evidence.

## CVE ranges and update claims

A CVE can affect many releases of the same implementation, sometimes every release from the bug's introduction until a fix. A bug may have been introduced after older releases; fixes can also be backported without changing the major version. Affected ranges, vendor patch status and whether the vulnerable feature is reachable all matter. Sharing a Bluetooth specification version does not imply sharing the same implementation or bugs. An unsupported version absent from a bulletin is not thereby proven safe.

For example, the December 2023 Android bulletin lists fixes for CVE-2023-45866 across Android 11, 12, 12L, 13 and 14. This illustrates a multi-release issue; it is not evidence that an unidentified TV or bulb contains the affected code. No applicable target CVE was established in this investigation.

The blanket claim that these products never update is unsupported. Roku documents automatic software updates. WiZ documents automatic firmware application as the default, with conditions including cloud connectivity and software-off state; users can disable automatic application for lighting. The actual targets' settings, available updates, support status and installed firmware still need checking.

Prior workspace evidence identifies a WiZ bulb and a Roku Express 4K streaming player. It does not establish the actual TV model or the bulb's Bluetooth hardware. WiZ documents Bluetooth-assisted discovery on supported lights for a limited period after power-on; that does not establish HID support.

## App integration status

The inspected Wi-Fi Lab manifest had no Bluetooth permissions. Its existing preparation workflow disables Bluetooth, and the identity preflight expects it off. A Bluetooth experiment needs a deliberate session flow that does not fight that Wi-Fi preparation state. No app changes were made. The installed app reported preview6/code8 during this inspection, whereas the checkpoint read at the start still described preview5; concurrent delivery work was not reconciled here.

## Primary sources consulted

- [OnePlus 13 specifications](https://www.oneplus.com/us/13/specs) — advertised Bluetooth 5.4; not proof of raw-packet capabilities.
- [Android Bluetooth architecture](https://source.android.com/docs/core/connect/bluetooth) — stack and vendor HAL separation.
- [Android BluetoothHidDevice](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice) — HID registration, lifecycle and paired-host connection API.
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) — scan, advertise and connect permissions.
- [Android Bluetooth debugging](https://source.android.com/docs/core/connect/bluetooth/verifying_debugging) — HCI snoop logging scope and configuration.
- [Bluetooth HCI functional specification](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-60/out/en/host-controller-interface/host-controller-interface-functional-specification.html) — host/controller boundary and controller protocol operations.
- [December 2023 Android security bulletin](https://source.android.com/docs/security/bulletin/2023-12-01) — CVE-2023-45866 fixes across several Android releases.
- [Roku software updates](https://support.roku.com/article/update-the-software-on-your-streaming-device) and [Roku security update history](https://support.roku.com/article/roku-os-security-updates) — update mechanism and published CVE fixes.
- [WiZ automatic/manual firmware application](https://faq.wizconnected.com/hc/id/7-wiz-v2/faq/1056-auto-apply-firmware-update-or-manually-apply/) — default automatic application and operating conditions.
- [WiZ getting started](https://faq.wizconnected.com/hc/en/7-wiz-v2/faq/767-smart-lighting---how-to-get-started/) — model-dependent Bluetooth-assisted discovery and Wi-Fi setup.
