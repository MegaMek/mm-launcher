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

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchCurtainTest {
    private final AtomicLong now = new AtomicLong(1_000_000);

    @Test
    void statusLineAndGreetingTypeAfterTheDarkBeat() {
        assertEquals(0, LaunchCurtain.typed(0, 1000, 20));
        assertEquals(10, LaunchCurtain.typed(500, 1000, 20));
        assertEquals(20, LaunchCurtain.typed(1000, 1000, 20));
        assertEquals(20, LaunchCurtain.typed(Long.MAX_VALUE, 1000, 20));
        assertTrue(LaunchCurtain.TEXT_START + LaunchCurtain.GREETING_DELAY
                + LaunchCurtain.GREETING_TYPING < LaunchCurtain.GREETING_END,
                "the greeting holds, typed, before the window opens");
        assertEquals(0, LaunchCurtain.easeOut(-1));
        assertEquals(1, LaunchCurtain.easeOut(2));
    }

    @Test
    void statusLineNamesTheLauncherVersion() {
        assertEquals("MegaMek Launcher 0.50.12.3  //  Link established",
                LaunchCurtain.statusLine("0.50.12.3"));
        assertEquals("MegaMek Launcher dev  //  Link established", LaunchCurtain.statusLine(null));
    }

    @Test
    void shimmerSweepsFromOffTheLeftToOffTheRight() {
        LaunchCurtain curtain = curtain();
        LaunchCurtain.Band start = curtain.shimmerBand(0);
        LaunchCurtain.Band end = curtain.shimmerBand(1);
        assertTrue(start.x() + start.width() + start.lean() <= 0);
        assertTrue(end.x() >= 800);
        assertTrue(curtain.shimmerBand(0.5).x() > start.x());
    }

    @Test
    void hexGridCoversTheWindow() {
        java.awt.geom.Rectangle2D bounds = LaunchCurtain.hexGrid(800, 600, 22).getBounds2D();
        assertTrue(bounds.getMinX() <= 0 && bounds.getMinY() <= 0);
        assertTrue(bounds.getMaxX() >= 800 && bounds.getMaxY() >= 600);
    }

    @Test
    void apertureOpensFromTheCentreLineToTheWholeWindow() {
        assertEquals(0, LaunchCurtain.apertureHalf(0, 600));
        assertEquals(300, LaunchCurtain.apertureHalf(1, 600));
        assertEquals(300, LaunchCurtain.apertureHalf(2, 600));
        int half = LaunchCurtain.apertureHalf(0.5, 600);
        assertTrue(half > 0 && half < 300);
    }

    @Test
    void greetingWaitsForTheContentThenTypesAndOpens() throws Exception {
        onEdt(() -> {
            LaunchCurtain curtain = curtain();
            curtain.greet();
            assertEquals(LaunchCurtain.Phase.GREETING, curtain.phase());
            assertTrue(curtain.isVisible());

            advance(LaunchCurtain.GREETING_MAX_WAIT - 1, curtain);
            assertEquals(LaunchCurtain.Phase.GREETING, curtain.phase(), "still waiting for Home");
            curtain.contentReady("Welcome, Commander");
            paint(curtain);
            // The dark beat has long passed, so typing starts at once.
            long typing = LaunchCurtain.GREETING_END - LaunchCurtain.TEXT_START;
            advance(typing - 1, curtain);
            assertEquals(LaunchCurtain.Phase.GREETING, curtain.phase(), "the greeting finishes first");
            advance(1, curtain);
            assertEquals(LaunchCurtain.Phase.OPENING, curtain.phase());
            paint(curtain);
            advance(5_000, curtain);
            assertEquals(LaunchCurtain.Phase.SHIMMER, curtain.phase());
            assertTrue(curtain.isVisible());
            assertFalse(curtain.contains(400, 300), "the sweep lets clicks through");
            advance(LaunchCurtain.SHIMMER_DURATION / 2, curtain);
            paint(curtain);
            curtain.reveal();
            assertEquals(LaunchCurtain.Phase.SHIMMER, curtain.phase(), "a reveal leaves the sweep be");
            advance(LaunchCurtain.SHIMMER_DURATION, curtain);
            assertEquals(LaunchCurtain.Phase.HIDDEN, curtain.phase());
            assertFalse(curtain.isVisible());
        });
    }

    @Test
    void greetingOpensAnywayWhenTheContentNeverArrives() throws Exception {
        onEdt(() -> {
            LaunchCurtain curtain = curtain();
            curtain.greet();
            advance(LaunchCurtain.GREETING_MAX_WAIT, curtain);
            assertEquals(LaunchCurtain.Phase.OPENING, curtain.phase());
            curtain.contentReady("Welcome, Commander");
            assertEquals(LaunchCurtain.Phase.OPENING, curtain.phase(), "a late load changes nothing");
            curtain.hideCurtain();
        });
    }

    @Test
    void launchClosesToTheCardAndRevealsAgain() throws Exception {
        onEdt(() -> {
            LaunchCurtain curtain = curtain();
            curtain.closeForLaunch("MegaMek");
            assertEquals(LaunchCurtain.Phase.CLOSING, curtain.phase());
            assertEquals("Launching MegaMek", curtain.getAccessibleContext().getAccessibleName());
            long untilShown = curtain.untilLaunchShown();
            assertTrue(untilShown > 0, "an instant ready still sees the transition");
            paint(curtain);

            advance(5_000, curtain);
            assertEquals(LaunchCurtain.Phase.DEPLOYING, curtain.phase());
            advance(5_000, curtain);
            paint(curtain);
            assertEquals(0, curtain.untilLaunchShown());

            curtain.reveal();
            assertEquals(LaunchCurtain.Phase.OPENING, curtain.phase());
            advance(5_000, curtain);
            assertEquals(LaunchCurtain.Phase.SHIMMER, curtain.phase());
            curtain.closeForLaunch("MekHQ");
            assertEquals(LaunchCurtain.Phase.CLOSING, curtain.phase(), "a launch cuts the sweep short");
            assertTrue(curtain.contains(400, 300));
            curtain.hideCurtain();
            assertEquals(LaunchCurtain.Phase.HIDDEN, curtain.phase());
        });
    }

    @Test
    void contentReadyOutsideTheGreetingAndRevealWhileHiddenDoNothing() throws Exception {
        onEdt(() -> {
            LaunchCurtain curtain = curtain();
            curtain.contentReady("Welcome, Commander");
            curtain.reveal();
            assertEquals(LaunchCurtain.Phase.HIDDEN, curtain.phase());
            assertFalse(curtain.isVisible());
            assertEquals(0, curtain.untilLaunchShown());
        });
    }

    private LaunchCurtain curtain() {
        LaunchCurtain curtain = new LaunchCurtain(GuiScale.DEFAULT, now::get);
        curtain.setSize(800, 600);
        return curtain;
    }

    private void advance(long millis, LaunchCurtain curtain) {
        now.addAndGet(millis);
        curtain.tick();
    }

    private static void paint(LaunchCurtain curtain) {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            curtain.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }

    private static void onEdt(Runnable body) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] instanceof Exception exception) throw exception;
    }
}
