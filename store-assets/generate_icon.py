"""
Birdo VPN app icon - "INK-TRUE"  (the shipping master).

WHAT THIS IS
------------
ink-final's ENGINEERING applied to the TRUE, UNMODIFIED brand mark.

The refinement pass ("ink-final") is beautiful and it is NOT THE LOGO. It ran the
phoenix through OPEN -> close-by-reconstruction -> taper-clearance -> duty-cycle carve
-> per-component curvature smoothing. That pipeline detached the wing's layered feathers
from the body into three free-floating quills, deleted the wing/body overlap, and
reshaped the head. Those are not icon-craft operations, they are a LOGO REDRAW.

So: KEEP the engineering, DISCARD every silhouette operator.

  KEPT from ink-final
    * composite + resolve in LINEAR light with an EXACT BOX filter. A LANCZOS resolve in
      gamma space overshoots at the bird's high-contrast edge and prints a fake dark
      outline that reads as a baked drop shadow.
    * the plate: neutral near-black, ONE soft key from the upper-left (#1B1B26 -> #08080C),
      ramped in linear light, low-frequency atmosphere, dithered at final res. No bevel,
      no gloss, no border, no baked shadow, no emerald bloom (the BIRD carries the brand).
    * single-key FORM shading on the bird (+-5%), so it sits IN the light.
    * the MEASURED optical-centring search: minimise the spread of the mark's clearance
      to the circular mask across 8 sectors, searched around a luminance-weighted
      perceptual anchor. Not a guessed offset.
    * REAL ANDROID ADAPTIVE LAYERS (432px): opaque full-bleed background, a RE-RENDERED
      (not cropped) foreground whose ink stays inside the 66dp/132px safe circle, and a
      monochrome layer for Android 13+ themed icons. The app currently ships legacy
      mipmaps only, so on Android 8+ the launcher shrinks the icon onto a WHITE plate -
      reintroducing, from the platform, the exact "sticker of an icon" look this rebuild
      exists to kill. This is the real blocker.

  DISCARDED from ink-final
    * ALL of it. No OPEN, no reconstruction, no taper, no carve, no curvature smoothing,
      no tip tapering, no component separation, no eye re-cut, not even the dust-speck
      cull (two 5x4 specks - they are in the brand mark, so they are in the icon).
      The silhouette is mark.png's ALPHA, scaled uniformly with LANCZOS. Nothing else.
      Not even ink/ink-final's `(a-0.5)*2.6+0.5` edge re-crisp: they needed it because
      blur+threshold had smeared their alpha; the true mark's alpha already carries a
      clean ~1.1px AA edge, and re-crisping it to ~0.4px would ALIAS the wing slits at
      the exact sizes we are trying to protect. Measured, not assumed - see EDGE_CRISP.

    Consequence: the wing feathers overlap the body exactly as they do in mark.png, the
    head is mark.png's head, the quills sweep into the bowl of the S (so the belly bay is
    a CHANNEL, not the equant void `ink`'s amputation left behind), and the blade tips
    taper to points instead of `ink`'s stepped "tabs" - all for free, because they are
    what the artwork already says.

THE LEVERS THAT REMAIN (the only ones the brief allows)
    scale, optical position, plate contrast, resolve quality. That is enough: see the
    proportion study (--study) and the 48px verdict printed by --audit.

Pillow + numpy only.
"""
import os
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.join(HERE, "fonts")                 # vendored Inter (SIL OFL - see fonts/LICENSE.txt)
MARK = os.path.join(HERE, "brand-mark-1024.png")    # the emerald phoenix, transparent


# ----------------------------------------------------------------------------- config

ID = "ink-true"

S = 4096              # supersample canvas
OUT = 1024            # final master
SS = S // OUT         # 4x supersample

# --- placement (chosen by the proportion study, see PROPORTION STUDY below) ---
BBOX_H_FRAC = 0.72    # phoenix bbox height as a fraction of the tile
SEARCH_R = 26.0       # sector-spread optimum is sought within this of the perceptual anchor
BETA = 0.62           # perceptual anchor: 0 = bbox-centre, 1 = luminance-weighted centroid
W_FLOOR = 0.35        # weight of a fully dark pixel vs a white one (1.0)
MIN_MARGIN = 10.0     # px of clearance the ink must keep from the circle mask, always

# --- silhouette fidelity ---
EDGE_CRISP = 1.0      # 1.0 == OFF. The one knob that could touch the silhouette; it is
                      # pinned at identity on purpose. Any value > 1 preserves the 0.5
                      # level set (so IoU would not even notice) but narrows the AA band,
                      # and the wing slits at 48px are exactly where a narrowed AA band
                      # turns into stair-stepping. Left in, at identity, so the next
                      # person can see that it was considered and rejected.

# --- plate: near-black NEUTRAL. one believable key, ramped in LINEAR light. ---
PLATE_KEY = (27, 27, 38)      # #1B1B26  upper-left key
PLATE_FAR = (8, 8, 12)        # #08080C  floor. NOT L5: the tile's own boundary has to
                              # survive a pure-black wallpaper.
LIGHT_CX, LIGHT_CY = 0.30, 0.24
LIGHT_R = 1.22                # falloff radius in tile units (large -> no visible ring)
ATMOS = 0.018                 # low-frequency atmosphere, +-1.8% (kills the CG look)
FORM = 0.05                   # bird form shading from the SAME key, +-5%
GRAIN = 1.6                   # +-levels, luminance-only, dithered at final res

# --- phoenix fill: near-white head -> emerald body/wing.
# The axis runs head (top-right) -> wing tips (bottom-left), so the white stays ON THE
# HEAD instead of flooding the upper half. This diagonal is what makes the icon read
# EMERALD rather than white.
# The dark end is clamped at emerald-500 (the PRIMARY), NOT emerald-600: on the TRUE
# mark the wing's quills are 4-20px hairlines and they are the most at-risk features in
# the whole icon - at 48px they are averaged hard against a near-black plate. Darkening
# them is exactly backwards.
GRAD_DIR = (-0.66, 0.75)
STOPS = [
    (0.00, (255, 255, 255)),
    (0.06, (242, 255, 251)),
    (0.15, (188, 244, 222)),
    (0.27, (110, 231, 183)),  # emerald-300
    (0.46, (52, 211, 153)),   # emerald-400
    (0.74, (16, 185, 129)),   # emerald-500 PRIMARY
    (1.00, (14, 178, 124)),
]

ROUND_R_FRAC = 0.224          # Android/Apple-ish squircle radius
SRGB = np.array([0.2126, 0.7152, 0.0722], np.float32)

ADAPT_N = 432                 # 108dp @ xxxhdpi
ADAPT_SAFE_R = 132.0          # 66dp safe-circle radius, in px
ADAPT_INK_R = 128.0           # target max ink radius (4px of headroom inside the circle)


# ----------------------------------------------------------------------------- colour
def to_lin(c):
    c = np.asarray(c, np.float32) / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4).astype(np.float32)


def to_srgb(l):
    l = np.clip(l, 0.0, 1.0)
    return (np.where(l <= 0.0031308, l * 12.92,
                     1.055 * np.power(l, 1.0 / 2.4) - 0.055) * 255.0).astype(np.float32)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def fresize(a, size, resample=Image.LANCZOS):
    return np.asarray(Image.fromarray(a.astype(np.float32), "F").resize(size, resample),
                      np.float32)


