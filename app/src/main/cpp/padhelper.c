// Root input helper for Back + D-pad shortcuts (see docs/HELPER.md).
//
// The Thor's D-pad reports only ABS_HAT0X/Y, which never reaches an accessibility service, and the
// unprivileged workaround (a focusable overlay) costs the game its window focus. This helper runs as
// root through AYN's pservice instead and sits between AYN's pad and Android:
//
//   * It takes the pad away from Android: it opens the evdev node, then unlinks the /dev/input entry.
//     Our fd stays valid; Android's EventHub watches /dev/input and drops the pad, freeing its
//     controller number and descriptor.
//   * It re-offers the pad as a uinput clone with the same name, bus, vendor, product, version, key
//     and axis bits and axis ranges. Android derives the same descriptor and hands out controller
//     number 1 again, so games and emulators keep their existing setup.
//   * It forwards every event unchanged, except D-pad presses that start while Back is held or the
//     app's own Recents overlay is open. Those go to the app over a socket ("HAT X -1") instead.
//     "PASS <mask>" exempts directions the focused app's exception keeps for itself.
//     Top Recents directions are buffered by the app until Quickstep has keyboard focus, then
//     replayed through NAV commands on this socket. Card-row Up uses a bounded dismissal gesture.
//
// AYN's controller-style switch (Xbox / Standard / Ban on Use) destroys the pad and creates a new one
// with another name and product id. read() then fails, the clone is torn down, and the helper takes
// over whichever pad appears next ("Ban on Use" exposes none, so it simply waits).
//
// On exit the clone is destroyed first and the node recreated, so Android gets the pad back on
// controller number 1. A helper that dies without cleaning up leaves the node missing; the next start
// recreates any such node before doing anything else.
//
// Written from scratch against the kernel UAPI (linux/input.h, linux/uinput.h) and libc only.
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/un.h>
#include <sys/xattr.h>
#include <time.h>
#include <unistd.h>

#define EMERGENCY_HOLD_MS 5000
#define CLONE_SETTLE_US 250000 // EventHub must drop the pad before the clone appears

#define BITS_PER_LONG (sizeof(long) * 8)
#define NLONGS(x) (((x) + BITS_PER_LONG - 1) / BITS_PER_LONG)
static int test_bit(int bit, const unsigned long *arr) { return (arr[bit / BITS_PER_LONG] >> (bit % BITS_PER_LONG)) & 1; }

// What it takes to put a /dev/input node back exactly as ueventd made it.
struct node_attrs {
    struct stat st;
    char label[128];
    ssize_t label_len;
};

static struct {
    int fd;
    char path[64];
    struct node_attrs attrs;
    int unlinked;
} pad = { .fd = -1 };

static int ufd = -1, sock = -1;
static volatile sig_atomic_t stop;

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static void on_signal(int sig) { (void)sig; stop = 1; }

static void read_attrs(const char *path, struct node_attrs *a) {
    stat(path, &a->st);
    a->label_len = getxattr(path, "security.selinux", a->label, sizeof a->label);
}

static int make_node(const char *path, dev_t rdev, const struct node_attrs *a) {
    if (mknod(path, S_IFCHR | (a->st.st_mode & 07777), rdev) != 0) return -1;
    if (chown(path, a->st.st_uid, a->st.st_gid) != 0) perror("chown");
    chmod(path, a->st.st_mode & 07777);
    if (a->label_len > 0) setxattr(path, "security.selinux", a->label, a->label_len, 0);
    return 0;
}

