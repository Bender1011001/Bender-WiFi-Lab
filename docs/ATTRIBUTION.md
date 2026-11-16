# Attribution and source information

Maintainer: [Bender1011001](https://github.com/Bender1011001).

Hijacker-QCA derives from Christos Kyriakopoulos and contributors' [Hijacker](https://github.com/chrisk44/Hijacker/tree/898af5b530dd000b8251f21da94626d9e41e53d2). Its GPL notices and upstream material are preserved in the source snapshot. Bender Wi-Fi Lab is the separate native app; five QCA runtime assets were adapted from that derivative. Bender project code is GPL-3.0-or-later, with `COPYING` and runtime attribution in its source archive.

The app includes sources/notices for [Pixiewps](https://github.com/wiire-a/pixiewps) at revision `30c22cf59e09c732682a733227a652cc9fe0685f`, Android's [wpa_supplicant source](https://android.googlesource.com/platform/external/wpa_supplicant_8) at `ab49c33aef291e4d9cabe9a16f535bca40569a10`, and the pinned libnl-tiny dependency. Their source, build metadata and licenses remain in `third_party/`; project-specific changes are retained with the source.

The offline ARM64 image is built from Debian 12 packages. Its manifest identifies exact binary versions and signed repository metadata hashes; package copyright/license files are retained under `usr/share/doc/*/copyright` inside the image. See [runtime source locations](RUNTIME-SOURCES.md) and [package/source mapping](../evidence/runtime-sources.json). This project does not claim authorship of Debian, Wireshark/TShark, Android, Qualcomm, Kali NetHunter or their dependencies.

The [prior-work comparison](PRIOR-WORK.md) credits relevant Nexmon, QCACLD and AviumUI research. No vendor firmware or proprietary driver is included in these application source packages. Phone experiments used components already installed on the owned handset.

Development, debugging, documentation and critique used AI assistants. Those reviews and automated checks are not external audits, independent laboratory replication, or proof of universal compatibility. The maintainer's recorded experiments and exact validation scope are identified separately.

The Bender name and pixel-art robot are project theming, not a claim of affiliation with any entertainment property. Android development signatures identify a preview build, not a production signing or identity-verification service.
