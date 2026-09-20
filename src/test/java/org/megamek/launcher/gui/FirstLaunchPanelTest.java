package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.QuickInstallOption;
import org.megamek.launcher.release.OfficialRepository;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
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
import java.util.concurrent.atomic.AtomicInteger;
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
                    BufferedImage rendered = render(panel, 1180, 760, density);
                    assertEquals((int) Math.round(1180 * density), rendered.getWidth());
                    assertEquals((int) Math.round(760 * density), rendered.getHeight());
                    assertActionsWithinPanel(panel);
                    assertActionGroupCentered(panel);
                    saveReviewImage("wide-" + (int) (density * 100) + ".png", rendered);
                }
                for (int width : new int[]{800, 680}) {
                    BufferedImage rendered = render(panel, width, 560, 1);
                    assertActionsWithinPanel(panel);
                    saveReviewImage("compact-" + width + ".png", rendered);
                }
                FirstLaunchSplitButton split = (FirstLaunchSplitButton)
                        findComponent(panel, "firstLaunchSplitButton");
                assertEquals(5, split.popupMenu().getComponentCount());
                assertFalse(find(panel, "downloadAndInstallButton").isEnabled());
                assertFalse(find(panel, "downloadAndInstallButton").isFocusable());
                assertFalse(find(panel, "downloadOptionsButton").isEnabled());
                assertFalse(find(panel, "downloadOptionsButton").isFocusable());
                assertTrue(find(panel, "useExistingCopyButton").isEnabled());
                for (Component component : split.popupMenu().getComponents()) {
                    JMenuItem item = (JMenuItem) component;
                    assertFalse(item.isEnabled());
                    assertTrue(item.getText().endsWith("(Loading…)"));
                }
                split.setOptions(QuickInstallTestData.snapshot("0.51.0", "0.52.0"));
                find(panel, "downloadAndInstallButton").doClick();
                for (Component component : split.popupMenu().getComponents()) {
                    ((JMenuItem) component).doClick();
                }
                find(panel, "useExistingCopyButton").doClick();
                assertEquals(List.of("downloadAndInstallButton",
                        "latestMegaMekMilestoneMenuItem",
                        "latestMegaMekLabMilestoneMenuItem",
                        "latestMekHQDevelopmentMenuItem",
                        "latestMegaMekDevelopmentMenuItem",
                        "latestMegaMekLabDevelopmentMenuItem",
                        "useExistingCopyButton"), actions);
                assertEquals(List.of(
                                "Install latest MegaMek Milestone (0.51.0)",
                                "Install latest MegaMekLab Milestone (0.51.0)",
                                "Install latest MekHQ Development (0.52.0)",
                                "Install latest MegaMek Development (0.52.0)",
                                "Install latest MegaMekLab Development (0.52.0)"),
                        java.util.Arrays.stream(split.popupMenu().getComponents())
                                .map(JMenuItem.class::cast).map(JMenuItem::getText).toList());
                assertEquals(FirstLaunchSplitButton.POPUP_BACKGROUND,
                        split.popupMenu().getBackground());
                assertEquals(FirstLaunchSplitButton.POPUP_BORDER,
                        ((javax.swing.border.CompoundBorder) split.popupMenu().getBorder())
                                .getOutsideBorder() instanceof javax.swing.border.LineBorder line
                                ? line.getLineColor() : null);
                assertFalse(FirstLaunchSplitButton.POPUP_BACKGROUND.equals(
                        FirstLaunchSplitButton.POPUP_SELECTION));
                assertFalse(FirstLaunchSplitButton.POPUP_SELECTION.equals(
                        FirstLaunchSplitButton.POPUP_SELECTION_FOREGROUND));
                for (Component component : split.popupMenu().getComponents()) {
                    JMenuItem item = (JMenuItem) component;
                    assertTrue(item.isOpaque());
                    assertEquals(FirstLaunchSplitButton.POPUP_BACKGROUND,
                            item.getBackground());
                    assertEquals(FirstLaunchSplitButton.POPUP_FOREGROUND,
                            item.getForeground());
                    assertNotNull(item.getAccessibleContext().getAccessibleName());
                    assertNotNull(item.getAccessibleContext().getAccessibleDescription());
                    assertNull(item.getToolTipText());
                }
                assertTrue(find(panel, "downloadAndInstallButton").isFocusable());
                assertTrue(find(panel, "downloadOptionsButton").isFocusable());
                assertTrue(find(panel, "useExistingCopyButton").isFocusable());
                assertEquals("Use existing installation",
                        find(panel, "useExistingCopyButton").getText());
                assertEquals("Install latest MekHQ Milestone (0.51.0)",
                        find(panel, "downloadAndInstallButton").getText());
                assertEquals("Install latest MekHQ Milestone (0.51.0)",
                        find(panel, "downloadAndInstallButton").getAccessibleContext()
                                .getAccessibleName());
                assertNull(find(panel, "downloadAndInstallButton").getToolTipText());
                assertEquals("Choose another application and channel",
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
                    () -> {}, key -> {}, () -> {});
            JButton existing = new FirstLaunchButton("Use existing installation",
                    "useExistingCopyButton", false, GuiScale.DEFAULT);
            JLabel status = new JLabel("");
            FirstLaunchPanel panel = new FirstLaunchPanel(artwork, GuiScale.DEFAULT,
                    download, existing, status);
            render(panel, 1180, 760, 1);
            JLabel existingCaption = (JLabel) findComponent(panel, "existingCopyCaption");
            assertNull(findComponent(panel, "releaseChannelLabel"));
            download.setOptions(QuickInstallTestData.snapshot("0.51.0", "0.52.0"));
            assertEquals("Install latest MekHQ Milestone (0.51.0)",
                    download.primaryButton().getText());
            assertEquals("MegaMek, MekHQ, or MegaMekLab", existingCaption.getText());
            Rectangle buttonBounds = SwingUtilities.convertRectangle(download.getParent(),
                    download.getBounds(), panel);
            Rectangle existingBounds = SwingUtilities.convertRectangle(existing.getParent(),
                    existing.getBounds(), panel);
            assertTrue(existingBounds.x > buttonBounds.x + buttonBounds.width);
            assertEquals(buttonBounds.y, existingBounds.y);
            assertEquals(buttonBounds.width, existingBounds.width);
            assertNull(findComponent(panel, "homeTitle"));
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
    void styledPopupRetainsHighContrastKeyboardAccessibilityAndUnavailableDisabling()
            throws Exception {
        onEdt(() -> {
            Object previous = UIManager.get("Theme.highContrast");
            UIManager.put("Theme.highContrast", Boolean.TRUE);
            try {
                FirstLaunchSplitButton split = new FirstLaunchSplitButton(GuiScale.DEFAULT,
                        () -> {}, key -> {}, () -> {});
                assertEquals(UIManager.getColor("MenuItem.background") == null
                                ? java.awt.Color.BLACK
                                : UIManager.getColor("MenuItem.background"),
                        split.popupMenu().getBackground());
                JButton arrow = split.optionsButton();
                Object binding = arrow.getInputMap(JComponent.WHEN_FOCUSED).get(
                        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F4, 0));
                assertNotNull(binding);
                assertNotNull(arrow.getActionMap().get(binding));
                assertNotNull(split.popupMenu().getAccessibleContext().getAccessibleDescription());

                split.setOptions(QuickInstallTestData.snapshot("0.51.0", "0.52.0"));
                for (Component component : split.popupMenu().getComponents()) {
                    assertTrue(component.isEnabled());
                }
                split.setOptionsUnavailable("Offline fixture");
                assertEquals(6, split.popupMenu().getComponentCount());
                for (int index = 0; index < 5; index++) {
                    Component component = split.popupMenu().getComponent(index);
                    assertFalse(component.isEnabled());
                    assertTrue(((JMenuItem) component).getAccessibleContext()
                            .getAccessibleDescription().contains("Offline fixture"));
                }
                JMenuItem retry = (JMenuItem) split.popupMenu().getComponent(5);
                assertTrue(retry.isEnabled());
                assertTrue(retry.getAccessibleContext().getAccessibleDescription()
                        .contains("Offline fixture"));
            } finally {
                if (previous == null) UIManager.getDefaults().remove("Theme.highContrast");
                else UIManager.put("Theme.highContrast", previous);
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
            @Override public org.megamek.launcher.channel.QuickInstallSnapshot
                    quickInstallSnapshot() throws InterruptedException {
                Thread.sleep(150);
                return QuickInstallTestData.snapshot("0.51.0", "0.52.0");
            }
        };
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            onEdt(() -> { frame.showWindow(); return null; });
            waitFor(() -> findComponent(frame, "firstLaunchPanel") != null);
            waitFor(() -> "Install latest MekHQ Milestone (0.51.0)".equals(
                    find(frame, "downloadAndInstallButton").getText()));
            assertNull(onEdt(() -> findComponent(frame, "releaseChannelLabel")));
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
            byte[] before = Files.readAllBytes(registry);
            onEdt(() -> { frame.showWindow(); return null; });
            waitFor(() -> find(frame, "launch-megamek-button") != null);
            assertNull(onEdt(() -> findComponent(frame, "firstLaunchPanel")));
            JPanel managed = (JPanel) onEdt(
                    () -> findComponent(frame, "managedHomePanel"));
            Component artwork = onEdt(() -> findComponent(frame, "homeArtwork"));
            Component deck = onEdt(() -> findComponent(frame, "managedHomeDeckScroller"));
            assertNotNull(managed);
            assertNotNull(artwork);
            assertNotNull(deck);
            assertEquals("Launch MegaMek (unknown)", onEdt(() ->
                    find(frame, "launch-megamek-button").getText()));
            assertNull(onEdt(() -> findComponent(frame, "releaseChannelLabel")));
            onEdt(() -> {
                frame.setSize(820, 560);
                frame.validate();
                assertEquals(0, artwork.getX());
                assertEquals(0, artwork.getY());
                assertEquals(managed.getWidth(), artwork.getWidth(),
                        "managed artwork is full width rather than a left rail");
                assertEquals(managed.getWidth(), deck.getWidth());
                assertEquals(artwork.getHeight(), deck.getY(),
                        "the dark control deck sits directly below the artwork");
                for (String action : List.of("launch-megamek-button",
                        "manageInstallationsButton", "settingsButton")) {
                    Component component = findComponent(frame, action);
                    Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(),
                            component.getBounds(), managed);
                    assertTrue(new Rectangle(managed.getSize()).contains(bounds), action);
                }
                frame.setSize(680, 470);
                frame.validate();
                assertEquals(managed.getWidth(), artwork.getWidth());
                assertEquals(managed.getWidth(), deck.getWidth());
                for (String action : List.of("launch-megamek-button",
                        "manageInstallationsButton", "settingsButton")) {
                    Component component = findComponent(frame, action);
                    Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(),
                            component.getBounds(), managed);
                    assertTrue(new Rectangle(managed.getSize()).contains(bounds),
                            action + " remains reachable in compact managed Home");
                }
                return null;
            });
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(registry)));
            JLabel status = (JLabel) onEdt(() -> findComponent(frame, "homeStatusLabel"));
            assertNotNull(status);
            assertEquals(FirstLaunchPanel.MUTED, status.getForeground());
            assertEquals(SwingConstants.CENTER, status.getHorizontalAlignment());
            assertEquals(Component.CENTER_ALIGNMENT, status.getAlignmentX());
        } finally {
            onEdt(() -> {
                for (var window : frame.getOwnedWindows()) window.dispose();
                frame.dispose();
                return null;
            });
        }
    }

    @Test
    void sixChoiceLookupDisablesInstallSegmentsWithoutBlockingExistingImport()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger requests = new AtomicInteger();
        LauncherServices services = new LauncherServices(temp.resolve("metadata.json")) {
            @Override public org.megamek.launcher.channel.QuickInstallSnapshot
                    quickInstallSnapshot() throws InterruptedException {
                requests.incrementAndGet();
                started.countDown();
                try {
                    release.await();
                    return QuickInstallTestData.snapshot("9.9.9", "9.10.0");
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
            assertEquals("Install latest MekHQ Milestone (Loading…)", onEdt(() ->
                    find(frame, "downloadAndInstallButton").getText()));
            assertFalse(onEdt(() -> find(frame, "downloadAndInstallButton").isEnabled()));
            assertFalse(onEdt(() -> find(frame, "downloadAndInstallButton").isFocusable()));
            assertFalse(onEdt(() -> find(frame, "downloadOptionsButton").isEnabled()));
            assertFalse(onEdt(() -> find(frame, "downloadOptionsButton").isFocusable()));
            assertTrue(onEdt(() -> find(frame, "useExistingCopyButton").isEnabled()));
            onEdt(() -> {
                find(frame, "downloadOptionsButton").doClick();
                return null;
            });
            FirstLaunchSplitButton split = (FirstLaunchSplitButton) onEdt(
                    () -> findComponent(frame, "firstLaunchSplitButton"));
            assertFalse(onEdt(() -> split.popupMenu().isVisible()));
            assertEquals(1, requests.get(), "menu opening must start zero new requests");
            for (Component component : split.popupMenu().getComponents()) {
                assertFalse(component.isEnabled());
            }
            assertFalse(interrupted.get());
            release.countDown();
            waitFor(() -> "Install latest MekHQ Milestone (9.9.9)".equals(
                    find(frame, "downloadAndInstallButton").getText()));
            assertFalse(interrupted.get());
            assertTrue(onEdt(() -> find(frame, "downloadAndInstallButton").isEnabled()));
            assertTrue(onEdt(() -> find(frame, "downloadOptionsButton").isEnabled()));
            assertEquals("Install latest MekHQ Development (9.10.0)", onEdt(() ->
                    ((JMenuItem) split.popupMenu().getComponent(2)).getText()));
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
    void unavailableSnapshotDisablesInstallAndRetriesOnlyFromExplicitCommand() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicInteger requests = new AtomicInteger();
        LauncherServices services = new LauncherServices(temp.resolve("offline.json")) {
            @Override public org.megamek.launcher.channel.QuickInstallSnapshot
                    quickInstallSnapshot() throws java.io.IOException {
                requests.incrementAndGet();
                throw new java.io.IOException("fixture offline");
            }
        };
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            onEdt(() -> { frame.showWindow(); return null; });
            waitFor(() -> {
                JMenuItem item = menuItem(frame, "latestMegaMekMilestoneMenuItem");
                return item != null && item.getText().endsWith("(Unavailable)");
            });
            assertEquals("Install latest MekHQ Milestone (Unavailable)", onEdt(() ->
                    find(frame, "downloadAndInstallButton").getText()));
            assertFalse(onEdt(() -> find(frame, "downloadAndInstallButton").isEnabled()));
            FirstLaunchSplitButton split = (FirstLaunchSplitButton) onEdt(
                    () -> findComponent(frame, "firstLaunchSplitButton"));
            assertEquals(6, split.popupMenu().getComponentCount());
            for (int index = 0; index < 5; index++) {
                Component component = split.popupMenu().getComponent(index);
                assertFalse(component.isEnabled());
                assertTrue(((JMenuItem) component).getText().endsWith("(Unavailable)"));
            }
            JMenuItem retry = (JMenuItem) split.popupMenu().getComponent(5);
            assertEquals("Retry version check", retry.getText());
            assertTrue(retry.isEnabled());
            onEdt(() -> {
                find(frame, "downloadOptionsButton").doClick();
                return null;
            });
            assertEquals(1, requests.get(),
                    "first opening only shows unavailable rows");
            onEdt(() -> {
                retry.doClick();
                return null;
            });
            waitFor(() -> requests.get() == 2);
            assertFalse(Files.exists(temp.resolve("offline.json")));
        } finally {
            onEdt(() -> {
                for (var window : frame.getOwnedWindows()) window.dispose();
                frame.dispose();
                return null;
            });
        }
    }

    @Test
    void reloadedFirstLaunchReusesTheInFlightSessionSnapshot() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch holdFirst = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        LauncherServices services = new LauncherServices(temp.resolve("stale.json")) {
            @Override public org.megamek.launcher.channel.QuickInstallSnapshot
                    quickInstallSnapshot() throws InterruptedException {
                requests.incrementAndGet();
                firstStarted.countDown();
                holdFirst.await();
                return QuickInstallTestData.snapshot("1.0.0", "1.1.0");
            }
        };
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            onEdt(() -> { frame.showWindow(); return null; });
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            onEdt(() -> { frame.showWindow(); return null; });
            waitFor(() -> find(frame, "downloadAndInstallButton") != null);
            assertEquals(1, requests.get());
            holdFirst.countDown();
            waitFor(() -> "Install latest MekHQ Milestone (1.0.0)".equals(
                    find(frame, "downloadAndInstallButton").getText()));
            Thread.sleep(100);
            assertEquals("Install latest MekHQ Milestone (1.0.0)", onEdt(() ->
                    find(frame, "downloadAndInstallButton").getText()));
            assertEquals(1, requests.get());
        } finally {
            holdFirst.countDown();
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
                key -> actions.add(actionName(key)), () -> {});
        JButton existing = new FirstLaunchButton("Use existing installation",
                "useExistingCopyButton", false, scale);
        existing.addActionListener(event -> actions.add(existing.getName()));
        return new FirstLaunchPanel(image, scale, download, existing, new JLabel(""));
    }

    private static String actionName(QuickInstallOption.Key key) {
        if (key.repository() == OfficialRepository.MEGAMEK
                && key.channel() == FollowChannel.MILESTONE) {
            return "latestMegaMekMilestoneMenuItem";
        }
        if (key.repository() == OfficialRepository.LAB
                && key.channel() == FollowChannel.MILESTONE) {
            return "latestMegaMekLabMilestoneMenuItem";
        }
        if (key.repository() == OfficialRepository.MEKHQ) {
            return "latestMekHQDevelopmentMenuItem";
        }
        if (key.repository() == OfficialRepository.MEGAMEK) {
            return "latestMegaMekDevelopmentMenuItem";
        }
        return "latestMegaMekLabDevelopmentMenuItem";
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

    private static void assertActionGroupCentered(FirstLaunchPanel panel) {
        Component actions = findComponent(panel, "firstLaunchActions");
        Component scroller = findComponent(panel, "firstLaunchControlsScroller");
        Rectangle actionsBounds = SwingUtilities.convertRectangle(actions.getParent(),
                actions.getBounds(), panel);
        Rectangle deckBounds = SwingUtilities.convertRectangle(scroller.getParent(),
                scroller.getBounds(), panel);
        int groupCenter = actionsBounds.y + actionsBounds.height / 2;
        int deckCenter = deckBounds.y + deckBounds.height / 2;
        assertTrue(Math.abs(groupCenter - deckCenter) <= 50,
                "title/action group should be centered in the control deck: " + groupCenter);
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

    private static JMenuItem menuItem(Container root, String name) {
        Component split = findComponent(root, "firstLaunchSplitButton");
        if (split instanceof FirstLaunchSplitButton button) {
            for (Component component : button.popupMenu().getComponents()) {
                if (name.equals(component.getName()) && component instanceof JMenuItem item) {
                    return item;
                }
            }
        }
        return null;
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
