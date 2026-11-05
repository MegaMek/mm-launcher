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
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.AWTEvent;
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
import java.awt.GraphicsConfiguration;
import java.awt.KeyboardFocusManager;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The launcher's scene transitions, painted on the frame's glass pane over the live content.
 * <p>
 * When the launcher opens, a status line and the greeting type themselves on the dark background
 * with a block cursor, above a rule that draws out under them. Then the window opens from a
 * horizontal centre line, like a screen powering on, a hex grid lighting up just behind the
 * opening edges and fading as they pass, and once it is open a scan line sweeps across it once,
 * trailing lit hexes, with brackets locking onto each target it passes. Launching an application
 * closes the window back to that line and leaves a "Launching" card that waits until the
 * application is on screen; when the launcher comes back it opens from the line again, without
 * the greeting.
 * <p>
 * Everything is cheap to draw: the hex grid is drawn once into an image per window size, the
 * content is printed once per transition and drawn from that image, only the strips that change
 * are repainted each frame, and the timer runs only while something moves. The greeting and
 * opening can be skipped with a
 * click or any key; the sweep lets clicks and keys through to the content. With reduce motion on,
 * nothing animates: the greeting and sweep are skipped, openings are instant and the card is
 * still. With the intro turned off in Settings, the greeting, opening and sweep are skipped but
 * launches still close onto the card.
 */
final class LaunchCurtain extends JComponent {
    enum Phase { HIDDEN, GREETING, OPENING, SHIMMER, CLOSING, DEPLOYING }

    /** The dark beat before the status line starts typing. */
    static final int TEXT_START = 350;
    /** How long the whole status line takes to type. */
    static final int STATUS_TYPING = 450;
    /** How long after the status line starts the greeting starts typing. */
    static final int GREETING_DELAY = 550;
    /** How long the whole greeting takes to type. */
    static final int GREETING_TYPING = 1000;
    /** When the typed greeting, having held, gives way to the opening. */
    static final int GREETING_END = 2700;
    /** The longest the greeting waits for the home screen before opening anyway. */
    static final int GREETING_MAX_WAIT = 4000;
    /** How long after the text starts the cursor shows. */
    private static final int CURSOR_DELAY = 500;
    static final int CURSOR_BLINK = 333;
    /** How long after the text starts the rule under it starts drawing out, and how long it takes. */
    private static final int RULE_DELAY = 400;
    private static final int RULE_DURATION = 1200;
    /** How long the scan line takes to sweep across the opened window. */
    static final int SHIMMER_DURATION = 1600;
    /** How long a target's brackets take to close in, hold, and fade once the scan passes it. */
    static final int LOCK_SNAP = 160;
    static final int LOCK_HOLD = 340;
    static final int LOCK_FADE = 300;
    static final int LOCK_DURATION = LOCK_SNAP + LOCK_HOLD + LOCK_FADE;
    /** One pass of the highlight along the "Launching" card's line. */
    static final int SCAN_PERIOD = 1600;
    /** How many steps the lit hex grid behind a moving edge fades over. */
    private static final int TRAIL_STEPS = 6;
    /** How many steps the sweeping band's light rises and falls over. */
    private static final int SHIMMER_STEPS = 16;
    private static final int FRAME = 1000 / 60;
    private static final int SLOW_FRAME = 1000 / 30;

