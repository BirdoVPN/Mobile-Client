"""
Generate tablet-sized screenshots from phone screenshots.
Creates 7-inch (1200x1920) and 10-inch (1600x2560) tablet screenshots
by centering phone screenshots on a dark background with device-style framing.
"""

from PIL import Image, ImageDraw, ImageFilter
import os

STORE_DIR = os.path.dirname(os.path.abspath(__file__))

# Phone screenshots to process
PHONE_SCREENSHOTS = [
    "screenshot-01-login.png",
    "screenshot-02-home-disconnected.png",
    "screenshot-03-servers.png",
    "screenshot-04-settings.png",
    "screenshot-05-split-tunneling.png",
]

# Tablet dimensions (portrait)
TABLET_7_SIZE = (1200, 1920)
TABLET_10_SIZE = (1600, 2560)

# Background gradient colors (match app theme)
BG_TOP = (15, 60, 52)      # Dark emerald
BG_BOTTOM = (10, 35, 27)   # Darker emerald


def create_gradient(size, top_color, bottom_color):
    """Create a vertical gradient background."""
    img = Image.new('RGB', size)
    draw = ImageDraw.Draw(img)
    w, h = size
    for y in range(h):
        ratio = y / h
        r = int(top_color[0] + (bottom_color[0] - top_color[0]) * ratio)
        g = int(top_color[1] + (bottom_color[1] - top_color[1]) * ratio)
        b = int(top_color[2] + (bottom_color[2] - top_color[2]) * ratio)
        draw.line([(0, y), (w, y)], fill=(r, g, b))
    return img


def create_tablet_screenshot(phone_path, tablet_size, output_path):
    """Place phone screenshot centered on tablet-sized background."""
    phone = Image.open(phone_path)
    pw, ph = phone.size
    tw, th = tablet_size

    # Scale phone screenshot to fit nicely (about 65% of tablet height)
    target_h = int(th * 0.72)
    scale = target_h / ph
    new_w = int(pw * scale)
    new_h = int(ph * scale)
    phone_resized = phone.resize((new_w, new_h), Image.LANCZOS)

    # Create gradient background
    bg = create_gradient(tablet_size, BG_TOP, BG_BOTTOM)

    # Center the phone screenshot
    x = (tw - new_w) // 2
    y = (th - new_h) // 2

    # Draw a subtle phone frame (rounded rect border)
    draw = ImageDraw.Draw(bg)
    frame_padding = 4
    frame_rect = [
        x - frame_padding, y - frame_padding,
        x + new_w + frame_padding, y + new_h + frame_padding
    ]
    draw.rounded_rectangle(frame_rect, radius=24, outline=(80, 60, 120), width=2)

    # Add subtle shadow
    shadow = Image.new('RGBA', tablet_size, (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow)
    shadow_rect = [
        x - frame_padding + 4, y - frame_padding + 4,
        x + new_w + frame_padding + 4, y + new_h + frame_padding + 4
    ]
    shadow_draw.rounded_rectangle(shadow_rect, radius=24, fill=(0, 0, 0, 60))
    shadow = shadow.filter(ImageFilter.GaussianBlur(8))

    # Composite: background + shadow + phone screenshot
    bg_rgba = bg.convert('RGBA')
    bg_rgba = Image.alpha_composite(bg_rgba, shadow)
    bg = bg_rgba.convert('RGB')

    # Paste the phone screenshot
    bg.paste(phone_resized, (x, y))

    bg.save(output_path, 'PNG')
    print(f"  Created: {output_path} ({os.path.getsize(output_path)} bytes)")


def main():
    print("Generating tablet screenshots...")

    for i, fname in enumerate(PHONE_SCREENSHOTS, 1):
        phone_path = os.path.join(STORE_DIR, fname)
        if not os.path.exists(phone_path):
            print(f"  SKIP: {fname} not found")
            continue

        # 7-inch tablet
        out_7 = os.path.join(STORE_DIR, f"tablet-7inch-{i:02d}.png")
        create_tablet_screenshot(phone_path, TABLET_7_SIZE, out_7)

        # 10-inch tablet
        out_10 = os.path.join(STORE_DIR, f"tablet-10inch-{i:02d}.png")
        create_tablet_screenshot(phone_path, TABLET_10_SIZE, out_10)

    print("\nDone! All tablet screenshots generated.")


if __name__ == "__main__":
    main()
