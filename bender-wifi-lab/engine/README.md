# Offline observation engine

Python 3.9+ standard library; TShark must be installed in the same execution environment. No radio access, passwords, hosted service or network connection is used. From this directory:

```
python -m bender_engine analyze --pcap capture.pcap --output observations-v1.json --tshark /usr/bin/tshark
```

The module can also be imported with `analyze_pcap(path, tshark, deadline=absolute_monotonic_time, cancel_file=optional_path)` or `analyze_pdml(path, artifact_sha256, deadline=absolute_monotonic_time, cancel_file=optional_path)`. Omitted deadlines default to 120 seconds from entry. Android packaging must put the directory containing `bender_engine` on Python's module search path. Analysis fails with nonzero status on missing decoder, malformed XML, decoder failure or exceeded budgets. Output is atomically replaced only after successful analysis and serialization. CLI `--cancel-file PATH` cooperatively cancels when that file exists and exits 130; other failures exit 2. Partial temporary output is removed and any existing result retained.

Each network has frame/time scoped `advertisements`, containing separate `security_ies` entries. Each IE preserves ordered AKMs, pairwise ciphers, group ciphers and group management ciphers, selectors' OUI/type/raw bytes, declared counts, capabilities and parse status. `advertised_akms` is a unique display summary of qualified decoded IEs; consult individual IEs for multiplicity. Incomplete or decoder-marked malformed security IEs retain their partial selectors/capabilities but cannot establish the high-level AKM/security/PMF summary. Required-PMF without capable-PMF is malformed. SSIDs retain original bytes as hex and use replacement decoding for display. PSK plus SAE advertisements retain both suites. Conflicting PMF advertisements remain unknown with their states retained. No missing IE implies open security.

Nested nontransmitted-profile security is retained in `nested_profile_security_ies`, with an owning tag path and unresolved BSSID. It never supplies the enclosing BSS's SSID, security, WPS or MLO summary. Each nested security IE appears once.

Client `ap_id` is the last observed radio-addressing relation, never proof of continuing association. Station attribution uses explicit transmitter/receiver roles and DS direction; logical wired SA/DA endpoints stay as relation metadata. WDS, IBSS and missing direction evidence do not establish an infrastructure AP/client relationship. `relations` retain each frame/time, AP, and association-request/response security IEs/status where present. No negotiated PMF/security state is inferred from these frames. MLO common MLD MAC/link ID is exposed only when explicitly decoded; unrecognized MLO is present/unknown. MLD identity remains separate from BSSID. WPS metadata is grouped by IE and allowlisted; device strings, credentials, PINs and network keys are excluded.

EAPOL pairwise exchanges require direction, replay-counter progression, matching ANonce, consistent SNonce and a ten-second adjacent-message window. Ambiguous matches stay partial. Missing initial messages, nonce mismatch, missing replay and expired evidence cannot establish four-message coverage. `four_messages_observed` is packet coverage only; MIC/password verification remains false. Retransmissions can be retained. A conservative matcher may split unusual valid exchanges. Nonces, MICs and key data never enter result JSON.

Budgets: 128 MiB PDML, 100,000 packets, 12,000 XML elements per packet and one shared 120-second cooperative deadline across hashing, decoding and parsing. CLI serialization shares that same deadline. Checks run between hash/XML-read chunks, decoder polls, parser event batches/packets and serializer chunk batches. This is not an operating-system hard real-time guarantee: an individual filesystem/OS call, one XML token or subprocess termination can exceed the deadline before control returns. Cancellation uses the same checkpoints and kills/waits for the owned TShark child. Signal/process-group supervision remains the caller's responsibility; the Python API cannot guarantee cleanup if externally killed. Decoder stderr is bounded to 1 MiB and not copied to results. DTD/entities are rejected. Retained detailed evidence grows with capture length; oversized captures must be split offline. TShark version differences can leave unsupported fields unknown. No live handset qualification was performed.

Run tests from the project root:

```
python -m unittest discover -s tests/engine -v
```

Tests synthesize structured PDML and, when TShark is installed, a four-message 802.11 EAPOL PCAP. No private PCAP is bundled. `synthetic-demo-v1.json` is explicitly synthetic and must only be loaded through an opt-in demo action, never as startup inventory or discovered evidence.

The 0.1.1 review run passed all 25 engine tests, including real TShark DS-addressing
and nested-BSSID PCAP regressions, and replayed the three retained captures without
changing the summary below. Local evidence: `validation/review-20261113/replay.json`.

Offline replay evidence is summarized without network/client identifiers in `OFFLINE_VALIDATION.json`. Three retained captures contain 1,947 frames. The Roku capture has 385 frames, 231 advertisements, AKMs 2 and 8 and PMF capable/optional. Its vendor IEs also contain WPS version/configured-state attributes: `-Y wps` alone misses these nested fields, so the prior no-WPS baseline was incorrect. WPS advertisement presence does not establish PIN availability, enrolment capability, setup unlock or exploitability. None of the three captures supplied association frames or EAPOL exchanges. Twenty-one synthetic tests passed on the Windows host, including an actual TShark 4.6.2 PCAP decode, malformed-summary regressions and shared-deadline/cancellation behavior. Live radio and Android/chroot decoder compatibility remain unqualified.

Device inventory update (0.5.2): two-address RTS, Block ACK/request and PS-Poll traffic can identify additional radio peers when exactly one endpoint is an AP directly advertised in the same capture. Known channel conflicts, AP-to-AP pairs, failed FCS and malformed controls are excluded; ACK/CTS alone do not identify a relationship. Repeated control evidence is grouped by endpoint pair, subtype and frequency, preserving first/last time, last frame and frame count. This is observed addressing, not proof of continuing association. Latest client relationships follow packet timestamps even when capture ordering differs.

`bss_load` retains a directly owned, complete BSS Load IE's station count with frame/time provenance. Zero is a valid AP report; missing, malformed, duplicate and nested-only reports stay unknown. This per-BSSID AP report is separate from identified address counts and is never summed across same-name APs or presented as a live physical-device census.
