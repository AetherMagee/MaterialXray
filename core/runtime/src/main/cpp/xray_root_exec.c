// Starts the root-mode core with only the privileges it needs.
//
// Usage: libxrayroot.so <uid> <gid> <config> <interface> <mtu> <program> [args...]
// <interface> and <mtu> are "-" when the core gets no TUN interface.
//
// A root shell runs this from the core's working directory. Everything that needs root happens
// here: creating the TUN interface (the Android Xray build only adopts a descriptor named by
// xray.tun.fd, and unlike the Linux build it leaves the interface MTU alone), opening the config,
// which stays private to the app and reaches the core as stdin, and opening the directory that
// holds the core, which may be in the app's private storage. Then it switches to <uid>:<gid> plus
// the inet group, keeps only the network capabilities the core uses, and execs <program>.
//
// The new uid cannot traverse the app's data directory, so the core reaches the files it needs
// through /proc/self/cwd, and the program through the directory descriptor opened here.
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <limits.h>
#include <linux/capability.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

// Android's inet group; without it a paranoid-network kernel refuses the core's sockets.
#define AID_INET 3003

// CAP_NET_ADMIN for SO_MARK and TPROXY's IP_TRANSPARENT, CAP_NET_RAW for SO_BINDTODEVICE on
// kernels older than 5.7, and CAP_NET_BIND_SERVICE because a TPROXY UDP reply is sent from the
// original destination, which is a privileged port for DNS or QUIC.
static const int kept_capabilities[] = {CAP_NET_ADMIN, CAP_NET_RAW, CAP_NET_BIND_SERVICE};
#define KEPT_CAPABILITY_COUNT (sizeof(kept_capabilities) / sizeof(kept_capabilities[0]))

static int is_kept(int capability) {
    for (size_t i = 0; i < KEPT_CAPABILITY_COUNT; i++) {
        if (kept_capabilities[i] == capability) return 1;
    }
    return 0;
}

static int parse_id(const char *value, long *out) {
    char *end;
    errno = 0;
    long id = strtol(value, &end, 10);
    if (*value == '\0' || *end != '\0' || errno != 0 || id <= 0 || id > 0x7fffffffL) return -1;
    *out = id;
    return 0;
}

static int open_tun(const char *name, const char *mtu_value) {
    if (strlen(name) >= IFNAMSIZ) {
        fprintf(stderr, "interface name is too long: %s\n", name);
        return -1;
    }
    char *end;
    long mtu = strtol(mtu_value, &end, 10);
    if (*mtu_value == '\0' || *end != '\0' || mtu < 576 || mtu > 65535) {
        fprintf(stderr, "invalid MTU: %s\n", mtu_value);
        return -1;
    }

    // Deliberately not O_CLOEXEC: the descriptor has to survive the exec.
    int fd = open("/dev/net/tun", O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "open /dev/net/tun: %s\n", strerror(errno));
        return -1;
    }

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, name, IFNAMSIZ - 1);
    ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
    if (ioctl(fd, TUNSETIFF, &ifr) < 0) {
        fprintf(stderr, "TUNSETIFF %s: %s\n", name, strerror(errno));
        return -1;
    }

    // SIOCSIFMTU needs a socket rather than the TUN descriptor.
    int sock = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    ifr.ifr_mtu = (int) mtu;
    if (sock < 0 || ioctl(sock, SIOCSIFMTU, &ifr) < 0) {
        fprintf(stderr, "SIOCSIFMTU %s: %s\n", name, strerror(errno));
        return -1;
    }
    close(sock);
    return fd;
}

// Whatever the root shell left open would otherwise reach the core.
static int close_inherited_descriptors(int keep_a, int keep_b) {
    DIR *dir = opendir("/proc/self/fd");
    if (dir == NULL) return -1;
    int listing = dirfd(dir);
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        char *end;
        long fd = strtol(entry->d_name, &end, 10);
        if (*end != '\0' || entry->d_name[0] == '\0') continue;
        if (fd <= STDERR_FILENO || fd == listing || fd == keep_a || fd == keep_b) continue;
        close((int) fd);
    }
    closedir(dir);
    return 0;
}

