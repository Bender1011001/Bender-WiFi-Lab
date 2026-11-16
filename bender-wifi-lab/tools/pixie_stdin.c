/* SPDX-License-Identifier: GPL-3.0-or-later
 * Fixed stdin protocol: six hex fields, no user-supplied command-line options.
 * Secrets are never placed in /proc argv or a temporary file.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/resource.h>

int pixie_main(int argc, char **argv);

int main(void) {
    pid_t parent = getppid();
    if (parent == 1 || prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) return 70;
    struct rlimit cpu = {125, 126};
    if (setrlimit(RLIMIT_CPU, &cpu) != 0) return 70;
    const size_t lengths[] = {384, 384, 64, 64, 64, 32};
    char fields[6][386];
    for (int i = 0; i < 6; i++) {
        if (!fgets(fields[i], sizeof(fields[i]), stdin)) return 64;
        size_t size = strlen(fields[i]);
        if (size != lengths[i] + 1 || fields[i][size - 1] != '\n') return 64;
        fields[i][size - 1] = 0;
        if (strspn(fields[i], "0123456789abcdefABCDEF") != lengths[i]) return 64;
    }
    if (getchar() != EOF) return 64;
    char *args[] = {"pixiewps", "-e", fields[0], "-r", fields[1], "-s", fields[2],
                    "-z", fields[3], "-a", fields[4], "-n", fields[5], "-j", "1", "-v", "3", NULL};
    return pixie_main(17, args);
}
