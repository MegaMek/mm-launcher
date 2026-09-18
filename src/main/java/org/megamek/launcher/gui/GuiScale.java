package org.megamek.launcher.gui;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;

/**
 * Scales application metrics in Swing's logical coordinate space. Java's per-monitor transform
 * supplies device DPI scaling; multiplying that transform into these metrics would scale twice.
 */
public final class GuiScale {
    public static final GuiScale DEFAULT = new GuiScale(1f);
    private final float factor;

    public GuiScale(float factor) {
        if (!Float.isFinite(factor) || factor < 0.5f || factor > 4f) {
            throw new IllegalArgumentException("GUI scale must be between 0.5 and 4");
        }
        this.factor = factor;
    }

    public int scaleForGUI(int value) {
        return Math.round(value * factor);
    }

    public float scaleForGUI(float value) {
        return value * factor;
    }

    public Dimension scaleForGUI(int width, int height) {
        return new Dimension(scaleForGUI(width), scaleForGUI(height));
    }

    public Insets insets(int top, int left, int bottom, int right) {
        return new Insets(scaleForGUI(top), scaleForGUI(left),
                scaleForGUI(bottom), scaleForGUI(right));
    }

    public Font font(Font base, int style, float points) {
        return base.deriveFont(style, scaleForGUI(points));
    }

    public static Rectangle usableBounds(GraphicsConfiguration configuration) {
        Rectangle bounds = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        return new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                Math.max(1, bounds.width - insets.left - insets.right),
                Math.max(1, bounds.height - insets.top - insets.bottom));
    }

    public static Dimension fitWindow(Dimension requested, Rectangle workArea) {
        return new Dimension(Math.max(1, Math.min(requested.width, workArea.width * 94 / 100)),
                Math.max(1, Math.min(requested.height, workArea.height * 94 / 100)));
    }

    public static Rectangle fitImage(int imageWidth, int imageHeight, int width, int height) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }
        if (width <= 0 || height <= 0) {
            return new Rectangle();
        }
        double ratio = Math.min((double) width / imageWidth, (double) height / imageHeight);
        int fittedWidth = Math.min(width, (int) Math.round(imageWidth * ratio));
        int fittedHeight = Math.min(height, (int) Math.round(imageHeight * ratio));
        return new Rectangle((width - fittedWidth) / 2, (height - fittedHeight) / 2,
                fittedWidth, fittedHeight);
    }
}
