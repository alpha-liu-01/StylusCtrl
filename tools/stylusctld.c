/* Grab the pointer pen and pass it through uinput.
   A json profile (phase 2) remaps area, pressure, and buttons.
   No file means phase 1 passthrough. */
#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/hidraw.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <sys/inotify.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#ifndef BTN_STYLUS2
#define BTN_STYLUS2 0x14c
#endif
#ifndef KEY_HOMEPAGE
#define KEY_HOMEPAGE 172
#endif
#ifndef SW_PEN_INSERTED
#define SW_PEN_INSERTED 0x0f
#endif
#ifndef SW_MACHINE_COVER
#define SW_MACHINE_COVER 0x10
#endif
#ifndef BUS_I2C
#define BUS_I2C 0x18
#endif

/* sec_e-pen is Samsung's digitizer. Any other built-in pen is an I2C node
   with INPUT_PROP_DIRECT, a pen tool, and no multitouch slot. Scarlet's
   node is "hid-over-i2c 2D1F:0036 Stylus". The sibling without DIRECT is
   not a pen. A finger screen is DIRECT too, but it has ABS_MT_SLOT.
   A USB tablet that reports DIRECT is left alone: area mapping for that
   case is not the internal path. */
static int is_builtin_pen(const char *name, int direct, int pen_tool, int multitouch, unsigned bustype) {
    if (!name || !strncmp(name, "StylusCtrl", 10)) return 0;
    if (!strcmp(name, "sec_e-pen")) return 1;
    if (!direct || !pen_tool || multitouch) return 0;
    return bustype == BUS_I2C;
}

/* sec_e-pen reports this key. The header on the build machine has no name for it. */
#define KEY_SEC_EPEN_EXTRA 0x00fd

#define MAX_PENS 4
#define MAX_PACKET 128
#define AXIS_X (1u << 0)
#define AXIS_Y (1u << 1)
#define AXIS_PRESSURE (1u << 2)
#define AXIS_TILT_X (1u << 3)
#define AXIS_TILT_Y (1u << 4)
#define AXIS_ALL (AXIS_X | AXIS_Y | AXIS_PRESSURE | AXIS_TILT_X | AXIS_TILT_Y)

struct tool_state {
    int phys_pen;
    int phys_rubber;
    int phys_touch;
    int phys_stylus;
    /* 5.10 hid-input latches BTN_TOOL_RUBBER until the pen leaves range.
       The Invert usage in the raw report still follows the second button. */
    int invert_known;
    int invert_down;
    int sent_pen;
    int sent_rubber;
    int sent_stylus2;
    unsigned axes;
    /* Internal digitizer: also forward distance, the dock switches, and its extra keys. */
    int relay;
};

enum {
    MODE_PASS = 0,
    MODE_STRETCH = 1,
    MODE_FIT = 2
};

enum {
    CROP_CENTER = 0,
    CROP_LEFT = 1,
    CROP_RIGHT = 2,
    CROP_TOP = 3,
    CROP_BOTTOM = 4
};

enum {
    STYLUS_PASS = 0,
    STYLUS_SHORTCUT = 1,
    STYLUS_ERASER = 2
};

enum {
    RUBBER_ERASER = 0,
    RUBBER_SHORTCUT = 1,
    RUBBER_STYLUS2 = 2
};

struct profile {
    int mode;
    int crop;
    int has_rect;
    int rect[4];
    int has_match;
    int saw_vendor;
    int saw_product;
    unsigned vendor;
    unsigned product;
    double curve[8];
    int curve_n;
    double deadzone;
    double ceiling;
    int has_screen;
    double screen[4];
    int rotation;
    int stylus_action;
    int rubber_action;
    int eraser_wins;
    int stylus_keys[8];
    int stylus_nkeys;
    int rubber_keys[8];
    int rubber_nkeys;
    int internal_on;
    /* Set when internal.pressure or internal.buttons is present. Otherwise the
       internal pen uses the external curve and shortcuts. */
    int in_ready;
    double in_curve[8];
    int in_curve_n;
    double in_deadzone;
    double in_ceiling;
    int in_stylus_action;
    int in_rubber_action;
    int in_eraser_wins;
    int in_stylus_keys[8];
    int in_stylus_nkeys;
    int in_rubber_keys[8];
    int in_rubber_nkeys;
    int disp_w;
    int disp_h;
    int orientation;
    int have_display;
};

static unsigned axis_bit(int code) {
    switch (code) {
    case ABS_X:
        return AXIS_X;
    case ABS_Y:
        return AXIS_Y;
    case ABS_PRESSURE:
        return AXIS_PRESSURE;
    case ABS_TILT_X:
        return AXIS_TILT_X;
    case ABS_TILT_Y:
        return AXIS_TILT_Y;
    default:
        return 0;
    }
}

static int keep_event(const struct tool_state *st, const struct input_event *ev) {
    if (ev->type == EV_ABS) {
        if (st->relay && ev->code == ABS_DISTANCE) return 1;
        unsigned bit = axis_bit(ev->code);
        return bit && (st->axes & bit);
    }
    if (ev->type == EV_KEY) {
        if (ev->code == BTN_TOOL_RUBBER || ev->code == BTN_TOUCH || ev->code == BTN_STYLUS) return 1;
        if (st->relay && (ev->code == KEY_HOMEPAGE || ev->code == KEY_SEC_EPEN_EXTRA)) return 1;
        return 0;
    }
    if (st->relay && ev->type == EV_SW) {
        return ev->code == SW_PEN_INSERTED || ev->code == SW_MACHINE_COVER;
    }
    return ev->type == EV_SYN && ev->code == SYN_REPORT;
}

/* Rubber wins over the pen tool when its action is still the eraser.
   Physical state still tracks BTN_TOOL_PEN. A null profile is phase 1. */
static int emit_report(struct tool_state *st, const struct input_event *in, int n, struct input_event *out,
                int out_cap, const struct profile *profile) {
    int pen = st->phys_pen;
    int rubber = st->phys_rubber;
    int touch = st->phys_touch;
    int stylus = st->phys_stylus;
    for (int i = 0; i < n; i++) {
        if (in[i].type != EV_KEY || (in[i].value != 0 && in[i].value != 1)) continue;
        if (in[i].code == BTN_TOOL_PEN) pen = in[i].value;
        else if (in[i].code == BTN_TOOL_RUBBER) rubber = in[i].value;
        else if (in[i].code == BTN_TOUCH) touch = in[i].value;
        else if (in[i].code == BTN_STYLUS) stylus = in[i].value;
    }
    int rubber_act = profile ? profile->rubber_action : RUBBER_ERASER;
    int stylus_act = profile ? profile->stylus_action : STYLUS_PASS;
    int eraser_wins = profile ? profile->eraser_wins : 1;
    int stylus_eraser = stylus_act == STYLUS_ERASER && stylus;
    int hide_pen = stylus_eraser || (rubber_act == RUBBER_ERASER && eraser_wins && rubber);
    int want_pen = hide_pen ? 0 : pen;
    int want_rubber = stylus_eraser || (rubber_act == RUBBER_ERASER && rubber);
    int rubber_btn = rubber;
    if (st->invert_known && rubber_act == RUBBER_STYLUS2) rubber_btn = st->invert_down;
    int want_s2 = rubber_act == RUBBER_STYLUS2 ? rubber_btn : 0;
    int count = 0;
    for (int i = 0; i < n; i++) {
        if (in[i].type == EV_SYN && in[i].code == SYN_REPORT) {
            if (st->sent_pen != want_pen) {
                if (count >= out_cap) return -1;
                out[count] = in[i];
                out[count].type = EV_KEY;
                out[count].code = BTN_TOOL_PEN;
                out[count].value = want_pen;
                count++;
            }
            if (st->sent_rubber != want_rubber) {
                if (count >= out_cap) return -1;
                out[count] = in[i];
                out[count].type = EV_KEY;
                out[count].code = BTN_TOOL_RUBBER;
                out[count].value = want_rubber;
                count++;
            }
            if (st->sent_stylus2 != want_s2) {
                if (count >= out_cap) return -1;
                out[count] = in[i];
                out[count].type = EV_KEY;
                out[count].code = BTN_STYLUS2;
                out[count].value = want_s2;
                count++;
            }
            if (count >= out_cap) return -1;
            out[count++] = in[i];
            continue;
        }
        if (in[i].type == EV_KEY && in[i].code == BTN_TOOL_PEN) continue;
        if (in[i].type == EV_KEY && in[i].code == BTN_TOOL_RUBBER) continue;
        if (in[i].type == EV_KEY && in[i].code == BTN_STYLUS && stylus_act != STYLUS_PASS) continue;
        if (!keep_event(st, &in[i])) continue;
        if (count >= out_cap) return -1;
        out[count++] = in[i];
    }
    st->phys_pen = pen;
    st->phys_rubber = rubber;
    st->phys_touch = touch;
    st->phys_stylus = stylus;
    st->sent_pen = want_pen;
    st->sent_rubber = want_rubber;
    st->sent_stylus2 = want_s2;
    return count;
}

static void profile_reset(struct profile *p) {
    int disp_w = p->disp_w;
    int disp_h = p->disp_h;
    int orientation = p->orientation;
    int have_display = p->have_display;
    memset(p, 0, sizeof *p);
    p->crop = CROP_CENTER;
    p->rubber_action = RUBBER_ERASER;
    p->eraser_wins = 1;
    p->ceiling = 1;
    p->in_rubber_action = RUBBER_ERASER;
    p->in_eraser_wins = 1;
    p->in_ceiling = 1;
    p->disp_w = disp_w;
    p->disp_h = disp_h;
    p->orientation = orientation;
    p->have_display = have_display;
}

static const char *skip_ws(const char *p) {
    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
    return p;
}

static int scan_string(const char **pp, char *out, int cap) {
    const char *p = *pp;
    if (*p != '"') return -1;
    p++;
    int n = 0;
    while (*p && *p != '"') {
        char c = *p++;
        if (c == '\\' && *p) c = *p++;
        if (out && n < cap - 1) out[n++] = c;
    }
    if (*p != '"') return -1;
    if (out) out[n] = 0;
    *pp = p + 1;
    return 0;
}

static int key_code(const char *name, int *code) {
    char buf[32];
    int n = 0;
    for (const char *s = name; *s && n < (int)sizeof buf - 1; s++) {
        buf[n++] = (char)toupper((unsigned char)*s);
    }
    buf[n] = 0;
    if (!strcmp(buf, "CTRL") || !strcmp(buf, "CTRL_LEFT") || !strcmp(buf, "LEFTCTRL")) {
        *code = KEY_LEFTCTRL;
        return 0;
    }
    if (!strcmp(buf, "ALT") || !strcmp(buf, "ALT_LEFT")) {
        *code = KEY_LEFTALT;
        return 0;
    }
    if (!strcmp(buf, "SHIFT") || !strcmp(buf, "SHIFT_LEFT")) {
        *code = KEY_LEFTSHIFT;
        return 0;
    }
    if (!strcmp(buf, "META") || !strcmp(buf, "META_LEFT")) {
        *code = KEY_LEFTMETA;
        return 0;
    }
    if (n == 1 && buf[0] >= 'A' && buf[0] <= 'Z') {
        /* Linux keycodes follow the QWERTY positions, not A–Z order. */
        static const int letters[] = {KEY_A, KEY_B, KEY_C, KEY_D, KEY_E, KEY_F, KEY_G, KEY_H, KEY_I,
                                      KEY_J, KEY_K, KEY_L, KEY_M, KEY_N, KEY_O, KEY_P, KEY_Q, KEY_R,
                                      KEY_S, KEY_T, KEY_U, KEY_V, KEY_W, KEY_X, KEY_Y, KEY_Z};
        *code = letters[buf[0] - 'A'];
        return 0;
    }
    if (n == 1 && buf[0] >= '1' && buf[0] <= '9') {
        *code = KEY_1 + buf[0] - '1';
        return 0;
    }
    if (n == 1 && buf[0] == '0') {
        *code = KEY_0;
        return 0;
    }
    if (!strcmp(buf, "SPACE")) {
        *code = KEY_SPACE;
        return 0;
    }
    if (!strcmp(buf, "ENTER") || !strcmp(buf, "RETURN")) {
        *code = KEY_ENTER;
        return 0;
    }
    if (!strcmp(buf, "TAB")) {
        *code = KEY_TAB;
        return 0;
    }
    if (!strcmp(buf, "ESC") || !strcmp(buf, "ESCAPE")) {
        *code = KEY_ESC;
        return 0;
    }
    if (!strcmp(buf, "UP")) {
        *code = KEY_UP;
        return 0;
    }
    if (!strcmp(buf, "DOWN")) {
        *code = KEY_DOWN;
        return 0;
    }
    if (!strcmp(buf, "LEFT")) {
        *code = KEY_LEFT;
        return 0;
    }
    if (!strcmp(buf, "RIGHT")) {
        *code = KEY_RIGHT;
        return 0;
    }
    if (!strcmp(buf, "BACKSPACE")) {
        *code = KEY_BACKSPACE;
        return 0;
    }
    if (!strcmp(buf, "DELETE") || !strcmp(buf, "DEL")) {
        *code = KEY_DELETE;
        return 0;
    }
    /* F1–F10 are consecutive. F11 and F12 are not. */
    if (buf[0] == 'F' && n >= 2 && n <= 3) {
        int num = 0;
        int ok = 1;
        for (int i = 1; i < n; i++) {
            if (buf[i] < '0' || buf[i] > '9') ok = 0;
            else num = num * 10 + (buf[i] - '0');
        }
        if (ok && num >= 1 && num <= 10) {
            *code = KEY_F1 + (num - 1);
            return 0;
        }
        if (ok && num == 11) {
            *code = KEY_F11;
            return 0;
        }
        if (ok && num == 12) {
            *code = KEY_F12;
            return 0;
        }
    }
    /* Names Generic.kl on this tablet already turns into system actions. */
    if (!strcmp(buf, "BACK")) {
        *code = KEY_BACK;
        return 0;
    }
    if (!strcmp(buf, "HOME")) {
        *code = KEY_HOMEPAGE;
        return 0;
    }
    if (!strcmp(buf, "RECENT")) {
        *code = KEY_SCALE;
        return 0;
    }
    if (!strcmp(buf, "APP_SWITCH")) {
        *code = KEY_APPSELECT;
        return 0;
    }
    if (!strcmp(buf, "MENU")) {
        *code = KEY_MENU;
        return 0;
    }
    if (!strcmp(buf, "COPY")) {
        *code = KEY_COPY;
        return 0;
    }
    if (!strcmp(buf, "CUT")) {
        *code = KEY_CUT;
        return 0;
    }
    if (!strcmp(buf, "PASTE")) {
        *code = KEY_PASTE;
        return 0;
    }
    if (!strcmp(buf, "MUTE")) {
        *code = KEY_MUTE;
        return 0;
    }
    if (!strcmp(buf, "VOL_DOWN") || !strcmp(buf, "VOLUME_DOWN")) {
        *code = KEY_VOLUMEDOWN;
        return 0;
    }
    if (!strcmp(buf, "VOL_UP") || !strcmp(buf, "VOLUME_UP")) {
        *code = KEY_VOLUMEUP;
        return 0;
    }
    if (!strcmp(buf, "POWER")) {
        *code = KEY_POWER;
        return 0;
    }
    if (!strcmp(buf, "SLEEP")) {
        *code = KEY_SLEEP;
        return 0;
    }
    if (!strcmp(buf, "WAKEUP") || !strcmp(buf, "WAKE")) {
        *code = KEY_WAKEUP;
        return 0;
    }
    if (!strcmp(buf, "PLAY")) {
        *code = KEY_PLAYPAUSE;
        return 0;
    }
    if (!strcmp(buf, "PREV")) {
        *code = KEY_PREVIOUSSONG;
        return 0;
    }
    if (!strcmp(buf, "NEXT")) {
        *code = KEY_NEXTSONG;
        return 0;
    }
    if (!strcmp(buf, "BRIGHTNESS_DOWN")) {
        *code = KEY_BRIGHTNESSDOWN;
        return 0;
    }
    if (!strcmp(buf, "BRIGHTNESS_UP")) {
        *code = KEY_BRIGHTNESSUP;
        return 0;
    }
    return -1;
}

