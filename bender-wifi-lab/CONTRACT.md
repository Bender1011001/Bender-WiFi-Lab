# Bender Wi-Fi Lab implementation contract

## Bluetooth preview contract (2026-11-15)

The Bluetooth implementation pass is offline-only; the earlier handset permission
does not authorize new phone tests while the user is away. Package code 9/version
0.3.0-bluetooth-preview1 adds a nonexported Bluetooth activity using public Android
BLE APIs. The Wi-Fi data schema, runtime image, privacy preparation and accepted
preview4 policy remain unchanged.

`BluetoothCoordinator` persists an operation intent before touching BLE, bounds
discovery/inspection, closes transport on cancellation, saves final evidence and
then releases its reservation. Start/stop calls run on the main dispatcher.
Initialization marks persisted running operations interrupted without replay.
`RadioController.reserveBluetooth/releaseBluetooth` serialize Bender operations
using the same synchronized controller entry points as Wi-Fi starts. Saved Wi-Fi
privacy changes and known radio recovery obligations block new Bluetooth runs;
ordinary BLE does not require a root radio probe. This reservation does not claim
control over other Android apps or external NetHunter commands.

Separate Room database `bender-bluetooth.db` v1 stores devices by observed address,
immutable operation results, per-device encounters and notes. Addresses can rotate,
be spoofed or be resolved by Android; they are not asserted physical identities.
Discovery retains latest data per address per bounded scan; it is not a full packet
capture. GATT snapshots contain service metadata and Android bond state only.
Authorization stays untested, encryption stays unobserved, and cache use remains
possible. The manifest's Android12+ neverForLocation choice can filter beacons.

Metadata export schema1 projects specific fields, aliases addresses within one
export and omits names/raw advertisements/notes/arbitrary errors. It does not
promise anonymity. It reads one payload at a time, bounds output to 8 MiB and 1,000
operations, and reports available/included counts and `limit_reached`. No Bluetooth
credential collection, value read/write,
notification subscription, pairing initiation, HID input or CVE exploit is added.

The ownership sections below describe the earlier Wi-Fi implementation; no new
delegated agents were used for this Bluetooth pass.

Private, internal-radio-only successor; package `dev.sir.benderwifi`. Preserve sibling projects. Builds and synthetic checks run locally. Handset testing was authorized on 2026-11-15; each live result must identify the exact APK and tested operation. Reuse GPL-3.0-or-later radio components with attribution.

## Ownership
- Protocol agent: `engine/`, `tests/engine/` only.
- Android agent: Kotlin `ui/`, `data/`, `MainActivity.kt`, `LabApplication.kt`, and Android resources only. Root owns Gradle, manifest, and Kotlin `radio/`.
- Runtime agent: `runtime/`, `research/`, `tests/runtime/` only.
- Root: integration, service/transport, build configuration, delivery and checkpoint.

## Observation JSON v1
CLI: `python -m bender_engine analyze --pcap FILE --output FILE [--tshark PATH]`.
Top level: `schema_version:1`, `source:{artifact_sha256,decoder}`, `networks:[]`, `clients:[]`, `handshakes:[]`, `observations:[]`, `limits:[]`.
Network: `id` (`ap:` + lowercase BSSID), `bssid`, `ssid_hex`, `ssid_display`, `frequency_mhz` (number or null), `channel` (number or null), `signal_dbm` (number or null), `first_seen`/`last_seen` (epoch seconds), `security` (display string), `advertised_akms` (array of selector objects with `oui`, `type`, `name`), `pmf_advertised` (`unknown`, `disabled`, `capable`, `required`), `wps` (object or null), `mlo` (object or null), `negotiated_security:"unknown"`, `negotiated_pmf:"unknown"`. Known structured fields are projected through ObservationSchema; unknown extensions and arbitrary text are not persisted as observations. Raw PCAP remains the original evidence.
Client: `id` (`sta:` + lowercase MAC), `mac`, `ap_id` (id or null), `first_seen`/`last_seen`, `signal_dbm` (number or null). Relationships describe observations, not durable identity.
Missing evidence is unknown, never a positive test result. Observation provenance references source frame/time. Secrets must not enter normal result/log files.