    /** The lit hex grid, drawn once for a window size and theme. */
    private record Scene(int width, int height, int colours, BufferedImage mesh) {
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
    /** When {@link #pause()} stopped a running phase, or -1. */
    private long pausedAt = -1;
    private String greeting = "";
    private String title = "";
    private Scene scene;
    private boolean contentReady;
    /** Whether the greeting, opening and scan play; off, the curtain only shows launches. */
    private boolean intro = true;
    private int lastTop = -1;
    private int lastBottom = -1;
    private Rectangle lastShimmer;
    private Component focusBeforeCover;
    /** The content as it was when the transition started, at the screen's resolution. */
    private BufferedImage snapshot;
    private int snapshotWidth;
    private int snapshotHeight;
    private Point pointer;
    private Supplier<? extends Collection<? extends Component>> targetSource = List::of;
    /** Where the sweep's targets sit, and when (in sweep time) the scan locked onto each; -1 not yet. */
    private List<Rectangle> targets = List.of();
    private long[] lockedAt = new long[0];
    /**
     * Input in the window during the sweep: drop the snapshot so the content paints itself
     * again, hovers and presses included. A move counts only once the pointer really moves, as
     * a window shown under a still pointer reports one too.
     */
    private final AWTEventListener goLive = event -> {
        Window window = SwingUtilities.getWindowAncestor(this);
        if (phase != Phase.SHIMMER || window == null
                || !(event.getSource() instanceof Component source)
                || (source != window && SwingUtilities.getWindowAncestor(source) != window)) {
            return;
        }
        int id = event.getID();
        if (id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED) {
            Point at = ((MouseEvent) event).getLocationOnScreen();
            if (pointer == null) {
                pointer = at;
                return;
            }
            if (pointer.equals(at)) {
                return;
            }
        } else if (id != MouseEvent.MOUSE_PRESSED && id != MouseEvent.MOUSE_WHEEL
                && id != KeyEvent.KEY_PRESSED) {
            return;
        }
        dropSnapshot();
    };

    /** Opaque while it draws everything itself, so Swing does not repaint the page under it. */
    @Override
    public boolean isOpaque() {
        if (phase == null) {
            return false;
        }
        return switch (phase) {
            case HIDDEN -> false;
            case GREETING, DEPLOYING -> true;
            case OPENING, CLOSING, SHIMMER -> snapshot != null;
        };
    }

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

    /** Turns the greeting, opening and scan on or off; launches still show their card. */
    void setIntro(boolean play) {
        intro = play;
    }

    /** The components the scan locks onto as it passes them, read each time a sweep starts. */
    void setTargets(Supplier<? extends Collection<? extends Component>> source) {
        targetSource = source == null ? List::of : source;
    }

    /** How many targets the current sweep has locked onto so far. */
    int lockedTargets() {
        return (int) Arrays.stream(lockedAt).filter(at -> at >= 0).count();
    }

    /** The status line typed above the greeting, for launcher {@code version}. */
    static String statusLine(String version) {
        return "MegaMek Launcher " + (version == null || version.isBlank() ? "dev" : version)
                + "  //  Link established";
    }

