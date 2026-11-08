#!/bin/sh
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

# Registers (install) or removes (uninstall) the launcher's menu entry and the hidden
# MegaMek, MekHQ and MegaMekLab entries that give the games' windows their names and icons.
# Run as root by the .deb and .rpm package scripts.

set -u

LIB_DIR=/opt/megamek-launcher/lib
ENTRIES="$LIB_DIR/megamek-launcher-MegaMek_Launcher.desktop
$LIB_DIR/desktop-entries/megamek-launcher-MegaMek.desktop
$LIB_DIR/desktop-entries/megamek-launcher-MekHQ.desktop
$LIB_DIR/desktop-entries/megamek-launcher-MegaMekLab.desktop"

# xdg-desktop-menu installs into the first writable XDG_DATA_DIRS entry, which on systems
# with Flatpak is /var/lib/flatpak/exports/share. Pin the usual system location instead.
XDG_DATA_DIRS=/usr/share
export XDG_DATA_DIRS

# Earlier packages could leave entries in whichever directory XDG_DATA_DIRS listed first.
remove_misplaced_entries() {
    for dir in /usr/local/share/applications /var/lib/flatpak/exports/share/applications; do
        for entry in $ENTRIES; do
            rm -f "$dir/${entry##*/}"
        done
    done
}

# A missing menu entry must never fail the package installation or a launcher update.
case "${1:-}" in
    install)
        remove_misplaced_entries
        # shellcheck disable=SC2086 # Entry paths contain no spaces; split on newlines.
        xdg-desktop-menu install --mode system $ENTRIES ||
            echo "MegaMek Launcher: could not add the application menu entries." >&2
        ;;
    uninstall)
        # shellcheck disable=SC2086
        xdg-desktop-menu uninstall --mode system $ENTRIES ||
            echo "MegaMek Launcher: could not remove the application menu entries." >&2
        remove_misplaced_entries
        ;;
    *)
        echo "Usage: $0 install|uninstall" >&2
        exit 2
        ;;
esac
exit 0
