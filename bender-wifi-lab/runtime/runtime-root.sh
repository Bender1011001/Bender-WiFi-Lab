#!/system/bin/sh
# Shared private runtime mounts. The caller owns the radio/setup mutex.
BENDER_RUNTIME=/data/local/bender-wifi/runtime-arm64-v1
BENDER_MOUNTED=''
runtime_mounted() { awk -v p="$1" '$5==p {found=1} END {exit !found}' /proc/self/mountinfo; }
runtime_binding_matches() {
    # A pre-existing mount may be recovery from process death. Adopt only the
    # exact host directory, never an unknown filesystem mounted at this path.
    source_identity=$(stat -c '%d:%i' "/$1") || return 1
    target_identity=$(stat -c '%d:%i' "$BENDER_RUNTIME/$1") || return 1
    [ "$source_identity" = "$target_identity" ]
}
runtime_mounts() {
    [ -d "$BENDER_RUNTIME" ] && [ ! -L "$BENDER_RUNTIME" ] || return 1
    [ "$(stat -c %u "$BENDER_RUNTIME")" = 0 ] || return 1
    [ -f "$BENDER_RUNTIME/.bender-ready" ] && [ ! -L "$BENDER_RUNTIME/.bender-ready" ] || return 1
    for part in proc sys dev; do
        target=$BENDER_RUNTIME/$part
        [ -d "$target" ] && [ ! -L "$target" ] || return 1
        if runtime_mounted "$target"; then
            runtime_binding_matches "$part" || return 1
        else
            mount -o bind "/$part" "$target" || return 1
        fi
        BENDER_MOUNTED="$part $BENDER_MOUNTED"
    done
}
runtime_unmounts() {
    remaining=''
    for part in $BENDER_MOUNTED; do
        target=$BENDER_RUNTIME/$part
        if runtime_mounted "$target"; then
            if ! runtime_binding_matches "$part" || ! umount "$target" || runtime_mounted "$target"; then
                remaining="$remaining $part"
            fi
        fi
    done
    BENDER_MOUNTED=$remaining
    [ -z "$BENDER_MOUNTED" ]
}
