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

import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.Window;
import javax.swing.JWindow;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Fades popups, menus and tooltips in ({@code fast}, design system, Motion). It wraps the popup factory
 * FlatLaf installs and eases the opacity of popup windows as they open. A popup that fits inside its window is
 * lightweight and has no window of its own, so it appears at once; so does one whose screen cannot draw translucent
 * windows, and every popup while motion is reduced. A popup window that is already showing, such as a tooltip following
 * the pointer, is reused without fading again.
 */
final class TokenPopupFactory extends PopupFactory {
    private static PopupFactory previous;

    private final PopupFactory delegate;

    private TokenPopupFactory(PopupFactory delegate) {
        this.delegate = delegate;
    }

    /** Wraps the current popup factory; call after FlatLaf has installed its own. */
    static void install() {
        PopupFactory current = PopupFactory.getSharedInstance();
        if (!(current instanceof TokenPopupFactory)) {
            previous = current;
            PopupFactory.setSharedInstance(new TokenPopupFactory(current));
        }
    }

    /** Restores the wrapped factory, before FlatLaf restores the one it replaced. */
    static void uninstall() {
        if (previous != null && PopupFactory.getSharedInstance() instanceof TokenPopupFactory) {
            PopupFactory.setSharedInstance(previous);
        }
        previous = null;
    }

    @Override
    public Popup getPopup(Component owner, Component contents, int x, int y) {
        return new Fading(delegate.getPopup(owner, contents, x, y), contents);
    }

    private static final class Fading extends Popup {
        private final Popup popup;
        private final Component contents;
        private Timer timer;

        private Fading(Popup popup, Component contents) {
            this.popup = popup;
            this.contents = contents;
        }

        @Override
        public void show() {
            Window window = SwingUtilities.getWindowAncestor(contents);
            boolean fade = !TokenMotion.isReduced() && window != null && !window.isVisible() && isTranslucent(window);
            if (fade) {
                window.setOpacity(0);
            }
            popup.show();
            if (fade) {
                fadeIn(window);
            }
        }

        @Override
        public void hide() {
            Window window = SwingUtilities.getWindowAncestor(contents);
            stop(window);
            popup.hide();
        }

        private void fadeIn(Window window) {
            int duration = TokenMotion.fast();
            long start = System.nanoTime();
            timer = new Timer(16, event -> {
                float t = Math.min(1, (System.nanoTime() - start) / 1_000_000f / duration);
                if (t >= 1 || !window.isShowing()) {
                    stop(window);
                } else {
                    window.setOpacity(TokenMotion.ease(t));
                }
            });
            timer.start();
        }

        /** Stops fading and leaves the window opaque, since popup windows are cached and reused. */
        private void stop(Window window) {
            if (timer != null) {
                timer.stop();
                timer = null;
                if (window != null && isTranslucent(window)) {
                    window.setOpacity(1);
                }
            }
        }

        private static boolean isTranslucent(Window window) {
            GraphicsConfiguration configuration = window.getGraphicsConfiguration();
            return window instanceof JWindow && configuration != null
                  && configuration.getDevice().isWindowTranslucencySupported(WindowTranslucency.TRANSLUCENT);
        }
    }
}
