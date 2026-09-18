package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstLaunchPanelTest {
    @TempDir Path temp;

    @Test
    void bundledArtworkKeepsSuppliedDimensions() throws Exception {
        BufferedImage image = FirstLaunchPanel.loadArtwork();
        assertEquals(1280, image.getWidth());
        assertEquals(719, image.getHeight());
    }

    @Test
    void actionsAndResponsiveLayoutsRemainUsableAcrossPixelDensities() throws Exception {
        BufferedImage artwork = FirstLaunchPanel.loadArtwork();
        onEdt(() -> {
            var originalLookAndFeel = UIManager.getLookAndFeel();
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                List<String> actions = new ArrayList<>();
                FirstLaunchPanel panel = panel(artwork, GuiScale.DEFAULT, actions);
                for (double density : new double[]{1, 1.25, 1.5, 2}) {
                    BufferedImage rendered = render(panel, 1312, 560, density);
                    assertEquals((int) Math.round(1312 * density), rendered.getWidth());
                    assertEquals((int) Math.round(560 * density), rendered.getHeight());
                    assertActionsWithinPanel(panel);
                    saveReviewImage("wide-" + (int) (density * 100) + ".png", rendered);
                }
                for (int width : new int[]{800, 680}) {
                    BufferedImage rendered = render(panel, width, 560, 1);
                    assertActionsWithinPanel(panel);
                    saveReviewImage("compact-" + width + ".png", rendered);
                }
                for (String action : List.of("downloadAndInstallButton", "moreOptionsButton")) {
                    find(panel, action).doClick();
                }
                assertEquals(List.of("downloadAndInstallButton", "moreOptionsButton"), actions);
                assertTrue(find(panel, "downloadAndInstallButton").isFocusable());
                assertTrue(find(panel, "moreOptionsButton").isFocusable());
                assertEquals("Advanced Options", find(panel, "moreOptionsButton").getText());
                assertTrue(find(panel, "moreOptionsButton").getPreferredSize().height
                        < find(panel, "downloadAndInstallButton").getPreferredSize().height);

                FirstLaunchPanel zoomed = panel(artwork, new GuiScale(1.5f), new ArrayList<>());
                render(zoomed, 1312, 700, 1);
                JScrollPane scroll = (JScrollPane) zoomed.getComponent(1);
                assertFalse(scroll.getHorizontalScrollBar().isVisible());
            } finally {
                UIManager.setLookAndFeel(originalLookAndFeel);
            }
            return null;
        });
    }

    @Test
    void channelCaptionIsSeparateAndRedundantCopyIsAbsent() throws Exception {
        BufferedImage artwork = FirstLaunchPanel.loadArtwork();
        onEdt(() -> {
            JButton download = new FirstLaunchButton("Download & install",
                    "downloadAndInstallButton", true, GuiScale.DEFAULT);
            JButton advanced = new FirstLaunchButton("Advanced Options", "moreOptionsButton",
                    false, GuiScale.DEFAULT, FirstLaunchButton.Size.SMALL);
            JLabel status = new JLabel("");
            FirstLaunchPanel panel = new FirstLaunchPanel(artwork, GuiScale.DEFAULT,
                    download, advanced, "Development", status);
            render(panel, 1312, 560, 1);
            JLabel channel = (JLabel) findComponent(panel, "releaseChannelLabel");
            assertEquals("Development", channel.getText());
            Rectangle buttonBounds = SwingUtilities.convertRectangle(download.getParent(),
                    download.getBounds(), panel);
            Rectangle captionBounds = SwingUtilities.convertRectangle(channel.getParent(),
                    channel.getBounds(), panel);
            assertTrue(captionBounds.y >= buttonBounds.y + buttonBounds.height);
            assertFalse(status.isVisible());
            List<String> labels = new ArrayList<>();
            collectLabels(panel, labels);
            for (String removed : List.of("review", "all three", "stay where", "approval",
                    "Get the current", "MEGAMEK SUITE")) {
                assertTrue(labels.stream().noneMatch(text -> text.contains(removed)), removed);
            }
            return null;
        });
    }

    @Test
    void firstLaunchTransitionsToExistingManagedPresentationWithoutChangingRegistration()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        Path registry = temp.resolve("registry.json");
        LauncherServices services = new LauncherServices(registry);
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            onEdt(() -> { frame.showWindow(); return null; });
            waitFor(() -> findComponent(frame, "firstLaunchPanel") != null);
            assertEquals("Milestone", onEdt(() ->
                    ((JLabel) findComponent(frame, "releaseChannelLabel")).getText()));
            onEdt(() -> {
                assertTrue(GuiScale.usableBounds(frame.getGraphicsConfiguration()).contains(frame.getBounds()));
                String expected = System.getProperty("mm.launcher.expectedDeviceScale");
                if (expected != null) {
                    assertEquals(Double.parseDouble(expected),
                            frame.getGraphicsConfiguration().getDefaultTransform().getScaleX(), 0.01);
                }
                return null;
            });
            assertFalse(Files.exists(registry));
            Path root = Files.createDirectory(temp.resolve("copy"));
            Files.createDirectories(root.resolve("data"));
            Files.createDirectories(root.resolve("lib"));
            Files.createDirectories(root.resolve("mmconf"));
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
            try (JarOutputStream ignored = new JarOutputStream(
                    Files.newOutputStream(root.resolve("MegaMek.jar")), manifest)) {
            }
            var record = services.register("Test copy", root);
            services.setChannel(record, org.megamek.launcher.channel.FollowChannel.DEVELOPMENT, false);
            byte[] before = Files.readAllBytes(registry);
            onEdt(() -> { frame.showWindow(); return null; });
            waitFor(() -> find(frame, "launch-megamek-button") != null);
            assertNull(onEdt(() -> findComponent(frame, "firstLaunchPanel")));
            assertNotNull(onEdt(() -> findComponent(frame, "managedHomePanel")));
            assertNotNull(onEdt(() -> findComponent(frame, "homeArtwork")));
            assertEquals("Development", onEdt(() ->
                    ((JLabel) findComponent(frame, "releaseChannelLabel")).getText()));
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(registry)));
            JLabel status = (JLabel) onEdt(() -> findComponent(frame, "homeStatusLabel"));
            assertNotNull(status);
            assertEquals(UIManager.getColor("Label.foreground"), status.getForeground());
        } finally {
            onEdt(() -> {
                for (var window : frame.getOwnedWindows()) window.dispose();
                frame.dispose();
                return null;
            });
        }
    }

    private static FirstLaunchPanel panel(BufferedImage image, GuiScale scale, List<String> actions) {
        JButton download = new FirstLaunchButton("Download & install",
                "downloadAndInstallButton",
                true, scale);
        JButton more = new FirstLaunchButton("Advanced Options", "moreOptionsButton",
                false, scale, FirstLaunchButton.Size.SMALL);
        for (JButton button : List.of(download, more)) {
            button.addActionListener(event -> actions.add(button.getName()));
        }
        return new FirstLaunchPanel(image, scale, download, more, "Milestone", new JLabel(""));
    }

    private static void collectLabels(Container root, List<String> labels) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null) labels.add(label.getText());
            if (child instanceof Container container) collectLabels(container, labels);
        }
    }

    private static BufferedImage render(FirstLaunchPanel panel, int width, int height, double density) {
        panel.setSize(width, height);
        layout(panel);
        BufferedImage rendered = new BufferedImage((int) Math.round(width * density),
                (int) Math.round(height * density), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rendered.createGraphics();
        try {
            graphics.scale(density, density);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            panel.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        return rendered;
    }

    private static void assertActionsWithinPanel(FirstLaunchPanel panel) {
        for (String name : List.of("downloadAndInstallButton", "moreOptionsButton")) {
            JButton button = find(panel, name);
            assertNotNull(button, name);
            Rectangle bounds = SwingUtilities.convertRectangle(button.getParent(),
                    button.getBounds(), panel);
            assertTrue(bounds.width > 0 && bounds.height > 0, name + " must have space");
            assertTrue(new Rectangle(panel.getSize()).contains(bounds), name + ": " + bounds);
            int textWidth = button.getFontMetrics(button.getFont()).stringWidth(button.getText());
            assertTrue(textWidth + button.getInsets().left + button.getInsets().right <= bounds.width,
                    name + " text must not be clipped");
        }
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) {
            if (child instanceof Container container) layout(container);
        }
    }

    private static void saveReviewImage(String name, BufferedImage image) throws Exception {
        String directory = System.getenv("MM_LAUNCHER_REVIEW_IMAGES");
        if (directory != null) {
            Path path = Path.of(directory);
            Files.createDirectories(path);
            ImageIO.write(image, "PNG", path.resolve(name).toFile());
        }
    }

    private static JButton find(Container root, String name) {
        Component found = findComponent(root, name);
        return found instanceof JButton button ? button : null;
    }

    private static Component findComponent(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container container) {
                Component found = findComponent(container, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (onEdt(condition)) return;
            Thread.sleep(30);
        }
        throw new AssertionError("Timed out waiting for home presentation");
    }

    private static <T> T onEdt(Callable<T> operation) throws Exception {
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(operation.call());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            if (failure.get() instanceof Error error) throw error;
            throw new Exception(failure.get());
        }
        return result.get();
    }
}
