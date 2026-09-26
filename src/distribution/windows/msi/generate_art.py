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
