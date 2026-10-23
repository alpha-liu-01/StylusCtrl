/* Grab the pointer pen and pass it through uinput.
   A json profile (phase 2) remaps area, pressure, and buttons.
   No file means phase 1 passthrough. */
#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
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
    int sent_pen;
    int sent_stylus2;
    unsigned axes;
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
    STYLUS_SHORTCUT = 1
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
    int stylus_action;
    int rubber_action;
    int eraser_wins;
    int stylus_keys[8];
    int stylus_nkeys;
    int rubber_keys[8];
    int rubber_nkeys;
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
        unsigned bit = axis_bit(ev->code);
        return bit && (st->axes & bit);
    }
    if (ev->type == EV_KEY) {
        return ev->code == BTN_TOOL_RUBBER || ev->code == BTN_TOUCH || ev->code == BTN_STYLUS;
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
    int hide_pen = rubber_act == RUBBER_ERASER && eraser_wins && rubber;
    int want_pen = hide_pen ? 0 : pen;
    int want_s2 = rubber_act == RUBBER_STYLUS2 ? rubber : 0;
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
        if (in[i].type == EV_KEY && in[i].code == BTN_TOOL_RUBBER && rubber_act != RUBBER_ERASER) continue;
        if (in[i].type == EV_KEY && in[i].code == BTN_STYLUS && stylus_act == STYLUS_SHORTCUT) continue;
        if (!keep_event(st, &in[i])) continue;
        if (count >= out_cap) return -1;
        out[count++] = in[i];
    }
    st->phys_pen = pen;
    st->phys_rubber = rubber;
    st->phys_touch = touch;
    st->phys_stylus = stylus;
    st->sent_pen = want_pen;
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

static int apply_string(const char *path, const char *s, struct profile *prof) {
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
    if (!strcmp(path, "buttons.BTN_STYLUS.action")) {
        if (!strcmp(s, "passthrough")) prof->stylus_action = STYLUS_PASS;
        else if (!strcmp(s, "shortcut")) prof->stylus_action = STYLUS_SHORTCUT;
        else return -1;
        return 0;
    }
    if (!strcmp(path, "buttons.BTN_TOOL_RUBBER.action")) {
        if (!strcmp(s, "eraser")) prof->rubber_action = RUBBER_ERASER;
        else if (!strcmp(s, "shortcut")) prof->rubber_action = RUBBER_SHORTCUT;
        else if (!strcmp(s, "stylus2") || !strcmp(s, "BTN_STYLUS2")) prof->rubber_action = RUBBER_STYLUS2;
        else return -1;
        return 0;
    }
    return 0;
}

