"""Generate a YouTube channel banner for Birdo VPN.

Output: 2560x1440 PNG. The "safe area" (1546x423, centered) holds all
text/logo so it renders correctly on mobile, tablet, desktop, and TV.
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import os

OUT = r"W:\vpn\birdo-client-mobile\store-assets"
os.makedirs(OUT, exist_ok=True)

W, H = 2560, 1440
SAFE_W, SAFE_H = 1546, 423
SAFE_X = (W - SAFE_W) // 2
SAFE_Y = (H - SAFE_H) // 2

MARK = os.path.join(OUT, "brand-mark-1024.png")  # transparent phoenix

# Brand palette (matches generate_assets.py)
GRAD_TOP_LEFT     = (15, 14, 35)        # near-black indigo
GRAD_MID          = (76, 51, 158)       # deep violet
GRAD_BOT_RIGHT    = (139, 92, 246)      # #8B5CF6
ACCENT            = (124, 58, 237)      # #7C3AED
WHITE             = (255, 255, 255)
WHITE_A           = (255, 255, 255, 230)
WHITE_FAINT       = (255, 255, 255, 40)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def diagonal_gradient(w, h):
    """Three-stop diagonal gradient, top-left dark -> bottom-right vivid."""
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w * 0.6) + (y / h * 0.4)
            if t < 0.5:
                col = lerp(GRAD_TOP_LEFT, GRAD_MID, t / 0.5)
            else:
                col = lerp(GRAD_MID, GRAD_BOT_RIGHT, (t - 0.5) / 0.5)
            px[x, y] = col + (255,)
    return img


def add_glow(img, cx, cy, radius, color, intensity=180):
    """Soft radial glow blob."""
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse(
        [cx - radius, cy - radius, cx + radius, cy + radius],
        fill=color + (intensity,),
    )
    glow = glow.filter(ImageFilter.GaussianBlur(radius // 2))
    return Image.alpha_composite(img, glow)


def paste_mark(img, cx, cy, size):
    """Composite the phoenix brand mark, centred at (cx, cy)."""
    m = Image.open(MARK).convert("RGBA").resize((size, size), Image.LANCZOS)
    img.alpha_composite(m, (int(cx - size / 2), int(cy - size / 2)))


def load_font(size, bold=False):
    """Try a few common Windows fonts, fall back to default."""
    candidates = (
        ["arialbd.ttf", "segoeuib.ttf", "Inter-Bold.ttf"]
        if bold
        else ["arial.ttf", "segoeui.ttf", "Inter-Regular.ttf"]
    )
    for name in candidates:
        try:
            return ImageFont.truetype(name, size)
        except Exception:
            continue
    return ImageFont.load_default()


def draw_pixel_grid(img, spacing=64, alpha=14):
    """Subtle grid overlay for a tech/hacker vibe."""
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    for x in range(0, img.size[0], spacing):
        od.line([(x, 0), (x, img.size[1])], fill=(255, 255, 255, alpha), width=1)
    for y in range(0, img.size[1], spacing):
        od.line([(0, y), (img.size[0], y)], fill=(255, 255, 255, alpha), width=1)
    return Image.alpha_composite(img, overlay)


print("Generating YouTube banner 2560x1440...")
banner = diagonal_gradient(W, H)

# Decorative glows outside safe area for desktop/TV
banner = add_glow(banner, 220, 220, 380, ACCENT, 90)
banner = add_glow(banner, W - 320, H - 260, 460, GRAD_BOT_RIGHT, 110)
banner = add_glow(banner, W // 2, H // 2, 700, ACCENT, 50)

banner = draw_pixel_grid(banner)

draw = ImageDraw.Draw(banner)

# Center the phoenix mark inside the safe area, on the left
mark_cx = SAFE_X + 150
mark_cy = SAFE_Y + SAFE_H // 2 - 10
paste_mark(banner, mark_cx, mark_cy, 300)

# Wordmark + tagline to the right of the mark
title_font  = load_font(150, bold=True)
sub_font    = load_font(48, bold=False)
small_font  = load_font(34, bold=False)

text_x = mark_cx + 200
title_y = SAFE_Y + 60
draw.text((text_x, title_y), "BIRDO VPN", fill=WHITE, font=title_font)

# Underline accent bar
bar_y = title_y + 175
draw.rounded_rectangle(
    [text_x, bar_y, text_x + 360, bar_y + 8],
    radius=4,
    fill=ACCENT,
)

draw.text(
    (text_x, bar_y + 30),
    "Fast. Private. Quantum-ready WireGuard VPN.",
    fill=WHITE_A,
    font=sub_font,
)
draw.text(
    (text_x, bar_y + 95),
    "No logs  ·  No ads  ·  No trackers  ·  Open source",
    fill=WHITE_A,
    font=small_font,
)

# Footer URL inside safe area, bottom-right
url = "birdo.app"
url_bbox = draw.textbbox((0, 0), url, font=sub_font)
url_w = url_bbox[2] - url_bbox[0]
draw.text(
    (SAFE_X + SAFE_W - url_w - 20, SAFE_Y + SAFE_H - 70),
    url,
    fill=WHITE,
    font=sub_font,
)

# (Optional) faint safe-area outline — comment out for final
# draw.rectangle([SAFE_X, SAFE_Y, SAFE_X + SAFE_W, SAFE_Y + SAFE_H],
#                outline=WHITE_FAINT, width=2)

out_path = os.path.join(OUT, "youtube-banner-2560x1440.png")
banner.convert("RGB").save(out_path, "PNG", optimize=True)
print(f"  -> {out_path}")
print(f"  size: {os.path.getsize(out_path) // 1024} KB")
