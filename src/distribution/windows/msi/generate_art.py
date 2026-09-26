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

"""Rebuild the WiX 3 wizard BMPs from the repository's licensed first-launch art.

Run from the repository root with: python src/distribution/windows/msi/generate_art.py
Requires Pillow. MSI bitmap dimensions are physical pixels for 180x234 and
370x44 dialog units at the standard 96 DPI (240x312 and 493x58).
"""

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[4]
DEST = Path(__file__).resolve().parent
SOURCE = ROOT / "artwork" / "MekHQ_Load_spooky_uhd.webp"


def save(image: Image.Image, name: str) -> None:
    image.convert("RGB").save(DEST / name, format="BMP")


with Image.open(SOURCE) as source:
    image = source.convert("RGB")

# Center the 'Mech in the left strip. No panel is baked into the bitmap:
# the right side of the dialog is the native MSI dialog background.
dialog = image.crop((285, 0, 825, 713)).resize((240, 312), Image.Resampling.LANCZOS)
save(dialog, "wizard-dialog.bmp")

# Street-level machinery and the 'Mech torso, not the empty sky at image top.
banner = image.crop((165, 236, 1170, 355)).resize((493, 58), Image.Resampling.LANCZOS)
save(banner, "wizard-banner.bmp")
