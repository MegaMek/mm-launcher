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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** Empty-home presentation only; all user actions are supplied by the existing launcher frame. */
final class FirstLaunchPanel extends JPanel {
    static final String ART_RESOURCE = "/org/megamek/launcher/gui/first-launch-art.png";
    private static final Color BACKGROUND = new Color(16, 26, 29);
    private static final Color PANEL = new Color(22, 36, 40);
    private static final Color TEXT = new Color(237, 243, 237);
    private static final Color MUTED = new Color(166, 186, 181);
    private final GuiScale scale;
    private final ArtworkPanel artwork;
    private final ContentPanel controls;
    private final JScrollPane scroller;
    private final JLabel releaseChannelLabel;
    private final Box.Filler actionsGap;
    private boolean compact;

    FirstLaunchPanel(BufferedImage image, GuiScale scale, FirstLaunchSplitButton download,
                     JButton useExisting, String releaseChannel, JLabel status) {
        this.scale = scale;
        setName("firstLaunchPanel");
        setLayout(null);
        setBackground(BACKGROUND);
        artwork = new ArtworkPanel(image, scale);
        artwork.setName("firstLaunchArtwork");
        artwork.getAccessibleContext().setAccessibleName("MegaMek launcher artwork");
        add(artwork);

        controls = new ContentPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBackground(PANEL);
        controls.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(26),
                scale.scaleForGUI(25), scale.scaleForGUI(20), scale.scaleForGUI(25)));
        Font base = UIManager.getFont("Label.font");
        JLabel title = label("MegaMek Launcher", scale.font(base, Font.BOLD, 27), TEXT);
        title.setName("homeTitle");
        controls.add(title);
        actionsGap = gap(28);
        controls.add(actionsGap);

        JPanel primaryAction = new JPanel(new BorderLayout(0, scale.scaleForGUI(9)));
        primaryAction.setOpaque(false);
        primaryAction.setAlignmentX(LEFT_ALIGNMENT);
        primaryAction.add(download, BorderLayout.CENTER);
        releaseChannelLabel = label(releaseChannel, scale.font(base, Font.PLAIN, 14), MUTED);
        releaseChannelLabel.setName("releaseChannelLabel");
        releaseChannelLabel.setHorizontalAlignment(SwingConstants.CENTER);
        primaryAction.add(releaseChannelLabel, BorderLayout.SOUTH);
        primaryAction.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                primaryAction.getPreferredSize().height));
        controls.add(primaryAction);
        controls.add(Box.createVerticalStrut(scale.scaleForGUI(14)));

        JPanel existingAction = new JPanel(new BorderLayout(0, scale.scaleForGUI(5)));
        existingAction.setOpaque(false);
        existingAction.setAlignmentX(LEFT_ALIGNMENT);
        existingAction.add(useExisting, BorderLayout.CENTER);
        JLabel existingCaption = label("MegaMek, MekHQ, or MegaMekLab",
                scale.font(base, Font.PLAIN, 12), MUTED);
        existingCaption.setName("existingCopyCaption");
        existingCaption.setHorizontalAlignment(SwingConstants.CENTER);
        existingAction.add(existingCaption, BorderLayout.SOUTH);
        existingAction.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                existingAction.getPreferredSize().height));
        controls.add(existingAction);
        controls.add(Box.createVerticalStrut(scale.scaleForGUI(12)));
        status.setForeground(MUTED);
        status.setFont(scale.font(base, Font.PLAIN, 11));
        status.setAlignmentX(LEFT_ALIGNMENT);
        status.setName("homeStatusLabel");
        status.setVisible(status.getText() != null && !status.getText().isBlank());
        controls.add(status);
        controls.add(Box.createVerticalGlue());

        scroller = new JScrollPane(controls, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroller.setBorder(BorderFactory.createEmptyBorder());
        scroller.getViewport().setBackground(PANEL);
        scroller.getVerticalScrollBar().setUnitIncrement(scale.scaleForGUI(18));
        add(scroller);
    }

    void setReleaseChannel(String text, String detail) {
        releaseChannelLabel.setText(text);
        releaseChannelLabel.setToolTipText(detail);
        releaseChannelLabel.getAccessibleContext().setAccessibleDescription(
                detail == null ? text : text + ". " + detail);
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

    private void resizeGap(Box.Filler gap, int height) {
        Dimension size = scale.scaleForGUI(0, height);
        gap.changeShape(size, size, new Dimension(Short.MAX_VALUE, size.height));
    }

    @Override
    public Dimension getPreferredSize() {
        return scale.scaleForGUI(1312, 560);
    }

    @Override
    public void doLayout() {
        boolean nextCompact = getWidth() < scale.scaleForGUI(1000);
        if (compact != nextCompact) {
            compact = nextCompact;
            resizeGap(actionsGap, compact ? 18 : 28);
            controls.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(compact ? 18 : 26),
                    scale.scaleForGUI(25), scale.scaleForGUI(compact ? 14 : 20),
                    scale.scaleForGUI(25)));
            controls.revalidate();
        }
        if (compact) {
            int artHeight = Math.min(scale.scaleForGUI(260), getHeight() * 45 / 100);
            artwork.setBounds(0, 0, getWidth(), artHeight);
            scroller.setBounds(0, artHeight, getWidth(), Math.max(0, getHeight() - artHeight));
        } else {
            int sidebar = scale.scaleForGUI(352);
            artwork.setBounds(0, 0, Math.max(0, getWidth() - sidebar), getHeight());
            scroller.setBounds(getWidth() - sidebar, 0, sidebar, getHeight());
        }
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
        private final JScrollPane scroller;

        private ManagedHomePanel(BufferedImage image, GuiScale scale, JPanel controls) {
            this.scale = scale;
            setName("managedHomePanel");
            setLayout(null);
            setBackground(BACKGROUND);
            artwork = new ArtworkPanel(image, scale);
            artwork.setName("homeArtwork");
            artwork.getAccessibleContext().setAccessibleName("MegaMek launcher artwork");
            controls.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(16),
                    scale.scaleForGUI(20), scale.scaleForGUI(14), scale.scaleForGUI(20)));
            scroller = new JScrollPane(controls, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroller.setBorder(BorderFactory.createEmptyBorder());
            scroller.getVerticalScrollBar().setUnitIncrement(scale.scaleForGUI(18));
            add(artwork);
            add(scroller);
        }

        @Override
        public void doLayout() {
            if (getWidth() < scale.scaleForGUI(850)) {
                int artHeight = Math.min(scale.scaleForGUI(190), getHeight() * 38 / 100);
                artwork.setBounds(0, 0, getWidth(), artHeight);
                scroller.setBounds(0, artHeight, getWidth(),
                        Math.max(0, getHeight() - artHeight));
            } else {
                int artWidth = Math.min(scale.scaleForGUI(430), getWidth() * 45 / 100);
                artwork.setBounds(0, 0, artWidth, getHeight());
                scroller.setBounds(artWidth, 0, Math.max(0, getWidth() - artWidth), getHeight());
            }
        }
    }

    private static final class ArtworkPanel extends JPanel {
        private final BufferedImage image;
        private final GuiScale scale;

        private ArtworkPanel(BufferedImage image, GuiScale scale) {
            this.image = image;
            this.scale = scale;
            setBackground(BACKGROUND);
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (image == null) {
                graphics.setColor(MUTED);
                graphics.drawString("Artwork unavailable", scale.scaleForGUI(20), scale.scaleForGUI(30));
                return;
            }
            Rectangle destination = GuiScale.fitImage(image.getWidth(), image.getHeight(),
                    getWidth(), getHeight());
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                canvas.drawImage(image, destination.x, destination.y,
                        destination.width, destination.height, this);
            } finally {
                canvas.dispose();
            }
        }
    }
}