// Recreates nodes a crashed earlier run left unlinked: every evdev device the kernel knows must have
// its /dev/input entry. Mode, owner and label are copied from a sibling node, which ueventd gave the
// same attributes.
static void restore_missing_nodes(void) {
    struct node_attrs sibling = {0};
    int have_sibling = 0;
    DIR *dir = opendir("/dev/input");
    if (dir) {
        struct dirent *d;
        while (!have_sibling && (d = readdir(dir))) {
            if (strncmp(d->d_name, "event", 5) != 0) continue;
            char path[64];
            snprintf(path, sizeof path, "/dev/input/%s", d->d_name);
            read_attrs(path, &sibling);
            have_sibling = S_ISCHR(sibling.st.st_mode);
        }
        closedir(dir);
    }
    if (!have_sibling) return;

    dir = opendir("/sys/class/input");
    if (!dir) return;
    struct dirent *d;
    while ((d = readdir(dir))) {
        if (strncmp(d->d_name, "event", 5) != 0) continue;
        char path[64], devfile[96], buf[32];
        snprintf(path, sizeof path, "/dev/input/%s", d->d_name);
        if (access(path, F_OK) == 0) continue;
        snprintf(devfile, sizeof devfile, "/sys/class/input/%s/dev", d->d_name);
        FILE *f = fopen(devfile, "re");
        if (!f) continue;
        unsigned maj, min;
        if (fgets(buf, sizeof buf, f) && sscanf(buf, "%u:%u", &maj, &min) == 2
                && make_node(path, makedev(maj, min), &sibling) == 0)
            fprintf(stderr, "recreated %s\n", path);
        fclose(f);
    }
    closedir(dir);
}

static void destroy_clone(void) {
    if (ufd >= 0) { ioctl(ufd, UI_DEV_DESTROY); close(ufd); ufd = -1; }
}

// Puts the pad's node back - but only while that device still exists. After a style switch ueventd
// has already created a node for the new pad, and the old one must stay gone.
static void release_pad(int device_alive) {
    if (pad.unlinked && device_alive && access(pad.path, F_OK) != 0)
        make_node(pad.path, pad.attrs.st.st_rdev, &pad.attrs);
    pad.unlinked = 0;
    if (pad.fd >= 0) { close(pad.fd); pad.fd = -1; }
}

// A usable pad has the hat, Back and gamepad buttons; "Ban on Use" exposes a node with none of them.
// AYN's pad is itself a uinput device, so "virtual" cannot tell it apart from our clone - but we only
// scan while the clone does not exist, so the match is always AYN's.
static int open_pad(char *name, size_t name_len) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return -1;
    struct dirent *d;
    while (pad.fd < 0 && (d = readdir(dir))) {
        if (strncmp(d->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof path, "/dev/input/%s", d->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        unsigned long abs[NLONGS(ABS_CNT)] = {0}, key[NLONGS(KEY_CNT)] = {0};
        ioctl(fd, EVIOCGBIT(EV_ABS, sizeof abs), abs);
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof key), key);
        if (test_bit(ABS_HAT0X, abs) && test_bit(KEY_BACK, key) && test_bit(BTN_GAMEPAD, key)) {
            memset(name, 0, name_len);
            ioctl(fd, EVIOCGNAME(name_len - 1), name);
            snprintf(pad.path, sizeof pad.path, "%s", path);
            read_attrs(path, &pad.attrs);
            pad.fd = fd;
        } else {
            close(fd);
        }
    }
    closedir(dir);
    return pad.fd;
}

static int make_clone(int fd, const char *name) {
    int u = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (u < 0) return -1;

    unsigned long ev[NLONGS(EV_CNT)] = {0}, key[NLONGS(KEY_CNT)] = {0}, abs[NLONGS(ABS_CNT)] = {0},
                  msc[NLONGS(MSC_CNT)] = {0}, prop[NLONGS(INPUT_PROP_CNT)] = {0};
    ioctl(fd, EVIOCGBIT(0, sizeof ev), ev);
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof key), key);
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof abs), abs);
    ioctl(fd, EVIOCGBIT(EV_MSC, sizeof msc), msc);
    ioctl(fd, EVIOCGPROP(sizeof prop), prop);

    // The pad has no force feedback; a clone advertising it would need an upload handler we lack.
    for (int t = 0; t < EV_CNT; t++)
        if (test_bit(t, ev) && t != EV_FF && t != EV_FF_STATUS) ioctl(u, UI_SET_EVBIT, t);
    for (int c = 0; c < KEY_CNT; c++) if (test_bit(c, key)) ioctl(u, UI_SET_KEYBIT, c);
    for (int c = 0; c < MSC_CNT; c++) if (test_bit(c, msc)) ioctl(u, UI_SET_MSCBIT, c);
    for (int c = 0; c < INPUT_PROP_CNT; c++) if (test_bit(c, prop)) ioctl(u, UI_SET_PROPBIT, c);
    for (int c = 0; c < ABS_CNT; c++) {
        if (!test_bit(c, abs)) continue;
        struct uinput_abs_setup s = { .code = c };
        ioctl(fd, EVIOCGABS(c), &s.absinfo);
        ioctl(u, UI_SET_ABSBIT, c);
        ioctl(u, UI_ABS_SETUP, &s);
    }

    // No phys/uniq: AYN's pad has neither, and Android's descriptor is built from what is set here.
    struct uinput_setup setup = {0};
    ioctl(fd, EVIOCGID, &setup.id);
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "%s", name);
    if (ioctl(u, UI_DEV_SETUP, &setup) < 0 || ioctl(u, UI_DEV_CREATE) < 0) {
        close(u);
        return -1;
    }
    return u;
}