def box_resolve(a, n):
    """EXACT box (area) resolve of an ss*n square supersample. This is the CORRECT filter
    for a supersample and the only safe one: a LANCZOS resolve in GAMMA space overshoots
    at the bird's high-contrast edge and prints a fake dark outline that reads as a baked
    drop shadow - which the brief forbids outright."""
    k = a.shape[0] // n
    return a.reshape(n, k, n, k).mean(axis=(1, 3))


def ramp_lin(t):
    """the fill ramp, evaluated in LINEAR light."""
    ps = np.array([s[0] for s in STOPS], np.float32)
    cs = np.stack([to_lin(np.array(s[1], np.float32)) for s in STOPS])
    return np.stack([np.interp(t, ps, cs[:, c]).astype(np.float32) for c in range(3)], -1)


def gblur(a, sigma):
    """separable gaussian, float32, edge-clamped."""
    r = int(np.ceil(sigma * 3.5))
    x = np.arange(-r, r + 1, dtype=np.float32)
    k = np.exp(-(x * x) / (2.0 * sigma * sigma))
    k /= k.sum()
    out = a.astype(np.float32)
    for axis in (1, 0):
        p = np.pad(out, [(r, r) if ax == axis else (0, 0) for ax in (0, 1)], mode="edge")
        acc = np.zeros_like(out)
        for i, w in enumerate(k):
            if axis == 1:
                acc += w * p[:, i:i + out.shape[1]]
            else:
                acc += w * p[i:i + out.shape[0], :]
        out = acc
    return out


# ----------------------------------------------------------------------------- silhouette
def load_mark():
    """THE WHOLE SILHOUETTE STAGE. mark.png's alpha, verbatim.

    Nothing is opened, closed, reconstructed, tapered, carved, smoothed, thickened or
    culled. If it is in the brand mark it is in the icon: the overlapping feathers, the
    hairline quills, the dotted brush dashes, the two dust specks, the head.
    """
    return np.asarray(Image.open(MARK).convert("RGBA"), np.float32)[:, :, 3] / 255.0


def enclosed_holes(b):
    """background pixels not reachable from the border == the mark's enclosed counters
    (the EYE). PILLOW LANDMINE: ImageDraw.floodfill silently no-ops on an image made by
    Image.fromarray (read-only shared buffer) - .copy() forces an owned buffer."""
    inv = Image.fromarray(((~b) * 255).astype(np.uint8), "L").copy()
    ImageDraw.floodfill(inv, (0, 0), 128)
    return np.array(inv) == 255


def components(b, minpx=1):
    """connected components (4-conn), largest first. Iterative flood fill on a bool grid."""
    lab = np.zeros(b.shape, np.int32)
    out, n = [], 0
    idx = np.argwhere(b)
    for sy, sx in idx:
        if lab[sy, sx]:
            continue
        n += 1
        stack = [(sy, sx)]
        lab[sy, sx] = n
        cells = []
        while stack:
            y, x = stack.pop()
            cells.append((y, x))
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                yy, xx = y + dy, x + dx
                if 0 <= yy < b.shape[0] and 0 <= xx < b.shape[1] and b[yy, xx] and not lab[yy, xx]:
                    lab[yy, xx] = n
                    stack.append((yy, xx))
        if len(cells) >= minpx:
            m = np.zeros(b.shape, bool)
            c = np.array(cells)
            m[c[:, 0], c[:, 1]] = True
            out.append((len(cells), m))
    out.sort(key=lambda t: -t[0])
    return out


# ----------------------------------------------------------------------------- placement
def grad_t(shape, cxy, ink):
    """gradient parameter t in [0,1], defined RELATIVE to the bird's own ink extent along
    GRAD_DIR -> identical in mark space and tile space, so it survives translation."""
    dx, dy = GRAD_DIR
    n = (dx * dx + dy * dy) ** 0.5
    dx, dy = dx / n, dy / n
    yy, xx = np.mgrid[0:shape[0], 0:shape[1]].astype(np.float32)
    proj = (xx - cxy[0]) * dx + (yy - cxy[1]) * dy
    p = proj[ink]
    lo, hi = np.percentile(p, 0.6), np.percentile(p, 99.4)
    return np.clip((proj - lo) / (hi - lo), 0.0, 1.0)


def hull(pts):
    """monotone-chain convex hull. The per-sector MAX radius is always attained on the
    hull, so the centring search only ever needs these ~40 points, not 300k."""
    p = sorted(map(tuple, pts))
    def half(ps):
        h = []
        for q in ps:
            while len(h) >= 2 and (h[-1][0] - h[-2][0]) * (q[1] - h[-2][1]) \
                    - (h[-1][1] - h[-2][1]) * (q[0] - h[-2][0]) <= 0:
                h.pop()
            h.append(q)
        return h[:-1]
    return np.array(half(p) + half(p[::-1]), np.float64)


def ink_hull(sil):
    """hull of the ink's boundary pixels, in MARK space (x, y)."""
    b = sil > 0.5
    e = b.copy()
    e[1:, :] &= b[:-1, :]
    e[:-1, :] &= b[1:, :]
    e[:, 1:] &= b[:, :-1]
    e[:, :-1] &= b[:, 1:]
    ep = np.argwhere(b ^ e)                       # boundary pixels
    return hull(np.stack([ep[:, 1], ep[:, 0]], 1))


def sector_spread(hp, cx, cy, R, nsec=8):
    """The optical-centring objective, and the only MEASURED one available: the SPREAD of
    the mark's clearance to the circular mask across eight 45-degree sectors. Minimising
    it directly optimises the thing that actually matters - looking centred under a circle
    mask - instead of guessing a blend constant between bbox-centre and alpha-centroid."""
    dx, dy = hp[:, 0] - cx, hp[:, 1] - cy
    r = np.sqrt(dx * dx + dy * dy)
    sec = ((np.arctan2(dy, dx) + 2 * np.pi) % (2 * np.pi)) // (2 * np.pi / nsec)
    m = np.full(nsec, -1.0)
    for i in range(nsec):
        s = sec == i
        if s.any():
            m[i] = r[s].max()
    ok = m > 0
    marg = R - m[ok]
    return float(marg.max() - marg.min()), float(marg.min()), marg


