#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Root supervisor. Capture is bounded even if the Android process disappears.
set -eu
export PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/magisk:$PATH
ACTION=${1:-probe}
SID=${2:-invalid}
OUT=${3:-invalid}
OWNER=${4:-0}
BSSID=${5:-all}
WPS_NATIVE=${6:-}
BASE=/data/local/tmp/hijacker-qca
KALI=/data/local/nhsystem/kali-arm64
if [ -f /data/local/bender-wifi/runtime-arm64-v1/.bender-ready ]; then
    KALI=/data/local/bender-wifi/runtime-arm64-v1
fi
PARAM=/sys/module/qca_cld3_peach_v2/parameters/con_mode
FWPARAM=/sys/module/firmware_class/parameters/path
STATE=$BASE/recovery
JOB=$KALI/tmp/hijacker-qca/$SID
SOURCE=${0%/*}
CPID=''
LOCKED=0
TOUCHED=0
CAPTURED=0
RESTORATION=not_attempted
RESTORATION_VERIFIED=false
RADIO_MUTATED=false
DELETED_FILES=0

fail() { echo "ERROR:$*"; exit 1; }
runtime_unavailable() {
    printf '{"runtime_ready":false,"message":"Runtime check failed. Review the session error, then check the radio or install the tools."}\n' > "$OUT/runtime-state.json" || true
    fail "$*"
}
phase() { echo "PHASE:$*"; }
trusted_path() {
    [ ! -L "$1" ] || fail "Refusing symlink: $1"
    [ "$(stat -c %u "$1")" = 0 ] || fail "Path must be root-owned: $1"
    ACCESS=$(stat -c %a "$1")
    case "$ACCESS" in ''|*[!0-7]*) fail "Invalid permissions: $1";; esac
    # Android mksh treats leading-zero arithmetic as decimal. Check the group
    # and other octal digits directly so both Android and host shells agree.
    case "$ACCESS" in *[2367][0-7]|*[2367]) fail "Path is writable by others: $1";; esac
}
private_dir() {
    [ ! -L "$1" ] || fail "Refusing symlink: $1"
    if [ ! -e "$1" ]; then mkdir -m 700 "$1" || fail "Cannot create private directory: $1"; fi
    [ -d "$1" ] || fail "Not a directory: $1"
    trusted_path "$1"
    chmod 700 "$1"
}
private_file() {
    [ ! -L "$1" ] || fail "Refusing symlink: $1"
    if [ -e "$1" ]; then
        [ -f "$1" ] || fail "Not a regular file: $1"
        trusted_path "$1"
        [ "$(stat -c %h "$1")" = 1 ] || fail "Refusing hard-linked file: $1"
    fi
}
case "$ACTION" in probe|capture|lab|wps|restore|analyze|identity|prepare|privacy_restore|privacy_status|recovery_status|delete_capture|cleanup_captures) ;; *) fail 'Unknown operation';; esac
case "$SID" in ''|*[!a-zA-Z0-9_-]*) fail 'Invalid session';; esac
case "$OWNER" in ''|*[!0-9]*) fail 'Invalid owner PID';; esac
case "$OUT" in
    /data/user/0/dev.sir.benderwifi/files/qca-sessions/"$SID"|/data/data/dev.sir.benderwifi/files/qca-sessions/"$SID") ;;
    *) fail 'Invalid app output directory';;
esac
[ "$(id -u)" = 0 ] || fail 'Root access was not granted'
[ -d "$OUT" ] || fail 'Missing app session directory'
# Read-only inventory remains available even when a dependency is missing.
# It contains no SSIDs, MACs, serials or captures, and is never evaluated as code.
HASH=$(sha256sum /vendor_dlkm/lib/modules/qca_cld3_peach_v2.ko 2>/dev/null || true)
printf 'model=%s\nbuild=%s\nkernel=%s\ninstalled_driver_sha256=%s\nchroot_path=%s\n' \
    "$(getprop ro.product.model)" "$(getprop ro.build.display.id)" "$(uname -r)" "${HASH%% *}" "$KALI" > "$OUT/environment.txt"
case "$ACTION" in analyze|identity|prepare|privacy_restore|privacy_status|recovery_status|delete_capture|cleanup_captures) ;; *) [ -f "$PARAM" ] || fail 'qca_cld3_peach_v2 is not installed';; esac
private_dir "$BASE"
# Keep the same kernel lock as setup/removal/recovery for the entire operation.
# Never unlink this file: replacing its inode would split the exclusion domain.
# Android toybox keeps this lock on the inherited open file description after
# the flock command exits. Restore does not depend on a functioning Kali chroot.
private_file "$BASE/.radio-mutation.lock"
umask 077
exec 9>> "$BASE/.radio-mutation.lock"
# mksh closes descriptors for external commands unless explicitly redirected.
# Pass FD 9 to flock while retaining the parent's open file description.
toybox flock -n 9 9>&9 || fail 'Radio/setup mutex is busy or Android flock is unavailable'

restore_radio() {
    [ -f "$STATE/pending" ] || return 0
    RESTORATION=failed
    RADIO_MUTATED=true
    RESTORATION_VERIFIED=false
    phase 'Restoring normal Wi-Fi'
    ip link set wlan0 down 2>/dev/null || true
    printf '0\n' > "$PARAM" || return 1
    sleep 3
    printf '%s\n' "$(cat "$STATE/firmware")" > "$FWPARAM" || return 1
    if [ "$(cat "$STATE/wifi")" = 1 ]; then svc wifi enable || return 1
    else svc wifi disable || return 1; fi
    # Restoring the setting is separate from reconnecting to an access point.
    TRIES=0
    while [ "$TRIES" -lt 10 ]; do
        [ "$(settings get global wifi_on)" = "$(cat "$STATE/wifi")" ] && break
        sleep 1
        TRIES=$((TRIES + 1))
    done
    [ "$(cat "$PARAM")" = 0 ] || return 1
    [ "$(cat "$FWPARAM")" = "$(cat "$STATE/firmware")" ] || return 1
    [ "$(settings get global wifi_on)" = "$(cat "$STATE/wifi")" ] || return 1
    # Do not clear the recovery marker while a radiotap interface remains.
    if [ -f /sys/class/net/wlan0/type ]; then
        [ "$(cat /sys/class/net/wlan0/type)" != 803 ] || return 1
    fi
    rm -f "$STATE/pending"
    TOUCHED=0
    RESTORATION=saved_state_restored
    RESTORATION_VERIFIED=true
    phase 'Managed mode, firmware path and Wi-Fi setting restored; association is separate'
}

stop_child() {
    [ -n "$CPID" ] || return 0
    # A worker must prove its private group and exact session before group kill.
    GROUP=false
    if [ -f "$JOB/worker.pid" ] && [ "$(cat "$JOB/worker.pid")" = "$CPID" ] &&
       [ -r "/proc/$CPID/cmdline" ] &&
       tr '\000' ' ' < "/proc/$CPID/cmdline" | grep -Fq "/tmp/hijacker-qca/$SID"; then
        GROUP=true
    fi
    if [ "$GROUP" = true ]; then kill -TERM -- "-$CPID" 2>/dev/null || true
    else kill -INT "$CPID" 2>/dev/null || true; fi
    WAIT=0
    while kill -0 "$CPID" 2>/dev/null && [ "$WAIT" -lt 10 ]; do
        sleep 1
        WAIT=$((WAIT + 1))
    done
    if kill -0 "$CPID" 2>/dev/null; then
        kill -TERM "$CPID" 2>/dev/null || true
        sleep 1
        kill -KILL "$CPID" 2>/dev/null || true
    fi
    if [ "$GROUP" = true ]; then kill -KILL -- "-$CPID" 2>/dev/null || true; fi
    wait "$CPID" 2>/dev/null || true
    # A killed Python supervisor must not leave its private decoder group alive.
    if [ -f "$JOB/decoder.pid" ]; then
        DECODER=$(cat "$JOB/decoder.pid")
        case "$DECODER" in ''|*[!0-9]*) ;; *)
            if [ -r "/proc/$DECODER/cmdline" ] && tr '\000' ' ' < "/proc/$DECODER/cmdline" | grep -Fq "/tmp/hijacker-qca/$SID/capture-01.cap"; then
                kill -KILL -- "-$DECODER" 2>/dev/null || true
            fi;;
        esac
    fi
    CPID=''
    rm -f "$BASE/lock/child"
}

stage_engine() {
# Stage only flat Python modules; reject source and destination symlinks.
private_file "$KALI/tmp/hijacker-qca/bin/bender-worker.py"
cp "$SOURCE/bender-worker.py" "$KALI/tmp/hijacker-qca/bin/bender-worker.py"
chmod 600 "$KALI/tmp/hijacker-qca/bin/bender-worker.py"
private_file "$KALI/tmp/hijacker-qca/bin/bender-engine.py"
cp "$SOURCE/bender-engine.py" "$KALI/tmp/hijacker-qca/bin/bender-engine.py"
chmod 600 "$KALI/tmp/hijacker-qca/bin/bender-engine.py"
private_dir "$KALI/tmp/hijacker-qca/bin/bender_engine"
for MODULE in "$SOURCE"/bender_engine/*.py; do
    [ -f "$MODULE" ] && [ ! -L "$MODULE" ] || fail 'Invalid engine module'
    NAME=${MODULE##*/}
    private_file "$KALI/tmp/hijacker-qca/bin/bender_engine/$NAME"
    cp "$MODULE" "$KALI/tmp/hijacker-qca/bin/bender_engine/$NAME"
    chmod 600 "$KALI/tmp/hijacker-qca/bin/bender_engine/$NAME"