static int assign_array(const char *path, const double *nums, int n, const int *codes, int ncodes,
                        struct profile *prof) {
    if (!strcmp(path, "pressure.curve")) {
        if (n != 6 && n != 8) return -1;
        if (n == 8) {
            for (int i = 0; i < 8; i++) prof->curve[i] = nums[i];
        } else {
            prof->curve[0] = nums[0];
            prof->curve[1] = nums[1];
            prof->curve[2] = nums[2];
            prof->curve[3] = nums[3];
            prof->curve[4] = nums[2];
            prof->curve[5] = nums[3];
            prof->curve[6] = nums[4];
            prof->curve[7] = nums[5];
        }
        prof->curve_n = 8;
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
    if (!strcmp(path, "buttons.BTN_STYLUS.keys")) {
        if (ncodes > 8) return -1;
        for (int i = 0; i < ncodes; i++) prof->stylus_keys[i] = codes[i];
        prof->stylus_nkeys = ncodes;
        return 0;
    }
    if (!strcmp(path, "buttons.BTN_TOOL_RUBBER.keys")) {
        if (ncodes > 8) return -1;
        for (int i = 0; i < ncodes; i++) prof->rubber_keys[i] = codes[i];
        prof->rubber_nkeys = ncodes;
        return 0;
    }
    return 0;
}

static int parse_value(const char **pp, const char *path, struct profile *prof);

static int parse_array(const char **pp, const char *path, struct profile *prof) {
    const char *p = skip_ws(*pp);
    if (*p != '[') return -1;
    p++;
    int is_curve = !strcmp(path, "pressure.curve");
    int is_rect = !strcmp(path, "mapping.rect");
    int is_keys = !strcmp(path, "buttons.BTN_STYLUS.keys") || !strcmp(path, "buttons.BTN_TOOL_RUBBER.keys");
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
        *pp = p + 4;
        return 0;
    }
    if (!strncmp(p, "false", 5)) {
        if (!strcmp(path, "tools.eraserWins")) prof->eraser_wins = 0;
        *pp = p + 5;
        return 0;
    }
    if (!strncmp(p, "null", 4)) return -1;
    char *end = NULL;
    double v = strtod(p, &end);
    if (end == p) return -1;
    if (!strcmp(path, "pressure.deadzone")) {
        if (v < 0 || v > 1) return -1;
        prof->deadzone = v;
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
    if (p->curve_n == 0 && p->deadzone <= 0) return raw;
    double span = (double)(pmax - pmin);
    double t = ((double)raw - (double)pmin) / span;
    if (t < 0) t = 0;
    if (t > 1) t = 1;
    if (p->deadzone > 0) {
        if (t <= p->deadzone || p->deadzone >= 1) return pmin;
        t = (t - p->deadzone) / (1.0 - p->deadzone);
    }
    if (p->curve_n == 0) {
        double y = t * span;
        int out = pmin + (int)(y >= 0 ? y + 0.5 : y - 0.5);
        if (out < pmin) return pmin;
        if (out > pmax) return pmax;
        return out;
    }
    double lo = 0;
    double hi = 1;
    for (int i = 0; i < 24; i++) {
        double mid = (lo + hi) / 2.0;
        double x = bezier(mid, p->curve[0], p->curve[2], p->curve[4], p->curve[6]);
        if (x < t) lo = mid;
        else hi = mid;
    }
    double y = bezier((lo + hi) / 2.0, p->curve[1], p->curve[3], p->curve[5], p->curve[7]);
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
    int x0 = xmin;
    int x1 = xmax;
    int y0 = ymin;
    int y1 = ymax;
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
        if (x0 < xmin) x0 = xmin;
        if (x1 > xmax) x1 = xmax;
        if (y0 < ymin) y0 = ymin;
        if (y1 > ymax) y1 = ymax;
        if (x1 < x0 || y1 < y0) return;
    }
    int cx = x;
    int cy = y;
    if (cx < x0) cx = x0;
    if (cx > x1) cx = x1;
    if (cy < y0) cy = y0;
    if (cy > y1) cy = y1;
    int rw = x1 - x0 + 1;
    int rh = y1 - y0 + 1;
    /* External tablet axes stay put. orientationAware would swap them with
       the viewport and turn a landscape fit into the portrait 12:20 window.
       disp_w/disp_h are already the oriented viewport. Inputflinger undoes
       that rotation after the uniform scale. */
    double lru, lrv;
    rotate_pt(0, rw, rh, (double)(cx - x0), (double)(cy - y0), &lru, &lrv);
    int rrw, rrh;
    rotated_size(0, rw, rh, &rrw, &rrh);
    if (rrw <= 0 || rrh <= 0) return;
    double sx, sy;
    if (p->mode == MODE_STRETCH) {
        sx = lru / (double)rrw * (double)p->disp_w;
        sy = lrv / (double)rrh * (double)p->disp_h;
    } else {
        double xscale = (double)p->disp_w / (double)rrw;
        double yscale = (double)p->disp_h / (double)rrh;
        double fs = xscale > yscale ? xscale : yscale;
        if (fs <= 0) return;
        double vis_w = (double)p->disp_w / fs;
        double vis_h = (double)p->disp_h / fs;
        double origin_x = 0;
        double origin_y = 0;
        int x_over = (double)rrw - vis_w > 1.0;
        int y_over = (double)rrh - vis_h > 1.0;
        if (p->crop == CROP_LEFT && x_over) origin_x = (double)rrw - vis_w;
        else if (p->crop == CROP_TOP && y_over) origin_y = (double)rrh - vis_h;
        else if (p->crop == CROP_CENTER) {
            if (x_over) origin_x = ((double)rrw - vis_w) / 2.0;
            if (y_over) origin_y = ((double)rrh - vis_h) / 2.0;
        }
        sx = (lru - origin_x) * fs;
        sy = (lrv - origin_y) * fs;
    }
    int W = xmax - xmin + 1;
    int H = ymax - ymin + 1;
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
    struct profile right = display_profile(MODE_FIT, CROP_RIGHT, 0, 1200, 2000);
    map_of(&right, 0, 0, &ox, &oy);
    expect("fit right origin x", ox, 0);
    expect("fit right origin y", oy, 0);
    map_of(&right, 15200, 9500, &ox, &oy);
    expect("fit right far x", ox, 15200);
    expect("fit right far y", oy, 9500);

    struct profile left = display_profile(MODE_FIT, CROP_LEFT, 0, 1200, 2000);
    map_of(&left, 15200, 9500, &ox, &oy);
    expect("fit left far x", ox, 5700);
    expect("fit left far y", oy, 9500);
    map_of(&left, 9500, 0, &ox, &oy);
    expect("fit left window x", ox, 0);

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
    map_of(&bottom, 0, 0, &ox, &oy);
    expect("land bottom origin x", ox, 0);
    expect("land bottom origin y", oy, 0);
    map_of(&bottom, 15200, 9500, &ox, &oy);
    expect("land bottom x", ox, 15200);
    expect("land bottom y", oy, 9500);

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
}

