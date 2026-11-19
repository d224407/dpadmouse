/*
 * evgrab: exclusive key grab (EVIOCGRAB) with pass-through. Runs as the shell user.
 *
 * stdin  (one command per line):
 *   G 1 | G 0        grab / release all key devices
 *   K <code> ...     Linux key codes to consume (replaces the list)
 * stdout:
 *   READY <n>        started, n devices found
 *   E <code> <val>   consumed key event (0 = up, 1 = down)
 *   OK | ERR <msg>   result of G
 *
 * While grabbed, every other event is re-injected through a uinput clone of the
 * device, so the rest of the remote keeps working. Exits when stdin closes, which
 * also releases every grab.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define MAX_DEV 16
#define MAX_KEYS 64
#define NBYTES(n) (((n) + 7) / 8 + 1)

struct dev {
    int fd, ui;
    char path[64], name[80];
};

static struct dev devs[MAX_DEV];
static int ndev, grabbed;
static int keys[MAX_KEYS], nkeys;

static int tbit(const unsigned char *b, int n) { return (b[n / 8] >> (n % 8)) & 1; }

static int consumed(int code) {
    for (int i = 0; i < nkeys; i++)
        if (keys[i] == code) return 1;
    return 0;
}

static int is_keyboard(int fd) {
    unsigned char ev[NBYTES(EV_MAX)] = {0}, key[NBYTES(KEY_MAX)] = {0};
    if (ioctl(fd, EVIOCGBIT(0, sizeof ev), ev) < 0 || !tbit(ev, EV_KEY)) return 0;
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof key), key) < 0) return 0;
    return tbit(key, KEY_ENTER) || tbit(key, KEY_UP) || tbit(key, KEY_OK) || tbit(key, KEY_SELECT);
}

static int type_max(int t) {
    switch (t) {
    case EV_KEY: return KEY_MAX;
    case EV_REL: return REL_MAX;
    case EV_ABS: return ABS_MAX;
    case EV_MSC: return MSC_MAX;
    default: return -1;
    }
}

static int set_ioctl(int t) {
    switch (t) {
    case EV_KEY: return UI_SET_KEYBIT;
    case EV_REL: return UI_SET_RELBIT;
    case EV_ABS: return UI_SET_ABSBIT;
    default: return UI_SET_MSCBIT;
    }
}

/* Virtual copy of a device with the same capabilities, used to pass events through. */
static int make_clone(const struct dev *d) {
    int ui = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (ui < 0) return -1;
    struct uinput_user_dev u;
    memset(&u, 0, sizeof u);
    unsigned char ev[NBYTES(EV_MAX)] = {0};
    ioctl(d->fd, EVIOCGBIT(0, sizeof ev), ev);
    for (int t = 1; t < EV_MAX; t++) {
        int max = type_max(t);
        if (max < 0 || !tbit(ev, t)) continue;
        unsigned char bits[NBYTES(KEY_MAX)] = {0};
        ioctl(d->fd, EVIOCGBIT(t, sizeof bits), bits);
        ioctl(ui, UI_SET_EVBIT, t);
        for (int c = 0; c <= max; c++) {
            if (!tbit(bits, c)) continue;
            ioctl(ui, set_ioctl(t), c);
            if (t == EV_ABS) {
                struct input_absinfo ai;
                if (ioctl(d->fd, EVIOCGABS(c), &ai) == 0) {
                    u.absmin[c] = ai.minimum;
                    u.absmax[c] = ai.maximum;
                    u.absfuzz[c] = ai.fuzz;
                    u.absflat[c] = ai.flat;
                }
            }
        }
    }
    snprintf(u.name, UINPUT_MAX_NAME_SIZE, "dpadmouse-%s", d->name);
    ioctl(d->fd, EVIOCGID, &u.id);
    if (write(ui, &u, sizeof u) != (ssize_t)sizeof u || ioctl(ui, UI_DEV_CREATE) < 0) {
        close(ui);
        return -1;
    }
    return ui;
}

static int grab_dev(struct dev *d, int on) {
    if (on && d->ui < 0 && (d->ui = make_clone(d)) < 0) {
        errno = ENODEV;
        return -1;
    }
    return ioctl(d->fd, EVIOCGRAB, on ? 1 : 0);
}

