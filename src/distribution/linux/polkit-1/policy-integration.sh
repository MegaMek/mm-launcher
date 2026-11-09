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

# Registers (install) or removes (uninstall) a polkit policy to allow seamless
# updates without prompting the user (implicit permission if active/user at
# computer), otherwise polkit requires explicit permission by prompting the
# user for a password in all other cases. Additionally, the permissions prompt
# is decorated with an explanatory message.
#
# Run as root by the .deb and .rpm package scripts.

set -u

LIB_DIR=/opt/megamek-launcher/lib
FILE_UPDATE_POLICY=org.megamek.launcher.update.policy
PATH_UPDATE_POLICY=$LIB_DIR/polkit-1/actions/$FILE_UPDATE_POLICY
PATH_POLKIT1_ACTION=/usr/share/polkit-1/actions/$FILE_UPDATE_POLICY

action() {
    VERB=$1; shift
    ERR="$(eval $* 2>&1 > /dev/null )"
    [ $? -ne 0 ] && echo -e "MegaMek Launcher: error while $VERB with message:\n\t$ERR" >&2
}

# A missing menu entry must never fail the package installation or a launcher update.
case "${1:-}" in
    install)
        # shellcheck disable=SC2086 # Entry paths contain no spaces; split on newlines.
        action "installing policy" cp $PATH_UPDATE_POLICY $PATH_POLKIT1_ACTION
        ;;
    uninstall)
        # shellcheck disable=SC2086
        action "uninstalling policy" rm $PATH_POLKIT1_ACTION
        ;;
    *)
        echo "Usage: $0 install|uninstall" >&2
        exit 2
        ;;
esac
exit 0