## Runtime contract
Copy existing five QCA assets into runtime; preserve shared `/data/local/tmp/hijacker-qca/.radio-mutation.lock` and recovery ownership across apps. Only permit this app's exact output path `/data/user/0/dev.sir.benderwifi/files/qca-sessions/SID` (plus its `/data/data` alias) in new runner. Keep backend source compatibility and explicit-frequency/intent/no-automatic-retry guards.
Android stages assets from `runtime/` to app private `files/radio-tools`; runner uses existing arguments ACTION SID OUT PID BSSID. Supported live primitives remain `probe`, `capture`, `lab`, `restore`. Captures additionally produce `observations-v1.json` with the engine after restoration. The engine package will be bundled as `runtime/bender_engine/*`; root integrates Gradle assets. Provide a runtime `bender-engine.py` import/CLI entrypoint and stage package safely in chroot. Never enable unsupported experimental operations merely because executables exist.
`catalog.json`: `{schema_version:1,actions:[{id,title,target_types:["ap","client","global"],status,description,blocker,operation}]}` is the v1 action catalog. Status `validated`, `experimental`, `awaiting_evidence`, `unavailable`. `operation` one of runtime primitives, `analyze`, `identity`, `prepare`, `privacy_restore`, `privacy_status`, or null. All new APK radio actions are experimental pending handset qualification. Deauth uses explicit AP/client/frequency addressing; capture provenance, age and an ownership checkbox are not prerequisites. Saved/imported targets are usable, while runtime privacy, feature and channel checks remain. Experimental hypotheses, controls, limits and stopping points are in the versioned research specs; executable backends enforce their own limits rather than trusting catalog values.

`radio-state.json` separates `managed_mode` and `recovery_pending` from `restoration_verified` and `restoration_evidence`. Only checked saved-state restoration sets restoration_verified true. Driver submission/acknowledgement and client effects remain in separate result fields. Interrupted/uncertain submissions are never replayed.
A subsequent explicit user action may use the same target after the cooldown.
No target-age/provenance or ownership-checkbox gate is imposed, and no interrupted
transmission is automatically repeated.

`identity-state.json`: `{schema_version:1,checked_at:epochSeconds,allowed:boolean,checks:[{id,status,reason}]}`. Required check IDs: android_context, airplane_mode, mobile_data, bluetooth, wlan_mac, assigned_addresses, ipv4_routes, ipv6_routes, cellular_links. Status pass/blocked/unknown. Only complete, strictly typed, recent all-pass reports enable the UI; the privileged backend repeats inspection before intent and submission. Raw identifiers are not serialized into this report. Verified first-position dual-stack egress locks can establish the ordinary-IP boundary even while routes and addresses remain configured; malformed routing evidence still blocks.

`privacy-state.json`: `{schema_version:1,checked_at:epochSeconds,pending:boolean,active:boolean,restored:boolean,state,message,blockers:[]}`. A successfully prepared profile remains pending until explicitly restored. Idle without a saved profile reports restored false. The root journal `/data/local/tmp/hijacker-qca/bender-privacy/pending.json` saves settings before mutation. No firmware or radio recovery marker is overwritten by this profile. Missing outcomes preserve a separate privacy-restoration obligation.

## Android boundary
Completed decoder output is reconciled into Room after app termination without
rerunning the operation. Session ingestion is atomic/idempotent. Runtime origins
take precedence over historical imports; live acquisition identity orders current
rows while original capture timestamps remain in the history. Invalid timestamps
reject the import. Foreground-service teardown precedes availability publication
and targets the owning service instance. Vault reveal generations are invalidated
on backgrounding, explicit locking, tab changes and disposal.

