/* SPDX-License-Identifier: GPL-3.0-or-later -- static executable for an empty test chroot. */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    struct stat ns, shared;
    if (stat("/proc/self/ns/mnt", &ns) ||
            stat("/tmp/fresh-boot-air-20261116/shared", &shared)) return 90;
    printf("namespace=%llu shared=%llu:%llu\n", (unsigned long long)ns.st_ino,
           (unsigned long long)shared.st_dev, (unsigned long long)shared.st_ino);
    fflush(stdout);
    if (argc > 1 && !strcmp(argv[1], "wait")) {
        for (;;) pause();
    }
    return argc > 1 ? atoi(argv[1]) : 0;
}
