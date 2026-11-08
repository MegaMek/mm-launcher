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
import org.megamek.launcher.launch.LaunchOptions;

import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchOptionsFormRestoreDefaultsTest {
    @Test
    void restoreDefaultsMakesEveryOptionFollowTheLauncherDefault() throws Exception {
        LaunchOptions inherited = new LaunchOptions(2048, 4096, 2048,
                LaunchOptions.Renderer.DEFAULT, "", "");
        LaunchOptions overrides = new LaunchOptions(1024, 8192, 3072,
                LaunchOptions.Renderer.OPENGL, "-Dfixture=true", "C:\\Users\\Shared");
        AtomicInteger changes = new AtomicInteger();
        AtomicReference<LaunchOptions> before = new AtomicReference<>();
        AtomicReference<LaunchOptions> after = new AtomicReference<>();
        AtomicReference<LaunchOptionsForm> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            LaunchOptionsForm form = new LaunchOptionsForm(GuiScale.DEFAULT, inherited, overrides,
                    "Use launcher default", changes::incrementAndGet);
            created.set(form);
            before.set(form.values());
            changes.set(0);
            form.restoreDefaults();
            after.set(form.values());
        });
        assertEquals(overrides, before.get());
        assertEquals(LaunchOptions.INHERIT, after.get());
        assertTrue(changes.get() > 0, "the dialog clears its message when the form changes");
        JCheckBox inherit = (JCheckBox) SwingTestSupport.find(created.get(),
                "launchOptionsRendererInherit");
        assertTrue(inherit.isSelected());
        assertFalse(SwingTestSupport.find(created.get(), "launchOptionsJvmArguments").isEnabled());
        assertEquals("", ((javax.swing.JTextField) SwingTestSupport.find(created.get(),
                "launchOptionsJvmArguments")).getText());
    }
}