done
}

copy_results() {
    # These destinations are pre-created by the app: retain its UID and SELinux label.
    for NAME in result.json attempt.json device.json compatibility.json scan-plan.json scan-summary.json decoder.log observations.json observations-v1.json analysis-state.json analysis.log identity-state.json privacy-state.json privacy-preparation.json; do
        if [ -f "$JOB/$NAME" ]; then cat "$JOB/$NAME" > "$OUT/$NAME" || return 1; fi
    done
    if [ -f "$JOB/capture-01.csv" ]; then cat "$JOB/capture-01.csv" > "$OUT/capture.csv" || return 1; fi
    if [ -f "$JOB/capture-01.cap" ]; then cat "$JOB/capture-01.cap" > "$OUT/capture.pcap" || return 1; fi
    if [ -f "$JOB/capture.log" ]; then cat "$JOB/capture.log" > "$OUT/capture.log" || return 1; fi
}

# Copies are transient. Delete only byte-verified capture duplicates after the
# app handoff; a failed copy keeps its only useful source and reports retention.
discard_handed_off_captures() {
    RETAINED=false
    for PAIR in 'capture-01.cap:capture.pcap' 'input.pcap:input.pcap' 'capture-01.csv:capture.csv' 'capture.log:capture.log' 'decoder.log:decoder.log'; do
        SRC=$JOB/${PAIR%%:*}; DEST=$OUT/${PAIR#*:}
        if [ -e "$SRC" ] || [ -L "$SRC" ]; then
            if [ -f "$SRC" ] && [ ! -L "$SRC" ] && [ -f "$DEST" ] && [ ! -L "$DEST" ] && cmp -s "$SRC" "$DEST"; then
                rm -f "$SRC" || RETAINED=true
            else RETAINED=true; fi
        fi
    done
    printf '{"runtime_capture_retained":%s,"scope":"current_session"}\n' "$RETAINED" > "$OUT/retention-state.json" || return 1
    [ "$RETAINED" = false ] || echo 'PHASE:Runtime capture copy retained; use Delete raw capture or Clear runtime copies in Evidence'
}

# Never recurse, follow links, glob capture filenames, or touch recovery/intent
# records. Only the Bender-generated session namespace is eligible for cleanup.
capture_session_name() { printf '%s\n' "$1" | grep -Eq '^bw_[0-9]+_[0-9a-f]{12}$'; }
remove_capture_files() {
    check_cancel
    DIRECTORY=$1
    [ ! -L "$DIRECTORY" ] || return 1
    [ -e "$DIRECTORY" ] || return 0
    [ -d "$DIRECTORY" ] || return 1
    for NAME in capture-01.cap input.pcap capture.pcap capture-01.csv capture.csv capture.log decoder.log; do
        check_cancel
        FILE=$DIRECTORY/$NAME
        [ ! -L "$FILE" ] || return 1
        if [ -e "$FILE" ]; then
            [ -f "$FILE" ] && [ "$(stat -c %h "$FILE")" = 1 ] || return 1
            rm -f "$FILE" || return 1
            DELETED_FILES=$((DELETED_FILES + 1))
        fi
    done
}

cleanup() {
    CODE=$?
    trap - EXIT HUP INT TERM
    stop_child
    if [ "$ACTION" = wps ] && [ -e "$BASE/bender-wps/pending.json" ]; then
        phase 'Removing the private WPS interface'
        if run_worker wps_restore; then
            RESTORATION=saved_state_restored; RESTORATION_VERIFIED=true
        else
            echo 'ERROR:WPS cleanup needs attention; use Restore Wi-Fi'; CODE=1
        fi
    fi
    if [ "$TOUCHED" = 1 ]; then
        restore_radio || { echo 'ERROR:Restore failed. Recovery state retained; use Restore Wi-Fi.'; CODE=1; }
    fi
    if [ "${AUTO_PRIVACY:-0}" = 1 ]; then
        if [ -f "$STATE/pending" ] || [ -e "$BASE/bender-wps/pending.json" ]; then
            echo 'PHASE:Radio restoration needs attention; privacy filter retained'
            CODE=1
        else
            phase 'Restoring test-session connectivity'
            run_worker session_restore || CODE=1
        fi
    fi
    if copy_results; then
        case "$ACTION" in capture|analyze) discard_handed_off_captures || CODE=1;; esac
    else
        CODE=1
        printf '{"runtime_capture_retained":true,"handoff_failed":true}\n' > "$OUT/retention-state.json" || true
    fi
    if [ -n "${BENDER_MOUNTED:-}" ]; then runtime_unmounts || {
        echo 'ERROR:Runtime mount cleanup needs attention'; CODE=1
        printf '{"runtime_ready":false,"message":"Runtime mounts need cleanup. Check the radio again to recover verified private mounts."}\n' > "$OUT/runtime-state.json" || true
    }; fi
    MANAGED=false; PENDING=false; PRIVACY_PENDING=false
    if [ "$(cat "$PARAM" 2>/dev/null || true)" = 0 ] &&
       [ ! -e /sys/module/qca_cld3_peach_v2/parameters/monitor_tx_ifname ] &&
       [ ! -e /data/local/tmp/nethunter-internal-wifi.lock ]; then
        MANAGED=true
    fi
    [ ! -f "$STATE/pending" ] || PENDING=true
    [ ! -e "$BASE/bender-wps/pending.json" ] || PENDING=true
    [ ! -e "$BASE/bender-privacy/pending.json" ] || PRIVACY_PENDING=true
    # A verified report can also flag owned hooks without a readable journal.
    if [ -f "$JOB/privacy-state.json" ] && grep -Eq '"pending"[[:space:]]*:[[:space:]]*true' "$JOB/privacy-state.json"; then
        PRIVACY_PENDING=true
    fi
    if [ "$RESTORATION" = not_attempted ] && [ "$MANAGED" = true ] && [ "$PENDING" = false ]; then
        RESTORATION=managed_mode_observed_no_saved_state
    fi
    printf '{"action":"%s","exit_code":%s,"managed_mode":%s,"recovery_pending":%s,"privacy_pending":%s,"restoration_verified":%s,"restoration_evidence":"%s"}\n' \
        "$ACTION" "$CODE" "$MANAGED" "$PENDING" "$PRIVACY_PENDING" "$RESTORATION_VERIFIED" "$RESTORATION" > "$OUT/radio-state.json" || CODE=1
    if [ "$LOCKED" = 1 ]; then
        rm -f "$BASE/lock/pid" "$BASE/lock/child"
        rmdir "$BASE/lock" 2>/dev/null || true
    fi
    echo "EXIT:$CODE"
    exit "$CODE"
}