def place(sil, h_frac=BBOX_H_FRAC, verbose=False):
    """uniform scale + measured optical centring. Returns (scale, anchor_in_mark_space)."""
    ink = sil > 0.5
    ys, xs = np.where(ink)
    x0, y0, x1, y1 = xs.min(), ys.min(), xs.max() + 1, ys.max() + 1
    bc = ((x0 + x1) / 2.0, (y0 + y1) / 2.0)

    # perceptual anchor: the eye weights the WHITE HEAD far more than the dark wing tips,
    # so a plain alpha centroid under-corrects. Weight alpha by the luminance of the fill
    # the pixel is actually going to receive.
    t = grad_t(sil.shape, bc, ink)
    lum = (ramp_lin(t) @ SRGB) ** (1 / 2.2)
    w = sil * (W_FLOOR + (1.0 - W_FLOOR) * lum)
    tot = w.sum()
    pc = (float((w * np.arange(sil.shape[1])[None, :]).sum() / tot),
          float((w * np.arange(sil.shape[0])[:, None]).sum() / tot))
    pa = (bc[0] + BETA * (pc[0] - bc[0]), bc[1] + BETA * (pc[1] - bc[1]))

    s = h_frac * OUT / (y1 - y0)                          # mark px -> tile px
    hp = ink_hull(sil) * s                                # hull in tile px
    base = (pa[0] * s, pa[1] * s)

    best = None
    for dy in np.arange(-SEARCH_R, SEARCH_R + 0.01, 1.0):
        for dx in np.arange(-SEARCH_R, SEARCH_R + 0.01, 1.0):
            if dx * dx + dy * dy > SEARCH_R ** 2:
                continue                                  # stay near the perceptual anchor
            cx, cy = base[0] - dx, base[1] - dy           # tile centre, in hull coords
            sp, mn, _ = sector_spread(hp, cx, cy, OUT / 2.0)
            if mn < MIN_MARGIN:                           # never let the circle clip
                continue
            if best is None or sp < best[0]:
                best = (sp, dx, dy, mn)
    if best is None:
        raise SystemExit("no placement keeps the ink inside the circle at h_frac=%.2f" % h_frac)
    sp, dx, dy, mn = best
    anchor = (pa[0] - dx / s, pa[1] - dy / s)             # back to mark space
    geo = dict(bbox_h_frac=h_frac,
               bbox_c=(round(bc[0], 1), round(bc[1], 1)),
               percep_c=(round(pc[0], 1), round(pc[1], 1)),
               percep_anchor=(round(pa[0], 1), round(pa[1], 1)),
               sector_nudge_px=(round(-dx, 1), round(-dy, 1)),
               sector_spread_px=round(sp, 1),
               circle_clearance_px=round(mn, 1))
    if verbose:
        print("  place:", geo)
    return s, anchor, geo


# ----------------------------------------------------------------------------- plate
def plate_lin(size):
    """near-black neutral plate, ONE key, ramped in LINEAR light. Returns (linRGB, key)."""
    key = to_lin(np.array(PLATE_KEY, np.float32))
    far = to_lin(np.array(PLATE_FAR, np.float32))
    gy, gx = np.mgrid[0:size, 0:size].astype(np.float32)
    gx = (gx + 0.5) / size
    gy = (gy + 0.5) / size
    d = np.sqrt((gx - LIGHT_CX) ** 2 + (gy - LIGHT_CY) ** 2) / LIGHT_R
    lit = np.clip(1.0 - d, 0.0, 1.0)
    lit = lit * lit * (3.0 - 2.0 * lit)          # smooth: no visible falloff ring

    rng = np.random.default_rng(7013)            # low-frequency atmosphere: a believable
    n = gblur(rng.normal(0, 1, (24, 24)).astype(np.float32), 1.6)   # plate is never a
    n /= max(np.abs(n).max(), 1e-6)                                  # perfect gradient
    atm = 1.0 + ATMOS * fresize(n, (size, size), Image.BICUBIC)

    p = np.empty((size, size, 3), np.float32)
    for c in range(3):
        p[..., c] = (far[c] + (key[c] - far[c]) * lit) * atm
    return np.clip(p, 0.0, 1.0), lit


# ----------------------------------------------------------------------------- compose
def mark_geometry(sil, s, anchor, size, ss):
    """The mark's placement, in SUPERSAMPLE px, SNAPPED TO THE SUPERSAMPLE GRID.

    The snap is what makes fidelity MEASURABLE. Unsnapped, the mark lands on a canvas
    offset that is not a multiple of ss, so the box resolve shifts it by a fraction of an
    output pixel relative to any reference you could build at output resolution - and the
    IoU gate then measures that sub-pixel shift instead of measuring the silhouette.
    Measured: unsnapped IoU 0.99305, snapped 0.99991, and the SILHOUETTE IS IDENTICAL in
    both. The snap costs at most half an output pixel of placement, which is far below the
    1px granularity of the centring search itself.
    """
    to_canvas = s * (size / float(OUT))                 # mark px -> output px
    n = int(round(sil.shape[0] * to_canvas))            # mark size in OUTPUT px
    ms = n * ss                                         # ...and in supersample px
    k = ms / float(sil.shape[0])
    Sz = size * ss
    ox = int(round((Sz / 2.0 - anchor[0] * k) / ss)) * ss
    oy = int(round((Sz / 2.0 - anchor[1] * k) / ss)) * ss
    return n, ms, k, ox, oy, Sz


def place_bird(sil, s, anchor, size, ss):
    """uniformly scale the TRUE alpha with LANCZOS and drop it on the supersample canvas.
    This function is the ENTIRE geometry stage. There is no other operator anywhere."""
    n, ms, k, ox, oy, Sz = mark_geometry(sil, s, anchor, size, ss)
    big = np.clip(fresize(sil, (ms, ms), Image.LANCZOS), 0.0, 1.0)   # <- the ONLY operator
    if EDGE_CRISP != 1.0:
        big = np.clip((big - 0.5) * EDGE_CRISP + 0.5, 0.0, 1.0)

    bird = np.zeros((Sz, Sz), np.float32)
    sx0, sy0 = max(0, ox), max(0, oy)
    sx1, sy1 = min(Sz, ox + ms), min(Sz, oy + ms)
    bird[sy0:sy1, sx0:sx1] = big[sy0 - oy:sy1 - oy, sx0 - ox:sx1 - ox]
    del big
    return bird


def compose(sil, s, anchor, size=OUT, ss=SS, mark_only=False, seed=20260713):
    """returns (rgb_uint8 @size, bird_alpha @size, ink_xs_ys). All compositing in LINEAR
    light, resolved with an EXACT BOX filter, dithered afterwards at final res."""
    Sz = size * ss
    bird = place_bird(sil, s, anchor, size, ss)

    m = bird > 0.5
    ys, xs = np.where(m)
    bbc = ((xs.min() + xs.max() + 1) / 2.0, (ys.min() + ys.max() + 1) / 2.0)
    fill = ramp_lin(grad_t((Sz, Sz), bbc, m))           # linear RGB

    plate, lit = plate_lin(Sz)
    # single-key FORM shading: the bird is lit by the SAME key as the plate, so it sits IN
    # the light instead of being pasted on top of it. +-5%. No gloss, no bevel.
    fill *= (1.0 + FORM * (2.0 * lit - 1.0))[..., None]
    np.clip(fill, 0.0, 1.0, out=fill)

    if mark_only:                                       # adaptive FOREGROUND: bird alone
        del plate, lit
        a = box_resolve(bird, size)                     # premultiplied resolve, in linear
        rgbf = np.stack([box_resolve(fill[..., c] * bird, size) for c in range(3)], -1)
        del fill, bird
        rgbf = np.where(a[..., None] > 1e-4, rgbf / np.maximum(a[..., None], 1e-4), 0.0)
        return to_srgb(rgbf), a, (xs, ys)
    del lit

    out = np.empty((size, size, 3), np.float32)
    for c in range(3):
        comp = plate[..., c] * (1.0 - bird) + fill[..., c] * bird
        out[..., c] = box_resolve(comp, size)           # <- resolve in LINEAR light
    del plate, fill
    a = box_resolve(bird, size)
    del bird

    rgb = to_srgb(out)
    rng = np.random.default_rng(seed)
    rgb = np.clip(rgb + rng.uniform(-GRAIN, GRAIN, (size, size, 1)).astype(np.float32),
                  0.0, 255.0)
    return np.rint(rgb).astype(np.uint8), a, (xs, ys)


