#!/usr/bin/env python3
"""Builds the README images from the Robolectric UI render tests' screenshots.

    ./gradlew testDebugUnitTest --tests '*ScreensRenderTest'
    python3 scripts/docs/readme-images.py

Writes docs/assets/hero.png (three framed phones) and refreshes docs/screenshots/*.png.
Needs Pillow.
"""
import shutil
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
SHOTS = ROOT / "app/build/screenshots"
ASSETS = ROOT / "docs/assets"
GALLERY = ROOT / "docs/screenshots"

# README gallery: published name -> render-test screenshot
GALLERY_FILES = {
    "conversation.png": "02-conversation-turns.png",
    "conversation-dark.png": "08-conversation-dark.png",
    "face-to-face.png": "03-face-to-face.png",
    "settings.png": "04-settings.png",
    "settings-listening.png": "04b-settings-listening.png",
    "providers.png": "05-providers.png",
    "elevenlabs.png": "07-elevenlabs.png",
    "command-code-zdr.png": "06-command-code.png",
    "diagnostics.png": "04f-diagnostics.png",
}

HERO = ["03-face-to-face.png", "08-conversation-dark.png", "04-settings.png"]


def rounded(img: Image.Image, radius: int) -> Image.Image:
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img.convert("RGBA"), (0, 0), mask)
    return out


def phone(screen: Image.Image, height: int) -> Image.Image:
    """A screenshot in a simple dark bezel with rounded corners."""
    bezel = max(12, height // 55)
    inner_h = height - 2 * bezel
    inner_w = round(screen.width * inner_h / screen.height)
    shot = rounded(screen.resize((inner_w, inner_h), Image.LANCZOS), radius=inner_w // 11)
    body = Image.new("RGBA", (inner_w + 2 * bezel, height), (0, 0, 0, 0))
    ImageDraw.Draw(body).rounded_rectangle((0, 0, body.width - 1, height - 1), (inner_w + 2 * bezel) // 10, fill=(18, 22, 28, 255))
    body.alpha_composite(shot, (bezel, bezel))
    return body


def gradient(size, top, bottom) -> Image.Image:
    w, h = size
    base = Image.new("RGB", size, top)
    draw = ImageDraw.Draw(base)
    for y in range(h):
        t = y / max(1, h - 1)
        draw.line([(0, y), (w, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return base.convert("RGBA")


def hero() -> None:
    W, H = 2400, 1500
    canvas = gradient((W, H), (7, 38, 59), (31, 107, 154))
    # soft yellow glow behind the middle phone, like the banner
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((W // 2 - 650, H // 2 - 520, W // 2 + 650, H // 2 + 520), fill=(255, 210, 63, 60))
    canvas.alpha_composite(glow.filter(ImageFilter.GaussianBlur(160)))

    heights = [1180, 1290, 1180]
    centres = [W // 2 - 720, W // 2, W // 2 + 720]
    for name, h, cx in zip(HERO, heights, centres):
        p = phone(Image.open(SHOTS / name), h)
        x, y = cx - p.width // 2, (H - h) // 2 + (0 if h == max(heights) else 40)
        shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).rounded_rectangle((x + 10, y + 36, x + p.width + 10, y + h + 36), p.width // 10, fill=(0, 0, 0, 150))
        canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(36)))
        canvas.alpha_composite(p, (x, y))

    out = rounded(canvas, radius=48)
    ASSETS.mkdir(parents=True, exist_ok=True)
    out.resize((W // 2, H // 2), Image.LANCZOS).save(ASSETS / "hero.png", optimize=True)


def gallery() -> None:
    GALLERY.mkdir(parents=True, exist_ok=True)
    for published, source in GALLERY_FILES.items():
        img = Image.open(SHOTS / source).convert("RGB")
        img = img.resize((img.width * 2 // 3, img.height * 2 // 3), Image.LANCZOS)
        img.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).save(GALLERY / published, optimize=True)


if __name__ == "__main__":
    missing = [n for n in set(HERO) | set(GALLERY_FILES.values()) if not (SHOTS / n).exists()]
    if missing:
        sys.exit(f"missing screenshots (run the ScreensRenderTest first): {', '.join(sorted(missing))}")
    hero()
    gallery()
    print(f"wrote {ASSETS / 'hero.png'} and {len(GALLERY_FILES)} screenshots in {GALLERY}")