static int parse_hex_id(const char *s, unsigned *out) {
    if (s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) s += 2;
    if (!*s) return -1;
    char *end = NULL;
    unsigned long v = strtoul(s, &end, 16);
    if (end == s || *end) return -1;
    *out = (unsigned)v;
    return 0;
}

static int skip_value(const char **pp, int depth);

static int skip_value(const char **pp, int depth) {
    if (depth > 8) return -1;
    const char *p = skip_ws(*pp);
    if (*p == '"') {
        if (scan_string(&p, NULL, 0) < 0) return -1;
        *pp = p;
        return 0;
    }
    if (*p == '{') {
        p++;
        p = skip_ws(p);
        if (*p == '}') {
            *pp = p + 1;
            return 0;
        }
        while (1) {
            p = skip_ws(p);
            if (scan_string(&p, NULL, 0) < 0) return -1;
            p = skip_ws(p);
            if (*p != ':') return -1;
            p++;
            if (skip_value(&p, depth + 1) < 0) return -1;
            p = skip_ws(p);
            if (*p == ',') {
                p++;
                continue;
            }
            if (*p == '}') {
                *pp = p + 1;
                return 0;
            }
            return -1;
        }
    }
    if (*p == '[') {
        p++;
        p = skip_ws(p);
        if (*p == ']') {
            *pp = p + 1;
            return 0;
        }
        while (1) {
            if (skip_value(&p, depth + 1) < 0) return -1;
            p = skip_ws(p);
            if (*p == ',') {
                p++;
                continue;
            }
            if (*p == ']') {
                *pp = p + 1;
                return 0;
            }
            return -1;
        }
    }
    if (!strncmp(p, "true", 4)) {
        *pp = p + 4;
        return 0;
    }
    if (!strncmp(p, "false", 5)) {
        *pp = p + 5;
        return 0;
    }
    if (!strncmp(p, "null", 4)) return -1;
    char *end = NULL;
    strtod(p, &end);
    if (end == p) return -1;
    *pp = end;
    return 0;
}

/* internal.pressure.* and internal.buttons.* are a second feel. The local
   path is what the external keys use, so one parser covers both. */
static int feel_path(const char *path, const char **local) {
    if (!strncmp(path, "internal.", 9)) {
        *local = path + 9;
        return 1;
    }
    *local = path;
    return 0;
}

static void view_for_pen(const struct profile *src, int direct, struct profile *dst) {
    *dst = *src;
    if (!direct || !src->in_ready) return;
    memcpy(dst->curve, src->in_curve, sizeof dst->curve);
    dst->curve_n = src->in_curve_n;
    dst->deadzone = src->in_deadzone;
    dst->ceiling = src->in_ceiling;
    dst->stylus_action = src->in_stylus_action;
    dst->rubber_action = src->in_rubber_action;
    dst->eraser_wins = src->in_eraser_wins;
    memcpy(dst->stylus_keys, src->in_stylus_keys, sizeof dst->stylus_keys);
    dst->stylus_nkeys = src->in_stylus_nkeys;
    memcpy(dst->rubber_keys, src->in_rubber_keys, sizeof dst->rubber_keys);
    dst->rubber_nkeys = src->in_rubber_nkeys;
}

static int apply_string(const char *path, const char *s, struct profile *prof) {
    const char *local;
    int inn = feel_path(path, &local);
    if (!strcmp(path, "mapping.mode")) {
        if (!strcmp(s, "passthrough")) prof->mode = MODE_PASS;
        else if (!strcmp(s, "stretch")) prof->mode = MODE_STRETCH;
        else if (!strcmp(s, "fit")) prof->mode = MODE_FIT;
        else return -1;
        return 0;
    }
    if (!strcmp(path, "mapping.crop")) {
        if (!strcmp(s, "center")) prof->crop = CROP_CENTER;
        else if (!strcmp(s, "left")) prof->crop = CROP_LEFT;
        else if (!strcmp(s, "right")) prof->crop = CROP_RIGHT;
        else if (!strcmp(s, "top")) prof->crop = CROP_TOP;
        else if (!strcmp(s, "bottom")) prof->crop = CROP_BOTTOM;
        else return -1;
        return 0;
    }
    if (!strcmp(path, "match.vendor")) {
        if (parse_hex_id(s, &prof->vendor) < 0) return -1;
        prof->saw_vendor = 1;
        prof->has_match = prof->saw_vendor && prof->saw_product;
        return 0;
    }
    if (!strcmp(path, "match.product")) {
        if (parse_hex_id(s, &prof->product) < 0) return -1;
        prof->saw_product = 1;
        prof->has_match = prof->saw_vendor && prof->saw_product;
        return 0;
    }
    if (!strcmp(local, "buttons.BTN_STYLUS.action")) {
        int act;
        if (!strcmp(s, "passthrough")) act = STYLUS_PASS;
        else if (!strcmp(s, "shortcut")) act = STYLUS_SHORTCUT;
        else if (!strcmp(s, "eraser")) act = STYLUS_ERASER;
        else return -1;
        if (inn) {
            prof->in_ready = 1;
            prof->in_stylus_action = act;
        } else prof->stylus_action = act;
        return 0;
    }
    if (!strcmp(local, "buttons.BTN_TOOL_RUBBER.action")) {
        int act;
        if (!strcmp(s, "eraser")) act = RUBBER_ERASER;
        else if (!strcmp(s, "shortcut")) act = RUBBER_SHORTCUT;
        else if (!strcmp(s, "stylus2") || !strcmp(s, "BTN_STYLUS2")) act = RUBBER_STYLUS2;
        else return -1;
        if (inn) {
            prof->in_ready = 1;
            prof->in_rubber_action = act;
        } else prof->rubber_action = act;
        return 0;
    }
    return 0;
}

static void store_curve(double *curve, int *curve_n, const double *nums, int n) {
    if (n == 8) {
        for (int i = 0; i < 8; i++) curve[i] = nums[i];
    } else {
        curve[0] = nums[0];
        curve[1] = nums[1];
        curve[2] = nums[2];
        curve[3] = nums[3];
        curve[4] = nums[2];
        curve[5] = nums[3];
        curve[6] = nums[4];
        curve[7] = nums[5];
    }
    *curve_n = 8;
}

static int assign_array(const char *path, const double *nums, int n, const int *codes, int ncodes,
                        struct profile *prof) {
    const char *local;
    int inn = feel_path(path, &local);
    if (!strcmp(local, "pressure.curve")) {
        if (n != 6 && n != 8) return -1;
        if (inn) {
            prof->in_ready = 1;
            store_curve(prof->in_curve, &prof->in_curve_n, nums, n);
        } else store_curve(prof->curve, &prof->curve_n, nums, n);
        return 0;
    }
    if (!strcmp(path, "mapping.rect")) {
        if (n != 4) return -1;
        for (int i = 0; i < 4; i++) {
            double v = nums[i];
            if (v >= 0) prof->rect[i] = (int)(v + 0.5);
            else prof->rect[i] = (int)(v - 0.5);
        }
        prof->has_rect = 1;
        return 0;
    }
    if (!strcmp(path, "mapping.screen")) {
        if (n != 4) return -1;
        for (int i = 0; i < 4; i++) prof->screen[i] = nums[i];
        prof->has_screen = 1;
        return 0;
    }
    if (!strcmp(local, "buttons.BTN_STYLUS.keys")) {
        if (ncodes > 8) return -1;
        if (inn) {
            prof->in_ready = 1;
            for (int i = 0; i < ncodes; i++) prof->in_stylus_keys[i] = codes[i];
            prof->in_stylus_nkeys = ncodes;
        } else {
            for (int i = 0; i < ncodes; i++) prof->stylus_keys[i] = codes[i];
            prof->stylus_nkeys = ncodes;
        }
        return 0;
    }
    if (!strcmp(local, "buttons.BTN_TOOL_RUBBER.keys")) {
        if (ncodes > 8) return -1;
        if (inn) {
            prof->in_ready = 1;
            for (int i = 0; i < ncodes; i++) prof->in_rubber_keys[i] = codes[i];
            prof->in_rubber_nkeys = ncodes;
        } else {
            for (int i = 0; i < ncodes; i++) prof->rubber_keys[i] = codes[i];
            prof->rubber_nkeys = ncodes;
        }
        return 0;
    }
    return 0;
}

static int parse_value(const char **pp, const char *path, struct profile *prof);

static int parse_array(const char **pp, const char *path, struct profile *prof) {
    const char *p = skip_ws(*pp);
    if (*p != '[') return -1;
    p++;
    const char *local;
    feel_path(path, &local);
    int is_curve = !strcmp(local, "pressure.curve");
    int is_rect = !strcmp(local, "mapping.rect") || !strcmp(local, "mapping.screen");
    int is_keys = !strcmp(local, "buttons.BTN_STYLUS.keys") || !strcmp(local, "buttons.BTN_TOOL_RUBBER.keys");
    double nums[8];
    int codes[8];
    int n = 0;
    int ncodes = 0;
    p = skip_ws(p);
    if (*p != ']') {
        while (1) {
            p = skip_ws(p);
            if (is_keys) {
                char name[32];
                if (ncodes >= 8) return -1;
                if (scan_string(&p, name, sizeof name) < 0) return -1;
                if (key_code(name, &codes[ncodes]) < 0) return -1;
                ncodes++;
            } else if (is_curve || is_rect) {
                char *end = NULL;
                if (n >= 8) return -1;
                nums[n] = strtod(p, &end);
                if (end == p) return -1;
                p = end;
                n++;
            } else if (skip_value(&p, 0) < 0) {
                return -1;
            }
            p = skip_ws(p);
            if (*p == ',') {
                p++;
                continue;
            }
            if (*p == ']') break;
            return -1;
        }
    }
    if (*p != ']') return -1;
    p++;
    if (assign_array(path, nums, n, codes, ncodes, prof) < 0) return -1;
    *pp = p;
    return 0;
}

static int parse_object(const char **pp, const char *path, struct profile *prof) {
    const char *p = skip_ws(*pp);
    if (*p != '{') return -1;
    p++;
    p = skip_ws(p);
    if (*p == '}') {
        *pp = p + 1;
        return 0;
    }
    while (1) {
        char key[64];
        char child[96];
        p = skip_ws(p);
        if (scan_string(&p, key, sizeof key) < 0) return -1;
        p = skip_ws(p);
        if (*p != ':') return -1;
        p++;
        int wrote;
        if (path[0]) wrote = snprintf(child, sizeof child, "%s.%s", path, key);
        else wrote = snprintf(child, sizeof child, "%s", key);
        if (wrote < 0 || wrote >= (int)sizeof child) return -1;
        if (parse_value(&p, child, prof) < 0) return -1;
        p = skip_ws(p);
        if (*p == ',') {
            p++;
            continue;
        }
        if (*p == '}') {
            *pp = p + 1;
            return 0;
        }
        return -1;
    }
}

static int parse_value(const char **pp, const char *path, struct profile *prof) {
    const char *p = skip_ws(*pp);
    if (*p == '{') return parse_object(pp, path, prof);
    if (*p == '[') return parse_array(pp, path, prof);
    if (*p == '"') {
        char text[64];
        if (scan_string(&p, text, sizeof text) < 0) return -1;
        if (apply_string(path, text, prof) < 0) return -1;
        *pp = p;
        return 0;
    }
    if (!strncmp(p, "true", 4)) {
        if (!strcmp(path, "tools.eraserWins")) prof->eraser_wins = 1;
        if (!strcmp(path, "internal.tools.eraserWins")) {
            prof->in_ready = 1;
            prof->in_eraser_wins = 1;
        }
        if (!strcmp(path, "internal.enabled")) prof->internal_on = 1;
        *pp = p + 4;
        return 0;
    }
    if (!strncmp(p, "false", 5)) {
        if (!strcmp(path, "tools.eraserWins")) prof->eraser_wins = 0;
        if (!strcmp(path, "internal.tools.eraserWins")) {
            prof->in_ready = 1;
            prof->in_eraser_wins = 0;
        }
        if (!strcmp(path, "internal.enabled")) prof->internal_on = 0;
        *pp = p + 5;
        return 0;
    }
    if (!strncmp(p, "null", 4)) return -1;
    char *end = NULL;
    double v = strtod(p, &end);
    if (end == p) return -1;
    const char *local;
    int inn = feel_path(path, &local);
    if (!strcmp(local, "pressure.deadzone") || !strcmp(local, "pressure.ceiling")) {
        if (v < 0 || v > 1) return -1;
        if (inn) {
            prof->in_ready = 1;
            if (!strcmp(local, "pressure.ceiling")) prof->in_ceiling = v;
            else prof->in_deadzone = v;
        } else if (!strcmp(local, "pressure.ceiling")) prof->ceiling = v;
        else prof->deadzone = v;
    }
    if (!strcmp(path, "mapping.rotation")) {
        int q = (int)(v >= 0 ? v + 0.5 : v - 0.5);
        if (q == 90) q = 1;
        else if (q == 180) q = 2;
        else if (q == 270) q = 3;
        if (q < 0 || q > 3) return -1;
        prof->rotation = q;
    }
    *pp = end;
    return 0;
}

static int parse_config(const char *text, struct profile *prof) {
    const char *p = text;
    if ((unsigned char)p[0] == 0xEF && (unsigned char)p[1] == 0xBB && (unsigned char)p[2] == 0xBF) p += 3;
    if (parse_value(&p, "", prof) < 0) return -1;
    p = skip_ws(p);
    if (*p) return -1;
    return 0;
}

static double bezier(double s, double p0, double p1, double p2, double p3) {
    double t = 1.0 - s;
    return t * t * t * p0 + 3 * t * t * s * p1 + 3 * t * s * s * p2 + s * s * s * p3;
}

static int pressure_out(const struct profile *p, int raw, int pmin, int pmax) {
    if (!p || pmax <= pmin) return raw;
    double span = (double)(pmax - pmin);
    double t = ((double)raw - (double)pmin) / span;
    if (t < 0) t = 0;
    if (t > 1) t = 1;
    double lo = p->deadzone;
    if (lo < 0) lo = 0;
    if (lo > 1) lo = 1;
    double hi = p->ceiling;
    if (!(hi > lo && hi <= 1.0)) hi = 1.0;
    if (p->curve_n == 0 && lo <= 0 && hi >= 1.0) return raw;
    double y0 = p->curve_n ? p->curve[1] : 0;
    double y1 = p->curve_n ? p->curve[7] : 1;
    double y;
    if (t <= lo || hi <= lo) y = y0;
    else if (t >= hi) y = y1;
    else if (p->curve_n == 0) y = (t - lo) / (hi - lo);
    else {
        t = (t - lo) / (hi - lo);
        double a = 0;
        double b = 1;
        for (int i = 0; i < 24; i++) {
            double mid = (a + b) / 2.0;
            double x = bezier(mid, p->curve[0], p->curve[2], p->curve[4], p->curve[6]);
            if (x < t) a = mid;
            else b = mid;
        }
        y = bezier((a + b) / 2.0, p->curve[1], p->curve[3], p->curve[5], p->curve[7]);
    }
    if (y < 0) y = 0;
    if (y > 1) y = 1;
    int out = pmin + (int)(y * span + 0.5);
    if (out < pmin) return pmin;
    if (out > pmax) return pmax;
    return out;
}

