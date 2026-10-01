/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwingTestSupportTest {
    @Test
    void probesRunOnEdtAndNestedEdtCallsAreSafe() throws Exception {
        assertTrue(SwingTestSupport.await("EDT probe", () ->
                SwingTestSupport.onEdt(SwingUtilities::isEventDispatchThread)));
    }

    @Test
    void edtAssertionFailuresReachTheTestUnwrapped() {
        AssertionError expected = new AssertionError("fixture assertion");
        assertSame(expected, assertThrows(AssertionError.class,
                () -> SwingTestSupport.onEdt(() -> { throw expected; })));
    }

    @Test
    void timeoutNamesTheMissingStateRatherThanReturningNull() {
        AssertionError failure = assertThrows(AssertionError.class, () ->
                SwingTestSupport.await("fixture ready state", () -> null, Duration.ofMillis(50)));
        assertTrue(failure.getMessage().contains("fixture ready state"));
    }

    @Test
    void hiddenButtonIsNotClickedUntilShowing() throws Exception {
        readiness(false, false);
    }

    @Test
    void disabledButtonIsNotClickedUntilEnabled() throws Exception {
        readiness(true, false);
    }

    @Test
    void componentReplacementClicksTheNewButtonNotTheCachedOne() throws Exception {
        readiness(false, true);
    }

    private static void readiness(boolean disabled, boolean replace) throws Exception {
        CountDownLatch probed = new CountDownLatch(1);
        AtomicBoolean observe = new AtomicBoolean();
        AtomicInteger oldClicks = new AtomicInteger();
        AtomicInteger newClicks = new AtomicInteger();
        JButton original = SwingTestSupport.onEdt(() -> {
            JButton button = new JButton("Original") {
                @Override public String getName() {
                    if (observe.get()) probed.countDown();
                    return super.getName();
                }
            };
            button.setName("target");
            button.setVisible(disabled);
            button.setEnabled(!disabled);
            button.addActionListener(event -> oldClicks.incrementAndGet());
            return button;
        });
        JFrame frame = window(original);
        try (var executor = Executors.newSingleThreadExecutor()) {
            observe.set(true);
            var click = executor.submit(() -> SwingTestSupport.click(frame, "target"));
            try {
                assertTrue(probed.await(5, TimeUnit.SECONDS));
                assertFalse(click.isDone(), "finding a hidden/disabled button must not finish the click");
                assertEquals(0, oldClicks.get());
                SwingTestSupport.onEdt(() -> {
                    if (replace) {
                        JButton replacement = new JButton("Replacement");
                        replacement.setName("target");
                        replacement.addActionListener(event -> newClicks.incrementAndGet());
                        frame.getContentPane().remove(original);
                        frame.add(replacement);
                    } else {
                        original.setVisible(true);
                        original.setEnabled(true);
                    }
                    frame.validate();
                    return null;
                });
                click.get(5, TimeUnit.SECONDS);
                assertEquals(replace ? 0 : 1, oldClicks.get());
                assertEquals(replace ? 1 : 0, newClicks.get());
            } finally {
                SwingTestSupport.onEdt(() -> {
                    original.setVisible(true);
                    original.setEnabled(true);
                    return null;
                });
            }
        } finally {
            SwingTestSupport.dispose(frame);
        }
    }

    @Test
    void asynchronousModalClickAllowsTheTestToFindAndCloseTheDialog() throws Exception {
        JButton open = SwingTestSupport.onEdt(() -> {
            JButton button = new JButton("Open");
            button.setName("open");
            return button;
        });
        JFrame frame = window(open);
        try {
            SwingTestSupport.onEdt(() -> {
                open.addActionListener(event -> {
                    JDialog dialog = new JDialog(frame, "Fixture modal", true);
                    JButton close = new JButton("Close");
                    close.setName("close");
                    close.addActionListener(ignored -> dialog.dispose());
                    dialog.add(close);
                    dialog.pack();
                    dialog.setVisible(true);
                });
                return null;
            });
            SwingTestSupport.startClick(frame, "open");
            JDialog dialog = SwingTestSupport.dialog(frame, "Fixture modal");
            SwingTestSupport.startClick(dialog, "close");
            SwingTestSupport.awaitCondition("modal closure", () -> !dialog.isDisplayable());
        } finally {
            SwingTestSupport.dispose(frame);
        }
    }

    @Test
    void timedOutModalClickCanStillBeJoinedDuringScopedTeardown() throws Exception {
        JButton open = SwingTestSupport.onEdt(() -> {
            JButton button = new JButton("Open");
            button.setName("open");
            return button;
        });
        JFrame frame = window(open);
        try {
            SwingTestSupport.onEdt(() -> {
                open.addActionListener(event -> {
                    JDialog dialog = new JDialog(frame, "Timed out modal", true);
                    dialog.setSize(180, 100);
                    dialog.setVisible(true);
                });
                return null;
            });
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> SwingTestSupport.click(frame, "open"));
            assertTrue(failure.getMessage().contains("click open"));
            assertTrue(SwingTestSupport.onEdt(
                    SwingTestSupport.dialog(frame, "Timed out modal")::isShowing));
        } finally {
            SwingTestSupport.dispose(frame);
        }
    }

    @Test
    void asynchronousListenerFailureReachesTheWaitingTest() throws Exception {
        IllegalStateException expected = new IllegalStateException("fixture click failure");
        JButton fail = SwingTestSupport.onEdt(() -> {
            JButton button = new JButton("Fail");
            button.setName("fail");
            button.addActionListener(event -> { throw expected; });
            return button;
        });
        JFrame frame = window(fail);
        try {
            assertSame(expected, assertThrows(IllegalStateException.class, () -> {
                SwingTestSupport.startClick(frame, "fail");
                SwingTestSupport.await("unreachable fixture state", () -> null);
            }));
        } finally {
            SwingTestSupport.dispose(frame);
        }
    }

    @Test
    void dialogLookupAndCleanupNeverUseAnUnrelatedFrame() throws Exception {
        JFrame owner = window(new JButton("Owner"));
        JFrame unrelated = window(new JButton("Unrelated"));
        try {
            JDialog foreign = modeless(unrelated, "Shared title");
            JDialog nestedOwner = modeless(owner, "Nested owner");
            JDialog target = modeless(nestedOwner, "Shared title");
            assertSame(target, SwingTestSupport.dialog(owner, "Shared title"));
            SwingTestSupport.dispose(owner);
            assertTrue(SwingTestSupport.onEdt(unrelated::isShowing));
            assertTrue(SwingTestSupport.onEdt(foreign::isShowing));
            assertFalse(SwingTestSupport.onEdt(target::isDisplayable));
            assertFalse(SwingTestSupport.onEdt(nestedOwner::isDisplayable));
        } finally {
            SwingTestSupport.dispose(owner);
            SwingTestSupport.dispose(unrelated);
        }
    }

    private static JFrame window(JButton button) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing readiness needs a display");
        return SwingTestSupport.onEdt(() -> {
            JFrame frame = new JFrame("Swing fixture");
            frame.add(button);
            frame.setSize(300, 160);
            frame.setVisible(true);
            return frame;
        });
    }

    private static JDialog modeless(java.awt.Window owner, String title) throws Exception {
        return SwingTestSupport.onEdt(() -> {
            JDialog dialog = new JDialog(owner, title);
            dialog.setSize(180, 100);
            dialog.setVisible(true);
            return dialog;
        });
    }
}
