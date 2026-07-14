# -*- coding: utf-8 -*-
"""
BIRDO VPN - Google Play feature graphic - concept "ATLAS-FINAL"
1024 x 500, 24-bit RGB, no alpha. Rendered at 4x, LANCZOS-downsampled, grain at final res.

This is the MERGE of the two prior renders:

  FROM atlas-craft (kept wholesale)
      - dot-matrix world map with SUBTRACTED seas (Med/Black/Caspian/Red/Gulf/Hudson) so
        Europe and Africa do not fuse; latitude dissolve at the poles (no "arctic band")
      - node-to-node great-circle arc CHAIN (every arc arrives at a node), bloom + haze
      - lit nodes, none inside the 220px play-button reserve
      - one light source; every map dot / arc graded by inverse-square falloff from it
      - feathered TYPE GUARD boxes: no art can touch a letterform, by construction
      - optical type engine (ink-bbox centring, kern table), white->mint "VPN",
        emerald full stops, chip line at 78% white
      - sheen, scrims, vignette, film grain at final res

  FROM atlas (restored)
      - the PHOENIX IS THE APP MARK, SHARP. mark.png composited at full fidelity: crisp
        LANCZOS edges, its own white-head -> emerald-body gradient, no halftone, no
        dot-matrix dissolve, no lattice mist. The bird is the brand; it must be
        unmistakable. Only the extreme lower-left wing tips feather out (they are wisps in
        the source art anyway) so the mark lands in the scene instead of being pasted on it.

  NEW - figure / ground
      - the map is knocked out behind the bird by the bird's own blurred silhouette
        (tight, alpha-driven) AND by a soft elliptical recession, so nothing reads as
        "the bird is made of map dots"
      - a contact-shadow ring darkens the plate immediately outside the silhouette, then
        the emerald bloom lifts around it: dark rim inside, glow outside -> the mark
        detaches from the plate at any size

Two deliverables, identical but for the mark's presence:
      atlas-final      PHX_SIZE 192
      atlas-final-big  PHX_SIZE 221 (+15%), bloom x1.20

Safe-zone contract (Play checklist, house rules):
  critical box  = centred 660x420 -> x 182..842, y 40..460   (headline + mark inside)
  meaning bound = centred 890x460 -> x  67..957, y 20..480
  play reserve  = centred circle d=220 -> zero text, zero mark, nothing bright
"""

import sys
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.join(HERE, "fonts")                 # vendored Inter (SIL OFL - see fonts/LICENSE.txt)
MARK = os.path.join(HERE, "brand-mark-1024.png")    # the emerald phoenix, transparent


# ----------------------------------------------------------------------------- config


W, H = 1024, 500
SS = 4
SW, SH = W * SS, H * SS

ALT_TEXT = ("Birdo VPN: the Birdo phoenix over a dark emerald dot-matrix world map with "
            "glowing server nodes and network arcs. Encrypted. Everywhere.")


