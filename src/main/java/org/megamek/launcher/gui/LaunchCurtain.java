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
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The launcher's scene transitions, painted on the frame's glass pane over the live content.
 * <p>
 * When the launcher opens it shows a console: a faint hex grid under scanlines, a vignette and
 * corner brackets. A status line and the greeting type themselves over it, above a rule that
 * draws out from the centre. Then the window opens from a horizontal centre line, like a screen
 * powering on, the hex grid lighting up just behind the opening edges and fading as they pass.
 * Launching an application closes the window back to that line and leaves a "Launching" card on
 * the console that waits until the application is on screen; when the launcher comes back it
 * opens from the line again, without the greeting.
 * <p>
 * Everything is cheap to draw: the console is drawn once into an image per window size, the
 * content underneath is never captured, only the strips that change are repainted each frame,
 * and the timer runs only while something moves. The greeting and opening can be skipped with a
 * click or any key. With reduce motion on, nothing animates: the greeting is skipped, openings
 * are instant and the card is still.
 */
final class LaunchCurtain extends JComponent {
    enum Phase { HIDDEN, GREETING, OPENING, CLOSING, DEPLOYING }

    /** The dark beat before the status line starts typing. */
    static final int GREETING_PAUSE = 250;
    /** How long each status line character takes to type. */
    static final int STATUS_INTERVAL = 14;
    /** How long after the status line starts the greeting starts typing. */
    static final int GREETING_LEAD = 350;
    /** How long each greeting character takes to type. */
    static final int TYPE_INTERVAL = 40;
    /** How long the typed greeting holds before the window opens. */
    static final int GREETING_HOLD = 650;
    /** The longest the greeting waits for the home screen before opening anyway. */
    static final int GREETING_MAX_WAIT = 4000;
    /** How long the rule under the greeting takes to draw out from the centre. */
    static final int RULE_DURATION = 900;
    static final int CURSOR_BLINK = 530;
    /** One pass of the highlight along the "Launching" card's line. */
    static final int SCAN_PERIOD = 1600;
    /** How many steps the lit hex grid behind a moving edge fades over. */
    private static final int TRAIL_STEPS = 6;
    private static final int FRAME = 1000 / 60;
    private static final int SLOW_FRAME = 1000 / 30;

    /** The console drawn once for a window size and theme: its backdrop and the lit hex grid. */
    private record Scene(int width, int height, int colours, BufferedImage backdrop,
                         BufferedImage mesh) {
    }

    /** Where the greeting's lines, rule and repaint band sit for the current size. */
    private record Layout(Font statusFont, Font greetingFont, String status, String greeting,
                          int x, int width, int statusBaseline, int greetingBaseline, int ruleY,
                          int top, int bottom) {
    }

    private final GuiScale scale;
    private final LongSupplier clock;
    private final Timer timer;
    private final String status;
    private Phase phase = Phase.HIDDEN;
    private long phaseStart;
    private String greeting = "";
    private String title = "";
    private Scene scene;
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
        status = statusLine(LaunchCurtain.class.getPackage().getImplementationVersion());
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