/* Same rotation TouchInputMapper applies when orientationAware is set and the
   device itself is not rotated. W and H are max-min+1. Coordinates are 0..W-1. */
static void rotate_pt(int ori, int W, int H, double u, double v, double *ru, double *rv) {
    switch (ori & 3) {
    case 0:
        *ru = u;
        *rv = v;
        break;
    case 1:
        *ru = v;
        *rv = (double)(W - 1) - u;
        break;
    case 2:
        *ru = (double)(W - 1) - u;
        *rv = (double)(H - 1) - v;
        break;
    default:
        *ru = (double)(H - 1) - v;
        *rv = u;
        break;
    }
}

static void unrotate_pt(int ori, int W, int H, double ru, double rv, double *u, double *v) {
    switch (ori & 3) {
    case 0:
        *u = ru;
        *v = rv;
        break;
    case 1:
        *u = (double)(W - 1) - rv;
        *v = ru;
        break;
    case 2:
        *u = (double)(W - 1) - ru;
        *v = (double)(H - 1) - rv;
        break;
    default:
        *u = rv;
        *v = (double)(H - 1) - ru;
        break;
    }
}

static void rotated_size(int ori, int W, int H, int *rW, int *rH) {
    if ((ori & 1) == 0) {
        *rW = W;
        *rH = H;
    } else {
        *rW = H;
        *rH = W;
    }
}

static int clamp_round(double v, int lo, int hi) {
    if (v <= (double)lo) return lo;
    if (v >= (double)hi) return hi;
    if (v >= 0) return (int)(v + 0.5);
    return (int)(v - 0.5);
}

static void map_point(const struct profile *p, int xmin, int xmax, int ymin, int ymax, int x, int y,
                      int *ox, int *oy) {
    *ox = x;
    *oy = y;
    if (!p || p->mode == MODE_PASS || !p->have_display) return;
    if (xmax <= xmin || ymax <= ymin || p->disp_w <= 0 || p->disp_h <= 0) return;
    /* User rotation is in the tablet's own axes, before fit/stretch/rects.
       Output is still encoded in the unrotated ABS range, because that is
       what inputflinger scales. Screen rotation stays in disp_w/disp_h. */
    int W = xmax - xmin + 1;
    int H = ymax - ymin + 1;
    int rot = p->rotation & 3;
    int spaceW, spaceH;
    rotated_size(rot, W, H, &spaceW, &spaceH);
    if (spaceW <= 1 || spaceH <= 1) return;
    double ru, rv;
    rotate_pt(rot, W, H, (double)(x - xmin), (double)(y - ymin), &ru, &rv);
    int x0 = 0;
    int y0 = 0;
    int x1 = spaceW - 1;
    int y1 = spaceH - 1;
    if (p->has_rect) {
        x0 = p->rect[0];
        y0 = p->rect[1];
        x1 = p->rect[2];
        y1 = p->rect[3];
        if (x0 > x1) {
            int t = x0;
            x0 = x1;
            x1 = t;
        }
        if (y0 > y1) {
            int t = y0;
            y0 = y1;
            y1 = t;
        }
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        if (x1 > spaceW - 1) x1 = spaceW - 1;
        if (y1 > spaceH - 1) y1 = spaceH - 1;
        if (x1 < x0 || y1 < y0) return;
    }
    int cx = clamp_round(ru, x0, x1);
    int cy = clamp_round(rv, y0, y1);
    int rrw = x1 - x0 + 1;
    int rrh = y1 - y0 + 1;
    if (rrw <= 0 || rrh <= 0) return;
    double lru = (double)(cx - x0);
    double lrv = (double)(cy - y0);
    double dest_x = 0;
    double dest_y = 0;
    double dest_w = (double)p->disp_w;
    double dest_h = (double)p->disp_h;
    if (p->has_screen) {
        double x0 = p->screen[0];
        double y0 = p->screen[1];
        double x1 = p->screen[2];
        double y1 = p->screen[3];
        if (x0 > x1) {
            double t = x0;
            x0 = x1;
            x1 = t;
        }
        if (y0 > y1) {
            double t = y0;
            y0 = y1;
            y1 = t;
        }
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        if (x1 > 1) x1 = 1;
        if (y1 > 1) y1 = 1;
        dest_x = x0 * (double)p->disp_w;
        dest_y = y0 * (double)p->disp_h;
        dest_w = (x1 - x0) * (double)p->disp_w;
        dest_h = (y1 - y0) * (double)p->disp_h;
        if (dest_w < 1 || dest_h < 1) return;
    }
    double sx, sy;
    if (p->mode == MODE_STRETCH) {
        sx = dest_x + lru / (double)rrw * dest_w;
        sy = dest_y + lrv / (double)rrh * dest_h;
    } else {
        double xscale = dest_w / (double)rrw;
        double yscale = dest_h / (double)rrh;
        double fs = xscale > yscale ? xscale : yscale;
        if (fs <= 0) return;
        double vis_w = dest_w / fs;
        double vis_h = dest_h / fs;
        double origin_x = 0;
        double origin_y = 0;
        int x_over = (double)rrw - vis_w > 1.0;
        int y_over = (double)rrh - vis_h > 1.0;
        /* left/right/top/bottom name the side that stays. The other axis stays centered. */
        if (x_over) {
            if (p->crop == CROP_RIGHT) origin_x = (double)rrw - vis_w;
            else if (p->crop != CROP_LEFT) origin_x = ((double)rrw - vis_w) / 2.0;
        }
        if (y_over) {
            if (p->crop == CROP_BOTTOM) origin_y = (double)rrh - vis_h;
            else if (p->crop != CROP_TOP) origin_y = ((double)rrh - vis_h) / 2.0;
        }
        sx = dest_x + (lru - origin_x) * fs;
        sy = dest_y + (lrv - origin_y) * fs;
    }
    int rotW, rotH;
    rotated_size(0, W, H, &rotW, &rotH);
    if (rotW <= 0 || rotH <= 0) return;
    double xScale = (double)p->disp_w / (double)rotW;
    double yScale = (double)p->disp_h / (double)rotH;
    double s = xScale > yScale ? xScale : yScale;
    if (s <= 0) return;
    double u, v;
    unrotate_pt(0, W, H, sx / s, sy / s, &u, &v);
    *ox = clamp_round((double)xmin + u, xmin, xmax);
    *oy = clamp_round((double)ymin + v, ymin, ymax);
}

static int fill_shortcut(const int *keys, int nkeys, int down, struct input_event *out, int cap) {
    if (nkeys <= 0 || nkeys + 1 > cap) return -1;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    int count = 0;
    if (down) {
        for (int i = 0; i < nkeys; i++) {
            memset(&out[count], 0, sizeof out[count]);
            out[count].time.tv_sec = ts.tv_sec;
            out[count].time.tv_usec = (suseconds_t)(ts.tv_nsec / 1000);
            out[count].type = EV_KEY;
            out[count].code = (unsigned short)keys[i];
            out[count].value = 1;
            count++;
        }
    } else {
        for (int i = nkeys - 1; i >= 0; i--) {
            memset(&out[count], 0, sizeof out[count]);
            out[count].time.tv_sec = ts.tv_sec;
            out[count].time.tv_usec = (suseconds_t)(ts.tv_nsec / 1000);
            out[count].type = EV_KEY;
            out[count].code = (unsigned short)keys[i];
            out[count].value = 0;
            count++;
        }
    }
    memset(&out[count], 0, sizeof out[count]);
    out[count].time.tv_sec = ts.tv_sec;
    out[count].time.tv_usec = (suseconds_t)(ts.tv_nsec / 1000);
    out[count].type = EV_SYN;
    out[count].code = SYN_REPORT;
    count++;
    return count;
}

struct invert_map {
    int report_id;
    int invert_bit;
    int inrange_bit;
};

/* Walk a HID report descriptor and find Digitizer Invert (0x000d003c).
   Bit 0 is the least significant bit of the first data byte, after the report id. */
static int parse_invert_map(const unsigned char *desc, int len, struct invert_map *out) {
    memset(out, 0, sizeof *out);
    out->invert_bit = -1;
    out->inrange_bit = -1;
    unsigned page = 0, size = 0, count = 0, report_id = 0;
    int have_id = 0;
    int bit = 0;
    int range_here = -1;
    unsigned usages[64];
    int nusage = 0;
    struct {
        unsigned page, size, count, id;
        int have_id;
    } stack[8];
    int sp = 0;
    int pos = 0;
    while (pos < len) {
        unsigned char b = desc[pos++];
        unsigned type, tag, val = 0;
        int item_size;
        if (b == 0xfe) {
            if (pos + 2 > len) return -1;
            item_size = desc[pos++];
            pos++;
            if (pos + item_size > len) return -1;
            pos += item_size;
            continue;
        }
        type = (b >> 2) & 3;
        tag = (b >> 4) & 15;
        item_size = b & 3;
        if (item_size == 3) item_size = 4;
        if (pos + item_size > len) return -1;
        for (int i = 0; i < item_size; i++) val |= ((unsigned)desc[pos++]) << (8 * i);
        if (type == 1) {
            if (tag == 0) page = val;
            else if (tag == 7) size = val;
            else if (tag == 8) {
                if (!have_id || report_id != val) {
                    bit = 0;
                    range_here = -1;
                }
                report_id = val;
                have_id = 1;
            } else if (tag == 9) count = val;
            else if (tag == 10 && sp < 8) {
                stack[sp].page = page;
                stack[sp].size = size;
                stack[sp].count = count;
                stack[sp].id = report_id;
                stack[sp].have_id = have_id;
                sp++;
            } else if (tag == 11 && sp > 0) {
                sp--;
                page = stack[sp].page;
                size = stack[sp].size;
                count = stack[sp].count;
                report_id = stack[sp].id;
                have_id = stack[sp].have_id;
            }
            continue;
        }
        if (type == 2 && tag == 0 && nusage < 64) {
            if (item_size == 4) usages[nusage++] = val;
            else usages[nusage++] = (page << 16) | (val & 0xffff);
            continue;
        }
        if (type != 0) continue;
        if (tag == 8 && size > 0 && count > 0 && bit < 4096) {
            int constant = val & 1;
            if (constant || size != 1) {
                bit += (int)(size * count);
            } else {
                int found_invert = -1;
                int found_range = -1;
                for (unsigned i = 0; i < count && bit < 4096; i++) {
                    unsigned u = 0;
                    if (nusage == 1) u = usages[0];
                    else if (nusage > 0) u = usages[i < (unsigned)nusage ? i : (unsigned)nusage - 1];
                    if (u == 0x000d003c && found_invert < 0) found_invert = bit;
                    if (u == 0x000d0032 && found_range < 0) found_range = bit;
                    bit++;
                }
                if (found_range >= 0) range_here = found_range;
                if (out->invert_bit < 0 && found_invert >= 0) {
                    out->report_id = (int)report_id;
                    out->invert_bit = found_invert;
                    out->inrange_bit = found_range >= 0 ? found_range : range_here;
                }
            }
        }
        nusage = 0;
    }
    return out->invert_bit < 0 ? -1 : 0;
}

/* Rising edge of Invert while the pen stays in range. Leaving range clears it
   so the next entry can click again, without counting the exit itself. */
static int invert_rise(int *down, int in_range, int invert) {
    if (!in_range) invert = 0;
    int rise = invert && !*down;
    *down = invert;
    return rise;
}

#ifdef STYLUSCTRL_TEST

static int failures;

static void expect(const char *name, int got, int want) {
    if (got != want) {
        fprintf(stderr, "FAIL %s: got %d want %d\n", name, got, want);
        failures++;
    }
}

static struct input_event ev_key(int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = EV_KEY;
    ev.code = code;
    ev.value = value;
    return ev;
}

static struct input_event ev_abs(int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = EV_ABS;
    ev.code = code;
    ev.value = value;
    return ev;
}

static struct input_event ev_syn(void) {
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = EV_SYN;
    ev.code = SYN_REPORT;
    return ev;
}

static int count_key(const struct input_event *out, int n, int code, int value) {
    int count = 0;
    for (int i = 0; i < n; i++) {
        if (out[i].type == EV_KEY && out[i].code == code && out[i].value == value) count++;
    }
    return count;
}

static int find_abs(const struct input_event *out, int n, int code) {
    for (int i = 0; i < n; i++) {
        if (out[i].type == EV_ABS && out[i].code == code) return out[i].value;
    }
    return -999999;
}

static void test_pen_down(void) {
    struct tool_state st = {.axes = AXIS_ALL};
    struct input_event in[2] = {ev_key(BTN_TOOL_PEN, 1), ev_syn()};
    struct input_event out[8];
    int n = emit_report(&st, in, 2, out, 8, NULL);
    expect("pen down count", n, 2);
    expect("pen down key", count_key(out, n, BTN_TOOL_PEN, 1), 1);
    expect("pen down sent", st.sent_pen, 1);
}

static void test_rubber_hides_pen_in_same_report(void) {
    struct tool_state st = {.axes = AXIS_ALL};
    struct input_event in[6] = {
        ev_key(BTN_TOOL_PEN, 1), ev_key(BTN_TOOL_RUBBER, 1), ev_key(BTN_TOUCH, 1),
        ev_abs(ABS_X, 15200),   ev_abs(ABS_PRESSURE, 50),   ev_syn(),
    };
    struct input_event out[8];
    int n = emit_report(&st, in, 6, out, 8, NULL);
    expect("same report pen", count_key(out, n, BTN_TOOL_PEN, 1), 0);
    expect("same report pen up", count_key(out, n, BTN_TOOL_PEN, 0), 0);
    expect("same report rubber", count_key(out, n, BTN_TOOL_RUBBER, 1), 1);
    expect("same report x", find_abs(out, n, ABS_X), 15200);
    expect("same report pressure", find_abs(out, n, ABS_PRESSURE), 50);
    expect("same report phys pen", st.phys_pen, 1);
}

static void test_rubber_arrival_releases_sent_pen(void) {
    struct tool_state st = {.axes = AXIS_ALL, .phys_pen = 1, .sent_pen = 1};
    struct input_event in[2] = {ev_key(BTN_TOOL_RUBBER, 1), ev_syn()};
    struct input_event out[8];
    int n = emit_report(&st, in, 2, out, 8, NULL);
    expect("rubber hides existing", count_key(out, n, BTN_TOOL_PEN, 0), 1);
    expect("rubber key", count_key(out, n, BTN_TOOL_RUBBER, 1), 1);
}

static void test_rubber_release_restores_pen(void) {
    struct tool_state st = {.axes = AXIS_ALL, .phys_pen = 1, .phys_rubber = 1, .sent_pen = 0};
    struct input_event in[3] = {ev_key(BTN_TOOL_RUBBER, 0), ev_abs(ABS_TILT_X, -15), ev_syn()};
    struct input_event out[8];
    int n = emit_report(&st, in, 3, out, 8, NULL);
    expect("restore pen", count_key(out, n, BTN_TOOL_PEN, 1), 1);
    expect("restore tilt", find_abs(out, n, ABS_TILT_X), -15);
}

