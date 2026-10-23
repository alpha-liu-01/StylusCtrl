/* Freestanding EVIOCGRAB reader. The running daemon is tools/stylusctld.c. */
typedef unsigned short u16;
typedef unsigned int u32;
typedef long i64;

struct input_event {
    i64 sec;
    i64 usec;
    u16 type;
    u16 code;
    u32 value;
};

static i64 syscall3(i64 num, i64 a0, i64 a1, i64 a2) {
    register i64 x8 __asm__("x8") = num;
    register i64 x0 __asm__("x0") = a0;
    register i64 x1 __asm__("x1") = a1;
    register i64 x2 __asm__("x2") = a2;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory", "cc");
    return x0;
}

static i64 syscall4(i64 num, i64 a0, i64 a1, i64 a2, i64 a3) {
    register i64 x8 __asm__("x8") = num;
    register i64 x0 __asm__("x0") = a0;
    register i64 x1 __asm__("x1") = a1;
    register i64 x2 __asm__("x2") = a2;
    register i64 x3 __asm__("x3") = a3;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3)
                     : "memory", "cc");
    return x0;
}

static void wr(const char *s) {
    i64 n = 0;
    while (s[n]) n++;
    syscall3(64, 1, (i64)s, n);
}

static void wrn(i64 v) {
    char buf[24];
    int i = 24;
    unsigned long u;
    if (v < 0) {
        wr("-");
        u = (unsigned long)(-v);
    } else {
        u = (unsigned long)v;
    }
    if (u == 0) {
        wr("0");
        return;
    }
    while (u) {
        buf[--i] = (char)('0' + (u % 10));
        u /= 10;
    }
    syscall3(64, 1, (i64)(buf + i), 24 - i);
}

void _start(void) {
    i64 *frame;
    __asm__ volatile("mov %0, sp" : "=r"(frame));
    i64 argc = frame[0];
    char **argv = (char **)(frame + 1);
    if (argc < 2) {
        wr("usage: pengrab /dev/input/eventN\n");
        syscall3(93, 2, 0, 0);
    }
    i64 fd = syscall4(56, -100, (i64)argv[1], 0, 0); /* openat AT_FDCWD O_RDONLY */
    if (fd < 0) {
        wr("open failed ");
        wrn(fd);
        wr("\n");
        syscall3(93, 1, 0, 0);
    }
    /* EVIOCGRAB = _IOW('E', 0x90, int) */
    i64 rc = syscall3(29, fd, 0x40044590, 1);
    if (rc < 0) {
        wr("grab failed ");
        wrn(rc);
        wr("\n");
        syscall3(93, 1, 0, 0);
    }
    wr("grab ok\n");
    i64 flags = syscall3(25, fd, 3, 0); /* fcntl F_GETFL */
    syscall3(25, fd, 4, flags | 04000);  /* F_SETFL O_NONBLOCK */
    struct input_event ev;
    i64 reports = 0;
    i64 keys = 0;
    for (int tick = 0; tick < 200; tick++) {
        for (;;) {
            i64 n = syscall3(63, fd, (i64)&ev, sizeof ev);
            if (n == (i64)sizeof ev) {
                if (ev.type == 0 && ev.code == 0) reports++;
                if (ev.type == 1) keys++;
                continue;
            }
            break;
        }
        struct { i64 sec; i64 nsec; } req = {0, 10000000};
        syscall3(101, (i64)&req, 0, 0); /* nanosleep 10ms */
    }
    syscall3(29, fd, 0x40044590, 0);
    wr("reports ");
    wrn(reports);
    wr(" keys ");
    wrn(keys);
    wr("\nrelease ok\n");
    syscall3(93, 0, 0, 0);
}
