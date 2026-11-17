/* SPDX-License-Identifier: GPL-3.0-or-later
 * Private mounts for the retained lab harness; not a security sandbox.
 * No radio operations and no host unmounts. Requires root/CAP_SYS_ADMIN.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sched.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <unistd.h>

static const char *relative_targets[] = {
    "proc", "sys", "dev", "tmp/fresh-boot-air-20261116/shared"
};
enum { N_TARGETS = 4 };
static char target_paths[N_TARGETS][PATH_MAX];
static struct stat isolated_namespace;

static void fail(const char *message)
{
    fprintf(stderr, "isolated-runtime: %s\n", message);
    exit(125);
}

static void syscall_fail(const char *operation)
{
    fprintf(stderr, "isolated-runtime: %s: %s\n", operation, strerror(errno));
    exit(125);
}

static bool same_object(const struct stat *a, const struct stat *b)
{
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino;
}

static bool at_or_below(const char *path, const char *base)
{
    size_t n = strlen(base);
    return !strncmp(path, base, n) && (path[n] == '\0' || path[n] == '/');
}

static void canonical_directory(const char *path, char out[PATH_MAX])
{
    if (!realpath(path, out)) syscall_fail("resolve directory");
    if (strcmp(path, out) || !strcmp(out, "/"))
        fail("use an absolute canonical directory, without symlinks or trailing slash");
}

/* Open every component without following links. Ancestors such as Android's
 * shell-owned /data/local/tmp are allowed; the pinned final directory must be
 * root-owned and not publicly writable.
 * A caller must not race another root process modifying the runtime tree.
 */
