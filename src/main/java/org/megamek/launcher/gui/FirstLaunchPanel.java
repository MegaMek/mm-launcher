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

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** Empty-home presentation only; all user actions are supplied by the existing launcher frame. */
final class FirstLaunchPanel extends JPanel {
    static final String ART_RESOURCE = "/org/megamek/launcher/gui/first-launch-art.png";
    static final Color BACKGROUND = new Color(16, 26, 29);
    static final Color PANEL = new Color(22, 36, 40);
    static final Color TEXT = new Color(237, 243, 237);
    static final Color MUTED = new Color(166, 186, 181);
    static final Color GOLD = new Color(226, 196, 125);
    private final GuiScale scale;
    private final ArtworkPanel artwork;
    private final ContentPanel controls;
    private final JScrollPane scroller;
    private final JPanel actionRow;
    private final JPanel primaryAction;
    private final JPanel existingAction;
    private final Box.Filler verticalCenterBefore;
    private final Box.Filler verticalCenterAfter;
    private boolean compact;

    FirstLaunchPanel(BufferedImage image, GuiScale scale, FirstLaunchSplitButton download,
                     JButton useExisting, JLabel status) {
        this.scale = scale;
        setName("firstLaunchPanel");
        setLayout(null);
        setBackground(BACKGROUND);
        artwork = new ArtworkPanel(image, scale, true);
        artwork.setName("firstLaunchArtwork");
        artwork.getAccessibleContext().setAccessibleName("MegaMek launcher artwork");
        add(artwork);

        controls = new ContentPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBackground(PANEL);
        controls.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(26),
                scale.scaleForGUI(25), scale.scaleForGUI(20), scale.scaleForGUI(25)));
        Font base = UIManager.getFont("Label.font");
        verticalCenterBefore = flexibleSpace();
        controls.add(verticalCenterBefore);
        primaryAction = new JPanel(new BorderLayout());
        primaryAction.setOpaque(false);
        primaryAction.add(download, BorderLayout.CENTER);

        existingAction = new JPanel(new BorderLayout(0, scale.scaleForGUI(5)));
        existingAction.setOpaque(false);
        existingAction.add(useExisting, BorderLayout.CENTER);
        JLabel existingCaption = label("MegaMek, MekHQ, or MegaMekLab",
                scale.font(base, Font.PLAIN, 12), MUTED);
        existingCaption.setName("existingCopyCaption");
        existingCaption.setHorizontalAlignment(SwingConstants.CENTER);
        existingAction.add(existingCaption, BorderLayout.SOUTH);
        int actionWidth = scale.scaleForGUI(480);
        primaryAction.setPreferredSize(new Dimension(actionWidth,
                primaryAction.getPreferredSize().height));
        existingAction.setPreferredSize(new Dimension(actionWidth,
                existingAction.getPreferredSize().height));

        actionRow = new JPanel(new GridBagLayout());
        actionRow.setName("firstLaunchActions");
        actionRow.setOpaque(false);
        actionRow.setAlignmentX(CENTER_ALIGNMENT);
        layoutActions(false);
        actionRow.setMaximumSize(new Dimension(scale.scaleForGUI(1000),
                actionRow.getPreferredSize().height));
        controls.add(actionRow);
        controls.add(Box.createVerticalStrut(scale.scaleForGUI(12)));
        status.setForeground(MUTED);
        status.setFont(scale.font(base, Font.PLAIN, 11));
        status.setHorizontalAlignment(SwingConstants.CENTER);
        status.setAlignmentX(CENTER_ALIGNMENT);
        status.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                status.getPreferredSize().height));
        status.setName("homeStatusLabel");
        status.setVisible(true);
        controls.add(status);
        verticalCenterAfter = flexibleSpace();
        controls.add(verticalCenterAfter);

        scroller = new JScrollPane(controls, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroller.setName("firstLaunchControlsScroller");
        scroller.setBorder(BorderFactory.createEmptyBorder());
        scroller.getViewport().setBackground(PANEL);
        scroller.getVerticalScrollBar().setUnitIncrement(scale.scaleForGUI(18));
        add(scroller);
    }

    static BufferedImage loadArtwork() throws IOException {
        try (var input = FirstLaunchPanel.class.getResourceAsStream(ART_RESOURCE)) {
            if (input == null) throw new IOException("Bundled launcher artwork is missing");
            BufferedImage image = ImageIO.read(input);
            if (image == null) throw new IOException("Bundled launcher artwork could not be decoded");
            return image;
        }
    }

    /** Reuses the accepted full-aspect artwork for managed, unavailable, and recovery Home. */
    static JPanel managedHome(BufferedImage image, GuiScale scale, JPanel controls) {
        return new ManagedHomePanel(image, scale, controls);
    }

    private JLabel label(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private Box.Filler gap(int height) {
        Dimension size = scale.scaleForGUI(0, height);
        return new Box.Filler(size, size, new Dimension(Short.MAX_VALUE, size.height));
    }

    private Box.Filler flexibleSpace() {
        Dimension zero = new Dimension();
        return new Box.Filler(zero, zero,
                new Dimension(Short.MAX_VALUE, Short.MAX_VALUE));
    }

    private void resizeGap(Box.Filler gap, int height) {
        Dimension size = scale.scaleForGUI(0, height);
        gap.changeShape(size, size, new Dimension(Short.MAX_VALUE, size.height));
    }

    private void layoutActions(boolean stacked) {
        actionRow.removeAll();
        GridBagConstraints primary = new GridBagConstraints();
        primary.gridx = 0;
        primary.gridy = 0;
        primary.weightx = 1;
        primary.fill = GridBagConstraints.HORIZONTAL;
        primary.anchor = GridBagConstraints.NORTH;
        primary.insets = stacked ? new Insets(0, 0, scale.scaleForGUI(10), 0)
                : new Insets(0, 0, 0, scale.scaleForGUI(7));
        actionRow.add(primaryAction, primary);

        GridBagConstraints existing = new GridBagConstraints();
        existing.gridx = stacked ? 0 : 1;
        existing.gridy = stacked ? 1 : 0;
        existing.weightx = 1;
        existing.fill = GridBagConstraints.HORIZONTAL;
        existing.anchor = GridBagConstraints.NORTH;
        existing.insets = stacked ? new Insets(0, 0, 0, 0)
                : new Insets(0, scale.scaleForGUI(7), 0, 0);
        actionRow.add(existingAction, existing);
        actionRow.revalidate();
    }

    @Override
    public Dimension getPreferredSize() {
        return scale.scaleForGUI(1180, 760);
    }

    @Override
    public void doLayout() {
        boolean nextCompact = getWidth() < scale.scaleForGUI(850)
                || getHeight() < scale.scaleForGUI(620);
        if (compact != nextCompact) {
            compact = nextCompact;
            verticalCenterBefore.setVisible(!compact);
            layoutActions(compact);
            actionRow.setMaximumSize(new Dimension(
                    compact ? Integer.MAX_VALUE : scale.scaleForGUI(1000),
                    actionRow.getPreferredSize().height));
            controls.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(compact ? 18 : 26),
                    scale.scaleForGUI(25), scale.scaleForGUI(compact ? 14 : 20),
                    scale.scaleForGUI(25)));
            controls.revalidate();
        }
        int minimumDeck = scale.scaleForGUI(compact ? 260 : 150);
        int deckHeight = Math.min(getHeight(),
                Math.max(minimumDeck, controls.getPreferredSize().height));
        int artHeight = Math.max(0, getHeight() - deckHeight);
        artwork.setBounds(0, 0, getWidth(), artHeight);
        scroller.setBounds(0, artHeight, getWidth(), deckHeight);
    }

    private final class ContentPanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return scale.scaleForGUI(18);
        }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(scale.scaleForGUI(18), visible.height - scale.scaleForGUI(18));
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() >= getPreferredSize().height;
        }
    }

    private static final class ManagedHomePanel extends JPanel {
        private final GuiScale scale;
        private final ArtworkPanel artwork;
        private final JPanel deck;
        private final JScrollPane scroller;

        private ManagedHomePanel(BufferedImage image, GuiScale scale, JPanel controls) {
            this.scale = scale;
            setName("managedHomePanel");
            setLayout(null);
            setBackground(BACKGROUND);
            artwork = new ArtworkPanel(image, scale, true);
            artwork.setName("homeArtwork");
            artwork.getAccessibleContext().setAccessibleName("MegaMek launcher artwork");
            controls.setOpaque(true);
            controls.setBackground(PANEL);
            controls.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(16),
                    scale.scaleForGUI(20), scale.scaleForGUI(14), scale.scaleForGUI(20)));
            deck = new ManagedDeckPanel(scale);
            deck.setLayout(new BorderLayout());
            deck.add(controls, BorderLayout.CENTER);
            scroller = new JScrollPane(deck, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroller.setName("managedHomeDeckScroller");
            scroller.setBackground(PANEL);
            scroller.setBorder(BorderFactory.createEmptyBorder());
            scroller.getViewport().setBackground(PANEL);
            scroller.getVerticalScrollBar().setUnitIncrement(scale.scaleForGUI(18));
            add(artwork);
            add(scroller);
        }

        @Override
        public void doLayout() {
            int minimumArt = Math.min(scale.scaleForGUI(180),
                    Math.max(0, getHeight() * 35 / 100));
            int maximumDeck = Math.max(0, getHeight() - minimumArt);
            int deckHeight = Math.min(maximumDeck, deck.getPreferredSize().height);
            int artHeight = Math.max(0, getHeight() - deckHeight);
            artwork.setBounds(0, 0, getWidth(), artHeight);
            scroller.setBounds(0, artHeight, getWidth(), deckHeight);
        }
    }

    private static final class ManagedDeckPanel extends JPanel implements Scrollable {
        private final GuiScale scale;

        private ManagedDeckPanel(GuiScale scale) {
            this.scale = scale;
            setBackground(PANEL);
        }

        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override public int getScrollableUnitIncrement(
                Rectangle visible, int orientation, int direction) {
            return scale.scaleForGUI(18);
        }

        @Override public int getScrollableBlockIncrement(
                Rectangle visible, int orientation, int direction) {
            return Math.max(scale.scaleForGUI(18), visible.height - scale.scaleForGUI(18));
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() >= getPreferredSize().height;
        }
    }

    private static final class ArtworkPanel extends JPanel {
        private final BufferedImage image;
        private final GuiScale scale;
        private final boolean fill;

        private ArtworkPanel(BufferedImage image, GuiScale scale, boolean fill) {
            this.image = image;
            this.scale = scale;
            this.fill = fill;
            setBackground(BACKGROUND);
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (image == null) {
                graphics.setColor(MUTED);
                graphics.drawString("Artwork unavailable", scale.scaleForGUI(20), scale.scaleForGUI(30));
                return;
            }
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                if (fill) {
                    double ratio = Math.max((double) getWidth() / image.getWidth(),
                            (double) getHeight() / image.getHeight());
                    int width = (int) Math.ceil(image.getWidth() * ratio);
                    int height = (int) Math.ceil(image.getHeight() * ratio);
                    canvas.drawImage(image, (getWidth() - width) / 2,
                            (getHeight() - height) / 2, width, height, this);
                } else {
                    Rectangle destination = GuiScale.fitImage(image.getWidth(), image.getHeight(),
                            getWidth(), getHeight());
                    canvas.drawImage(image, destination.x, destination.y,
                            destination.width, destination.height, this);
                }
            } finally {
                canvas.dispose();
            }
        }
    }
}
