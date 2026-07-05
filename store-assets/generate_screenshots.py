# Generates the Google Play phone screenshots + feature graphic for BirdoVPN.
# Clean, corporate, dark brand (no neon). 2x supersampled then downscaled for
# crisp anti-aliased text. Requires Pillow + the phoenix mark (brand-mark-1024).
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
S = 2  # supersample
W, H = 1080 * S, 1920 * S

# palette
BG        = (10, 10, 16)
BG2       = (16, 15, 26)
WHITE     = (255, 255, 255)
MUTED     = (150, 155, 170)
VIOLET    = (139, 92, 246)     # #8B5CF6
VIOLET_DK = (95, 60, 190)
GREEN     = (52, 211, 153)
CARD      = (22, 22, 32)
CARD_BRD  = (44, 44, 60)

FONTS = "C:/Windows/Fonts"
def font(name, px): return ImageFont.truetype(os.path.join(FONTS, name), px * S)
BOLD   = lambda px: font("segoeuib.ttf", px)
SEMI   = lambda px: font("seguisb.ttf" if os.path.exists(os.path.join(FONTS,"seguisb.ttf")) else "segoeuib.ttf", px)
REG    = lambda px: font("segoeui.ttf", px)
LIGHT  = lambda px: font("segoeuisl.ttf", px)

phoenix = Image.open(os.path.join(HERE, "brand-mark-1024.png")).convert("RGBA")

