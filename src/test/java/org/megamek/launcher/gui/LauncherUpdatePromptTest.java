/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LauncherUpdatePromptTest {
    @Test void linuxPackageUpdatesWarnAboutTheAdministratorPassword() {
        assertEquals("Version 0.1.1 is available.\n\nClose any running games before updating.\n\n"
                        + "Your system will ask for an administrator password, because the launcher is "
                        + "installed as a system package.",
                LauncherFrame.launcherUpdatePrompt("0.1.1", null, true));
    }

    @Test void otherUpdatesKeepTheConcisePromptAfterAnyNotice() {
        assertEquals("Windows requires a computer restart.\n\nVersion 0.1.1 is available.\n\n"
                        + "Close any running games before updating.",
                LauncherFrame.launcherUpdatePrompt("0.1.1", "Windows requires a computer restart.", false));
    }
}
