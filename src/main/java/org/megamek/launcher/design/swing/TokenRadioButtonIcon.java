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

package org.megamek.launcher.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import javax.swing.JComponent;

import com.formdev.flatlaf.icons.FlatRadioButtonIcon;
import com.formdev.flatlaf.ui.FlatUIUtils;

/**
 * The radio button at the design-system size, with the design-system focus ring a gap away from the circle; see
 * {@link TokenCheckBoxIcon}. The circle eases between states and its dot grows in.
 */
public class TokenRadioButtonIcon extends FlatRadioButtonIcon {
    /** The circle's diameter, FlatCheckBoxIcon's package-private ICON_SIZE. */
    private static final float SIZE = 15;

    public TokenRadioButtonIcon() {
        setScale(TokenCheckBoxIcon.size() / SIZE);
    }

    @Override
    protected Color getBorderColor(Component c, boolean selected, boolean indeterminate) {
        return TokenCheckBoxIcon.ease(c, TokenCheckBoxIcon.EDGE, super.getBorderColor(c, selected, indeterminate));
    }

    @Override
    protected Color getBackground(Component c, boolean selected, boolean indeterminate) {
        Color base = selected ? selectedBackground : background;
        Color target = super.getBackground(c, selected, indeterminate);
        return TokenCheckBoxIcon.ease(c, TokenCheckBoxIcon.FILL,
              target == null || base == null ? target : FlatUIUtils.deriveColor(target, base));
    }

    @Override
    protected Color getCheckmarkColor(Component c, boolean indeterminate) {
        return TokenCheckBoxIcon.disabledSelected(c, isSelected(c), super.getCheckmarkColor(c, indeterminate));
    }

    @Override
    protected void paintIcon(Component c, Graphics2D g) {
        super.paintIcon(c, g);
        // A cleared radio button shrinks its dot away; a selected one grows it in paintCheckmark
        if (!isSelected(c) && c instanceof JComponent component) {
            float dot = TokenMotion.value(component, TokenCheckBoxIcon.MARK, false, TokenMotion.fast());
            if (dot > 0) {
                g.setColor(getCheckmarkColor(c, false));
                dot(g, dot);
            }
        }
    }

    @Override
    protected void paintCheckmark(Component c, Graphics2D g) {
        dot(g, c instanceof JComponent component
              ? TokenMotion.value(component, TokenCheckBoxIcon.MARK, true, TokenMotion.fast()) : 1);
    }

    private void dot(Graphics2D g, float part) {
        float diameter = centerDiameter * part;
        float xy = (SIZE - diameter) / 2f;
        g.fill(new Ellipse2D.Float(xy, xy, diameter, diameter));
    }

    @Override
    protected void paintFocusBorder(Component c, Graphics2D g) {
        float width = focusWidth / getScale();
        float gap = TokenCheckBoxIcon.gap() / getScale();
        // The circle is 15 across at (0, 0), see FlatRadioButtonIcon.paintBorder
        Area ring = new Area(new Ellipse2D.Float(-width, -width, SIZE + width * 2, SIZE + width * 2));
        ring.subtract(new Area(new Ellipse2D.Float(-gap, -gap, SIZE + gap * 2, SIZE + gap * 2)));
        g.fill(ring);
    }
}
