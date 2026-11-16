# Runtime/research implementation checkpoint

Objective: isolated Bender Wi-Fi Lab runtime and offline experiment infrastructure.
No handset access, transmission, firmware loading or sibling edits performed.

- Runtime copies five GPL QCA assets with attribution. Backend/scanner/netlink
  preserve existing frequency/intent/no-retry guards; supervisor output paths allow only
  dev.sir.benderwifi sessions. Shared hijacker-qca kernel mutex, lock directory,
  recovery and chroot session namespace retained for exclusion across apps.
- Capture restores radio before engine analysis; decoder failure recorded separately
  in analysis-state.json. Root Gradle stages engine/bender_engine into runtime assets.
- analyze accepts OUT/input.pcap <=256 MiB and produces observation/result/analysis
  artifacts without mode or firmware writes; pending recovery is retained. Engine
  enforces a shared 120-second deadline. A private worker process group owns lab
  validation and analysis/decoder children. The supervisor checks cancellation and
  Android owner loss before/during/after workers, sends group termination, and
  marks cancellation exit 130; analysis also receives a cooperative cancel-file.
- Backend checks cancellation/owner immediately before intent and FRAME; privacy
  checks run before each of those owner checks. Freshness is revalidated after
  privacy collection so slow inspection cannot authorize an aged selection. Identity facts are privileged live
  checks; request booleans cannot override them. Uncertain intent never retries.
- Read-only identity exports redacted identity-state.json and never creates/clears
  pending recovery. Capture/probe/analyze do not require an identity pass.
- Saved-state restoration is reported separately from managed-mode observation
  with no recovery record, via restoration_verified/restoration_evidence.
- catalog.json lists experimental live primitives pending APK handset qualification.
  Planned WPS/isolation/managementTXRX/PMF/SoftAP/MLO/firmware operations remain null.
- research/wps.py provides bounded structured event classification and explicit
  in-memory credential sink; returned diagnostics contain no raw transcript fields.
  research/isolation.py provides real bounded single UDP nonce send/receive, never
  classifies direct versus forwarded paths. Per-feature *.spec.json define gates.
- firmware_audit.py verified actual pinned original firmware, derived ELF, retained
  disassembly, nested pointer, bit0 rejection, second-allocation zero and six
  non-file-backed ROM callees. Result: firmware-audit-result.json. No images copied.
  Disassembly is pinned retained evidence; this audit does not regenerate decoding.
- V60 stop/gate details retained in management-txrx.spec.json and firmware.spec.json.

- Added supervised prepare/privacy_restore/privacy_status operations and staged
  privacy_profile.py. Profile journal is host BASE/bender-privacy/pending.json;
  radio-state privacy_pending is independent of radio recovery_pending. These
  operations never write mode/firmware or clear radio recovery. Prepare refuses
  pending radio recovery, while privacy restore/status remain available.
- Backend exports privacy-state.json and post-operation identity-state.json.
  Restored connectivity may correctly block identity without failing restoration.
  Cancel/owner loss stops the worker; durable privacy journal survives interruption.

Validation: consolidated runtime/research suite140 passed in98.902s
(research/runtime-tests.log). Subsequent isolated idle-restoration semantics:
backend33 passed in0.511s (research/backend-tests.log); status with owned hooks
and no journal passed1 focused supervisor test (research/profile-status-tests.log).
Unexpected prepare failure retaining durable profile obligation passed1 focused
supervisor test (research/profile-failure-tests.log). Supervisor38 passed earlier
in96.862s; full140 includes added failure regression. Syntax/compile pass.
Module owner is finishing separate final guard/profile regression checks; follow
that result for any post-snapshot isolated hardening. Host tests model Android
commands, timing, locks and signals. They do not qualify APK/SELinux, real Android
process-group delivery, RF or handset lifecycle. No phone access performed.

Integration: root must precreate observations-v1.json, analysis-state.json,
analysis.log in app session output files to preserve app ownership/SELinux labels.
Root owns APK staging and transport; stage identity_guard.py and precreate
identity-state.json and privacy-state.json too; allow prepare/privacy_restore/
privacy_status and reflect separate privacy_pending. Next: integration build and offline QA;
new handset qualification remains a separate authorized session.

No-journal/no-owned-hooks privacy restore/status is idle, restored=false. Only a
verified saved journal restoration reports restored=true. A profile pending report
from owned hooks also makes radio-state privacy_pending=true without a journal.