if ! mkdir "$BASE/lock" 2>/dev/null; then
    OLD=$(cat "$BASE/lock/pid" 2>/dev/null || true)
    case "$OLD" in ''|*[!0-9]*) fail 'Incomplete radio lock; inspect /data/local/tmp/hijacker-qca/lock';; esac
    if kill -0 "$OLD" 2>/dev/null; then fail 'Another Hijacker radio operation is running'; fi
    [ ! -e "$BASE/lock/setup-owner.json" ] || fail 'Interrupted package setup: use setup.py recover-setup from the host'
    # An orphan capture is identified by its unique output directory, never by process name.
    CHILD=$(cat "$BASE/lock/child" 2>/dev/null || true)
    case "$CHILD" in ''|*[!0-9]*) ;; *)
        if [ -r "/proc/$CHILD/cmdline" ]; then
            tr '\000' ' ' < "/proc/$CHILD/cmdline" | grep -q '/tmp/hijacker-qca/' || fail 'Stale child PID belongs to another process'
            kill -TERM "$CHILD" 2>/dev/null || true
            sleep 1
            if kill -0 "$CHILD" 2>/dev/null; then
                kill -KILL "$CHILD" 2>/dev/null || true
                sleep 1
                kill -0 "$CHILD" 2>/dev/null && fail 'Orphan capture did not stop; recovery record retained'
            fi
        fi;;
    esac
    rm -f "$BASE/lock/pid" "$BASE/lock/child"
    rmdir "$BASE/lock"
    mkdir "$BASE/lock"
