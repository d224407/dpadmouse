/*
 * evgrab: EVIOCGRAB key blocking with pass-through, run as the shell user.
 *
 * Only devices whose exact name was allowed (D) are ever opened or grabbed, never virtual
 * devices. Every event that is not consumed is re-injected through a uinput clone. Keys in
 * the protected list (built-in + W) are never consumed.
 *
 * stdin (one command per line):
 *   DC | D <name>   clear / add an allowed device name
 *   K <code> ...    Linux key codes to consume
 *   W <code> ...    extra protected key codes (never consumed)
 *   G 1 | G 0       grab / release
 *   P               heartbeat
 * stdout:
 *   READY <n> | OK | REGRAB | WARN <msg> | ERR <msg> | E <code> <val>
 *
 * Fail-open: no command for WATCHDOG_MS (app hung) -> grab released, re-armed by the next P.
 * Exits on stdin EOF or SIGTERM/SIGHUP/SIGINT, releasing every grab and virtual device.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define MAX_DEV 8
#define MAX_ALLOW 8
#define MAX_KEYS 64
#define NBYTES(n) (((n) + 7) / 8 + 1)
#define WATCHDOG_MS 10000
#define PENDING_MS 3000

struct dev {
    int fd, ui;
    char path[64], name[80];
    unsigned char fwd[NBYTES(KEY_MAX)]; /* keys currently held down on the clone */
};

static struct dev devs[MAX_DEV];
static int ndev;
static char allow[MAX_ALLOW][80];
static int nallow;
static int keys[MAX_KEYS], nkeys, white[MAX_KEYS], nwhite;
static int want, grabbed, pending;
static long long pending_since, last_cmd;
static volatile sig_atomic_t quit;

/* Keys that must always reach the system. */
static const int builtin[] = {KEY_POWER, KEY_SLEEP, KEY_WAKEUP, KEY_HOME, KEY_HOMEPAGE, KEY_BACK,
                              KEY_MUTE, KEY_VOLUMEDOWN, KEY_VOLUMEUP};

static long long now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

/* stdout is non-blocking: a stalled reader must never be able to block this loop. */
static void out(const char *fmt, ...) {
    char b[160];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(b, sizeof b, fmt, ap);
    va_end(ap);
    if (n > 0 && write(1, b, (size_t)(n < (int)sizeof b ? n : (int)sizeof b - 1)) < 0) { /* drop */ }
}

static int tbit(const unsigned char *b, int n) { return (b[n / 8] >> (n % 8)) & 1; }

static int in_list(const int *l, int n, int code) {
    for (int i = 0; i < n; i++)
        if (l[i] == code) return 1;
    return 0;
}

static int is_protected(int code) {
    return in_list(builtin, (int)(sizeof builtin / sizeof builtin[0]), code) || in_list(white, nwhite, code);
}

static int consumed(int code) { return in_list(keys, nkeys, code) && !is_protected(code); }

