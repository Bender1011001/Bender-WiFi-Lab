# Bluetooth feasibility checkpoint — 2026-11-15

- Objective: determine what the user's OnePlus 13 can actually do with Bluetooth and which demonstrations fit their own TV and bulbs.
- User clarification: interested in known Bluetooth CVEs and keyboard input. CVEs commonly affect multiple releases of an implementation; a specific stack does not imply a single affected version. No TV or bulb model/firmware supplied yet.
- Completed: read-only phone inspection and native Linux HCI socket/controller enumeration. Findings and sources are in `FINDINGS.md`; probe source is `hci_socket_probe.c`.
- Verified on connected CPH2655: Android 16; root available; Bluetooth ON; Classic/LE features advertised; GATT, HID_DEVICE and HID_HOST services listed. Shell HCI socket returned errno 13; root opened it but enumerated zero Linux HCI controllers. These are capability observations, not a working HID connection or security finding.
- Existing cached capture has a BTSnoop header; only its 16-byte file header was read. No capture contents copied or interpreted.
- Probe built with NDK 27.1.12297006, API 30, `-Wall -Wextra -Werror -O2`; compilation and both runs completed. Remote probe removed successfully. No Bluetooth setting change, discovery, pairing, keystroke, exploit attempt, firmware change, or app installation performed.
- Workspace drift: project checkpoint read at start referred to preview5; installed app later reported preview6/code8. This investigation did not reconcile or alter ongoing app work.
- Next: exact TV and bulb model/firmware, then match vendor advisories and profile support. Public HID API pairing demo and BLE observation are feasible candidates, not yet tested. No general pairing bypass or network access established.