fi
LOCKED=1
echo $$ > "$BASE/lock/pid"
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

cancelled() { [ ! -f "$OUT/cancel" ] && kill -0 "$OWNER" 2>/dev/null; }
cancel_operation() {
    phase 'Cancelled'
    case "$ACTION" in delete_capture|cleanup_captures)
        printf '{"ok":false,"action":"%s","cancelled":true,"raw_capture_deletion_complete":false,"raw_capture_files_deleted":%s,"error":"Cleanup interrupted; some files may remain. No automatic retry."}\n' "$ACTION" "$DELETED_FILES" > "$OUT/result.json"
        exit 130;;
    esac
    # Stop the worker before reading intent; its existence makes lab outcome
    # uncertain even if cancellation arrived between intent and FRAME.
    [ ! -d "$JOB" ] || touch "$JOB/cancel"
    stop_child
    if [ -d "$JOB" ]; then
        MAYBE=false
        [ "$ACTION" != lab ] || [ ! -f "$JOB/attempt.json" ] || MAYBE=true
        printf '{"ok":false,"action":"%s","cancelled":true,"submission_may_have_occurred":%s,"radio_mutated":%s,"error":"Cancelled; no automatic retry"}\n' "$ACTION" "$MAYBE" "$RADIO_MUTATED" > "$JOB/result.json"
        if [ "$ACTION" = analyze ] || [ "$ACTION" = capture ]; then
            rm -f "$JOB/observations-v1.json"
            printf '{"status":"cancelled","radio_mutated":false}\n' > "$JOB/analysis-state.json"
        fi
    fi
    exit 130
}
check_cancel() { cancelled || cancel_operation; }
trap cancel_operation HUP INT TERM