static int take_over(void) {
    char name[UINPUT_MAX_NAME_SIZE];
    if (open_pad(name, sizeof name) < 0) return -1;
    if (unlink(pad.path) != 0) { release_pad(1); return -1; }
    pad.unlinked = 1;
    usleep(CLONE_SETTLE_US);
    ufd = make_clone(pad.fd, name);
    if (ufd < 0) { release_pad(1); return -1; }
    dprintf(sock, "PAD %s\n", name);
    return 0;
}

static int connect_app(const char *name) {
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    size_t len = strlen(name);
    if (len + 1 >= sizeof addr.sun_path) return -1;
    // Abstract namespace (leading NUL): no file to protect, so the app checks our uid instead.
    memcpy(addr.sun_path + 1, name, len);
    socklen_t addr_len = offsetof(struct sockaddr_un, sun_path) + 1 + len;
    for (int attempt = 0; attempt < 50; attempt++) {
        int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (s >= 0 && connect(s, (struct sockaddr *)&addr, addr_len) == 0) return s;
        if (s >= 0) close(s);
        usleep(100000);
    }
    return -1;
}

static void report(const char *axis, int value) {
    char line[32];
    int n = snprintf(line, sizeof line, "HAT %s %d\n", axis, value);
    if (write(sock, line, n) != n) stop = 1;
}

// Only navigation keys are accepted on the authenticated app socket.
static void navigation_key(int key) {
    int axis, value;
    switch (key) {
        case KEY_UP: axis = ABS_HAT0Y; value = -1; break;
        case KEY_DOWN: axis = ABS_HAT0Y; value = 1; break;
        case KEY_LEFT: axis = ABS_HAT0X; value = -1; break;
        case KEY_RIGHT: axis = ABS_HAT0X; value = 1; break;
        default: return;
    }
    if (ufd < 0) return;
    // Use the same hat path as the physical pad. BTN_DPAD key bits alone do not guarantee
    // that AYN's controller key layout maps them to Android navigation keys.
    struct input_event event = { .type = EV_ABS, .code = axis, .value = value };
    struct input_event sync = { .type = EV_SYN, .code = SYN_REPORT };
    if (write(ufd, &event, sizeof event) != sizeof event ||
        write(ufd, &sync, sizeof sync) != sizeof sync) { stop = 1; return; }
    // A down and neutral in the same frame can be coalesced into neutral by Android's
    // joystick synthesizer. Span two frames so one replay always creates one direction press.
    usleep(40000);
    event.value = 0;
    if (write(ufd, &event, sizeof event) != sizeof event ||
        write(ufd, &sync, sizeof sync) != sizeof sync) stop = 1;
}

// Directions the focused app keeps while Back is held (bits: up 1, down 2, left 4, right 8).
static int pass_mask = 0;

static int passes(int code, int value) {
    int bit = code == ABS_HAT0X ? (value < 0 ? 4 : 8) : (value < 0 ? 1 : 2);
    return (pass_mask & bit) != 0;
}

