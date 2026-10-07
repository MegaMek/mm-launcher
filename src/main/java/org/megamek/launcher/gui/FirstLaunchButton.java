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

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/** A map-style command button, independent of the suite's texture and skin dependencies. */
final class FirstLaunchButton extends JButton {
    enum Size { REGULAR, SMALL, COMPACT }

    private final GuiScale scale;
    private final boolean primary;
    private final Size size;

    FirstLaunchButton(String text, String name, boolean primary, GuiScale scale) {
        this(text, name, primary, scale, Size.REGULAR);
    }

    FirstLaunchButton(String text, String name, boolean primary, GuiScale scale, Size size) {
        super(text);
        this.scale = scale;
        this.primary = primary;
        this.size = size;
        setName(name);
        getAccessibleContext().setAccessibleName(text);
        setFont(scale.font(getFont(), Font.BOLD, size == Size.REGULAR ? 16f : 12f));
        int vertical = scale.scaleForGUI(size == Size.REGULAR ? 14 : size == Size.SMALL ? 8 : 5);
        int horizontal = scale.scaleForGUI(size == Size.REGULAR ? 18 : size == Size.SMALL ? 14 : 10);
        setBorder(BorderFactory.createEmptyBorder(vertical, horizontal, vertical, horizontal));
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        setUI(new BasicButtonUI() {
            @Override
            protected void paintText(Graphics graphics, JComponent component,
                                     Rectangle textRectangle, String value) {
                Graphics2D text = (Graphics2D) graphics.create();
                try {
                    text.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    text.setColor(LauncherTheme.buttonText(primary, isEnabled()));
                    BasicGraphicsUtils.drawStringUnderlineCharAt(text, value,
                            getDisplayedMnemonicIndex(), textRectangle.x,
                            textRectangle.y + text.getFontMetrics().getAscent());
                } finally {
                    text.dispose();
                }
            }
        });
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        int minimumWidth = size == Size.REGULAR ? 248 : size == Size.SMALL ? 160 : 0;
        int minimumHeight = size == Size.REGULAR ? 54 : size == Size.SMALL ? 36 : LauncherTheme.CONTROL_HEIGHT;
        return new Dimension(Math.max(preferred.width, scale.scaleForGUI(minimumWidth)),
                Math.max(preferred.height, scale.scaleForGUI(minimumHeight)));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            LauncherTheme.paintButton(canvas, getWidth(), getHeight(), scale, isEnabled(),
                    getModel().isPressed() && getModel().isArmed(), getModel().isRollover(), isFocusOwner());
        } finally {
            canvas.dispose();
        }
        super.paintComponent(graphics);
    }

    @Override
    public javax.swing.JToolTip createToolTip() {
        return LauncherTheme.toolTip(this, scale);
    }
}