def radial_glow(size, color, radius_frac=0.9, alpha=70):
    w, h = size
    g = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(g)
    cx, cy = w // 2, int(h * 0.30)
    r = int(min(w, h) * radius_frac)
    for i in range(r, 0, -max(1, r // 90)):
        a = int(alpha * (1 - i / r))
        d.ellipse([cx - i, cy - i, cx + i, cy + i], fill=a)
    tint = Image.new("RGBA", (w, h), color + (0,))
    tint.putalpha(g)
    return tint

def base():
    img = Image.new("RGB", (W, H), BG)
    # subtle vertical shade
    top = Image.new("RGB", (W, H), BG2)
    mask = Image.new("L", (W, H), 0)
    md = ImageDraw.Draw(mask)
    for y in range(H):
        md.line([(0, y), (W, y)], fill=int(60 * (1 - y / H)))
    img = Image.composite(top, img, mask)
    img = img.convert("RGBA")
    img.alpha_composite(radial_glow((W, H), VIOLET, 0.85, 55))
    return img

def center_text(d, cx, y, text, fnt, fill, spacing=1.0):
    bb = d.textbbox((0, 0), text, font=fnt)
    w = bb[2] - bb[0]
    d.text((cx - w / 2, y), text, font=fnt, fill=fill)
    return (bb[3] - bb[1])

def wrap(d, text, fnt, maxw):
    words, lines, cur = text.split(), [], ""
    for wd in words:
        t = (cur + " " + wd).strip()
        if d.textlength(t, font=fnt) <= maxw: cur = t
        else: lines.append(cur); cur = wd
    if cur: lines.append(cur)
    return lines

def rrect(d, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)

def chip(d, cx, y, text, fnt):
    tw = d.textlength(text, font=fnt)
    padx, padyt = 28 * S, 14 * S
    w = tw + padx * 2; h = fnt.size + padyt * 2
    x0 = cx - w / 2
    rrect(d, [x0, y, x0 + w, y + h], r=h/2, fill=(26, 24, 40), outline=(70, 55, 120), width=S)
    d.text((x0 + padx, y + padyt - 2*S), text, font=fnt, fill=(200, 195, 220))
    return h

def header(img, d):
    ph = phoenix.resize((88 * S, 88 * S), Image.LANCZOS)
    x = W // 2 - int(150 * S)
    img.alpha_composite(ph, (x, 96 * S))
    d.text((x + 104 * S, 118 * S), "BirdoVPN", font=BOLD(40), fill=WHITE)

def big_glyph(img, d, kind):
    # a soft violet disc with a simple line-icon; drawn on an overlay for glow
    cx, cy, R = W // 2, int(H * 0.44), 210 * S
    disc = Image.new("RGBA", (W, H), (0,0,0,0))
    dd = ImageDraw.Draw(disc)
    dd.ellipse([cx-R, cy-R, cx+R, cy+R], fill=(139,92,246,40))
    dd.ellipse([cx-R, cy-R, cx+R, cy+R], outline=(139,92,246,150), width=3*S)
    img.alpha_composite(disc.filter(ImageFilter.GaussianBlur(2*S)))
    lc = (216, 208, 245)
    lw = 7 * S
    if kind == "shield":
        pts=[(cx,cy-120*S),(cx+95*S,cy-70*S),(cx+95*S,cy+20*S),(cx,cy+120*S),(cx-95*S,cy+20*S),(cx-95*S,cy-70*S)]
        dd2=ImageDraw.Draw(img); dd2.line(pts+[pts[0]],fill=lc,width=lw,joint="curve")
        dd2.line([(cx-40*S,cy),(cx-8*S,cy+38*S),(cx+52*S,cy-42*S)],fill=GREEN,width=lw,joint="curve")
    elif kind == "eye":
        dd2=ImageDraw.Draw(img)
        dd2.arc([cx-120*S,cy-70*S,cx+120*S,cy+70*S],200,340,fill=lc,width=lw)
        dd2.arc([cx-120*S,cy-10*S,cx+120*S,cy+130*S],20,160,fill=lc,width=lw)
        dd2.ellipse([cx-30*S,cy-30*S,cx+30*S,cy+30*S],outline=lc,width=lw)
        dd2.line([(cx-110*S,cy+95*S),(cx+110*S,cy-95*S)],fill=(239,68,68),width=lw)
    elif kind == "globe":
        dd2=ImageDraw.Draw(img); r=120*S
        dd2.ellipse([cx-r,cy-r,cx+r,cy+r],outline=lc,width=lw)
        dd2.ellipse([cx-r//2,cy-r,cx+r//2,cy+r],outline=lc,width=int(lw*0.7))
        dd2.line([(cx-r,cy),(cx+r,cy)],fill=lc,width=int(lw*0.7))
        dd2.arc([cx-r,cy-r//2,cx+r,cy+r*3//2],200,340,fill=lc,width=int(lw*0.7))
        dd2.arc([cx-r,cy-r*3//2,cx+r,cy+r//2],20,160,fill=lc,width=int(lw*0.7))
        for (px,py) in [(cx-60*S,cy-40*S),(cx+50*S,cy+30*S),(cx+30*S,cy-60*S)]:
            dd2.ellipse([px-9*S,py-9*S,px+9*S,py+9*S],fill=GREEN)
    elif kind == "lock":
        dd2=ImageDraw.Draw(img)
        rrect(dd2,[cx-85*S,cy-20*S,cx+85*S,cy+120*S],r=24*S,outline=lc,width=lw)
        dd2.arc([cx-55*S,cy-120*S,cx+55*S,cy+10*S],180,360,fill=lc,width=lw)
        dd2.line([(cx-55*S,cy-65*S),(cx-55*S,cy-15*S)],fill=lc,width=lw)
        dd2.line([(cx+55*S,cy-65*S),(cx+55*S,cy-15*S)],fill=lc,width=lw)
        dd2.ellipse([cx-16*S,cy+30*S,cx+16*S,cy+62*S],fill=lc)
    elif kind == "toggle":
        dd2=ImageDraw.Draw(img)
        for i,(on) in enumerate([True,False,True]):
            yy=cy-120*S+i*110*S
            rrect(dd2,[cx-150*S,yy,cx+150*S,yy+72*S],r=36*S,fill=CARD,outline=CARD_BRD,width=S)
            tcol=VIOLET if on else (70,70,86)
            rrect(dd2,[cx+40*S,yy+12*S,cx+138*S,yy+60*S],r=24*S,fill=tcol)
            knob_x = cx+96*S if on else cx+52*S
            dd2.ellipse([knob_x,yy+16*S,knob_x+40*S,yy+56*S],fill=WHITE)
            dd2.ellipse([cx-126*S,yy+20*S,cx-90*S,yy+56*S],fill=(60,55,80))
    elif kind == "bolt":
        dd2=ImageDraw.Draw(img)
        pts=[(cx+10*S,cy-130*S),(cx-70*S,cy+20*S),(cx-6*S,cy+20*S),(cx-14*S,cy+130*S),(cx+70*S,cy-30*S),(cx+4*S,cy-30*S)]
        dd2.polygon(pts,fill=VIOLET)
        dd2.line(pts+[pts[0]],fill=lc,width=int(lw*0.6),joint="curve")

def screenshot(fname, glyph, headline, subline, chips):
    img = base(); d = ImageDraw.Draw(img)
    header(img, d)
    big_glyph(img, d, glyph)
    y = int(H * 0.60)
    hf = BOLD(76)
    for ln in wrap(d, headline, hf, W - 200*S):
        h = center_text(d, W//2, y, ln, hf, WHITE); y += hf.size + 12*S
    y += 18*S
    sf = REG(38)
    for ln in wrap(d, subline, sf, W - 260*S):
        center_text(d, W//2, y, ln, sf, MUTED); y += sf.size + 12*S
    # chips row
    cy = int(H*0.90); cf = SEMI(28)
    total = sum(d.textlength(c,font=cf)+56*S+30*S for c in chips) - 30*S
    x = W//2 - total/2
    for c in chips:
        w = d.textlength(c,font=cf)+56*S
        rrect(d,[x,cy,x+w,cy+62*S],r=31*S,fill=(24,22,38),outline=(70,55,120),width=S)
        d.text((x+28*S,cy+14*S),c,font=cf,fill=(205,200,225)); x += w+30*S
    out = img.convert("RGB").resize((1080,1920), Image.LANCZOS)
    out.save(os.path.join(HERE, fname), "PNG")
    print("wrote", fname)

def feature_graphic():
    w,h=1024*S,500*S
    img=Image.new("RGB",(w,h),BG).convert("RGBA")
    img.alpha_composite(radial_glow((w,h),VIOLET,1.0,60).resize((w,h)))
    d=ImageDraw.Draw(img)
    ph=phoenix.resize((300*S,300*S),Image.LANCZOS)
    img.alpha_composite(ph,(90*S,100*S))
    tx=470*S
    d.text((tx,150*S),"BirdoVPN",font=BOLD(96),fill=WHITE)
    d.text((tx,275*S),"Fast, private WireGuard® VPN",font=REG(40),fill=(210,205,225))
    d.text((tx,330*S),"No logs. No ads. No trackers.",font=REG(40),fill=MUTED)
    img.convert("RGB").resize((1024,500),Image.LANCZOS).save(os.path.join(HERE,"feature-graphic-1024x500.png"),"PNG")
    print("wrote feature-graphic-1024x500.png")

feature_graphic()
screenshot("screenshot-01-privacy.png","shield","Private by design","A zero-logs WireGuard® VPN built for people who actually care about privacy.",["No logs","No ads","Open source"])
screenshot("screenshot-02-nologs.png","eye","We don't keep logs","No browsing history, DNS queries, or traffic - backed by an independent warrant canary.",["RAM-only","Warrant canary","UK company"])
screenshot("screenshot-03-servers.png","globe","One tap to connect","A global network of fast, low-latency servers. Switch locations anytime.",["Global network","Low latency","One tap"])
screenshot("screenshot-04-killswitch.png","lock","Always protected","Kill switch and DNS-leak protection keep your traffic covered if the tunnel ever drops.",["Kill switch","DNS protection","Auto-reconnect"])
screenshot("screenshot-05-split.png","toggle","Split tunnelling","Choose exactly which apps use the VPN — and which don't.",["Per-app","Full control","Quick tile"])
screenshot("screenshot-06-quantum.png","bolt","Post-quantum ready","ML-KEM-1024 protection against harvest-now, decrypt-later attacks.",["Post-quantum","WireGuard®","Stealth mode"])
print("done")
