#!/usr/bin/env python3
# Render the legacy ic_launcher PNG fallbacks at every density.
#
# Source of truth is the vector geometry in
#   res/drawable/ic_launcher_foreground.xml
# which is designed on a 108-unit canvas. This script renders the same
# composition (deep-ink fill + rounded-square tile + stroke-only focus
# ring) at the per-density pixel sizes Android expects, with 4x super-
# sampling and Lanczos downscale for anti-aliasing quality.
#
# Square (`android:icon`) PNGs only. The round-icon fallbacks were
# dropped in v1.4.5: minSdk is 26, so every device resolves the adaptive
# icon (res/mipmap-anydpi-v26/ic_launcher.xml) and the platform derives
# the round / squircle / teardrop mask from it. A separate
# `android:roundIcon` set is pure dead weight on this floor, so this
# script no longer emits it.
#
# Run from the repo root:
#   python3 scripts/render_launcher_icons.py
#
# Idempotent. No external state. Safe to re-run.
#
# Densities (px) follow the standard Android scaling ladder:
#   mdpi    48  (baseline; 1x)
#   hdpi    72  (1.5x)
#   xhdpi   96  (2x)
#   xxhdpi  144 (3x)
#   xxxhdpi 192 (4x)

from __future__ import annotations

import os
from PIL import Image, ImageDraw

# Density -> (folder name, side length in px). Matches the existing
# folders under LauncherV15/app/src/main/res/.
DENSITIES = [
    ("mdpi",     48),
    ("hdpi",     72),
    ("xhdpi",    96),
    ("xxhdpi",  144),
    ("xxxhdpi", 192),
]

# Colours match res/values/colors.xml + ic_launcher_foreground.xml.
BG_INK   = (14,  17,  22,  255)   # #0E1116 deep ink
FG_TILE  = (245, 245, 244, 255)   # #F5F5F4 warm off-white
FG_RING  = (125, 211, 252, 255)   # #7DD3FC sky cyan

# 4x supersample then Lanczos downscale -> crisp edges at every density.
SUPERSAMPLE = 4

# Vector design canvas is 108 units; visual content lives inside the
# central 66u safe circle (radius 33u from centre 54,54).
DESIGN     = 108.0
TILE_HALF  =  18.0   # tile is 36x36, so half-side = 18u
TILE_CR    =   8.0   # tile corner radius
RING_R     =  30.0   # focus-ring radius
RING_SW    =   2.5   # focus-ring stroke width


def render_square(size_px: int) -> Image.Image:
    """Render the full-bleed square legacy icon at `size_px`."""
    s = size_px * SUPERSAMPLE
    img = Image.new("RGBA", (s, s), BG_INK)
    draw = ImageDraw.Draw(img, "RGBA")

    # Per-design-unit pixel size at the supersampled resolution.
    u = s / DESIGN
    cx = cy = s / 2.0

    # Rounded-square app tile.
    th = TILE_HALF * u
    cr = TILE_CR * u
    draw.rounded_rectangle(
        [(cx - th, cy - th), (cx + th, cy + th)],
        radius=cr,
        fill=FG_TILE,
    )

    # Focus ring. PIL's `width` is integer pixels; use the supersampled
    # stroke width so the downscale yields the right effective stroke.
    rr = RING_R * u
    sw = max(1, round(RING_SW * u))
    draw.ellipse(
        [(cx - rr, cy - rr), (cx + rr, cy + rr)],
        outline=FG_RING,
        width=sw,
    )

    return img.resize((size_px, size_px), Image.LANCZOS)


def main() -> None:
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res_root = os.path.join(repo_root, "LauncherV15", "app", "src", "main", "res")

    for density, size in DENSITIES:
        folder = os.path.join(res_root, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)

        sq_path = os.path.join(folder, "ic_launcher.png")

        # `optimize=True` strips tEXt chunks and re-runs zlib at level 9;
        # cuts the typical icon from ~1.2 KB to ~0.4 KB at no quality
        # cost (these are flat-colour images with hard edges).
        render_square(size).save(sq_path, "PNG", optimize=True)

        sq_kb = os.path.getsize(sq_path) / 1024.0
        print(f"  mipmap-{density:8s} {size:3d}px  square={sq_kb:.2f}KB")


if __name__ == "__main__":
    main()
