// Opens a TUN interface and replaces itself with a program that receives the descriptor.
//
// Usage: libxraytun.so <interface> <mtu> <program> [args...]
//
// The Android Xray build cannot create a TUN interface; it only adopts an open descriptor named
// by xray.tun.fd, and unlike the Linux build it leaves the interface MTU alone. A root shell has
// no way to issue TUNSETIFF, so root mode starts the core through this executable, which leaves
// the interface owned by the core process and gone when it exits.
#include <errno.h>
#include <fcntl.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <interface> <mtu> <program> [args...]\n", argv[0]);
        return 2;
    }
    if (strlen(argv[1]) >= IFNAMSIZ) {
        fprintf(stderr, "interface name is too long: %s\n", argv[1]);
        return 2;
    }
    char *end;
    long mtu = strtol(argv[2], &end, 10);
    if (*argv[2] == '\0' || *end != '\0' || mtu < 576 || mtu > 65535) {
        fprintf(stderr, "invalid MTU: %s\n", argv[2]);
        return 2;
    }

    // Deliberately not O_CLOEXEC: the descriptor has to survive the exec below.
    int fd = open("/dev/net/tun", O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "open /dev/net/tun: %s\n", strerror(errno));
        return 1;
    }

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, argv[1], IFNAMSIZ - 1);
    ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
    if (ioctl(fd, TUNSETIFF, &ifr) < 0) {
        fprintf(stderr, "TUNSETIFF %s: %s\n", argv[1], strerror(errno));
        return 1;
    }

    // SIOCSIFMTU needs a socket rather than the TUN descriptor.
    int sock = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    ifr.ifr_mtu = (int) mtu;
    if (sock < 0 || ioctl(sock, SIOCSIFMTU, &ifr) < 0) {
        fprintf(stderr, "SIOCSIFMTU %s: %s\n", argv[1], strerror(errno));
        return 1;
    }
    close(sock);

    char value[16];
    snprintf(value, sizeof(value), "%d", fd);
    if (setenv("xray.tun.fd", value, 1) != 0 || setenv("XRAY_TUN_FD", value, 1) != 0) {
        fprintf(stderr, "setenv: %s\n", strerror(errno));
        return 1;
    }

    execv(argv[3], argv + 3);
    fprintf(stderr, "execv %s: %s\n", argv[3], strerror(errno));
    return 1;
}
