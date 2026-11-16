# Preview 6 handset follow-up

The [independent preview 6 review](research/CORRECTIVE-REVIEW-preview6.md) covers
the navigation, shell packaging, decoder bounds and handset-manifest changes.
Its final finding was fixed and no actionable issues remain in that scope.
Handset results and exact artifact identity are in [VALIDATION.md](VALIDATION.md).

# Corrective review - 0.2.0-preview5

All six findings in the preview-3 technical review were verified against preview4
and corrected. Independent reviewer report:
[scoped review](research/CORRECTIVE-REVIEW-preview5.md).
Four regressions found during that review were fixed: text encoding, invalid
timestamp handling, export session joins, and cleanup cancellation/owner death.
The final scoped source verdict has no remaining actionable findings. This is not
a claim of whole-app perfection or handset/RF qualification. Exact build checks
and device evidence are recorded in [VALIDATION.md](VALIDATION.md).

The preview4 driver, target, screenshot and vault policy choices remain unchanged.
Earlier review history below describes its original artifact, including superseded
policies; the current contract and preview5 review take precedence.

# User-requested policy revision - 0.2.0-preview4

This revision changes intentional policies following the user's review of preview 3.
It is not a new independent APK-review verdict; the five-round review below belongs
to the preceding APK hash.

- The exact driver hash is now reference metadata. Other binaries may try the same
  QCA interface, with runtime checks and persistent recovery records retained.
  Unknown-build recovery is not guaranteed.
- Target capture-age, provenance and ownership-checkbox prerequisites are removed.
  Saved/imported targets work with explicit AP/client/frequency addressing. A new
  request is always manual; single-request behavior and cooldown remain.
- Privacy checks accept Android's existing randomized MAC when its actual address
  is locally administered. Help now distinguishes this from anonymity and explains
  the existing network/cellular/Bluetooth checks. Driver channel checks are retained.
- Screenshots are enabled. Vault reveal/copy has no extra prompt for new entries
  and no fixed expiry timer. Data stays encrypted and hides when backgrounded.
  Older authenticated entries migrate only after a successful read; corrupt data
  is preserved and concurrent deletion is not undone.

Offline runtime/JVM, real Keystore migration/tamper and Compose workflow checks are
recorded in [VALIDATION.md](VALIDATION.md). The source investigation and remaining
hardware questions are in [ROOT-PRIVACY-CHANNELS.md](research/ROOT-PRIVACY-CHANNELS.md).
Previous review records follow for provenance.

---

# APK critique loop - 0.2.0-preview3

A fresh independent subagent reviewed the packaged APK and matching source. The
primary agent implemented fixes and regression checks, then returned each rebuilt
candidate for review. Five rounds resolved six product issues and one defect in
a newly added test fixture. The final reviewer reports **no actionable findings
remaining within the reviewed scope** for APK SHA-256
`a4613f4d55f9358b10371ffee0f184cfd28be4887cbd9283394bca5ba7e2cb82`.

| Finding | Delivered change and evidence |
|---|---|
| R1-1: no notification permission/settings flow | Optional enable/settings actions handle permission denial and blocked channels, refresh on resume and explain the in-app Cancel fallback. JVM branches and native emulator permission/settings checks pass. The notification action is labelled Cancel. |
| R1-2: masked vault field used an ordinary text keyboard | Configure a password editor and disable autocorrection. An emulator test inspects the actual Android input method flags. |
| R1-3: persisted root privacy obligation could disappear from UI after app-data reset | Propagate positive root `privacy_pending` state into the controller banner; an absent or false report cannot silently clear a prior obligation. JVM and supervisor regressions pass. |
| R1-4: identity checks ignored cancellation | Run identity through the supervised worker and honor cancellation and owner death before, during and after collection. Runtime regressions pass. |
| R3-1: analysis inherited Android PATH, so installed TShark could not be found | Supply a deterministic Linux PATH to every chroot worker. Actual ARM64 analysis and capture-analysis both fail with the old PATH and succeed with the fix using the same empty PCAP and runtime. |
| R4-1: cancelled import reported idle before removing its temporary copy | Attempt cleanup before releasing the reservation or publishing idle; retain a cancellation message and disclose deletion failure. The final APK passes the emulator regression that exposed the race. |

Round 2 found a missing parent-directory creation in the new privacy-probe test
fixture. Its correction and failed first-run log are preserved separately from
product fixes. Round 4 included the import-cleanup failure found by the emulator;
round 5 inspected that fix, the final artifact and passing evidence.

**262 distinct tests passed**, with no final failures or skips. Repeated ARM64
engine and Linux installer checks, four actual ARM64 worker control cases and
native notification settings checks also pass. See [VALIDATION.md](VALIDATION.md).
The package contains hashes of 87 source/build/test inputs and 18 checked runtime
assets. Raw review rounds and logs are local in `validation/apk-loop-20261115/`
and excluded from the source archive.

This review covers installer/controller recovery, workflow, privacy/export
boundaries, vault input, cancellation and decoder execution. It does not prove
that all possible defects have been eliminated. Physical OnePlus installation and
RF behavior remain untested for this APK. Earlier review records follow.

---

# Installer and workflow critique loop — 0.2.0-preview2

Three independent subagents reviewed the installer, app workflow and state recovery.
The first pass reported 13 actionable findings. The primary agent implemented fixes
and regression checks, then each reviewer inspected the changed source again.
The second pass caught a remaining readiness branch and capture/export timing
problems; these were fixed and re-reviewed within that pass. All three final reports
state **no actionable findings in their reviewed scope**. This is a bounded review
result, not a claim that the app is bug-free or handset-qualified.