static int allowed(const char *name) {
    for (int i = 0; i < nallow; i++)
        if (!strcmp(allow[i], name)) return 1;
    return 0;
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

static void emit(int ui, int type, int code, int value) {
    struct input_event e;
    memset(&e, 0, sizeof e);
    e.type = (unsigned short)type;
    e.code = (unsigned short)code;
    e.value = value;
    if (write(ui, &e, sizeof e) < 0) { /* queue full: drop */ }
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
    snprintf(u.name, UINPUT_MAX_NAME_SIZE, "dpadmouse-%.60s", d->name);
    ioctl(d->fd, EVIOCGID, &u.id);
    if (write(ui, &u, sizeof u) != (ssize_t)sizeof u || ioctl(ui, UI_DEV_CREATE) < 0) {
        close(ui);
        return -1;
    }
    return ui;
}

/* Key-ups for everything still held on the clone, so the system never sees a stuck key. */
static void release_fwd(struct dev *d) {
    if (d->ui < 0) return;
    int any = 0;
    for (int c = 0; c <= KEY_MAX; c++)
        if (tbit(d->fwd, c)) {
            emit(d->ui, EV_KEY, c, 0);
            any = 1;
        }
    if (any) emit(d->ui, EV_SYN, SYN_REPORT, 0);
    memset(d->fwd, 0, sizeof d->fwd);
}

static int keys_down(const struct dev *d) {
    unsigned char k[NBYTES(KEY_MAX)] = {0};
    if (ioctl(d->fd, EVIOCGKEY(sizeof k), k) < 0) return 0;
    for (size_t i = 0; i < sizeof k; i++)
        if (k[i]) return 1;
    return 0;
}

static int grab_dev(struct dev *d, int on) {
    if (on) {
        if (d->ui < 0 && (d->ui = make_clone(d)) < 0) {
            errno = ENODEV;
            return -1;
        }
        return ioctl(d->fd, EVIOCGRAB, 1);
    }
    release_fwd(d);
    return ioctl(d->fd, EVIOCGRAB, 0);
}

static void free_dev(int i) {
    struct dev *d = &devs[i];
    if (grabbed) grab_dev(d, 0);
    if (d->ui >= 0) {
        ioctl(d->ui, UI_DEV_DESTROY);
        close(d->ui);
    }
    close(d->fd);
    devs[i] = devs[--ndev];
}

static void add_dev(const char *path) {
    for (int i = 0; i < ndev; i++)
        if (!strcmp(devs[i].path, path)) return;
    if (ndev >= MAX_DEV) return;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return;
    struct dev *d = &devs[ndev];
    memset(d, 0, sizeof *d);
    ioctl(fd, EVIOCGNAME(sizeof d->name - 1), d->name);
    struct input_id id;
    unsigned char ev[NBYTES(EV_MAX)] = {0};
    if (!allowed(d->name) || !strncmp(d->name, "dpadmouse", 9) ||
        ioctl(fd, EVIOCGID, &id) < 0 || id.bustype == BUS_VIRTUAL ||
        ioctl(fd, EVIOCGBIT(0, sizeof ev), ev) < 0 || !tbit(ev, EV_KEY)) {
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

static void scan(void) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return;
    struct dirent *e;
    char path[300];
    while ((e = readdir(dir)))
        if (!strncmp(e->d_name, "event", 5)) {
            snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
            add_dev(path);
        }
    closedir(dir);
}

static void release_all(void) {
    for (int i = 0; i < ndev; i++) grab_dev(&devs[i], 0);
    grabbed = 0;
    pending = 0;
}

/* Grabs only when no key is held (a held key would end up stuck). Returns 1 done, 0 waiting, -1 failed. */
static int try_grab(void) {
    scan();
    if (ndev == 0) {
        out("ERR no allowed device found\n");
        return -1;
    }
    for (int i = 0; i < ndev; i++)
        if (keys_down(&devs[i])) {
            if (!pending) pending_since = now_ms();
            pending = 1;
            return 0;
        }
    for (int i = 0; i < ndev; i++)
        if (grab_dev(&devs[i], 1) < 0) {
            out("ERR grab %s: %s\n", devs[i].path, strerror(errno));
            for (int j = 0; j < i; j++) grab_dev(&devs[j], 0);
            return -1;
        }
    pending = 0;
    grabbed = 1;
    return 1;
}

static void do_grab(int announce) {
    int r = try_grab();
    if (r == 1) {
        out(announce ? "OK\n" : "REGRAB\n");
    } else if (r < 0) {
        want = 0;
        pending = 0;
    }
}

/* Applies the allowed-device list: drops devices no longer allowed, picks up new ones. */
static void apply_allow(void) {
    for (int i = ndev - 1; i >= 0; i--)
        if (!allowed(devs[i].name)) free_dev(i);
    scan();
}

static void parse_list(const char *p, int *dst, int *n) {
    *n = 0;
    char *end;
    for (long v; *n < MAX_KEYS; p = end) {
        v = strtol(p, &end, 10);
        if (end == p) break;
        dst[(*n)++] = (int)v;
    }
}

static void command(char *line) {
    last_cmd = now_ms();
    if (line[0] == 'G') {
        if (atoi(line + 1)) {
            want = 1;
            do_grab(1);
        } else {
            want = 0;
            release_all();
            out("OK\n");
        }
    } else if (line[0] == 'K') {
        parse_list(line + 1, keys, &nkeys);
    } else if (line[0] == 'W') {
        parse_list(line + 1, white, &nwhite);
    } else if (line[0] == 'P') {
        if (want && !grabbed && !pending) do_grab(0);
    } else if (!strcmp(line, "DC")) {
        nallow = 0;
        apply_allow();
    } else if (line[0] == 'D' && line[1] == ' ' && nallow < MAX_ALLOW) {
        snprintf(allow[nallow++], sizeof allow[0], "%s", line + 2);
        apply_allow();
    }
}

/* Returns -1 when the device is gone. */
static int on_device(struct dev *d) {
    struct input_event ie[32];
    ssize_t r = read(d->fd, ie, sizeof ie);
    if (r < 0) return (errno == EAGAIN || errno == EINTR) ? 0 : -1;
    for (size_t i = 0; i < (size_t)r / sizeof ie[0]; i++) {
        struct input_event *e = &ie[i];
        if (e->type == EV_KEY && consumed(e->code)) {
            if (e->value != 2) out("E %d %d\n", e->code, e->value);
            continue;
        }
        if (!grabbed || d->ui < 0) continue;
        if (e->type == EV_KEY && e->code <= KEY_MAX) {
            if (e->value == 1) d->fwd[e->code / 8] |= (unsigned char)(1 << (e->code % 8));
            else if (e->value == 0) d->fwd[e->code / 8] &= (unsigned char)~(1 << (e->code % 8));
        }
        if (write(d->ui, e, sizeof *e) < 0) { /* queue full: drop */ }
    }
    return 0;
}

static void on_signal(int s) {
    (void)s;
    quit = 1;
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_signal; /* no SA_RESTART: poll() returns so the loop sees `quit` */
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    fcntl(0, F_SETFL, fcntl(0, F_GETFL) | O_NONBLOCK);
    fcntl(1, F_SETFL, fcntl(1, F_GETFL) | O_NONBLOCK);

    int in = inotify_init1(IN_CLOEXEC | IN_NONBLOCK); /* must be non-blocking: the drain loop below */
    if (in >= 0) inotify_add_watch(in, "/dev/input", IN_CREATE | IN_ATTRIB);
    last_cmd = now_ms();
    out("READY %d\n", ndev);

    char buf[512];
    size_t blen = 0;
    while (!quit) {
        struct pollfd pf[MAX_DEV + 2];
        int n = 0, nd = ndev;
        pf[n++] = (struct pollfd){0, POLLIN, 0};
        pf[n++] = (struct pollfd){in, POLLIN, 0};
        for (int i = 0; i < nd; i++) pf[n++] = (struct pollfd){devs[i].fd, POLLIN, 0};
        int timeout = pending ? 30 : grabbed ? 1000 : -1;
        if (poll(pf, n, timeout) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        for (int i = nd - 1; i >= 0; i--)
            if (pf[i + 2].revents && on_device(&devs[i]) < 0) free_dev(i);
        if (in >= 0 && (pf[1].revents & POLLIN)) {
            char junk[512];
            while (read(in, junk, sizeof junk) > 0) {}
            if (nallow) scan();
        }
        if (pf[0].revents) {
            ssize_t r = read(0, buf + blen, sizeof buf - blen - 1);
            if (r == 0) break;
            if (r > 0) {
                blen += (size_t)r;
                char *nl;
                while ((nl = memchr(buf, '\n', blen))) {
                    *nl = 0;
                    command(buf);
                    blen -= (size_t)(nl + 1 - buf);
                    memmove(buf, nl + 1, blen);
                }
                if (blen >= sizeof buf - 1) blen = 0;
            } else if (errno != EAGAIN && errno != EINTR) {
                break;
            }
        }
        long long t = now_ms();
        if (pending) {
            if (t - pending_since > PENDING_MS) {
                out("ERR keys held, could not grab\n");
                want = 0;
                pending = 0;
            } else {
                do_grab(1);
            }
        }
        if (grabbed && t - last_cmd > WATCHDOG_MS) {
            release_all();
            out("WARN released: no heartbeat\n");
        }
    }
    while (ndev > 0) free_dev(ndev - 1);
    return 0;
}
