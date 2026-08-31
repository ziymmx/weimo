#!/usr/bin/env python3
"""Weimo icon generator.

Turns the source artwork (art/icon-source.webp, a full-bleed flat green icon
with a cute character in the center) into every Android launcher mipmap.

Per the design request:
  * the artwork is used as-is (no cropping, no zooming the character);
  * only the outer edge is turned into a rounded rectangle;
  * a light unsharp mask keeps it crisp when downscaled to each mipmap size.

The background colour is derived from the artwork itself so the adaptive
foreground blends seamlessly with the background layer.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "art" / "icon-source.webp"
RES = ROOT / "app" / "src" / "main" / "res"

# Derived from the artwork: dominant uniform green (average of green pixels).
BACKGROUND_HEX = "CBE226"

LEGACY_SIZES = {
    "mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192,
}
FOREGROUND_SIZES = {
    "mipmap-mdpi": 108, "mipmap-hdpi": 162, "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324, "mipmap-xxxhdpi": 432,
}

# Rounded-rectangle corner radius, as a fraction of the icon size.
CORNER_RADIUS = 0.20


def background_color() -> tuple:
    return tuple(int(BACKGROUND_HEX[i:i + 2], 16) for i in (0, 2, 4))


def rounded_rect_mask(size: int, radius: int) -> Image.Image:
    """Grayscale alpha mask with rounded-rectangle corners."""
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=radius, fill=255
    )
    return mask


def make_icon(out_size: int) -> Image.Image:
    """Full artwork resized edge to edge, sharpened, with rounded corners."""
    src = Image.open(SRC).convert("RGB")
    img = src.resize((out_size, out_size), Image.LANCZOS)
    # Mild sharpening compensates for the slight blur introduced by downscaling.
    img = img.filter(ImageFilter.UnsharpMask(radius=2, percent=120, threshold=2))
    img = img.convert("RGBA")
    img.putalpha(rounded_rect_mask(out_size, int(out_size * CORNER_RADIUS)))
    return img


def update_colors_xml() -> None:
    import re
    path = RES / "values" / "colors.xml"
    text = path.read_text(encoding="utf-8")
    text = re.sub(
        r'<color name="ic_launcher_background">#[0-9A-Fa-f]{6}</color>',
        f'<color name="ic_launcher_background">#{BACKGROUND_HEX}</color>',
        text,
    )
    path.write_text(text, encoding="utf-8")
    print(f"colors.xml -> #{BACKGROUND_HEX}")


def main() -> None:
    if not SRC.exists():
        sys.exit(f"source icon not found: {SRC}")
    for folder, size in LEGACY_SIZES.items():
        out = RES / folder / "ic_launcher.png"
        make_icon(size).save(out, "PNG")
        print(f"{out.relative_to(ROOT)} ({size}x{size})")
    for folder, size in FOREGROUND_SIZES.items():
        out = RES / folder / "ic_launcher_foreground.png"
        make_icon(size).save(out, "PNG")
        print(f"{out.relative_to(ROOT)} ({size}x{size})")
    update_colors_xml()
    print("done")


if __name__ == "__main__":
    main()