# ----------------------------------------------------------------------------- masks
def rounded_alpha(size, ss, rfrac):
    Sz = size * ss
    r = rfrac * Sz
    yy, xx = np.mgrid[0:Sz, 0:Sz].astype(np.float32)
    xx = np.abs(xx + 0.5 - Sz / 2.0)
    yy = np.abs(yy + 0.5 - Sz / 2.0)
    ex = np.maximum(xx - (Sz / 2.0 - r), 0.0)
    ey = np.maximum(yy - (Sz / 2.0 - r), 0.0)
    d = np.sqrt(ex * ex + ey * ey)
    # band = +-0.5px, NOT +-0.7: a pixel centred 0.5px inside a straight edge is 100%
    # covered; a wider band renders the tile's flat edges at alpha ~251 and leaks the
    # launcher background through them. Corner AA comes from supersample + BOX resolve.
    mk = 1.0 - smoothstep(r - 0.5, r + 0.5, d)
    return np.rint(np.clip(box_resolve(mk, size) * 255.0, 0, 255)).astype(np.uint8)


def circle_alpha(size, ss):
    Sz = size * ss
    r = Sz / 2.0
    yy, xx = np.mgrid[0:Sz, 0:Sz].astype(np.float32)
    d = np.sqrt((xx + 0.5 - r) ** 2 + (yy + 0.5 - r) ** 2)
    mk = 1.0 - smoothstep(r - 0.5, r + 0.5, d)
    return np.rint(np.clip(box_resolve(mk, size) * 255.0, 0, 255)).astype(np.uint8)


# ----------------------------------------------------------------------------- adaptive
def min_enclosing_centre(hp):
    """centre of the minimum enclosing circle of the hull (local descent on max radius).
    It maximises the scale the mark can reach inside a circle - so it sets the CEILING on
    the adaptive foreground's size. It is NOT used as the anchor: see place_adaptive."""
    c = hp.mean(0)
    for step in (32.0, 8.0, 2.0, 0.5, 0.125):
        improved = True
        while improved:
            improved = False
            r0 = np.sqrt(((hp - c) ** 2).sum(1)).max()
            for d in ((step, 0), (-step, 0), (0, step), (0, -step)):
                cc = c + np.array(d, np.float64)
                r1 = np.sqrt(((hp - cc) ** 2).sum(1)).max()
                if r1 < r0 - 1e-9:
                    c, r0, improved = cc, r1, True
    return c, float(np.sqrt(((hp - c) ** 2).sum(1)).max())


def place_adaptive(sil):
    """Scale + optical centring for the adaptive FOREGROUND.

    First cut used the min-enclosing-circle centre, on the argument that it lets the mark
    be biggest. It does - and it looks WRONG, because the MEC is a purely geometric anchor
    that knows nothing about where the mark's visual weight is (the white head), so under
    the circle and squircle masks the bird sat noticeably left and high.

    So: the SAME measured objective as the square masters - minimise the 8-sector spread of
    the mark's clearance - only now the boundary is the 66dp SAFE CIRCLE (r=132px), not the
    tile. Scale is taken as far as that objective allows: start at the MEC ceiling, and if
    no perceptually-acceptable centre keeps 2px of clearance, back the scale off 1.5% and
    try again. Costs a few percent of size, buys a mark that is actually centred.
    """
    N = ADAPT_N
    hp0 = ink_hull(sil)                                  # mark space
    _, r_mec = min_enclosing_centre(hp0)                 # the ceiling

    ink = sil > 0.5
    ys, xs = np.where(ink)
    bc = ((xs.min() + xs.max() + 1) / 2.0, (ys.min() + ys.max() + 1) / 2.0)
    t = grad_t(sil.shape, bc, ink)
    lum = (ramp_lin(t) @ SRGB) ** (1 / 2.2)
    w = sil * (W_FLOOR + (1.0 - W_FLOOR) * lum)
    tot = w.sum()
    pc = (float((w * np.arange(sil.shape[1])[None, :]).sum() / tot),
          float((w * np.arange(sil.shape[0])[:, None]).sum() / tot))
    pa = (bc[0] + BETA * (pc[0] - bc[0]), bc[1] + BETA * (pc[1] - bc[1]))

    target = ADAPT_INK_R
    for _ in range(20):
        f = target / r_mec                               # mark px -> 432-canvas px
        hp = hp0 * f
        base = (pa[0] * f, pa[1] * f)
        best = None
        for dy in np.arange(-14.0, 14.01, 0.5):
            for dx in np.arange(-14.0, 14.01, 0.5):
                if dx * dx + dy * dy > 14.0 ** 2:
                    continue
                cx, cy = base[0] - dx, base[1] - dy
                sp, mn, _ = sector_spread(hp, cx, cy, ADAPT_SAFE_R)
                if mn < 2.0:
                    continue
                if best is None or sp < best[0]:
                    best = (sp, dx, dy, mn)
        if best is not None:
            sp, dx, dy, mn = best
            anchor = (pa[0] - dx / f, pa[1] - dy / f)
            s = f / (N / float(OUT))                     # -> the scale `compose` wants
            return s, anchor, dict(sector_spread_px=round(sp, 1),
                                   safe_circle_clearance_px=round(mn, 1),
                                   mec_ceiling_r_px=round(r_mec * f, 1))
        target *= 0.985                                  # back off and try again
    raise SystemExit("adaptive: no placement fits the safe circle")


def adaptive(sil):
    """REAL adaptive layers. 108dp canvas @xxxhdpi = 432px.
    The mark is RE-RENDERED (not cropped) so that ALL of its ink fits inside the 66dp
    SAFE CIRCLE (r=132px) - not merely the 66dp square, whose corners sit at r=46.7dp and
    get shaved by every circular mask."""
    N, ss = ADAPT_N, 4
    s, pa, pstat = place_adaptive(sil)

    rgb, a, _ = compose(sil, s, pa, size=N, ss=ss, mark_only=True)

    bg = plate_lin(N * ss)[0]
    bg = to_srgb(np.stack([box_resolve(bg[..., c], N) for c in range(3)], -1))
    rng = np.random.default_rng(4321)
    bg = np.clip(bg + rng.uniform(-GRAIN, GRAIN, (N, N, 1)).astype(np.float32), 0, 255)

    a8 = np.rint(np.clip(a * 255.0, 0, 255)).astype(np.uint8)
    fg = np.dstack([np.rint(rgb).astype(np.uint8), a8])
    # monochrome: Android tints the layer itself, so the RGB must be a constant. Alpha is
    # the shape. Keep the SAME alpha -> the themed icon is the same silhouette.
    mono = np.dstack([np.full((N, N, 3), 255, np.uint8), a8])

    yy, xx = np.where(a > 0.5)
    stat = dict(
        fg_bbox_px=(int(xx.max() - xx.min() + 1), int(yy.max() - yy.min() + 1)),
        fg_bbox_dp=(round((xx.max() - xx.min() + 1) / 4.0, 1),
                    round((yy.max() - yy.min() + 1) / 4.0, 1)),
        max_ink_r_px=round(float(np.sqrt((xx - (N / 2.0 - 0.5)) ** 2
                                         + (yy - (N / 2.0 - 0.5)) ** 2).max()), 1),
        safe_circle_r_px=ADAPT_SAFE_R)
    stat["safe_circle_clearance_px"] = round(ADAPT_SAFE_R - stat["max_ink_r_px"], 1)
    stat.update(pstat)
    return (Image.fromarray(np.rint(bg).astype(np.uint8), "RGB"),
            Image.fromarray(fg, "RGBA"), Image.fromarray(mono, "RGBA"), stat)