# These maintenance operations need root and the shared mutex, not a working
# decoder or Wi-Fi module. No radio, connectivity, or recovery state is changed.
case "$ACTION" in
    recovery_status)
        check_cancel
        phase 'Checking root recovery journals'
        printf '{"ok":true,"action":"recovery_status","radio_mutated":false}\n' > "$OUT/result.json"
        exit 0;;
    delete_capture|cleanup_captures)
        check_cancel
        [ ! -f "$STATE/pending" ] || fail 'Restore Wi-Fi before deleting capture evidence'
        [ ! -e /data/local/tmp/nethunter-internal-wifi.lock ] || fail 'Another internal Wi-Fi session is active'
        [ ! -L "$KALI" ] && [ ! -L "$KALI/tmp" ] && [ ! -L "$KALI/tmp/hijacker-qca" ] || fail 'Capture storage must not be a symlink'
        if [ "$ACTION" = delete_capture ]; then
            TARGET=$(cat "$OUT/delete-target")
            capture_session_name "$TARGET" || fail 'Invalid capture session'
            [ "$TARGET" != "$SID" ] || fail 'Cannot delete the current session'
            [ ! -d "$KALI/tmp/hijacker-qca/$TARGET" ] || trusted_path "$KALI/tmp/hijacker-qca/$TARGET"
            remove_capture_files "$KALI/tmp/hijacker-qca/$TARGET" || fail 'Runtime capture deletion incomplete; evidence retained'
            remove_capture_files "${OUT%/*}/$TARGET" || fail 'App capture deletion incomplete; inspect retained evidence'
        else
            for DIRECTORY in "$KALI"/tmp/hijacker-qca/bw_*; do
                capture_session_name "${DIRECTORY##*/}" || continue
                [ ! -d "$DIRECTORY" ] || trusted_path "$DIRECTORY"
                remove_capture_files "$DIRECTORY" || fail 'Runtime cleanup incomplete; unsafe path retained'
            done
        fi
        check_cancel
        printf '{"ok":true,"action":"%s","radio_mutated":false,"raw_capture_deletion_complete":true}\n' "$ACTION" > "$OUT/result.json"
        phase 'Raw capture cleanup complete; summaries and recovery records retained'
        exit 0;;
esac

if [ "$KALI" = /data/local/bender-wifi/runtime-arm64-v1 ] && [ "$ACTION" != restore ]; then
    . "$SOURCE/runtime-root.sh"
    runtime_mounts || runtime_unavailable 'Private runtime mounts failed; use Setup to inspect the installation'
fi


run_worker() {
    KIND=$1
    # Cleanup survives cancellation and owner death; this worker cannot transmit.
    case "$KIND" in session_restore|wps_restore) ;; *) check_cancel;; esac
    # The chroot owns its executables. Android's PATH cannot locate its decoder.
    chroot "$KALI" /usr/bin/env PATH=/usr/sbin:/usr/bin:/sbin:/bin TZ=UTC /usr/bin/python3 /tmp/hijacker-qca/bin/bender-worker.py "$KIND" "/tmp/hijacker-qca/$SID" > "$JOB/worker.log" 2>&1 &
    CPID=$!
    echo "$CPID" > "$BASE/lock/child"
    N=0
    while kill -0 "$CPID" 2>/dev/null; do
        case "$KIND" in session_restore|wps_restore) ;; *) check_cancel;; esac
        LIMIT=150; [ "$KIND" != wps ] || LIMIT=330
        [ "$N" -lt "$LIMIT" ] || { stop_child; return 124; }
        sleep 1
        N=$((N + 1))
    done
    if wait "$CPID"; then WORKER_CODE=0; else WORKER_CODE=$?; fi
    # A worker may finish during a concurrent cancel/owner-death notification.
    case "$KIND" in session_restore|wps_restore) ;; *) check_cancel;; esac
    CPID=''
    rm -f "$BASE/lock/child"
    return "$WORKER_CODE"
}

# Read-only inventory still obeys cancellation and owner-death supervision.
if [ "$ACTION" = identity ]; then
    check_cancel
    [ -x "$KALI/usr/bin/python3" ] || runtime_unavailable 'Runtime missing: open Setup and install the bundled runtime'
    private_dir "$KALI/tmp/hijacker-qca"
    private_dir "$KALI/tmp/hijacker-qca/bin"
    [ ! -e "$JOB" ] && [ ! -L "$JOB" ] || fail 'Session directory already exists; use a new session'
    mkdir -m 700 "$JOB"
    for NAME in qca-backend.py netlink.py nl80211-constants.json identity_guard.py privacy_profile.py bender-worker.py; do
        [ -f "$SOURCE/$NAME" ] && [ ! -L "$SOURCE/$NAME" ] || fail 'Invalid runtime module'
        private_file "$KALI/tmp/hijacker-qca/bin/$NAME"
        cp "$SOURCE/$NAME" "$KALI/tmp/hijacker-qca/bin/$NAME"
        chmod 600 "$KALI/tmp/hijacker-qca/bin/$NAME"
    done
    printf '{}\n' > "$JOB/request.json"
    run_worker identity
    exit $?
fi

check_foreign_owner() {
    # Held under our own atomic lock, including for connectivity-profile mutations.
    [ ! -e /data/local/tmp/nethunter-internal-wifi.lock ] || fail 'Internal Wi-Fi session holds the radio; stop it first'
    [ ! -e /sys/module/qca_cld3_peach_v2/parameters/monitor_tx_ifname ] || fail 'Experimental driver is loaded; restore its owning session first'
    [ ! -e /data/local/tmp/benderlab/lock ] || fail 'Bender Lab holds the radio; stop it first'
    [ ! -e /data/local/tmp/benderlab/recovery/pending ] || fail 'Bender Lab has unresolved recovery; restore it first'
}