    /** While the band sweeps, clicks, hovers and the cursor go to the content underneath. */
    @Override
    public boolean contains(int x, int y) {
        return phase != Phase.SHIMMER && super.contains(x, y);
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
        if (TokenMotion.isReduced() || !intro) {
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
        phaseStart = now - Math.min(now - phaseStart, TEXT_START);
        repaintGreeting();
    }

    /** Opens on the content from the centre line, if the curtain covers it. */
    void reveal() {
        if (!intro) {
            if (phase != Phase.HIDDEN && phase != Phase.SHIMMER) {
                hideCurtain();
            }
            return;
        }
        if (phase == Phase.OPENING && pausedAt >= 0) {
            // Carry on from where minimising stopped the opening.
            phaseStart += clock.getAsLong() - pausedAt;
            pausedAt = -1;
            timer.start();
            repaint();
            return;
        }
        if (phase == Phase.HIDDEN || phase == Phase.OPENING || phase == Phase.SHIMMER) {
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
        if (phase == Phase.SHIMMER) {
            hideCurtain();
            return;
        }
        if (timer.isRunning()) {
            pausedAt = clock.getAsLong();
        }
        timer.stop();
    }

    /** Whether the curtain's frame timer is running; for tests. */
    boolean animating() {
        return timer.isRunning();
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
        release();
        if (isVisible()) {
            setVisible(false);
        }
        setFocusable(true);
        restoreFocus();
    }

    /** Stops the timer and drops the cached images and global listener, so nothing outlives the frame. */
    private void release() {
        phase = Phase.HIDDEN;
        pausedAt = -1;
        timer.stop();
        Toolkit.getDefaultToolkit().removeAWTEventListener(goLive);
        scene = null;
        snapshot = null;
        targets = List.of();
        lockedAt = new long[0];
        lastTop = -1;
        lastBottom = -1;
        lastShimmer = null;
    }

    @Override
    public void removeNotify() {
        // Disposing the frame mid-launch must not leave the timer ticking on it.
        release();
        focusBeforeCover = null;
        super.removeNotify();
    }

    private void restoreFocus() {
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
        Toolkit.getDefaultToolkit().removeAWTEventListener(goLive);
        phase = next;
        phaseStart = clock.getAsLong();
        pausedAt = -1;
        lastTop = -1;
        lastBottom = -1;
        lastShimmer = null;
        if (next == Phase.OPENING || next == Phase.CLOSING) {
            captureContent();
        }
        setFocusable(true);
        cover();
        timer.setDelay(FRAME);
        timer.start();
        repaint();
    }


    private void cover() {
        // A sweep already handed focus back, so take it again from whoever has it now.
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner != null && owner != this) {
            focusBeforeCover = owner;
        }
        if (!isVisible()) {
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
                if (contentReady ? elapsed >= GREETING_END : elapsed >= GREETING_MAX_WAIT) {
                    start(Phase.OPENING);
                    return;
                }
                repaintGreeting();
            }
            case OPENING -> {
                if (elapsed >= textFadeDuration() + openDuration()) {
                    sweep();
                    return;
                }
                if (elapsed < textFadeDuration()) {
                    repaintGreeting();
                } else {
                    repaintAperture(apertureHalf(progress(elapsed - textFadeDuration(),
                            openDuration()), getHeight()));
                }
            }
            case SHIMMER -> {
                Band band = shimmerBand(progress(elapsed, SHIMMER_DURATION));
                boolean locking = lockOn(band, elapsed);
                if (elapsed >= SHIMMER_DURATION && !locking) {
                    hideCurtain();
                    return;
                }
                if (elapsed < SHIMMER_DURATION || lastShimmer != null) {
                    repaintShimmer(band);
                    if (elapsed >= SHIMMER_DURATION) {
                        lastShimmer = null;
                    }
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
     * How many of {@code length} characters typed evenly over {@code duration} ms show
     * {@code since} ms after typing starts.
     */
    static int typed(long since, long duration, int length) {
        if (since <= 0) {
            return 0;
        }
        return (int) (Math.min(1, (double) since / duration) * length);
    }

    /** Eases out: fast at first, settling at 1. */
    static double easeOut(double x) {
        double clamped = Math.max(0, Math.min(1, x));
        return 1 - Math.pow(1 - clamped, 3);
    }

    /** The window has opened: a band of lit hexes sweeps across it while the content takes input. */
    private void sweep() {
        lastTop = -1;
        lastBottom = -1;
        if (TokenMotion.isReduced() || getWidth() <= 0) {
            hideCurtain();
            return;
        }
        phase = Phase.SHIMMER;
        phaseStart = clock.getAsLong();
        lastShimmer = null;
        pointer = null;
        findTargets();
        // Anything that loaded while the window opened is in the sweep's picture.
        captureContent();
        Toolkit.getDefaultToolkit().addAWTEventListener(goLive, AWTEvent.MOUSE_EVENT_MASK
                | AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK
                | AWTEvent.KEY_EVENT_MASK);
        // Repaint what the opening left so only the band shows.
        repaint();
        if (focusBeforeCover != null) {
            restoreFocus();
        } else {
            // Nothing had focus before: an unfocusable owner hands focus on to the content.
            setFocusable(false);
        }
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

    /**
     * The sweeping band at {@code progress} (0 before the left edge, 1 past the right): its left
     * edge along the bottom of the window, its width, and how far its top leans ahead.
     */
    record Band(int x, int width, int lean) {
        Rectangle bounds(int height) {
            return new Rectangle(x, 0, width + lean, height);
        }
    }

    Band shimmerBand(double progress) {
        int width = Math.max(scale.scaleForGUI(180), getWidth() / 5);
        int lean = getHeight() / 3;
        double p = Math.max(0, Math.min(1, progress));
        double travel = p * p * (3 - 2 * p);
        int x = (int) Math.round(-(width + lean) + (getWidth() + width + lean) * travel);
        return new Band(x, width, lean);
    }

    private void repaintShimmer(Band band) {
        Rectangle bounds = band.bounds(getHeight());
        Rectangle area = lastShimmer == null ? bounds : bounds.union(lastShimmer);
        int edge = lineThickness() + 1;
        repaint(area.x - edge, 0, area.width + 2 * edge, getHeight());
        lastShimmer = bounds;
    }

    /** Where the scan line crosses row {@code y}: it leans forward, ahead at the top. */
    private int scanEdge(Band band, int y) {
        int height = Math.max(1, getHeight());
        return band.x() + band.width() + (int) Math.round(band.lean() * (height - y)
                / (double) height);
    }

    /** Reads where the targets sit now, in the curtain's coordinates; none locked yet. */
    private void findTargets() {
        List<Rectangle> found = new ArrayList<>();
        Collection<? extends Component> components = targetSource.get();
        Rectangle window = new Rectangle(0, 0, getWidth(), getHeight());
        boolean live = isShowing();
        if (components != null) {
            for (Component component : components) {
                if (component == null || component.getParent() == null
                        || !(live ? component.isShowing() : component.isVisible())) {
                    continue;
                }
                Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(),
                        component.getBounds(), this);
                if (!bounds.isEmpty() && window.intersects(bounds)) {
                    found.add(bounds);
                }
            }
        }
        targets = List.copyOf(found);
        lockedAt = new long[targets.size()];
        Arrays.fill(lockedAt, -1);
    }

    /**
     * Locks onto each target the scan line has reached the middle of, and repaints the brackets
     * still moving; whether any are.
     */
    private boolean lockOn(Band band, long elapsed) {
        boolean active = false;
        for (int i = 0; i < targets.size(); i++) {
            Rectangle target = targets.get(i);
            if (lockedAt[i] < 0 && (elapsed >= SHIMMER_DURATION
                    || scanEdge(band, (int) target.getCenterY()) >= target.getCenterX())) {
                lockedAt[i] = elapsed;
            }
            if (lockedAt[i] >= 0 && elapsed - lockedAt[i] <= LOCK_DURATION) {
                Rectangle area = bracketBounds(target, bracketTravel());
                int edge = lineThickness() + 1;
                repaint(area.x - edge, area.y - edge, area.width + 2 * edge,
                        area.height + 2 * edge);
                active = true;
            } else if (lockedAt[i] < 0) {
                active = true;
            }
        }
        return active;
    }

    /** How far out the brackets start before closing in on a target. */
    private int bracketTravel() {
        return scale.scaleForGUI(14);
    }

    /** The rectangle a target's brackets frame, {@code out} beyond their resting place. */
    private Rectangle bracketBounds(Rectangle target, int out) {
        int pad = scale.scaleForGUI(5) + out;
        return new Rectangle(target.x - pad, target.y - pad, target.width + 2 * pad,
                target.height + 2 * pad);
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

    /** The greeting is the screen's headline: the display face, growing with tall windows. */
    private Font greetingFont() {
        return displayFont().deriveFont(Math.max(scale.scaleForGUI(34f), getHeight() * 0.055f));
    }

    private Font statusFont() {
        DesignTokens.TypeStyle overline = DesignTokens.shipped().typeStyle("overline");
        Font font = scale.font(baseFont(), "overline");
        return TokenFonts.condensed(font.deriveFont(font.getSize2D() * 1.4f), overline, true);
    }

    /**
     * The status line sits above the greeting on its left edge, the greeting centred on the
     * window's middle, and the rule under both.
     */
    private Layout greetingLayout() {
        Font statusFont = statusFont();
        Font greetingFont = greetingFont();
        FontMetrics statusMetrics = getFontMetrics(statusFont);
        FontMetrics greetingMetrics = getFontMetrics(greetingFont);
        String statusText = status.toUpperCase(Locale.ROOT);
        String greetingText = greeting.toUpperCase(Locale.ROOT);
        int width = greetingMetrics.stringWidth(greetingText);
        int x = (getWidth() - width) / 2;
        int ascent = greetingMetrics.getAscent();
        int greetingBaseline = getHeight() / 2 + ascent / 2;
        int statusBaseline = greetingBaseline - ascent - scale.scaleForGUI(10);
        int ruleY = greetingBaseline + scale.scaleForGUI(18);
        int pad = scale.space("lg");
        return new Layout(statusFont, greetingFont, statusText, greetingText, x, width,
                statusBaseline, greetingBaseline, ruleY,
                statusBaseline - statusMetrics.getAscent() - pad, ruleY + ruleThickness() + pad);
    }

    private int ruleThickness() {
        return Math.max(1, scale.scaleForGUI(1));
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
                case SHIMMER -> {
                    paintContent(g, 0, 0, width, height);
                    if (elapsed < SHIMMER_DURATION) {
                        paintShimmer(g, shimmerBand(progress(elapsed, SHIMMER_DURATION)), height);
                    }
                    paintLocks(g, elapsed);
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
        long since = elapsed - TEXT_START;
        int statusCount = typed(since, STATUS_TYPING, layout.status().length());
        int greetingCount = typed(since - GREETING_DELAY, GREETING_TYPING,
                layout.greeting().length());
        int x = layout.x();

        g.setFont(layout.statusFont());
        g.setColor(withAlpha(LauncherTheme.ACCENT, alpha));
        g.drawString(layout.status().substring(0, statusCount), x, layout.statusBaseline());
        g.setFont(layout.greetingFont());
        FontMetrics metrics = g.getFontMetrics();
        String typedGreeting = layout.greeting().substring(0, greetingCount);
        g.setColor(withAlpha(LauncherTheme.TEXT, alpha));
        g.drawString(typedGreeting, x, layout.greetingBaseline());

        int ruleWidth = (int) Math.round(layout.width()
                * easeOut((double) (since - RULE_DELAY) / RULE_DURATION));
        if (ruleWidth > 0) {
            g.setColor(withAlpha(LauncherTheme.MUTED, 0.6f * alpha));
            g.fillRect(x, layout.ruleY(), ruleWidth, ruleThickness());
        }

        boolean typing = greetingCount < layout.greeting().length();
        boolean blinkOn = (elapsed / CURSOR_BLINK) % 2 == 0;
        if (alpha >= 1f && since > CURSOR_DELAY && (typing || blinkOn)) {
            int cursorX = x + metrics.stringWidth(typedGreeting) + scale.scaleForGUI(6);
            int cursorWidth = Math.max(scale.scaleForGUI(10), metrics.charWidth('M') / 2);
            int cursorHeight = metrics.getAscent() - scale.scaleForGUI(6);
            g.setColor(LauncherTheme.ACCENT);
            g.fillRect(cursorX, layout.greetingBaseline() - cursorHeight, cursorWidth,
                    cursorHeight);
        }
    }

    /**
     * Draws the snapshot of the content over the given area, limited to the clip; nothing when
     * there is none, as the curtain is then see-through and the content paints itself.
     */
    private void paintContent(Graphics2D g, int x, int y, int width, int height) {
        BufferedImage image = snapshot;
        if (image == null || width <= 0 || height <= 0) {
            return;
        }
        if (snapshotWidth != getWidth() || snapshotHeight != getHeight()) {
            // Resized mid-transition: let the live content show from here on.
            dropSnapshot();
            return;
        }
        double sx = (double) image.getWidth() / snapshotWidth;
        double sy = (double) image.getHeight() / snapshotHeight;
        g.drawImage(image, x, y, x + width, y + height,
                (int) Math.round(x * sx), (int) Math.round(y * sy),
                (int) Math.round((x + width) * sx), (int) Math.round((y + height) * sy), null);
    }

    /**
     * Prints the content once, at the screen's resolution, so the transition draws from the
     * image and Swing need not repaint the page under every frame.
     */
    private void captureContent() {
        snapshot = null;
        JRootPane root = getRootPane();
        int width = getWidth();
        int height = getHeight();
        if (root == null || width <= 0 || height <= 0) {
            return;
        }
        GraphicsConfiguration config = getGraphicsConfiguration();
        AffineTransform device = config != null ? config.getDefaultTransform()
                : new AffineTransform();
        double sx = device.getScaleX();
        double sy = device.getScaleY();
        BufferedImage image = new BufferedImage((int) Math.ceil(width * sx),
                (int) Math.ceil(height * sy), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.scale(sx, sy);
            g.setColor(LauncherTheme.BACKGROUND);
            g.fillRect(0, 0, width, height);
            JLayeredPane layered = root.getLayeredPane();
            Point at = SwingUtilities.convertPoint(layered, 0, 0, this);
            g.translate(at.x, at.y);
            // Printing paints without Swing's shared back buffer, which a frame may be using.
            layered.print(g);
        } finally {
            g.dispose();
        }
        snapshot = image;
        snapshotWidth = width;
        snapshotHeight = height;
    }

    private void dropSnapshot() {
        if (snapshot != null) {
            snapshot = null;
            repaint();
        }
    }

    /**
     * The scan: a bright leaning line at the band's leading edge with a glow just behind it, and
     * the hex grid it has lit fading out behind it in a few steps.
     */
    private void paintShimmer(Graphics2D g, Band band, int height) {
        Scene current = scene();
        if (current == null) {
            return;
        }
        Shape clip = g.getClip();
        Composite composite = g.getComposite();
        try {
            Polygon whole = new Polygon(
                    new int[] {band.x() + band.lean(), band.x() + band.width() + band.lean(),
                            band.x() + band.width(), band.x()},
                    new int[] {0, 0, height, height}, 4);
            // The wash's gradient runs across the band, square to its leaning edges.
            double square = (double) height * height + (double) band.lean() * band.lean();
            float across = (float) (band.width() * (double) height / square);
            Color accent = LauncherTheme.ACCENT;
            g.setPaint(new LinearGradientPaint(band.x(), height,
                    band.x() + across * height, height + across * band.lean(),
                    new float[] {0f, 0.82f, 1f},
                    new Color[] {withAlpha(accent, 0f), withAlpha(accent, 0.06f),
                            withAlpha(accent, 0.24f)}));
            g.fill(whole);
            int step = Math.max(1, band.width() / SHIMMER_STEPS);
            for (int i = 0; i < SHIMMER_STEPS; i++) {
                double middle = (i + 0.5) / SHIMMER_STEPS;
                float light = (float) (middle * middle);
                int left = band.x() + i * step;
                Polygon strip = new Polygon(
                        new int[] {left + band.lean(), left + step + band.lean(), left + step, left},
                        new int[] {0, 0, height, height}, 4);
                g.setClip(clip);
                g.clip(strip);
                g.setComposite(AlphaComposite.SrcOver.derive(0.7f * light));
                Rectangle area = g.getClipBounds();
                if (area != null && !area.isEmpty()) {
                    int from = Math.max(0, area.x);
                    int to = Math.min(current.width(), area.x + area.width);
                    if (to > from) {
                        g.drawImage(current.mesh(), from, 0, to, current.height(), from, 0, to,
                                current.height(), null);
                    }
                }
            }
            g.setClip(clip);
            g.setComposite(composite);
            int edge = band.x() + band.width();
            int thickness = lineThickness();
            g.setColor(withAlpha(accent, 0.9f));
            g.fillPolygon(new int[] {edge + band.lean() - thickness, edge + band.lean(), edge,
                    edge - thickness}, new int[] {0, 0, height, height}, 4);
        } finally {
            g.setComposite(composite);
            g.setClip(clip);
        }
    }

    /**
     * Corner brackets on each target the scan has locked onto: they close in from a little
     * outside, hold, and fade.
     */
    private void paintLocks(Graphics2D g, long elapsed) {
        int thickness = lineThickness();
        for (int i = 0; i < targets.size(); i++) {
            long since = lockedAt[i] < 0 ? -1 : elapsed - lockedAt[i];
            if (since < 0 || since > LOCK_DURATION) {
                continue;
            }
            double snap = easeOut((double) since / LOCK_SNAP);
            float alpha = since < LOCK_SNAP ? (float) snap
                    : since < LOCK_SNAP + LOCK_HOLD ? 1f
                    : 1f - (float) (since - LOCK_SNAP - LOCK_HOLD) / LOCK_FADE;
            Rectangle frame = bracketBounds(targets.get(i),
                    (int) Math.round((1 - snap) * bracketTravel()));
            int arm = Math.max(thickness * 2, Math.min(scale.scaleForGUI(16),
                    Math.min(frame.width, frame.height) / 3));
            int left = frame.x;
            int top = frame.y;
            int right = frame.x + frame.width - thickness;
            int bottom = frame.y + frame.height - thickness;
            g.setColor(withAlpha(LauncherTheme.ACCENT, Math.max(0f, Math.min(1f, alpha))));
            g.fillRect(left, top, arm, thickness);
            g.fillRect(left, top, thickness, arm);
            g.fillRect(right - arm + thickness, top, arm, thickness);
            g.fillRect(right, top, thickness, arm);
            g.fillRect(left, bottom, arm, thickness);
            g.fillRect(left, bottom - arm + thickness, thickness, arm);
            g.fillRect(right - arm + thickness, bottom, arm, thickness);
            g.fillRect(right, bottom - arm + thickness, thickness, arm);
        }
    }

    /** Covers all but the band of {@code half} above and below the centre, with lit edges. */
    private void paintAperture(Graphics2D g, int width, int height, int half, float glow) {
        int top = Math.max(0, height / 2 - half);
        int bottom = Math.min(height, height / 2 + half);
        paintBackdrop(g, 0, 0, width, top);
        paintBackdrop(g, 0, bottom, width, height - bottom);
        paintContent(g, 0, top, width, bottom - top);
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

    /** Covers the given area with the background colour, limited to the clip. */
    private void paintBackdrop(Graphics2D g, int x, int y, int width, int height) {
        if (width > 0 && height > 0) {
            g.setColor(LauncherTheme.BACKGROUND);
            g.fillRect(x, y, width, height);
        }
    }

    /** The lit hex grid for the current size and colours, drawn once and kept while the curtain shows. */
    private Scene scene() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        int colours = LauncherTheme.ACCENT.getRGB();
        if (scene == null || scene.width() != width || scene.height() != height
                || scene.colours() != colours) {
            scene = buildScene(width, height, colours);
        }
        return scene;
    }

    private Scene buildScene(int width, int height, int colours) {
        BufferedImage mesh = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = mesh.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(Math.max(1f, scale.size("border") * 1.5f)));
            g.setColor(LauncherTheme.ACCENT);
            g.draw(hexGrid(width, height, scale.scaleForGUI(22)));
        } finally {
            g.dispose();
        }
        return new Scene(width, height, colours, mesh);
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