static void test_repeat_pen_is_not_duplicated(void) {
    struct tool_state st = {.axes = AXIS_ALL, .phys_pen = 1, .sent_pen = 1};
    struct input_event in[3] = {ev_key(BTN_TOOL_PEN, 1), ev_abs(ABS_Y, 10), ev_syn()};
    struct input_event out[8];
    int n = emit_report(&st, in, 3, out, 8, NULL);
    expect("no duplicate pen", count_key(out, n, BTN_TOOL_PEN, 1), 0);
    expect("y kept", find_abs(out, n, ABS_Y), 10);
}

static void test_stylus_hold_eraser(void) {
    struct profile p;
    memset(&p, 0, sizeof p);
    profile_reset(&p);
    p.stylus_action = STYLUS_ERASER;
    struct tool_state st = {.axes = AXIS_ALL, .phys_pen = 1, .sent_pen = 1};
    struct input_event down[2] = {ev_key(BTN_STYLUS, 1), ev_syn()};
    struct input_event out[8];
    int n = emit_report(&st, down, 2, out, 8, &p);
    expect("hold hides pen", count_key(out, n, BTN_TOOL_PEN, 0), 1);
    expect("hold shows rubber", count_key(out, n, BTN_TOOL_RUBBER, 1), 1);
    expect("hold swallows stylus", count_key(out, n, BTN_STYLUS, 1), 0);
    struct input_event up[2] = {ev_key(BTN_STYLUS, 0), ev_syn()};
    n = emit_report(&st, up, 2, out, 8, &p);
    expect("release restores pen", count_key(out, n, BTN_TOOL_PEN, 1), 1);
    expect("release clears rubber", count_key(out, n, BTN_TOOL_RUBBER, 0), 1);
    const char *text = "{\"buttons\":{\"BTN_STYLUS\":{\"action\":\"eraser\"}}}";
    expect("stylus eraser parse", parse_config(text, &p), 0);
    expect("stylus eraser act", p.stylus_action, STYLUS_ERASER);
}

static void test_stylus_survives_rubber(void) {
    struct tool_state st = {.axes = AXIS_ALL, .phys_pen = 1, .phys_rubber = 1, .sent_pen = 0};
    struct input_event in[4] = {ev_key(BTN_STYLUS, 1), ev_abs(ABS_DISTANCE, 9), ev_key(KEY_A, 1),
                                ev_syn()};
    struct input_event out[8];
    int n = emit_report(&st, in, 4, out, 8, NULL);
    expect("stylus kept", count_key(out, n, BTN_STYLUS, 1), 1);
    expect("distance dropped", find_abs(out, n, ABS_DISTANCE), -999999);
    expect("key dropped", count_key(out, n, KEY_A, 1), 0);
    expect("no pen while rubber", count_key(out, n, BTN_TOOL_PEN, 1), 0);
}

static void test_parse_roadmap(void) {
    const char *text =
        "{\n"
        "  \"match\": { \"vendor\": \"0531\", \"product\": \"0104\" },\n"
        "  \"mapping\": { \"mode\": \"fit\", \"crop\": \"center\" },\n"
        "  \"pressure\": { \"curve\": [0.0, 0.0, 0.4, 0.7, 1.0, 1.0], \"deadzone\": 0.0 },\n"
        "  \"buttons\": { \"BTN_STYLUS\": { \"action\": \"shortcut\", \"keys\": [\"CTRL\", \"Z\"] } },\n"
        "  \"tools\": { \"eraserWins\": true },\n"
        "  \"note\": \"ignored\"\n"
        "}\n";
    struct profile p;
    memset(&p, 0, sizeof p);
    profile_reset(&p);
    expect("parse", parse_config(text, &p), 0);
    expect("mode", p.mode, MODE_FIT);
    expect("crop", p.crop, CROP_CENTER);
    expect("match", p.has_match, 1);
    expect("vendor", (int)p.vendor, 0x0531);
    expect("product", (int)p.product, 0x0104);
    expect("curve n", p.curve_n, 8);
    expect("curve mid x", (int)(p.curve[2] * 10), 4);
    expect("curve dup", p.curve[2] == p.curve[4] && p.curve[3] == p.curve[5], 1);
    expect("stylus act", p.stylus_action, STYLUS_SHORTCUT);
    expect("stylus n", p.stylus_nkeys, 2);
    expect("ctrl", p.stylus_keys[0], KEY_LEFTCTRL);
    expect("z", p.stylus_keys[1], KEY_Z);
    expect("rubber act", p.rubber_action, RUBBER_ERASER);
    expect("wins", p.eraser_wins, 1);
    expect("internal default", p.internal_on, 0);
    expect("internal parse", parse_config("{\"internal\":{\"enabled\":true}}", &p), 0);
    expect("internal on", p.internal_on, 1);
    expect("internal feel absent", p.in_ready, 0);
    expect("ceiling default", p.ceiling == 1.0, 1);
    expect("chord3", parse_config("{\"buttons\":{\"BTN_STYLUS\":{\"action\":\"shortcut\",\"keys\":[\"CTRL\",\"SHIFT\",\"Z\"]}}}", &p), 0);
    expect("chord n", p.stylus_nkeys, 3);
    expect("chord shift", p.stylus_keys[1], KEY_LEFTSHIFT);
    int space = 0;
    expect("space", key_code("SPACE", &space), 0);
    expect("space code", space, KEY_SPACE);
    expect("screen parse", parse_config("{\"mapping\":{\"screen\":[0.25,0.25,0.75,0.75]},\"pressure\":{\"ceiling\":0.8}}", &p), 0);
    expect("has screen", p.has_screen, 1);
    expect("ceiling set", p.ceiling == 0.8, 1);
    expect("rotation parse", parse_config("{\"mapping\":{\"rotation\":90}}", &p), 0);
    expect("rotation quarter", p.rotation, 1);
    expect("bad json", parse_config("{", &p), -1);
}

static void test_pressure_curve(void) {
    struct profile p;
    memset(&p, 0, sizeof p);
    profile_reset(&p);
    expect("identity", pressure_out(&p, 1234, 0, 4095), 1234);
    p.deadzone = 0.2;
    expect("deadzone low", pressure_out(&p, 819, 0, 4095), 0);
    expect("deadzone high", pressure_out(&p, 4095, 0, 4095), 4095);
    p.ceiling = 0.8;
    expect("ceiling low", pressure_out(&p, 409, 0, 4095), 0);
    expect("ceiling top", pressure_out(&p, 3686, 0, 4095), 4095);
    expect("ceiling mid", pressure_out(&p, 2048, 0, 4095), 2048);
    profile_reset(&p);
    const char *curve = "{\"pressure\":{\"curve\":[0.0,0.0,0.4,0.7,1.0,1.0]}}";
    expect("curve parse", parse_config(curve, &p), 0);
    expect("curve start", pressure_out(&p, 0, 0, 4095), 0);
    expect("curve end", pressure_out(&p, 4095, 0, 4095), 4095);
    int low = pressure_out(&p, 1000, 0, 4095);
    int high = pressure_out(&p, 3000, 0, 4095);
    expect("curve above line", low > 1000, 1);
    expect("curve rises", high > low, 1);
}

static void test_rotation_roundtrip(void) {
    int samples[][2] = {{0, 0}, {15200, 0}, {0, 9500}, {15200, 9500}, {4000, 2000}};
    for (int ori = 0; ori < 4; ori++) {
        for (int i = 0; i < 5; i++) {
            double ru, rv, u, v;
            rotate_pt(ori, 15201, 9501, samples[i][0], samples[i][1], &ru, &rv);
            unrotate_pt(ori, 15201, 9501, ru, rv, &u, &v);
            expect("round u", (int)(u + 0.5), samples[i][0]);
            expect("round v", (int)(v + 0.5), samples[i][1]);
        }
    }
}

static struct profile display_profile(int mode, int crop, int ori, int w, int h) {
    struct profile p;
    memset(&p, 0, sizeof p);
    profile_reset(&p);
    p.mode = mode;
    p.crop = crop;
    p.orientation = ori;
    p.disp_w = w;
    p.disp_h = h;
    p.have_display = 1;
    return p;
}

static void map_of(const struct profile *p, int x, int y, int *ox, int *oy) {
    map_point(p, 0, 15200, 0, 9500, x, y, ox, oy);
}

static void test_map_fit_and_stretch(void) {
    int ox, oy;
    struct profile keep_left = display_profile(MODE_FIT, CROP_LEFT, 0, 1200, 2000);
    map_of(&keep_left, 0, 100, &ox, &oy);
    expect("keep left origin x", ox, 0);
    map_of(&keep_left, 5700, 100, &ox, &oy);
    expect("keep left edge x", ox, 5700);

    struct profile keep_right = display_profile(MODE_FIT, CROP_RIGHT, 0, 1200, 2000);
    map_of(&keep_right, 9500, 100, &ox, &oy);
    expect("keep right start x", ox, 0);
    map_of(&keep_right, 15200, 100, &ox, &oy);
    expect("keep right end x", ox, 5700);

    struct profile center = display_profile(MODE_FIT, CROP_CENTER, 0, 1200, 2000);
    map_of(&center, 4750, 100, &ox, &oy);
    expect("fit center start", ox, 0);
    map_of(&center, 10450, 100, &ox, &oy);
    expect("fit center end", ox, 5700);

    struct profile stretch = display_profile(MODE_STRETCH, CROP_CENTER, 0, 1200, 2000);
    map_of(&stretch, 0, 0, &ox, &oy);
    expect("stretch origin", ox, 0);
    expect("stretch origin y", oy, 0);
    map_of(&stretch, 15200, 9500, &ox, &oy);
    expect("stretch far x", ox, 5700);
    expect("stretch far y", oy, 9500);

    struct profile land = display_profile(MODE_FIT, CROP_CENTER, 3, 2000, 1200);
    struct profile land0 = display_profile(MODE_FIT, CROP_CENTER, 0, 2000, 1200);
    int ox0, oy0;
    map_of(&land, 0, 190, &ox, &oy);
    map_of(&land0, 0, 190, &ox0, &oy0);
    expect("land ignores rotation x", ox, ox0);
    expect("land ignores rotation y", oy, oy0);
    expect("land left x", ox, 0);
    expect("land top band y", oy, 0);
    map_of(&land, 15200, 9310, &ox, &oy);
    expect("land right x", ox, 15200);
    expect("land bottom band y", oy, 9120);
    struct profile bottom = display_profile(MODE_FIT, CROP_BOTTOM, 3, 2000, 1200);
    map_of(&bottom, 15200, 380, &ox, &oy);
    expect("land bottom start y", oy, 0);
    map_of(&bottom, 15200, 9500, &ox, &oy);
    expect("land bottom end x", ox, 15200);
    expect("land bottom end y", oy, 9120);

    struct profile screen = display_profile(MODE_STRETCH, CROP_CENTER, 0, 2000, 1200);
    screen.has_screen = 1;
    screen.screen[0] = 0.25;
    screen.screen[1] = 0.25;
    screen.screen[2] = 0.75;
    screen.screen[3] = 0.75;
    map_of(&screen, 0, 0, &ox, &oy);
    expect("screen rect origin x", ox, 3800);
    expect("screen rect origin y", oy, 2280);
    map_of(&screen, 15200, 9500, &ox, &oy);
    expect("screen rect far x", ox, 11400);
    expect("screen rect far y", oy, 6840);

    struct profile spin = display_profile(MODE_STRETCH, CROP_CENTER, 0, 2000, 1200);
    spin.rotation = 1;
    map_of(&spin, 15200, 0, &ox, &oy);
    expect("rot90 origin x", ox, 0);
    expect("rot90 origin y", oy, 0);
    map_of(&spin, 0, 0, &ox, &oy);
    expect("rot90 other x", ox, 0);
    expect("rot90 other y", oy, 9120);
    spin.rotation = 2;
    map_of(&spin, 15200, 9500, &ox, &oy);
    expect("rot180 far x", ox, 0);
    expect("rot180 far y", oy, 0);
    spin.rotation = 1;
    spin.has_rect = 1;
    spin.rect[0] = 0;
    spin.rect[1] = 0;
    spin.rect[2] = 9500;
    spin.rect[3] = 15200;
    map_of(&spin, 15200, 0, &ox, &oy);
    expect("rot90 rect still origin x", ox, 0);
    expect("rot90 rect still origin y", oy, 0);

    struct profile rect = display_profile(MODE_STRETCH, CROP_CENTER, 0, 1200, 2000);
    rect.has_rect = 1;
    rect.rect[0] = 7600;
    rect.rect[1] = 0;
    rect.rect[2] = 15200;
    rect.rect[3] = 9500;
    map_of(&rect, 7600, 0, &ox, &oy);
    expect("rect start", ox, 0);
    map_of(&rect, 15200, 9500, &ox, &oy);
    expect("rect end x", ox, 5700);
    map_of(&rect, 0, 0, &ox, &oy);
    expect("rect clamp", ox, 0);
}

static void test_button_actions(void) {
    struct profile p;
    memset(&p, 0, sizeof p);
    profile_reset(&p);
    p.rubber_action = RUBBER_STYLUS2;
    struct tool_state st = {.axes = AXIS_ALL, .phys_pen = 1, .sent_pen = 1};
    struct input_event in[3] = {ev_key(BTN_TOOL_RUBBER, 1), ev_abs(ABS_X, 10), ev_syn()};
    struct input_event out[8];
    int n = emit_report(&st, in, 3, out, 8, &p);
    expect("stylus2 down", count_key(out, n, BTN_STYLUS2, 1), 1);
    expect("no rubber", count_key(out, n, BTN_TOOL_RUBBER, 1), 0);
    expect("pen stays", count_key(out, n, BTN_TOOL_PEN, 0), 0);
    expect("x kept", find_abs(out, n, ABS_X), 10);

    profile_reset(&p);
    p.stylus_action = STYLUS_SHORTCUT;
    p.stylus_nkeys = 2;
    p.stylus_keys[0] = KEY_LEFTCTRL;
    p.stylus_keys[1] = KEY_Z;
    struct tool_state st2 = {.axes = AXIS_ALL};
    struct input_event in2[3] = {ev_key(BTN_STYLUS, 1), ev_abs(ABS_PRESSURE, 40), ev_syn()};
    n = emit_report(&st2, in2, 3, out, 8, &p);
    expect("shortcut drops stylus", count_key(out, n, BTN_STYLUS, 1), 0);
    expect("pressure kept", find_abs(out, n, ABS_PRESSURE), 40);
    struct input_event keys[4];
    int kn = fill_shortcut(p.stylus_keys, p.stylus_nkeys, 1, keys, 4);
    expect("chord len", kn, 3);
    expect("ctrl down", keys[0].code == KEY_LEFTCTRL && keys[0].value == 1, 1);
    expect("z down", keys[1].code == KEY_Z && keys[1].value == 1, 1);
    expect("chord syn", keys[2].type == EV_SYN, 1);
    kn = fill_shortcut(p.stylus_keys, p.stylus_nkeys, 0, keys, 4);
    expect("z up first", keys[0].code == KEY_Z && keys[0].value == 0, 1);
    expect("ctrl up last", keys[1].code == KEY_LEFTCTRL && keys[1].value == 0, 1);
    int code = 0;
    expect("f1 name", key_code("F1", &code), 0);
    expect("f1 code", code, KEY_F1);
    expect("f12 name", key_code("f12", &code), 0);
    expect("f12 code", code, KEY_F12);
    expect("back name", key_code("BACK", &code), 0);
    expect("back code", code, KEY_BACK);
    expect("recent name", key_code("RECENT", &code), 0);
    expect("recent code", code, KEY_SCALE);
    expect("not f13", key_code("F13", &code), -1);

    static const unsigned char desc[] = {
        0x05, 0x0d, 0x09, 0x20, 0xa1, 0x01, 0x85, 0x06, 0x09, 0x42, 0x09, 0x44, 0x09, 0x45, 0x09, 0x3c, 0x09,
        0x32, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x05, 0x81, 0x02, 0x09, 0x30, 0x75, 0x10, 0x95, 0x01,
        0x81, 0x02, 0xc0};
    struct invert_map map;
    expect("invert parse", parse_invert_map(desc, (int)sizeof desc, &map), 0);
    expect("invert report", map.report_id, 6);
    expect("invert bit", map.invert_bit, 3);
    expect("inrange bit", map.inrange_bit, 4);
    static const unsigned char padded[] = {
        0x85, 0x02, 0x75, 0x01, 0x95, 0x01, 0x81, 0x03, 0x05, 0x0d, 0x09, 0x3c, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02};
    expect("padded parse", parse_invert_map(padded, (int)sizeof padded, &map), 0);
    expect("padded bit", map.invert_bit, 1);
    expect("padded range", map.inrange_bit, -1);
    int down = 0;
    expect("hover idle", invert_rise(&down, 1, 0), 0);
    expect("second press", invert_rise(&down, 1, 1), 1);
    expect("still held", invert_rise(&down, 1, 1), 0);
    expect("release", invert_rise(&down, 1, 0), 0);
    expect("press again", invert_rise(&down, 1, 1), 1);
    expect("leave range", invert_rise(&down, 0, 1), 0);
    expect("down cleared", down, 0);
    expect("come back", invert_rise(&down, 1, 1), 1);

    p.rubber_action = RUBBER_STYLUS2;
    struct tool_state held = {
        .axes = AXIS_ALL, .phys_pen = 1, .sent_pen = 1, .sent_stylus2 = 1, .invert_known = 1, .invert_down = 0};
    struct input_event only_syn[1] = {ev_syn()};
    n = emit_report(&held, only_syn, 1, out, 8, &p);
    expect("stylus2 follows release", count_key(out, n, BTN_STYLUS2, 0), 1);
}

