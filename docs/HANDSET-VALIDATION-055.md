# OnePlus inventory-view validation — November 17, 2026

Tested `0.5.5-latest-scan-preview1`, version code 18, on the rooted CPH2655, Android 16 build
`CPH2655_16.0.8.301(EX01)`, kernel `6.6.89-android15-8-g97a9aaefab9a-ab14519050-4k`.
The installed APK matched release SHA-256 `db4c4d45ac487a9172d33d63c44452dd4fdc19a5c8b42c427051cc0859673649`.

The exact APK was installed over 0.5.4 on the OnePlus. Startup and both inventory tabs passed: Latest scan displayed 16 networks and 9 device addresses from the preceding capture; Saved history displayed 100 networks and 59 addresses. Both matched the database. A fresh passive scan cleared old results and showed the pending-results explanation. **USB disconnected before completion could be collected**, so the new capture's final counts and restoration remain unverified. The earlier [0.5.4 handset survey and restoration](HANDSET-VALIDATION-054.md) retain their separate build/date scope.

The app's accumulated database was backed up before updating. The tab counts
confirm access to existing inventory; they do not independently verify every
saved record ID after the unfinished observation window. That check remains pending.

The software tests separately cover completed scan replacement, empty and failed
captures, historical imports, network/device navigation and saved-history retention.
They are not substitutes for an unobserved handset result.

One older capture still displays **Evidence JSON node budget exceeded**. Its raw
evidence remains preserved. The new survey's import outcome was not retrieved.
No new active transmission or credential-recovery test was performed.

[Scoped handset result](../evidence/handset-preview055-20261117.json) ·
[Software record](../evidence/bender-validation-055.json) ·
[Retained backend provenance](../evidence/retained-backend-tests-055.json)