# ----------------------------------------------------------------------------- palette
def hx(s):
    s = s.lstrip("#")
    return np.array([int(s[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float32) / 255.0


SURFACE0 = hx("050507")
EM300 = hx("6EE7B7")
EM400 = hx("34D399")
EM500 = hx("10B981")
EM600 = hx("059669")
EM900 = hx("064E3B")
TEAL = hx("14B8A6")
SLATE = hx("64748B")

# ----------------------------------------------------------------------------- geometry
CEN_X, CEN_Y = W / 2.0, H / 2.0
PLAY_R = 110.0                      # play-button reserve radius (generous house rule)

PHX_CX, PHX_CY = 276.0, 214.0       # phoenix centre (square paste; aspect preserved)
BLOOM_X, BLOOM_Y = 284.0, 206.0     # the single light source, just behind the mark

# mark.png ink occupies these fractions of its square canvas (measured)
ART_L_F, ART_R_F = 0.1230, 0.8789
ART_T_F, ART_B_F = 0.1035, 0.8984

# equirectangular projection
MX0, MX1 = 62.0, 946.0
PXDEG = (MX1 - MX0) / 360.0         # 2.4556 px/deg
MY0 = 68.0                          # y of lat 72N
LAT_REF = 72.0

MAP_PITCH_DEG = 3.0
MAP_PITCH = MAP_PITCH_DEG * PXDEG   # 7.367 px


def proj(lon, lat):
    return MX0 + (lon + 180.0) * PXDEG, MY0 + (LAT_REF - lat) * PXDEG


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


# ----------------------------------------------------------------------------- coastlines
# Closed rings in (lon, lat). LAND = union(POLYS) minus union(SEAS).
POLYS = [
    # North America
    [(-168, 66), (-166, 60), (-158, 57), (-152, 58), (-146, 60), (-139, 60), (-134, 57),
     (-130, 53), (-126, 49), (-124, 45), (-123, 40), (-120, 35), (-117, 32.5), (-114, 29),
     (-112, 25), (-106, 23), (-99, 19), (-95, 18), (-90, 18), (-87, 21), (-90, 25), (-94, 29),
     (-89, 29), (-84, 30), (-82, 28), (-80, 25), (-80, 31), (-76, 35), (-70, 41), (-66, 44),
     (-60, 46), (-53, 47), (-56, 53), (-64, 58), (-78, 62), (-80, 68), (-92, 70), (-105, 69),
     (-120, 70), (-133, 69), (-145, 70), (-157, 71)],
    # Greenland (mostly dissolved by the arctic fade)
    [(-45, 60), (-33, 66), (-22, 70), (-20, 76), (-28, 82), (-45, 83), (-58, 82), (-60, 76),
     (-55, 68), (-50, 62)],
    # Central America
    [(-95, 17), (-88, 15), (-84, 11), (-79, 8), (-77, 7), (-79, 10), (-84, 14), (-89, 17), (-93, 19)],
    # Cuba / Hispaniola
    [(-85, 22), (-77, 20), (-74, 19), (-77, 22), (-83, 23)],
    [(-74, 19), (-69, 19), (-68, 17), (-72, 17)],
    # South America
    [(-81, 7), (-76, 9), (-71, 12), (-63, 11), (-60, 8), (-52, 5), (-50, 1), (-44, -2), (-38, -4),
     (-35, -6), (-38, -13), (-39, -18), (-42, -23), (-48, -25), (-53, -33), (-58, -35), (-57, -39),
     (-62, -40), (-65, -43), (-68, -50), (-72, -54), (-75, -52), (-73, -45), (-73, -38), (-72, -30),
     (-70, -22), (-71, -16), (-77, -6), (-80, -3), (-81, 1), (-78, 6)],
    # British Isles
    [(-10, 52), (-6, 55), (-3, 58), (-2, 57), (0, 53), (1, 51), (-5, 50), (-10, 51)],
    # EUROPE (overshoots south into the Med; the sea carve defines the coast)
    [(-9.5, 43), (-9, 37), (-6, 36), (-2, 30), (14, 30), (26, 30), (36, 30), (40, 36), (42, 42),
     (46, 46), (48, 52), (50, 56), (48, 60), (45, 66), (40, 68), (30, 70), (22, 70), (15, 67),
     (10, 63), (11, 58), (8, 57), (9, 54), (7, 53), (3, 51), (1, 49), (-2, 48), (-2, 43)],
    # Siberia / Central Asia / China
    [(45, 66), (55, 70), (65, 72), (75, 73), (85, 75), (95, 76), (105, 76), (115, 74), (125, 73),
     (135, 72), (145, 70), (155, 68), (163, 62), (170, 60), (163, 58), (158, 52), (150, 48),
     (142, 46), (135, 44), (130, 43), (127, 39), (122, 37), (121, 31), (118, 26), (112, 22),
     (108, 21), (102, 22), (97, 26), (92, 28), (88, 28), (80, 30), (75, 32), (70, 32), (65, 35),
     (58, 38), (52, 42), (48, 45), (46, 50), (44, 55), (43, 60)],
    # Middle East + Arabia (carved back out by the Red Sea / Gulf)
    [(34, 31), (38, 36), (45, 39), (52, 40), (60, 38), (64, 30), (62, 25), (57, 23), (52, 22),
     (48, 20), (44, 12), (43, 12), (39, 15), (35, 25)],
    # India
    [(67, 24), (70, 23), (72, 20), (73, 15), (77, 8), (80, 12), (82, 17), (86, 21), (89, 22),
     (92, 21), (89, 26), (84, 27), (78, 30), (72, 28), (68, 26)],
    # Indochina
    [(92, 21), (96, 17), (99, 10), (103, 5), (105, 9), (107, 14), (109, 18), (107, 21), (103, 22),
     (98, 20), (95, 22)],
    # Japan
    [(129, 31), (135, 34), (140, 36), (144, 42), (146, 45), (141, 45), (137, 38), (132, 34), (128, 33)],
    # AFRICA (overshoots north into the Med; the sea carve defines the coast)
    [(-17, 15), (-16, 20), (-13, 28), (-10, 33), (-6, 38), (2, 40), (12, 40), (22, 38), (30, 36),
     (34, 32), (37, 22), (42, 12), (48, 12), (51, 11), (48, 5), (42, 0), (40, -5),
     (40, -12), (35, -18), (32, -25), (30, -30), (26, -34), (20, -34), (18, -33), (15, -25),
     (12, -17), (9, -1), (9, 4), (3, 6), (-3, 5), (-8, 5), (-13, 9), (-17, 12)],
    # Madagascar
    [(44, -16), (48, -13), (50, -16), (48, -24), (45, -25), (43, -21)],
    # Sumatra / Java / Borneo / Sulawesi / Papua / Philippines
    [(95, 6), (99, 4), (106, -6), (102, -6), (95, 2)],
    [(105, -6), (114, -8), (115, -9), (105, -8)],
    [(109, 2), (117, 7), (119, 1), (117, -4), (110, -4), (108, -1)],
    [(119, 1), (125, 1), (124, -5), (120, -6), (119, -3)],
    [(131, -1), (141, -3), (150, -6), (147, -10), (140, -9), (133, -4)],
    [(120, 18), (124, 18), (126, 10), (123, 6), (120, 12)],
    # Australia
    [(114, -22), (113, -26), (115, -34), (120, -34), (129, -32), (135, -35), (138, -35), (141, -38),
     (147, -38), (150, -36), (153, -28), (153, -25), (148, -20), (143, -13), (136, -12), (130, -11),
     (125, -14), (122, -17), (117, -20)],
]

# Subtracted seas - this is what stops Europe and Africa fusing into one blob.
SEAS = [
    # Mediterranean, around the Italian boot
    [(-5.5, 35.9), (0, 36.3), (5, 36.9), (10, 37.2), (11.5, 34), (15, 32.4), (20, 32),
     (25, 31.6), (31, 31.4), (34, 31.6), (36, 36), (33, 36.5), (30, 36.6), (27, 37),
     (26, 40.4), (23.6, 40.6), (20.2, 40.2), (18.8, 42.2), (16.3, 43.2), (13.5, 45.4),
     (14.6, 42.2), (16.4, 41.6), (18.4, 40.6), (16.2, 38.9), (15.6, 38.1), (16.0, 40.2),
     (14.0, 41.2), (12.0, 41.6), (10.4, 43.2), (8.2, 44.0), (5.0, 43.3), (3.0, 42.6),
     (0.2, 39.2), (-0.6, 38.0), (-2.2, 36.7)],
    # Black Sea
    [(28, 41.2), (33, 42), (38, 43), (41.5, 43.2), (40, 45.5), (35, 46.2), (30, 46.5), (27.5, 43.5)],
    # Caspian
    [(48.5, 37.5), (52.5, 38.5), (54, 42), (52.5, 46), (49, 46), (47.5, 42)],
    # Red Sea
    [(32.5, 29.8), (35, 28), (38.5, 22), (43.5, 13.5), (42.2, 12.2), (37, 20), (34.5, 25), (31.6, 28.6)],
    # Persian Gulf
    [(48, 30.2), (50.5, 28), (56.5, 26.5), (57.5, 24.6), (53, 25.5), (48.5, 28.5), (46.8, 29.8)],
    # Hudson Bay
    [(-95, 60), (-79, 60), (-77, 63), (-80, 66.5), (-88, 67), (-95, 64)],
    # Gulf of Mexico
    [(-97, 21), (-90, 19), (-86, 21.5), (-83, 24), (-82.5, 27.5), (-84.5, 29.5), (-89, 29.5),
     (-94, 28.5), (-97, 26)],
]


def _bbox(rings):
    out = []
    for P in rings:
        xs = [p[0] for p in P]
        ys = [p[1] for p in P]
        out.append((min(xs), max(xs), min(ys), max(ys)))
    return out


PB = _bbox(POLYS)
SB = _bbox(SEAS)


def pip(x, y, poly):
    inside = False
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y):
            if x < (xj - xi) * (y - yi) / (yj - yi) + xi:
                inside = not inside
        j = i
    return inside


def _hit(lon, lat, rings, bb):
    for k, P in enumerate(rings):
        x0, x1, y0, y1 = bb[k]
        if lon < x0 or lon > x1 or lat < y0 or lat > y1:
            continue
        if pip(lon, lat, P):
            return True
    return False


def is_land(lon, lat):
    if not _hit(lon, lat, POLYS, PB):
        return False
    return not _hit(lon, lat, SEAS, SB)


def land_cov(lon, lat, e=0.85):
    """0..1 coverage on a 5-point cross -> coastlines dim rather than cut. e is deliberately
    tight: at 1.15 the cross bled land into the Mediterranean and fused Europe with Africa."""
    pts = ((lon, lat), (lon - e, lat), (lon + e, lat), (lon, lat - e), (lon, lat + e))
    return sum(1 for (a, b) in pts if is_land(a, b)) / 5.0


# ----------------------------------------------------------------------------- nodes / arcs
# Frankfurt (d=109) and Johannesburg (d=99) stay CUT: their cores sat inside the play reserve.
NODES = [
    ("saopaulo",  -46.6, -23.5, 0.95),
    ("london",     -0.5,  51.5, 0.86),
    ("mumbai",     72.9,  19.1, 0.80),
    ("singapore", 103.8,   1.3, 1.00),
    ("tokyo",     139.7,  35.7, 0.92),
    ("sydney",    151.0, -33.9, 0.86),
]
NODE_XY = {n: proj(lo, la) for (n, lo, la, _w) in NODES}

# A CHAIN, not a scatter. Every arc terminates on a node at both ends.
ARCS = [
    ("saopaulo", "london",    0.46),
    ("london", "mumbai",      0.30),
    ("mumbai", "singapore",   0.30),
    ("singapore", "tokyo",    0.28),
    ("singapore", "sydney",  -0.26),
]


def bezier(p0, p1, bulge, n=420):
    (x0, y0), (x1, y1) = p0, p1
    mx, my = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    dx, dy = x1 - x0, y1 - y0
    L = float(np.hypot(dx, dy))
    nx, ny = -dy / L, dx / L
    if ny > 0:
        nx, ny = -nx, -ny                    # normalise the normal to point "up"
    s = 1.0 if bulge >= 0 else -1.0
    cx = mx + nx * L * abs(bulge) * s
    cy = my + ny * L * abs(bulge) * s
    t = np.linspace(0.0, 1.0, n)
    x = (1 - t) ** 2 * x0 + 2 * (1 - t) * t * cx + t ** 2 * x1
    y = (1 - t) ** 2 * y0 + 2 * (1 - t) * t * cy + t ** 2 * y1
    return x, y, t


# ----------------------------------------------------------------------------- guards / light
TYPE_BOX = (188.0, 842.0, 346.0, 452.0)      # feathered no-art zone around the headline lockup
WORD_BOX = (352.0, 672.0, 60.0, 96.0)        # ...and around the wordmark + its hairlines


def _box_guard(x, y, box, feather):
    bx0, bx1, by0, by1 = box
    dx = max(bx0 - x, 0.0, x - bx1)
    dy = max(by0 - y, 0.0, y - by1)
    return float(np.clip(np.hypot(dx, dy) / feather, 0.0, 1.0))


def type_guard(x, y):
    return _box_guard(x, y, TYPE_BOX, 30.0)


def word_guard(x, y):
    return 0.10 + 0.90 * _box_guard(x, y, WORD_BOX, 34.0)


def calm(x, y, floor=0.50):
    """Attenuate toward the play-button reserve. 1.0 well outside, `floor` at the centre."""
    d = float(np.hypot(x - CEN_X, y - CEN_Y))
    return floor + (1.0 - floor) * float(np.clip((d - 96.0) / 90.0, 0.0, 1.0))


def key(x, y, k=430.0):
    """Inverse-square falloff from the ONE light source (the bloom behind the mark)."""
    d2 = (x - BLOOM_X) ** 2 + (y - BLOOM_Y) ** 2
    return 1.0 / (1.0 + d2 / (k * k))


# ----------------------------------------------------------------------------- raster helpers
def radial(cx, cy, rx, ry, power=2.0):
    xs = (np.arange(SW, dtype=np.float32) / SS - cx) / rx
    ys = (np.arange(SH, dtype=np.float32) / SS - cy) / ry
    d = np.sqrt(xs[None, :] ** 2 + ys[:, None] ** 2)
    return (np.clip(1.0 - d, 0.0, 1.0) ** power).astype(np.float32)


def add_light(img, field, color, amount):
    a = (field * amount)[:, :, None] * color[None, None, :]
    return 1.0 - (1.0 - img) * (1.0 - np.clip(a, 0, 1))


def blur_alpha(a_u8, radius_final):
    im = Image.fromarray(a_u8, "L").resize((SW // 4, SH // 4), Image.LANCZOS)
    im = im.filter(ImageFilter.GaussianBlur(radius=max(0.6, radius_final * SS / 4.0)))
    return np.asarray(im.resize((SW, SH), Image.LANCZOS), dtype=np.float32) / 255.0


def composite(base, layer):
    arr = np.asarray(layer, dtype=np.float32) / 255.0
    a = arr[:, :, 3:4]
    return base * (1.0 - a) + arr[:, :, :3] * a


def new_layer():
    return Image.new("RGBA", (SW, SH), (0, 0, 0, 0))


def rgba(c, a):
    return (int(np.clip(c[0], 0, 1) * 255), int(np.clip(c[1], 0, 1) * 255),
            int(np.clip(c[2], 0, 1) * 255), int(np.clip(a, 0, 1) * 255))


def dot(d, x, y, r, col, a):
    if a <= 0.004 or r <= 0.05:
        return
    cx, cy = x * SS, y * SS
    rr = r * SS
    d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=rgba(col, a))


# ----------------------------------------------------------------------------- typography
KERN = {
    "ry": -0.010, "yp": -0.012, "pt": -0.004, "d.": -0.008, ". ": +0.030, " E": +0.012,
    "Ev": -0.016, "ve": -0.004, "yw": -0.016, "wh": -0.004, "he": -0.004, "re": -0.004,
    "e.": -0.010, "cr": -0.004, "En": -0.004,
    "IR": -0.006, "RD": -0.004, "DO": -0.004, "O ": 0.004, " V": 0.004, "VP": -0.010,
    "PN": -0.006, "BI": -0.004,
}


def adv(font, ch):
    return font.getlength(ch)


def stamp(draw, text, font, x0, base, tr):
    x = x0
    prev = None
    for ch in text:
        if prev is not None:
            x += KERN.get(prev + ch, 0.0) * font.size
        draw.text((x, base), ch, font=font, fill=255, anchor="ls")
        x += adv(font, ch) + tr * SS
        prev = ch
    return x - tr * SS


def run_width(font, text, tr):
    x = 0.0
    prev = None
    for ch in text:
        if prev is not None:
            x += KERN.get(prev + ch, 0.0) * font.size
        x += adv(font, ch) + tr * SS
        prev = ch
    return x - tr * SS


class Line:
    """A text line of coloured runs, laid out then OPTICALLY centred on its ink bbox."""

    def __init__(self, runs, tr):
        self.runs = runs                    # [(text, font, tag)]
        self.tr = tr

    def place(self, cx_final, top_final):
        offs = []
        x = 0.0
        prev = None
        for (txt, f, _tag) in self.runs:
            if prev is not None:
                x += KERN.get(prev + txt[0], 0.0) * f.size
            offs.append(x)
            x += run_width(f, txt, self.tr)
            x += self.tr * SS
            prev = txt[-1]

        probe = Image.new("L", (SW, SH), 0)
        pd = ImageDraw.Draw(probe)
        base = int(SH * 0.5)
        for (txt, f, _tag), ox in zip(self.runs, offs):
            stamp(pd, txt, f, 200.0 + ox, base, self.tr)
        bb = probe.getbbox()
        if bb is None:
            raise RuntimeError("empty line")
        ink_l, ink_t, ink_r, ink_b = bb
        shift_x = cx_final * SS - (ink_l + ink_r) / 2.0
        shift_y = top_final * SS - ink_t + base

        masks = {}
        for (txt, f, tag), ox in zip(self.runs, offs):
            m = masks.get(tag)
            if m is None:
                m = Image.new("L", (SW, SH), 0)
                masks[tag] = m
            stamp(ImageDraw.Draw(m), txt, f, 200.0 + ox + shift_x, shift_y, self.tr)

        self.bbox_final = ((ink_l + shift_x) / SS, (ink_t + shift_y - base) / SS,
                           (ink_r + shift_x) / SS, (ink_b + shift_y - base) / SS)
        return masks


def paint(img, mask, color, alpha):
    a = (np.asarray(mask, dtype=np.float32) / 255.0) * alpha
    col = color[None, None, :] if color.ndim == 1 else color
    return img * (1.0 - a[:, :, None]) + col * a[:, :, None]


# ----------------------------------------------------------------------------- build
def build(name, phx_size, bloom=1.00):
    out_png = os.path.join(HERE, "feature-%s-1024x500.png" % name)
    out_thm = os.path.join(HERE, "thumb-%s.png" % name)
    qa = {"variant": name, "phx_size": phx_size}

    yy = (np.arange(SH, dtype=np.float32) / SS)[:, None]
    xx = (np.arange(SW, dtype=np.float32) / SS)[None, :]

    # ---------------------------------------------------------------- 0. the mark, prepared
    # Two rasters of the same artwork:
    #   MSS  - SS-resolution RGBA, the SHARP composite (this is the hero)
    #   Aq   - final-resolution blurred alpha, used to knock the map out behind it
    mark = Image.open(MARK).convert("RGBA")
    ms = int(round(phx_size * SS))
    mss = np.asarray(mark.resize((ms, ms), Image.LANCZOS), dtype=np.float32) / 255.0

    # Feather ONLY the extreme lower-left wing wisps (they are already fraying in the source
    # art). Everything else - head, crest, beak, body, the S-curve - stays fully opaque.
    gx = np.linspace(0.0, 1.0, ms, dtype=np.float32)[None, :]
    gy = np.linspace(0.0, 1.0, ms, dtype=np.float32)[:, None]
    diag = gx * 0.58 + (1.0 - gy) * 0.42                 # 0 at the lower-left tips
    wisp = np.clip((diag - 0.02) / 0.15, 0.0, 1.0) ** 0.85
    mss[:, :, 3] *= (0.68 + 0.32 * wisp)

    px = int(round((PHX_CX - phx_size / 2) * SS))
    py = int(round((PHX_CY - phx_size / 2) * SS))
    MSS = np.zeros((SH, SW, 4), dtype=np.float32)
    x0, y0 = max(0, px), max(0, py)
    x1, y1 = min(SW, px + ms), min(SH, py + ms)
    MSS[y0:y1, x0:x1] = mss[y0 - py:y1 - py, x0 - px:x1 - px]
    del mss

    A8 = (np.clip(MSS[:, :, 3], 0, 1) * 255).astype(np.uint8)      # SS-res alpha, for blooms

    # final-res silhouette for the map knockout (cheap lookups)
    qm = Image.new("L", (W, H), 0)
    mq = mark.resize((int(round(phx_size)), int(round(phx_size))), Image.LANCZOS)
    qm.paste(mq.split()[3], (int(round(PHX_CX - phx_size / 2)), int(round(PHX_CY - phx_size / 2))))
    Aq = np.asarray(qm.filter(ImageFilter.GaussianBlur(9.0)), dtype=np.float32) / 255.0

    art_l = PHX_CX - phx_size / 2 + ART_L_F * phx_size
    art_r = PHX_CX - phx_size / 2 + ART_R_F * phx_size
    art_t = PHX_CY - phx_size / 2 + ART_T_F * phx_size
    art_b = PHX_CY - phx_size / 2 + ART_B_F * phx_size
    qa["mark_art_box"] = tuple(round(v, 1) for v in (art_l, art_t, art_r, art_b))
    qa["mark_min_edge_dist"] = round(min(art_l, art_t, W - art_r, H - art_b), 1)
    qa["mark_to_play_centre"] = round(
        float(np.hypot(max(art_l - CEN_X, 0, CEN_X - art_r), max(art_t - CEN_Y, 0, CEN_Y - art_b))), 1)

    # elliptical radii for the ground recession, scaled with the bird
    ex, ey = 0.58 * phx_size, 0.54 * phx_size

    def knock(x, y):
        """Map dots behind the bird: a tight, alpha-driven knockout of the silhouette itself
        plus a soft elliptical recession around it. The map passes BEHIND the bird; it never
        looks like the bird is BUILT from map dots, and there is no rectangular hole.
        The recession is deliberately SHALLOW (floor 0.30) and tight - at 0.18/0.72 it ate the
        whole of North America and left an orphan cluster of dots at the far left."""
        xi = int(np.clip(x, 0, W - 1))
        yi = int(np.clip(y, 0, H - 1))
        cut = float(np.clip(1.0 - Aq[yi, xi] * 3.2, 0.05, 1.0))
        de = float(np.clip(np.hypot((x - PHX_CX) / ex, (y - PHX_CY) / ey), 0.0, 1.0))
        rec = 0.36 + 0.64 * de ** 1.10
        return cut * rec

    # ---------------------------------------------------------------- 1. base
    tv = np.clip(yy / H, 0, 1)
    img = (hx("0A0A12")[None, None, :] * (1 - tv)[:, :, None] +
           hx("040406")[None, None, :] * tv[:, :, None]).astype(np.float32)
    img = np.broadcast_to(img, (SH, SW, 3)).copy()

    # ---------------------------------------------------------------- 2. ONE light source
    img = add_light(img, radial(BLOOM_X, BLOOM_Y, 470, 360, 2.2), EM500, 0.150 * bloom)
    img = add_light(img, radial(BLOOM_X, BLOOM_Y, 205, 178, 2.6), EM400, 0.062 * bloom)
    img = add_light(img, radial(760, 250, 500, 340, 3.0), TEAL, 0.030)      # quiet fill, right
    img = add_light(img, radial(512, 470, 700, 240, 2.6), EM900, 0.038)     # ground bounce

    # ---------------------------------------------------------------- 3. graticule
    # Meridians + equator only, as sub-pixel numpy hairlines whose alpha ramps to zero at both
    # ends, so no rule has a visible terminus (hard ends read as render seams).
    xg = (np.arange(SW, dtype=np.float32) / SS)
    yg = (np.arange(SH, dtype=np.float32) / SS)
    gy_top, gy_bot = proj(0, 64)[1], proj(0, -42)[1]
    vramp = (smoothstep(gy_top, gy_top + 46, yg) * smoothstep(gy_bot, gy_bot - 46, yg)).astype(np.float32)
    ga = np.zeros((SH, SW), dtype=np.float32)
    for lon in range(-150, 151, 30):
        gx0, _ = proj(lon, 0)
        prof = np.exp(-(((xg - gx0) / 0.62) ** 2)).astype(np.float32)
        ga += prof[None, :] * vramp[:, None] * 0.033
    hramp = (smoothstep(MX0, MX0 + 70, xg) * smoothstep(MX1, MX1 - 70, xg)).astype(np.float32)
    _, yeq = proj(0, 0)
    profy = np.exp(-(((yg - yeq) / 0.62) ** 2)).astype(np.float32)
    ga += profy[:, None] * hramp[None, :] * 0.040
    ga *= radial(500, 225, 700, 330, 1.0)
    # the grid recedes into the bloom rather than running through the mark
    bell = np.clip(np.sqrt(((xx - PHX_CX) / (0.62 * phx_size)) ** 2 +
                           ((yy - PHX_CY) / (0.58 * phx_size)) ** 2), 0, 1) ** 0.9
    ga *= (0.18 + 0.82 * bell).astype(np.float32)
    img = img * (1 - ga[:, :, None]) + np.float32(1.0) * ga[:, :, None]
    del ga, prof, profy, bell

    # ---------------------------------------------------------------- 4. dot-matrix world map
    lay = new_layer()
    d = ImageDraw.Draw(lay)
    dots = []
    lat = 84.0
    row = 0
    while lat > -58.0:
        off = (MAP_PITCH_DEG / 2.0) if (row % 2) else 0.0   # brick offset kills moire
        lon = -179.0 + off
        while lon < 180.0:
            c = land_cov(lon, lat)
            if c > 0.0:
                x, y = proj(lon, lat)
                dots.append((x, y, c, lat))
            lon += MAP_PITCH_DEG
        lat -= MAP_PITCH_DEG
        row += 1

    node_pts = list(NODE_XY.values())
    for (x, y, c, la) in dots:
        # poles dissolve into the void -> no straight arctic band, ever
        latfade = float(smoothstep(70.0, 52.0, la) if la > 0 else smoothstep(-50.0, -34.0, la))
        if latfade <= 0.01:
            continue
        k = key(x, y)
        grade = 0.46 + 0.72 * k
        edge = float(np.clip(1.0 - np.hypot((x - 512) / 700.0, (y - 235) / 430.0) * 0.78, 0, 1)) ** 1.1
        edge *= float(smoothstep(96.0, 208.0, x)) * float(smoothstep(972.0, 916.0, x))
        a = 1.00 * latfade * grade * edge * (0.08 + 0.92 * c ** 1.35)
        a *= knock(x, y)
        a *= calm(x, y, 0.44)
        a *= type_guard(x, y)
        a *= word_guard(x, y)
        if a < 0.018:
            continue
        r = (0.62 + 0.62 * c ** 0.55 + 0.24 * k) * (MAP_PITCH / 7.367)
        nd = min(np.hypot(x - nx, y - ny) for (nx, ny) in node_pts)
        tint = float(np.clip(1.0 - nd / 88.0, 0.0, 1.0)) ** 1.7
        col = SLATE * (1 - tint) + EM400 * tint
        col = col * (0.70 + 0.52 * k)
        dot(d, x, y, r, col, min(a, 0.86))
    img = composite(img, lay)
    del lay

    # ---------------------------------------------------------------- 5. arcs (node -> node)
    arc_lay = new_layer()
    da = ImageDraw.Draw(arc_lay)
    for (a_n, b_n, bulge) in ARCS:
        xs, ys, ts = bezier(NODE_XY[a_n], NODE_XY[b_n], bulge)
        mind = min(float(np.hypot(ax - CEN_X, ay - CEN_Y)) for ax, ay in zip(xs, ys))
        qa.setdefault("arc_mind", {})[a_n + "->" + b_n] = round(mind, 1)
        for i in range(len(ts) - 1):
            t = float((ts[i] + ts[i + 1]) * 0.5)
            mx = (xs[i] + xs[i + 1]) * 0.5
            my = (ys[i] + ys[i + 1]) * 0.5
            taper = 0.38 + 0.62 * float(np.sin(np.pi * t)) ** 0.62   # ARRIVES at both nodes
            grade = 0.55 + 0.55 * key(mx, my, 560.0)
            a = 0.62 * taper * grade * calm(mx, my, 0.40) * type_guard(mx, my)
            col = EM300 * (1.0 - t) * 0.55 + EM400 * 0.55 + TEAL * (t * 0.35)
            wdt = max(1, int(round((0.75 + 0.75 * taper) * SS)))
            da.line([(xs[i] * SS, ys[i] * SS), (xs[i + 1] * SS, ys[i + 1] * SS)],
                    fill=rgba(col, a), width=wdt)
    arc_a = np.asarray(arc_lay, dtype=np.float32)[:, :, 3].astype(np.uint8)
    img = add_light(img, blur_alpha(arc_a, 3.2), EM400, 0.20)      # tight bloom
    img = add_light(img, blur_alpha(arc_a, 16.0), EM600, 0.16)     # volumetric haze
    img = composite(img, arc_lay)
    del arc_lay, arc_a

    # ---------------------------------------------------------------- 6. nodes
    for (nname, _lo, _la, wgt) in NODES:
        x, y = NODE_XY[nname]
        cm = calm(x, y, 0.55)
        img = add_light(img, radial(x, y, 46 * wgt, 46 * wgt, 2.6), EM400, 0.32 * wgt * cm)
        img = add_light(img, radial(x, y, 15 * wgt, 15 * wgt, 1.7), EM300, 0.48 * wgt * cm)

    node_lay = new_layer()
    dn = ImageDraw.Draw(node_lay)
    for (nname, _lo, _la, wgt) in NODES:
        x, y = NODE_XY[nname]
        cm = calm(x, y, 0.55)
        cx, cy = x * SS, y * SS
        rr = (10.0 * wgt) * SS * 0.5
        dn.ellipse([cx - rr, cy - rr, cx + rr, cy + rr],
                   outline=rgba(EM400, 0.42 * wgt * cm), width=max(1, int(0.9 * SS)))
        rr2 = (5.4 * wgt) * SS * 0.5
        dn.ellipse([cx - rr2, cy - rr2, cx + rr2, cy + rr2], fill=rgba(EM300, 0.32 * wgt * cm))
        rc = (2.9 * wgt) * SS * 0.5
        dn.ellipse([cx - rc, cy - rc, cx + rc, cy + rc], fill=rgba(hx("E6FFF3"), 0.97 * cm))
        qa.setdefault("node_d", {})[nname] = round(float(np.hypot(x - CEN_X, y - CEN_Y)), 1)
    img = composite(img, node_lay)
    del node_lay

    # packets riding the arcs (evidence, not decoration) - all far from the reserve
    pk = new_layer()
    dpk = ImageDraw.Draw(pk)
    for (a_n, b_n, bulge, tp) in [("mumbai", "singapore", 0.30, 0.52),
                                  ("singapore", "tokyo", 0.28, 0.44),
                                  ("london", "mumbai", 0.30, 0.62)]:
        xs, ys, _ts = bezier(NODE_XY[a_n], NODE_XY[b_n], bulge)
        i = int(tp * (len(xs) - 1))
        x, y = float(xs[i]), float(ys[i])
        img = add_light(img, radial(x, y, 13, 13, 2.0), EM300, 0.34)
        dot(dpk, x, y, 0.95, hx("EAFFF7"), 0.92)
    img = composite(img, pk)
    del pk

    # ---------------------------------------------------------------- 7. THE PHOENIX (sharp)
    # (a) contact shadow: a THIN dark band immediately outside the silhouette. Order matters -
    #     shadow first, bloom on top, so the sequence outward from the bird is
    #     white edge -> dark rim -> emerald glow. That is what detaches it from the plate.
    #     Kept small (0.17): at 0.30/r22 it became a grey cloud that swallowed the bloom.
    near = blur_alpha(A8, 4.0)
    wide = blur_alpha(A8, 15.0)
    ring = np.clip(wide * 1.15 - near * 0.62, 0.0, 1.0)
    img = img * (1.0 - (0.17 * ring)[:, :, None])
    del near, wide, ring

    # (b) bloom BEHIND the bird: a tight emerald rim, a mid bed, a wide halo. The bird is lit
    #     FROM BEHIND by the scene's single light source, which lives right behind its chest.
    img = add_light(img, blur_alpha(A8, 8.0), EM400, 0.21 * bloom)
    img = add_light(img, blur_alpha(A8, 28.0), EM500, 0.16 * bloom)
    img = add_light(img, blur_alpha(A8, 70.0), EM600, 0.20 * bloom)

    # (c) the mark itself - straight alpha composite of the artwork, untouched colour.
    #     Its native white-head -> emerald-body gradient is the whole point; do not tint it.
    a = MSS[:, :, 3:4]
    img = img * (1.0 - a) + MSS[:, :, :3] * a
    del MSS, a, A8

    # ---------------------------------------------------------------- 8. sheen + scrims
    u = (xx * 0.90 + yy * 0.44)
    sheen = np.exp(-(((u - 430.0) / 210.0) ** 2)).astype(np.float32)
    img = add_light(img, sheen * 0.5, hx("BFF3E2"), 0.026)

    ta = ((1.0 - np.clip(yy / 118.0, 0, 1)) ** 2.0) * 0.50
    img = img * (1 - ta[:, :, None]) + SURFACE0[None, None, :] * ta[:, :, None]

    ba = (smoothstep(336.0, 412.0, yy) ** 1.15) * 0.78
    img = img * (1 - ba[:, :, None]) + hx("030305")[None, None, :] * ba[:, :, None]

    # ---------------------------------------------------------------- 9. vignette
    vd = np.sqrt(((xx - 512.0) / 580.0) ** 2 + ((yy - 250.0) / 310.0) ** 2)
    img = img * (1.0 - 0.40 * np.clip(vd - 0.52, 0.0, 1.0) ** 1.5)[:, :, None]

    # ---------------------------------------------------------------- 10. type
    f_head = ImageFont.truetype(os.path.join(FONTS, "InterDisplay-Bold.ttf"), int(56 * SS))
    f_word = ImageFont.truetype(os.path.join(FONTS, "InterDisplay-SemiBold.ttf"), int(17 * SS))
    f_chip = ImageFont.truetype(os.path.join(FONTS, "Inter-SemiBold.ttf"), int(15.2 * SS))

    # -- wordmark: BIRDO (white) + VPN (white->mint gradient), ink-top y=72
    wm = Line([("BIRDO ", f_word, "w"), ("VPN", f_word, "v")], tr=3.2)
    wmm = wm.place(512.0, 72.0)
    qa["wordmark_box"] = tuple(round(v, 1) for v in wm.bbox_final)

    gyv = np.clip((yy - 68.0) / 20.0, 0, 1)
    vpn_grad = (hx("F2FFFA")[None, None, :] * (1 - gyv)[:, :, None] +
                hx("5FE3C0")[None, None, :] * gyv[:, :, None]).astype(np.float32)
    img = paint(img, wmm["w"], hx("FFFFFF"), 0.94)
    img = paint(img, wmm["v"], np.broadcast_to(vpn_grad, (SH, SW, 3)).copy(), 1.0)

    # -- flanking hairlines, alpha-ramped away from the wordmark
    wl, wt, wr, wb = wm.bbox_final
    ruleY = (wt + wb) / 2.0 + 0.5
    rl = Image.new("L", (SW, SH), 0)
    dr = ImageDraw.Draw(rl)
    for (rx0, _rx1, flip) in ((wl - 92, wl - 22, True), (wr + 22, wr + 92, False)):
        n = 70
        for i in range(n * SS):
            fx = rx0 + i / SS
            t = i / float(n * SS)
            av = t if flip else (1.0 - t)
            dr.rectangle([fx * SS, ruleY * SS - SS * 0.25, fx * SS + SS, ruleY * SS + SS * 0.25],
                         fill=int(np.clip(av, 0, 1) * 78))
    img = paint(img, rl, hx("FFFFFF"), 1.0)
    del rl

    # -- headline, ink-top y=364. Both full stops emerald-400 (the brand beat).
    hl = Line([("Encrypted", f_head, "h"), (".", f_head, "d"),
               (" Everywhere", f_head, "h"), (".", f_head, "d")], tr=-1.40)
    hlm = hl.place(512.0, 364.0)
    qa["headline_box"] = tuple(round(v, 1) for v in hl.bbox_final)

    hg = np.clip((yy - 364.0) / 56.0, 0, 1)
    head_grad = (hx("FFFFFF")[None, None, :] * (1 - hg)[:, :, None] +
                 hx("DCE7E3")[None, None, :] * hg[:, :, None]).astype(np.float32)
    img = paint(img, hlm["h"], np.broadcast_to(head_grad, (SH, SW, 3)).copy(), 1.0)
    img = paint(img, hlm["d"], EM400, 1.0)

    # -- chip line: tracked caps, emerald middots
    cl = Line([("POST-QUANTUM ENCRYPTION", f_chip, "c"), ("  ·  ", f_chip, "s"),
               ("KILL SWITCH", f_chip, "c"), ("  ·  ", f_chip, "s"),
               ("NO ACTIVITY LOGS", f_chip, "c")], tr=1.5)
    clm = cl.place(512.0, 424.0)
    qa["chips_box"] = tuple(round(v, 1) for v in cl.bbox_final)
    img = paint(img, clm["c"], hx("FFFFFF"), 0.78)
    img = paint(img, clm["s"], EM400, 0.95)

    # ---------------------------------------------------------------- 11. downsample (float)
    chans = [np.asarray(Image.fromarray(np.ascontiguousarray(img[:, :, c]), mode="F")
                        .resize((W, H), Image.LANCZOS), dtype=np.float32) for c in range(3)]
    small = np.stack(chans, axis=2)
    del img, chans

    # ---------------------------------------------------------------- 12. grain at FINAL res
    rng = np.random.default_rng(5)
    small = (small
             + rng.normal(0.0, 1.7 / 255.0, size=(H, W)).astype(np.float32)[:, :, None]
             + rng.normal(0.0, 0.6 / 255.0, size=(H, W, 3)).astype(np.float32))

    out = (np.clip(small, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)
    im = Image.fromarray(out, "RGB").convert("RGB")
    im.save(out_png, "PNG", optimize=True)
    im.resize((320, 156), Image.LANCZOS).save(out_thm, "PNG")

    # ---------------------------------------------------------------- 13. QA
    arr = np.asarray(im, dtype=np.float32) / 255.0
    lum = 0.2126 * arr[:, :, 0] + 0.7152 * arr[:, :, 1] + 0.0722 * arr[:, :, 2]
    Y, X = np.mgrid[0:H, 0:W]
    circ = ((X - 512) ** 2 + (Y - 250) ** 2) <= PLAY_R ** 2
    qa["play_reserve_max_lum"] = round(float(lum[circ].max()), 3)
    qa["play_reserve_max_255"] = int((np.asarray(im).max(axis=2))[circ].max())
    qa["global_max_lum"] = round(float(lum.max()), 3)
    bl, bt, br, bb2 = (int(art_l), int(art_t), int(art_r), int(art_b))
    qa["mark_peak_lum"] = round(float(lum[bt:bb2, bl:br].max()), 3)
    qa["headline_peak_lum"] = round(float(lum[364:420, 200:824].max()), 3)
    qa["size_mode"] = (im.size, im.mode)
    qa["bytes"] = os.path.getsize(out_png)
    for k2, v in qa.items():
        print("  %-22s %s" % (k2, v))
    print("saved:", out_png)
    print()


if __name__ == "__main__":
    # -> feature-graphic-1024x500.png, the asset Play and F-Droid actually ship.
    build("graphic", 200.0, bloom=1.00)
    # The +15% phoenix variant, kept reproducible but not shipped. `--variants` to render it.
    if "--variants" in sys.argv:
        build("graphic-big", 230.0, bloom=1.20)
    with open(os.path.join(HERE, "alt-text.txt"), "w", encoding="utf-8") as fh:
        fh.write(ALT_TEXT + "\n")
