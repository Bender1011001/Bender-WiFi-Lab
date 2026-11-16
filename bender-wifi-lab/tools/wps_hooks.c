/* Private WPS handoff. Built only into Bender's isolated supplicant. */
#include "utils/includes.h"
#include "utils/common.h"
#include "utils/wpabuf.h"
#include "wps/wps.h"
#include "wps/wps_i.h"
#include <sys/prctl.h>
#include <sys/resource.h>
#include <signal.h>

static int mode(const char *expected)
{
    const char *actual = getenv("BENDER_WPS_MODE");
    return actual && strcmp(actual, expected) == 0;
}

void bender_wps_begin(void)
{
    static unsigned int exchanges;
    if (++exchanges > 1) _exit(45);
}

void bender_wps_bad_pin(int half)
{
    if (!mode("trial")) return;
    printf("BENDER_PIN_REJECTED %d\n", half);
    fflush(stdout);
    /* Only authenticated M5/M7 hash mismatch is a confirmed wrong PIN. */
    _exit(46);
}

static void field(const char *name, const void *data, size_t length, int last)
{
    const unsigned char *p = data;
    printf("\"%s\":\"", name);
    for (size_t i = 0; i < length; ++i) printf("%02x", p[i]);
    printf(last ? "\"}\n" : "\",");
}

void bender_wps_m3(struct wps_data *wps)
{
    if (!mode("capture")) return;
    if (!wps->dh_pubkey_e || !wps->dh_pubkey_r ||
        wpabuf_len(wps->dh_pubkey_e) != 192 || wpabuf_len(wps->dh_pubkey_r) != 192)
        _exit(44);
    printf("BENDER_EXCHANGE {");
    field("pke", wpabuf_head(wps->dh_pubkey_e), 192, 0);
    field("pkr", wpabuf_head(wps->dh_pubkey_r), 192, 0);
    field("authkey", wps->authkey, sizeof(wps->authkey), 0);
    field("e_hash1", wps->peer_hash1, sizeof(wps->peer_hash1), 0);
    field("e_hash2", wps->peer_hash2, sizeof(wps->peer_hash2), 0);
    field("e_nonce", wps->nonce_e, sizeof(wps->nonce_e), 1);
    fflush(stdout);
    /* Never send M4 with the collection PIN; no automatic second exchange. */
    _exit(42);
}

void bender_wps_credential(const struct wps_credential *cred)
{
    if (!mode("credential") && !mode("trial")) return;
    if (cred->ssid_len > sizeof(cred->ssid) || cred->key_len > sizeof(cred->key))
        _exit(44);
    printf("BENDER_CREDENTIAL {\"auth_type\":%u,\"encr_type\":%u,", cred->auth_type, cred->encr_type);
    field("ssid_hex", cred->ssid, cred->ssid_len, 0);
    field("key_hex", cred->key, cred->key_len, 0);
    field("mac_hex", cred->mac_addr, sizeof(cred->mac_addr), 1);
    fflush(stdout);
    /* AP settings were authenticated/decrypted; do not reconfigure or join it. */
    _exit(43);
}

int bender_wpa_main(int argc, char **argv);
int main(int argc, char **argv)
{
    struct rlimit core = {0, 0}, cpu = {100, 100};
    pid_t parent = getppid();
    if (argc == 2 && strcmp(argv[1], "--bender-version") == 0) {
        puts("Bender managed WPS protocol 1");
        return 0;
    }
    if (!mode("capture") && !mode("credential") && !mode("trial")) return 64;
    if (parent <= 1 || prctl(PR_SET_PDEATHSIG, SIGKILL) || getppid() != parent ||
        prctl(PR_SET_DUMPABLE, 0) || setrlimit(RLIMIT_CORE, &core) || setrlimit(RLIMIT_CPU, &cpu)) return 65;
    return bender_wpa_main(argc, argv);
}
