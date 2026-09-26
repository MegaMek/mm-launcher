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
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/** A vector-painted command button, independent of the suite's texture and skin dependencies. */
final class FirstLaunchButton extends JButton {
    enum Size { REGULAR, SMALL }

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
        setFont(scale.font(getFont(), Font.BOLD, size == Size.SMALL ? 12f : 16f));
        int vertical = scale.scaleForGUI(size == Size.SMALL ? 8 : 14);
        int horizontal = scale.scaleForGUI(size == Size.SMALL ? 14 : 18);
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
                    text.setColor(!isEnabled() ? new Color(159, 174, 171)
                            : primary ? new Color(25, 34, 30) : new Color(239, 246, 240));
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
        return new Dimension(Math.max(preferred.width, scale.scaleForGUI(size == Size.SMALL ? 160 : 248)),
                Math.max(preferred.height, scale.scaleForGUI(size == Size.SMALL ? 36 : 54)));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int inset = scale.scaleForGUI(3);
            int cut = scale.scaleForGUI(8);
            int right = getWidth() - inset - 1;
            int bottom = getHeight() - inset - 1;
            Polygon plate = new Polygon(
                    new int[]{inset + cut, right, right, right - cut, inset, inset},
                    new int[]{inset, inset, bottom - cut, bottom, bottom, inset + cut}, 6);
            boolean pressed = getModel().isPressed() && getModel().isArmed();
            boolean hover = getModel().isRollover();
            Color top = primary ? new Color(226, 196, 125) : new Color(43, 65, 69);
            Color lower = primary ? new Color(192, 159, 88) : new Color(28, 47, 51);
            if (!isEnabled()) {
                top = new Color(54, 68, 69);
                lower = new Color(43, 55, 57);
            } else if (pressed) {
                top = lower;
            } else if (hover) {
                top = top.brighter();
                lower = lower.brighter();
            }
            canvas.setPaint(new GradientPaint(0, inset, top, 0, bottom, lower));
            canvas.fillPolygon(plate);
            canvas.setColor(primary ? new Color(239, 211, 147) : new Color(90, 119, 120));
            canvas.setStroke(new BasicStroke(scale.scaleForGUI(1f)));
            canvas.drawPolygon(plate);
            if (isFocusOwner()) {
                canvas.setColor(new Color(246, 238, 207));
                canvas.setStroke(new BasicStroke(scale.scaleForGUI(2f)));
                canvas.drawPolygon(plate);
            }
        } finally {
            canvas.dispose();
        }
        super.paintComponent(graphics);
    }
}