static void add_dev(const char *path) {
    for (int i = 0; i < ndev; i++)
        if (!strcmp(devs[i].path, path)) return;
    if (ndev >= MAX_DEV) return;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return;
    struct dev *d = &devs[ndev];
    memset(d->name, 0, sizeof d->name);
    ioctl(fd, EVIOCGNAME(sizeof d->name - 1), d->name);
    if (!strncmp(d->name, "dpadmouse", 9) || !is_keyboard(fd)) {
        close(fd);
        return;
    }
    d->fd = fd;
    d->ui = -1;
    snprintf(d->path, sizeof d->path, "%s", path);
    if (grabbed && grab_dev(d, 1) < 0) {
        close(fd);
        return;
    }
    ndev++;
}

static void remove_dev(int i) {
    if (devs[i].ui >= 0) {
        ioctl(devs[i].ui, UI_DEV_DESTROY);
        close(devs[i].ui);
    }
    close(devs[i].fd);
    devs[i] = devs[--ndev];
}

static void scan(void) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return;
    struct dirent *e;
    char path[64];
    while ((e = readdir(dir)))
        if (!strncmp(e->d_name, "event", 5)) {
            snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
            add_dev(path);
        }
    closedir(dir);
}

static void set_grab(int on) {
    if (on) scan();
    for (int i = 0; i < ndev; i++) {
        if (grab_dev(&devs[i], on) < 0 && on) {
            printf("ERR grab %s: %s\n", devs[i].path, strerror(errno));
            for (int j = 0; j < i; j++) grab_dev(&devs[j], 0);
            grabbed = 0;
            fflush(stdout);
            return;
        }
    }
    grabbed = on;
    printf("OK\n");
    fflush(stdout);
}

static void command(char *line) {
    if (line[0] == 'G') {
        set_grab(atoi(line + 1) != 0);
    } else if (line[0] == 'K') {
        nkeys = 0;
        char *p = line + 1, *end;
        for (long v; nkeys < MAX_KEYS; p = end) {
            v = strtol(p, &end, 10);
            if (end == p) break;
            keys[nkeys++] = (int)v;
        }
    }
}

/* Returns -1 when the device is gone. */
static int on_device(struct dev *d) {
    struct input_event ie[32];
    ssize_t r = read(d->fd, ie, sizeof ie);
    if (r < 0) return (errno == EAGAIN || errno == EINTR) ? 0 : -1;
    for (size_t i = 0; i < (size_t)r / sizeof ie[0]; i++) {
        if (ie[i].type == EV_KEY && consumed(ie[i].code)) {
            if (ie[i].value != 2) printf("E %d %d\n", ie[i].code, ie[i].value);
            continue;
        }
        if (grabbed && d->ui >= 0 && write(d->ui, &ie[i], sizeof ie[i]) < 0) { /* queue full: drop */ }
    }
    fflush(stdout);
    return 0;
}

int main(void) {
    int in = inotify_init1(IN_CLOEXEC);
    if (in >= 0) inotify_add_watch(in, "/dev/input", IN_CREATE | IN_ATTRIB);
    scan();
    printf("READY %d\n", ndev);
    fflush(stdout);

    char buf[512];
    size_t blen = 0;
    for (;;) {
        struct pollfd pf[MAX_DEV + 2];
        int n = 0, nd = ndev;
        pf[n++] = (struct pollfd){0, POLLIN, 0};
        pf[n++] = (struct pollfd){in, POLLIN, 0};
        for (int i = 0; i < nd; i++) pf[n++] = (struct pollfd){devs[i].fd, POLLIN, 0};
        if (poll(pf, n, -1) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        for (int i = nd - 1; i >= 0; i--)
            if (pf[i + 2].revents && on_device(&devs[i]) < 0) remove_dev(i);
        if (in >= 0 && (pf[1].revents & POLLIN)) {
            char junk[512];
            while (read(in, junk, sizeof junk) > 0) {}
            scan();
        }
        if (pf[0].revents) {
            ssize_t r = read(0, buf + blen, sizeof buf - blen - 1);
            if (r <= 0) break;
            blen += (size_t)r;
            char *nl;
            while ((nl = memchr(buf, '\n', blen))) {
                *nl = 0;
                command(buf);
                blen -= (size_t)(nl + 1 - buf);
                memmove(buf, nl + 1, blen);
            }
            if (blen >= sizeof buf - 1) blen = 0;
        }
    }
    return 0;
}
