# Latest scan and saved history — 0.5.5

Version `0.5.5-latest-scan-preview1`, code 18. Built November 17, 2026.

The default **Latest scan** view shows only networks and devices observed in the
most recent live capture. **Saved history** retains the accumulated inventory,
notes, captures and credentials. Device counts, AP relationships, signal and
AP-reported totals in Latest scan come from that capture. Older identity clues
can still help label a device, with their provenance, but cannot establish its
current presence. Imported captures remain historical.

Running, failed, unimported and empty captures have explicit states; they do not
silently substitute the preceding scan. Selected-channel captures are labeled
separately from all-band surveys. A capture is a time-bounded observation, not a
complete census or continuous proof that a device is still nearby.

## Software validation

- 96 JVM tests and 62 API35 emulator tests passed, with no failures or skips.
  These include Room snapshot selection, the production UI's two inventory
  views, network/device counts, empty scans, failed scans and historical imports.
- The previous 48 engine and 205 runtime passes are retained. All 36 relevant
  source/test inputs match their prior hashes; those suites were not rerun for
  this UI/data change.
- Android build and instrumentation packaging passed. Lint: 0 errors, 18 warnings.
- All 1,158 source-manifest inputs and 27 packaged assets matched. The APK keeps
  the previous update-signing certificate. No database schema migration.

APK SHA-256:
`db4c4d45ac487a9172d33d63c44452dd4fdc19a5c8b42c427051cc0859673649`

## Handset work in progress

The exact APK was installed in place over 0.5.4 on the rooted OnePlus 13. Startup
and both inventory tabs were checked: the preceding capture showed 16 networks
and 9 device addresses, while history retained 100 networks and 59 addresses.
These counts matched the database's immutable session and accumulated tables.

A fresh passive survey started. Latest scan cleared the preceding results and
displayed 0 observations with the explanation that results appear after capture
and analysis. USB disconnected before completion could be collected. Final new
capture counts and restoration remain pending handset reconnection. No active
transmission test was performed for this change.

One older capture's existing **Evidence JSON node budget exceeded** import
warning remains; its raw evidence has been preserved. The scan/history change
does not alter that importer limit or the previously agreed preview-4 behavior.

[Handset details](https://github.com/Bender1011001/Bender-WiFi-Lab/blob/main/docs/HANDSET-VALIDATION-055.md).