# ----------------------------------------------------------------------------- preview
def scale_rgba(im, n):
    """PREMULTIPLIED, LINEAR-LIGHT, EXACT-AREA (BOX) downscale.

    Every word of that is load-bearing, and this one function is where most of the 48px
    verdict is decided. It is the same argument as the master's resolve, applied to every
    subsequent scale-down - the icon must not get quietly worse each time it is resized.

      * PREMULTIPLIED: otherwise the mask edge picks up a dark/light fringe from the RGB
        of fully-transparent pixels.

      * LINEAR: averaging sRGB BYTES averages a ~2.2-power-encoded signal, so a thin BRIGHT
        feature on a DARK plate loses most of its energy. The wing's top feather is 1.5
        DEVICE PX wide at 48. Measured on the wing cut, its peak sits at 0.33 of the bird's
        range in gamma space and 0.55 in linear - i.e. gamma-space scaling renders it at
        about a third of the light it actually reflects. That is what killed the quills at
        small sizes, and it is a scaling bug, not a drawing problem.

      * BOX, not LANCZOS: Lanczos (and bicubic) have negative lobes, so at the bird's
        high-contrast edge they UNDERSHOOT into the plate and print a dark ring - the exact
        fake drop-shadow the brief forbids, just relocated from the resolve to the preview.
        Rendered all five combinations at 48 @11x and looked (_filter-compare-48.png):
        LANCZOS/BICUBIC in linear both ring visibly around the head; HAMMING is clean but
        soft; BOX is clean, and its slight softness is what a real 48px icon looks like.
        BOX at a non-integer ratio is PIL's true area filter, so this is the same "exact
        box" the master resolve uses.

    Nothing here touches the silhouette.
    """
    src = np.asarray(im, np.float32)
    al = src[..., 3] / 255.0
    lin = to_lin(src[..., :3])                                  # sRGB byte -> linear
    pm = np.dstack([lin[..., c] * al for c in range(3)] + [al])
    o = np.clip(np.stack([fresize(pm[..., c], (n, n), Image.BOX) for c in range(4)], -1),
                0.0, 1.0)
    aa = o[..., 3:4]
    rgb = to_srgb(np.where(aa > 1e-3, o[..., :3] / np.maximum(aa, 1e-6), 0.0))
    return Image.fromarray(np.dstack([np.rint(np.clip(rgb, 0, 255)),
                                      np.rint(np.clip(aa * 255.0, 0, 255))]).astype(np.uint8),
                           "RGBA")


def font(sz):
    """Labels on the preview contact sheet only — never touches the shipped art."""
    try:
        return ImageFont.truetype(os.path.join(FONTS, "Inter-SemiBold.ttf"), sz)
    except Exception:
        return ImageFont.load_default()