int main(void) {
    test_pen_down();
    test_rubber_hides_pen_in_same_report();
    test_rubber_arrival_releases_sent_pen();
    test_rubber_release_restores_pen();
    test_repeat_pen_is_not_duplicated();
    test_stylus_survives_rubber();
    test_parse_roadmap();
    test_pressure_curve();
    test_rotation_roundtrip();
    test_map_fit_and_stretch();
    test_button_actions();
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
static int kbd_fd = -1;
static int held_stylus;
static int held_rubber;
static long display_ms;

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
    int ufd;
    int drop;
    int n;
    int seen;
    int idc_on;
    int xmin, xmax, ymin, ymax, pmin, pmax;
    int last_x, last_y, last_pressure;
    char path[64];
    char vname[UINPUT_MAX_NAME_SIZE];
    char idc_path[160];
    struct input_id id;
    struct axis_setup axes[5];
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
                         int naxes, int *ufd_out) {
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
    if (ioctl(ufd, UI_SET_PROPBIT, INPUT_PROP_POINTER) < 0) goto fail;
    for (int i = 0; i < 5; i++) {
        if (ioctl(ufd, UI_SET_KEYBIT, keys[i]) < 0) goto fail;
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
    if (create_uinput("StylusCtrl check", &id, axes, 5, &ufd) < 0) return 1;
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
        return "丢掉左边";
    case CROP_RIGHT:
        return "丢掉右边";
    case CROP_TOP:
        return "丢掉上边";
    case CROP_BOTTOM:
        return "丢掉下边";
    default:
        return "居中";
    }
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
    const char *side = cfg.stylus_action == STYLUS_SHORTCUT ? "快捷键" : "原样";
    log_msg("配置 %s，%s，侧键%s，橡皮位%s，死区 %.2f", mode_word(cfg.mode), crop_word(cfg.crop), side,
            rubber_word(cfg.rubber_action), cfg.deadzone);
    if (cfg.has_rect) log_msg("板内矩形 %d,%d-%d,%d", cfg.rect[0], cfg.rect[1], cfg.rect[2], cfg.rect[3]);
    if (cfg.has_match) log_msg("只抓 %04x:%04x", cfg.vendor, cfg.product);
    if (cfg.curve_n) log_msg("压感曲线已加载");
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
    char *start = strstr(buf, "type=INTERNAL");
    if (!start) {
        free(buf);
        log_msg("dumpsys 里没有内屏");
        return -1;
    }
    char *end = strchr(start, '}');
    size_t len = end ? (size_t)(end - start) : strlen(start);
    if (len > 8000) len = 8000;
    char saved = start[len];
    start[len] = 0;
    char *o = strstr(start, "orientation=");
    char *w = strstr(start, "deviceWidth=");
    char *h = strstr(start, "deviceHeight=");
    if (!o || !w || !h) {
        start[len] = saved;
        free(buf);
        log_msg("读不到屏幕方向");
        return -1;
    }
    int ori = atoi(o + 12);
    int dw = atoi(w + 12);
    int dh = atoi(h + 13);
    start[len] = saved;
    free(buf);
    if (dw <= 0 || dh <= 0 || ori < 0 || ori > 3) {
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
    if (create_uinput(pen->vname, &pen->id, pen->axes, 5, &ufd) < 0) return -1;
    if (pen->ufd >= 0) {
        ioctl(pen->ufd, UI_DEV_DESTROY);
        close(pen->ufd);
    }
    pen->ufd = ufd;
    pen->n = 0;
    pen->idc_on = cfg_loaded;
    return 0;
}

static void apply_axes(struct pen *pen, const struct input_event *in, int nin, struct input_event *out, int nout) {
    int x = pen->last_x;
    int y = pen->last_y;
    int pressure = pen->last_pressure;
    for (int i = 0; i < nin; i++) {
        if (in[i].type != EV_ABS) continue;
        if (in[i].code == ABS_X) x = in[i].value;
        else if (in[i].code == ABS_Y) y = in[i].value;
        else if (in[i].code == ABS_PRESSURE) pressure = in[i].value;
    }
    pen->last_x = x;
    pen->last_y = y;
    pen->last_pressure = pressure;
    int mx = x;
    int my = y;
    map_point(&cfg, pen->xmin, pen->xmax, pen->ymin, pen->ymax, x, y, &mx, &my);
    int mp = pressure_out(&cfg, pressure, pen->pmin, pen->pmax);
    for (int i = 0; i < nout; i++) {
        if (out[i].type != EV_ABS) continue;
        if (out[i].code == ABS_X) out[i].value = mx;
        else if (out[i].code == ABS_Y) out[i].value = my;
        else if (out[i].code == ABS_PRESSURE) out[i].value = mp;
    }
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

static void release_held(void) {
    if (held_stylus) {
        emit_shortcut(cfg.stylus_keys, cfg.stylus_nkeys, 0);
        held_stylus = 0;
    }
    if (held_rubber) {
        emit_shortcut(cfg.rubber_keys, cfg.rubber_nkeys, 0);
        held_rubber = 0;
    }
}

static int create_keyboard(void) {
    int ufd = open("/dev/uinput", O_WRONLY | O_CLOEXEC);
    if (ufd < 0) {
        log_errno("打开 /dev/uinput");
        return -1;
    }
    if (ioctl(ufd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(ufd, UI_SET_EVBIT, EV_SYN) < 0) goto fail;
    int mods[] = {KEY_LEFTCTRL, KEY_LEFTALT, KEY_LEFTSHIFT, KEY_LEFTMETA};
    for (int i = 0; i < 4; i++) {
        if (ioctl(ufd, UI_SET_KEYBIT, mods[i]) < 0) goto fail;
    }
    static const int letters[] = {KEY_A, KEY_B, KEY_C, KEY_D, KEY_E, KEY_F, KEY_G, KEY_H, KEY_I, KEY_J,
                                  KEY_K, KEY_L, KEY_M, KEY_N, KEY_O, KEY_P, KEY_Q, KEY_R, KEY_S, KEY_T,
                                  KEY_U, KEY_V, KEY_W, KEY_X, KEY_Y, KEY_Z};
    for (int i = 0; i < 26; i++) {
        if (ioctl(ufd, UI_SET_KEYBIT, letters[i]) < 0) goto fail;
    }
    for (int code = KEY_1; code <= KEY_0; code++) {
        if (ioctl(ufd, UI_SET_KEYBIT, code) < 0) goto fail;
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

static int wants_keyboard(void) {
    return cfg_loaded && (cfg.stylus_action == STYLUS_SHORTCUT || cfg.rubber_action == RUBBER_SHORTCUT);
}

static void sync_keyboard(void) {
    if (wants_keyboard()) {
        if (kbd_fd < 0) create_keyboard();
    } else if (kbd_fd >= 0) {
        ioctl(kbd_fd, UI_DEV_DESTROY);
        close(kbd_fd);
        kbd_fd = -1;
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
    if (cfg_loaded) {
        map_point(&cfg, pen->xmin, pen->xmax, pen->ymin, pen->ymax, pen->last_x, pen->last_y, &mx, &my);
        mp = pressure_out(&cfg, pen->last_pressure, pen->pmin, pen->pmax);
    }
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    struct input_event evs[9];
    int n = 0;
    stamp_ev(&evs[n++], &ts, EV_ABS, ABS_X, mx);
    stamp_ev(&evs[n++], &ts, EV_ABS, ABS_Y, my);
    stamp_ev(&evs[n++], &ts, EV_ABS, ABS_PRESSURE, mp);
    stamp_ev(&evs[n++], &ts, EV_KEY, BTN_TOOL_PEN, pen->tool.sent_pen);
    if (!cfg_loaded || cfg.rubber_action == RUBBER_ERASER) {
        stamp_ev(&evs[n++], &ts, EV_KEY, BTN_TOOL_RUBBER, pen->tool.phys_rubber);
    } else if (cfg.rubber_action == RUBBER_STYLUS2) {
        stamp_ev(&evs[n++], &ts, EV_KEY, BTN_STYLUS2, pen->tool.phys_rubber);
    }
    stamp_ev(&evs[n++], &ts, EV_KEY, BTN_TOUCH, pen->tool.phys_touch);
    if (!cfg_loaded || cfg.stylus_action == STYLUS_PASS) {
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
    if (!cfg_loaded || kbd_fd < 0) return;
    for (int i = 0; i < npens; i++) {
        if (cfg.stylus_action == STYLUS_SHORTCUT && pens[i].tool.phys_stylus && !held_stylus) {
            emit_shortcut(cfg.stylus_keys, cfg.stylus_nkeys, 1);
            held_stylus = 1;
        }
        if (cfg.rubber_action == RUBBER_SHORTCUT && pens[i].tool.phys_rubber && !held_rubber) {
            emit_shortcut(cfg.rubber_keys, cfg.rubber_nkeys, 1);
            held_rubber = 1;
        }
    }
}

static void forward_packet(struct pen *pen) {
    int in_n = pen->n;
    int prev_pen = pen->tool.sent_pen;
    int prev_rubber = pen->tool.phys_rubber;
    int prev_touch = pen->tool.phys_touch;
    int prev_stylus = pen->tool.phys_stylus;
    struct input_event out[MAX_PACKET + 4];
    const struct profile *prof = cfg_loaded ? &cfg : NULL;
    int n = emit_report(&pen->tool, pen->pkt, in_n, out, MAX_PACKET + 4, prof);
    if (n < 0) {
        pen->n = 0;
        log_msg("%s 报告写不下，丢掉这一帧", pen->path);
        return;
    }
    if (prof) apply_axes(pen, pen->pkt, in_n, out, n);
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
    if (prof && prof->stylus_action == STYLUS_SHORTCUT && prev_stylus != pen->tool.phys_stylus) {
        emit_shortcut(prof->stylus_keys, prof->stylus_nkeys, pen->tool.phys_stylus);
        held_stylus = pen->tool.phys_stylus;
    }
    if (prof && prof->rubber_action == RUBBER_SHORTCUT && prev_rubber != pen->tool.phys_rubber) {
        emit_shortcut(prof->rubber_keys, prof->rubber_nkeys, pen->tool.phys_rubber);
        held_rubber = pen->tool.phys_rubber;
    }
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
    memset(prop, 0, sizeof prop);
    if (ioctl(fd, EVIOCGPROP(sizeof prop), prop) < 0 || !bit_is_set(prop, INPUT_PROP_POINTER) ||
        bit_is_set(prop, INPUT_PROP_DIRECT)) {
        close(fd);
        return;
    }
    unsigned long absbits[(ABS_MAX / (sizeof(long) * 8)) + 1];
    memset(absbits, 0, sizeof absbits);
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof absbits), absbits) < 0 ||
        !bit_is_set(absbits, ABS_X) || !bit_is_set(absbits, ABS_Y) ||
        !bit_is_set(absbits, ABS_PRESSURE)) {
        close(fd);
        return;
    }

    int codes[] = {ABS_X, ABS_Y, ABS_PRESSURE, ABS_TILT_X, ABS_TILT_Y};
    struct axis_setup axes[5];
    memset(axes, 0, sizeof axes);
    unsigned mask = 0;
    for (int i = 0; i < 5; i++) {
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
    struct input_id id;
    memset(&id, 0, sizeof id);
    if (ioctl(fd, EVIOCGID, &id) < 0) {
        log_errno(path);
        close(fd);
        return;
    }
    if (cfg_loaded && cfg.has_match && (id.vendor != cfg.vendor || id.product != cfg.product)) {
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
    pen->ufd = -1;
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
    char sysname[64];
    char node[64];
    if (uinput_sysname(pen->ufd, sysname, sizeof sysname) == 0) event_node(sysname, node, sizeof node);
    else snprintf(node, sizeof node, "uinput");
    log_msg("独占 %s「%s」，虚拟设备 %s，x %d-%d，y %d-%d，pressure %d-%d", path, source, node,
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

static void reload_config(struct pen *pens, int npens) {
    release_held();
    if (load_config() == 0) {
        sync_keyboard();
        if (need_display()) refresh_display();
        for (int i = 0; i < npens; i++) {
            if (pens[i].fd < 0 || pens[i].idc_on == cfg_loaded) continue;
            if (open_virtual(&pens[i]) == 0) {
                resync_pen(&pens[i]);
                log_msg("已按配置重建 %s", pens[i].path);
            }
        }
    }
    resync_shortcuts(pens, npens);
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
            reload_config(pens, npens);
        }
        struct pollfd pfds[MAX_PENS + 1];
        int nfd = 0;
        for (int i = 0; i < npens; i++) {
            pfds[nfd].fd = pens[i].fd;
            pfds[nfd].events = POLLIN;
            pfds[nfd].revents = 0;
            nfd++;
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
            for (int i = npens - 1; i >= 0; i--) {
                if (pfds[i].revents & (POLLIN | POLLERR | POLLHUP)) drain(&pens[i]);
                if (pens[i].fd < 0) {
                    if (held_stylus && pens[i].tool.phys_stylus) {
                        emit_shortcut(cfg.stylus_keys, cfg.stylus_nkeys, 0);
                        held_stylus = 0;
                    }
                    if (held_rubber && pens[i].tool.phys_rubber) {
                        emit_shortcut(cfg.rubber_keys, cfg.rubber_nkeys, 0);
                        held_rubber = 0;
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
        if (pr == 0 && npens > 0 && need_display() && now_ms() - display_ms >= 5000) refresh_display();
        if (pr == 0 || npens == 0 || saw_watch) {
            int before = npens;
            scan(pens, &npens);
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
    } else {
        snprintf(config_path, sizeof config_path, "/data/local/tmp/stylusctrl.json");
    }
    return run_daemon();
}

#endif
