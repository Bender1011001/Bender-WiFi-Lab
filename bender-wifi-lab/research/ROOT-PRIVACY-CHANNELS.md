# Root, privacy and channel policy review - 2026-11-15

This is an offline source investigation and implementation change. No handset,
router, radio or Internet access was used. Kernel/driver sources are references;
their equivalence to an installed binary was not newly established.

## Does deauth require root?

The current app does. `RadioController.transport` starts the supervisor through
`su -mm -c`; `qca-runner.sh` rejects a nonzero UID and the backend checks its root
runtime. Chroot, interface transitions and recovery also use privileged operations.

There is a kernel-level boundary as well: the locally available OnePlus kernel
source registers `NL80211_CMD_FRAME` with `GENL_UNS_ADMIN_PERM` and the
`NL80211_FLAG_NEED_WDEV_UP` prerequisite. The handler is `nl80211_tx_mgmt`.
This is a privileged management interface, not an ordinary Android app permission.
In the Linux permission model this administrative netlink operation requires
CAP_NET_ADMIN in the applicable namespace. A correctly privileged helper could
serve an otherwise unprivileged app; that would still use privileged access.

The driver's advertised ability to use a different transmitter address is
independent of this permission check. It does not demonstrate a root bypass.
No unprivileged transmission was attempted or established. A future handset check
should first record an unprivileged permission failure without submitting a frame;
actual RF testing remains a separate, explicitly resumed hardware task.

Reference: OnePlus kernel commit `6028f47faddaa27700f8dd3a1d83906ea8f27170`,
`net/wireless/nl80211.c:17271-17275`. The local `genetlink.c` object was unavailable
and was not downloaded. Saved source bytes and SHA-256 values are in
`validation/policy-20261115/kernel-source.json`.

## Driver compatibility and recovery

Preview 4 removes the exact driver SHA-256 allowlist. Model/build/hash remain
recorded evidence of the reference profile. Other binaries may attempt the same
`qca_cld3_peach_v2` interface; this is not universal support for every Qualcomm
chipset or rooted phone. Root, required files, managed interface state, capability
queries, channel permissions, exclusive ownership and restoration checks remain.

Untested drivers receive an explicit untested classification and message.
Recovery uses the saved state and retains the journal if verification fails.
It cannot guarantee recovery from a driver hang, changed interface semantics or
kernel failure. Controlled supervisor tests exercise unknown-hash dispatch and
restoration; these do not establish that another physical build works.

## Android MAC randomization and privacy

Android already supplies MAC randomization. The installed API 35 SDK source
`android/net/wifi/WifiConfiguration.java:1873-1901` documents persistent,
non-persistent and automatic strategies. Persistent randomization can reuse an
address for a particular network. A setting label is not proof that a particular
frame used that address, or that an address changed on every reconnect.

The app reads the current `wlan0` address and accepts a valid, unicast, locally
administered MAC. Android's built-in randomized address can pass without any
additional spoofing tool. The check does not distinguish Android-generated
addresses from other locally administered addresses, prove entropy, or inspect
the exact Developer Options toggle on this OnePlus build. UI help now says this.

| Exposure | Relevance and current behavior |
|---|---|
| Station MAC | Relevant to association and other station traffic. Read back the current address; do not assume a toggle took effect. The directed test constructs its management-frame source/BSSID from the selected AP. |
| IP addresses | Raw deauthentication frames contain no IP header. Other applications and background services may still produce IP traffic. Prepare lab blocks ordinary IPv4/IPv6 output, or the preflight requires acceptable local-only addressing/routes. No external IP lookup is used. |
| IMEI / cellular identity | Not an 802.11 deauthentication field. The app does not read or spoof it. Airplane mode, mobile-data state and cellular-interface checks address the separate cellular path; they are not RF proof that the modem emitted nothing. |
| Bluetooth | A separate nearby-radio exposure. Turning it off is a conservative preparation choice, not a technical requirement for sending a Wi-Fi management frame. |
| Saved evidence | PCAPs, names, BSSIDs, credentials and screenshots can identify a network. Ordinary evidence exports remain redacted; deliberate raw PCAP export and credential copying preserve their content. Screenshots are now permitted. |
| Linkability | Timing, location, persistent addresses, protocol details and other observations can link sessions. A passing preflight does not establish anonymity. A VPN alone does not satisfy the current local boundary checks. |

The existing privacy profile and its pre-transmission checks remain enabled,
consistent with the earlier request to block active tests when exposure checks
are unresolved. This pass clarifies their meaning rather than promising invisible
operation. Root software and firmware remain outside an ordinary-IP firewall
assurance. New Android builds still need qualification of the actual settings,
MAC readback, routing and restoration behavior.

## Disabled, NO-IR and DFS/radar channels

These flags describe different conditions. They are not a single list of
permanently unusable channels.

- **Disabled:** the channel is unavailable under the current regulatory state.
  Country rules, firmware regulatory data, hardware support and driver policy can
  affect the effective list. A list from another build or country is insufficient.
- **NO-IR:** no initiating radiation. The kernel header explicitly includes probe
  requests and beaconing in this restriction. It does not mean infrared. Passive
  reception and context-dependent connected-station behavior are separate cases.
- **DFS/radar:** Dynamic Frequency Selection. Radar detection is required on the
  applicable channel. The kernel distinguishes usable (CAC required before AP/IBSS
  use), unavailable (radar detected) and available (CAC completed) states. It does
  not mean every connected client is always forbidden to use that channel.

The QCA reference driver maps wiphy disabled, radar and passive/NO-IR flags into
its regulatory channel state (`wlan_hdd_regulatory.c:586-601`). The kernel UAPI
explains the frequency attributes at `nl80211.h:4186-4195` and DFS states at
`6791-6795`. Source hashes are retained in the local validation directory.

Our directed raw-frame path reads the current PHY's frequency list. It refuses
unavailable or ambiguous channels and conservatively refuses NO-IR/DFS/radar
contexts because this specific path has not qualified their prerequisites.
That additional app policy is stricter than normal station connectivity in some
situations. It was not removed in this pass: changing country hints, clearing
flags or treating an AP connection as permission for arbitrary off-channel
transmission would not establish a correct implementation. Qualification requires
current driver state, the actual operating role and independent observation.

## Requested app behavior

- Saved/imported selections work without a two-minute evidence expiry, a completed
  targeted-capture prerequisite or an ownership checkbox. The selected AP/client
  and explicit frequency still must form a well-formed request. Refresh is optional.
- A new test is always an explicit user action. Interrupted transmissions are not
  replayed. Outcome uncertainty remains recorded; the old fresh-capture retry
  requirement is removed. Single-request behavior and the 30-second cooldown stay.
- Screenshots and recording are allowed. Vault data remains encrypted, with one-tap
  reveal/copy, no fixed reveal timer, and hiding when the app leaves the foreground.
- New vault keys use the phone's unlocked state on Android 15+. Older Android
  versions use the app's device-lock check without the known Android 12-14
  unlocked-key deletion behavior documented in the local API 35 SDK source.
- Existing authentication-bound records retain their original key and encrypted
  data. A successful authenticated read migrates the record in place. Failed
  reads do not overwrite it; a concurrent deletion is not reversed. This may
  require a one-time device unlock when an older record is first opened.