Root provides `radio.RadioController` singleton initialized via `init(context)`; `val state: StateFlow<RadioUiState>`; `start(operation:String,request:JSONObject=JSONObject())`, `cancel()`, `reconcile()`, `startAnalysis(uri)`. `RadioUiState` contains busy, phase, sessionId, recoveryRequired, identityReport, privacyReport and privacyPending. Radio recovery and privacy restoration are separate; read-only analysis cannot erase either obligation. Finalization releases the foreground service/busy latch even when evidence storage fails.
Data agent provides `data.LabRepository.get(context)`, `val networks:Flow<List<NetworkEntity>>`, `val clients:Flow<List<ClientEntity>>`, `val experiments:Flow<List<ExperimentEntity>>`, `val credentials:Flow<List<CredentialEntity>>`; suspend `importObservations(json:String,sessionId:String)`, `recordExperiment(id:String,operation:String,state:String,result:String)`, `getNetwork(id:String)`, `getClient(id:String)`; `sessionId` retained with network/client capture origin for active selection. Repository owns schema, persistent observation history and encrypted vault. Root calls import/record after supervisor completion.
UI can call controller and read catalog from packaged assets. User actions explicit. Imported records retain historical provenance and may supply explicitly selected targets, subject to current runtime checks. Import never starts a transmission. Native offline PCAP import will invoke root analysis only when available; JSON imports must work without root. No sample findings presented as discovered networks.

## UI
Bender pixel-art theme only, no XP/missions. Radar, Dossiers, Workshop, Vault, Evidence. Charcoal backgrounds, lime accents, yellow eyes, silver pixel robot. Clear readable text with advanced raw evidence access. Real functionality and honest empty/blocked states.

## Preview 4 policy and vault compatibility

The SHA-256 compatibility profile is informational, not a binary allowlist.
Unknown drivers have tier `unverified_driver`; `operations_candidate:true` means
attempts are not hash-blocked, not that capabilities or recovery are validated.
The existing QCA module/interface checks remain. `capture_session` is optional
provenance metadata for a lab request and is never resolved as a backend file path.
`authorized_owned_hardware` is no longer required. No transmission is automatically
replayed; explicit subsequent requests remain subject to the existing cooldown.

New credential `wrappedKey` values use a `v2:` prefix and Keystore alias
`bender_wifi_vault_rsa_v2`. Ciphertext, IV and AAD remain unchanged in format.
Unprefixed v1 records retain `bender_wifi_vault_rsa_v1` and its original authentication
requirement. A successful reveal re-encrypts and updates in place only if the old
record still exists; failed reads do not rewrite it. There is no Room schema change.
New reads require an unlocked phone; keys created on API35+ also enforce that in Keystore. No fixed
reveal timeout or extra prompt. UI clears on background/disposal. Screenshots are
permitted, copying is explicit, and ordinary evidence exports still exclude secrets.

## Preview 5 evidence corrections

Explicit radiotap bad-FCS or decoder FCS-failure status excludes a frame from
AP/client/handshake summaries while preserving the raw PCAP and exclusion count.
Source frame_integrity counts distinguish failed, verified, not_flagged and
unknown; not_flagged is not checksum verification. Advertisements retain security
variants with protocols, authentication, ciphers, PMF and quality separately.
Open requires a complete nonmalformed advertisement with explicit privacy off.
WPA and RSN selector namespaces and authentication/cipher kinds remain separate.

Schema-1 observation imports use a typed field projection. Share exports use
export_schema_version 2 and reproject existing records, omitting unknown fields,
free text, names, raw bytes and credentials. Runtime session aliases preserve
observation/experiment joins, including imported:bw_* origins. Shared timestamps
and technical metadata are not an anonymity guarantee.

recoveryChecked starts false each process lifetime; reconciliation of saved app
sessions cannot establish current root-journal state. recovery_status queries the
root journals under the shared mutex without requiring a decoder or Wi-Fi module.
It changes no radio/connectivity state and never replays an interrupted action.

Root raw duplicates are deleted only after a successful byte-matched handoff.
retention-state.json records failures to remove duplicates and is saved in session
evidence. delete_capture accepts a validated Bender capture_session and removes
only fixed raw-capture/log files from app and selected-runtime storage.
cleanup_captures removes those files only in the Bender bw_TIMESTAMP_HEX session
namespace of the selected runtime. Both preserve summaries, intents and recovery
journals, reject symlink/hardlink hazards, serialize with radio operations, stop
on cancellation/owner death and report interrupted cleanup without automatic retry.
