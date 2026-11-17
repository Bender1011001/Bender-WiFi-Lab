# OnePlus handset validation — November 17, 2026

Tested Bender `0.5.4-device-hints-preview1`, version code 17, on rooted CPH2655, Android 16 build `CPH2655_16.0.8.301(EX01)`, kernel `6.6.89-android15-8-g97a9aaefab9a-ab14519050-4k`. The installed APK hash matched the release artifact:

`f5aa6eb977501427773635a13f87a608821f19e397fba58b6e8db1c00aa8f5c2`

The app was updated in place from 0.5.3. Startup and Check radio passed. The UI then ran one full **Scan networks & devices** survey for 120 seconds. It captured 2,285 frames, 16 AP records and 9 client addresses, with no handshakes. All 94 planned frequency requests returned without request errors; that does not independently measure dwell on every channel. Analysis completed after radio restoration and imported the new capture successfully.

All previously saved record IDs remained present. This checks record preservation, not byte-for-byte immutability of every row. Network/device navigation displayed the vendor-prefix guess and the explanation that the registered hardware vendor may differ from the retail brand or model. This particular capture supplied no additional identity-clue records, and no actual device model was independently verified.

Final checks found the original AP/frequency, managed mode, an empty firmware override, no pending recovery, restored privacy settings and no runtime bind mounts under either tested runtime path. The gateway replied to all three pings. The crash buffer had no new entries. These were ordinary connectivity probes, not a claim of zero radio traffic.

## Known retained warning

One older completed capture reports **Evidence JSON node budget exceeded** after restart and triggers the saved-observations warning. Its raw evidence was preserved. The new capture had no import error. This result is disclosed rather than clearing historical evidence to obtain an empty warning state.

## Scope

This pass qualifies installation, passive survey, saved-record preservation, the vendor display and restoration on this exact build. It did not test live WPS recovery, app-driven deauth, a new handshake capture, cancellation/process death during an app radio operation, or Bluetooth interoperability. The software tests and separate terminal experiments have their own records.

[Machine-readable handset result](../evidence/handset-preview054-20261117.json) · [Software qualification](../evidence/bender-validation-054.json) · [Earlier 0.5.2 handset report](HANDSET-VALIDATION.md)
