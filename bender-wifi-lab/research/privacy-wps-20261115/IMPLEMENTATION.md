# Automatic sessions and WPS implementation

Version 0.4.0-privacy-wps-preview1, 2026-11-15. No handset or router mutation was
performed for this implementation. Consult the matching VALIDATION document for
the final test counts, artifact hashes and limitations.

## Automatic Wi-Fi preparation

The root supervisor, under its existing radio mutex, prepares before capture or
one-shot transmission. A durable root-owned journal precedes every mutation. The
default profile installs exact IPv4 and IPv6 filter chains, with first-position
OUTPUT/FORWARD hooks and a wlan0 DROP rule. Other output returns to existing
policies. This covers ordinary IP stack output, including root/chroot helpers;
it does not establish coverage of AF_PACKET DHCP, firmware traffic, association
identity or RF correlation. No hostname-suppression claim is made.

Default preparation leaves cellular and Bluetooth settings alone. Wi-Fi is
enabled if necessary and its original state is restored. Optional Strict radio
isolation uses the previous all-interface filter and radio settings, now with
automatic session ownership/restoration. The backend independently selects its
privacy policy from the protected current-session journal and verifies actual
rules and the current locally administered WLAN address before TX. A UI snapshot
does not authorize transmission. Android randomization can be persistent and
does not establish unlinkability. IMEI/SIM data are not collected.

Cleanup runs after radio restoration and ignores cancellation/owner loss only
for the non-transmitting restoration worker. Pending radio recovery retains the
filter. Missing or modified cleanup evidence remains pending. A session cannot
restore another session's profile. Legacy schema-1 journals remain recoverable
through explicit restoration. No interrupted transmission is restarted. Saved
analysis and offline WPS processing do not change connectivity.

## Offline Pixie Dust

Upstream [Pixiewps](https://github.com/wiire-a/pixiewps) is vendored with its license
and recorded revision in `third_party/pixiewps-build.json`. Android ARM64 and
x86_64 executables are built using `tools/build_pixie.py` and the NDK. The wrapper
accepts six fixed-length hex fields over stdin, never shell commands or arbitrary
flags. Sensitive values do not enter process arguments, files, notifications or
ordinary evidence. One worker, parent-death termination, CPU and app wall deadlines
bound execution. The process runs as the app UID, without root or a network API.

Supported input is one complete Reaver-style text exchange, or JSON with exactly
`pke`, `pkr`, `e_hash1`, `e_hash2`, `authkey`, `e_nonce`. Duplicate/mixed exchanges,
missing or malformed values and input over 64 KiB are rejected. A normal WPA EAPOL
handshake, WPS advertisement, or an arbitrary starting PIN is insufficient.

The app recomputes both enrollee hashes using the recovered PIN and secret nonces.
It accepts a PIN only when both hashes match. The public synthetic fixture uses
`12345678`, exercising upstream support for non-checksummed eight-digit PINs;
this is not a finding about any real router. A successful PIN and its result are
stored in one database transaction, with Keystore encryption. Empty-PIN findings
are recorded explicitly without inventing a nonempty credential. No Wi-Fi password
is claimed. Network attribution comes from the user's selected dossier, not a
cryptographic BSSID binding of the imported fields.

Live WPS registrar exchange collection through the internal managed interface is
still unimplemented/unqualified. Ordinary WPS enrollment and using a recovered PIN
to obtain credentials are separate future modules. The catalog keeps those
entries distinct from the functioning offline solver.

## Preserved behavior

No exact driver/binary hash lock, target ownership/capture-age gates or screenshot
restrictions were added. Channel restrictions, single-request/no-replay behavior,
shared radio ownership, easy encrypted credential reveal/copy, Bluetooth metadata
tools and earlier release artifacts are preserved. No public release/upload was
performed.