static int count_type(const struct input_event *out, int n, int type, int code, int value) {
    int count = 0;
    for (int i = 0; i < n; i++) {
        if (out[i].type == type && out[i].code == code && out[i].value == value) count++;
    }
    return count;
}

static void test_internal_feel(void) {
    struct profile p;
    memset(&p, 0, sizeof p);
    profile_reset(&p);
    const char *text =
        "{\"pressure\":{\"deadzone\":0.2,\"ceiling\":0.9},"
        "\"buttons\":{\"BTN_STYLUS\":{\"action\":\"shortcut\",\"keys\":[\"CTRL\",\"Z\"]}},"
        "\"internal\":{\"enabled\":true,\"pressure\":{\"deadzone\":0.5},"
        "\"buttons\":{\"BTN_STYLUS\":{\"action\":\"passthrough\"},"
        "\"BTN_TOOL_RUBBER\":{\"action\":\"shortcut\",\"keys\":[\"SPACE\"]}}}}";
    expect("internal parse", parse_config(text, &p), 0);
    expect("external dead", p.deadzone == 0.2, 1);
    expect("internal dead", p.in_deadzone == 0.5, 1);
    expect("external still shortcut", p.stylus_action, STYLUS_SHORTCUT);
    expect("internal stylus pass", p.in_stylus_action, STYLUS_PASS);
    expect("internal rubber shortcut", p.in_rubber_action, RUBBER_SHORTCUT);
    expect("internal space", p.in_rubber_keys[0], KEY_SPACE);
    expect("ready", p.in_ready, 1);
    struct profile view;
    view_for_pen(&p, 0, &view);
    expect("external view dead", view.deadzone == 0.2, 1);
    expect("external view shortcut", view.stylus_action, STYLUS_SHORTCUT);
    view_for_pen(&p, 1, &view);
    expect("internal view dead", view.deadzone == 0.5, 1);
    expect("internal view pass", view.stylus_action, STYLUS_PASS);
    expect("span ignored", parse_config("{\"mapping\":{\"span\":[100,200]}}", &p), 0);
}

static void test_builtin_pen(void) {
    expect("samsung name", is_builtin_pen("sec_e-pen", 0, 0, 0, 0), 1);
    expect("scarlet", is_builtin_pen("hid-over-i2c 2D1F:0036 Stylus", 1, 1, 0, BUS_I2C), 1);
    expect("scarlet sibling", is_builtin_pen("hid-over-i2c 2D1F:0036", 0, 1, 0, BUS_I2C), 0);
    expect("finger screen", is_builtin_pen("Elan Touchscreen", 1, 0, 1, BUS_I2C), 0);
    expect("own virtual", is_builtin_pen("StylusCtrl hid-over-i2c 2D1F:0036 Stylus", 1, 1, 0, BUS_I2C), 0);
    expect("usb direct", is_builtin_pen("Wacom Tablet", 1, 1, 0, BUS_USB), 0);
}

static void test_relay_dock(void) {
    struct tool_state st = {.axes = AXIS_ALL, .relay = 1};
    struct input_event sw;
    memset(&sw, 0, sizeof sw);
    sw.type = EV_SW;
    sw.code = SW_PEN_INSERTED;
    sw.value = 1;
    struct input_event in[5] = {ev_abs(ABS_DISTANCE, 40), ev_key(KEY_HOMEPAGE, 1), ev_key(KEY_SEC_EPEN_EXTRA, 1),
                                sw, ev_syn()};
    struct input_event out[8];
    int n = emit_report(&st, in, 5, out, 8, NULL);
    expect("relay distance", find_abs(out, n, ABS_DISTANCE), 40);
    expect("relay home", count_key(out, n, KEY_HOMEPAGE, 1), 1);
    expect("relay extra", count_key(out, n, KEY_SEC_EPEN_EXTRA, 1), 1);
    expect("relay dock", count_type(out, n, EV_SW, SW_PEN_INSERTED, 1), 1);
}

int main(void) {
    test_pen_down();
    test_rubber_hides_pen_in_same_report();
    test_rubber_arrival_releases_sent_pen();
    test_rubber_release_restores_pen();
    test_repeat_pen_is_not_duplicated();
    test_stylus_hold_eraser();
    test_stylus_survives_rubber();
    test_parse_roadmap();
    test_pressure_curve();
    test_rotation_roundtrip();
    test_map_fit_and_stretch();
    test_button_actions();
    test_internal_feel();
    test_relay_dock();
    test_builtin_pen();
    if (failures) {
        fprintf(stderr, "%d failed\n", failures);
        return 1;
    }
    printf("ok\n");
    return 0;
}

#else

static volatile sig_atomic_t stop_flag;
static volatile sig_atomic_t reload_flag;
static int verbose;
static struct profile cfg;
static int cfg_loaded;
static char config_path[256];
static time_t config_sec;
static long config_nsec;
static long config_check_ms;
/* No Magisk: the settings app writes here as its own uid. User 0 only. */
static const char APP_CONFIG[] = "/data/user/0/app.stylusctrl/files/config.json";
static int kbd_fd = -1;
static int held_stylus;
static int held_rubber;
static int held_stylus_in;
static int held_rubber_in;
static long display_ms;
static int display_inactive_logged;

static int bit_is_set(const unsigned long *bits, int bit) {
    int width = (int)(8 * sizeof(unsigned long));
    return (bits[bit / width] >> (bit % width)) & 1UL;
}

static void on_stop(int sig) {
    (void)sig;
    stop_flag = 1;
}

static void on_reload(int sig) {
    (void)sig;
    reload_flag = 1;
}

static void log_msg(const char *fmt, ...) {
    va_list ap;
    fputs("stylusctld: ", stderr);
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
}

static void log_errno(const char *what) {
    log_msg("%s: %s", what, strerror(errno));
}

struct axis_setup {
    int code;
    int present;
    struct input_absinfo info;
};

struct pen {
    int fd;
    int hid_fd;
    int hid_report;
    int invert_bit;
    int inrange_bit;
    int ufd;
    int drop;
    int n;
    int seen;
    int idc_on;
    int direct;
    int xmin, xmax, ymin, ymax, pmin, pmax;
    int last_x, last_y, last_pressure;
    int last_tilt_x, last_tilt_y;
    char path[64];
    char vname[UINPUT_MAX_NAME_SIZE];
    char idc_path[160];
    struct input_id id;
    struct axis_setup axes[6];
    struct tool_state tool;
    struct input_event pkt[MAX_PACKET];
};

