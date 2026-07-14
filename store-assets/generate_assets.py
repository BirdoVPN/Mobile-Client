"""Generate every shipped Birdo brand asset from the two masters.

    python store-assets/generate_assets.py            # derive all targets from the masters
    python store-assets/generate_assets.py --render   # re-render the masters first (slow, ~1 min)

The masters are produced by the two renderers next to this file, both of which read the emerald
phoenix from `brand-mark-1024.png` and the vendored Inter faces from `fonts/`:

    generate_icon.py            -> icon-fullbleed-1024 / icon-rounded-1024 / icon-circle-1024
                                   (+ adaptive background/foreground/monochrome layers, unused for now)
    generate_feature_graphic.py -> feature-graphic-1024x500

GEOMETRY — the thing the old assets got wrong
    The rounded corners are NOT painted into the art. The master is a full-bleed square; the rounded and
    circular variants are alpha masks CUT from it. A bitmap with a rounded box drawn inside it gets rounded
    a second time by Play and by the launcher, which is what produced the visible frame, the bevel line and
    the dead margin on the old icon.

    Play hi-res icon      FULL-BLEED, opaque.  Play applies its own corner mask + shadow.
    F-Droid icon          ROUNDED.             F-Droid does not mask.
    ic_launcher.png       ROUNDED (squircle).
    ic_launcher_round.png CIRCLE.
    Feature graphic       24-bit RGB, no alpha (Play's spec).
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

    for master in (FULLBLEED, ROUNDED, CIRCLE, FEATURE):
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

    print("Launcher mipmaps:")
    for density, px in DENSITIES.items():
        save(rs(rnd, px), f"app/src/main/res/mipmap-{density}/ic_launcher.png")
        save(rs(circ, px), f"app/src/main/res/mipmap-{density}/ic_launcher_round.png")

    # Vestigial copies written by scripts/generate-store-assets.ps1. The Play upload
    # (scripts/play_listing.py) reads store-assets/, not this directory — but the files are
    # committed, so keep them in step rather than letting them rot into the old brand.
    print("Legacy duplicates (screenshots/play/store-assets):")
    save(rs(full, 512), "screenshots/play/store-assets/icon-512.png")
    save(feat, "screenshots/play/store-assets/feature-1024x500.png", "RGB")

    print("\nDone.")


if __name__ == "__main__":
    main()
