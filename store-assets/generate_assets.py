"""Generate every shipped Birdo brand asset from the two masters.

    python store-assets/generate_assets.py            # derive all targets from the masters
    python store-assets/generate_assets.py --render   # re-render the masters first (slow, ~1 min)

The masters are produced by the two renderers next to this file, both of which read the emerald
phoenix from `brand-mark-1024.png` and the vendored Inter faces from `fonts/`:

    generate_icon.py            -> icon-fullbleed-1024 / icon-rounded-1024 / icon-circle-1024
                                   + adaptive background/foreground/monochrome layers (432px)
    generate_feature_graphic.py -> feature-graphic-1024x500

GEOMETRY — the thing the old assets got wrong
    The rounded corners are NOT painted into the art. The master is a full-bleed square; the rounded and
    circular variants are alpha masks CUT from it. A bitmap with a rounded box drawn inside it gets rounded
    a second time by Play and by the launcher, which is what produced the visible frame, the bevel line and
    the dead margin on the old icon.

    Play hi-res icon      FULL-BLEED, opaque.  Play applies its own corner mask + shadow.
    F-Droid icon          ROUNDED.             F-Droid does not mask.
    Launcher icon         ADAPTIVE (mipmap-anydpi/ic_launcher*.xml): the three layers below, masked
                          by the launcher, and the monochrome one tinted for themed icons.
    app_mark.png          ROUNDED (squircle), the in-app brand mark (AppIconMark).
    app_mark_round.png    CIRCLE.
    Feature graphic       24-bit RGB, no alpha (Play's spec).

ADAPTIVE LAYERS are rasters on purpose. Issue #150 removed the first adaptive icon because its foreground
was an 832-command VectorDrawable that failed to inflate; PNG layers cannot. The in-app mark has its own
drawables so it never again depends on how the launcher icon is built.
"""
import os
import subprocess
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

FULLBLEED = os.path.join(HERE, "icon-fullbleed-1024.png")
ROUNDED = os.path.join(HERE, "icon-rounded-1024.png")
CIRCLE = os.path.join(HERE, "icon-circle-1024.png")
FEATURE = os.path.join(HERE, "feature-graphic-1024x500.png")

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# An adaptive layer is a 108dp canvas; generate_icon.py renders it at xxxhdpi (432px).
ADAPTIVE_DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
ADAPTIVE_LAYERS = {
    "ic_launcher_background": os.path.join(HERE, "adaptive-background-432.png"),
    "ic_launcher_foreground": os.path.join(HERE, "adaptive-foreground-432.png"),
    "ic_launcher_monochrome": os.path.join(HERE, "adaptive-monochrome-432.png"),
}


def render_masters():
    for script in ("generate_icon.py", "generate_feature_graphic.py"):
        print(f"rendering {script} ...")
        subprocess.run([sys.executable, os.path.join(HERE, script)], check=True, cwd=HERE)


def save(img, relpath, mode=None):
    path = os.path.join(ROOT, relpath.replace("/", os.sep))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out = img.convert(mode) if mode else img
    out.save(path, "PNG", optimize=True)
    print(f"  {relpath:56s} {out.size} {out.mode}")


def main():
    if "--render" in sys.argv:
        render_masters()

    for master in (FULLBLEED, ROUNDED, CIRCLE, FEATURE, *ADAPTIVE_LAYERS.values()):
        if not os.path.exists(master):
            sys.exit(f"missing master: {master}\nrun with --render to build it")

    full = Image.open(FULLBLEED).convert("RGBA")
    rnd = Image.open(ROUNDED).convert("RGBA")
    circ = Image.open(CIRCLE).convert("RGBA")
    feat = Image.open(FEATURE).convert("RGB")

    if full.size != (1024, 1024) or feat.size != (1024, 500):
        sys.exit(f"master geometry wrong: icon={full.size} feature={feat.size}")

    def rs(im, n):
        return im.resize((n, n), Image.LANCZOS)

    print("Play Console:")
    save(rs(full, 512), "store-assets/app-icon-512.png")  # Play masks it — must be full-bleed
    save(full, "store-assets/app-icon-fullbleed-1024.png")
    save(feat, "store-assets/feature-graphic-1024x500.png", "RGB")

    print("F-Droid:")
    save(rs(rnd, 512), "fdroid/icon.png")  # F-Droid does not mask — ship the rounded one
    save(rs(rnd, 512), "fdroid/metadata/app.birdo.vpn/en-US/icon.png")
    save(feat, "fdroid/metadata/app.birdo.vpn/en-US/featureGraphic.png", "RGB")

    print("In-app brand mark:")
    for density, px in DENSITIES.items():
        save(rs(rnd, px), f"app/src/main/res/drawable-{density}/app_mark.png")
        save(rs(circ, px), f"app/src/main/res/drawable-{density}/app_mark_round.png")

    # Same premultiplied, linear-light box filter the masters are resolved with: a LANCZOS
    # downscale rings the bird's edge on the dark plate (see generate_icon.scale_rgba).
    from generate_icon import scale_rgba

    print("Adaptive launcher layers:")
    for name, src in ADAPTIVE_LAYERS.items():
        layer = Image.open(src).convert("RGBA")
        if layer.size != (432, 432):
            sys.exit(f"adaptive layer geometry wrong: {src} is {layer.size}")
        opaque = name == "ic_launcher_background"
        for density, px in ADAPTIVE_DENSITIES.items():
            out = layer if px == 432 else scale_rgba(layer, px)
            save(out, f"app/src/main/res/mipmap-{density}/{name}.png", "RGB" if opaque else None)

    print("\nDone.")


if __name__ == "__main__":
    main()
