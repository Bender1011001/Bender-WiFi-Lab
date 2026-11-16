# Handset validation addendum - November 16, 2026

After the publication review package was sealed, **Bender 0.5.2-device-count-preview2 (version code 15)** was installed in place on the rooted OnePlus 13 CPH2655, running Android 16 build `CPH2655_16.0.8.301(EX01)`. Installed APK SHA256:

`812fd140e9368a19ea7b67517f428bce7e2c43393f61eccb604c50fa10877425`

Startup, root/runtime readiness, one full 120-second passive survey, saved-inventory persistence and device-count display checks passed. The scan captured 7,072 frames. The app's newer analysis identified 15 AP records and 9 client addresses, compared with 3 station addresses in the legacy scan summary. Six AP records supplied BSS Load station counts. No handshake exchange was saved.

The UI was checked with both an unknown AP total and an explicit AP-reported zero. It displayed identified addresses separately, retained the report timestamp, and kept Look for devices available on empty and nonempty device lists. The existing inventory was preserved; the total saved client-address inventory increased from 15 to 19. These are observations and saved addresses, not a ground-truth count of physical devices.

The app reported successful saved-state restoration and no pending recovery. Separate readback confirmed managed mode, absence of the checked operation/recovery markers, the same original network and connection identifiers, and three successful gateway replies out of three. No new Bender crash marker appeared in the retained Android crash buffer during this run. Cancellation, process death and transport timeout were not exercised.

This update qualifies the installation and passive workflow on this handset/build. It does not newly validate deauthentication, PMF bypass, WPS recovery, general injection or Bluetooth. The earlier V61/router-correlated deauth evidence remains a separate result.

No application code or APK bytes changed. Raw captures, screenshots, database backups and network identifiers remain private. This report supplements the original packaging checks and is included in the November 16 public snapshot.
