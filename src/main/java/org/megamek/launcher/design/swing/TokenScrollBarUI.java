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
import java.awt.Graphics;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.LookAndFeel;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatScrollBarUI;

/**
 * Scroll bars of the MegaMek look and feels (design system, Scrollbars): a thin pill thumb and no track
 * of their own, so a bar shows the color of the content it scrolls instead of a stripe. The pointer anywhere over the
 * bar eases in its rail and brightens the thumb.
 */
public class TokenScrollBarUI extends FlatScrollBarUI {
    /** Client property freezing a bar in {@link #HOVER} or {@link #PRESSED}, for the design gallery. */
    static final String FROZEN_STATE = "MegaMek.scrollBar.frozenState";
    static final String HOVER = "hover";
    static final String PRESSED = "pressed";

    private static final Color NO_TRACK = new Color(0, true);
    private static final String RAIL = "scrollBar.rail";
    private static final String THUMB = "scrollBar.thumb";

    public static ComponentUI createUI(JComponent c) {
        return new TokenScrollBarUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        // The scroll pane behind paints the content color, so the bar needs no background of its own
        LookAndFeel.installProperty(scrollbar, "opaque", false);
    }

    @Override
    public void update(Graphics g, JComponent c) {
        if (c.isOpaque()) {
            g.setColor(contentBackground(c));
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
        }
        paint(g, c);
    }

    @Override
    protected Color getTrackColor(JComponent c, boolean hover, boolean pressed) {
        boolean shown = hover || c.getClientProperty(FROZEN_STATE) != null;
        Color rail = TokenMotion.color(c, RAIL, shown ? hoverTrackColor : null, TokenMotion.hover(shown));
        return rail != null ? rail : NO_TRACK;
    }

    @Override
    protected Color getThumbColor(JComponent c, boolean hover, boolean pressed) {
        Object frozen = c.getClientProperty(FROZEN_STATE);
        boolean down = pressed || PRESSED.equals(frozen);
        boolean over = hover || frozen != null;
        Color target = super.getThumbColor(c, over, down);
        return TokenMotion.color(c, THUMB, target, down ? 0 : TokenMotion.hover(over));
    }

    /**
     * The color behind a bar: the scrolled view's when the bar belongs to a scroll pane and the view paints its own
     * background, otherwise the viewport's or the bar's own.
     */
    static Color contentBackground(JComponent bar) {
        Color content = bar.getParent() instanceof JScrollPane scroll ? contentBackground(scroll) : null;
        return content != null ? content : bar.getBackground();
    }

    /** The color of what {@code scroll} shows, or null when neither the view nor the viewport paints a background. */
    static Color contentBackground(JScrollPane scroll) {
        JViewport viewport = scroll.getViewport();
        if (viewport == null) {
            return null;
        }
        Component view = viewport.getView();
        if (view != null && view.isOpaque()) {
            return view.getBackground();
        }
        return viewport.isOpaque() ? viewport.getBackground() : null;
    }
}