| Finding | Delivered change |
|---|---|
| I1: failed result write skipped stage cleanup | Clean up the owned stage before best-effort failure reporting. |
| I2: partial unmount retried already released mounts | Retain and retry only mounts that remain unresolved. |
| I3: later jobs forgot orphan runtime mounts | Verify exact host-directory identity before adopting and cleaning private mounts; preserve unknown mounts. |
| W1: survey passed UI target prerequisites | Require a completed matching targeted capture; offer Refresh AP / client evidence in the action dialog. |
| W2: Stop skipped capture analysis | Separate Finish & save from Cancel. Finish restores the radio and analyses saved packets; late scanning updates cannot reinstate Listening after finish/cancel. |
| W3: failed/unlinked PCAPs were inaccessible | Export private PCAPs from Evidence as well as network workspaces. Export availability observes controller release even when the saved row is unchanged. |
| W4: Workshop buttons only displayed prerequisite text | Route current executable actions to their controller operation, target screen or import picker. |
| W5: import copy raced a competing operation | Reserve the controller during copying, close the input on cancellation and atomically hand the copied file to analysis. |
| S1: intent without a valid result looked retryable | Keep uncertain transmission outcomes blocked against the same capture, independent of the prior failed/cancelled label. |
| S2: terminal files could leave Room stuck running | Reconcile both commits idempotently without replaying operations; preserve timestamps and richer saved evidence. |
| S3: stale Runtime ready hid setup | Missing dependencies and failed readiness checks invalidate readiness across all relevant operations. Show the failure reason. |
| S4: legacy directed tests lacked network links | Backfill links from the durable target request during terminal reconciliation. |
| S5: client-only captures were omitted from the AP workspace | Link the capture through its observed client/AP relationship without inventing an AP record. |

Validation: **254 distinct tests passed**, plus 25 repeated ARM64 engine checks and
17 repeated Linux installer/mount checks. Build and lint pass; final APK assets
match 84 source/test inputs and the pinned runtime image. See [VALIDATION.md](VALIDATION.md).
Raw local reports and logs are retained in `validation/review-product-20261114/`;
they are excluded from the source archive. The first runtime run's sole failure
was a new test expecting `completed` instead of the established analysis value
`complete`; the corrected regression and full final suite pass.

All work stayed offline, using mocked root/filesystem behavior and a disposable
emulator. The bundled ARM64 tools were exercised under QEMU. No physical phone,
router or RF operation was performed. Root extraction/SELinux, actual mount
recovery and radio behavior on the OnePlus remain live qualification tasks.

# Earlier review fixes — 0.1.1

Three GPT-6.1 Sol reviewers at medium reasoning inspected Android/data/lifecycle,
protocol observations, and the privileged runtime independently. The reviews were
read-only; fixes and validation were performed by the primary agent. No physical
phone or live RF experiment was performed.

| Priority | Finding | Fix and regression |
|---|---|---|
| P1 | Privacy preparation/restoration ran before foreign radio-owner checks. | `qca-runner.sh` checks all four foreign markers before either mutating worker. Eight action/marker combinations reject without invoking the worker or changing saved settings; read-only privacy status remains available. |
| P2 | App termination after decoder completion could leave observations out of Room permanently. | Reconciliation imports committed `analysis-state.json`/`observations-v1.json` without executing a radio action. Room session/observation ingestion is atomic and idempotent. Instrumentation models missing app exit/journal completion, repeated imports, partial output and retry. |
| P2 | A future-dated historical import displaced fresh live AP/client rows. | Live origin takes precedence over imports. Live acquisition IDs determine ordering independently of packet clocks/reconciliation order. Raw history is retained; negative/nonfinite/unparseable timestamps reject atomically. Both import orders and both AP/client rows are tested. |
| P2 | Previous-session teardown could stop a newly started foreground service. | Stop the owning service instance with its start ID on the main thread before publishing availability. Unit tests hold teardown pending while a competing start is attempted and verify release after a teardown error. |
| P2 | A decrypt completing after ON_STOP could redisplay a credential. | Foreground checks and a reveal generation reject stale completions after background/resume, locking, tab changes or a newer request. Controlled delayed-decrypt unit tests verify no stale display; actual Keystore roundtrip/tamper tests still pass. |
| P2 | Data frames turned wired logical endpoints into Wi-Fi clients. | Attribute stations through transmitter/receiver addresses and DS direction. Retain logical endpoints as frame evidence. Real TShark ToDS/FromDS fixtures and unknown/WDS/IBSS cases cover this distinction. |
| P2 | Nested nontransmitted BSSID security contaminated and duplicated the parent summary. | Scope fields to their owning IE/BSS. Preserve nested security once with unresolved profile identity, separately from the parent. Real nested-BSSID decoding and nested WPS/MLO/SSID regressions pass. |

The release process also embeds build-input hashes and refuses packaging when the
APK and current source differ. Version 0.1.0 artifacts remain available.

Validation: **214 tests passed**, no final failures or skips; Android build/lint
passed (0 errors, 16 warnings, 1 informational notice). Three retained captures
were replayed with their previous security/WPS/handshake summaries preserved.
See [VALIDATION.md](VALIDATION.md). Raw reviews, reproductions and run logs stay
under local `validation/review-20261113/`; private capture data is not packaged.

These bounded reviews do not establish complete security coverage, handset radio
compatibility, anonymity, general injection, WPS recovery or the unimplemented
research modules. Exact OnePlus app/driver/firmware qualification remains pending.
