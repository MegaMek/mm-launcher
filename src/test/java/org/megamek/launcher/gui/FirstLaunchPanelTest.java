package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.accessibility.AccessibleRole;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
                FirstLaunchSplitButton split = (FirstLaunchSplitButton)
                        findComponent(panel, "firstLaunchSplitButton");
                find(panel, "downloadAndInstallButton").doClick();
                ((JMenuItem) split.popupMenu().getComponent(0)).doClick();
                ((JMenuItem) split.popupMenu().getComponent(1)).doClick();
                find(panel, "useExistingCopyButton").doClick();
                assertEquals(List.of("downloadAndInstallButton",
                        "latestDevelopmentMenuItem", "chooseAnotherVersionMenuItem",
                        "useExistingCopyButton"), actions);
                assertTrue(find(panel, "downloadAndInstallButton").isFocusable());
                assertTrue(find(panel, "downloadOptionsButton").isFocusable());
                assertTrue(find(panel, "useExistingCopyButton").isFocusable());
                assertEquals("Use existing installation",
                        find(panel, "useExistingCopyButton").getText());
                assertEquals("Choose another version or application",
                        find(panel, "downloadOptionsButton").getAccessibleContext()
                                .getAccessibleName());
                assertEquals(AccessibleRole.PUSH_BUTTON,
                        find(panel, "downloadAndInstallButton").getAccessibleContext()
                                .getAccessibleRole());
                assertEquals(AccessibleRole.PUSH_BUTTON,
                        find(panel, "downloadOptionsButton").getAccessibleContext()
                                .getAccessibleRole());
                assertNotNull(find(panel, "downloadAndInstallButton").getAccessibleContext()
                        .getAccessibleDescription());
                assertNotNull(find(panel, "downloadOptionsButton").getAccessibleContext()
                        .getAccessibleDescription());
                split.setEnabled(false);
                assertFalse(find(panel, "downloadAndInstallButton").isEnabled());
                assertFalse(find(panel, "downloadOptionsButton").isEnabled());
                split.setEnabled(true);
                assertTrue(find(panel, "downloadAndInstallButton").isEnabled());
                assertTrue(find(panel, "downloadOptionsButton").isEnabled());

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
            FirstLaunchSplitButton download = new FirstLaunchSplitButton(GuiScale.DEFAULT,
                    () -> {}, () -> {}, () -> {});
            JButton existing = new FirstLaunchButton("Use existing installation",
                    "useExistingCopyButton", false, GuiScale.DEFAULT);
            JLabel status = new JLabel("");
            FirstLaunchPanel panel = new FirstLaunchPanel(artwork, GuiScale.DEFAULT,
                    download, existing, "Latest Milestone", status);
            render(panel, 1312, 560, 1);
            JLabel channel = (JLabel) findComponent(panel, "releaseChannelLabel");
            JLabel existingCaption = (JLabel) findComponent(panel, "existingCopyCaption");
            assertEquals("Latest Milestone", channel.getText());
            panel.setReleaseChannel("Latest Milestone (0.51.0)", "Validated");
            assertEquals("Latest Milestone (0.51.0)", channel.getText());
            assertEquals("MegaMek, MekHQ, or MegaMekLab", existingCaption.getText());
            Rectangle buttonBounds = SwingUtilities.convertRectangle(download.getParent(),
                    download.getBounds(), panel);
            Rectangle captionBounds = SwingUtilities.convertRectangle(channel.getParent(),
                    channel.getBounds(), panel);
            assertTrue(captionBounds.y >= buttonBounds.y + buttonBounds.height);
            Rectangle existingBounds = SwingUtilities.convertRectangle(existing.getParent(),
                    existing.getBounds(), panel);
            assertTrue(existingBounds.y > captionBounds.y);
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
        LauncherServices services = new LauncherServices(registry) {
            @Override public String latestMilestoneVersion() throws InterruptedException {
                Thread.sleep(150);
                return "0.51.0";
            }
        };
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            onEdt(() -> { frame.showWindow(); return null; });
            waitFor(() -> findComponent(frame, "firstLaunchPanel") != null);
            waitFor(() -> "Latest Milestone (0.51.0)".equals(
                    ((JLabel) findComponent(frame, "releaseChannelLabel")).getText()));
            assertEquals("Latest Milestone (0.51.0)", onEdt(() ->
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

    @Test
    void milestoneLookupContinuesWhileDownloadMenuIsOpened() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        LauncherServices services = new LauncherServices(temp.resolve("metadata.json")) {
            @Override public String latestMilestoneVersion() throws InterruptedException {
                started.countDown();
                try {
                    release.await();
                    return "9.9.9";
                } catch (InterruptedException error) {
                    interrupted.set(true);
                    throw error;
                }
            }
        };
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            onEdt(() -> { frame.showWindow(); return null; });
            waitFor(() -> findComponent(frame, "firstLaunchPanel") != null);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals("Latest Milestone", onEdt(() ->
                    ((JLabel) findComponent(frame, "releaseChannelLabel")).getText()));
            assertTrue(onEdt(() -> find(frame, "downloadAndInstallButton").isEnabled()));
            assertTrue(onEdt(() -> find(frame, "useExistingCopyButton").isEnabled()));
            onEdt(() -> {
                find(frame, "downloadOptionsButton").doClick();
                return null;
            });
            FirstLaunchSplitButton split = (FirstLaunchSplitButton) onEdt(
                    () -> findComponent(frame, "firstLaunchSplitButton"));
            assertTrue(onEdt(() -> split.popupMenu().isVisible()));
            assertFalse(interrupted.get());
            release.countDown();
            waitFor(() -> "Latest Milestone (9.9.9)".equals(
                    ((JLabel) findComponent(frame, "releaseChannelLabel")).getText()));
            assertFalse(interrupted.get());
            onEdt(() -> {
                split.closePopup();
                return null;
            });
        } finally {
            release.countDown();
            onEdt(() -> {
                for (var window : frame.getOwnedWindows()) window.dispose();
                frame.dispose();
                return null;
            });
        }
    }

    @Test
    void unavailableMilestoneVersionKeepsGenericCaptionAndOnboarding() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        LauncherServices services = new LauncherServices(temp.resolve("offline.json")) {
            @Override public String latestMilestoneVersion() throws java.io.IOException {
                throw new java.io.IOException("fixture offline");
            }
        };
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            onEdt(() -> { frame.showWindow(); return null; });
            waitFor(() -> {
                JLabel label = (JLabel) findComponent(frame, "releaseChannelLabel");
                return label != null && label.getToolTipText() != null;
            });
            JLabel label = (JLabel) onEdt(() -> findComponent(frame, "releaseChannelLabel"));
            assertEquals("Latest Milestone", label.getText());
            assertTrue(label.getToolTipText().contains("unavailable"));
            assertTrue(onEdt(() -> find(frame, "downloadAndInstallButton").isEnabled()));
            assertFalse(Files.exists(temp.resolve("offline.json")));
        } finally {
            onEdt(() -> {
                for (var window : frame.getOwnedWindows()) window.dispose();
                frame.dispose();
                return null;
            });
        }
    }

    private static FirstLaunchPanel panel(BufferedImage image, GuiScale scale, List<String> actions) {
        FirstLaunchSplitButton download = new FirstLaunchSplitButton(scale,
                () -> actions.add("downloadAndInstallButton"),
                () -> actions.add("latestDevelopmentMenuItem"),
                () -> actions.add("chooseAnotherVersionMenuItem"));
        JButton existing = new FirstLaunchButton("Use existing installation",
                "useExistingCopyButton", false, scale);
        existing.addActionListener(event -> actions.add(existing.getName()));
        return new FirstLaunchPanel(image, scale, download, existing, "Latest Milestone",
                new JLabel(""));
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
        for (String name : List.of("downloadAndInstallButton", "downloadOptionsButton",
                "useExistingCopyButton")) {
            JButton button = find(panel, name);
            assertNotNull(button, name);
            Rectangle bounds = SwingUtilities.convertRectangle(button.getParent(),
                    button.getBounds(), panel);
            assertTrue(bounds.width > 0 && bounds.height > 0, name + " must have space");
            assertTrue(new Rectangle(panel.getSize()).contains(bounds), name + ": " + bounds);
            if (button.getText() != null && !button.getText().isEmpty()) {
                int textWidth = button.getFontMetrics(button.getFont())
                        .stringWidth(button.getText());
                assertTrue(textWidth + button.getInsets().left + button.getInsets().right
                                <= bounds.width,
                        name + " text must not be clipped");
            }
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
