# PMF investigation — 2026-11-14

The useful question is **whether a specific association negotiated and enforced
PMF**, and which evidence proves that. The existing phone request has an
unprotected deauthentication header in userspace; its ability to disconnect an
unprotected client does not establish a route through negotiated PMF. There is
currently no verified PMF bypass on this OnePlus or any of the retained targets.

This pass produced an offline inspector, regression fixtures, a source trace and
a bounded experiment matrix. It adds no live radio operation and is not integrated
into the released 0.1.1 APK. The previous APK and its validation remain separate.

Subsequent user-authorized live follow-up: **all four PMF/beacon counter fields
were returned in five queries on the OnePlus**, each with value zero. Readout
availability is now validated under the recorded conditions; increment accuracy,
freshness and enforcement remain unresolved. See [live counter results](COUNTER-LIVE-RESULTS.md).
The sections below describing offline work retain their original evidence scope.

A subsequent [controlled guest-network trial](CONTROLLED-TEST-RESULTS.md) submitted
one group deauthentication frame without an MMIE. Six baseline and nineteen
subsequent readings all remained zero, with the phone still associated at the
sampled checks. There is no independent receipt/drop evidence for that frame, so
counter increment behavior and PMF enforcement remain unresolved. The Alfa was
returned to Windows and the temporary sender driver modules were unloaded.

## What PMF establishes