static int write_all(int fd, const void *buf, size_t len) {
    const char *p = buf;
    while (len) {
        ssize_t n = write(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static int read_text(const char *path, char *buf, int cap) {
    buf[0] = 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    int n = 0;
    while (n < cap - 1) {
        ssize_t got = read(fd, buf + n, (size_t)(cap - 1 - n));
        if (got < 0) {
            if (errno == EINTR) continue;
            close(fd);
            return -1;
        }
        if (got == 0) break;
        n += (int)got;
    }
    close(fd);
    buf[n] = 0;
    return n;
}

static int create_uinput(const char *name, const struct input_id *id, const struct axis_setup *axes,
                         int naxes, int direct, int relay, int *ufd_out) {
    int evbits[] = {EV_KEY, EV_ABS, EV_SYN};
    int keys[] = {BTN_TOOL_PEN, BTN_TOOL_RUBBER, BTN_TOUCH, BTN_STYLUS, BTN_STYLUS2};
    struct uinput_setup setup;
    int ufd = open("/dev/uinput", O_WRONLY | O_CLOEXEC);
    if (ufd < 0) {
        log_errno("打开 /dev/uinput");
        return -1;
    }
    for (int i = 0; i < 3; i++) {
        if (ioctl(ufd, UI_SET_EVBIT, evbits[i]) < 0) goto fail;
    }
    if (ioctl(ufd, UI_SET_PROPBIT, direct ? INPUT_PROP_DIRECT : INPUT_PROP_POINTER) < 0) goto fail;
    for (int i = 0; i < 5; i++) {
        if (ioctl(ufd, UI_SET_KEYBIT, keys[i]) < 0) goto fail;
    }
    /* Grabbing sec_e-pen swallows the dock, the cover, and hover distance.
       Put those back on the virtual device unchanged. Other direct pens,
       such as Scarlet's i2c stylus, do not carry those events. */
    if (relay) {
        if (ioctl(ufd, UI_SET_EVBIT, EV_SW) < 0) goto fail;
        if (ioctl(ufd, UI_SET_KEYBIT, KEY_HOMEPAGE) < 0) goto fail;
        if (ioctl(ufd, UI_SET_KEYBIT, KEY_SEC_EPEN_EXTRA) < 0) goto fail;
        if (ioctl(ufd, UI_SET_SWBIT, SW_PEN_INSERTED) < 0) goto fail;
        if (ioctl(ufd, UI_SET_SWBIT, SW_MACHINE_COVER) < 0) goto fail;
    }
    for (int i = 0; i < naxes; i++) {
        if (!axes[i].present) continue;
        if (ioctl(ufd, UI_SET_ABSBIT, axes[i].code) < 0) goto fail;
    }

    memset(&setup, 0, sizeof setup);
    setup.id.bustype = BUS_USB;
    setup.id.vendor = id->vendor;
    setup.id.product = id->product;
    setup.id.version = id->version;
    snprintf(setup.name, sizeof setup.name, "%s", name);
    if (ioctl(ufd, UI_DEV_SETUP, &setup) < 0) goto fail;
    for (int i = 0; i < naxes; i++) {
        if (!axes[i].present) continue;
        struct uinput_abs_setup abs;
        memset(&abs, 0, sizeof abs);
        abs.code = (unsigned short)axes[i].code;
        abs.absinfo = axes[i].info;
        if (ioctl(ufd, UI_ABS_SETUP, &abs) < 0) goto fail;
    }
    if (ioctl(ufd, UI_DEV_CREATE, 0) < 0) goto fail;
    *ufd_out = ufd;
    return 0;
fail:
    log_errno("建立虚拟设备");
    close(ufd);
    return -1;
}

static int uinput_sysname(int ufd, char *out, int cap) {
    memset(out, 0, (size_t)cap);
    if (ioctl(ufd, UI_GET_SYSNAME(cap), out) < 0) return -1;
    out[cap - 1] = 0;
    return 0;
}

static void event_node(const char *sysname, char *out, int cap) {
    char dirpath[128];
    snprintf(dirpath, sizeof dirpath, "/sys/class/input/%s", sysname);
    DIR *dir = opendir(dirpath);
    if (!dir) {
        snprintf(out, (size_t)cap, "%s", sysname);
        return;
    }
    snprintf(out, (size_t)cap, "%s", sysname);
    struct dirent *ent;
    while ((ent = readdir(dir))) {
        size_t len = strlen(ent->d_name);
        if (len < 5 || len > 16 || strncmp(ent->d_name, "event", 5) != 0) continue;
        snprintf(out, (size_t)cap, "/dev/input/%.*s", 16, ent->d_name);
        break;
    }
    closedir(dir);
}

static int parse_bitmap_bit(const char *text, int bit) {
    unsigned long words[16];
    int nwords = 0;
    const char *p = text;
    while (*p && nwords < 16) {
        while (*p == ' ' || *p == '\n' || *p == '\t') p++;
        if (!*p) break;
        char *end = NULL;
        words[nwords++] = strtoul(p, &end, 16);
        if (end == p) break;
        p = end;
    }
    if (nwords == 0) return 0;
    int width = (int)(8 * sizeof(unsigned long));
    int low_index = bit / width;
    int token = nwords - 1 - low_index;
    if (token < 0 || token >= nwords) return 0;
    return (words[token] >> (bit % width)) & 1UL;
}

static int check_mode(void) {
    struct input_id id = {.bustype = BUS_USB, .vendor = 0x0531, .product = 0x0104, .version = 1};
    struct axis_setup axes[5] = {
        {ABS_X, 1, {.minimum = 0, .maximum = 15200, .resolution = 100}},
        {ABS_Y, 1, {.minimum = 0, .maximum = 9500, .resolution = 100}},
        {ABS_PRESSURE, 1, {.minimum = 0, .maximum = 4095}},
        {ABS_TILT_X, 1, {.minimum = -90, .maximum = 90, .resolution = 57}},
        {ABS_TILT_Y, 1, {.minimum = -90, .maximum = 90, .resolution = 57}},
    };
    int ufd = -1;
    if (create_uinput("StylusCtrl check", &id, axes, 5, 0, 0, &ufd) < 0) return 1;
    char sysname[64];
    if (uinput_sysname(ufd, sysname, sizeof sysname) < 0) {
        log_errno("UI_GET_SYSNAME");
        ioctl(ufd, UI_DEV_DESTROY);
        close(ufd);
        return 1;
    }
    char node[64];
    event_node(sysname, node, sizeof node);
    char path[160];
    char text[1024];
    int ok = 1;
    snprintf(path, sizeof path, "/sys/class/input/%s/name", sysname);
    if (read_text(path, text, sizeof text) < 0 || strncmp(text, "StylusCtrl check", 16) != 0) {
        log_msg("名字不对: %s", text);
        ok = 0;
    }
    snprintf(path, sizeof path, "/sys/class/input/%s/id/bustype", sysname);
    if (read_text(path, text, sizeof text) < 0 || strtoul(text, NULL, 16) != BUS_USB) {
        log_msg("bustype 不是 USB: %s", text);
        ok = 0;
    }
    snprintf(path, sizeof path, "/sys/class/input/%s/id/vendor", sysname);
    if (read_text(path, text, sizeof text) < 0 || strtoul(text, NULL, 16) != 0x0531) {
        log_msg("vendor 不是 0531: %s", text);
        ok = 0;
    }
    snprintf(path, sizeof path, "/sys/class/input/%s/properties", sysname);
    if (read_text(path, text, sizeof text) < 0 || !parse_bitmap_bit(text, INPUT_PROP_POINTER)) {
        log_msg("没有 INPUT_PROP_POINTER: %s", text);
        ok = 0;
    }
    snprintf(path, sizeof path, "/sys/class/input/%s/capabilities/abs", sysname);
    if (read_text(path, text, sizeof text) < 0 || !parse_bitmap_bit(text, ABS_X) ||
        !parse_bitmap_bit(text, ABS_Y) || !parse_bitmap_bit(text, ABS_PRESSURE) ||
        !parse_bitmap_bit(text, ABS_TILT_X) || !parse_bitmap_bit(text, ABS_TILT_Y) ||
        parse_bitmap_bit(text, ABS_DISTANCE)) {
        log_msg("轴不对: %s", text);
        ok = 0;
    }
    snprintf(path, sizeof path, "/sys/class/input/%s/capabilities/key", sysname);
    if (read_text(path, text, sizeof text) < 0 || !parse_bitmap_bit(text, BTN_TOOL_PEN) ||
        !parse_bitmap_bit(text, BTN_TOOL_RUBBER) || !parse_bitmap_bit(text, BTN_TOUCH) ||
        !parse_bitmap_bit(text, BTN_STYLUS)) {
        log_msg("键不对: %s", text);
        ok = 0;
    }
    ioctl(ufd, UI_DEV_DESTROY);
    close(ufd);
    ufd = -1;
    snprintf(path, sizeof path, "/sys/class/input/%s", sysname);
    for (int i = 0; i < 20; i++) {
        if (access(path, F_OK) != 0) break;
        usleep(50000);
    }
    if (access(path, F_OK) == 0) {
        log_msg("虚拟设备 %s 还在", sysname);
        ok = 0;
    }
    if (!ok) return 1;
    log_msg("check 通过，曾建立 %s（%s），退出后已消失", node, sysname);
    return 0;
}

static void close_pen(struct pen *pen) {
    if (pen->hid_fd >= 0) {
        close(pen->hid_fd);
        pen->hid_fd = -1;
        pen->tool.invert_known = 0;
    }
    if (pen->ufd >= 0) {
        ioctl(pen->ufd, UI_DEV_DESTROY);
        close(pen->ufd);
        pen->ufd = -1;
    }
    if (pen->fd >= 0) {
        /* Argument is a boolean, not a pointer. Zero releases the grab. */
        ioctl(pen->fd, EVIOCGRAB, 0);
        close(pen->fd);
        pen->fd = -1;
    }
}

static void log_tool(struct pen *pen, int prev_pen, int prev_rubber, int prev_touch, int prev_stylus) {
    if (!verbose) return;
    int show_pen = pen->tool.sent_pen;
    if (show_pen == prev_pen && pen->tool.phys_rubber == prev_rubber &&
        pen->tool.phys_touch == prev_touch && pen->tool.phys_stylus == prev_stylus) {
        return;
    }
    const char *tool = pen->tool.phys_rubber ? "eraser" : (show_pen ? "pen" : "none");
    log_msg("%s tool=%s touch=%s side=%s", pen->path, tool, pen->tool.phys_touch ? "down" : "up",
            pen->tool.phys_stylus ? "down" : "up");
}

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + (long)(ts.tv_nsec / 1000000L);
}

static const char *mode_word(int mode) {
    if (mode == MODE_STRETCH) return "拉伸铺满";
    if (mode == MODE_FIT) return "等比裁切";
    return "原样";
}

static const char *crop_word(int crop) {
    switch (crop) {
    case CROP_LEFT:
        return "靠左";
    case CROP_RIGHT:
        return "靠右";
    case CROP_TOP:
        return "靠上";
    case CROP_BOTTOM:
        return "靠下";
    default:
        return "居中";
    }
}

static const char *stylus_word(int act) {
    if (act == STYLUS_SHORTCUT) return "快捷键";
    if (act == STYLUS_ERASER) return "按住橡皮";
    return "原样";
}

static const char *rubber_word(int act) {
    if (act == RUBBER_SHORTCUT) return "快捷键";
    if (act == RUBBER_STYLUS2) return "BTN_STYLUS2";
    return "橡皮";
}

static void log_profile(void) {
    if (!cfg_loaded) {
        log_msg("没有配置文件 %s，坐标和压感原样转发", config_path);
        return;
    }
    log_msg("配置 %s，%s，侧键%s，橡皮位%s，死区 %.2f–%.2f", mode_word(cfg.mode), crop_word(cfg.crop),
            stylus_word(cfg.stylus_action),
            rubber_word(cfg.rubber_action), cfg.deadzone, cfg.ceiling > cfg.deadzone ? cfg.ceiling : 1.0);
    if (cfg.has_rect) log_msg("板内矩形 %d,%d-%d,%d", cfg.rect[0], cfg.rect[1], cfg.rect[2], cfg.rect[3]);
    if (cfg.has_screen)
        log_msg("屏幕矩形 %.3f,%.3f-%.3f,%.3f", cfg.screen[0], cfg.screen[1], cfg.screen[2], cfg.screen[3]);
    if (cfg.rotation) log_msg("数位板旋转 %d", cfg.rotation * 90);
    if (cfg.has_match) log_msg("只抓 %04x:%04x", cfg.vendor, cfg.product);
    if (cfg.curve_n) log_msg("压感曲线已加载");
    if (cfg.in_ready) {
        log_msg("内屏单独一份，侧键%s，橡皮位%s，死区 %.2f–%.2f", stylus_word(cfg.in_stylus_action),
                rubber_word(cfg.in_rubber_action),
                cfg.in_deadzone, cfg.in_ceiling > cfg.in_deadzone ? cfg.in_ceiling : 1.0);
    }
}

static char *read_file(const char *path, int *missing) {
    *missing = 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        if (errno == ENOENT) *missing = 1;
        return NULL;
    }
    char *buf = malloc(65536);
    if (!buf) {
        close(fd);
        return NULL;
    }
    int n = 0;
    while (n < 65535) {
        ssize_t got = read(fd, buf + n, (size_t)(65535 - n));
        if (got < 0) {
            if (errno == EINTR) continue;
            free(buf);
            close(fd);
            return NULL;
        }
        if (got == 0) break;
        n += (int)got;
    }
    close(fd);
    buf[n] = 0;
    return buf;
}

static void remember_config_time(void) {
    struct stat st;
    if (stat(config_path, &st) == 0) {
        config_sec = st.st_mtim.tv_sec;
        config_nsec = st.st_mtim.tv_nsec;
    } else {
        config_sec = 0;
        config_nsec = 0;
    }
}

static int config_time_changed(void) {
    struct stat st;
    if (stat(config_path, &st) != 0) return config_sec != 0 || config_nsec != 0;
    return st.st_mtim.tv_sec != config_sec || st.st_mtim.tv_nsec != config_nsec;
}

static int load_config(void) {
    int missing = 0;
    char *text = read_file(config_path, &missing);
    if (!text) {
        if (!missing) {
            log_errno(config_path);
            return -1;
        }
        int dw = cfg.disp_w;
        int dh = cfg.disp_h;
        int ori = cfg.orientation;
        int hd = cfg.have_display;
        profile_reset(&cfg);
        cfg.disp_w = dw;
        cfg.disp_h = dh;
        cfg.orientation = ori;
        cfg.have_display = hd;
        cfg_loaded = 0;
        log_profile();
        return 0;
    }
    struct profile next = cfg;
    profile_reset(&next);
    if (parse_config(text, &next) < 0) {
        free(text);
        log_msg("配置解析失败，沿用上一份：%s", config_path);
        return -1;
    }
    free(text);
    cfg = next;
    cfg_loaded = 1;
    log_profile();
    return 0;
}

static int refresh_display(void) {
    display_ms = now_ms();
    FILE *fp = popen("dumpsys display", "r");
    if (!fp) {
        log_errno("dumpsys display");
        return -1;
    }
    size_t cap = 192 * 1024;
    char *buf = malloc(cap);
    if (!buf) {
        pclose(fp);
        return -1;
    }
    size_t n = 0;
    while (n + 1 < cap) {
        size_t got = fread(buf + n, 1, cap - 1 - n, fp);
        n += got;
        if (got == 0) break;
    }
    buf[n] = 0;
    pclose(fp);
    char *best = NULL;
    int best_active = 0;
    for (char *p = buf; (p = strstr(p, "type=INTERNAL")) != NULL; p += 13) {
        char *end = strchr(p, '}');
        if (!end || end - p > 8000) continue;
        char saved = *end;
        *end = 0;
        int active = strstr(p, "isActive=true") != NULL;
        *end = saved;
        if (!best || (active && !best_active)) {
            best = p;
            best_active = active;
        }
        if (active) break;
    }
    if (!best) {
        free(buf);
        log_msg("dumpsys 里没有内屏");
        return -1;
    }
    if (!best_active) {
        free(buf);
        if (!display_inactive_logged) {
            display_inactive_logged = 1;
            log_msg("屏幕 viewport 不在活动状态，先不改尺寸");
        }
        return -1;
    }
    display_inactive_logged = 0;
    char *end = strchr(best, '}');
    size_t len = end ? (size_t)(end - best) : strlen(best);
    if (len > 8000) len = 8000;
    char saved = best[len];
    best[len] = 0;
    char *o = strstr(best, "orientation=");
    int dw = 0;
    int dh = 0;
    char *frame = strstr(best, "logicalFrame=Rect(");
    if (frame) {
        int x0, y0, x1, y1;
        if (sscanf(frame, "logicalFrame=Rect(%d, %d - %d, %d)", &x0, &y0, &x1, &y1) == 4) {
            dw = x1 - x0;
            dh = y1 - y0;
        }
    }
    if (dw <= 0 || dh <= 0) {
        char *w = strstr(best, "deviceWidth=");
        char *h = strstr(best, "deviceHeight=");
        if (w && h) {
            dw = atoi(w + 12);
            dh = atoi(h + 13);
        }
    }
    if (!o || dw <= 0 || dh <= 0) {
        best[len] = saved;
        free(buf);
        log_msg("读不到屏幕方向");
        return -1;
    }
    int ori = atoi(o + 12);
    best[len] = saved;
    free(buf);
    if (ori < 0 || ori > 3) {
        log_msg("屏幕尺寸不对");
        return -1;
    }
    int changed = !cfg.have_display || cfg.disp_w != dw || cfg.disp_h != dh || cfg.orientation != ori;
    cfg.have_display = 1;
    cfg.disp_w = dw;
    cfg.disp_h = dh;
    cfg.orientation = ori;
    if (changed) log_msg("屏幕 %dx%d，旋转 %d", dw, dh, ori);
    return 0;
}

static int need_display(void) {
    return cfg_loaded && (cfg.mode == MODE_STRETCH || cfg.mode == MODE_FIT);
}

static void idc_path_for(const char *vname, char *out, int cap) {
    char canon[96];
    int j = 0;
    for (const char *s = vname; *s && j < (int)sizeof canon - 1; s++) {
        unsigned char c = (unsigned char)*s;
        int ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' ||
                 c == '_';
        canon[j++] = ok ? (char)c : '_';
    }
    canon[j] = 0;
    int wrote = snprintf(out, (size_t)cap, "/data/system/devices/idc/%s.idc", canon);
    if (wrote < 0 || wrote >= cap) out[0] = 0;
}

static void sync_idc(struct pen *pen) {
    idc_path_for(pen->vname, pen->idc_path, (int)sizeof pen->idc_path);
    if (!pen->idc_path[0]) return;
    /* POINTER must stay orientationAware false. A name IDC with that flag
       swaps the raw axes and crops a landscape screen down to 12:20. */
    if (unlink(pen->idc_path) == 0) log_msg("去掉 IDC %s", pen->idc_path);
    else if (errno != ENOENT) log_errno(pen->idc_path);
}

static int open_virtual(struct pen *pen) {
    sync_idc(pen);
    int ufd = -1;
    if (create_uinput(pen->vname, &pen->id, pen->axes, 6, pen->direct, pen->tool.relay, &ufd) < 0) return -1;
    if (pen->ufd >= 0) {
        ioctl(pen->ufd, UI_DEV_DESTROY);
        close(pen->ufd);
    }
    pen->ufd = ufd;
    pen->n = 0;
    pen->idc_on = cfg_loaded;
    return 0;
}

static void rotate_tilt(int rot, int tx, int ty, int *ox, int *oy) {
    switch (rot & 3) {
    case 1:
        *ox = ty;
        *oy = -tx;
        break;
    case 2:
        *ox = -tx;
        *oy = -ty;
        break;
    case 3:
        *ox = -ty;
        *oy = tx;
        break;
    default:
        *ox = tx;
        *oy = ty;
        break;
    }
}

static int apply_axes(struct pen *pen, const struct profile *prof, const struct input_event *in, int nin,
                      struct input_event *out, int nout, int cap) {
    int x = pen->last_x;
    int y = pen->last_y;
    int pressure = pen->last_pressure;
    int tilt_x = pen->last_tilt_x;
    int tilt_y = pen->last_tilt_y;
    int saw_tilt = 0;
    for (int i = 0; i < nin; i++) {
        if (in[i].type != EV_ABS) continue;
        if (in[i].code == ABS_X) x = in[i].value;
        else if (in[i].code == ABS_Y) y = in[i].value;
        else if (in[i].code == ABS_PRESSURE) pressure = in[i].value;
        else if (in[i].code == ABS_TILT_X) {
            tilt_x = in[i].value;
            saw_tilt = 1;
        } else if (in[i].code == ABS_TILT_Y) {
            tilt_y = in[i].value;
            saw_tilt = 1;
        }
    }
    pen->last_x = x;
    pen->last_y = y;
    pen->last_pressure = pressure;
    pen->last_tilt_x = tilt_x;
    pen->last_tilt_y = tilt_y;
    int mx = x;
    int my = y;
    /* The internal digitizer is already the panel. Keep its coordinates and
       only reshape pressure and buttons. */
    if (!pen->direct) map_point(prof, pen->xmin, pen->xmax, pen->ymin, pen->ymax, x, y, &mx, &my);
    int mp = pressure_out(prof, pressure, pen->pmin, pen->pmax);
    int mtx = tilt_x;
    int mty = tilt_y;
    if (!pen->direct && prof->rotation) rotate_tilt(prof->rotation, tilt_x, tilt_y, &mtx, &mty);
    int have_tx = 0;
    int have_ty = 0;
    for (int i = 0; i < nout; i++) {
        if (out[i].type != EV_ABS) continue;
        if (out[i].code == ABS_X) out[i].value = mx;
        else if (out[i].code == ABS_Y) out[i].value = my;
        else if (out[i].code == ABS_PRESSURE) out[i].value = mp;
        else if (out[i].code == ABS_TILT_X) {
            out[i].value = mtx;
            have_tx = 1;
        } else if (out[i].code == ABS_TILT_Y) {
            out[i].value = mty;
            have_ty = 1;
        }
    }
    if (saw_tilt && !pen->direct && prof->rotation && (!have_tx || !have_ty)) {
        int syn = -1;
        for (int i = 0; i < nout; i++) {
            if (out[i].type == EV_SYN && out[i].code == SYN_REPORT) syn = i;
        }
        if (syn >= 0) {
            if (!have_tx && nout < cap) {
                memmove(&out[syn + 1], &out[syn], (size_t)(nout - syn) * sizeof out[0]);
                out[syn] = out[syn + 1];
                out[syn].type = EV_ABS;
                out[syn].code = ABS_TILT_X;
                out[syn].value = mtx;
                nout++;
                syn++;
                have_tx = 1;
            }
            if (!have_ty && nout < cap) {
                memmove(&out[syn + 1], &out[syn], (size_t)(nout - syn) * sizeof out[0]);
                out[syn] = out[syn + 1];
                out[syn].type = EV_ABS;
                out[syn].code = ABS_TILT_Y;
                out[syn].value = mty;
                nout++;
            }
        }
    }
    return nout;
}

static void emit_shortcut(const int *keys, int nkeys, int down) {
    if (kbd_fd < 0 || nkeys <= 0) return;
    struct input_event evs[10];
    int n = fill_shortcut(keys, nkeys, down, evs, 10);
    if (n < 0) return;
    for (int i = 0; i < n; i++) {
        if (write_all(kbd_fd, &evs[i], sizeof evs[i]) < 0) {
            log_errno("写入快捷键");
            return;
        }
    }
}

/* One click, not a hold. A held key is repeated by the focused window after
   about half a second. The eraser tool stays down for the whole hover. */
static void emit_click(const int *keys, int nkeys) {
    emit_shortcut(keys, nkeys, 1);
    emit_shortcut(keys, nkeys, 0);
}

static void release_chord(int *held, const int *keys, int nkeys) {
    if (!*held) return;
    emit_shortcut(keys, nkeys, 0);
    *held = 0;
}

static void release_held(void) {
    release_chord(&held_stylus, cfg.stylus_keys, cfg.stylus_nkeys);
    release_chord(&held_rubber, cfg.rubber_keys, cfg.rubber_nkeys);
    release_chord(&held_stylus_in, cfg.in_stylus_keys, cfg.in_stylus_nkeys);
    release_chord(&held_rubber_in, cfg.in_rubber_keys, cfg.in_rubber_nkeys);
}

static int create_keyboard(const int *codes, int ncodes) {
    int ufd = open("/dev/uinput", O_WRONLY | O_CLOEXEC);
    if (ufd < 0) {
        log_errno("打开 /dev/uinput");
        return -1;
    }
    if (ioctl(ufd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(ufd, UI_SET_EVBIT, EV_SYN) < 0) goto fail;
    /* Only the keys this profile actually chords. A full alphabet is an
       alphabetic keyboard, and Android then refuses to show the soft keyboard. */
    for (int i = 0; i < ncodes; i++) {
        if (ioctl(ufd, UI_SET_KEYBIT, codes[i]) < 0) goto fail;
    }
    struct uinput_setup setup;
    memset(&setup, 0, sizeof setup);
    setup.id.bustype = BUS_USB;
    setup.id.vendor = 0x0531;
    setup.id.product = 0x0001;
    setup.id.version = 1;
    snprintf(setup.name, sizeof setup.name, "StylusCtrl keys");
    if (ioctl(ufd, UI_DEV_SETUP, &setup) < 0 || ioctl(ufd, UI_DEV_CREATE, 0) < 0) goto fail;
    kbd_fd = ufd;
    log_msg("快捷键设备 StylusCtrl keys");
    return 0;
fail:
    log_errno("建立快捷键设备");
    close(ufd);
    return -1;
}

static int kbd_codes[16];
static int kbd_ncodes;

static int wants_keyboard(void) {
    if (!cfg_loaded) return 0;
    if (cfg.stylus_action == STYLUS_SHORTCUT || cfg.rubber_action == RUBBER_SHORTCUT) return 1;
    if (cfg.internal_on && cfg.in_ready &&
        (cfg.in_stylus_action == STYLUS_SHORTCUT || cfg.in_rubber_action == RUBBER_SHORTCUT))
        return 1;
    return 0;
}

static int collect_bound_keys(int *out, int cap) {
    int n = 0;
    const int *lists[4];
    int counts[4];
    int nlists = 0;
    if (cfg.stylus_action == STYLUS_SHORTCUT && cfg.stylus_nkeys > 0) {
        lists[nlists] = cfg.stylus_keys;
        counts[nlists] = cfg.stylus_nkeys;
        nlists++;
    }
    if (cfg.rubber_action == RUBBER_SHORTCUT && cfg.rubber_nkeys > 0) {
        lists[nlists] = cfg.rubber_keys;
        counts[nlists] = cfg.rubber_nkeys;
        nlists++;
    }
    if (cfg.internal_on && cfg.in_ready && cfg.in_stylus_action == STYLUS_SHORTCUT && cfg.in_stylus_nkeys > 0) {
        lists[nlists] = cfg.in_stylus_keys;
        counts[nlists] = cfg.in_stylus_nkeys;
        nlists++;
    }
    if (cfg.internal_on && cfg.in_ready && cfg.in_rubber_action == RUBBER_SHORTCUT && cfg.in_rubber_nkeys > 0) {
        lists[nlists] = cfg.in_rubber_keys;
        counts[nlists] = cfg.in_rubber_nkeys;
        nlists++;
    }
    for (int i = 0; i < nlists; i++) {
        for (int k = 0; k < counts[i]; k++) {
            int code = lists[i][k];
            int seen = 0;
            for (int j = 0; j < n; j++) {
                if (out[j] == code) seen = 1;
            }
            if (seen) continue;
            if (n >= cap) return n;
            out[n++] = code;
        }
    }
    return n;
}

static int same_codes(const int *a, int na, const int *b, int nb) {
    if (na != nb) return 0;
    for (int i = 0; i < na; i++) {
        int found = 0;
        for (int j = 0; j < nb; j++) {
            if (a[i] == b[j]) found = 1;
        }
        if (!found) return 0;
    }
    return 1;
}

static void close_keyboard(void) {
    if (kbd_fd < 0) return;
    ioctl(kbd_fd, UI_DEV_DESTROY);
    close(kbd_fd);
    kbd_fd = -1;
    kbd_ncodes = 0;
}

static void sync_keyboard(void) {
    int next[16];
    int nnext = wants_keyboard() ? collect_bound_keys(next, 16) : 0;
    if (nnext == 0) {
        close_keyboard();
        return;
    }
    if (kbd_fd >= 0 && same_codes(kbd_codes, kbd_ncodes, next, nnext)) return;
    release_held();
    close_keyboard();
    if (create_keyboard(next, nnext) == 0) {
        memcpy(kbd_codes, next, (size_t)nnext * sizeof next[0]);
        kbd_ncodes = nnext;
    }
}

static void stamp_ev(struct input_event *ev, const struct timespec *ts, int type, int code, int value) {
    memset(ev, 0, sizeof *ev);
    ev->time.tv_sec = ts->tv_sec;
    ev->time.tv_usec = (suseconds_t)(ts->tv_nsec / 1000);
    ev->type = (unsigned short)type;
    ev->code = (unsigned short)code;
    ev->value = value;
}

static void resync_pen(struct pen *pen) {
    if (!pen->seen || pen->ufd < 0) return;
    int mx = pen->last_x;
    int my = pen->last_y;
    int mp = pen->last_pressure;
    struct profile view;
    int have_view = 0;
    if (cfg_loaded) {
        view_for_pen(&cfg, pen->direct, &view);
        have_view = 1;
        if (!pen->direct)
            map_point(&view, pen->xmin, pen->xmax, pen->ymin, pen->ymax, pen->last_x, pen->last_y, &mx, &my);
        mp = pressure_out(&view, pen->last_pressure, pen->pmin, pen->pmax);
    }
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    struct input_event evs[9];
    int n = 0;
    stamp_ev(&evs[n++], &ts, EV_ABS, ABS_X, mx);
    stamp_ev(&evs[n++], &ts, EV_ABS, ABS_Y, my);
    stamp_ev(&evs[n++], &ts, EV_ABS, ABS_PRESSURE, mp);
    int pen_down = pen->tool.sent_pen;
    int rubber_down = pen->tool.phys_rubber;
    int stamp_rubber = !have_view || view.rubber_action == RUBBER_ERASER;
    if (have_view && view.stylus_action == STYLUS_ERASER && pen->tool.phys_stylus) {
        pen_down = 0;
        rubber_down = 1;
        stamp_rubber = 1;
    }
    stamp_ev(&evs[n++], &ts, EV_KEY, BTN_TOOL_PEN, pen_down);
    pen->tool.sent_pen = pen_down;
    if (stamp_rubber) {
        stamp_ev(&evs[n++], &ts, EV_KEY, BTN_TOOL_RUBBER, rubber_down);
        pen->tool.sent_rubber = rubber_down;
    } else if (view.rubber_action == RUBBER_STYLUS2) {
        stamp_ev(&evs[n++], &ts, EV_KEY, BTN_STYLUS2, pen->tool.phys_rubber);
    }
    stamp_ev(&evs[n++], &ts, EV_KEY, BTN_TOUCH, pen->tool.phys_touch);
    if (!have_view || view.stylus_action == STYLUS_PASS) {
        stamp_ev(&evs[n++], &ts, EV_KEY, BTN_STYLUS, pen->tool.phys_stylus);
    }
    stamp_ev(&evs[n++], &ts, EV_SYN, SYN_REPORT, 0);
    for (int i = 0; i < n; i++) {
        if (write_all(pen->ufd, &evs[i], sizeof evs[i]) < 0) {
            log_errno("同步虚拟设备");
            return;
        }
    }
}

static void resync_shortcuts(struct pen *pens, int npens) {
    /* Shortcuts are clicks. Reloading must not press them again just because
       the eraser or the side button is still down. */
    (void)pens;
    (void)npens;
}

static void forward_packet(struct pen *pen) {
    int in_n = pen->n;
    int prev_pen = pen->tool.sent_pen;
    int prev_rubber = pen->tool.phys_rubber;
    int prev_touch = pen->tool.phys_touch;
    int prev_stylus = pen->tool.phys_stylus;
    struct input_event out[MAX_PACKET + 4];
    struct profile view;
    const struct profile *prof = NULL;
    if (cfg_loaded) {
        view_for_pen(&cfg, pen->direct, &view);
        prof = &view;
    }
    int n = emit_report(&pen->tool, pen->pkt, in_n, out, MAX_PACKET + 4, prof);
    if (n < 0) {
        pen->n = 0;
        log_msg("%s 报告写不下，丢掉这一帧", pen->path);
        return;
    }
    if (prof) n = apply_axes(pen, prof, pen->pkt, in_n, out, n, MAX_PACKET + 4);
    pen->n = 0;
    pen->seen = 1;
    if (pen->ufd < 0) return;
    for (int i = 0; i < n; i++) {
        if (write_all(pen->ufd, &out[i], sizeof out[i]) < 0) {
            log_errno("写入虚拟设备");
            close_pen(pen);
            return;
        }
    }
    if (prof && prof->stylus_action == STYLUS_SHORTCUT && !prev_stylus && pen->tool.phys_stylus)
        emit_click(prof->stylus_keys, prof->stylus_nkeys);
    /* When Invert is visible on hidraw, the evdev tool bit stays down for the
       whole hover and cannot show a second press. Clicks come from hidraw. */
    if (prof && prof->rubber_action == RUBBER_SHORTCUT && !pen->tool.invert_known && !prev_rubber &&
        pen->tool.phys_rubber)
        emit_click(prof->rubber_keys, prof->rubber_nkeys);
    log_tool(pen, prev_pen, prev_rubber, prev_touch, prev_stylus);
}

static void drain(struct pen *pen) {
    while (pen->fd >= 0) {
        struct input_event evs[32];
        ssize_t got = read(pen->fd, evs, sizeof evs);
        if (got < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) return;
            log_msg("%s 断开: %s", pen->path, strerror(errno));
            close_pen(pen);
            return;
        }
        if (got == 0) {
            log_msg("%s 断开", pen->path);
            close_pen(pen);
            return;
        }
        if (got % (ssize_t)sizeof(struct input_event)) {
            log_msg("%s 读到不完整事件", pen->path);
            close_pen(pen);
            return;
        }
        int count = (int)(got / (ssize_t)sizeof(struct input_event));
        for (int i = 0; i < count; i++) {
            struct input_event ev = evs[i];
            if (pen->drop) {
                if (ev.type == EV_SYN && ev.code == SYN_REPORT) pen->drop = 0;
                continue;
            }
            if (ev.type == EV_SYN && ev.code == SYN_DROPPED) {
                log_msg("%s SYN_DROPPED", pen->path);
                pen->n = 0;
                continue;
            }
            if (pen->n >= MAX_PACKET) {
                log_msg("%s 报告过长", pen->path);
                pen->n = 0;
                pen->drop = 1;
                continue;
            }
            pen->pkt[pen->n++] = ev;
            if (ev.type == EV_SYN && ev.code == SYN_REPORT) forward_packet(pen);
        }
    }
}

static int is_event_name(const char *name) {
    if (strncmp(name, "event", 5) != 0) return 0;
    if (!name[5]) return 0;
    for (const char *p = name + 5; *p; p++) {
        if (*p < '0' || *p > '9') return 0;
    }
    return 1;
}

static int already_open(struct pen *pens, int npens, const char *path) {
    for (int i = 0; i < npens; i++) {
        if (strcmp(pens[i].path, path) == 0) return 1;
    }
    return 0;
}

static int hid_bit(const unsigned char *data, int len, int bit) {
    if (bit < 0) return 0;
    int byte = bit / 8;
    if (byte < 0 || byte >= len) return 0;
    return (data[byte] >> (bit % 8)) & 1;
}

static void push_stylus2(struct pen *pen, int down) {
    if (pen->ufd < 0 || pen->tool.sent_stylus2 == down) return;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    struct input_event ev;
    stamp_ev(&ev, &ts, EV_KEY, BTN_STYLUS2, down);
    if (write_all(pen->ufd, &ev, sizeof ev) < 0) return;
    stamp_ev(&ev, &ts, EV_SYN, SYN_REPORT, 0);
    if (write_all(pen->ufd, &ev, sizeof ev) < 0) return;
    pen->tool.sent_stylus2 = down;
}

static void close_hid(struct pen *pen) {
    if (pen->hid_fd < 0) return;
    close(pen->hid_fd);
    pen->hid_fd = -1;
    pen->tool.invert_known = 0;
}

static void drain_hid(struct pen *pen) {
    unsigned char buf[256];
    while (pen->hid_fd >= 0) {
        ssize_t got = read(pen->hid_fd, buf, sizeof buf);
        if (got < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) return;
            log_msg("%s 的 hidraw 断开: %s", pen->path, strerror(errno));
            close_hid(pen);
            return;
        }
        if (got == 0) {
            close_hid(pen);
            return;
        }
        const unsigned char *data = buf;
        int dlen = (int)got;
        if (pen->hid_report) {
            if (buf[0] != (unsigned char)pen->hid_report) continue;
            data = buf + 1;
            dlen--;
        }
        int in_range = pen->inrange_bit < 0 ? 1 : hid_bit(data, dlen, pen->inrange_bit);
        int invert = hid_bit(data, dlen, pen->invert_bit);
        int rise = invert_rise(&pen->tool.invert_down, in_range, invert);
        if (!cfg_loaded) continue;
        struct profile view;
        view_for_pen(&cfg, pen->direct, &view);
        if (rise && view.rubber_action == RUBBER_SHORTCUT && view.rubber_nkeys > 0)
            emit_click(view.rubber_keys, view.rubber_nkeys);
        if (view.rubber_action == RUBBER_STYLUS2) push_stylus2(pen, pen->tool.invert_down);
    }
}

