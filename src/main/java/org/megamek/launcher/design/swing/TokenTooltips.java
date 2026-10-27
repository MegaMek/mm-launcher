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

import javax.swing.JComponent;

/**
 * The design system's tooltips for Swing (design system, Tooltips). The MegaMek look and feels draw
 * every plain-text tooltip as a small card on the popup surface and wrap long text; this adds the shortcut key chip and
 * the info card, a title over a secondary detail text. HTML tooltips keep their own layout inside the same card.
 */
public final class TokenTooltips {
    /** Client property holding the {@link Card} a component's tooltip draws; set through {@link #card}. */
    static final String CARD = "MegaMek.toolTip.card";
    static final String MAX_WIDTH = "MegaMek.toolTip.maxWidth";
    static final String DETAIL_FOREGROUND = "MegaMek.toolTip.detailForeground";
    static final String KEY_FOREGROUND = "MegaMek.toolTip.keyForeground";
    static final String KEY_BORDER_COLOR = "MegaMek.toolTip.keyBorderColor";

    private TokenTooltips() {
    }

    /** Gives {@code component} the tooltip {@code text} with the shortcut {@code key}, such as "Ctrl+S", in a chip. */
    public static <T extends JComponent> T withKey(T component, String text, String key) {
        return card(component, null, text, key);
    }

    /**
     * Gives {@code component} an info card: {@code title} in strong text over the secondary {@code detail}, with an
     * optional shortcut {@code key}. Either text may be null, but not both. Screen readers get the title and the
     * detail as the tooltip text.
     */
    public static <T extends JComponent> T card(T component, String title, String detail, String key) {
        Card card = new Card(blankToNull(title), blankToNull(detail), blankToNull(key));
        if (card.title() == null && card.detail() == null) {
            throw new IllegalArgumentException("A tooltip needs a title or a detail text");
        }
        component.putClientProperty(CARD, card);
        component.setToolTipText(card.text());
        return component;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** What a tooltip card shows; {@link #text} is the plain tooltip text the card stands for. */
    record Card(String title, String detail, String key) {
        String text() {
            if (title == null) {
                return detail;
            }
            return detail == null ? title : title + "\n" + detail;
        }
    }
}
