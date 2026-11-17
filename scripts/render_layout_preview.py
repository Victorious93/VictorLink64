#!/usr/bin/env python3
"""Victorious Framework: render the overlay layout dumps written by LayoutPreviewTool to PNG (needs Pillow).

    VL64_LAYOUT_PREVIEW_DIR=/tmp/layouts ./gradlew :app:testDebugUnitTest --tests '*LayoutPreviewTool*'
    python3 scripts/render_layout_preview.py /tmp/layouts
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

NAVY, CYAN, STEEL = (10, 17, 40), (63, 208, 255), (143, 163, 200)


def render(src: Path) -> Path:
    lines = src.read_text().splitlines()
    w, h = map(int, lines[0].split())
    img = Image.new("RGB", (w, h), (16, 20, 40))
    d = ImageDraw.Draw(img, "RGBA")
    if w > h:
        d.rectangle([0, 0, w, h], fill=(32, 40, 72))
    else:
        d.rectangle([0, 0, w, int(w * 0.75)], fill=(32, 40, 72))
    for ln in lines[1:]:
        cid, shape, accent, cx, cy, cw, ch = ln.split()
        cx, cy, cw, ch = map(float, (cx, cy, cw, ch))
        box = [cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2]
        fill = (*(CYAN if accent == "CYAN" else STEEL), 150)
        if shape in ("CIRCLE", "OVAL"):
            d.ellipse(box, fill=fill, outline=(255, 255, 255, 200))
        else:
            d.rounded_rectangle(box, radius=min(cw, ch) / 2, fill=fill, outline=(255, 255, 255, 200))
        d.text((cx - 8, cy - 5), cid, fill=(255, 255, 255, 255))
    out = src.with_suffix(".png")
    img.save(out)
    return out


if __name__ == "__main__":
    for p in sorted(Path(sys.argv[1]).glob("*.txt")):
        print(render(p))
