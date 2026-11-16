/* Read-only probe: open the Linux HCI socket and list registered controllers.
 * No HCI command, ACL packet, connection, or radio transmission is sent.
 */
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

#ifndef AF_BLUETOOTH
#define AF_BLUETOOTH 31
#endif
#define BTPROTO_HCI 1
#define HCIGETDEVLIST _IOR('H', 210, int)

struct hci_dev_req {
    uint16_t dev_id;
    uint32_t dev_opt;
};

struct hci_dev_list_req {
    uint16_t dev_num;
    struct hci_dev_req dev_req[8];
};

int main(void) {
    int fd = socket(AF_BLUETOOTH, SOCK_RAW | SOCK_CLOEXEC, BTPROTO_HCI);
    if (fd < 0) {
        printf("hci_raw_socket=error errno=%d (%s)\n", errno, strerror(errno));
        return 0;
    }
    puts("hci_raw_socket=opened");

    struct hci_dev_list_req req = { .dev_num = 8 };
    if (ioctl(fd, HCIGETDEVLIST, &req) < 0) {
        printf("hci_controller_list=error errno=%d (%s)\n", errno, strerror(errno));
    } else {
        printf("hci_controller_count=%u\n", req.dev_num);
        for (uint16_t i = 0; i < req.dev_num && i < 8; ++i)
            printf("hci_controller_id=%u\n", req.dev_req[i].dev_id);
    }
    close(fd);
    return 0;
}