Classic PMF protects robust management traffic such as deauthentication,
disassociation and applicable action frames. It is not protection for every
management/control frame or every source of lost connectivity. Security
association queries also help protect an existing association when unauthenticated
reassociation traffic arrives. [Cisco implementation guide](https://www.cisco.com/c/en/us/td/docs/wireless/controller/technotes/5700/software/release/ios_xe_33/11rkw_DeploymentGuide/b_802point11rkw_deployment_guide_cisco_ios_xe_release33_chapter_0100.html)

An optional-PMF BSS can contain different client outcomes. Hostap explicitly
supports allowing legacy non-SAE stations without PMF while requiring PMF for
SAE connections. Its configuration also treats beacon protection and operating
channel validation as additional features with their own support requirements.
Neither should be inferred from the basic PMF-capable bit.
[Hostap configuration, especially ieee80211w, sae_require_mfp, beacon_prot and ocv](https://android.googlesource.com/platform/external/wpa_supplicant_8/+/refs/heads/main/hostapd/hostapd.conf)

The app should keep these stages separate:

| Evidence | What it establishes | What it does not establish |
|---|---|---|
| Beacon/probe RSN MFPC/MFPR | Advertised BSS policy at that time | A client's negotiated protection |
| Matched association request/response | Requested/accepted capability evidence | Installed keys or cryptographic authenticity of the capture |
| Successful key-establishment evidence plus trusted endpoint PMF state | Stronger evidence for that association's active PMF | Correct rejection of every invalid frame |
| Protected unicast format or group MMIE | A protection format is present | A valid MIC, fresh replay counter, or acceptance |
| Controlled challenge, independent peer state and control run | Behavior under the exact tested conditions | A universal bypass or universal immunity |

An ordinary MAC ACK is not evidence that the recipient accepted the frame at the
PMF/state-machine layer. Group integrity protection also differs from encrypted
unicast: a group robust frame can carry an MMIE while its Protected bit is clear.
The new actual-TShark fixture exercises this distinction without claiming its
synthetic MIC is valid.

## Contemporary research

**PMFInspect, WiSec 2026** is directly relevant. Its authors evaluated IoT
provisioning and PMF behavior across 25 devices. The conference abstract reports
18 devices affected by combined weaknesses; that number must not be presented as
18 breaks of correctly negotiated PMF. The public artifact includes association
and deauthentication/disassociation captures for capability and robustness
reports. [Conference abstract](https://wisec26.events.cispa.de/program-accepted-papers/),
[author artifact](https://zenodo.org/records/19687498)

The publisher/full-paper and Inria repository could not be read through this
session's browser (access denied); the full method and code therefore remain
unreviewed. The available artifact is a 10 GB VM and was not downloaded. This is
a concrete comparison target, not a claim that its tests work on the OnePlus.

A separate February 2026 preprint reports resistance to its classical deauth
tests for the PMF/WPA3 configurations it measured. That is a useful control
result, not a general completeness proof. Its broad background wording about
management protection should not replace frame-specific implementation evidence.
[Authors' preprint](https://arxiv.org/html/2602.23513v1)

## OnePlus source trace

The retained source subset is under
`oneplus-modules/vendor/qcom/opensource/wlan/qcacld-3.0/`. Hashes and exact paths
are recorded in [DRIVER-SOURCES.json](DRIVER-SOURCES.json). These observations do
not establish source equivalence with the presently loaded binary or firmware.

| Location | Observed source behavior | Consequence for experiments |
|---|---|---|
| `core/wma/src/wma_data.c:2335` | PMF TX processing depends on interface/flag state, management subtype and per-peer context; unicast processing also examines the Protected flag. | Setting a header bit is not a substitute for the relevant association/key context. Firmware completion remains a separate observation. |
| `wma_data.c:2357` and `:2445` | Unicast processing obtains per-peer cipher lengths; group processing invokes the MMIE crypto helper. | Unicast and group paths need separate qualification. No arbitrary-target key capability follows from these functions. |
| `core/wma/src/wma_mgmt.c:3827` | Group verification can be performed locally or deferred to supported firmware offload, then the MMIE is trimmed at line 3891. | A postprocessed receive buffer can omit protection metadata that was present over the air. |
| `wma_mgmt.c:3907` | The protected-unicast path checks replay state and removes privacy header/trailer bytes. | Capture provenance matters; do not assume this path is the monitor capture path. |
| `wma_mgmt.c:4013` | An unprotected unicast frame is annotated with an error for subsequent handling. | Forwarding this buffer is not by itself a bypass. The later error consumer is absent from the retained subset and remains unresolved. |
| `wma_mgmt.c:570` | Firmware SA Query timeout is represented as a distinct peer-removal reason. | Preserve the specific cause when available; a generic disconnect loses useful attribution. |

The current app request is constructed at `runtime/qca-backend.py:305` and records
driver acknowledgement separately from client effects. No patch, firmware change,
new TX primitive, key extraction or handset query was performed in this pass.

## Additional handset evidence

The user's separate 2026-11-14 vendor survey was located and checked offline.
It adds working vendor-query evidence and demonstrates stale immediate readbacks
for dynamic bandwidth, followed by stable spaced reads and restoration. It also
led to a source-backed PMF telemetry candidate: `GET_STA_INFO` can package group
integrity/replay and separate beacon-protection counters when valid. That path
consumes cached validity, so absent fields must remain unknown. A subsequent live
check returned the four fields in five samples; their accuracy and freshness remain
unqualified. See [live counter results](COUNTER-LIVE-RESULTS.md), [vendor evidence and implications](VENDOR-EVIDENCE.md)
and [derived checks](VENDOR-EVIDENCE.json). These additions do not establish PMF
negotiation, enforcement, dwell, or a bypass, and do not change the released APK.

## Best next experiments

These are research hypotheses, not verified vulnerabilities or claims of novelty.
All live cases require a specifically selected owned AP/client, exact software
versions, fresh state and the existing ownership/recovery controls. An observation
must be possible before a disruptive step is enabled.

| Order | Hypothesis and control | Bounded execution and required observation | Stop condition |
|---|---|---|---|
| 1 | The same AP's optional and required policies produce different client negotiation. Compare controlled configurations and a known PMF-capable client. | One ordinary association per configuration. Capture request/response, selected AKM, key-establishment evidence and trusted AP/client PMF state. Keep credentials out of the report. | No usable association or endpoint PMF evidence: negotiation stays unknown. |
| 2 | A verified PMF association rejects an unprotected robust frame. Compare with a PMF-disabled control and a no-submission control. | Only the already bounded single-request path after explicit target selection. Record submission, on-air/receiver evidence, peer association state, independently observed effect and restoration separately. | Missing RF/receiver evidence or ambiguous outcome: no retry and no bypass verdict. |
| 3 | A protection gap is confined to association/rekey/reconnection state rather than established PMF. | One controlled reconnect, then an ordinary stable session; compare state timelines before scheduling any challenge. | Missing key-state transition or inability to distinguish normal reconnect from effect. |
| 4 | Managed capture processing hides PMF metadata visible at the AP. Compare capture paths for the same normal controlled exchange. | Observe one exchange at phone and controlled AP/client; correlate frame direction, timing and sequence. Use an AP-side capture/log if the phone cannot receive while transmitting. | Uncorrelated records or unqualified RX/TX concurrency. |
| 5 | SA Query behavior explains a reconnect/timeout failure. Compare a peer with retained keys against an intentional normal restart. | One reconnect observation; preserve protected/opaque action evidence, any decoded query IDs, configured timing and endpoint timeout reason. No flooding. | Opaque query body without endpoint corroboration, missing timing or uncertain peer identity. |
| 6 | Verified links/BSSIDs in one controlled deployment have inconsistent policy. | Passive comparison followed by normal per-link associations. Treat beacon protection, OCV and MLO support as separate capabilities. | Same SSID alone, missing MLD/link evidence or unsupported band. |

The phone's internal-radio requirement is preserved. Independent outcome evidence
can come from the controlled AP/client; it need not mean attaching another adapter
to the phone. IP traffic blocked by the app's preparation profile must not be
mistaken for a PMF-induced disconnect.

## Offline inspector and results

[inspector.py](inspector.py) reads PDML produced from a saved capture. It records
association hints, protected/unprotected management formats, group MMIE structure
and visible SA Query request/response matches. It never returns verified integrity,
receiver acceptance, installed keys or a successful bypass. Encrypted action
bodies can hide SA Query details. The caller-supplied capture-layer label is not
independently verified. Raw payloads, MICs, SSIDs and credential fields are omitted;
radio addresses remain private local evidence.

From the project root (use a binary-safe redirect for PDML on Windows):

```powershell
python -c "import subprocess; f=open('capture.pdml','wb'); subprocess.run(['tshark','-n','-r','capture.pcap','-T','pdml'],stdout=f,check=True); f.close()"
python research/pmf/inspector.py --pdml capture.pdml --output pmf-report.json
python -m unittest discover -s research/pmf -p 'test_*.py' -v
```

Fourteen tests pass, including a real TShark PCAP with a group MMIE and visible
SA Query exchange, malformed/duplicate field cases, association ambiguity and
missing evidence. Full local evidence is under `validation/pmf-20261114/`.
Public metadata only: [RESULTS.json](RESULTS.json).

The three retained captures contain **1,947 frames**. Roku advertises PMF capable
but not required; the other two captures advertise PMF disabled. There are no
association requests/responses, EAPOL exchanges, deauth/disassociation/action
observations or SA Query pairs in these captures. They cannot test negotiated
PMF or enforcement. No handset or live RF operation was performed.

The first useful live measurement is experiment 1: obtain one complete, ordinary
PMF-required association and corroborate the negotiated state. Until then, the
app should display advertised policy and explicit evidence gaps rather than a
PMF bypass action.