// Leaves exactly the kept capabilities effective, permitted and inheritable. The inheritable set
// matters even for a core that stays root: exec grants root whatever is inheritable, and some root
// managers hand their shells every capability there.
static int set_kept_capabilities(void) {
    struct __user_cap_header_struct header = {.version = _LINUX_CAPABILITY_VERSION_3, .pid = 0};
    struct __user_cap_data_struct data[_LINUX_CAPABILITY_U32S_3];
    memset(data, 0, sizeof(data));
    for (size_t i = 0; i < KEPT_CAPABILITY_COUNT; i++) {
        int capability = kept_capabilities[i];
        __u32 bit = 1U << (capability % 32);
        data[capability / 32].effective |= bit;
        data[capability / 32].permitted |= bit;
        data[capability / 32].inheritable |= bit;
    }
    if (syscall(SYS_capset, &header, data) < 0) {
        fprintf(stderr, "capset: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

static int drop_privileges(uid_t uid, gid_t gid) {
    // The bounding set caps what any later exec can regain, root included.
    for (int capability = 0; prctl(PR_CAPBSET_READ, capability, 0, 0, 0) >= 0; capability++) {
        if (!is_kept(capability) && prctl(PR_CAPBSET_DROP, capability, 0, 0, 0) < 0) {
            fprintf(stderr, "PR_CAPBSET_DROP %d: %s\n", capability, strerror(errno));
            return -1;
        }
    }

    gid_t groups[] = {AID_INET};
    if (setgroups(1, groups) < 0 || setresgid(gid, gid, gid) < 0) {
        fprintf(stderr, "setgid %d: %s\n", (int) gid, strerror(errno));
        return -1;
    }

    // Ambient capabilities arrived in Linux 4.3. Without them nothing carries a capability across
    // exec into a non-root uid, so an older kernel keeps uid 0, held to the bounding set above.
    if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_IS_SET, CAP_NET_ADMIN, 0, 0) < 0) {
        fprintf(stderr, "this kernel has no ambient capabilities, so the core keeps uid 0\n");
        return set_kept_capabilities();
    }

    if (prctl(PR_SET_KEEPCAPS, 1, 0, 0, 0) < 0 || setresuid(uid, uid, uid) < 0) {
        fprintf(stderr, "setuid %d: %s\n", (int) uid, strerror(errno));
        return -1;
    }

    if (set_kept_capabilities() < 0) return -1;
    for (size_t i = 0; i < KEPT_CAPABILITY_COUNT; i++) {
        if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_RAISE, kept_capabilities[i], 0, 0) < 0) {
            fprintf(stderr, "PR_CAP_AMBIENT_RAISE %d: %s\n", kept_capabilities[i], strerror(errno));
            return -1;
        }
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 7) {
        fprintf(stderr, "usage: %s <uid> <gid> <config> <interface> <mtu> <program> [args...]\n", argv[0]);
        return 2;
    }
    long uid, gid;
    if (parse_id(argv[1], &uid) < 0 || parse_id(argv[2], &gid) < 0) {
        fprintf(stderr, "invalid uid or gid: %s %s\n", argv[1], argv[2]);
        return 2;
    }
    const char *program = argv[6];
    const char *program_name = strrchr(program, '/');
    if (program[0] != '/' || program_name == program || program_name[1] == '\0') {
        fprintf(stderr, "program must be an absolute file path: %s\n", program);
        return 2;
    }

    int tun_fd = -1;
    if (strcmp(argv[4], "-") != 0) {
        tun_fd = open_tun(argv[4], argv[5]);
        if (tun_fd < 0) return 1;
        char value[16];
        snprintf(value, sizeof(value), "%d", tun_fd);
        if (setenv("xray.tun.fd", value, 1) != 0 || setenv("XRAY_TUN_FD", value, 1) != 0) {
            fprintf(stderr, "setenv: %s\n", strerror(errno));
            return 1;
        }
    }

    int config_fd = open(argv[3], O_RDONLY | O_CLOEXEC);
    if (config_fd < 0 || dup2(config_fd, STDIN_FILENO) < 0) {
        fprintf(stderr, "open %s: %s\n", argv[3], strerror(errno));
        return 1;
    }
    close(config_fd);

    char *program_dir = strndup(program, (size_t) (program_name - program));
    int program_dir_fd = program_dir == NULL ? -1 : open(program_dir, O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (program_dir_fd < 0) {
        fprintf(stderr, "open %s: %s\n", program_dir == NULL ? program : program_dir, strerror(errno));
        return 1;
    }
    free(program_dir);
    // The last path component stays the program's own name, which is what pidof finds it by.
    char program_path[64 + PATH_MAX];
    snprintf(program_path, sizeof(program_path), "/proc/self/fd/%d%s", program_dir_fd, program_name);

    if (close_inherited_descriptors(tun_fd, program_dir_fd) < 0) {
        fprintf(stderr, "close inherited descriptors: %s\n", strerror(errno));
        return 1;
    }
    // Sockets and files the core creates stay reachable by the app through the shared group only.
    umask(007);
    if (drop_privileges((uid_t) uid, (gid_t) gid) < 0) return 1;
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) {
        fprintf(stderr, "PR_SET_NO_NEW_PRIVS: %s\n", strerror(errno));
        return 1;
    }

    // argv[6] stays the program's real path, so its command line still names it.
    execv(program_path, argv + 6);
    fprintf(stderr, "execv %s: %s\n", program, strerror(errno));
    return 1;
}