# Privacy settings have their own durable journal, but changing Wi-Fi or airplane
# mode still requires exclusive radio ownership. Read-only status remains usable.
case "$ACTION" in prepare|privacy_restore|privacy_status)
    [ "$ACTION" = privacy_status ] || [ ! -e "$BASE/bender-wps/pending.json" ] || fail 'Restore Wi-Fi before changing WPS privacy settings'
    [ "$ACTION" = privacy_status ] || check_foreign_owner
    [ "$ACTION" != prepare ] || [ ! -f "$STATE/pending" ] || fail 'Restore Wi-Fi before preparing privacy settings'
    [ -x "$KALI/usr/bin/python3" ] || runtime_unavailable 'Runtime missing: open Setup and install the bundled runtime'
    private_dir "$KALI/tmp/hijacker-qca"
    private_dir "$KALI/tmp/hijacker-qca/bin"
    [ ! -e "$JOB" ] && [ ! -L "$JOB" ] || fail 'Session directory already exists; use a new session'
    mkdir -m 700 "$JOB"
    for NAME in qca-backend.py netlink.py nl80211-constants.json identity_guard.py privacy_profile.py bender-worker.py; do
        [ -f "$SOURCE/$NAME" ] && [ ! -L "$SOURCE/$NAME" ] || fail 'Invalid runtime module'
        private_file "$KALI/tmp/hijacker-qca/bin/$NAME"
        cp "$SOURCE/$NAME" "$KALI/tmp/hijacker-qca/bin/$NAME"
        chmod 600 "$KALI/tmp/hijacker-qca/bin/$NAME"
    done
    printf '{}\n' > "$JOB/request.json"
    printf '%s\n' "$OWNER" > "$JOB/owner.pid"
    printf '%s\n' "/proc/1/root$OUT/cancel" > "$JOB/cancel.path"
    phase 'Checking privacy settings and separate recovery journal'
    run_worker "$ACTION"
    exit 0;;
esac

# Both supervisors take their own atomic lock before checking the other owner.
# Check even for Restore: it must not reset an active internal-driver session.
check_foreign_owner

if [ "$ACTION" = analyze ]; then
    # Offline import uses no interface transition and never clears recovery state.
    [ -x "$KALI/usr/bin/python3" ] || runtime_unavailable 'Runtime missing: open Setup and install the bundled runtime'
    [ -x "$KALI/usr/bin/tshark" ] || runtime_unavailable 'Packet decoder missing: open Setup and install the bundled runtime'
    [ -f "$OUT/input.pcap" ] && [ ! -L "$OUT/input.pcap" ] || fail 'Missing regular imported capture'
    SIZE=$(stat -c %s "$OUT/input.pcap")
    case "$SIZE" in ''|*[!0-9]*) fail 'Invalid imported capture size';; esac
    [ "$SIZE" -le 268435456 ] || fail 'Imported capture exceeds 256 MiB'
    private_dir "$KALI/tmp/hijacker-qca"
    private_dir "$KALI/tmp/hijacker-qca/bin"
    [ ! -e "$JOB" ] && [ ! -L "$JOB" ] || fail 'Session directory already exists; use a new session'
    mkdir -m 700 "$JOB"
    stage_engine
    cat "$OUT/input.pcap" > "$JOB/input.pcap"
    check_cancel
    phase 'Analyzing imported capture offline'
    if run_worker analyze; then
        cat "$JOB/worker.log" > "$JOB/analysis.log"
        printf '{"status":"complete","radio_mutated":false}\n' > "$JOB/analysis-state.json"
        printf '{"ok":true,"action":"analyze","radio_mutated":false}\n' > "$JOB/result.json"
        exit 0
    fi
    cat "$JOB/worker.log" > "$JOB/analysis.log"
    printf '{"status":"failed","radio_mutated":false,"reason":"decoder failed; inspect analysis.log"}\n' > "$JOB/analysis-state.json"
    printf '{"ok":false,"action":"analyze","radio_mutated":false,"error":"offline analysis failed"}\n' > "$JOB/result.json"
    exit 1
fi

if [ "$ACTION" = restore ]; then
    if [ -e "$BASE/bender-wps/pending.json" ]; then
        # The WPS journal is independent of monitor-mode firmware restoration.
        [ -x "$KALI/usr/bin/python3" ] || runtime_unavailable 'Runtime required to recover the WPS interface'
        if [ "$KALI" = /data/local/bender-wifi/runtime-arm64-v1 ]; then
            . "$SOURCE/runtime-root.sh"
            runtime_mounts || runtime_unavailable 'Runtime mounts failed during WPS recovery'
        fi
        private_dir "$KALI/tmp/hijacker-qca"
        private_dir "$KALI/tmp/hijacker-qca/bin"
        [ ! -e "$JOB" ] && [ ! -L "$JOB" ] || fail 'Session directory exists'
        mkdir -m 700 "$JOB"
        for NAME in bender-worker.py live_wps.py privacy_profile.py identity_guard.py; do
            private_file "$KALI/tmp/hijacker-qca/bin/$NAME"
            cp "$SOURCE/$NAME" "$KALI/tmp/hijacker-qca/bin/$NAME"
            chmod 600 "$KALI/tmp/hijacker-qca/bin/$NAME"
        done
        run_worker wps_restore || fail 'WPS recovery identity unresolved; journal retained'
        RESTORATION=saved_state_restored; RESTORATION_VERIFIED=true
    fi
    TOUCHED=1
    restore_radio || fail 'Recovery failed; saved state retained'
    [ "$(cat "$PARAM")" = 0 ] || fail 'Unknown monitor state; no Hijacker recovery record'
    if [ "$RESTORATION_VERIFIED" = false ]; then RESTORATION=managed_mode_observed_no_saved_state; fi
    printf '{"ok":true,"managed_mode":true,"restoration_verified":%s,"restoration_evidence":"%s"}\n' "$RESTORATION_VERIFIED" "$RESTORATION" > "$OUT/result.json"
    exit 0
