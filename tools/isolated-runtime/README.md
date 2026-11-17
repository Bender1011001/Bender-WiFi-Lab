# Isolated runtime for the experimental harness

This replaces the mount setup used by the November 16 terminal experiment. It
does not change the Android app or transmit a Wi-Fi frame. The executed original
scripts remain preserved privately; this replacement was written afterward.

The original wrapper's `unshare` plus shell propagation command left duplicate
mounts visible outside its intended private setup. A normal cleanup reboot
removed them. The exact shell/binary failure mechanism was not established.

## What changed

The launcher calls `unshare(CLONE_NEWNS)` and checks that the mount namespace
changed. It then calls `mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL)` and
reads every mountinfo propagation tag before creating a binding. A successful
return code alone is insufficient. This uses the documented Linux
[mount syscall](https://man7.org/linux/man-pages/man2/mount.2.html) and
[mount namespace semantics](https://man7.org/linux/man-pages/man7/mount_namespaces.7.html).

Only four directories are bound: `/proc`, `/sys`, `/dev` and the existing shared
radio-lock directory. Source and destination descriptors are opened **after**
namespace creation. Opening them before `unshare` retains references to the
parent's mounts; the first offline test run caught this and it was corrected.
Every binding is checked by device/inode and mount-table count. Existing or
nested mounts at a destination are refused; the launcher never stacks or
unmounts them. All namespace and propagation checks are repeated during setup.

The runtime and shared source must be canonical, separate directories. Each
destination and source is opened component by component without following
symlinks. Final directories must be root-owned and not group/world writable.
Ancestors such as Android's shell-owned `/data/local/tmp` are supported. The
caller must serialize runtime-tree changes with other root processes.

The launcher executes one foreground command in the chroot. Its private mounts
are released when the last process/reference in that namespace is gone. A
command that leaves descendants running can keep its own namespace alive; this
helper is not a daemon supervisor. **This is mount isolation, not a security
sandbox:** the child retains root, device access and the original network/PID
namespaces. The original harness still owns radio locking, cancellation,
restoration and its durable no-replay record.

## Offline validation

On November 17, **21 Linux integration tests passed** on the development
computer's WSL kernel `6.6.87.2-microsoft-standard-WSL2`. They used empty temporary
roots and a static probe, with no phone, adapter, network test or radio access.

Cases cover ordinary and shared-parent success, concurrent children, command
failure, missing executable, SIGKILL, denied/no-op namespace and propagation
calls, failure after a partial setup, a false-success bind call, existing/nested
mounts, symlink escapes, missing targets, permissions, shell-owned ancestors and
paths with spaces. Every test compares the caller's complete mount table and
namespace before and after. Fault injection is confined to the test build.
The [complete passing log](../../evidence/harness-isolation-linux-20261117.txt)
is included with the source.

Run on a development Linux machine with root, GCC and Python already installed:

```sh
sudo python3 test_isolation.py
```

The Android ARM64 executable builds with the installed NDK; compiler warnings
are errors. [The build record](../../evidence/harness-isolation-20261117.json)
identifies the source, binary and test inputs. Host tests and subsequent handset
checks have separate scopes.

```text
python build_android.py --ndk /path/to/installed/ndk --output /local/build/isolated-runtime --record /local/build/build.json
```

## Interface and handset check

```text
isolated-runtime ROOT SHARED -- /absolute/foreground-command [arguments...]
```

The expected destination directories must already exist under `ROOT`:
`proc`, `sys`, `dev`, and `tmp/fresh-boot-air-20261116/shared`. Source directories
are bound non-recursively; this harness does not require nested PTY mounts.
Exit 125 means setup or exec failed. Otherwise the executed command's exit code
is preserved. There is no automatic retry and no cleanup unmount command.

On November 17 the exact Android ARM64 binary also ran three read-only Python
probes on CPH2655, Android build `CPH2655_16.0.8.301(EX01)`: normal completion,
deliberate exit 23, and forced termination (exit 137). Each child used a different
mount namespace, acquired the original shared radio lock and read `/proc`,
`/sys` and `/dev`. The caller's and Android init's complete mount tables were
unchanged after every case; all child processes exited. The original Wi-Fi
association/frequency remained, and the final gateway check passed 3/3.
[Handset results](../../evidence/harness-isolation-phone-20261117.json) retain
the binary hash and scope. Denied/no-op syscall fault injection was host-only.

These handset checks made no management-frame request and did not execute the
original transmit harness. Do not remove the completed experiment's intent
marker or rerun that job.

The helper and test sources use GPL-3.0-or-later, consistent with the Bender
project. See [COPYING](../../bender-wifi-lab/COPYING).
