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

import org.megamek.launcher.design.swing.TokenPresentation;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/** A design-token command button: a cream primary face or a raised secondary face. */
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
        setFont(scale.font(getFont(), size == Size.REGULAR ? "title" : "label"));
        setBorder(size == Size.COMPACT ? scale.padding("xs", "md") : scale.padding("md", "xl"));
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        // Upper-cased and condensed only on a game screen; app screens keep the sentence-case label
        TokenPresentation.role(this, "button");
    }

    @Override
    public void updateUI() {
        // The look and feel paints nothing here; a theme change keeps the token painting.
        setUI(new BasicButtonUI() {
            @Override
            protected void paintText(Graphics graphics, JComponent component,
                                     Rectangle textRectangle, String value) {
                Graphics2D text = (Graphics2D) graphics.create();
                try {
                    ButtonDetail.paint(text, FirstLaunchButton.this, scale, textRectangle, value,
                            LauncherTheme.buttonText(primary, isEnabled()));
                } finally {
                    text.dispose();
                }
            }
        });
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = ButtonDetail.grow(this, scale, super.getPreferredSize());
        int minimumWidth = size == Size.REGULAR ? 248 : size == Size.SMALL ? 160 : 0;
        int minimumHeight = scale.size(size == Size.REGULAR ? "control.lg" : "control.md");
        return new Dimension(Math.max(preferred.width, scale.scaleForGUI(minimumWidth)),
                Math.max(preferred.height, minimumHeight));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        try {
            LauncherTheme.paintButton(canvas, getWidth(), getHeight(), scale, primary, isEnabled(),
                    getModel().isPressed() && getModel().isArmed(), getModel().isRollover(), isFocusOwner());
        } finally {
            canvas.dispose();
        }
        super.paintComponent(graphics);
    }
}
