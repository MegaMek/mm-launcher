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
import java.util.List;
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
    void scanLocksOntoTargetsAsItPassesAndWaitsForTheBrackets() throws Exception {
        onEdt(() -> {
            javax.swing.JPanel page = new javax.swing.JPanel(null);
            page.setSize(800, 600);
            LaunchCurtain curtain = curtain();
            page.add(curtain);
            javax.swing.JPanel left = new javax.swing.JPanel();
            left.setBounds(80, 260, 160, 80);
            javax.swing.JPanel right = new javax.swing.JPanel();
            right.setBounds(560, 260, 160, 80);
            page.add(left);
            page.add(right);
            curtain.setTargets(() -> java.util.List.of(left, right));

            curtain.closeForLaunch("MegaMek");
            advance(5_000, curtain);
            curtain.reveal();
            advance(5_000, curtain);
            assertEquals(LaunchCurtain.Phase.SHIMMER, curtain.phase());
            assertEquals(0, curtain.lockedTargets(), "nothing is locked before the scan moves");
            advance(LaunchCurtain.SHIMMER_DURATION / 2, curtain);
            paint(curtain);
            assertEquals(1, curtain.lockedTargets(), "the scan has passed only the left target");
            advance(LaunchCurtain.SHIMMER_DURATION / 2, curtain);
            assertEquals(2, curtain.lockedTargets());
            paint(curtain);
            assertEquals(LaunchCurtain.Phase.SHIMMER, curtain.phase(),
                    "the sweep waits for the last brackets to fade");
            advance(LaunchCurtain.LOCK_DURATION + 1, curtain);
            assertEquals(LaunchCurtain.Phase.HIDDEN, curtain.phase());
            assertEquals(0, curtain.lockedTargets());
        });
    }

    @Test
    void restoringAMinimisedOpeningCarriesOnFromWhereItStopped() throws Exception {
        onEdt(() -> {
            LaunchCurtain curtain = curtain();
            curtain.closeForLaunch("MegaMek");
            advance(5_000, curtain);
            curtain.reveal();
            assertEquals(LaunchCurtain.Phase.OPENING, curtain.phase());
            advance(1, curtain);

            curtain.pause();
            assertFalse(curtain.animating());
            now.addAndGet(60_000);
            curtain.reveal();
            assertTrue(curtain.animating(), "the restored window keeps opening");
            advance(1, curtain);
            assertEquals(LaunchCurtain.Phase.OPENING, curtain.phase(), "time spent minimised is not counted");
            advance(5_000, curtain);
            assertEquals(LaunchCurtain.Phase.SHIMMER, curtain.phase());
            curtain.hideCurtain();
        });
    }

    @Test
    void removingTheCurtainStopsItsTimer() throws Exception {
        onEdt(() -> {
            LaunchCurtain curtain = curtain();
            curtain.closeForLaunch("MegaMek");
            advance(5_000, curtain);
            assertEquals(LaunchCurtain.Phase.DEPLOYING, curtain.phase());
            assertTrue(curtain.animating());

            curtain.removeNotify();
            assertFalse(curtain.animating(), "a disposed frame leaves nothing ticking");
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

    @Test
    void withTheIntroOffTheLauncherOpensStraightOnItsContent() throws Exception {
        onEdt(() -> {
            LaunchCurtain curtain = curtain();
            curtain.setIntro(false);
            curtain.greet();
            curtain.contentReady("Welcome, Commander");
            assertEquals(LaunchCurtain.Phase.HIDDEN, curtain.phase(), "no greeting");
            assertFalse(curtain.isVisible());

            curtain.closeForLaunch("MegaMek");
            assertEquals(LaunchCurtain.Phase.CLOSING, curtain.phase(), "launches still show the card");
            advance(5_000, curtain);
            curtain.reveal();
            assertEquals(LaunchCurtain.Phase.HIDDEN, curtain.phase(), "no opening or scan on return");
            assertFalse(curtain.animating());
        });
    }

    @Test
    void actionsWaitingForTheCurtainRunOnceTheIntroHasFinished() throws Exception {
        List<String> ran = new java.util.concurrent.CopyOnWriteArrayList<>();
        LaunchCurtain[] held = new LaunchCurtain[1];
        onEdt(() -> {
            LaunchCurtain curtain = curtain();
            held[0] = curtain;
            curtain.whenUncovered(() -> ran.add("hidden"));
            assertEquals(List.of("hidden"), ran, "nothing covers the content, so it runs at once");

            curtain.greet();
            curtain.contentReady("Welcome, Commander");
            curtain.whenUncovered(() -> ran.add("first"));
            curtain.whenUncovered(() -> ran.add("second"));
            advance(LaunchCurtain.GREETING_END, curtain);
            advance(5_000, curtain);
            assertEquals(LaunchCurtain.Phase.SHIMMER, curtain.phase());
        });
        onEdt(() -> {
            assertEquals(List.of("hidden"), ran, "the greeting, opening and sweep come first");
            advance(5_000, held[0]);
            assertEquals(LaunchCurtain.Phase.HIDDEN, held[0].phase());
        });
        onEdt(() -> assertEquals(List.of("hidden", "first", "second"), ran));

        onEdt(() -> {
            held[0].closeForLaunch("MegaMek");
            held[0].whenUncovered(() -> ran.add("disposed"));
            held[0].removeNotify();
        });
        onEdt(() -> assertEquals(3, ran.size(), "a disposed frame drops what was waiting"));
    }

    @Test
    void actionsHiddenButNotYetRunAreDroppedWhenTheFrameIsDisposed() throws Exception {
        List<String> ran = new java.util.concurrent.CopyOnWriteArrayList<>();
        onEdt(() -> {
            LaunchCurtain curtain = curtain();
            curtain.greet();
            curtain.whenUncovered(() -> ran.add("late"));
            curtain.hideCurtain();
            assertEquals(List.of(), ran, "the actions wait for the frame after the hide");
            curtain.removeNotify();
        });
        onEdt(() -> assertEquals(List.of(), ran, "disposal before the dispatch drops them"));
    }

    @Test
    void actionsWaitAgainWhenTheCurtainCoversBeforeTheyRun() throws Exception {
        List<String> ran = new java.util.concurrent.CopyOnWriteArrayList<>();
        LaunchCurtain[] held = new LaunchCurtain[1];
        onEdt(() -> {
            LaunchCurtain curtain = curtain();
            held[0] = curtain;
            curtain.greet();
            curtain.whenUncovered(() -> ran.add("first"));
            curtain.hideCurtain();
            curtain.greet();
            curtain.whenUncovered(() -> ran.add("second"));
        });
        onEdt(() -> {
            assertEquals(List.of(), ran, "nothing opens over the new cover");
            held[0].hideCurtain();
        });
        onEdt(() -> assertEquals(List.of("first", "second"), ran, "both run, in order"));
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