static int open_pen_hidraw(struct pen *pen) {
    const char *slash = strrchr(pen->path, '/');
    const char *name = slash ? slash + 1 : pen->path;
    const char *fmts[] = {"/sys/class/input/%s/device/device/hidraw", "/sys/class/input/%s/device/hidraw"};
    char dirpath[160];
    DIR *dir = NULL;
    for (int i = 0; i < 2 && !dir; i++) {
        snprintf(dirpath, sizeof dirpath, fmts[i], name);
        dir = opendir(dirpath);
    }
    if (!dir) return -1;
    char node[32] = {0};
    struct dirent *ent;
    while ((ent = readdir(dir))) {
        if (strncmp(ent->d_name, "hidraw", 6) != 0) continue;
        if (strlen(ent->d_name) >= sizeof node) continue;
        memcpy(node, ent->d_name, strlen(ent->d_name) + 1);
        break;
    }
    closedir(dir);
    if (!node[0]) return -1;
    char dev[64];
    snprintf(dev, sizeof dev, "/dev/%s", node);
    int fd = open(dev, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        log_msg("打开 %s 失败: %s", dev, strerror(errno));
        return -1;
    }
    struct hidraw_report_descriptor rdesc;
    memset(&rdesc, 0, sizeof rdesc);
    rdesc.size = HID_MAX_DESCRIPTOR_SIZE;
    if (ioctl(fd, HIDIOCGRDESC, &rdesc) < 0 || rdesc.size == 0 || rdesc.size > HID_MAX_DESCRIPTOR_SIZE) {
        log_msg("%s 读不到报告描述符: %s", dev, strerror(errno));
        close(fd);
        return -1;
    }
    struct invert_map map;
    if (parse_invert_map(rdesc.value, (int)rdesc.size, &map) != 0) {
        log_msg("%s 的描述符里没有 Invert", dev);
        close(fd);
        return -1;
    }
    pen->hid_fd = fd;
    pen->hid_report = map.report_id;
    pen->invert_bit = map.invert_bit;
    pen->inrange_bit = map.inrange_bit;
    pen->tool.invert_known = 1;
    pen->tool.invert_down = 0;
    log_msg("%s 第二侧键读 HID Invert，报告 %d，位 %d", pen->path, map.report_id, map.invert_bit);
    return 0;
}

