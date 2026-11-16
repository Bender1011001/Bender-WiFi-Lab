# Wi-Fi workflow and feature coverage

2026-11-15, offline implementation review. No handset or target actions. This
release changes navigation and the capability catalog; it does not add a radio
transmission path or claim full compatibility with other tools.

## User workflow

**Networks → Scan networks & devices → select a network → Actions / Devices /
Saved work / Details.** Select a device to open its own action page. Back returns
to the device list, preserving the network section; returning to the network list
preserves filters and sorting. Tools contains installation, diagnostics, privacy
preparation, imports and the complete catalog. Vault and Evidence remain available.

The scanner requests a 120-second survey across driver-enabled 2.4/5/6 GHz
frequencies, with one-second dwell. Tuning failures and time limits can leave gaps.
Radio restoration precedes full analysis and inventory import; this UI does not
promise live streaming results. There is no guarantee of observing every AP or
silent/sleeping client. Counts mean observed addresses using their latest saved
AP relationship, not physical devices currently associated. Same-name APs remain
distinct BSSIDs; rotating addresses remain distinct. Unlinked devices stay visible
without fabricated AP assignments. Scan scope is all available bands regardless
of the inventory display filter.

## Capability map

| Feature family | App workflow now | Remaining dependency |
|---|---|---|
| AP/client discovery and filtering | Network list, counts, bands, signal/last-seen/device sorting, unlinked addresses | Live qualification of this exact APK; observational limits above |
| Channel capture and PCAP export | Network action and saved workspace | Root runtime, supported receive mode and recovery |
| WPA handshake collection | Explicit 120-second network capture; separate exchanges saved | Client authentication must occur while listening; capture completeness/password usability are separate |
| Directed client deauth | Device page, explicit bounded request, privacy/channel checks | Existing narrow managed-interface route; no automatic concurrent capture or broadcast sweep |
| Saved work and credentials | Network notes, captures, exchanges, results; encrypted vault | Automated recovery depends on an implemented recovery workflow |
| WPA/WPA2 wordlist audit | Visible unavailable catalog entry | No integrated candidate-verification backend; SAE differs from WPA2-PSK |
| WPS PIN/PBC enrollment | Visible unavailable catalog entry | Managed WPS transport, cancellation and credential handoff |
| Reaver-style PIN / Pixie Dust | Visible unavailable catalog entry | Suitable protocol exchanges, transport and recovery implementation; target susceptibility unproven |
| WEP IV/key workflow | Visible unavailable catalog entry | Parser/workflow integration; replay additionally needs qualified injection |
| PMKID assessment | Visible unavailable catalog entry | Suitable protocol evidence and backend; not a universal clientless method |
| Controlled AP / evil-twin comparison | Visible unavailable AP/device entry | SoftAP lifecycle, concurrency, security configuration and restoration |
| AP availability/authentication load | Visible unavailable AP entry | Frame-family support, bounded scheduling and independent outcome evidence |
| Beacon/probe experiments | Visible unavailable AP entry | General transmission support has not been demonstrated |
| PMF, isolation, MLO | Existing research catalog visible on relevant targets | Negotiation/topology/implementation evidence specific to each experiment |

The checked-out upstream `reference-hijacker/README.md` lists AP/station discovery,
PCAP capture, directed and network deauth, beacon/authentication flooding, WPA/WEP,
Reaver, wordlist cracking, custom actions and aliases. It also states its own
monitor-mode/root prerequisites. It is an offline source snapshot, not a fresh
audit of that project. Reaver/Pixie Dust support is explicitly conditioned there
on suitable tools/radio support. Our narrow managed deauth path does not establish
the general injection capabilities assumed by those workflows.

Bruce and Fern are user-requested comparison targets. Their current source and
complete feature sets were not retrieved during this offline pass; no exhaustive
or up-to-date parity claim is made. The catalog covers the requested Wi-Fi feature
families without implying that non-Wi-Fi peripherals or every plugin can run on
the phone. OUI lookup, aliases and a custom-tool runner also remain unimplemented.

## Implementation order after this UI

1. Qualify the new navigation and existing scan/capture/deauth workflow on the
   handset, preserving separate transmission/capture modes.
2. Integrate offline password candidate verification and evidence requirements
   as its own module. Check result attribution and secret handling before wiring
   verified credentials into the vault.
3. Demonstrate ordinary managed WPS enrollment before claiming recovery support.
4. Qualify SoftAP and any additional frame families individually. A command's
   acceptance or a tool binary's presence is not evidence of its RF effect.

Accepted preview4 behavior remains: no exact binary allowlist, no ownership
checkbox/capture-age gate, screenshots allowed, simple encrypted credential use.
Shared operation ownership, root requirements, privacy/channel checks and recovery
behavior are unchanged. Bluetooth uses the same existing ownership coordination.
