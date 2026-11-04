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

import org.megamek.launcher.design.DesignTokens;
import org.megamek.launcher.design.swing.TokenFonts;
import org.megamek.launcher.design.swing.TokenMotion;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * The launcher's scene transitions, painted on the frame's glass pane over the live content.
 * <p>
 * When the launcher opens it types a greeting on the background colour, then the window opens
 * from a horizontal centre line, like a screen powering on. Launching an application closes the
 * window back to that line and leaves a "Launching" card that waits until the application is on
 * screen; when the launcher comes back it opens from the line again, without the greeting.
 * <p>
 * Everything is cheap to draw: the content underneath is never captured, only the strips that
 * change are repainted each frame, and the timer runs only while something moves. The greeting
 * and opening can be skipped with a click or any key. With reduce motion on, nothing animates:
 * the greeting is skipped, openings are instant and the card is still.
 */
final class LaunchCurtain extends JComponent {
    enum Phase { HIDDEN, GREETING, OPENING, CLOSING, DEPLOYING }

    /** The dark beat before the greeting starts typing. */
    static final int GREETING_PAUSE = 250;
    /** How long each greeting character takes to type. */
    static final int TYPE_INTERVAL = 40;
    /** How long the typed greeting holds before the window opens. */
    static final int GREETING_HOLD = 550;
    /** The longest the greeting waits for the home screen before opening anyway. */
    static final int GREETING_MAX_WAIT = 4000;
    static final int CURSOR_BLINK = 530;
    /** One pass of the highlight along the "Launching" card's line. */
    static final int SCAN_PERIOD = 1600;
    private static final int FRAME = 1000 / 60;
    private static final int SLOW_FRAME = 1000 / 30;

    private final GuiScale scale;
    private final LongSupplier clock;
    private final Timer timer;
    private Phase phase = Phase.HIDDEN;
    private long phaseStart;
    private String greeting = "";
    private String title = "";
    private boolean contentReady;
    private int lastTop = -1;
    private int lastBottom = -1;
    private Component focusBeforeCover;

    LaunchCurtain(GuiScale scale) {
        this(scale, () -> System.nanoTime() / 1_000_000L);
    }

