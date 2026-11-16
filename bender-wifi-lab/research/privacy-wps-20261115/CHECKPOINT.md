# Current delivery - 0.4.0-privacy-wps-preview1

2026-11-15: automatic Wi-Fi privacy sessions and local Pixiewps analysis implemented.
Default preserves cellular/Bluetooth; strict isolation is optional. Exact filters,
root-owned session journals, interface readiness and automatic restoration retain
pending recovery on uncertainty. No phone/router modifications or live radio tests.

APK code11, 210650179 bytes, SHA256
1e77ac22568f7df1cd7cbc6314f0f2e76af68ca0313ebfbc3e6e35b59546d169.
352 passing checks: engine32 + runtime195 + JVM73 + emulator52.
Lint 0 errors/6 warnings. 205 source inputs/21 assets match the APK.
Actual x86_64 Android solver recovered the synthetic 12345678 PIN, independently
verified both hashes and stored it encrypted against its network. Deadline and
interrupted-result recovery passed. ARM64 is built; handset execution remains.
Live WPS collection/enrollment is unimplemented. This is PIN analysis, not a
demonstration of recovering a real network password. Raw imports are memory-only.

Preserved network/Bluetooth workflow, preview 4 policy, preview 6 fixes and older
artifacts. See VALIDATION-0.4.0-privacy-wps-preview1.md and
validation/privacy-wps-preview1-20261115/delivery-audit.json.
Next: handset privacy qualification and managed WPS enrollment/collection research.

Earlier checkpoints below are historical.

