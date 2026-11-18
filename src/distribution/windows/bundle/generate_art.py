# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
#
# This file is part of MegaMek Launcher.
#
# MegaMek Launcher is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License (GPL),
# version 3 or (at your option) any later version,
# as published by the Free Software Foundation.
#
# MegaMek Launcher is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty
# of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
# See the GNU General Public License for more details.
#
# A copy of the GPL should have been included with this project;
# if not, see <https://www.gnu.org/licenses/>.
#
# NOTICE: The MegaMek organization is a non-profit group of volunteers
# creating free software for the BattleTech community.
#
# MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
# of The Topps Company, Inc. All Rights Reserved.
#
# Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
# InMediaRes Productions, LLC.
#
# MechWarrior Copyright Microsoft Corporation. MegaMek was created under
# Microsoft's "Game Content Usage Rules"
# <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
# affiliated with Microsoft.
#

"""Rebuild the setup bundle theme PNGs from the first-launch art and the
launcher design tokens (dark MekBay palette).

Run from the repository root with: python src/distribution/windows/bundle/generate_art.py
Requires Pillow. Sizes are logical pixels of theme.xml at 100% scale.
"""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[4]
DEST = Path(__file__).resolve().parent / "art"
SOURCE = ROOT / "artwork" / "MekHQ_Load_spooky_uhd.webp"

SURFACE_BASE = (0x0E, 0x13, 0x14)
SURFACE_RAISED = (0x1C, 0x24, 0x26)
SURFACE_INPUT = (0x1D, 0x24, 0x26)
INVERSE = (0xF0, 0xF2, 0xEE)
ACCENT = (0x82, 0xE2, 0xCE)
BORDER_CONTROL = (0x6B, 0x77, 0x74)
BORDER_SUBTLE = (0x2A, 0x31, 0x33)
BORDER_STRONG = (0xE8, 0xF1, 0xEB)

SIDE = (280, 450)
BUTTON = (136, 38)

DEST.mkdir(exist_ok=True)


def save(image: Image.Image, name: str) -> None:
    image.save(DEST / name, format="PNG", optimize=True)


def mix(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


with Image.open(SOURCE) as source:
    art = source.convert("RGB")

# Full-height strip centered on the 'Mech, fading into the window surface on
# the right and darkening toward the bottom like the launcher's hero panels.
crop_w = round(art.height * SIDE[0] / SIDE[1])
left = 555 - crop_w // 2
side = art.crop((left, 0, left + crop_w, art.height)).resize(SIDE, Image.Resampling.LANCZOS)
shade = Image.new("RGB", SIDE, SURFACE_BASE)
mask = Image.new("L", SIDE, 0)
pixels = mask.load()
for y in range(SIDE[1]):
    bottom = max(0.0, (y - SIDE[1] * 0.55) / (SIDE[1] * 0.45)) * 0.75
    for x in range(SIDE[0]):
        right = max(0.0, (x - (SIDE[0] - 70)) / 70)
        pixels[x, y] = round(255 * min(1.0, max(bottom, right ** 1.6)))
side = Image.composite(shade, side, mask)
draw = ImageDraw.Draw(side)
# Corner ticks, the suite's frame motif.
for x0, y0, dx, dy in ((16, 16, 1, 1), (16, SIDE[1] - 17, 1, -1)):
    draw.line((x0, y0, x0 + 18 * dx, y0), fill=ACCENT, width=2)
    draw.line((x0, y0, x0, y0 + 18 * dy), fill=ACCENT, width=2)
save(side, "side.png")

# Short mint rule under the title.
save(Image.new("RGB", (56, 3), ACCENT), "rule.png")


def button(face, edge, ring=None, ticks=None):
    image = Image.new("RGB", BUTTON, face)
    draw = ImageDraw.Draw(image)
    w, h = BUTTON
    draw.rectangle((0, 0, w - 1, h - 1), outline=edge)
    if ring:
        draw.rectangle((1, 1, w - 2, h - 2), outline=ring)
        draw.rectangle((2, 2, w - 3, h - 3), outline=ring)
    if ticks:
        for x0, y0, dx, dy in ((0, 0, 1, 1), (w - 1, 0, -1, 1), (0, h - 1, 1, -1), (w - 1, h - 1, -1, -1)):
            draw.line((x0, y0, x0 + 7 * dx, y0), fill=ticks, width=2)
            draw.line((x0, y0, x0, y0 + 7 * dy), fill=ticks, width=2)
    return image


# Primary: the cream inverse face. Secondary: raised surface with a control edge.
save(button(INVERSE, INVERSE), "primary.png")
save(button((0xFF, 0xFF, 0xFF), (0xFF, 0xFF, 0xFF), ticks=ACCENT), "primary-hover.png")
save(button(mix(INVERSE, SURFACE_BASE, 0.18), mix(INVERSE, SURFACE_BASE, 0.18)), "primary-pressed.png")
save(button(INVERSE, INVERSE, ring=ACCENT), "primary-focus.png")
save(button(SURFACE_RAISED, BORDER_CONTROL), "secondary.png")
save(button(mix(SURFACE_RAISED, (0xFF, 0xFF, 0xFF), 0.06), BORDER_STRONG, ticks=BORDER_STRONG), "secondary-hover.png")
save(button(SURFACE_BASE, BORDER_STRONG), "secondary-pressed.png")
save(button(SURFACE_RAISED, BORDER_CONTROL, ring=ACCENT), "secondary-focus.png")

# thmutil progress bars: column 0 left cap, 1 used, 2 unused, 3 right cap.
bar = Image.new("RGB", (4, 6))
for x, color in enumerate((BORDER_SUBTLE, ACCENT, SURFACE_INPUT, BORDER_SUBTLE)):
    for y in range(6):
        bar.putpixel((x, y), color)
save(bar, "progress.png")
