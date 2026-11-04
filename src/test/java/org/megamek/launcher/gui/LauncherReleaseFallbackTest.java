/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;
import org.megamek.launcher.update.LauncherSelfUpdate;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherReleaseFallbackTest {
    @Test void missingInstallerToolsFallBackToTheReleasePageEvenBehindAnUpdateNotice() {
        var tools = new LauncherSelfUpdate.InstallerToolsUnavailableException(
                "Linux launcher updates require PolicyKit and dnf.");
        assertTrue(LauncherFrame.toolsUnavailable(tools));
        assertTrue(LauncherFrame.toolsUnavailable(new IOException(
                "Previous update failed.\nLauncher release lookup also failed: " + tools.getMessage(), tools)));
    }

    @Test void otherCheckFailuresStayErrors() {
        assertFalse(LauncherFrame.toolsUnavailable(
                new IOException("Running launcher version does not match its installed RPM package")));
        assertFalse(LauncherFrame.toolsUnavailable(new IOException("offline")));
    }
}