static int open_directory(int start, const char *path)
{
    char copy[PATH_MAX];
    if (strlen(path) >= sizeof(copy)) fail("directory path too long");
    strcpy(copy, path);
    int fd = fcntl(start, F_DUPFD_CLOEXEC, 3);
    if (fd < 0) syscall_fail("duplicate directory descriptor");
    char *save = NULL;
    char *part = strtok_r(copy, "/", &save);
    if (!part) fail("empty directory path");
    while (part) {
        if (!strcmp(part, ".") || !strcmp(part, "..")) fail("invalid path component");
        int next = openat(fd, part, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (next < 0) syscall_fail("open directory component");
        close(fd);
        fd = next;
        part = strtok_r(NULL, "/", &save);
        struct stat st;
        if (fstat(fd, &st)) syscall_fail("stat directory");
        if (!part && (st.st_uid != 0 || (st.st_mode & 0022)))
            fail("directory must be root-owned and not group/world writable");
    }
    return fd;
}

/* mountinfo escapes space, tab, newline and backslash as octal. */
static void unescape_mount_path(char *s)
{
    char *out = s;
    for (char *p = s; *p;) {
        if (*p == '\\') {
            if (strlen(p) < 4 || p[1] < '0' || p[1] > '7' ||
                    p[2] < '0' || p[2] > '7' || p[3] < '0' || p[3] > '7')
                fail("malformed mountinfo escape");
            unsigned value = (unsigned)((p[1]-'0')*64 + (p[2]-'0')*8 + p[3]-'0');
            if (!value || value > 255) fail("invalid mountinfo path byte");
            *out++ = (char)value;
            p += 4;
        } else {
            *out++ = *p++;
        }
    }
    *out = '\0';
}

static void check_mounts(bool require_private, int mounted)
{
    if (require_private) {
        struct stat current;
        if (stat("/proc/self/ns/mnt", &current)) syscall_fail("read mount namespace");
        if (!same_object(&current, &isolated_namespace)) fail("mount namespace changed");
    }
    FILE *f = fopen("/proc/self/mountinfo", "re");
    if (!f) syscall_fail("read mountinfo");
    char *line = NULL;
    size_t capacity = 0;
    int count[N_TARGETS] = {0};
    size_t records = 0;
    while (getline(&line, &capacity, f) != -1) {
        char *save = NULL, *word = strtok_r(line, " ", &save), *point = NULL;
        for (int column = 0; column < 6; ++column) {
            if (!word) fail("incomplete mountinfo record");
            if (column == 4) point = word;
            word = strtok_r(NULL, " ", &save);
        }
        while (word && strcmp(word, "-")) {
            if (require_private && (!strncmp(word, "shared:", 7) ||
                    !strncmp(word, "master:", 7) || !strncmp(word, "propagate_from:", 15)))
                fail("mount propagation is not private");
            word = strtok_r(NULL, " ", &save);
        }
        if (!word) fail("missing mountinfo separator");
        unescape_mount_path(point);
        for (int i = 0; i < N_TARGETS; ++i) {
            if (at_or_below(point, target_paths[i])) {
                if (strcmp(point, target_paths[i])) fail("nested mount under runtime target");
                ++count[i];
            }
        }
        ++records;
    }
    if (ferror(f)) syscall_fail("read mountinfo record");
    free(line);
    fclose(f);
    if (!records) fail("empty mountinfo");
    for (int i = 0; i < N_TARGETS; ++i) {
        if (count[i] != (i < mounted ? 1 : 0))
            fail("runtime target already mounted, missing, or stacked; stop the other runtime first");
    }
}

int main(int argc, char **argv)
{
    if (argc < 5 || strcmp(argv[3], "--") || argv[4][0] != '/') {
        fprintf(stderr, "usage: isolated-runtime ROOT SHARED -- /absolute/command [args...]\n");
        return 125;
    }
    if (geteuid() != 0) fail("root is required");
    char root[PATH_MAX], shared[PATH_MAX];
    canonical_directory(argv[1], root);
    canonical_directory(argv[2], shared);
    if (at_or_below(root, shared) || at_or_below(shared, root))
        fail("runtime and shared source must be separate directory trees");
    const char *sources[] = { "/proc", "/sys", "/dev", shared };
    for (int i = 0; i < N_TARGETS; ++i) {
        if (snprintf(target_paths[i], PATH_MAX, "%s/%s", root, relative_targets[i]) >= PATH_MAX)
            fail("runtime target path too long");
    }
    check_mounts(false, 0); /* Refuse existing mounts; never stack or remove them. */
    struct stat before;
    if (stat("/proc/self/ns/mnt", &before)) syscall_fail("read original namespace");
    if (unshare(CLONE_NEWNS)) syscall_fail("unshare mount namespace");
    if (stat("/proc/self/ns/mnt", &isolated_namespace)) syscall_fail("read isolated namespace");
    if (same_object(&before, &isolated_namespace)) fail("unshare did not change namespace");
    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL)) syscall_fail("make mount tree private");
    check_mounts(true, 0); /* No new mount occurs before both isolation checks pass. */
    /* Open descriptors after unshare: descriptors opened beforehand still refer
     * to mounts in the parent namespace, unsuitable as bind destinations here.
     */
    int slash = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (slash < 0) syscall_fail("open root directory");
    int root_fd = open_directory(slash, root);
    int source_fds[N_TARGETS], target_fds[N_TARGETS];
    for (int i = 0; i < N_TARGETS; ++i) {
        source_fds[i] = open_directory(slash, sources[i]);
        target_fds[i] = open_directory(root_fd, relative_targets[i]);
    }
    close(slash);
    for (int i = 0; i < N_TARGETS; ++i) {
        char source[64], target[64];
        snprintf(source, sizeof(source), "/proc/self/fd/%d", source_fds[i]);
        snprintf(target, sizeof(target), "/proc/self/fd/%d", target_fds[i]);
        if (mount(source, target, NULL, MS_BIND, NULL)) syscall_fail("bind runtime directory");
        struct stat expected, actual;
        if (fstat(source_fds[i], &expected) || fstatat(root_fd, relative_targets[i], &actual, 0))
            syscall_fail("verify runtime binding");
        if (!same_object(&expected, &actual)) fail("runtime binding identity mismatch");
        check_mounts(true, i + 1);
    }
    fprintf(stderr, "isolated-runtime: private namespace verified; four bindings verified\n");
    if (fchdir(root_fd) || chroot(".") || chdir("/")) syscall_fail("enter runtime");
    for (int i = 0; i < N_TARGETS; ++i) {
        close(source_fds[i]);
        close(target_fds[i]);
    }
    close(root_fd);
    execv(argv[4], argv + 4);
    syscall_fail("execute runtime command");
    return 125;
}
