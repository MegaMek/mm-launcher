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

package org.megamek.launcher.design.host;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Icon;

/**
 * Registers the fonts bundled with the launcher (Roboto and a Material Symbols subset) and paints Material
 * Symbols icons. The subset holds only the icons the launcher uses; {@code scripts/build_launcher_fonts.py}
 * lists them and rebuilds it.
 */
public final class FontHandler {
    public static final String SYMBOL_FAMILY = "Material Symbols Rounded";

    private static final Logger LOGGER = Logger.getLogger(FontHandler.class.getName());
    private static final String FONT_ROOT = "/org/megamek/launcher/design/fonts/";
    private static final List<String> BUNDLED_FONTS = List.of(
          "Roboto-Regular.ttf",
          "Roboto-Medium.ttf",
          "Roboto-CondensedMedium.ttf",
          "MaterialSymbolsRounded-Subset.ttf");

    private static volatile boolean initialized;

    private FontHandler() {
    }

    /** Registers the bundled fonts with the graphics environment; later calls do nothing. */
    public static void initialize() {
        if (initialized) {
            return;
        }
        synchronized (FontHandler.class) {
            if (initialized) {
                return;
            }
            GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
            for (String name : BUNDLED_FONTS) {
                try (InputStream input = FontHandler.class.getResourceAsStream(FONT_ROOT + name)) {
                    if (input == null) {
                        LOGGER.warning(() -> "Bundled font is missing: " + name);
                        continue;
                    }
                    environment.registerFont(Font.createFont(Font.TRUETYPE_FONT, input));
                } catch (IOException | FontFormatException failure) {
                    LOGGER.log(Level.WARNING, failure, () -> "Could not load bundled font " + name);
                }
            }
            initialized = true;
        }
    }

    /** @return the Material Symbols font at 12 points; derive the size you need */
    public static Font symbolFont() {
        initialize();
        return new Font(SYMBOL_FAMILY, Font.PLAIN, 12);
    }

    /**
     * Creates an icon that paints one Material Symbols glyph live, so it stays crisp on HiDPI displays, sized to
     * the glyph's ink box so it carries no uneven side bearing.
     *
     * @param codePoint the Material Symbols code point, for example {@code 0xE5CD} for {@code close}
     * @param size      the glyph height in (already scaled) pixels
     * @param color     the colour to paint the glyph
     *
     * @return the icon, or {@code null} on invalid input or when the bundled subset lacks the glyph
     */
    public static Icon symbolIcon(int codePoint, int size, Color color) {
        if (!Character.isValidCodePoint(codePoint) || size <= 0 || color == null) {
            return null;
        }
        Font font = symbolFont().deriveFont((float) size);
        if (!font.canDisplay(codePoint)) {
            return null;
        }
        return new MaterialSymbolIcon(font, new String(Character.toChars(codePoint)), color);
    }

    private static final class MaterialSymbolIcon implements Icon {
        private final Font font;
        private final String glyph;
        private final Color color;
        private final int width;
        private final int height;
        private final float drawX;
        private final float drawY;

        private MaterialSymbolIcon(Font font, String glyph, Color color) {
            this.font = font;
            this.glyph = glyph;
            this.color = color;

            // The scratch image only provides a Graphics2D with real font metrics; nothing is drawn into it.
            BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scratch.createGraphics();
            FontMetrics metrics = graphics.getFontMetrics(font);
            // Every icon shares the line height so glyphs sit on one baseline; the width trims to the ink because
            // Material Symbols bake uneven side bearings into each glyph. Fractional offsets avoid a clipped edge.
            height = Math.max(1, metrics.getHeight());
            drawY = metrics.getAscent();
            FontRenderContext renderContext = new FontRenderContext(null, true, true);
            Rectangle2D inkBounds = font.createGlyphVector(renderContext, glyph).getVisualBounds();
            width = Math.max(1, (int) Math.ceil(inkBounds.getWidth()));
            drawX = (float) -inkBounds.getX();
            graphics.dispose();
        }

        @Override
        public void paintIcon(Component component, Graphics g, int x, int y) {
            Graphics2D graphics = (Graphics2D) g.create();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                      RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                      RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                graphics.setFont(font);
                graphics.setColor(color);
                graphics.drawString(glyph, x + drawX, y + drawY);
            } finally {
                graphics.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }
    }
}
