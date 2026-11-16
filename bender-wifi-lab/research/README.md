# Offline research infrastructure

`python -m research.firmware_audit` checks pinned external artifacts and focused
predicates without copying firmware. Its retained disassembly hash is pinned;
it does not regenerate instruction decoding. `python -m research.isolation`
is an explicit ordinary UDP sender/receiver CLI; configure two controlled endpoints,
start receiver first with the same fresh 64-hex nonce, then run sender once.
A reply proves round-trip delivery only. No reply is unknown. Record routes and
AP topology separately before interpreting isolation. No radio commands here.

`research.wps.analyze(events, credential_sink=...)` accepts bounded structured
events. It strips all unrecognized fields from its returned diagnostics. Credentials
are passed exclusively to an explicit in-memory sink. It does not write transcripts,
recover PINs, exchange WPS messages, or assert usable credentials from DONE.

The per-feature `.spec.json` files describe evidence gates, stop conditions and
limits. These records are not implementations of active experiments. The catalog
keeps those operation fields null. See runtime attribution for reused radio code.

The runtime supervisor now distinguishes `restoration_verified:true` with
`restoration_evidence:saved_state_restored` from managed mode merely observed
without a saved recovery record (`restoration_verified:false`). A managed
interface alone does not establish restoration of a prior firmware/Wi-Fi state.

`identity` is a read-only runtime operation that exports redacted
`identity-state.json`; it does not create or clear recovery obligations. Every
active management request checks privileged current identity facts before intent
and again before FRAME submission. A request boolean cannot override unknown or
failed facts. Probe, passive capture, and offline analysis remain available without
an identity pass. All active catalog entries require this gate.

The root supervisor tracks lab validation and offline analysis in private worker
process groups. Cancellation or Android owner death stops that group, including
decoder children, and yields exit 130. Imports never change radio mode or clear a
pending recovery record. Analysis receives `--cancel-file` as a cooperative stop
marker. Once lab intent exists an interrupted outcome is uncertain; no automatic
retry is performed. These are host-control-flow checks; physical handset lifecycle
and process-group behavior still require qualification.

`prepare`, `privacy_restore`, and `privacy_status` operate on a separate durable
host journal at `/data/local/tmp/hijacker-qca/bender-privacy/pending.json` under
the shared runtime mutex. Preparation saves original radio settings before
applying the private lab profile and verified IPv4/IPv6 IP egress locks; layer-2
Wi-Fi lab primitives remain separate. Explicit privacy restoration verifies the
saved settings and removal of owned rules. This is distinct from firmware/mode
recovery: `radio-state.json` reports `privacy_pending` independently of
`recovery_pending`, and exports redacted `privacy-state.json` plus a current
`identity-state.json`. Prepared profiles retain a journal until explicit restore.

Prepare refuses pending radio recovery; privacy restore and read-only status do
not clear it or change radio mode/firmware. Cancellation stops the supervised
operation; its durable privacy journal remains authoritative after interruption.
Do not replay a failed preparation automatically. Restored connectivity will
usually fail the private-lab identity gate, while privacy restoration can still
be verified. Where automation is unavailable, the UI must explain manual setup
and keep transmission blocked until privileged current checks pass.
