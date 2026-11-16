# Validation — 0.5.0-live-wps-preview1

2026-11-15. Personal debug-signed preview, package `dev.sir.benderwifi`,
versionCode 12. APK: `dist/Bender-WiFi-Lab-0.5.0-live-wps-preview1.apk`,
SHA256 `50f53740c97042f34e21688f4d24d71c694bac4ad662a5337ce83ab33897d391`.
The prior releases remain in `dist/`.

## Implemented offline

The selected network's WPS screen can start a managed-interface collection,
run the existing local Pixiewps solver, request AP settings once with a
verified PIN, and save the PIN or password encrypted in Vault. A private local
socket hands material from the root worker to the app; native stdout and the
app's persistent session result do not contain those fields. The root
supervisor holds the existing radio mutex, prepares separate wlan0/wlan1 IP
filters, journals the secondary interface, attempts cleanup on cancellation
or completion, and retains recovery state when ownership cannot be verified.
The prior offline exchange-import path remains available.

The bundled hostap build is pinned to AOSP revision
`ab49c33aef291e4d9cabe9a16f535bca40569a10`; libnl-tiny is pinned to
OpenWrt revision `40493a655d8caa2ccf5206dde1e733abe2920432`. The build
script and source are included in the source ZIP. ARM64 and x86_64 binaries
were built with NDK 27.1.12297006. On an Android API 35 x86_64 emulator, the
native executable returned `Bender managed WPS protocol 1` from its bounded
version mode. This is a startup smoke check, not radio qualification.

## Exact-build checks

The final APK matched 1,140 source manifest inputs and 24 executable assets.
The test set passed 32 engine, 199 runtime, 73 JVM, and 52 emulator tests.
The latter were 51 UI/data tests and one vault migration test, both against
APK SHA256 `50f53740c97042f34e21688f4d24d71c694bac4ad662a5337ce83ab33897d391`.
Lint reported zero errors and six warnings. Original logs, test reports and
`tested-build.json` are under `validation/live-wps-20261115/`. The ARM64 engine
and Linux installer logs are retained from the earlier unchanged-input checks;
`retained-checks.json` records their hashes and provenance.

The first complete runtime run failed because a legacy supervisor fixture did
not contain the new WPS module. Staging is now conditional on WPS. The focused
49 supervisor and 10 capture-retention tests passed, followed by the complete
199-test runtime suite. The final small exception-handling change was covered
by four focused WPS runtime tests; no secrets are formatted into error logs.

## Handset evidence and limits

The OnePlus 13, CPH2655, accepted creation and deletion of a separate managed
`wlan1` interface on its internal PHY. The original `wlan0` ifindex and AP
were unchanged after that probe. No native supplicant startup, secondary
association, WPS exchange, PIN recovery, AP setting retrieval, or new RF
transmission was tested on the phone in this preview. The phone was unplugged
at the user's request. The cached WNC 2.4/5 GHz BSS records advertised WPS;
their age was roughly 934 seconds, so a fresh selected-AP check remains required.
The 6 GHz primary and Bender-PMF guest records did not advertise WPS. Local raw
probe files under the validation directory may contain private network IDs;
they are not included in the source package.

The app labels the live workflow experimental. A matched Pixiewps PIN means
the two captured E-Hashes matched locally; a credential received from WPS AP
settings is separately recorded. Neither result establishes completed
enrollment or a successful Wi-Fi connection. A timeout, rejection, or no PIN
does not establish that an AP is secure.
