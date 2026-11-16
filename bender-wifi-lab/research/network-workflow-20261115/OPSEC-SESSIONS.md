# Operation-specific privacy sessions

Design investigation, 2026-11-15. The existing protection policy has **not** been
changed by the network-navigation release. Changing that policy needs separate
implementation and hardware verification; a UI label is not an isolation boundary.

## Current implementation

`runtime/identity_guard.py` and `ui/IdentityPreflight.kt` currently require all nine
checks, including airplane mode, mobile data off, Bluetooth off, a locally
administered WLAN address and acceptable IPv4/IPv6 exposure. A verified firewall
lock can satisfy address/route exposure checks, but does not waive the airplane
mode, mobile data or Bluetooth checks. `runtime/privacy_profile.py` journals four
settings and installs IPv4/IPv6 OUTPUT/FORWARD hooks. Restoration is explicit.

That policy is deliberately broad. It does not distinguish capture, management
frames and connected IP experiments, and it does not establish anonymity.

## Better user flow

**Choose a test → review the short exposure summary → Start test → automatic
cleanup and verified restoration.** Only exceptions require attention. The summary
names the operation's actual interface, identity and connectivity, with unknown
facts shown as unknown. A strict all-radio profile remains an optional mode.

| Operation | Appropriate preparation to validate |
|---|---|
| Saved-file analysis | No radio or connectivity changes |
| Receive-only survey/capture | Verify receive mode and document disconnect/rejoin transitions; do not require airplane mode merely because a capture is running |
| Management-frame test | Verify relevant WLAN identity and frame source fields, current channel permission, and absence of unintended IP traffic on the test interface |
| Connected LAN assessment | Deliberate association, documented client identity, necessary DHCP/ARP/IPv6 behavior and traffic bound to the selected interface; preserve the local paths the test needs |

Per-app firewall rules alone may miss root/chroot helpers, and a normal Android VPN
does not cover raw Wi-Fi management frames. A dedicated namespace/interface policy
is a candidate only if this kernel, driver and helper process arrangement supports
it and measurements establish its coverage. Do not advertise it based on source
code or command acceptance alone. Cellular and Bluetooth decisions should follow
the chosen threat model instead of being universal Wi-Fi prerequisites.

MAC randomization does not suppress DHCP hostnames, application discovery traffic,
IPv6 identifiers or radio fingerprints. A management frame does not carry an IMEI
or public IP field. Changing a display name is not proof that the actual DHCP or
discovery identifier changed. Logs must distinguish those claims.

The persistent journal and shared radio ownership remain necessary. Cleanup runs
on completion, failure and cancellation; process death leaves a recovery record.
Restart may recover settings, but must never automatically replay a transmission.
Restoration failure must stay visible with a recovery action.

## CR200A baseline inspected with the user

The user opened the owned WNC-CR200A admin page in Chrome and authorized inspection.
Using the Computer Use skill, we read the existing System Status, System Log,
LAN DHCP Log and Devices pages. No router configuration, log clearing, firmware
update, phone command or test transmission was performed.

Observed router version: 3.6.0.3, hardware 0.0.5. Its own broadband uses a cellular
modem. IMEI/ICCID fields on System Status describe that router modem; they are not
evidence of the OnePlus exposing its IMEI through Wi-Fi.

Existing logs showed:

- Wi-Fi association/disassociation records with AP BSSID, client address, interface,
  reported signal, PHY/rate fields and timestamps.
- DHCPv4 DISCOVER/OFFER/REQUEST/ACK entries naming **OnePlus-13** alongside a client
  address and local IP. These are historical events, not effects of a new test.
- DHCPv6 Solicit/Advertise events with link-local addresses and transaction IDs.
  These entries have not been attributed to the phone.
- The online client inventory listed OnePlus-13 on 6 GHz.

Private addresses, account identifiers, modem identifiers and other household
device names are omitted from this note. No raw router log was exported or saved.
Router pages can cache data and omit events; this is not a packet-level exposure
audit or proof of which fields an independent wireless observer could correlate.

## Next discriminating test

Record a phone/clock/router baseline, then compare ordinary association, capture
mode transitions and one separately authorized bounded test. Inspect DHCP names,
IPv4/IPv6 traffic and association records for each stage. Use independent capture
where the router lacks visibility. Keep current protection unchanged until its
replacement is implemented and its coverage and restoration are demonstrated.
