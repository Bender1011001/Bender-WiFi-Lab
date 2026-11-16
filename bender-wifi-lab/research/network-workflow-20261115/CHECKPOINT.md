# Network-first Wi-Fi workflow

Objective: scan available bands, show networks with observed client counts, then
open network or client actions and the work saved with that network. Keep the
Bluetooth preview and accepted preview4/preview6 behavior intact.

Offline only: no phone, SSH, target or live radio operations. Start from
0.3.0-bluetooth-preview1 (code9); preserve its APK/source/validation artifacts.
No new goal requested for this task. No subagent delegation.

Current source: app/src/main/java/dev/sir/benderwifi/ui/LabApp.kt,
ui/NetworkWorkspace.kt, data/NetworkWork.kt, runtime/catalog.json. Existing
scanner requests all enabled 2.4/5/6 GHz frequencies for 120 seconds, with
one-second dwell and recorded tuning outcomes; this is not guaranteed complete
coverage. Client rows describe observed addresses and latest AP relationships,
not a router census. SSID matches must never merge AP identities.

Implementation complete. 327 final checks pass; exact-APK/source verified.
See VALIDATION-0.3.1-network-preview1.md and validation/network-preview1-20261115/.
User later authorized read-only CR200A inspection in Chrome; no configuration or
phone/radio changes. Baseline and proposed operation-specific privacy sessions
are in OPSEC-SESSIONS.md. Current privacy backend remains unchanged.
Delivery is packaged with exact-build validation. Final archive correspondence is
recorded in validation/network-preview1-20261115/delivery-audit.json. Handset
qualification and a controlled router exposure comparison remain separate work.
