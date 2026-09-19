package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.QuickInstallOption;
import org.megamek.launcher.channel.QuickInstallSnapshot;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.NormalInstallService;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.update.UpdatePreviewService;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleHomeSwingTest {
    @TempDir Path temp;

    @Test
    void firstHomeSplitRoutesExplicitChoicesWithoutOpeningNetworkOrChangingDefault()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("registry.json"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton primary = waitButton(frame, "downloadAndInstallButton");
            JButton options = waitButton(frame, "downloadOptionsButton");
            FirstLaunchSplitButton split = waitFor(
                    () -> find(frame, "firstLaunchSplitButton"));
            assertNotNull(primary);
            assertNotNull(options);
            assertNotNull(find(frame, "useExistingCopyButton"));
            assertNull(find(frame, "downloadProductCombo"));
            assertNull(find(frame, "manageDownloadMegaMekButton"),
                    "the exact picker is deferred to Installations");
            waitUntil(() -> "1.2.3".equals(
                    ((JLabel) find(frame, "releaseChannelLabel")).getText()));
            int catalogRequests = services.catalogRequests.get();

            invokeKeyBinding(primary, KeyStroke.getKeyStroke(
                    KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK));
            waitUntil(() -> split.popupMenu().isVisible());
            JPopupMenu popup = split.popupMenu();
            waitUntil(() -> popup.getComponent(0).isEnabled());
            assertEquals(5, popup.getComponentCount());
            List<String> menuLabels = java.util.Arrays.stream(popup.getComponents())
                    .map(JMenuItem.class::cast).map(JMenuItem::getText).toList();
            assertEquals(List.of(
                            "Install latest MegaMek Milestone (1.2.3)",
                            "Install latest MegaMekLab Milestone (1.2.3)",
                            "Install latest MekHQ Development (1.2.4)",
                            "Install latest MegaMek Development (1.2.4)",
                            "Install latest MegaMekLab Development (1.2.4)"),
                    menuLabels);
            assertFalse(menuLabels.contains("Choose another version or application…"));
            assertTrue(menuLabels.stream().noneMatch(label -> label.contains("Advanced")));
            assertEquals(0, services.plans.get());
            assertEquals(0, services.releaseFetches.get());
            assertEquals(catalogRequests, services.catalogRequests.get(),
                    "opening the menu must start zero new metadata requests");
            invokePopupEscape(popup);
            waitUntil(() -> !popup.isVisible());

            for (int index = 0; index < QuickInstallSnapshot.MENU_KEYS.size(); index++) {
                QuickInstallOption.Key key = QuickInstallSnapshot.MENU_KEYS.get(index);
                int itemIndex = index;
                waitUntil(() -> popup.getComponent(itemIndex).isEnabled());
                SwingUtilities.invokeAndWait(options::doClick);
                invokeMenuSelection((JMenuItem) popup.getComponent(index));
                String version = key.channel() == FollowChannel.MILESTONE
                        ? "1.2.3" : "1.2.4";
                JDialog alternate = waitDialog(frame, "Install latest "
                        + display(key.repository()) + " " + key.channel());
                assertEquals(key, services.plannedChoices.get(index));
                assertEquals(index + 1, services.plans.get());
                assertEquals(0, services.installs.get());
                ReleaseCatalog.Asset plannedAsset =
                        services.quickInstallSnapshot.option(key).asset();
                assertNormalInstallQuote(alternate, key.repository(), key.channel(), version,
                        plannedAsset, services.destination);
                if (index == 0) {
                    assertComponentsWithin(alternate, List.of(
                            "normalInstallSummary", "normalInstallDestination",
                            "changeNormalLocationButton",
                            "cancelNormalInstallButton", "confirmNormalInstallButton"));
                    invokeDialogEscape(alternate);
                } else if (index == 1) {
                    JButton staleInstall = find(alternate, "confirmNormalInstallButton");
                    SwingUtilities.invokeAndWait(() -> {
                        alternate.dispatchEvent(new WindowEvent(
                                alternate, WindowEvent.WINDOW_CLOSING));
                        staleInstall.doClick();
                    });
                    assertEquals(index + 1, services.plans.get(),
                            "a closed quote cannot invoke a retained action");
                    assertEquals(0, services.installs.get());
                } else {
                    SwingUtilities.invokeAndWait(
                            () -> ((JButton) find(alternate,
                                    "cancelNormalInstallButton")).doClick());
                }
                waitUntil(() -> !alternate.isDisplayable());
            }
            assertEquals(catalogRequests + 5, services.catalogRequests.get(),
                    "cancelling a quote refreshes Home; menu opening itself does not");
            assertEquals("1.2.3", onEdt(() ->
                    ((JLabel) find(frame, "releaseChannelLabel")).getText()));
            assertEquals(0, services.releaseFetches.get());
            assertFalse(Files.exists(temp.resolve("registry.json")));

            services.planStarted = new CountDownLatch(1);
            services.releasePlan = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(primary::doClick);
            assertTrue(services.planStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(onEdt(primary::isEnabled));
            assertFalse(onEdt(options::isEnabled));
            services.releasePlan.countDown();
            JDialog confirmation = waitDialog(frame, "Install latest MekHQ Milestone");
            assertEquals(FollowChannel.MILESTONE, services.lastPlannedChannel);
            assertEquals(OfficialRepository.MEKHQ, services.lastPlannedRepository);
            assertEquals(6, services.plans.get());
            assertTrue(onEdt(primary::isEnabled));
            assertTrue(onEdt(options::isEnabled));
            assertNull(find(confirmation, "downloadProductCombo"));
            assertNull(find(confirmation, "downloadChannelCombo"));
            assertNull(find(confirmation, "changeNormalJavaButton"));
            assertNormalInstallQuote(confirmation, OfficialRepository.MEKHQ,
                    FollowChannel.MILESTONE, "1.2.3",
                    services.quickInstallSnapshot.option(new QuickInstallOption.Key(
                            OfficialRepository.MEKHQ, FollowChannel.MILESTONE)).asset(),
                    services.destination);

            services.installStarted = new CountDownLatch(1);
            services.releaseInstall = new CountDownLatch(1);
            JButton install = find(confirmation, "confirmNormalInstallButton");
            SwingUtilities.invokeAndWait(() -> {
                install.doClick();
                install.doClick();
                confirmation.dispose();
            });
            assertTrue(services.installStarted.await(
                    5, java.util.concurrent.TimeUnit.SECONDS));
            JDialog progress = waitDialog(frame, "Installing MekHQ 1.2.3");
            assertEquals("Cancel",
                    ((JButton) find(progress, "operationCancelButton")).getText());
            assertTrue(find(progress, "operationCancelButton").isVisible());
            assertFalse(find(progress, "repairNormalInstallButton").isVisible());
            assertFalse(find(progress, "operationViewDetailsButton").isVisible());
            assertNull(find(progress, "operationViewLogsButton"));
            assertNull(find(progress, "copyOperationDetailsButton"));
            assertNull(find(progress, "normalInstallProgressLog"));
            services.releaseInstall.countDown();
            JLabel notice = waitFor(() -> find(frame, "installationReadyNotice"));
            assertEquals("MekHQ 1.2.3 is ready", notice.getText());
            assertNoShowingDialog(frame, "Install complete");
            assertNoDisplayableProgress(frame);
            assertNotNull(waitButton(frame, "launch-megamek-button"));
            assertNotNull(waitButton(frame, "launch-mekhq-button"));
            assertNotNull(waitButton(frame, "launch-lab-button"));
            HomeLaunchSplitButton megamekSplit =
                    find(frame, "launch-megamek-split-button");
            HomeLaunchSplitButton mekhqSplit = find(frame, "launch-mekhq-split-button");
            HomeLaunchSplitButton labSplit = find(frame, "launch-lab-split-button");
            assertNotNull(megamekSplit);
            assertNotNull(mekhqSplit);
            assertNotNull(labSplit);
            assertTrue(megamekSplit.hasOptions());
            assertTrue(mekhqSplit.hasOptions());
            assertTrue(labSplit.hasOptions());
            for (HomeLaunchSplitButton split
                    : List.of(megamekSplit, mekhqSplit, labSplit)) {
                assertEquals(List.of("Other copy · 1.2.3"),
                        java.util.Arrays.stream(split.popupMenu().getComponents())
                                .map(JMenuItem.class::cast).map(JMenuItem::getText).toList(),
                        "every actual Main product gets its matching alternate menu");
            }
            SwingUtilities.invokeAndWait(() -> {
                frame.setSize(680, 470);
                frame.validate();
            });
            assertTrue(labSplit.getY() > megamekSplit.getY(),
                    "three launch controls wrap when the compact deck cannot fit one row");
            SwingUtilities.invokeAndWait(() -> {
                frame.setSize(900, 600);
                frame.validate();
            });
            assertEquals(megamekSplit.getWidth(), mekhqSplit.getWidth());
            assertEquals(mekhqSplit.getWidth(), labSplit.getWidth());
            assertFalse(megamekSplit.primaryButton().isContentAreaFilled(),
                    "managed launch controls use vector painting rather than OS white buttons");

            int selections = services.selections.get();
            SwingUtilities.invokeAndWait(megamekSplit.optionsButton()::doClick);
            waitUntil(() -> megamekSplit.popupMenu().isVisible());
            invokeMenuSelection((JMenuItem) megamekSplit.popupMenu().getComponent(0));
            JDialog alternateLaunch = waitDialog(frame, "Confirm launch");
            assertEquals(services.second, services.previewedRecord);
            assertEquals("megamek", services.previewedProduct);
            assertEquals(selections, services.selections.get(),
                    "alternate launch performs no preference mutation");
            assertEquals(services.first.id(), services.main.id());
            SwingUtilities.invokeAndWait(alternateLaunch::dispose);

            assertEquals(1, services.installs.get());
            assertEquals(FollowChannel.MILESTONE, services.installedChannel);
            assertFalse(services.launched);

            JButton installations = waitButton(frame, "manageInstallationsButton");
            SwingUtilities.invokeAndWait(installations::doClick);
            assertNotNull(waitButton(frame, "manageAddExistingButton"));
            JButton exactPicker = waitButton(frame, "installAnotherVersionButton");
            assertNotNull(exactPicker);
            SwingUtilities.invokeAndWait(exactPicker::doClick);
            JDialog picker = waitDialog(frame, "Download an official release");
            assertEquals(0, services.releaseFetches.get(),
                    "the deferred exact picker waits for Fetch releases");
            SwingUtilities.invokeAndWait(picker::dispose);
            assertNotNull(find(frame, "installationCardsScrollPane"));
            JPanel cards = find(frame, "installationCards");
            assertEquals(FirstLaunchPanel.BACKGROUND, cards.getBackground());
            JPanel firstCard = find(frame, "installationCard-" + services.first.id());
            assertEquals(FirstLaunchPanel.PANEL, firstCard.getBackground());
            assertNotNull(firstCard.getAccessibleContext().getAccessibleName());
            JButton firstMenu = waitButton(frame,
                    "installationMenuButton-" + services.first.id());
            assertNotNull(firstMenu);
            assertNotNull(waitButton(frame,
                    "installationMenuButton-" + services.second.id()));
            SwingUtilities.invokeAndWait(firstMenu::doClick);
            waitUntil(() -> java.util.Arrays.stream(
                            javax.swing.MenuSelectionManager.defaultManager().getSelectedPath())
                    .anyMatch(element -> element instanceof JPopupMenu));
            JPopupMenu cardMenu = (JPopupMenu) java.util.Arrays.stream(
                            javax.swing.MenuSelectionManager.defaultManager().getSelectedPath())
                    .filter(element -> element instanceof JPopupMenu)
                    .findFirst().orElseThrow();
            List<String> preferenceActions = java.util.Arrays.stream(cardMenu.getComponents())
                    .filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast)
                    .map(JMenuItem::getText)
                    .filter(text -> text.startsWith("Use as preferred for ")).toList();
            assertEquals(List.of("Use as preferred for MegaMek",
                    "Use as preferred for MekHQ",
                    "Use as preferred for MegaMekLab"), preferenceActions);
            SwingUtilities.invokeAndWait(() ->
                    javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath());
            assertNull(find(frame, "updateAllButton"), "there is no bulk update action");
            JButton settings = waitButton(frame, "settingsButton");
            SwingUtilities.invokeAndWait(settings::doClick);
            assertNotNull(waitButton(frame, "viewOperationLogsButton"));
            JButton home = waitButton(frame, "homeButton");
            waitUntil(home::isEnabled);
            SwingUtilities.invokeAndWait(home::doClick);
            assertNull(find(frame, "installationReadyNotice"),
                    "navigation clears the low-prominence success notice");
        } finally {
            dispose(frame);
        }
    }

    @Test
    void changeLocationDisposesTheOldQuoteAndReplansBeforeReconfirmation() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("change-location.json"));
        Path changed = temp.resolve("chosen-parent").resolve("MekHQ Milestone")
                .toAbsolutePath().normalize();
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services,
                (parent, plan) -> changed));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton primary = waitButton(frame, "downloadAndInstallButton");
            SwingUtilities.invokeAndWait(primary::doClick);
            JDialog firstQuote = waitDialog(frame, "Install latest MekHQ Milestone");
            int catalogBeforeChange = services.catalogRequests.get();
            assertEquals(services.destination.toString(),
                    ((JTextArea) find(firstQuote, "normalInstallDestination")).getText());

            JButton change = find(firstQuote, "changeNormalLocationButton");
            SwingUtilities.invokeAndWait(() -> {
                change.doClick();
                change.doClick();
            });
            waitUntil(() -> !firstQuote.isDisplayable());
            JDialog changedQuote = waitDialog(frame, "Install latest MekHQ Milestone");
            assertEquals(2, services.plans.get(), "Change location prepares one fresh plan");
            assertEquals(List.of(services.destination, changed),
                    services.plannedDestinations);
            assertEquals(changed.toString(),
                    ((JTextArea) find(changedQuote, "normalInstallDestination")).getText());
            assertEquals(0, services.installs.get());
            assertEquals(catalogBeforeChange, services.catalogRequests.get(),
                    "replanning does not briefly resume or reload first-launch choices");

            JButton cancel = find(changedQuote, "cancelNormalInstallButton");
            SwingUtilities.invokeAndWait(() -> {
                cancel.doClick();
                cancel.doClick();
                changedQuote.dispose();
            });
            waitUntil(() -> !changedQuote.isDisplayable());
            waitUntil(() -> services.catalogRequests.get() == catalogBeforeChange + 1);
            assertEquals(0, services.installs.get());
        } finally {
            dispose(frame);
        }
    }

    @Test
    void humanDownloadSizesUseStableBinaryUnitsWithoutOverflow() {
        assertEquals("0 bytes", NormalInstallConfirmationDialog.formatBinaryBytes(0));
        assertEquals("1 byte", NormalInstallConfirmationDialog.formatBinaryBytes(1));
        assertEquals("1.0 KiB", NormalInstallConfirmationDialog.formatBinaryBytes(1L << 10));
        assertEquals("657.7 MiB",
                NormalInstallConfirmationDialog.formatBinaryBytes(689_648_435L));
        assertEquals("1.0 GiB",
                NormalInstallConfirmationDialog.formatBinaryBytes(1L << 30));
        assertTrue(NormalInstallConfirmationDialog.formatBinaryBytes(Long.MAX_VALUE)
                .endsWith(" GiB"));
        assertThrows(IllegalArgumentException.class,
                () -> NormalInstallConfirmationDialog.formatBinaryBytes(-1));
    }

    @Test
    void latestMekHQDevelopmentCompletesThroughSharedInstallerAndShowsDevelopment()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("development.json"));
        services.checkOnOpen = true;
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton options = waitButton(frame, "downloadOptionsButton");
            FirstLaunchSplitButton split = waitFor(
                    () -> find(frame, "firstLaunchSplitButton"));
            SwingUtilities.invokeAndWait(options::doClick);
            waitUntil(() -> split.popupMenu().isVisible());
            waitUntil(() -> split.popupMenu().getComponent(2).isEnabled());
            invokeMenuSelection((JMenuItem) split.popupMenu().getComponent(2));

            JDialog confirmation = waitDialog(frame, "Install latest MekHQ Development");
            assertNormalInstallQuote(confirmation, OfficialRepository.MEKHQ,
                    FollowChannel.DEVELOPMENT, "1.2.4",
                    services.quickInstallSnapshot.option(new QuickInstallOption.Key(
                            OfficialRepository.MEKHQ, FollowChannel.DEVELOPMENT)).asset(),
                    services.destination);
            assertEquals(0, services.installs.get(),
                    "confirmation must precede the shared install backend");
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(confirmation,
                            "confirmNormalInstallButton")).doClick());
            JLabel notice = waitFor(() -> find(frame, "installationReadyNotice"));
            assertEquals("MekHQ 1.2.4 is ready", notice.getText());
            assertNoShowingDialog(frame, "Install complete");
            assertNoDisplayableProgress(frame);

            assertEquals("Launch MekHQ 1.2.4",
                    waitButton(frame, "launch-mekhq-button").getText());
            assertEquals(FollowChannel.DEVELOPMENT, services.lastPlannedChannel);
            assertEquals(FollowChannel.DEVELOPMENT, services.installedChannel);
            assertEquals(1, services.plans.get());
            assertEquals(1, services.installs.get());
        } finally {
            dispose(frame);
        }
    }

    @Test
    void concurrentMainIsPreservedAndInstallNoticePointsToInstallations() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("concurrent-main.json"));
        services.installBecameMain = false;
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton primary = waitButton(frame, "downloadAndInstallButton");
            SwingUtilities.invokeAndWait(primary::doClick);
            JDialog confirmation = waitDialog(frame, "Install latest MekHQ Milestone");
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(confirmation,
                            "confirmNormalInstallButton")).doClick());

            JLabel notice = waitFor(() -> find(frame, "installationReadyNotice"));
            assertEquals("MekHQ 1.2.3 is ready", notice.getText());
            assertNull(find(frame, "mainInstallationName"),
                    "managed Home has no visible global Main block");
            assertNotNull(find(frame, "launch-megamek-button"),
                    "Home continues to show the existing preferred application action");
            assertNoShowingDialog(frame, "Install complete");
            assertNoDisplayableProgress(frame);
        } finally {
            dispose(frame);
        }
    }

    @Test
    void publishedNormalFailureRevealsRepairOnlyInCompactFailureState() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("published-failure.json"));
        services.installFailure = new NormalInstallService.PublishedInstallationException(
                services.destination, "fixture-installation",
                "The published copy needs setup repair.", null);
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(frame, "downloadAndInstallButton")).doClick());
            JDialog confirmation = waitDialog(frame, "Install latest MekHQ Milestone");
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(confirmation,
                            "confirmNormalInstallButton")).doClick());

            JDialog failure = waitDialog(frame, "Copy retained — setup needs repair");
            JButton repair = find(failure, "repairNormalInstallButton");
            JButton close = find(failure, "operationCancelButton");
            assertTrue(repair.isEnabled());
            assertTrue(repair.isVisible());
            assertEquals("Close", close.getText());
            assertTrue(find(failure, "operationViewDetailsButton").isVisible());
            assertNull(find(failure, "operationViewLogsButton"));
            assertNull(find(failure, "copyOperationDetailsButton"));
            assertNoShowingDialog(frame, "Installing MekHQ 1.2.3 failed");
            assertNoShowingDialog(frame, "Install complete");

            SwingUtilities.invokeAndWait(repair::doClick);
            assertNotNull(waitButton(frame, "manageAddExistingButton"));
            assertFalse(failure.isDisplayable());
        } finally {
            dispose(frame);
        }
    }

    @Test
    void installationCardsDoNotSilentlyChangePreferencesAndBlockersStayActionable()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("multi.json"));
        services.installed = true;
        services.pending = true;
        services.main = services.firstWithoutJava;
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            assertNotNull(waitButton(frame, "resolveHomeBlockerButton"));
            JButton installations = waitButton(frame, "manageInstallationsButton");
            SwingUtilities.invokeAndWait(installations::doClick);
            assertNotNull(waitButton(frame,
                    "installationMenuButton-" + services.firstWithoutJava.id()));
            assertNotNull(waitButton(frame,
                    "installationMenuButton-" + services.second.id()));
            assertNull(find(frame, "makePreferredButton"),
                    "cards never turn selection into an implicit preference mutation");
            assertEquals(services.firstWithoutJava.id(), services.main.id());
            assertEquals(2, services.records.size(), "rendering cards must not move records");
        } finally {
            dispose(frame);
        }
    }

    @Test
    void homeUsesUnionOfProductsEvenWhenOldDefaultContainsOnlyMegaMek() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("megamek-only.json"));
        Product megamek = services.first.products().stream()
                .filter(product -> product.key().equals("megamek")).findFirst().orElseThrow();
        services.installed = true;
        services.main = new InstallationRecord(services.first.id(), "MegaMek Main",
                services.first.canonicalRoot(), services.first.observedBuild(),
                List.of(megamek), services.first.javaExecutable(), services.first.pin(),
                services.first.updateEligible(), services.first.registeredAt());
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            assertEquals("Launch MegaMek 1.2.3",
                    waitButton(frame, "launch-megamek-button").getText());
            assertEquals("Launch MekHQ 1.2.3",
                    waitButton(frame, "launch-mekhq-button").getText());
            assertEquals("Launch MegaMekLab 1.2.3",
                    waitButton(frame, "launch-lab-button").getText());
            HomeLaunchSplitButton split = find(frame, "launch-megamek-split-button");
            assertTrue(split.hasOptions(),
                    "matching alternate copies remain one-off launch choices");
        } finally {
            dispose(frame);
        }
    }

    private static final class FakeServices extends LauncherServices {
        private final Path registry;
        private final Path destination;
        private final ReleaseCatalog.Asset asset;
        private final ReleaseCatalog.Release release;
        private final InstallationRecord first;
        private final InstallationRecord firstWithoutJava;
        private final InstallationRecord second;
        private final List<InstallationRecord> records;
        private final AtomicInteger plans = new AtomicInteger();
        private final AtomicInteger installs = new AtomicInteger();
        private final AtomicInteger releaseFetches = new AtomicInteger();
        private final AtomicInteger catalogRequests = new AtomicInteger();
        private final AtomicInteger selections = new AtomicInteger();
        private final List<QuickInstallOption.Key> plannedChoices =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<Path> plannedDestinations =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final QuickInstallSnapshot quickInstallSnapshot =
                QuickInstallTestData.snapshot("1.2.3", "1.2.4");
        private volatile boolean installed;
        private volatile boolean pending;
        private volatile boolean launched;
        private volatile InstallationRecord previewedRecord;
        private volatile String previewedProduct;
        private volatile InstallationRecord main;
        private volatile OfficialRepository lastPlannedRepository;
        private volatile FollowChannel lastPlannedChannel;
        private volatile FollowChannel installedChannel;
        private volatile CountDownLatch planStarted;
        private volatile CountDownLatch releasePlan;
        private volatile CountDownLatch installStarted;
        private volatile CountDownLatch releaseInstall;
        private volatile boolean checkOnOpen;
        private volatile boolean installBecameMain = true;
        private volatile IOException installFailure;

        private FakeServices(Path registry) {
            super(registry);
            this.registry = registry.toAbsolutePath().normalize();
            destination = registry.getParent().resolve("installations").resolve("Main");
            asset = new ReleaseCatalog.Asset("MekHQ-v1.2.3.tar.gz", 1234,
                    "sha256:" + "a".repeat(64),
                    URI.create("https://github.com/MegaMek/mekhq/releases/download/"
                            + "v1.2.3/MekHQ-v1.2.3.tar.gz"));
            release = new ReleaseCatalog.Release("v1.2.3", "Milestone 1.2.3",
                    false, false,
                    URI.create("https://github.com/MegaMek/mekhq/releases/tag/v1.2.3"),
                    List.of(asset));
            List<Product> products = List.of(
                    new Product("megamek", "MegaMek.jar", "megamek.MegaMek",
                            "1.2.3", List.of()),
                    new Product("mekhq", "MekHQ.jar", "mekhq.MekHQ",
                            "1.2.3", List.of()),
                    new Product("lab", "MegaMekLab.jar", "megameklab.MegaMekLab",
                            "1.2.3", List.of()));
            first = record("1", "Main", destination, products,
                    Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
            firstWithoutJava = new InstallationRecord(first.id(), first.name(),
                    first.canonicalRoot(), first.observedBuild(), first.products(), null,
                    first.pin(), false, first.registeredAt());
            second = record("2", "Other copy", registry.getParent().resolve("Other"),
                    products, first.javaExecutable());
            records = List.of(firstWithoutJava, second);
            main = first;
        }

        @Override public QuickInstallSnapshot quickInstallSnapshot() {
            catalogRequests.incrementAndGet();
            return quickInstallSnapshot;
        }

        @Override
        public Path normalInstallDestination(OfficialRepository repository,
                                             FollowChannel channel) {
            return destination;
        }

        @Override
        public NormalInstallService.Plan prepareNormalInstall(OfficialRepository repository,
                                                              FollowChannel channel, Path target,
                                                              Path java)
                throws InterruptedException {
            plans.incrementAndGet();
            lastPlannedRepository = repository;
            lastPlannedChannel = channel;
            plannedDestinations.add(target.toAbsolutePath().normalize());
            QuickInstallOption.Key key = new QuickInstallOption.Key(repository, channel);
            plannedChoices.add(key);
            CountDownLatch started = planStarted;
            CountDownLatch releasePlanning = releasePlan;
            if (started != null) started.countDown();
            if (releasePlanning != null) releasePlanning.await();
            RegistryData empty = new RegistryData(RegistryStore.SCHEMA, null, List.of());
            QuickInstallOption option = quickInstallSnapshot.option(key);
            java.util.Set<String> expectedProducts = switch (repository) {
                case MEKHQ -> NormalInstallService.SUITE_PRODUCTS;
                case MEGAMEK -> java.util.Set.of("megamek");
                case LAB -> java.util.Set.of("lab");
            };
            return new NormalInstallService.Plan(
                    registry, new NormalInstallService.RegistrySnapshot(false, empty), target,
                    List.of(), Path.of(System.getProperty("java.home"), "bin", "java.exe"),
                    21, channel, repository, option.version(), expectedProducts,
                    option.release(), option.asset(),
                    "https://raw.githubusercontent.com/MegaMek/megamek.github.io/main/"
                            + "_data/current_releases.yml",
                    new NormalInstallService.CheckConfiguration(checkOnOpen,
                            "fixture:" + checkOnOpen));
        }

        @Override
        public NormalInstallService.Result installNormal(NormalInstallService.Plan plan,
                                                         PrintStream progress,
                                                         OperationContext context)
                throws IOException, InterruptedException {
            context.phase(OperationPhase.METADATA, "fixture metadata");
            context.phase(OperationPhase.DOWNLOAD, "fixture download");
            CountDownLatch started = installStarted;
            CountDownLatch releaseGate = releaseInstall;
            if (started != null) started.countDown();
            if (releaseGate != null) releaseGate.await();
            if (installFailure != null) throw installFailure;
            installs.incrementAndGet();
            installed = true;
            installedChannel = plan.channel();
            InstallationRecord installedRecord = new InstallationRecord(
                    first.id(), first.name(), first.canonicalRoot(), plan.version(),
                    first.products(), first.javaExecutable(), first.pin(),
                    first.updateEligible(), first.registeredAt());
            main = installBecameMain ? installedRecord : second;
            return new NormalInstallService.Result(installedRecord, release, asset,
                    destination, null, installBecameMain);
        }

        @Override
        public HomeState loadHome() {
            if (!installed) {
                return new HomeState(new RegistryData(RegistryStore.SCHEMA, null, List.of()),
                        null, null, null, null, false, null);
            }
            InstallationRecord current = main;
            Inspection inspection = new Inspection(current.canonicalRoot(), current.products(),
                    current.observedBuild(), "fixture");
            ChannelPreference preference = new ChannelPreference(1, current.id(),
                    current.canonicalRoot(), current.registeredAt(),
                    installedChannel == null ? FollowChannel.MILESTONE : installedChannel, true);
            return new HomeState(new RegistryData(
                    RegistryStore.SCHEMA, current.id(), records), current,
                    inspection, null,
                    new UpdatePreviewService.Eligibility(current, false,
                            "fixture imported copy", null, null),
                    pending && current.id().equals(first.id()),
                    new ChannelPreferenceStore.ReadResult(
                            ChannelPreferenceStore.Status.CONFIGURED,
                            preference, "Configured"));
        }

        @Override
        public void select(InstallationRecord selected) {
            selections.incrementAndGet();
            main = selected;
        }

        @Override
        public ReleaseCatalog.Page releases(OfficialRepository repository, int page) {
            releaseFetches.incrementAndGet();
            return new ReleaseCatalog.Page(page, 10, List.of(), false);
        }

        @Override
        public List<String> preview(InstallationRecord record, String product) {
            previewedRecord = record;
            previewedProduct = product;
            return List.of("fixture-java", product);
        }

        @Override
        public int launch(InstallationRecord record, String product) {
            launched = true;
            return 0;
        }

        private static InstallationRecord record(String suffix, String name, Path root,
                                                 List<Product> products, String java) {
            return new InstallationRecord("00000000-0000-0000-0000-00000000000" + suffix,
                    name, root.toAbsolutePath().normalize().toString(), "1.2.3", products,
                    java, null, false, "2026-09-17T00:00:0" + suffix + "Z");
        }
    }

    private static String display(OfficialRepository repository) {
        return switch (repository) {
            case MEKHQ -> "MekHQ";
            case MEGAMEK -> "MegaMek";
            case LAB -> "MegaMekLab";
        };
    }

    private static void click(JDialog dialog, String text) throws Exception {
        JButton button = findByText(dialog, text);
        assertNotNull(button);
        SwingUtilities.invokeLater(button::doClick);
        waitUntil(() -> !dialog.isDisplayable());
    }

    private static void assertNoShowingDialog(LauncherFrame frame, String title)
            throws Exception {
        boolean[] showing = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window window : frame.getOwnedWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()
                        && title.equals(dialog.getTitle())) {
                    showing[0] = true;
                }
            }
        });
        assertFalse(showing[0], title + " must not be shown");
    }

    private static void assertNoDisplayableProgress(LauncherFrame frame) throws Exception {
        boolean[] displayable = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window window : frame.getOwnedWindows()) {
                if (window instanceof OperationProgressDialog && window.isDisplayable()) {
                    displayable[0] = true;
                }
            }
        });
        assertFalse(displayable[0], "successful progress must be disposed before Home renders");
    }

    private static void assertNormalInstallQuote(
            JDialog dialog, OfficialRepository repository, FollowChannel channel,
            String version, ReleaseCatalog.Asset asset, Path destination) throws Exception {
        String product = display(repository);
        assertEquals("Install latest " + product + " " + channel, dialog.getTitle());
        assertEquals("Install latest " + product + " " + channel,
                ((JLabel) find(dialog, "normalInstallHeading")).getText());
        assertEquals("Version " + version,
                ((JLabel) find(dialog, "normalInstallVersion")).getText());
        assertEquals(expectedIncludes(repository),
                ((JLabel) find(dialog, "normalInstallIncludes")).getText());
        assertEquals("Download: "
                        + NormalInstallConfirmationDialog.formatBinaryBytes(asset.size()),
                ((JLabel) find(dialog, "normalInstallDownloadSize")).getText());

        JTextArea location = find(dialog, "normalInstallDestination");
        String exactDestination = destination.toAbsolutePath().normalize().toString();
        assertEquals(exactDestination, location.getText());
        assertEquals(exactDestination, location.getToolTipText());
        assertFalse(location.isEditable());
        assertTrue(location.isFocusable(), "the destination remains selectable/copyable");
        location.selectAll();
        assertEquals(exactDestination, location.getSelectedText());
        location.setCaretPosition(0);
        assertTrue(location.isVisible());

        JPanel summary = find(dialog, "normalInstallSummary");
        String summaryText = componentText(summary);
        assertFalse(summaryText.contains("https://"),
                "raw repository and asset URLs stay out of the normal summary");
        assertFalse(summaryText.contains("Repository:"));
        assertFalse(summaryText.contains("sha256:"),
                "the digest stays out of the normal summary");
        assertFalse(summaryText.contains("SHA-256:"));
        assertFalse(summaryText.contains(asset.name()),
                "the raw asset name stays out of the normal summary");
        assertEquals(NormalInstallConfirmationDialog.PANEL, summary.getBackground());
        assertEquals(NormalInstallConfirmationDialog.BACKGROUND,
                ((JPanel) find(dialog, "normalInstallConfirmationContent")).getBackground());

        for (String removed : List.of("normalInstallConfirmationDetails",
                "normalInstallTechnicalToggle", "normalInstallTechnicalDetailsPane",
                "normalInstallUpdateBehavior", "changeNormalJavaButton")) {
            assertNull(find(dialog, removed), removed + " is absent from the simple quote");
        }
        for (String name : List.of("changeNormalLocationButton", "cancelNormalInstallButton",
                "confirmNormalInstallButton")) {
            JButton action = find(dialog, name);
            assertTrue(action instanceof FirstLaunchButton,
                    name + " uses the shared first-launch button painting");
            assertNotNull(action.getAccessibleContext().getAccessibleName());
            assertNotNull(action.getAccessibleContext().getAccessibleDescription());
            int textWidth = action.getFontMetrics(action.getFont())
                    .stringWidth(action.getText()) + action.getInsets().left
                    + action.getInsets().right;
            assertTrue(textWidth <= action.getWidth(), name + " text is not clipped");
        }
        assertEquals(find(dialog, "confirmNormalInstallButton"),
                dialog.getRootPane().getDefaultButton());
        assertTrue(dialog.getWidth() <= 680,
                "logical width is not multiplied by the monitor transform");
        Rectangle usable = GuiScale.usableBounds(dialog.getGraphicsConfiguration());
        assertTrue(usable.contains(dialog.getBounds()),
                "the quote is clamped to the monitor's logical work area");
        assertComponentsWithin(dialog, List.of(
                "normalInstallSummary", "normalInstallDestination",
                "changeNormalLocationButton",
                "cancelNormalInstallButton", "confirmNormalInstallButton"));
    }

    private static String expectedIncludes(OfficialRepository repository) {
        return switch (repository) {
            case MEKHQ -> "Includes: MegaMek, MekHQ, MegaMekLab";
            case MEGAMEK -> "Includes: MegaMek";
            case LAB -> "Includes: MegaMekLab";
        };
    }

    private static String componentText(Container root) {
        StringBuilder text = new StringBuilder();
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label) text.append(label.getText()).append('\n');
            if (child instanceof JTextArea area) text.append(area.getText()).append('\n');
            if (child instanceof Container nested) text.append(componentText(nested));
        }
        return text.toString();
    }

    private static void assertComponentsWithin(JDialog dialog, List<String> names)
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            dialog.validate();
            Rectangle visible = new Rectangle(dialog.getContentPane().getSize());
            for (String name : names) {
                Component component = find(dialog, name);
                assertNotNull(component, name);
                Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(),
                        component.getBounds(), dialog.getContentPane());
                assertTrue(bounds.width > 0 && bounds.height > 0, name + " has usable bounds");
                assertTrue(visible.contains(bounds), name + " remains visible without clipping");
            }
        });
    }

    private static JDialog waitDialog(LauncherFrame frame, String title) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            JDialog[] found = new JDialog[1];
            SwingUtilities.invokeAndWait(() -> {
                for (java.awt.Window window : java.awt.Window.getWindows()) {
                    if (window instanceof JDialog dialog && dialog.isShowing()
                            && title.equals(dialog.getTitle())) {
                        found[0] = dialog;
                    }
                }
            });
            if (found[0] != null) return found[0];
            Thread.sleep(20);
        }
        List<String> titles = java.util.Arrays.stream(java.awt.Window.getWindows())
                .filter(JDialog.class::isInstance).map(JDialog.class::cast)
                .filter(JDialog::isShowing).map(JDialog::getTitle).toList();
        throw new AssertionError("timed out waiting for " + title + "; showing=" + titles);
    }

    private static JButton waitButton(Container root, String name) throws Exception {
        return waitFor(() -> find(root, name));
    }

    private static <T> T waitFor(java.util.function.Supplier<T> probe) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            Object[] value = new Object[1];
            SwingUtilities.invokeAndWait(() -> value[0] = probe.get());
            if (value[0] != null) {
                @SuppressWarnings("unchecked") T cast = (T) value[0];
                return cast;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for Swing component");
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            boolean[] result = new boolean[1];
            SwingUtilities.invokeAndWait(() -> result[0] = condition.getAsBoolean());
            if (result[0]) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for Swing state");
    }

    private static void invokeKeyBinding(JButton button, KeyStroke stroke) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Object key = button.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
            assertNotNull(key);
            assertNotNull(button.getActionMap().get(key));
            button.getActionMap().get(key).actionPerformed(
                    new ActionEvent(button, ActionEvent.ACTION_PERFORMED, key.toString()));
        });
    }

    private static void invokePopupEscape(JPopupMenu popup) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
            Object key = popup.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape);
            assertNotNull(key);
            assertNotNull(popup.getActionMap().get(key));
            popup.getActionMap().get(key).actionPerformed(
                    new ActionEvent(popup, ActionEvent.ACTION_PERFORMED, key.toString()));
        });
    }

    private static void invokeDialogEscape(JDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
            Object key = dialog.getRootPane()
                    .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape);
            assertNotNull(key);
            assertNotNull(dialog.getRootPane().getActionMap().get(key));
            dialog.getRootPane().getActionMap().get(key).actionPerformed(
                    new ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, key.toString()));
        });
    }

    private static void invokeMenuSelection(JMenuItem item) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.Action action = item.getActionMap().get("doClick");
            assertNotNull(action);
            action.actionPerformed(new ActionEvent(
                    item, ActionEvent.ACTION_PERFORMED, "keyboard-select"));
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return (T) child;
            if (child instanceof Container nested) {
                T found = find(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JButton findByText(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) {
                JButton found = findByText(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> work) throws Exception {
        Object[] value = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                value[0] = work.call();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        @SuppressWarnings("unchecked") T cast = (T) value[0];
        return cast;
    }

    private static void dispose(LauncherFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window window : frame.getOwnedWindows()) window.dispose();
            frame.dispose();
        });
    }
}
