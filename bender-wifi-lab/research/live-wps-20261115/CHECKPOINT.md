# Live internal-radio WPS

Objective: select an owned network, collect the WPS exchange on the OnePlus 13
internal radio, run Pixiewps locally and save verified results without import.
User connected the phone and permits either owned Rockspace or WNC-CR200A;
their WPS availability has not yet been established.

Baseline: 0.4.0-privacy-wps-preview1 APK, SHA256
1e77ac22568f7df1cd7cbc6314f0f2e76af68ca0313ebfbc3e6e35b59546d169.
Offline solver is tested; live collection/enrollment is not implemented.
Preserve prior artifacts, preview 4 choices and automatic restoration.

Evidence: validation/live-wps-20261115/ (raw local identifiers are private).
On the handset, managed wlan1 creation/deletion on the internal PHY succeeded,
with the original wlan0 ifindex and AP unchanged. Cached WNC 2.4/5 GHz records
advertise WPS; 6 GHz and Bender-PMF guest do not. Rockspace did not advertise
WPS. Cache age was approximately 934 s. User unplugged phone; continue offline.

Current source: pinned AOSP hostap and OpenWrt libnl-tiny, reproducible NDK
builder and bounded native WPS hooks, root worker/supervisor/privacy/recovery,
app local-socket bridge, selected-network screen, Vault handoff and provenance.
ARM64/x86_64 native build passed (third_party/wps-build.json). The M3 hook
emits six fields and exits before M4; the credential hook exits before normal
network configuration. No system-wide key logging or phone WPS TX was used.

Validation: Android build/unit/lint passed, 49 supervisor and 10 capture
retention tests passed after WPS module staging was restricted to WPS. Four
new WPS runtime tests passed. Final full runtime/emulator checks remain.
The first complete runtime run failed due to fixtures lacking the new module;
focused reruns passed after the fix. Native log: validation/live-wps-20261115/
native-build-9.log. No release claim before final checks and artifact audit.

Next: finish runtime/emulator tests and package an experimental preview.
Later reconnect phone for native startup, fresh discovery, controlled exchange,
PIN/credential outcome and restoration. No live WPS result is established.