fi

[ ! -f "$STATE/pending" ] || fail 'Unfinished session: use Restore Wi-Fi first'
[ ! -e "$BASE/bender-wps/pending.json" ] || fail 'Unfinished WPS session: use Restore Wi-Fi first'
[ "$(cat "$PARAM")" = 0 ] || fail 'Radio is not managed; restore the tool that changed it first'
# Readiness is read-only, including on an unrecognized driver. Model/build
# labels and hashes describe the tested profile, not an interface contract.
# Operations on other binaries retain the same runtime and recovery checks.
[ -x "$KALI/usr/bin/python3" ] || runtime_unavailable 'Runtime missing: open Setup and install the bundled runtime'
[ -x "$KALI/usr/bin/tshark" ] || runtime_unavailable 'Packet decoder missing: open Setup and install the bundled runtime'
private_dir "$KALI/tmp/hijacker-qca"
private_dir "$KALI/tmp/hijacker-qca/bin"
[ ! -e "$JOB" ] && [ ! -L "$JOB" ] || fail 'Session directory already exists; use a new session'
mkdir -m 700 "$JOB"
for NAME in qca-backend.py qca-scan.py netlink.py nl80211-constants.json identity_guard.py privacy_profile.py; do
    [ -f "$SOURCE/$NAME" ] && [ ! -L "$SOURCE/$NAME" ] || fail 'Invalid runtime module'
    private_file "$KALI/tmp/hijacker-qca/bin/$NAME"
    cp "$SOURCE/$NAME" "$KALI/tmp/hijacker-qca/bin/$NAME"
    chmod 600 "$KALI/tmp/hijacker-qca/bin/$NAME"
done
if [ "$ACTION" = wps ]; then
    [ -f "$SOURCE/live_wps.py" ] && [ ! -L "$SOURCE/live_wps.py" ] || fail 'WPS runtime module unavailable'
    private_file "$KALI/tmp/hijacker-qca/bin/live_wps.py"
    cp "$SOURCE/live_wps.py" "$KALI/tmp/hijacker-qca/bin/live_wps.py"
    chmod 600 "$KALI/tmp/hijacker-qca/bin/live_wps.py"
fi
stage_engine
cat "$OUT/request.json" > "$JOB/request.json"
[ "$ACTION" != wps ] || printf '%s\n' "$WPS_NATIVE" > "$JOB/wps-native.path"
printf '%s\n' "$OWNER" > "$JOB/owner.pid"
printf '%s\n' "/proc/1/root$OUT/cancel" > "$JOB/cancel.path"
getprop ro.product.model > "$JOB/model.txt"
getprop ro.build.display.id > "$JOB/build.txt"
uname -r > "$JOB/kernel.txt"
printf '%s\n' "${HASH%% *}" > "$JOB/driver-sha256.txt"
cat /sys/module/qca_cld3_peach_v2/srcversion > "$JOB/driver-srcversion.txt" 2>/dev/null || true
FWHASH=$(sha256sum /vendor/firmware_mnt/image/peach/amss20.bin 2>/dev/null || true)
printf '%s\n' "${FWHASH%% *}" > "$JOB/firmware-sha256.txt"
printf '%s\n' "$KALI" > "$JOB/chroot.txt"
cat "$FWPARAM" > "$JOB/firmware.txt"
# Preparation must precede the interface probe: Wi-Fi may initially be off.
# A new operation never adopts or overwrites another session's journal.
if [ "$ACTION" != probe ]; then
    cp "$SOURCE/privacy-session.py" "$KALI/tmp/hijacker-qca/bin/privacy-session.py"
    AUTO_PRIVACY=1
    phase 'Preparing automatic Wi-Fi privacy session'
    run_worker session_prepare || fail 'Privacy preparation failed; inspect the preparation report'
fi
chroot "$KALI" /usr/bin/env PATH=/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC \
    /usr/bin/python3 /tmp/hijacker-qca/bin/qca-backend.py probe "/tmp/hijacker-qca/$SID"
if [ "$KALI" = /data/local/bender-wifi/runtime-arm64-v1 ]; then PROVIDER=bundled; else PROVIDER=nethunter; fi
printf '{"runtime_ready":true,"provider":"%s","message":"Runtime check passed. Radio capabilities are recorded separately."}\n' "$PROVIDER" > "$OUT/runtime-state.json"
# A successful preflight is not a completed capture or transmit operation.
[ "$ACTION" = probe ] || rm -f "$JOB/result.json"
check_cancel
[ "$ACTION" != probe ] || exit 0

if [ "${HASH%% *}" != d6bd3fac600aaeba858e83292df167bc410457c07e422047c1a181c31d24d3df ]; then
    phase 'Untested driver: attempting compatible interfaces; recovery is not guaranteed'
