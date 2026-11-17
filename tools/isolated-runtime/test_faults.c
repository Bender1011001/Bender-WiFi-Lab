/* SPDX-License-Identifier: GPL-3.0-or-later -- LD_PRELOAD faults, never used on a handset. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>

int unshare(int flags)
{
    const char *fault = getenv("BENDER_TEST_FAULT");
    if (fault && !strcmp(fault, "unshare-noop")) return 0;
    if (fault && !strcmp(fault, "unshare-error")) { errno = EPERM; return -1; }
    int (*original)(int) = dlsym(RTLD_NEXT, "unshare");
    return original(flags);
}

int mount(const char *src, const char *dst, const char *type,
          unsigned long flags, const void *data)
{
    const char *fault = getenv("BENDER_TEST_FAULT");
    static int bindings;
    if (fault && (flags & MS_PRIVATE)) {
        if (!strcmp(fault, "private-noop")) return 0;
        if (!strcmp(fault, "private-error")) { errno = EPERM; return -1; }
    }
    if (flags & MS_BIND) {
        ++bindings;
        if (fault && !strcmp(fault, "second-bind-error") && bindings == 2) {
            errno = EIO;
            return -1;
        }
        if (fault && !strcmp(fault, "bind-noop")) return 0;
    }
    int (*original)(const char *, const char *, const char *, unsigned long, const void *)
        = dlsym(RTLD_NEXT, "mount");
    return original(src, dst, type, flags, data);
}
