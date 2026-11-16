# Bundled runtime build

The APK contains a private ARM64 Debian 12 runtime: Python 3, libpcap, TShark,
iw, iproute2 and procps. Installation is local and offline. It does not install
or replace NetHunter. Root and a compatible handset remain required for radio
operations; APK installation alone does not establish hardware support.

Build in an isolated Linux environment with root privileges. On this Windows
workstation WSL Ubuntu and registered qemu-user-static binfmt support were used.
These commands build a new staging root, not the phone's installed runtime:

```sh
apt-get update
apt-get install -y debootstrap qemu-user-static debian-archive-keyring
debootstrap --arch=arm64 --foreign --variant=minbase \
  --include=python3,tshark,iw,iproute2,procps \
  --keyring=/usr/share/keyrings/debian-archive-keyring.gpg \
  bookworm /var/tmp/bender-runtime-BUILD https://deb.debian.org/debian
chroot /var/tmp/bender-runtime-BUILD /debootstrap/debootstrap --second-stage
```

Before upgrades, create `usr/sbin/policy-rc.d` in that root containing
`#!/bin/sh` followed by `exit 101`, mode 0755, to prevent service startup.
Configure `etc/apt/sources.list` inside the build root with:

```text
deb https://deb.debian.org/debian bookworm main
deb https://deb.debian.org/debian bookworm-updates main
deb https://security.debian.org/debian-security bookworm-security main
```

Run `chroot ROOT apt-get update` then `chroot ROOT apt-get upgrade -y`.
Keep repository signature verification enabled. Run the synthetic engine suite
with the ARM64 Python and TShark inside that root, and verify every scanner field
against `tshark -G fields`. The first packaged image's exact versions and signed
repository metadata hashes are in `runtime-bundle/manifest.json`.

Unmount any bind mounts under the build root before packaging:

```sh
python3 tools/package_runtime.py --rootfs /var/tmp/bender-runtime-BUILD \
  --output runtime-bundle
```

The packager only accepts dedicated `/var/tmp/bender-runtime-*` roots. It removes
caches, temporary files, device nodes and build-specific host identity, audits
archive paths and symlinks, strips privilege bits, and preserves Debian package
copyright files and package inventory. Source packages corresponding to those
exact versions are available from the Debian repositories listed above.

The Gradle build verifies the image size and SHA-256 before staging APK assets.
It stores the gzip archive as an uncompressed `rootfs.bundle` APK entry because
AAPT otherwise expands and renames `.gz` assets. Release verification checks the
actual APK entry's size and SHA-256, not just Gradle's staging directory.
The Android app verifies them again while copying the asset; the root installer
hashes its own private copy before extraction. Setup commits a ready marker only
after ARM64 executable self-checks and mount cleanup. Cancellation and failure
do not replace an existing runtime. Failed extraction is cleaned up unless a
mount remains unresolved, in which case the stage is retained for recovery.

An image upgrade is deliberately a separate operation: this preview refuses to
overwrite a different installed runtime. Preserve and review its recovery state
before adding an upgrade path. No downloads occur during setup or radio use.