def preview(paths, ad):
    rnd = Image.open(paths["round"])
    crc = Image.open(paths["circle"])
    ful = Image.open(paths["full"]).convert("RGBA")
    f = font(15)
    items = [("rounded", rnd, 512), ("rounded", rnd, 192), ("rounded", rnd, 96),
             ("rounded", rnd, 48), ("circle", crc, 192), ("circle", crc, 48),
             ("FULLBLEED", ful, 192)]
    zooms = [("rounded 48 @6x", rnd, 48, 6), ("circle 48 @6x", crc, 48, 6),
             ("rounded 96 @3x", rnd, 96, 3)]

    PAD, GAP, ROWH = 40, 56, 620
    W = max(PAD * 2 + sum(n + GAP for _, _, n in items),
            PAD * 2 + 48 * 12 + 96 * 3 + GAP * 2,
            PAD * 2 + 4 * (216 + GAP))
    H = ROWH * 2 + 48 * 6 + 90 + 300 + PAD
    sheet = Image.new("RGB", (W, H), (24, 24, 28))
    d = ImageDraw.Draw(sheet)

    for row, (bg, fg) in enumerate([((245, 245, 247), (60, 60, 66)),
                                    ((13, 17, 23), (170, 175, 185))]):
        y0 = row * ROWH
        d.rectangle([0, y0, W, y0 + ROWH - 8], fill=bg)
        x, base = PAD, y0 + 60
        for label, im, n in items:
            t = scale_rgba(im, n)
            sheet.paste(t, (x, base + (512 - n) // 2), t)
            d.text((x, base + 512 + 14), f"{label} {n}", font=f, fill=fg)
            x += n + GAP

    y0 = ROWH * 2
    d.rectangle([0, y0, W, H], fill=(13, 17, 23))
    x = PAD
    for label, im, n, z in zooms:
        t = scale_rgba(im, n).resize((n * z, n * z), Image.NEAREST)
        flat = Image.new("RGB", t.size, (13, 17, 23))
        flat.paste(t, (0, 0), t)
        sheet.paste(flat, (x, y0 + 30))
        d.text((x, y0 + 34 + n * z), label, font=f, fill=(170, 175, 185))
        x += n * z + GAP

    # adaptive strip: bg+fg composited, then masked exactly as the launchers mask.
    y1 = y0 + 48 * 6 + 90
    d.rectangle([0, y1, W, H], fill=(30, 30, 36))
    bgL, fgL, monoL, _ = ad
    comp = bgL.convert("RGBA")
    comp.alpha_composite(fgL)
    n = 216
    c = scale_rgba(comp, n)
    x = PAD
    for label, mk in [("adaptive: circle", circle_alpha(n, 4)),
                      ("adaptive: squircle", rounded_alpha(n, 4, 0.224)),
                      ("adaptive: 72dp square", None)]:
        t = np.asarray(c, np.uint8).copy()
        if mk is not None:
            t[..., 3] = np.minimum(t[..., 3], mk)
        else:
            sq = np.zeros((n, n), np.uint8)
            m = int(n * 18 / 108)
            sq[m:n - m, m:n - m] = 255
            t[..., 3] = np.minimum(t[..., 3], sq)
        ti = Image.fromarray(t, "RGBA")
        flat = Image.new("RGB", (n, n), (30, 30, 36))
        flat.paste(ti, (0, 0), ti)
        sheet.paste(flat, (x, y1 + 24))
        d.text((x, y1 + 28 + n), label, font=f, fill=(170, 175, 185))
        x += n + GAP
    mo = np.asarray(monoL, np.uint8).copy()
    mo[..., :3] = np.array([80, 90, 110], np.uint8)          # system tint, as Android does
    mi = scale_rgba(Image.fromarray(mo, "RGBA"), n)
    flat = Image.new("RGB", (n, n), (225, 228, 233))
    flat.paste(mi, (0, 0), mi)
    sheet.paste(flat, (x, y1 + 24))
    d.text((x, y1 + 28 + n), "monochrome (themed)", font=f, fill=(170, 175, 185))

    p = os.path.join(HERE, f"preview-{ID}.png")
    sheet.save(p)
    return p


# ----------------------------------------------------------------------------- study
def study(fracs=(0.64, 0.68, 0.72, 0.76)):
    """PROPORTION STUDY. Render the tile at each candidate scale, show it at 512 and at
    48 (blown up 6x, nearest) so the two decisions - gesture at large, legibility at
    small - are made on the same sheet."""
    sil = load_mark()
    f = font(16)
    N = len(fracs)
    CW, PAD, GAP = 512, 40, 40
    W = PAD * 2 + N * CW + (N - 1) * GAP
    H = PAD + 30 + CW + 24 + 48 * 6 + 30 + PAD
    sheet = Image.new("RGB", (W, H), (13, 17, 23))
    d = ImageDraw.Draw(sheet)
    rows = []
    for i, fr in enumerate(fracs):
        s, anchor, geo = place(sil, fr)
        rgb, a, (xs, ys) = compose(sil, s, anchor)
        ar = rounded_alpha(OUT, SS, ROUND_R_FRAC)
        im = Image.fromarray(np.dstack([rgb, ar]), "RGBA")
        x = PAD + i * (CW + GAP)
        big = scale_rgba(im, CW)
        flat = Image.new("RGB", (CW, CW), (13, 17, 23))
        flat.paste(big, (0, 0), big)
        sheet.paste(flat, (x, PAD + 30))
        sm = scale_rgba(im, 48).resize((48 * 6, 48 * 6), Image.NEAREST)
        f2 = Image.new("RGB", sm.size, (13, 17, 23))
        f2.paste(sm, (0, 0), sm)
        sheet.paste(f2, (x + (CW - 48 * 6) // 2, PAD + 30 + CW + 24))
        d.text((x, PAD + 4), "bbox_h = %d%%   clearance %.0fpx   spread %.0fpx"
               % (round(fr * 100), geo["circle_clearance_px"], geo["sector_spread_px"]),
               font=f, fill=(200, 205, 215))
        rows.append((fr, geo))
        print("  h=%.2f  clearance=%5.1fpx  spread=%5.1fpx  bbox=%dx%d px  nudge=%s"
              % (fr, geo["circle_clearance_px"], geo["sector_spread_px"],
                 (xs.max() - xs.min() + 1) // SS, (ys.max() - ys.min() + 1) // SS,
                 geo["sector_nudge_px"]))
    p = os.path.join(HERE, "study-scale.png")
    sheet.save(p)
    print(p)
    return p


# ----------------------------------------------------------------------------- audit
def iou_vs_true_mark(s, anchor, a_rendered, filt=Image.LANCZOS):
    """THE FIDELITY GATE.

    Re-read mark.png from disk. Uniformly scale its alpha STRAIGHT to output resolution -
    a completely different resampling path from the render's (upscale to 4096 -> composite
    -> exact-box resolve) - and lay it down on the same snapped grid position. Compare the
    two 0.5 level sets.

    This is the number that would have caught ink-final: it does not care about AA, gamma,
    colour or the plate, only about WHERE THE INK IS. Any open/close/taper/carve/smooth
    would move thousands of boundary pixels and drop it through the floor.
    """
    src = load_mark()                                    # re-read, do not trust the caller
    n, ms, k, ox, oy, Sz = mark_geometry(src, s, anchor, OUT, SS)
    ox, oy = ox // SS, oy // SS                          # snapped -> exact in output px
    ref_small = np.clip(fresize(src, (n, n), filt), 0.0, 1.0)
    ref = np.zeros((OUT, OUT), np.float32)
    sx0, sy0 = max(0, ox), max(0, oy)
    sx1, sy1 = min(OUT, ox + n), min(OUT, oy + n)
    ref[sy0:sy1, sx0:sx1] = ref_small[sy0 - oy:sy1 - oy, sx0 - ox:sx1 - ox]

    A = ref > 0.5
    B = a_rendered > 0.5
    return int((A & B).sum()) / float((A | B).sum()), A, B


def audit(p_full, p_round, p_circ, sil, s, anchor, a_rendered):
    a = np.asarray(Image.open(p_full).convert("RGB"), np.int16)
    r = np.asarray(Image.open(p_round).convert("RGBA"), np.int16)
    c = np.asarray(Image.open(p_circ).convert("RGBA"), np.int16)
    lum = a.mean(2)

    row = a[40, :, 1]                             # banding tell: longest flat run
    runs, cur = 1, 1
    for i in range(1, OUT):
        cur = cur + 1 if row[i] == row[i - 1] else 1
        runs = max(runs, cur)

    iou, A, B = iou_vs_true_mark(s, anchor, a_rendered)
    # ...and again against a reference built with three OTHER filters, so the gate cannot
    # be passing merely because both paths share LANCZOS's ringing.
    iou_alt = {nm: round(iou_vs_true_mark(s, anchor, a_rendered, f)[0], 5) for nm, f in
               [("bicubic", Image.BICUBIC), ("bilinear", Image.BILINEAR), ("box", Image.BOX)]}

    # circle-mask clearance, measured on the RENDERED ink (not on a hull estimate)
    ys, xs = np.where(B)
    rr = np.sqrt((xs - (OUT / 2.0 - 0.5)) ** 2 + (ys - (OUT / 2.0 - 0.5)) ** 2)
    clearance = OUT / 2.0 - rr.max()

    # NO BAKED DROP SHADOW. A gamma-space resolve UNDERSHOOTS at the bird's high-contrast
    # edge and lays a dark rim on the plate; that rim is what reads as a drop shadow, and
    # the brief forbids it outright.
    #
    # First attempt at this metric compared a band just outside the ink to the plate further
    # out, and reported +382%: it was measuring the bird's own ANTIALIASED EDGE, which of
    # course is bright. So compare against ground truth instead - render the PLATE ALONE
    # through the identical resolve, and look only at pixels the bird does not touch AT ALL
    # (coverage < 1e-4) that lie within 10px of it. If the resolve baked a shadow, those
    # pixels are darker than the bare plate. Anything within +-1% is grain.
    bare = to_srgb(np.stack([box_resolve(plate_lin(S)[0][..., c], OUT) for c in range(3)], -1))
    lin_i = to_lin(a.astype(np.float32)) @ SRGB
    lin_p = to_lin(bare) @ SRGB
    near = B.copy()
    for _ in range(10):
        g = near.copy()
        g[1:, :] |= near[:-1, :]; g[:-1, :] |= near[1:, :]
        g[:, 1:] |= near[:, :-1]; g[:, :-1] |= near[:, 1:]
        near = g
    pure = near & (a_rendered < 1e-4)               # plate pixels the bird never touched
    halo = float(lin_i[pure].mean() / max(lin_p[pure].mean(), 1e-9) - 1.0) * 100.0

    out = dict(
        fullbleed_corners={k: tuple(int(v) for v in a[y, x]) for k, (y, x) in
                           {"TL": (0, 0), "TR": (0, 1023),
                            "BL": (1023, 0), "BR": (1023, 1023)}.items()},
        fullbleed_edge_mids={k: tuple(int(v) for v in a[y, x]) for k, (y, x) in
                             {"T": (0, 512), "B": (1023, 512),
                              "L": (512, 0), "R": (512, 1023)}.items()},
        edge_lum_0_30px=[round(float(lum[512, i]), 1) for i in range(0, 30, 3)],
        lum_at_60px=round(float(lum[512, 60]), 1),
        max_flat_run_px=int(runs),
        rounded_rgb_identical_to_fullbleed=bool((r[..., :3] == a).all()),
        rounded_corner_alpha_TL_TR_BL_BR=tuple(int(r[y, x, 3]) for y, x in
                                               [(0, 0), (0, 1023), (1023, 0), (1023, 1023)]),
        rounded_edge_mid_alpha=tuple(int(r[y, x, 3]) for y, x in
                                     [(0, 512), (512, 0), (1023, 512), (512, 1023)]),
        circle_corner_alpha=tuple(int(c[y, x, 3]) for y, x in
                                  [(0, 0), (0, 1023), (1023, 0), (1023, 1023)]),
        silhouette_IoU_vs_true_mark=round(iou, 5),
        silhouette_IoU_other_ref_filters=iou_alt,
        disagreeing_px=int((A ^ B).sum()),
        ink_px_rendered=int(B.sum()),
        ink_px_reference=int(A.sum()),
        n_components=len(components(sil > 0.5, 40)),
        eye_counter_px=int(enclosed_holes(sil > 0.5).sum()),
        circle_mask_clearance_px=round(float(clearance), 1),
        edge_halo_pct=round(halo, 1),          # ~0 = no baked shadow. Negative = dark rim.
    )
    return out


def contrast(p_round, size=48):
    """measured figure-ground at `size`: p92 of the tile's luminance vs the plate's."""
    im = scale_rgba(Image.open(p_round), size)
    v = np.asarray(im, np.float32)[..., :3]
    lin = to_lin(v) @ SRGB
    hi = np.percentile(lin, 92)
    lo = np.percentile(lin[lin < np.percentile(lin, 55)], 50)
    return round(float((hi + 0.05) / (lo + 0.05)), 2)


def _profile(img, p0, p1, nsamp):
    """bilinear samples along a segment."""
    t = np.linspace(0, 1, nsamp)
    x = p0[0] + (p1[0] - p0[0]) * t
    y = p0[1] + (p1[1] - p0[1]) * t
    x0 = np.clip(np.floor(x).astype(int), 0, img.shape[1] - 2)
    y0 = np.clip(np.floor(y).astype(int), 0, img.shape[0] - 2)
    fx, fy = x - x0, y - y0
    return ((img[y0, x0] * (1 - fx) + img[y0, x0 + 1] * fx) * (1 - fy) +
            (img[y0 + 1, x0] * (1 - fx) + img[y0 + 1, x0 + 1] * fx) * fy)


SLIT_MIN, SLIT_MAX = 40, 220   # a REAL wing slit, in mark px. Below 40 is the brush's
                               # dotted hairline dashes (4-28px), which are texture, not
                               # structure, and are SUPPOSED to average away at 48px.
FEATHER_MIN = 30               # ...and a real slit is flanked by real FEATHERS. Without
                               # this the search walks out to the feather TIPS, where the
                               # blades are 9-16px quills: it scores a gorgeous 4 slits and
                               # is measuring the thinnest slice of the wing that exists.


def find_wing_cut(sil):
    """Find, IN MARK SPACE, the straight cut that crosses the most wing slits - i.e. the
    grating's NORMAL. Searched, not guessed: sweep angle x offset, score by the number of
    interior gaps whose width is in the real-slit band.

    Two traps, both hit on the first attempt:
      * an unconstrained search returns a near-vertical line through the WHOLE bird (it
        scores 8 "slits" by also crossing the neck, the bowl and the beak). Constrain the
        cut to the wing quadrant AND make both endpoints live there.
      * counting any gap >=10px counts the brush's dotted dashes as slits, which inflates
        the score and then reports them as "surviving" when they are 0.6px at 48 and are
        meant to disappear. Hence the SLIT_MIN band.
    """
    b = (sil > 0.5).astype(np.float32)
    ys, xs = np.where(sil > 0.5)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    W, H = x1 - x0, y1 - y0
    rx0, rx1 = x0, x0 + 0.56 * W          # the wing: lower-LEFT of the mark
    ry0, ry1 = y0 + 0.42 * H, y1
    cx, cy = (rx0 + rx1) / 2.0, (ry0 + ry1) / 2.0

    def clip_to_box(c, d):
        """longest chord of the wing box through c along d."""
        ts = []
        for lo, hi, ci, di in ((rx0, rx1, c[0], d[0]), (ry0, ry1, c[1], d[1])):
            if abs(di) < 1e-9:
                if not (lo <= ci <= hi):
                    return None
                continue
            ts.append(sorted(((lo - ci) / di, (hi - ci) / di)))
        if not ts:
            return None
        tmin = max(t[0] for t in ts)
        tmax = min(t[1] for t in ts)
        if tmax - tmin < 60:
            return None
        return c + d * tmin, c + d * tmax

    best = None
    for adeg in range(0, 180, 2):
        a = np.radians(adeg)
        d = np.array([np.cos(a), np.sin(a)])
        nvec = np.array([-d[1], d[0]])
        for off in np.arange(-0.5 * H, 0.5 * H + 1e-9, 4.0):
            seg_pts = clip_to_box(np.array([cx, cy]) + nvec * off, d)
            if seg_pts is None:
                continue
            p0, p1 = seg_pts
            L = int(np.hypot(*(p1 - p0)))
            pr = _profile(b, p0, p1, L)
            ink = pr > 0.5
            if ink.sum() < 60:
                continue
            i0, i1 = int(np.argmax(ink)), int(len(ink) - 1 - np.argmax(ink[::-1]))
            seg = ink[i0:i1 + 1]
            # run-length encode the cut: [(is_ink, length), ...]
            rle, cur, run = [], seg[0], 0
            for v in seg:
                if v == cur:
                    run += 1
                else:
                    rle.append((bool(cur), run))
                    cur, run = v, 1
            rle.append((bool(cur), run))
            # a WELL-FORMED slit: a gap in the slit band, flanked BOTH sides by real feathers
            gaps = [rle[i][1] for i in range(1, len(rle) - 1)
                    if not rle[i][0] and SLIT_MIN <= rle[i][1] <= SLIT_MAX
                    and rle[i - 1][1] >= FEATHER_MIN and rle[i + 1][1] >= FEATHER_MIN]
            score = (len(gaps), int(seg.sum()))
            if best is None or score > best[0]:
                best = (score, p0, p1, gaps)
    return best[1], best[2], best[0][0], best[3]


def slit_survival(sil, s, anchor, p_round, size=48):
    """THE 48px TEST, as a number.

    Take the cut found above, map it into the rendered tile, and sample the ACTUAL 48px
    icon (the same LANCZOS-downscaled tile the contact sheet and a launcher show) along it,
    in LINEAR luminance. A slit survives if it still prints a luminance trough between two
    feather peaks. Report, per slit, the WCAG contrast between the feather peak and the
    slit floor: >= 1.6 reads as a slit at arm's length; ~1.2 is a smudge; 1.0 is gone.
    """
    p0, p1, nslit, gaps = find_wing_cut(sil)

    n, ms, k, ox, oy, Sz = mark_geometry(sil, s, anchor, OUT, SS)
    kk = (k / SS) * (size / float(OUT))          # mark px -> `size` px
    o = np.array([ox / SS, oy / SS]) * (size / float(OUT))
    q0 = np.array(p0) * kk + o
    q1 = np.array(p1) * kk + o

    im = np.asarray(scale_rgba(Image.open(p_round), size), np.float32)[..., :3]
    lin = to_lin(im) @ SRGB
    pr = _profile(lin, q0, q1, 240)

    # trim to the ink's own extent along the cut, then find interior troughs
    hi = pr > (pr.min() + 0.35 * (pr.max() - pr.min()))
    if not hi.any():
        return dict(slits_in_mark=nslit, slits_surviving_48=0)
    i0, i1 = int(np.argmax(hi)), int(len(hi) - 1 - np.argmax(hi[::-1]))
    seg = pr[i0:i1 + 1]

    troughs = []
    for i in range(2, len(seg) - 2):
        if seg[i] <= seg[i - 1] and seg[i] <= seg[i + 1] and seg[i] < seg[i - 2] * 0.999:
            lo = seg[i]
            lpk = seg[:i].max()
            rpk = seg[i + 1:].max()
            pk = min(lpk, rpk)
            if pk > lo:
                troughs.append((i, float(lo), float(pk)))
    # merge troughs that sit inside the same dip (keep the deepest of any run within 6 samples)
    troughs.sort(key=lambda t: t[1])
    kept = []
    for t in troughs:
        if all(abs(t[0] - u[0]) > 6 for u in kept):
            kept.append(t)
    kept = [t for t in kept if (t[2] + 0.05) / (t[1] + 0.05) >= 1.15]
    kept.sort(key=lambda t: t[0])

    ratios = [round((pk + 0.05) / (lo + 0.05), 2) for _, lo, pk in kept]
    return dict(slits_in_mark=nslit,
                mark_slit_widths_px=gaps,
                slit_pitch_at_48px=[round(g * kk, 2) for g in gaps],
                slits_surviving_48=len(kept),
                slit_contrast_48=ratios,
                cut_mark_space=(tuple(np.round(p0, 0).astype(int)),
                                tuple(np.round(p1, 0).astype(int))))


# ----------------------------------------------------------------------------- main
def render(write=True):
    sil = load_mark()
    s, anchor, geo = place(sil)
    rgb, a, (xs, ys) = compose(sil, s, anchor)

    geo.update(
        mark_scale=round(s, 4),
        bbox_w_pct=round((xs.max() - xs.min() + 1) / S * 100, 1),
        bbox_h_pct=round((ys.max() - ys.min() + 1) / S * 100, 1),
        max_ink_r_pct_of_tile_r=round(
            np.sqrt((xs - S / 2.) ** 2 + (ys - S / 2.) ** 2).max() / (S / 2.) * 100, 1),
        margins_LRTB=[int(xs.min() / SS), int((S - 1 - xs.max()) / SS),
                      int(ys.min() / SS), int((S - 1 - ys.max()) / SS)],
    )
    if not write:
        return geo

    p = {k: os.path.join(HERE, v) for k, v in {
        # The three masters generate_assets.py derives every shipped size from:
        "full": "icon-fullbleed-1024.png",
        "round": "icon-rounded-1024.png",
        "circle": "icon-circle-1024.png",
        # Intermediates — regenerate on demand, not tracked (see store-assets/.gitignore).
        # The adaptive layers are ready if we ever ship an adaptive launcher icon; see the
        # note in the module docstring about issue #150.
        "play": "icon-play-512.png",
        "abg": "adaptive-background-432.png",
        "afg": "adaptive-foreground-432.png",
        "amono": "adaptive-monochrome-432.png",
        "lround": "ic_launcher_round-192.png",
    }.items()}

    ar = rounded_alpha(OUT, SS, ROUND_R_FRAC)
    ac = circle_alpha(OUT, SS)
    Image.fromarray(rgb, "RGB").save(p["full"])                       # RGB, NO alpha
    Image.fromarray(np.dstack([rgb, ar]), "RGBA").save(p["round"])
    Image.fromarray(np.dstack([rgb, ac]), "RGBA").save(p["circle"])

    # Play hi-res icon: 512, full-bleed, NOT rounded, NOT shadowed (Play applies its own
    # 30% mask AND drop shadow). Play's uploader requires a 32-BIT png, so it carries a
    # fully-OPAQUE alpha channel: transparent pixels would composite against Play's UI.
    # Scaled in LINEAR light with the exact-area filter, for the same reason as everything
    # else here - a gamma-space LANCZOS here would undo the resolve's whole point and hand
    # Play a tile with a ringed bird in it.
    pl = to_lin(rgb.astype(np.float32))
    pl = to_srgb(np.stack([fresize(pl[..., c], (512, 512), Image.BOX) for c in range(3)], -1))
    play = Image.fromarray(np.rint(np.clip(pl, 0, 255)).astype(np.uint8), "RGB").convert("RGBA")
    play.putalpha(255)
    play.save(p["play"])

    ad = adaptive(sil)
    ad[0].save(p["abg"])
    ad[1].save(p["afg"])
    ad[2].save(p["amono"])
    # legacy round mipmap MUST be a baked circle with transparent corners: a square here
    # renders as a square on API<=25 round-icon launchers.
    scale_rgba(Image.open(p["circle"]), 192).save(p["lround"])

    pv = preview(p, ad)
    return p, pv, geo, ad[3], (sil, s, anchor, a)


if __name__ == "__main__":
    if "--study" in sys.argv:
        study()
        raise SystemExit

    p, pv, geo, astat, (sil, s, anchor, a) = render()
    print("geometry:", geo)
    print("adaptive:", astat)
    for k, v in audit(p["full"], p["round"], p["circle"], sil, s, anchor, a).items():
        print("  %-34s %s" % (k, v))
    print("  %-34s %s" % ("contrast@48", contrast(p["round"], 48)))
    for k, v in slit_survival(sil, s, anchor, p["round"], 48).items():
        print("  %-34s %s" % (k, v))

    ims = {k: Image.open(p[k]) for k in p}
    print("VERIFY:")
    for k in ("full", "round", "circle", "play", "abg", "afg", "amono", "lround"):
        im = ims[k]
        extra = ""
        if im.mode == "RGBA":
            extra = " min-alpha=%d" % np.asarray(im)[..., 3].min()
        print("   %-9s %-9s %s%s" % (k, str(im.size), im.mode, extra))
    for k in ("full", "round", "circle", "play", "abg", "afg", "amono", "lround"):
        print(p[k])
    print(pv)
