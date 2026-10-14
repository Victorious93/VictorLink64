#!/usr/bin/env python3
"""Regenerates launcher icons / TV banner from assets/logo.png so nothing is cropped by masks."""
from PIL import Image, ImageDraw, ImageChops
import numpy as np, os
R = 'app/src/main/res'
NAVY = (10, 17, 40)
logo = Image.open('assets/logo.png').convert('RGB')
a = np.asarray(logo).astype(np.float32)
bg = np.array([4, 19, 39], np.float32)
d = np.sqrt(((a - bg) ** 2).sum(-1))
alpha = np.clip((d - 10) / 30, 0, 1)  # soft key the logo's own dark background to transparent
rgba = np.dstack([a, alpha * 255]).astype(np.uint8)
logo_t = Image.fromarray(rgba, 'RGBA')
bbox = logo_t.getchannel('A').point(lambda v: 255 if v > 20 else 0).getbbox()
logo_t = logo_t.crop(bbox)

def fit(img, box):
    w, h = img.size; s = min(box / w, box / h)
    return img.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)

def canvas(size, content_frac, bgcolor=None):
    c = Image.new('RGBA', (size, size), bgcolor + (255,) if bgcolor else (0, 0, 0, 0))
    l = fit(logo_t, size * content_frac)
    c.alpha_composite(l, ((size - l.width) // 2, (size - l.height) // 2))
    return c

dens = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
for k, m in dens.items():
    dd = f'{R}/mipmap-{k}'; os.makedirs(dd, exist_ok=True)
    # adaptive foreground 108dp: logo fits within 66dp safe zone (61%) -> use 0.60 of the canvas
    canvas(round(108 * m), 0.60).save(f'{dd}/ic_launcher_foreground.png')
    # legacy square icon 48dp: navy rounded square, logo padded
    s = round(48 * m)
    sq = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    mask = Image.new('L', (s, s), 0); ImageDraw.Draw(mask).rounded_rectangle([0, 0, s - 1, s - 1], radius=s // 6, fill=255)
    sq.paste(canvas(s, 0.80, NAVY), (0, 0), mask); sq.save(f'{dd}/ic_launcher.png')
    # legacy round icon: logo inside inscribed circle (square of side 0.707 * d)
    rd = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    mask = Image.new('L', (s, s), 0); ImageDraw.Draw(mask).ellipse([0, 0, s - 1, s - 1], fill=255)
    rd.paste(canvas(s, 0.68, NAVY), (0, 0), mask); rd.save(f'{dd}/ic_launcher_round.png')
# TV banner 320x180: navy with logo fitted to height + padding
b = Image.new('RGBA', (320, 180), NAVY + (255,)); l = fit(logo_t, 150)
b.alpha_composite(l, ((320 - l.width) // 2, (180 - l.height) // 2)); b.convert('RGB').save(f'{R}/drawable-xhdpi/banner.png')
# Play store / web icon
canvas(512, 0.80, NAVY).convert('RGB').save('app/src/main/ic_launcher-web.png')
logo_t.save('/tmp/logo_transparent.png')

# Preview: masked adaptive icon (circle + squircle) and legacy icons
S = 432
full = Image.new('RGBA', (S, S), NAVY + (255,)); full.alpha_composite(canvas(S, 0.60))
inner = full.crop((S // 6 * 0, 0, S, S))
def masked(shape):
    vis = full.crop((S * 9 // 108, S * 9 // 108, S * 99 // 108, S * 99 // 108)).resize((360, 360), Image.LANCZOS)  # 72dp visible area
    m = Image.new('L', (360, 360), 0); dr = ImageDraw.Draw(m)
    if shape == 'circle': dr.ellipse([0, 0, 359, 359], fill=255)
    else:
        n = 4.0; pts = []
        import math
        for i in range(720):
            t = 2 * math.pi * i / 720; c, s_ = math.cos(t), math.sin(t)
            pts.append((180 + 179 * abs(c) ** (2 / n) * (1 if c >= 0 else -1), 180 + 179 * abs(s_) ** (2 / n) * (1 if s_ >= 0 else -1)))
        dr.polygon(pts, fill=255)
    o = Image.new('RGBA', (360, 360), (0, 0, 0, 0)); o.paste(vis, (0, 0), m); return o
prev = Image.new('RGBA', (360 * 4 + 100, 420), (235, 235, 240, 255))
prev.alpha_composite(masked('circle'), (20, 30)); prev.alpha_composite(masked('squircle'), (400, 30))
prev.alpha_composite(Image.open(f'{R}/mipmap-xxxhdpi/ic_launcher.png').resize((192 * 2 - 24, 360), Image.LANCZOS) if False else Image.open(f'{R}/mipmap-xxxhdpi/ic_launcher.png').resize((360, 360), Image.LANCZOS), (780, 30))
prev.alpha_composite(Image.open(f'{R}/mipmap-xxxhdpi/ic_launcher_round.png').resize((360, 360), Image.LANCZOS), (1160, 30))
dr = ImageDraw.Draw(prev)
for x, t in [(20, 'adaptive · circle'), (400, 'adaptive · squircle'), (780, 'legacy square'), (1160, 'legacy round')]: dr.text((x, 400), t, fill=(30, 30, 30))
prev.convert('RGB').save('/workspace/icon_preview.png')
