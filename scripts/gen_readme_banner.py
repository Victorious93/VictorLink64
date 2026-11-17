#!/usr/bin/env python3
"""Regenerates docs/assets/banner.png (1280x640) in VictorLink64's own brand colours.

Brand palette, sampled from assets/logo.png: navy, cyan-blue, and red (red is an accent only).
Run from the repository root: python3 scripts/gen_readme_banner.py

Victorious Framework
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1280, 640
NAVY = (4, 17, 37)
NAVY_LIGHT = (9, 30, 62)
CYAN_BLUE = (25, 194, 252)
BLUE = (30, 120, 255)
RED = (252, 54, 64)
WHITE = (240, 246, 255)
MUTED = (150, 190, 230)
FONT_DIR = '/usr/share/fonts/truetype/macos/'


def font(name, size):
    return ImageFont.truetype(FONT_DIR + name, size)


def vertical_gradient(size, top, bottom):
    w, h = size
    col = Image.new('RGB', (1, h))
    for y in range(h):
        t = y / (h - 1)
        col.putpixel((0, y), tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return col.resize((w, h))


def horizontal_bar(width, height, stops):
    bar = Image.new('RGB', (width, height))
    px = bar.load()
    for x in range(width):
        t = x / max(1, width - 1)
        for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
            if t0 <= t <= t1:
                k = (t - t0) / (t1 - t0)
                color = tuple(round(c0[i] + (c1[i] - c0[i]) * k) for i in range(3))
                break
        for y in range(height):
            px[x, y] = color
    return bar


def main():
    img = vertical_gradient((W, H), NAVY_LIGHT, NAVY).convert('RGBA')

    glow = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([20, 70, 540, 590], fill=CYAN_BLUE + (70,))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(70)))

    logo = Image.open('assets/logo.png').convert('RGBA').resize((440, 440), Image.LANCZOS)
    mask = Image.new('L', logo.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 439, 439], radius=80, fill=255)
    img.paste(logo, (70, 100), mask)

    d = ImageDraw.Draw(img)
    x0 = 590
    d.text((x0, 200), 'VictorLink64', font=font('Inter-Bold.ttf', 88), fill=WHITE)

    bar = horizontal_bar(600, 8, [(0, CYAN_BLUE), (0.85, BLUE), (1, RED)])
    img.paste(bar, (x0 + 4, 320))

    tag = font('Inter-Medium.ttf', 27)
    d.text((x0 + 4, 362), 'N64, Super Nintendo, PSP, and GameCube games,', font=tag, fill=CYAN_BLUE)
    d.text((x0 + 4, 402), 'built-in chess, and multiplayer with friends', font=tag, fill=CYAN_BLUE)
    d.text((x0 + 4, 442), 'in one Android app.', font=tag, fill=CYAN_BLUE)

    d.text((x0 + 4, 560), 'Part of the Victorious Framework fleet', font=font('Inter-Regular.ttf', 24), fill=MUTED)

    img.convert('RGB').save('docs/assets/banner.png', optimize=True)


if __name__ == '__main__':
    main()