static void try_add(struct pen *pens, int *npens, const char *path) {
    if (*npens >= MAX_PENS || already_open(pens, *npens, path)) return;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return;
    char source[80];
    memset(source, 0, sizeof source);
    if (ioctl(fd, EVIOCGNAME(sizeof source - 1), source) < 0) {
        close(fd);
        return;
    }
    if (strncmp(source, "StylusCtrl", 10) == 0) {
        close(fd);
        return;
    }
    unsigned long prop[(INPUT_PROP_MAX / (sizeof(long) * 8)) + 1];
    unsigned long keybits[(KEY_MAX / (sizeof(long) * 8)) + 1];
    unsigned long absbits[(ABS_MAX / (sizeof(long) * 8)) + 1];
    memset(prop, 0, sizeof prop);
    memset(keybits, 0, sizeof keybits);
    memset(absbits, 0, sizeof absbits);
    struct input_id id;
    memset(&id, 0, sizeof id);
    if (ioctl(fd, EVIOCGPROP(sizeof prop), prop) < 0 ||
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof keybits), keybits) < 0 ||
        ioctl(fd, EVIOCGBIT(EV_ABS, sizeof absbits), absbits) < 0 || ioctl(fd, EVIOCGID, &id) < 0) {
        close(fd);
        return;
    }
    int samsung = strcmp(source, "sec_e-pen") == 0;
    int builtin = is_builtin_pen(source, bit_is_set(prop, INPUT_PROP_DIRECT), bit_is_set(keybits, BTN_TOOL_PEN),
                                 bit_is_set(absbits, ABS_MT_SLOT), id.bustype);
    if (builtin) {
        if (!cfg_loaded || !cfg.internal_on) {
            close(fd);
            return;
        }
    } else if (!bit_is_set(prop, INPUT_PROP_POINTER) || bit_is_set(prop, INPUT_PROP_DIRECT)) {
        close(fd);
        return;
    }
    if (!bit_is_set(absbits, ABS_X) || !bit_is_set(absbits, ABS_Y) || !bit_is_set(absbits, ABS_PRESSURE)) {
        close(fd);
        return;
    }

    int codes[] = {ABS_X, ABS_Y, ABS_PRESSURE, ABS_TILT_X, ABS_TILT_Y, ABS_DISTANCE};
    struct axis_setup axes[6];
    memset(axes, 0, sizeof axes);
    unsigned mask = 0;
    for (int i = 0; i < 6; i++) {
        if (codes[i] == ABS_DISTANCE && !builtin) continue;
        axes[i].code = codes[i];
        if (!bit_is_set(absbits, codes[i])) continue;
        if (ioctl(fd, EVIOCGABS(codes[i]), &axes[i].info) < 0) {
            log_errno(path);
            close(fd);
            return;
        }
        axes[i].present = 1;
        mask |= axis_bit(codes[i]);
    }
    if (!builtin && cfg_loaded && cfg.has_match && (id.vendor != cfg.vendor || id.product != cfg.product)) {
        close(fd);
        return;
    }
    if (ioctl(fd, EVIOCGRAB, 1) < 0) {
        log_msg("独占 %s 失败: %s", path, strerror(errno));
        close(fd);
        return;
    }
    struct pen *pen = &pens[*npens];
    memset(pen, 0, sizeof *pen);
    pen->fd = fd;
    pen->hid_fd = -1;
    pen->ufd = -1;
    pen->direct = builtin;
    pen->tool.relay = samsung;
    pen->tool.axes = mask;
    memcpy(pen->axes, axes, sizeof axes);
    pen->id = id;
    pen->xmin = axes[0].info.minimum;
    pen->xmax = axes[0].info.maximum;
    pen->ymin = axes[1].info.minimum;
    pen->ymax = axes[1].info.maximum;
    pen->pmin = axes[2].info.minimum;
    pen->pmax = axes[2].info.maximum;
    pen->last_x = pen->xmin;
    pen->last_y = pen->ymin;
    pen->last_pressure = pen->pmin;
    snprintf(pen->path, sizeof pen->path, "%s", path);
    snprintf(pen->vname, sizeof pen->vname, "StylusCtrl %.*s", (int)sizeof pen->vname - 12, source);
    if (open_virtual(pen) < 0) {
        ioctl(fd, EVIOCGRAB, 0);
        close(fd);
        pen->fd = -1;
        return;
    }
    (*npens)++;
    if (!builtin) open_pen_hidraw(pen);
    char sysname[64];
    char node[64];
    if (uinput_sysname(pen->ufd, sysname, sizeof sysname) == 0) event_node(sysname, node, sizeof node);
    else snprintf(node, sizeof node, "uinput");
    log_msg("%s独占 %s「%s」，虚拟设备 %s，x %d-%d，y %d-%d，pressure %d-%d", builtin ? "内屏 " : "", path,
            source, node,
            axes[0].info.minimum, axes[0].info.maximum, axes[1].info.minimum, axes[1].info.maximum,
            axes[2].info.minimum, axes[2].info.maximum);
}

static void scan(struct pen *pens, int *npens) {
    DIR *dir = opendir("/dev/input");
    if (!dir) {
        log_errno("打开 /dev/input");
        return;
    }
    struct dirent *ent;
    while ((ent = readdir(dir))) {
        if (!is_event_name(ent->d_name) || strlen(ent->d_name) > 16) continue;
        char path[32];
        snprintf(path, sizeof path, "/dev/input/%.*s", 16, ent->d_name);
        try_add(pens, npens, path);
    }
    closedir(dir);
}

static void log_waiting(void) {
    if (cfg_loaded && cfg.has_match) log_msg("等待 %04x:%04x 的笔节点", cfg.vendor, cfg.product);
    else log_msg("等待带 INPUT_PROP_POINTER 和 ABS_PRESSURE 的笔节点");
}

static void reload_config(struct pen *pens, int *npens) {
    release_held();
    if (load_config() == 0) {
        sync_keyboard();
        if (need_display()) refresh_display();
        if (!cfg.internal_on) {
            for (int i = *npens - 1; i >= 0; i--) {
                if (!pens[i].direct || pens[i].fd < 0) continue;
                char path[64];
                snprintf(path, sizeof path, "%s", pens[i].path);
                close_pen(&pens[i]);
                log_msg("已放开 %s", path);
                pens[i] = pens[*npens - 1];
                (*npens)--;
            }
        }
        for (int i = 0; i < *npens; i++) {
            if (pens[i].fd < 0 || pens[i].idc_on == cfg_loaded) continue;
            if (open_virtual(&pens[i]) == 0) {
                resync_pen(&pens[i]);
                log_msg("已按配置重建 %s", pens[i].path);
            }
        }
        scan(pens, npens);
    }
    resync_shortcuts(pens, *npens);
}

static int open_input_watch(void) {
    int fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (fd < 0) {
        log_errno("inotify");
        return -1;
    }
    if (inotify_add_watch(fd, "/dev/input", IN_CREATE | IN_DELETE | IN_MOVED_TO | IN_MOVED_FROM) < 0) {
        log_errno("监听 /dev/input");
        close(fd);
        return -1;
    }
    log_msg("监听 /dev/input");
    return fd;
}

static void drain_watch(int fd) {
    char buf[4096];
    while (read(fd, buf, sizeof buf) > 0) {
    }
}

static int run_daemon(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_stop;
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    sa.sa_handler = on_reload;
    sigaction(SIGUSR1, &sa, NULL);

    profile_reset(&cfg);
    load_config();
    remember_config_time();
    sync_keyboard();

    struct pen pens[MAX_PENS];
    int npens = 0;
    int waiting = 0;
    int watch = open_input_watch();
    scan(pens, &npens);
    if (need_display()) refresh_display();
    if (npens == 0) {
        log_waiting();
        waiting = 1;
    }
    while (!stop_flag) {
        if (reload_flag) {
            reload_flag = 0;
            reload_config(pens, &npens);
        }
        struct pollfd pfds[MAX_PENS * 2 + 1];
        int nfd = 0;
        for (int i = 0; i < npens; i++) {
            pfds[nfd].fd = pens[i].fd;
            pfds[nfd].events = POLLIN;
            pfds[nfd].revents = 0;
            nfd++;
        }
        int hid_pen[MAX_PENS];
        int nhid = 0;
        for (int i = 0; i < npens; i++) {
            if (pens[i].hid_fd < 0) continue;
            hid_pen[nhid] = i;
            pfds[nfd].fd = pens[i].hid_fd;
            pfds[nfd].events = POLLIN;
            pfds[nfd].revents = 0;
            nfd++;
            nhid++;
        }
        int watch_index = -1;
        if (watch >= 0) {
            watch_index = nfd;
            pfds[nfd].fd = watch;
            pfds[nfd].events = POLLIN;
            pfds[nfd].revents = 0;
            nfd++;
        }
        int pr = poll(pfds, (nfds_t)nfd, 1000);
        if (pr < 0) {
            if (errno == EINTR) continue;
            log_errno("poll");
            break;
        }
        int saw_watch = 0;
        if (pr > 0) {
            for (int h = 0; h < nhid; h++) {
                int slot = npens + h;
                if (pfds[slot].revents & (POLLIN | POLLERR | POLLHUP)) drain_hid(&pens[hid_pen[h]]);
            }
            for (int i = npens - 1; i >= 0; i--) {
                if (pfds[i].revents & (POLLIN | POLLERR | POLLHUP)) drain(&pens[i]);
                if (pens[i].fd < 0) {
                    struct profile view;
                    view_for_pen(&cfg, pens[i].direct, &view);
                    if (pens[i].direct) {
                        if (held_stylus_in && pens[i].tool.phys_stylus)
                            release_chord(&held_stylus_in, view.stylus_keys, view.stylus_nkeys);
                        if (held_rubber_in && pens[i].tool.phys_rubber)
                            release_chord(&held_rubber_in, view.rubber_keys, view.rubber_nkeys);
                    } else {
                        if (held_stylus && pens[i].tool.phys_stylus)
                            release_chord(&held_stylus, view.stylus_keys, view.stylus_nkeys);
                        if (held_rubber && pens[i].tool.phys_rubber)
                            release_chord(&held_rubber, view.rubber_keys, view.rubber_nkeys);
                    }
                    log_msg("已放开 %s", pens[i].path);
                    pens[i] = pens[npens - 1];
                    npens--;
                }
            }
            if (watch_index >= 0 && (pfds[watch_index].revents & POLLIN)) {
                drain_watch(watch);
                saw_watch = 1;
            }
        }
        if (pr == 0 && npens > 0 && need_display() && now_ms() - display_ms >= 1000) refresh_display();
        if (now_ms() - config_check_ms >= 1000) {
            config_check_ms = now_ms();
            if (config_time_changed()) {
                log_msg("配置文件有变化");
                reload_config(pens, &npens);
                remember_config_time();
            }
        }
        if (pr == 0 || npens == 0 || saw_watch) {
            int before = npens;
            scan(pens, &npens);
            if (npens > before && need_display()) refresh_display();
            if (npens == 0 && !waiting) {
                log_waiting();
                waiting = 1;
            }
            if (npens > before) waiting = 0;
        }
    }
    release_held();
    if (watch >= 0) close(watch);
    for (int i = 0; i < npens; i++) {
        char path[64];
        snprintf(path, sizeof path, "%s", pens[i].path);
        close_pen(&pens[i]);
        log_msg("已放开 %s", path);
    }
    if (kbd_fd >= 0) {
        ioctl(kbd_fd, UI_DEV_DESTROY);
        close(kbd_fd);
        kbd_fd = -1;
    }
    log_msg("退出");
    return 0;
}

static void usage(void) {
    fputs("usage: stylusctld [-v] [--check] [--config PATH]\n", stderr);
}

int main(int argc, char **argv) {
    setvbuf(stderr, NULL, _IONBF, 0);
    int check = 0;
    const char *config_arg = NULL;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-v") == 0 || strcmp(argv[i], "--verbose") == 0) verbose = 1;
        else if (strcmp(argv[i], "--check") == 0) check = 1;
        else if (strcmp(argv[i], "--config") == 0 && i + 1 < argc) config_arg = argv[++i];
        else {
            usage();
            return 2;
        }
    }
    if (check) return check_mode();
    if (config_arg) {
        if (snprintf(config_path, sizeof config_path, "%s", config_arg) >= (int)sizeof config_path) {
            usage();
            return 2;
        }
    } else if (access("/data/adb/stylusctrl/config.json", R_OK) == 0) {
        snprintf(config_path, sizeof config_path, "/data/adb/stylusctrl/config.json");
    } else if (access("/data/local/tmp/stylusctrl.json", R_OK) == 0) {
        snprintf(config_path, sizeof config_path, "/data/local/tmp/stylusctrl.json");
    } else {
        snprintf(config_path, sizeof config_path, "%s", APP_CONFIG);
    }
    return run_daemon();
}

#endif
