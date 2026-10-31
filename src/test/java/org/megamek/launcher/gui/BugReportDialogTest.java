/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek Launcher is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BugReportDialogTest {
    @Test
    void everyActionReachesItsCallbackAndTheStepButtonsShareASize() throws Exception {
        List<String> calls = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            BugReportDialog.Content content = BugReportDialog.content(GuiScale.DEFAULT,
                    new BugReportDialog.Actions(() -> calls.add("save"), () -> calls.add("form"),
                            () -> calls.add("discord")), () -> calls.add("close"));

            assertEquals(content.saveArchive().getPreferredSize(),
                    content.openIssueForm().getPreferredSize());
            assertFalse(content.archiveStatus().isVisible(), "no status until something is saved");
            assertEquals(Boolean.TRUE, content.saveArchive().getClientProperty(
                            OperationProgressDialog.OPERATION_CONTROL),
                    "browser work in the frame must not snapshot and later restore a stale Save state");
            assertInstanceOf(LauncherLink.class, find(content.panel(), "bugReportDiscordButton"));
            assertNotNull(find(content.panel(), "bugReportIntro"));

            ((AbstractButton) find(content.panel(), "saveBugReportArchiveButton")).doClick();
            ((AbstractButton) find(content.panel(), "openBugReportFormButton")).doClick();
            ((AbstractButton) find(content.panel(), "bugReportDiscordButton")).doClick();
            ((AbstractButton) find(content.panel(), "closeBugReportButton")).doClick();
        });
        assertEquals(List.of("save", "form", "discord", "close"), calls);
    }

    private static Component find(Container root, String name) {
        if (name.equals(root.getName())) return root;
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container container) {
                Component found = find(container, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
