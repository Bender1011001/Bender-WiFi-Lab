# Where this fits

Primary sources checked during release preparation on November 15, 2026. This is a comparison of relevant documented work, not an exhaustive novelty search.

| Project | Relevant documented work | Relationship to this result |
|---|---|---|
| [Nexmon](https://github.com/seemoo-lab/nexmon/blob/master/README.md) | Firmware-based monitor/injection support on specific Broadcom phones; the matrix includes Galaxy S10/S20 configurations | Establishes substantial earlier internal-radio phone work. A different chipset/path does not justify a first-ever claim |
| [Kali 2026.2 QCACLD work](https://www.kali.org/blog/kali-linux-2026-2-release/#the-qcacld30-injection-story) | Documents Qualcomm injection work and kernel support across selected devices, crediting Kimocoder, Loukious and cyberknight777 | Qualcomm phone injection already has prior art. Our result is the tested stock-driver OnePlus 13 managed-frame path and its workflow/evidence |
| [AviumUI WLAN tools](https://github.com/spiral009/aviumui-wlan-tools/blob/master/README.md) | Related OnePlus/SM8750 internal monitor work; its README describes a reachable kernel injection path with firmware preventing successful over-the-air injection in its tests | Closely related hardware research deserves explicit credit. Its monitor-injection limitation is compatible with a narrower managed deauth path working |
| [Original Hijacker](https://github.com/chrisk44/Hijacker/tree/898af5b530dd000b8251f21da94626d9e41e53d2) | Android Wi-Fi testing interface and upstream code/resources used by the Hijacker-QCA derivative | Credit the original project. Inherited menus and tools are not evidence that all upstream features work on this radio |

The defensible contribution is a useful, documented result on a particular recent handset, with independent observation channels inside the maintainer's lab and a simpler Android workflow. The published material should invite comparison and reproduction rather than claim that similar work cannot exist elsewhere.

Neither this comparison nor the lab evidence establishes a previously unknown security vulnerability. The normal role of PMF and the unresolved monitor-injection path remain explicit in the report.