fi
[ -z "$(cat "$FWPARAM")" ] || fail 'An existing firmware search override is active; restore its owning tool first'

if [ "$ACTION" = wps ]; then
    phase 'Starting managed WPS collection'
    RADIO_MUTATED=true
    run_worker wps
    exit 0
fi

if [ "$ACTION" = capture ]; then
    if command -v droidspaces >/dev/null 2>&1; then
        droidspaces show | grep -qi 'No containers running' || fail 'Stop Droidspaces containers first'
    fi
    # Validate the current regulatory list before interrupting normal Wi-Fi.
    chroot "$KALI" /usr/bin/env PATH=/usr/sbin:/usr/bin:/sbin:/bin TZ=UTC \
        /usr/bin/python3 /tmp/hijacker-qca/bin/qca-scan.py plan "/tmp/hijacker-qca/$SID"
    phase 'Preparing passive scan across enabled frequencies'
    private_dir "$BASE/firmware"
    private_dir "$BASE/firmware/peach"
    # Refresh from this installed build, not a downloaded firmware image.
    cp -a /vendor/firmware_mnt/image/peach/. "$BASE/firmware/peach/"
    cp -a /odm/etc/wifi/peach/. "$BASE/firmware/peach/"
    chcon -R u:object_r:firmware_file:s0 "$BASE/firmware"
    [ -f "$BASE/firmware/peach/amss20.bin" ] || fail 'Firmware staging failed'
    private_dir "$STATE"
    cat "$FWPARAM" > "$STATE/firmware"
    settings get global wifi_on > "$STATE/wifi"
    case "$(cat "$STATE/wifi")" in 0|1) ;; *) fail 'Unsupported Wi-Fi setting; radio was not changed';; esac
    touch "$STATE/pending"
    TOUCHED=1
    RADIO_MUTATED=true
    printf '%s\n' "$BASE/firmware" > "$FWPARAM"
    svc wifi disable
    sleep 3
    check_cancel
    ip link set wlan0 down 2>/dev/null || true
    printf '4\n' > "$PARAM"
    sleep 4
    check_cancel
    ip link set wlan0 up
    [ "$(cat /sys/class/net/wlan0/type)" = 803 ] || fail 'Radiotap interface was not created'
    chroot "$KALI" /usr/bin/env PATH=/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC TERM=xterm \
        /usr/bin/python3 /tmp/hijacker-qca/bin/qca-scan.py scan "/tmp/hijacker-qca/$SID" > "$JOB/capture.log" 2>&1 &
    CPID=$!
    echo "$CPID" > "$BASE/lock/child"
    echo 'READY:Passive RX · enabled frequencies · Cancel stops without analysis'
    N=0
    while [ "$N" -lt 145 ]; do
        kill -0 "$CPID" 2>/dev/null || break
        check_cancel
        if [ -f "$OUT/finish" ] && [ ! -f "$JOB/finish" ]; then
            touch "$JOB/finish"
            phase 'Finishing capture and saving packets'
        fi
        sleep 1
        N=$((N + 1))
        if [ $((N % 2)) = 0 ] && [ -s "$JOB/capture-01.csv" ]; then
            if [ ! -f "$OUT/finish" ]; then tail -n 1 "$JOB/capture.log" | grep '^PHASE:' || true; fi
            echo 'CSV_BEGIN'
            # Network names are untrusted text: encode them so they cannot spoof control lines.
            base64 "$JOB/capture-01.csv"
            echo 'CSV_END'
            if [ -s "$JOB/observations.json" ]; then
                echo 'OBS_BEGIN'
                base64 "$JOB/observations.json"
                echo 'OBS_END'
            fi
        fi
    done
    if kill -0 "$CPID" 2>/dev/null; then stop_child; fail 'Passive scanner exceeded its deadline'; fi
    wait "$CPID" || fail 'Passive scanner failed; inspect capture.log and decoder.log'
    CPID=''
    rm -f "$BASE/lock/child"
    restore_radio || fail 'Restore failed; recovery record retained'
    phase 'Restoring test-session connectivity'
    run_worker session_restore || fail 'Privacy restoration failed; recovery record retained'
    AUTO_PRIVACY=0
    chroot "$KALI" /usr/bin/env TZ=UTC /usr/bin/python3 /tmp/hijacker-qca/bin/qca-backend.py capture "/tmp/hijacker-qca/$SID"
    # Restoration succeeded above. Decoder failure must not imply radio recovery failure.
    phase 'Analyzing saved capture'
    if run_worker capture-analysis; then
        cat "$JOB/worker.log" > "$JOB/analysis.log"
        printf '{"status":"complete","radio_restored_before_analysis":true}\n' > "$JOB/analysis-state.json"
    else
        cat "$JOB/worker.log" > "$JOB/analysis.log"
        printf '{"status":"failed","radio_restored_before_analysis":true,"reason":"decoder failed; inspect analysis.log"}\n' > "$JOB/analysis-state.json"
        phase 'Capture restored; offline analysis failed'
    fi
else
    check_cancel
    phase 'Validating the selected client and managed interface'
    # One request, no retry: the Python process records intent before transmission.
    run_worker lab
fi
