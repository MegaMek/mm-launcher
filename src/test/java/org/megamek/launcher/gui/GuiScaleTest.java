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

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiScaleTest {
    @Test
    void applicationScaleIsSeparateFromDeviceDpi() {
        GuiScale scale = new GuiScale(1.5f);
        assertEquals(18, scale.scaleForGUI(12));
        assertEquals(new Dimension(300, 150), scale.scaleForGUI(200, 100));
        assertEquals(18f, scale.font(new Font(Font.DIALOG, Font.PLAIN, 12),
                Font.BOLD, 12).getSize2D());
        assertEquals(12, GuiScale.DEFAULT.scaleForGUI(12));
        assertThrows(IllegalArgumentException.class, () -> new GuiScale(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new GuiScale(0));
    }

    @Test
    void tokenAccessorsScaleTheSharedDesignTokens() {
        GuiScale scale = new GuiScale(1.5f);
        org.megamek.launcher.design.DesignTokens tokens =
                org.megamek.launcher.design.DesignTokens.shipped();
        assertEquals(Math.round(Math.round(tokens.space().get("md")) * 1.5f), scale.space("md"));
        assertEquals(Math.round(Math.round(tokens.size().get("control.md")) * 1.5f),
                scale.size("control.md"));
        assertEquals(Math.round(Math.round(tokens.radius().get("md")) * 1.5f), scale.radius("md"));
        java.awt.Insets padding = scale.padding("sm", "xl").getBorderInsets(null);
        assertEquals(new java.awt.Insets(scale.space("sm"), scale.space("xl"),
                scale.space("sm"), scale.space("xl")), padding);
        Font headline = scale.font(new Font("Roboto", Font.PLAIN, 12), "headline");
        assertEquals(tokens.typeStyle("headline").size() * 1.5f, headline.getSize2D());
        assertEquals("Roboto Medium", headline.getFamily());
        assertEquals("Roboto", scale.font(new Font("Roboto", Font.BOLD, 12), "body").getFamily());
        assertThrows(IllegalArgumentException.class, () -> scale.space("huge"));
        assertThrows(IllegalArgumentException.class, () -> scale.size("control.xl"));
    }

    @Test
    void robotoFacesKeepSwingFontFallback() {
        // The look and feel's own Roboto falls back as far as the platform allows; derived faces must match it
        Font label = javax.swing.UIManager.getFont("Label.font");
        Font medium = GuiScale.DEFAULT.font(new Font("Roboto", Font.PLAIN, 12), Font.BOLD, 12);
        assertEquals("Roboto Medium", medium.getFamily());
        // Hebrew, Arabic and CJK, as in installation names or folder paths
        for (char character : new char[]{'\u05D0', '\u0627', '\u4E2D'}) {
            if (label.canDisplay(character)) {
                assertTrue(medium.canDisplay(character), "falls back for U+" + Integer.toHexString(character));
            }
        }
    }

    @Test
    void workAreaIsAlreadyInLogicalCoordinates() {
        Rectangle workArea = new Rectangle(1920, 0, 960, 520);
        Dimension fitted = GuiScale.fitWindow(new Dimension(1312, 560), workArea);
        assertEquals(new Dimension(902, 488), fitted);
        assertTrue(fitted.width <= workArea.width);
        assertTrue(fitted.height <= workArea.height);
    }

    @Test
    void fitsWholeArtworkWithoutCroppingOrStretching() {
        for (Dimension viewport : new Dimension[]{new Dimension(960, 560),
                new Dimension(800, 200), new Dimension(200, 800)}) {
            Rectangle image = GuiScale.fitImage(1280, 719, viewport.width, viewport.height);
            assertTrue(new Rectangle(viewport).contains(image));
            assertTrue(Math.abs((double) image.width / 1280 - (double) image.height / 719)
                    <= 1.0 / 719);
            assertEquals((viewport.width - image.width) / 2, image.x);
            assertEquals((viewport.height - image.height) / 2, image.y);
        }
        assertEquals(new Rectangle(), GuiScale.fitImage(1280, 719, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> GuiScale.fitImage(0, 719, 800, 400));
    }
}
