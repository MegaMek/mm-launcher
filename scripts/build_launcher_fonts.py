"""Builds the launcher's bundled fonts from the MegaMek data fonts.

The launcher ships only what Swing draws: static Roboto Regular, Roboto Medium and Roboto Condensed
Medium, and a small Material Symbols Rounded subset holding the icons listed below. Java2D draws a
variable font's default instance only, so every face here is static. Needs `pip install fonttools`.

    python scripts/build_launcher_fonts.py <mm-data>/data/fonts

Add an icon by adding its code point (from https://fonts.google.com/icons) to ICONS and re-running.
"""
import pathlib
import shutil
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ICONS = {
    0xE000: "error",
    0xE002: "warning",
    0xE037: "play_arrow",
    0xE145: "add",
    0xE14D: "content_copy",
    0xE157: "link",
    0xE2C7: "folder",
    0xE2C8: "folder_open",
    0xE518: "light_mode",
    0xE51C: "dark_mode",
    0xE5C5: "arrow_drop_down",
    0xE5CA: "check",
    0xE5CB: "chevron_left",
    0xE5CC: "chevron_right",
    0xE5CD: "close",
    0xE5CE: "expand_less",
    0xE5CF: "expand_more",
    0xE5D3: "more_horiz",
    0xE5D4: "more_vert",
    0xE5D5: "refresh",
    0xE5D7: "unfold_more",
    0xE86C: "check_circle",
    0xE872: "delete",
    0xE887: "help",
    0xE88A: "home",
    0xE88E: "info",
    0xE89E: "open_in_new",
    0xE8B6: "search",
    0xE8B8: "settings",
    0xE8F4: "visibility",
    0xE923: "update",
    0xF090: "download",
}

TARGET = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "org" / "megamek" \
    / "launcher" / "design" / "fonts"


def static_roboto(source, target, weight, width, family):
    font = instancer.instantiateVariableFont(TTFont(source), {"wght": weight, "wdth": width})
    postscript = family.replace(" ", "-", 1).replace(" ", "")
    style = family[len("Roboto "):] if family.startswith("Roboto ") else "Regular"
    names = {1: family, 2: "Regular", 3: postscript + " static instance", 4: family, 6: postscript, 16: "Roboto",
             17: style}
    name = font["name"]
    for record in list(name.names):
        if record.nameID in (1, 2, 3, 4, 6, 16, 17, 21, 22, 25) or record.nameID >= 256:
            name.removeNames(nameID=record.nameID)
    # Java2D looks fonts up by family (ID 1) or full name (ID 4), so both carry the family.
    for name_id, value in names.items():
        name.setName(value, name_id, 3, 1, 0x409)
        name.setName(value, name_id, 1, 0, 0)
    font["OS/2"].usWeightClass = weight
    font["OS/2"].usWidthClass = {75: 3}.get(width, 5)
    font["OS/2"].fsSelection = (font["OS/2"].fsSelection & ~0b1100001) | 0b1000000
    font["head"].macStyle = 0
    font.save(target)


def symbols(source, target):
    font = instancer.instantiateVariableFont(TTFont(source), {"FILL": 0, "GRAD": 0, "opsz": 24, "wght": 400})
    options = subset.Options()
    options.layout_features = []
    options.name_IDs = ["*"]
    options.notdef_outline = True
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=sorted(ICONS))
    subsetter.subset(font)
    font.save(target)


def main(fonts):
    TARGET.mkdir(parents=True, exist_ok=True)
    variable = fonts / "Roboto" / "Roboto-VariableFont_wdth,wght.ttf"
    static_roboto(variable, TARGET / "Roboto-Regular.ttf", 400, 100, "Roboto")
    static_roboto(variable, TARGET / "Roboto-Medium.ttf", 500, 100, "Roboto Medium")
    static_roboto(variable, TARGET / "Roboto-CondensedMedium.ttf", 500, 75, "Roboto Condensed Medium")
    shutil.copyfile(fonts / "Roboto" / "OFL.txt", TARGET / "Roboto-OFL.txt")
    symbols(fonts / "Icons" / "MaterialSymbolsRounded[FILL,GRAD,opsz,wght].ttf",
            TARGET / "MaterialSymbolsRounded-Subset.ttf")
    shutil.copyfile(fonts / "Icons" / "LICENSE-2.0.txt", TARGET / "MaterialSymbols-LICENSE-2.0.txt")


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]))