    /** The status line typed above the greeting, for launcher {@code version}. */
    static String statusLine(String version) {
        return "MegaMek Launcher " + (version == null || version.isBlank() ? "dev" : version)
                + "  //  Uplink established";
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
        scene = null;
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
                if (contentReady ? elapsed >= greetingDuration(status.length(), greeting.length())
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

    /**
     * How long a status line of {@code statusCharacters} and a greeting of {@code characters}
     * take to type and hold; the greeting starts {@link #GREETING_LEAD} after the status line.
     */
    static long greetingDuration(int statusCharacters, int characters) {
        long typing = Math.max((long) statusCharacters * STATUS_INTERVAL,
                GREETING_LEAD + (long) characters * TYPE_INTERVAL);
        return GREETING_PAUSE + typing + GREETING_HOLD;
    }

    /** How many of {@code length} characters typed every {@code interval} ms show {@code since} ms in. */
    static int typed(long since, int length, int interval) {
        if (since < 0) {
            return 0;
        }
        return (int) Math.min(length, since / interval + 1);
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

    /** How deep the lit hex grid trails behind a moving edge. */
    private int trailDepth() {
        return scale.scaleForGUI(110);
    }

    private void repaintGreeting() {
        Layout layout = greetingLayout();
        repaint(0, layout.top(), getWidth(), layout.bottom() - layout.top());
    }

    /** Repaints only the rows between the band's previous and new edges, with glow and trail. */
    private void repaintAperture(int half) {
        int width = getWidth();
        int height = getHeight();
        int top = height / 2 - half;
        int bottom = height / 2 + half;
        if (lastTop < 0) {
            repaint();
        } else {
            int glow = glowHeight() + 2;
            int inside = Math.max(glow, trailDepth() + 2);
            int from = Math.min(top, lastTop) - glow;
            repaint(0, from, width, Math.max(top, lastTop) + inside - from);
            from = Math.min(bottom, lastBottom) - inside;
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

    private Font baseFont() {
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = getFont() != null ? getFont() : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        return base;
    }

    private Font displayFont() {
        DesignTokens.TypeStyle display = DesignTokens.shipped().typeStyle("display");
        return TokenFonts.condensed(scale.font(baseFont(), "display"), display, true);
    }

    /** The greeting is the console's headline, half again the display size. */
    private Font greetingFont() {
        Font display = displayFont();
        return display.deriveFont(display.getSize2D() * 1.5f);
    }

    private Font statusFont() {
        DesignTokens.TypeStyle overline = DesignTokens.shipped().typeStyle("overline");
        Font font = scale.font(baseFont(), "overline");
        return TokenFonts.condensed(font.deriveFont(font.getSize2D() * 1.3f), overline, true);
    }

    /** The status line and greeting share a left edge, centred as a block over their rule. */
    private Layout greetingLayout() {
        Font statusFont = statusFont();
        Font greetingFont = greetingFont();
        FontMetrics statusMetrics = getFontMetrics(statusFont);
        FontMetrics greetingMetrics = getFontMetrics(greetingFont);
        String statusText = status.toUpperCase(Locale.ROOT);
        String greetingText = greeting.toUpperCase(Locale.ROOT);
        int width = Math.max(statusMetrics.stringWidth(statusText),
                greetingMetrics.stringWidth(greetingText));
        int x = (getWidth() - width) / 2;
        int greetingBaseline = (getHeight() - greetingMetrics.getHeight()) / 2
                + greetingMetrics.getAscent();
        int statusBaseline = greetingBaseline - greetingMetrics.getAscent() - scale.space("sm");
        int ruleY = greetingBaseline + greetingMetrics.getDescent() + scale.space("sm");
        int pad = scale.space("lg");
        return new Layout(statusFont, greetingFont, statusText, greetingText, x, width,
                statusBaseline, greetingBaseline, ruleY,
                statusBaseline - statusMetrics.getAscent() - pad, ruleY + lineThickness() + pad);
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
                    paintBackdrop(g, 0, 0, width, height);
                    paintGreeting(g, elapsed, 1f);
                }
                case OPENING -> {
                    long fade = textFadeDuration();
                    if (elapsed < fade) {
                        paintBackdrop(g, 0, 0, width, height);
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
        Layout layout = greetingLayout();
        long since = elapsed - GREETING_PAUSE;
        int statusCount = typed(since, layout.status().length(), STATUS_INTERVAL);
        int greetingCount = typed(since - GREETING_LEAD, layout.greeting().length(), TYPE_INTERVAL);
        int x = layout.x();

        g.setFont(layout.statusFont());
        FontMetrics statusMetrics = g.getFontMetrics();
        g.setColor(withAlpha(LauncherTheme.ACCENT, alpha));
        g.drawString(layout.status().substring(0, statusCount), x, layout.statusBaseline());
        g.setFont(layout.greetingFont());
        FontMetrics greetingMetrics = g.getFontMetrics();
        g.setColor(withAlpha(LauncherTheme.TEXT, alpha));
        g.drawString(layout.greeting().substring(0, greetingCount), x, layout.greetingBaseline());

        int thickness = lineThickness();
        float drawn = TokenMotion.ease((float) Math.max(0, progress(since, RULE_DURATION)));
        int ruleWidth = Math.round(layout.width() * drawn);
        if (ruleWidth > 0) {
            int ruleX = x + (layout.width() - ruleWidth) / 2;
            g.setColor(withAlpha(LauncherTheme.MUTED, 0.6f * alpha));
            g.fillRect(ruleX, layout.ruleY(), ruleWidth, Math.max(1, thickness / 2));
            int tick = scale.space("sm");
            g.setColor(withAlpha(LauncherTheme.ACCENT, alpha));
            g.fillRect(ruleX, layout.ruleY() - tick / 2, thickness, tick);
            g.fillRect(ruleX + ruleWidth - thickness, layout.ruleY() - tick / 2, thickness, tick);
        }

        boolean typing = statusCount < layout.status().length()
                || greetingCount < layout.greeting().length();
        boolean blinkOn = (elapsed / CURSOR_BLINK) % 2 == 0;
        if (alpha >= 1f && statusCount > 0 && (typing || blinkOn)) {
            boolean onGreeting = greetingCount > 0;
            FontMetrics metrics = onGreeting ? greetingMetrics : statusMetrics;
            String line = onGreeting ? layout.greeting().substring(0, greetingCount)
                    : layout.status().substring(0, statusCount);
            int baseline = onGreeting ? layout.greetingBaseline() : layout.statusBaseline();
            int cursorX = x + metrics.stringWidth(line) + scale.space("xs");
            int cursorWidth = Math.max(thickness, metrics.charWidth('M') / 2);
            int cursorHeight = Math.round(metrics.getAscent() * 0.72f);
            g.setColor(LauncherTheme.ACCENT);
            g.fillRect(cursorX, baseline - cursorHeight, cursorWidth, cursorHeight);
        }
    }

    /** Covers all but the band of {@code half} above and below the centre, with lit edges. */
    private void paintAperture(Graphics2D g, int width, int height, int half, float glow) {
        int top = Math.max(0, height / 2 - half);
        int bottom = Math.min(height, height / 2 + half);
        paintBackdrop(g, 0, 0, width, top);
        paintBackdrop(g, 0, bottom, width, height - bottom);
        if (glow <= 0) {
            return;
        }
        paintHexTrail(g, top, bottom, glow);
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

    /**
     * Lights the console's hex grid over the content just inside the band's edges, fading with
     * distance from them in a few steps, all scaled by {@code strength}.
     */
    private void paintHexTrail(Graphics2D g, int top, int bottom, float strength) {
        Scene current = scene();
        if (current == null || bottom <= top) {
            return;
        }
        int step = Math.max(1, trailDepth() / TRAIL_STEPS);
        Composite composite = g.getComposite();
        try {
            for (int i = 0; i < TRAIL_STEPS; i++) {
                float alpha = 0.6f * strength * (1f - (float) i / TRAIL_STEPS);
                g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, alpha))));
                int from = top + i * step;
                drawMesh(g, current, from, Math.min(bottom, from + step));
                int to = bottom - i * step;
                drawMesh(g, current, Math.max(top, to - step), to);
            }
        } finally {
            g.setComposite(composite);
        }
    }

    private static void drawMesh(Graphics2D g, Scene current, int from, int to) {
        if (to > from) {
            g.drawImage(current.mesh(), 0, from, current.width(), to, 0, from, current.width(), to,
                    null);
        }
    }

    /** Draws the console backdrop over the given area, limited to the clip. */
    private void paintBackdrop(Graphics2D g, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        Rectangle area = new Rectangle(x, y, width, height);
        Rectangle clip = g.getClipBounds();
        if (clip != null) {
            area = area.intersection(clip);
        }
        if (area.isEmpty()) {
            return;
        }
        Scene current = scene();
        if (current == null) {
            g.setColor(LauncherTheme.BACKGROUND);
            g.fill(area);
            return;
        }
        g.drawImage(current.backdrop(), area.x, area.y, area.x + area.width, area.y + area.height,
                area.x, area.y, area.x + area.width, area.y + area.height, null);
    }

    /** The console for the current size and colours, drawn once and kept while the curtain shows. */
    private Scene scene() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        int colours = Objects.hash(LauncherTheme.BACKGROUND.getRGB(), LauncherTheme.TEXT.getRGB(),
                LauncherTheme.ACCENT.getRGB());
        if (scene == null || scene.width() != width || scene.height() != height
                || scene.colours() != colours) {
            scene = buildScene(width, height, colours);
        }
        return scene;
    }

    private Scene buildScene(int width, int height, int colours) {
        Path2D grid = hexGrid(width, height, scale.scaleForGUI(22));
        Color background = LauncherTheme.BACKGROUND;
        boolean dark = (background.getRed() * 299 + background.getGreen() * 587
                + background.getBlue() * 114) / 1000 < 128;

        BufferedImage backdrop = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = backdrop.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(background);
            g.fillRect(0, 0, width, height);
            g.setStroke(new BasicStroke(1f));
            g.setColor(withAlpha(LauncherTheme.TEXT, dark ? 0.05f : 0.07f));
            g.draw(grid);
            g.setPaint(new RadialGradientPaint(new Point2D.Float(width / 2f, height / 2f),
                    Math.max(width, height) * 0.75f, new float[] {0.45f, 1f},
                    new Color[] {new Color(0, 0, 0, 0), new Color(0, 0, 0, dark ? 110 : 28)}));
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(0, 0, 0, dark ? 40 : 10));
            int pitch = Math.max(2, scale.scaleForGUI(3));
            for (int y = 0; y < height; y += pitch) {
                g.fillRect(0, y, width, 1);
            }
            paintCornerBrackets(g, width, height);
        } finally {
            g.dispose();
        }

        BufferedImage mesh = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        g = mesh.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(Math.max(1f, scale.size("border") * 1.5f)));
            g.setColor(LauncherTheme.ACCENT);
            g.draw(grid);
        } finally {
            g.dispose();
        }
        return new Scene(width, height, colours, backdrop, mesh);
    }

    private void paintCornerBrackets(Graphics2D g, int width, int height) {
        int inset = scale.space("xl");
        int length = scale.scaleForGUI(28);
        int thickness = lineThickness();
        int right = width - inset;
        int bottom = height - inset;
        g.setColor(withAlpha(LauncherTheme.ACCENT, 0.7f));
        g.fillRect(inset, inset, length, thickness);
        g.fillRect(inset, inset, thickness, length);
        g.fillRect(right - length, inset, length, thickness);
        g.fillRect(right - thickness, inset, thickness, length);
        g.fillRect(inset, bottom - thickness, length, thickness);
        g.fillRect(inset, bottom - length, thickness, length);
        g.fillRect(right - length, bottom - thickness, length, thickness);
        g.fillRect(right - thickness, bottom - length, thickness, length);
    }

    /** Flat-topped hexes of {@code radius} tiling {@code width} by {@code height}, as outlines. */
    static Path2D hexGrid(int width, int height, double radius) {
        Path2D grid = new Path2D.Float();
        double rowHeight = Math.sqrt(3) * radius;
        int column = 0;
        for (double cx = 0; cx < width + radius; cx += 1.5 * radius, column++) {
            double offset = column % 2 == 0 ? 0 : rowHeight / 2;
            for (double cy = offset - rowHeight; cy < height + rowHeight; cy += rowHeight) {
                for (int corner = 0; corner <= 6; corner++) {
                    double angle = Math.PI / 3 * corner;
                    double px = cx + radius * Math.cos(angle);
                    double py = cy + radius * Math.sin(angle);
                    if (corner == 0) {
                        grid.moveTo(px, py);
                    } else {
                        grid.lineTo(px, py);
                    }
                }
            }
        }
        return grid;
    }

    private void paintDeploying(Graphics2D g, int width, int height, long elapsed) {
        paintBackdrop(g, 0, 0, width, height);
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