static int read_switcher_mode(char *buffer, size_t *used, int current) {
    ssize_t n = read(sock, buffer + *used, 127 - *used);
    if (n <= 0) return -1;
    *used += (size_t)n;
    buffer[*used] = '\0';

    char *line = buffer;
    char *newline;
    while ((newline = strchr(line, '\n')) != NULL) {
        *newline = '\0';
        int mode, key, mask;
        if (sscanf(line, "SWITCHER %d", &mode) == 1 && mode >= 0 && mode <= 3) current = mode;
        if (sscanf(line, "PASS %d", &mask) == 1 && mask >= 0 && mask <= 15) pass_mask = mask;
        if (sscanf(line, "NAV %d", &key) == 1 && current == 2) navigation_key(key);
        line = newline + 1;
    }
    size_t remaining = *used - (size_t)(line - buffer);
    memmove(buffer, line, remaining);
    *used = remaining;
    return current;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s <abstract-socket-name>\n", argv[0]); return 2; }
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGHUP, on_signal);
    signal(SIGPIPE, SIG_IGN);

    restore_missing_nodes();
    sock = connect_app(argv[1]);
    if (sock < 0) return 1;
    dprintf(sock, "READY\n");

    int back = 0, home = 0, cap_x = 0, cap_y = 0, switcher = 0, emergency = 0;
    char command_buffer[128] = {0};
    size_t command_used = 0;
    long both_since = 0;

    while (!stop) {
        if (ufd < 0) { // no usable pad yet, or a style switch just removed it
            if (take_over() < 0) {
                struct pollfd sp = { .fd = sock, .events = POLLIN };
                if (poll(&sp, 1, 300) > 0) break; // app went away while we were waiting
                continue;
            }
            back = home = cap_x = cap_y = 0;
            both_since = 0;
        }

        struct pollfd pfd[2] = { { .fd = pad.fd, .events = POLLIN }, { .fd = sock, .events = POLLIN } };
        if (poll(pfd, 2, -1) < 0) { if (errno == EINTR) continue; break; }
        if (pfd[1].revents) {
            int mode = read_switcher_mode(command_buffer, &command_used, switcher);
            if (mode < 0) break; // EOF or hangup: the app is gone, so hand the pad back
            switcher = mode;
        }
        if (pfd[0].revents & (POLLERR | POLLHUP)) goto pad_gone;
        if (!(pfd[0].revents & POLLIN)) continue;

        struct input_event evs[64];
        ssize_t n = read(pad.fd, evs, sizeof evs);
        if (n < 0 && errno == ENODEV) goto pad_gone;
        if (n <= 0) continue;

        for (int i = 0; i < n / (ssize_t)sizeof evs[0]; i++) {
            struct input_event *e = &evs[i];
            if (e->type == EV_KEY && e->code == KEY_BACK) back = e->value != 0;
            if (e->type == EV_KEY && e->code == KEY_HOME) home = e->value != 0;
            if (e->type == EV_ABS && (e->code == ABS_HAT0X || e->code == ABS_HAT0Y)) {
                int *cap = e->code == ABS_HAT0X ? &cap_x : &cap_y;
                // A press that starts while Back is held is ours until it is released, even if Back
                // goes first; a press that started before Back stays the game's. Mode 3 suspends
                // all new captures without recreating the controller under a running game. An app
                // exception passes chosen directions through a held Back; the switchers ignore it.
                int passed = e->value != 0 && back && switcher == 0 && passes(e->code, e->value);
                if (e->value != 0 && ((back && switcher == 0 && !passed) || switcher == 1 || switcher == 2)) *cap = 1;
                // A passed press still reaches the game below; the app only learns it was a combo.
                if (passed) report(e->code == ABS_HAT0X ? "X" : "Y", e->value);
                if (*cap == 1) {
                    report(e->code == ABS_HAT0X ? "X" : "Y", e->value);
                    if (e->value == 0) *cap = 0;
                    continue;
                }

            }
            if (write(ufd, e, sizeof *e) != (ssize_t)sizeof *e) { stop = 1; break; }
        }
        // Escape hatch that does not depend on the app: Home + Back for 5 s returns the pad to Android
        // and tells the app not to start us again.
        if (back && home) {
            if (!both_since) both_since = now_ms();
            else if (now_ms() - both_since > EMERGENCY_HOLD_MS) { emergency = 1; break; }
        } else {
            both_since = 0;
        }
        continue;

    pad_gone:
        destroy_clone();
        release_pad(0);
        dprintf(sock, "PAD_GONE\n");
    }

    destroy_clone(); // before the node returns, so the pad gets controller number 1 back
    release_pad(1);
    if (emergency) dprintf(sock, "RELEASED\n");
    close(sock);
    return 0;
}