    LaunchCurtain(GuiScale scale, LongSupplier clock) {
        this.scale = scale;
        this.clock = clock;
        setName("launchCurtain");
        setOpaque(false);
        setVisible(false);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        timer = new Timer(FRAME, event -> tick());
        timer.setCoalesce(true);
        // A visible glass pane with listeners keeps clicks, hovers and keys from the content.
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                skip();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                skip();
                event.consume();
            }
        });
    }

    Phase phase() {
        return phase;
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.PANEL;
                }
            };
        }
        return accessibleContext;
    }

    /** Whether the curtain covers the content: anything but {@link Phase#HIDDEN}. */
    boolean covering() {
        return phase != Phase.HIDDEN;
    }

    /**
     * Covers the window with the background colour and waits for {@link #contentReady(String)} to
     * type the greeting and open on the content.
     */
    void greet() {
        if (TokenMotion.isReduced()) {
            return;
        }
        greeting = "";
        contentReady = false;
        start(Phase.GREETING);
    }

    /**
     * The content underneath is ready: a waiting greeting types {@code text}, holds and opens on
     * it. A blank text opens at once.
     */
    void contentReady(String text) {
        if (phase != Phase.GREETING || contentReady) {
            return;
        }
        contentReady = true;
        if (text == null || text.isBlank()) {
            reveal();
            return;
        }
        greeting = text;
        getAccessibleContext().setAccessibleName(text);
        // Keep whatever is left of the dark beat, then type from the start.
        long now = clock.getAsLong();
        phaseStart = now - Math.min(now - phaseStart, GREETING_PAUSE);
        repaintGreeting();
    }

    /** Opens on the content from the centre line, if the curtain covers it. */
    void reveal() {
        if (phase == Phase.HIDDEN || phase == Phase.OPENING) {
            return;
        }
        if (TokenMotion.isReduced()) {
            hideCurtain();
            return;
        }
        greeting = "";
        start(Phase.OPENING);
    }

    /** Closes the window to the centre line and shows the "Launching {@code product}" card. */
    void closeForLaunch(String product) {
        title = "Launching " + product;
        getAccessibleContext().setAccessibleName(title);
        if (TokenMotion.isReduced()) {
            phase = Phase.DEPLOYING;
            // Start past the line and the fade so the card shows at once and stays still.
            phaseStart = clock.getAsLong() - lineDuration() - fadeDuration();
            timer.stop();
            cover();
            repaint();
            return;
        }
        start(Phase.CLOSING);
    }

    /** Stops animating while the window is minimised; {@link #reveal()} carries on from there. */
    void pause() {
        timer.stop();
    }

    /**
     * Milliseconds until the "Launching" card has closed in and shown for a moment, so a launch
     * that is ready at once still plays its transition; 0 when it has, or when no launch shows.
     */
    long untilLaunchShown() {
        if (TokenMotion.isReduced()) {
            return 0;
        }
        long elapsed = elapsed();
        return switch (phase) {
            case CLOSING -> Math.max(0, closeDuration() - elapsed) + launchCardDuration();
            case DEPLOYING -> Math.max(0, launchCardDuration() - elapsed);
            default -> 0;
        };
    }

    private static long launchCardDuration() {
        return lineDuration() + fadeDuration() + TokenMotion.slow();
    }

    /** Removes the curtain at once. */
    void hideCurtain() {
        phase = Phase.HIDDEN;
        timer.stop();
        lastTop = -1;
        lastBottom = -1;
        if (isVisible()) {
            setVisible(false);
        }
        Component previous = focusBeforeCover;
        focusBeforeCover = null;
        if (previous != null && previous.isShowing()) {
            previous.requestFocusInWindow();
        }
    }

    private void skip() {
        if (phase == Phase.GREETING || phase == Phase.OPENING) {
            hideCurtain();
        }
    }

    private void start(Phase next) {
        phase = next;
        phaseStart = clock.getAsLong();
        lastTop = -1;
        lastBottom = -1;
        cover();
        timer.setDelay(FRAME);
        timer.start();
        repaint();
    }

    private void cover() {
        if (!isVisible()) {
            Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            focusBeforeCover = owner != this ? owner : null;
            setVisible(true);
        }
        requestFocusInWindow();
    }

    private long elapsed() {
        return Math.max(0, clock.getAsLong() - phaseStart);
    }

    /** Advances the current phase; the timer calls it every frame. */
    void tick() {
        long elapsed = elapsed();
        switch (phase) {
            case GREETING -> {
                if (contentReady ? elapsed >= greetingDuration(greeting.length())
                        : elapsed >= GREETING_MAX_WAIT) {
                    start(Phase.OPENING);
                    return;
                }
                repaintGreeting();
            }
            case OPENING -> {
                if (elapsed >= textFadeDuration() + openDuration()) {
                    hideCurtain();
                    return;
                }
                if (elapsed < textFadeDuration()) {
                    repaintGreeting();
                } else {
                    repaintAperture(apertureHalf(progress(elapsed - textFadeDuration(),
                            openDuration()), getHeight()));
                }
            }
            case CLOSING -> {
                if (elapsed >= closeDuration()) {
                    phase = Phase.DEPLOYING;
                    phaseStart = clock.getAsLong();
                    repaint();
                    return;
                }
                repaintAperture(apertureHalf(1 - progress(elapsed, closeDuration()), getHeight()));
            }
            case DEPLOYING -> {
                if (elapsed < lineDuration() + fadeDuration()) {
                    repaint();
                } else {
                    timer.setDelay(SLOW_FRAME);
                    Rectangle scanner = scannerBounds();
                    repaint(scanner.x, scanner.y, scanner.width, scanner.height);
                }
            }
            case HIDDEN -> timer.stop();
        }
    }

    /** How long a greeting of {@code characters} takes to type and hold. */
    static long greetingDuration(int characters) {
        return GREETING_PAUSE + (long) characters * TYPE_INTERVAL + GREETING_HOLD;
    }

    /** How many of {@code length} greeting characters are typed {@code elapsed} ms in. */
    static int typed(long elapsed, int length) {
        if (elapsed < GREETING_PAUSE) {
            return 0;
        }
        return (int) Math.min(length, (elapsed - GREETING_PAUSE) / TYPE_INTERVAL + 1);
    }

    /** Half the height of the open band at {@code progress} (0 closed, 1 open), eased. */
    static int apertureHalf(double progress, int height) {
        float eased = TokenMotion.ease((float) Math.max(0, Math.min(1, progress)));
        return Math.round(eased * ((height + 1) / 2));
    }

    private static double progress(long elapsed, long duration) {
        return duration <= 0 ? 1 : Math.min(1, (double) elapsed / duration);
    }

    private long textFadeDuration() {
        return greeting.isEmpty() ? 0 : TokenMotion.medium();
    }

    private static long openDuration() {
        return TokenMotion.slow() + TokenMotion.medium();
    }

    private static long closeDuration() {
        return TokenMotion.slow();
    }

    private static long lineDuration() {
        return TokenMotion.medium();
    }

    private static long fadeDuration() {
        return TokenMotion.slow();
    }

    private int glowHeight() {
        return scale.space("xl");
    }

    private void repaintGreeting() {
        int line = getFontMetrics(displayFont()).getHeight();
        repaint(0, getHeight() / 2 - line, getWidth(), line * 2);
    }

    /** Repaints only the rows between the band's previous and new edges, with their glow. */
    private void repaintAperture(int half) {
        int width = getWidth();
        int height = getHeight();
        int top = height / 2 - half;
        int bottom = height / 2 + half;
        if (lastTop < 0) {
            repaint();
        } else {
            int glow = glowHeight() + 2;
            int from = Math.min(top, lastTop) - glow;
            repaint(0, from, width, Math.max(top, lastTop) + glow - from);
            from = Math.min(bottom, lastBottom) - glow;
            repaint(0, from, width, Math.max(bottom, lastBottom) + glow - from);
        }
        lastTop = top;
        lastBottom = bottom;
    }

    private Rectangle scannerBounds() {
        int width = Math.min(getWidth() / 2, scale.scaleForGUI(280));
        int thickness = lineThickness();
        int y = getHeight() / 2 + scale.space("lg");
        return new Rectangle((getWidth() - width) / 2, y - thickness, width, thickness * 3);
    }

    private int lineThickness() {
        return Math.max(1, scale.size("border") * 2);
    }

    private java.awt.Font displayFont() {
        java.awt.Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = getFont() != null ? getFont() : new java.awt.Font(java.awt.Font.SANS_SERIF,
                    java.awt.Font.PLAIN, 12);
        }
        DesignTokens.TypeStyle display = DesignTokens.shipped().typeStyle("display");
        return TokenFonts.condensed(scale.font(base, "display"), display, true);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (phase == Phase.HIDDEN) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            long elapsed = elapsed();
            switch (phase) {
                case GREETING -> {
                    g.setColor(LauncherTheme.BACKGROUND);
                    g.fillRect(0, 0, width, height);
                    paintGreeting(g, elapsed, 1f);
                }
                case OPENING -> {
                    long fade = textFadeDuration();
                    if (elapsed < fade) {
                        g.setColor(LauncherTheme.BACKGROUND);
                        g.fillRect(0, 0, width, height);
                        paintGreeting(g, Long.MAX_VALUE, 1f - (float) progress(elapsed, fade));
                    } else {
                        double open = progress(elapsed - fade, openDuration());
                        paintAperture(g, width, height, apertureHalf(open, height), 1f - (float) open);
                    }
                }
                case CLOSING -> {
                    double closed = progress(elapsed, closeDuration());
                    paintAperture(g, width, height, apertureHalf(1 - closed, height),
                            (float) closed);
                }
                case DEPLOYING -> paintDeploying(g, width, height, elapsed);
                default -> {
                }
            }
        } finally {
            g.dispose();
        }
    }

    private void paintGreeting(Graphics2D g, long elapsed, float alpha) {
        if (alpha <= 0 || greeting.isEmpty()) {
            return;
        }
        String text = greeting.toUpperCase(Locale.ROOT);
        g.setFont(displayFont());
        FontMetrics metrics = g.getFontMetrics();
        int count = typed(elapsed, text.length());
        // Centre on the whole line so typing never shifts what is already there.
        int x = (getWidth() - metrics.stringWidth(text)) / 2;
        int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
        g.setColor(withAlpha(LauncherTheme.TEXT, alpha));
        g.drawString(text.substring(0, count), x, baseline);
        boolean typing = count < text.length();
        boolean blinkOn = (elapsed / CURSOR_BLINK) % 2 == 0;
        if (alpha >= 1f && (typing || blinkOn)) {
            int cursorX = x + metrics.stringWidth(text.substring(0, count)) + scale.space("xs");
            g.setColor(LauncherTheme.ACCENT);
            g.fillRect(cursorX, baseline - metrics.getAscent(), lineThickness(), metrics.getAscent());
        }
    }

    /** Covers all but the band of {@code half} above and below the centre, with glowing edges. */
    private void paintAperture(Graphics2D g, int width, int height, int half, float glow) {
        int top = Math.max(0, height / 2 - half);
        int bottom = Math.min(height, height / 2 + half);
        g.setColor(LauncherTheme.BACKGROUND);
        g.fillRect(0, 0, width, top);
        g.fillRect(0, bottom, width, height - bottom);
        if (glow <= 0) {
            return;
        }
        Color accent = LauncherTheme.ACCENT;
        int glowHeight = Math.min(glowHeight(), Math.max(1, half));
        Color strong = withAlpha(accent, 0.45f * glow);
        Color clear = withAlpha(accent, 0f);
        g.setPaint(new GradientPaint(0, top, strong, 0, top + glowHeight, clear));
        g.fillRect(0, top, width, glowHeight);
        g.setPaint(new GradientPaint(0, bottom, strong, 0, bottom - glowHeight, clear));
        g.fillRect(0, bottom - glowHeight, width, glowHeight);
        int thickness = lineThickness();
        g.setColor(withAlpha(accent, glow));
        g.fillRect(0, top - thickness / 2, width, thickness);
        g.fillRect(0, bottom - thickness / 2, width, thickness);
    }

    private void paintDeploying(Graphics2D g, int width, int height, long elapsed) {
        g.setColor(LauncherTheme.BACKGROUND);
        g.fillRect(0, 0, width, height);
        long line = lineDuration();
        int thickness = lineThickness();
        if (elapsed < line) {
            // The closed window's line draws in to the centre before the card appears.
            float shrink = TokenMotion.ease((float) progress(elapsed, line));
            int lineWidth = Math.round(width * (1 - shrink));
            g.setColor(withAlpha(LauncherTheme.ACCENT, 1f - shrink / 2));
            g.fillRect((width - lineWidth) / 2, height / 2 - thickness / 2, lineWidth, thickness);
            return;
        }
        float alpha = (float) progress(elapsed - line, fadeDuration());
        String text = title.toUpperCase(Locale.ROOT);
        g.setFont(displayFont());
        FontMetrics metrics = g.getFontMetrics();
        int baseline = height / 2 - scale.space("lg");
        g.setColor(withAlpha(LauncherTheme.TEXT, alpha));
        g.drawString(text, (width - metrics.stringWidth(text)) / 2, baseline);

        Rectangle scanner = scannerBounds();
        int y = scanner.y + thickness;
        g.setColor(withAlpha(LauncherTheme.BORDER, alpha));
        g.fillRect(scanner.x, y, scanner.width, thickness);
        if (TokenMotion.isReduced()) {
            return;
        }
        int segment = Math.max(1, scanner.width / 3);
        double pass = ((elapsed - line) % SCAN_PERIOD) / (double) SCAN_PERIOD;
        int start = scanner.x - segment + (int) Math.round((scanner.width + segment) * pass);
        Color accent = withAlpha(LauncherTheme.ACCENT, alpha);
        Color clear = withAlpha(LauncherTheme.ACCENT, 0f);
        Graphics2D highlight = (Graphics2D) g.create();
        try {
            highlight.clipRect(scanner.x, scanner.y, scanner.width, scanner.height);
            int middle = start + segment / 2;
            highlight.setPaint(new GradientPaint(start, 0, clear, middle, 0, accent));
            highlight.fillRect(start, y, segment / 2, thickness);
            highlight.setPaint(new GradientPaint(middle, 0, accent, start + segment, 0, clear));
            highlight.fillRect(middle, y, segment - segment / 2, thickness);
        } finally {
            highlight.dispose();
        }
    }

    private static Color withAlpha(Color color, float alpha) {
        int a = Math.round(Math.max(0, Math.min(1, alpha)) * color.getAlpha());
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
    }
}
