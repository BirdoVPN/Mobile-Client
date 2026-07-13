"""Generate Google Play Store assets for Birdo VPN (new phoenix brand).

The phoenix mark is loaded from `brand-mark-1024.png` (a transparent render of
new-logo/mark.svg). Run after any logo change to refresh the Play Store icon +
feature graphic.
"""
from PIL import Image, ImageDraw, ImageFont
import os

OUT = os.path.dirname(os.path.abspath(__file__))
MARK = os.path.join(OUT, "brand-mark-1024.png")  # transparent white->emerald phoenix

# ── Brand palette (matches the new app icon: dark emerald box + mint) ──
BOX_TOP    = (26, 68, 54)     # #1A4436
BOX_BOT    = (7, 15, 12)      # #070F0C
FEAT_TOP   = (22, 64, 47)     # #16402F
FEAT_BOT   = (29, 92, 74)     # #1D5C4A  (a touch of emerald so the banner isn't dead-dark)
WHITE      = (255, 255, 255)
WHITE_A    = (255, 255, 255, 220)


def gradient_bg(w, h, c0, c1):
    """Diagonal linear gradient from c0 (top-left) to c1 (bottom-right)."""
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w + y / h) / 2
            px[x, y] = (
                int(c0[0] + (c1[0] - c0[0]) * t),
                int(c0[1] + (c1[1] - c0[1]) * t),
                int(c0[2] + (c1[2] - c0[2]) * t),
                255,
            )
    return img


def load_mark(size):
    """Load the phoenix mark, scaled to `size` (square, transparent)."""
    m = Image.open(MARK).convert("RGBA")
    return m.resize((size, size), Image.LANCZOS)


# ─── 1. App Icon 512×512 (matches the launcher: dark box + phoenix) ──────
print("Generating app icon 512x512...")
# Play wants a full-bleed opaque square (Play applies its own corner mask);
# use the glossy emerald master so the listing matches the launcher exactly.
SIZE = 512
FULLBLEED = os.path.join(OUT, "app-icon-fullbleed-1024.png")
icon = Image.open(FULLBLEED).convert("RGBA").resize((SIZE, SIZE), Image.LANCZOS)
icon.save(os.path.join(OUT, "app-icon-512.png"), "PNG")
print(f"  -> {OUT}\\app-icon-512.png")


# ─── 2. Feature Graphic 1024×500 ────────────────────────────────────────
print("Generating feature graphic 1024x500...")
FW, FH = 1024, 500
feat = gradient_bg(FW, FH, FEAT_TOP, FEAT_BOT)

# Phoenix on the left-centre
mark_px = 300
mark = load_mark(mark_px)
feat.alpha_composite(mark, (FW // 4 - mark_px // 2, FH // 2 - mark_px // 2))

draw = ImageDraw.Draw(feat)
try:
    font_title = ImageFont.truetype("arialbd.ttf", 60)
    font_sub = ImageFont.truetype("arial.ttf", 24)
except Exception:
    font_title = ImageFont.load_default()
    font_sub = ImageFont.load_default()

text_x = FW // 2 + 20
draw.text((text_x, FH // 2 - 64), "BirdoVPN", fill=WHITE, font=font_title)
draw.text((text_x, FH // 2 + 14), "Fast & Secure WireGuard® VPN", fill=WHITE_A, font=font_sub)
draw.text((text_x, FH // 2 + 49), "No logs. No ads. No trackers.", fill=WHITE_A, font=font_sub)

feat.save(os.path.join(OUT, "feature-graphic-1024x500.png"), "PNG")
print(f"  -> {OUT}\\feature-graphic-1024x500.png")

print("\nDone! Assets saved to store-assets/")
