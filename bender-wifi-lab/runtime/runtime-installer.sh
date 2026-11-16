#!/system/bin/sh
# Install only the runtime embedded in this APK. No downloads or radio commands.
set -eu
export PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/magisk:$PATH
SID=${1:-invalid}; OUT=${2:-invalid}; OWNER=${3:-0}; ARCHIVE=${4:-invalid}
EXPECTED=${5:-invalid}; REQUIRED=${6:-0}
BASE=/data/local/bender-wifi
FINAL=$BASE/runtime-arm64-v1
STAGE=$BASE/stage-$SID
CPID=''; MOUNTS=''; STAGE_CREATED=false
fail(){ echo "ERROR:$*"; exit 1; }
phase(){ echo "PHASE:$*"; }
case "$SID" in bw_[0-9]*_[a-f0-9]*) ;; *) fail 'Invalid setup session';; esac
case "$SID" in *[!a-zA-Z0-9_]*) fail 'Invalid setup session';; esac
case "$OUT" in /data/user/0/dev.sir.benderwifi/files/qca-sessions/"$SID"|/data/data/dev.sir.benderwifi/files/qca-sessions/"$SID") ;; *) fail 'Invalid output path';; esac
case "$ARCHIVE" in /data/user/0/dev.sir.benderwifi/files/runtime-package/rootfs.tar.gz|/data/data/dev.sir.benderwifi/files/runtime-package/rootfs.tar.gz) ;; *) fail 'Invalid runtime package';; esac
case "$OWNER:$REQUIRED" in *[!0-9:]*) fail 'Invalid numeric parameter';; esac
[ "${#EXPECTED}" = 64 ] || fail 'Invalid runtime digest'
case "$EXPECTED" in *[!0-9a-f]*) fail 'Invalid runtime digest';; esac
[ "$(id -u)" = 0 ] || fail 'Grant root access to install the runtime'
[ "$(uname -m)" = aarch64 ] || fail 'This runtime requires an ARM64 phone'
check_cancel(){ [ ! -f "$OUT/cancel" ] && kill -0 "$OWNER" 2>/dev/null || exit 130; }
private_dir(){
    [ ! -L "$1" ] || fail 'Setup path must not be a symlink'
    [ -e "$1" ] || mkdir -m 700 "$1"
    [ -d "$1" ] && [ "$(stat -c %u "$1")" = 0 ] || fail 'Setup directory is not private root storage'
    case "$(stat -c %a "$1")" in *[2367][0-7]|*[2367]) fail 'Setup directory is writable by others';; esac
}
private_dir /data/local/tmp/hijacker-qca
LOCK=/data/local/tmp/hijacker-qca/.radio-mutation.lock
[ ! -L "$LOCK" ] || fail 'Invalid setup lock'
umask 077
exec 9>> "$LOCK"
[ "$(stat -c %u "$LOCK")" = 0 ] && [ "$(stat -c %h "$LOCK")" = 1 ] || fail 'Untrusted setup lock'
case "$(stat -c %a "$LOCK")" in *[2367][0-7]|*[2367]) fail 'Setup lock is writable by others';; esac
toybox flock -n 9 9>&9 || fail 'Another radio or setup operation is running'
[ ! -e /data/local/tmp/hijacker-qca/recovery/pending ] || fail 'Restore the previous Wi-Fi session before setup'
private_dir "$BASE"
unmount_stage(){
    remaining=''
    for part in $MOUNTS; do
        if umount "$STAGE/$part"; then :; else remaining="$remaining $part"; fi
    done
    MOUNTS=$remaining
    [ -z "$MOUNTS" ]
}
cleanup(){
    code=$?;trap - EXIT HUP INT TERM
    # Cleanup must continue even when evidence storage is full or unavailable.
    set +e
    if [ -n "$CPID" ]; then kill -TERM "$CPID" 2>/dev/null || true;wait "$CPID" 2>/dev/null || true;CPID='';fi
    unmount_stage || code=1
    # A failed install must be retryable without accumulating unpacked images.
    # Never remove a stage with unresolved mounts. Logs stay in the app session.
    if [ "$STAGE_CREATED" = true ] && [ -d "$STAGE" ] && [ ! -L "$STAGE" ] && [ -z "$MOUNTS" ]; then
        if awk -v p="$STAGE" '$5==p || index($5,p"/")==1 {found=1} END {exit !found}' /proc/self/mountinfo; then
            echo 'ERROR:Setup mounts remain; staging files were preserved for recovery'
        else
            case "$STAGE" in "$BASE"/stage-bw_*) rm -rf "$STAGE" || code=1;; esac
        fi
    fi
    if [ "$code" != 0 ]; then
        printf '{"ok":false,"action":"setup","cancelled":%s,"radio_mutated":false}\n' "$([ "$code" = 130 ] && echo true || echo false)" > "$OUT/result.json" || echo 'ERROR:Could not save setup result'
        printf '{"runtime_ready":false,"message":"Setup did not finish. Existing runtime and networks were preserved. Retry setup after resolving the displayed error."}\n' > "$OUT/runtime-state.json" || echo 'ERROR:Could not save setup state'
    fi
    echo "EXIT:$code";exit "$code"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM
check_cancel
if [ -e "$FINAL" ]; then
    private_dir "$FINAL"
    [ -f "$FINAL/.bender-ready" ] && [ ! -L "$FINAL/.bender-ready" ] || fail 'Existing runtime is incomplete; preserve it for recovery'
    [ "$(cat "$FINAL/.bender-ready")" = "$EXPECTED" ] || fail 'A different runtime is installed; an explicit upgrade is required'
    printf '{"ok":true,"action":"setup","already_installed":true,"radio_mutated":false}\n' > "$OUT/result.json"
    printf '{"runtime_ready":true,"provider":"bundled","message":"Private runtime is installed. Check the radio to verify this phone."}\n' > "$OUT/runtime-state.json"
    exit 0
fi
FREE=$(df -k "$BASE" | tail -n 1 | awk '{print $4}')
case "$FREE" in ''|*[!0-9]*) fail 'Cannot determine free storage';; esac
[ "$FREE" -ge "$REQUIRED" ] || fail 'Not enough storage for the private runtime'
[ ! -e "$STAGE" ] && [ ! -L "$STAGE" ] || fail 'Setup staging directory already exists'
mkdir -m 700 "$STAGE"
STAGE_CREATED=true
phase 'Verifying the bundled runtime'
[ -f "$ARCHIVE" ] && [ ! -L "$ARCHIVE" ] || fail 'Runtime archive is missing'
cp "$ARCHIVE" "$STAGE/package.tar.gz"
ACTUAL=$(sha256sum "$STAGE/package.tar.gz");ACTUAL=${ACTUAL%% *}
[ "$ACTUAL" = "$EXPECTED" ] || fail 'Runtime archive failed integrity verification'
check_cancel
phase 'Installing the private runtime; existing NetHunter is preserved'
# Package members are audited and hashed during the build. Hash verification
# above runs against the root-owned copy, preventing extraction-time replacement.
tar -xzf "$STAGE/package.tar.gz" -C "$STAGE" > "$OUT/install.log" 2>&1 &
CPID=$!; COUNT=0
while kill -0 "$CPID" 2>/dev/null; do check_cancel;[ "$COUNT" -lt 420 ] || fail 'Runtime extraction timed out';sleep 1;COUNT=$((COUNT+1));done
wait "$CPID" || fail 'Runtime extraction failed; inspect setup evidence'
CPID=''
rm "$STAGE/package.tar.gz"
for part in proc sys dev; do
    [ -d "$STAGE/$part" ] && [ ! -L "$STAGE/$part" ] || fail 'Invalid runtime mount directory'
    mount -o bind "/$part" "$STAGE/$part" || fail 'Could not prepare the runtime mounts'
    MOUNTS="$part $MOUNTS"
done
check_cancel
phase 'Checking Python, packet decoder and Wi-Fi utilities'
timeout 30 chroot "$STAGE" /usr/bin/python3 -I -c 'import ctypes,json,ssl,sqlite3; ctypes.CDLL("libpcap.so.0.8"); print("Python and capture library ready")' >> "$OUT/install.log" 2>&1 || fail 'Python runtime self-check failed'
timeout 30 chroot "$STAGE" /usr/bin/tshark --version >> "$OUT/install.log" 2>&1 || fail 'Packet decoder self-check failed'
timeout 30 chroot "$STAGE" /usr/sbin/iw --version >> "$OUT/install.log" 2>&1 || fail 'Wi-Fi utility self-check failed'
check_cancel
unmount_stage || fail 'Runtime mount cleanup failed'
printf '%s\n' "$EXPECTED" > "$STAGE/.bender-ready"
mv "$STAGE" "$FINAL"
printf '{"ok":true,"action":"setup","radio_mutated":false}\n' > "$OUT/result.json"
printf '{"runtime_ready":true,"provider":"bundled","message":"Runtime installed. Run Check radio, then Scan networks."}\n' > "$OUT/runtime-state.json"
phase 'Runtime installed; ready to check the radio'
