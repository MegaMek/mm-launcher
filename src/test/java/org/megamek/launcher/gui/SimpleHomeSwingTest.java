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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.QuickInstallOption;
import org.megamek.launcher.channel.QuickInstallSnapshot;
import org.megamek.launcher.channel.SuiteTestData;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.NormalInstallService;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.update.UpdatePreviewService;
import org.megamek.launcher.update.ImportedCopyAdoptionService;
import org.megamek.launcher.update.CurrentUpdateState;
import org.megamek.launcher.update.RealUpdateService;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JPopupMenu;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    @org.junit.jupiter.api.Tag("native-gui")
    void firstLaunchMetadataLoadingIsNonBlockingDisabledAndSessionDeduplicated()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("loading-metadata.json"));
        services.snapshotStarted = new CountDownLatch(1);
        services.releaseSnapshot = new CountDownLatch(1);
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            assertTrue(services.snapshotStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            JButton primary = waitButton(frame, "downloadAndInstallButton");
            JButton options = waitButton(frame, "downloadOptionsButton");
            JButton existing = waitButton(frame, "useExistingCopyButton");
            assertFalse(primary.isEnabled());
            assertFalse(primary.isFocusable());
            assertFalse(options.isEnabled());
            assertFalse(options.isFocusable());
            assertTrue(existing.isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                primary.doClick();
                options.doClick();
                frame.showWindow();
            });
            invokeKeyBinding(primary, KeyStroke.getKeyStroke(
                    KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK));
            waitButton(frame, "downloadAndInstallButton");
            assertEquals(1, services.catalogRequests.get());
            assertEquals(0, services.plans.get());
            FirstLaunchSplitButton loadingSplit =
                    find(frame, "firstLaunchSplitButton");
            assertFalse(loadingSplit.popupMenu().isVisible());

            services.releaseSnapshot.countDown();
            JButton ready = waitFor(() -> {
                JButton button = find(frame, "downloadAndInstallButton");
                return button != null && button.isEnabled()
                        && button.getText().endsWith("(1.2.3)") ? button : null;
            });
            assertTrue(ready.isFocusable());
            assertEquals(1, services.catalogRequests.get());
            SwingUtilities.invokeAndWait(frame::showWindow);
            waitFor(() -> {
                JButton button = find(frame, "downloadAndInstallButton");
                return button != null && button.isEnabled()
                        && button.getText().endsWith("(1.2.3)") ? button : null;
            });
            assertEquals(1, services.catalogRequests.get(),
                    "READY is rebound without another snapshot");
        } finally {
            services.releaseSnapshot.countDown();
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void failedMetadataRequiresOneExplicitDeduplicatedRetry() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("retry-metadata.json"));
        services.snapshotFailures.set(1);
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton primary = waitFor(() -> {
                JButton button = find(frame, "downloadAndInstallButton");
                return button != null && button.getText().endsWith("(Unavailable)")
                        ? button : null;
            });
            JButton options = waitButton(frame, "downloadOptionsButton");
            assertFalse(primary.isEnabled());
            assertTrue(options.isEnabled());
            assertEquals(1, services.catalogRequests.get());

            FirstLaunchSplitButton split = waitFor(
                    () -> find(frame, "firstLaunchSplitButton"));
            SwingUtilities.invokeAndWait(options::doClick);
            waitUntil(() -> split.popupMenu().isVisible());
            JMenuItem retry = java.util.Arrays.stream(split.popupMenu().getComponents())
                    .filter(JMenuItem.class::isInstance)
                    .map(JMenuItem.class::cast)
                    .filter(item -> "retryQuickInstallMetadataMenuItem".equals(item.getName()))
                    .findFirst().orElseThrow();
            SwingUtilities.invokeAndWait(() -> {
                retry.doClick();
                retry.doClick();
            });

            waitUntil(() -> {
                JButton button = find(frame, "downloadAndInstallButton");
                return button != null && button.isEnabled()
                        && button.getText().endsWith("(1.2.3)");
            });
            assertEquals(2, services.catalogRequests.get());
            assertEquals(0, services.plans.get());
        } finally {
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
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
            waitUntil(() -> {
                JButton button = find(frame, "downloadAndInstallButton");
                return button != null
                        && button.getText().endsWith("(1.2.3)");
            });
            assertEquals("Install latest MekHQ Milestone (1.2.3)", primary.getText());
            assertEquals(QuickInstallSnapshot.DEFAULT_KEY,
                    new QuickInstallOption.Key(OfficialRepository.MEKHQ, FollowChannel.MILESTONE));
            assertEquals(List.of(
                            new QuickInstallOption.Key(OfficialRepository.MEGAMEK, FollowChannel.MILESTONE),
                            new QuickInstallOption.Key(OfficialRepository.LAB, FollowChannel.MILESTONE)),
                    QuickInstallSnapshot.MENU_KEYS.subList(0, 2));
            int catalogRequests = services.catalogRequests.get();

            invokeKeyBinding(primary, KeyStroke.getKeyStroke(
                    KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK));
            waitUntil(() -> split.popupMenu().isVisible());
            JPopupMenu popup = split.popupMenu();
            waitUntil(() -> popup.getComponent(0).isEnabled());
            assertEquals(8, popup.getComponentCount());
            List<String> menuLabels = java.util.Arrays.stream(popup.getComponents())
                    .map(JMenuItem.class::cast).map(JMenuItem::getText).toList();
            assertEquals(List.of(
                            "Install latest MegaMek Milestone (1.2.3)",
                            "Install latest MegaMekLab Milestone (1.2.3)",
                            "Install latest MekHQ Development (1.2.4)",
                            "Install latest MegaMek Development (1.2.4)",
                            "Install latest MegaMekLab Development (1.2.4)",
                            "Install latest MekHQ Weekly (1.2.4)",
                            "Install latest MegaMek Weekly (1.2.4)",
                            "Install latest MegaMekLab Weekly (1.2.4)"),
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
                JDialog alternate = waitDialog(frame, VersionDisplay.installLatest(
                        display(key.repository()), key.channel().toString(), version));
                assertEquals("", ((JLabel) find(frame, "homeStatusLabel")).getText(),
                        "a visible quote must not retain a checking status");
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
            assertEquals(catalogRequests, services.catalogRequests.get(),
                    "quote cancellation and reopening reuse the one session snapshot");
            assertEquals("", ((JLabel) find(frame, "homeStatusLabel")).getText());
            waitUntil(() -> {
                JButton button = find(frame, "downloadAndInstallButton");
                return button != null && button.getText().endsWith("(1.2.3)");
            });
            assertEquals(0, services.releaseFetches.get());
            assertFalse(Files.exists(temp.resolve("registry.json")));

            services.planStarted = new CountDownLatch(1);
            services.releasePlan = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(primary::doClick);
            assertTrue(services.planStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(onEdt(primary::isEnabled));
            assertFalse(onEdt(options::isEnabled));
            services.releasePlan.countDown();
            JDialog confirmation =
                    waitDialog(frame, "Install latest MekHQ Milestone (1.2.3)");
            assertEquals(FollowChannel.MILESTONE, services.lastPlannedChannel);
            assertEquals(OfficialRepository.MEKHQ, services.lastPlannedRepository);
            assertEquals(9, services.plans.get());
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
            JDialog progress =
                    waitDialog(frame, "Installing MekHQ Milestone (1.2.3)");
            assertEquals("", ((JLabel) find(frame, "homeStatusLabel")).getText(),
                    "the progress dialog is the only active-operation status surface");
            assertEquals("Cancel",
                    ((JButton) find(progress, "operationCancelButton")).getText());
            assertTrue(find(progress, "operationCancelButton").isVisible());
            assertFalse(find(progress, "repairNormalInstallButton").isVisible());
            assertFalse(find(progress, "operationViewDetailsButton").isVisible());
            assertNull(find(progress, "operationViewLogsButton"));
            assertNull(find(progress, "copyOperationDetailsButton"));
            assertNull(find(progress, "normalInstallProgressLog"));
            saveReviewImage("install-progress.png", progress);
            services.releaseInstall.countDown();
            assertNotNull(waitButton(frame, "launch-megamek-button"));
            assertNull(find(frame, "installationReadyNotice"));
            assertNoShowingDialog(frame, "Install complete");
            assertNoDisplayableProgress(frame);
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
            List<HomeLaunchSplitButton> launchControls =
                    List.of(megamekSplit, mekhqSplit, labSplit);
            List<String> productNames = List.of("MegaMek", "MekHQ", "MegaMekLab");
            for (int index = 0; index < launchControls.size(); index++) {
                assertEquals(List.of("Other copy · " + productNames.get(index)
                                + " (1.2.3)"),
                        java.util.Arrays.stream(launchControls.get(index)
                                        .popupMenu().getComponents())
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
            assertNull(find(frame, "installationUpdateSummary"));
            SwingUtilities.invokeAndWait(() -> {
                frame.setSize(1180, 760);
                frame.validate();
            });
            saveReviewImage("managed-home.png", frame);

            int selections = services.selections.get();
            SwingUtilities.invokeAndWait(megamekSplit.optionsButton()::doClick);
            waitUntil(() -> megamekSplit.popupMenu().isVisible());
            invokeMenuSelection((JMenuItem) megamekSplit.popupMenu().getComponent(0));
            waitUntil(() -> services.launched);
            assertEquals(services.second, services.launchedRecord);
            assertEquals("megamek", services.launchedProduct);
            assertNull(services.previewedRecord,
                    "direct alternate launch does not build a visible command preview");
            assertEquals(selections, services.selections.get(),
                    "alternate launch performs no preference mutation");
            assertEquals(services.first.id(), services.main.id());
            assertTrue((onEdt(frame::getExtendedState) & javax.swing.JFrame.ICONIFIED) != 0);
            assertNoShowingDialog(frame, "Confirm launch");
            assertNoShowingDialog(frame, "Game finished");
            JButton launchedButton = megamekSplit.primaryButton();
            waitUntil(() -> services.launchAttempts.get() == 1
                    && find(frame, "launch-megamek-button") != launchedButton
                    && find(frame, "launch-megamek-button") != null
                    && ((JButton) find(frame, "launch-megamek-button")).isEnabled());
            SwingUtilities.invokeAndWait(() ->
                    frame.setExtendedState(javax.swing.JFrame.NORMAL));

            assertEquals(1, services.installs.get());
            assertEquals(FollowChannel.MILESTONE, services.installedChannel);
            assertTrue(services.launched);

            JButton installations = waitButton(frame, "manageInstallationsButton");
            SwingUtilities.invokeAndWait(installations::doClick);
            assertNotNull(waitButton(frame, "manageAddExistingButton"));
            JButton exactPicker = waitButton(frame, "installAnotherVersionButton");
            assertNotNull(exactPicker);
            SwingUtilities.invokeAndWait(exactPicker::doClick);
            JDialog picker = waitDialog(frame, "Download an official release");
            assertEquals(0, services.releaseFetches.get(),
                    "the deferred picker waits for an explicit fetch or browse action");
            saveReviewImage("release-picker.png", picker);
            SwingUtilities.invokeAndWait(picker::dispose);
            assertNotNull(find(frame, "installationCardsScrollPane"));
            saveReviewImage("installations.png", frame);
            JPanel cards = find(frame, "installationCards");
            assertEquals(FirstLaunchPanel.BACKGROUND, cards.getBackground());
            JPanel firstCard = find(frame, "installationCard-" + services.first.id());
            assertEquals(FirstLaunchPanel.PANEL, firstCard.getBackground());
            assertNotNull(firstCard.getAccessibleContext().getAccessibleName());
            JCheckBox automatic = find(frame,
                    "checkOnOpenCheckbox-" + services.first.id());
            assertNotNull(automatic);
            assertTrue(automatic.isSelected());
            assertNotNull(automatic.getAccessibleContext().getAccessibleName());
            assertNotNull(automatic.getAccessibleContext().getAccessibleDescription());
            assertEquals(KeyEvent.VK_C, automatic.getMnemonic());
            JButton firstMenu = waitButton(frame,
                    "installationMenuButton-" + services.first.id());
            assertNotNull(firstMenu);
            JButton manualCheck = waitButton(frame, "checkUpdatesButton");
            SwingUtilities.invokeAndWait(() -> {
                frame.setSize(680, 470);
                frame.validate();
            });
            Rectangle automaticBounds = SwingUtilities.convertRectangle(
                    automatic.getParent(), automatic.getBounds(), firstCard);
            Rectangle menuBounds = SwingUtilities.convertRectangle(
                    firstMenu.getParent(), firstMenu.getBounds(), firstCard);
            Rectangle manualBounds = SwingUtilities.convertRectangle(
                    manualCheck.getParent(), manualCheck.getBounds(), firstCard);
            assertFalse(automaticBounds.intersects(menuBounds));
            assertFalse(automaticBounds.intersects(manualBounds));
            assertNotNull(waitButton(frame,
                    "installationMenuButton-" + services.second.id()));
            assertNull(find(frame, "checkOnOpenCheckbox-" + services.second.id()),
                    "managed-incomplete cards must not expose automatic checks");
            JPopupMenu cardMenu = SwingTestSupport.installationMenu(frame, services.first.id());
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
            saveReviewImage("settings.png", frame);
            JButton home = waitButton(frame, "homeButton");
            waitUntil(home::isEnabled);
            SwingUtilities.invokeAndWait(home::doClick);
            assertNull(find(frame, "installationReadyNotice"));
        } finally {
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void browsedChangeLocationRetainsCapturedRecordWhenANewerRecordReusesTheTag() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("browsed-location.json"));
        services.capturedTargetKind = NormalInstallService.TargetKind.BROWSED_EXACT;
        Path changed = temp.resolve("browsed-new-location").toAbsolutePath().normalize();
        String capturedSource = services.quickInstallSnapshot.option(QuickInstallSnapshot.DEFAULT_KEY)
                .target().source();
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services, (parent, plan) -> changed));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton primary = waitButton(frame, "downloadAndInstallButton");
            waitUntil(primary::isEnabled);
            SwingUtilities.invokeAndWait(primary::doClick);
            JDialog firstQuote = waitDialog(frame, "Install MekHQ Milestone (1.2.3)");
            services.currentBrowsedSource = SuiteTestData.recordUri("1.02.04").toString();
            assertFalse(capturedSource.equals(services.currentBrowsedSource));
            JButton change = find(firstQuote, "changeNormalLocationButton");
            SwingUtilities.invokeAndWait(change::doClick);
            waitUntil(() -> !firstQuote.isDisplayable());
            JDialog changedQuote = waitDialog(frame, "Install MekHQ Milestone (1.2.3)");
            assertEquals(List.of(capturedSource, capturedSource), services.plannedSources);
            assertEquals(List.of(services.destination, changed), services.plannedDestinations);
            assertEquals(0, services.installs.get());
            JButton cancel = find(changedQuote, "cancelNormalInstallButton");
            SwingUtilities.invokeAndWait(cancel::doClick);
        } finally {
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
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
            waitUntil(primary::isEnabled);
            SwingUtilities.invokeAndWait(primary::doClick);
            JDialog firstQuote =
                    waitDialog(frame, "Install latest MekHQ Milestone (1.2.3)");
            int catalogBeforeChange = services.catalogRequests.get();
            assertEquals(services.destination.toString(),
                    ((JTextArea) find(firstQuote, "normalInstallDestination")).getText());

            JButton change = find(firstQuote, "changeNormalLocationButton");
            SwingUtilities.invokeAndWait(() -> {
                change.doClick();
                change.doClick();
            });
            waitUntil(() -> !firstQuote.isDisplayable());
            JDialog changedQuote =
                    waitDialog(frame, "Install latest MekHQ Milestone (1.2.3)");
            assertEquals("", ((JLabel) find(frame, "homeStatusLabel")).getText());
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
            assertEquals(catalogBeforeChange, services.catalogRequests.get(),
                    "cancelling the changed quote retains the session snapshot");
            assertEquals("", ((JLabel) find(frame, "homeStatusLabel")).getText());
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
    @org.junit.jupiter.api.Tag("native-gui")
    void latestMekHQDevelopmentCompletesThroughSharedInstallerAndShowsDevelopment()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("development.json"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton options = waitButton(frame, "downloadOptionsButton");
            FirstLaunchSplitButton split = waitFor(
                    () -> find(frame, "firstLaunchSplitButton"));
            waitUntil(options::isEnabled);
            SwingUtilities.invokeAndWait(options::doClick);
            waitUntil(() -> split.popupMenu().isVisible());
            waitUntil(() -> split.popupMenu().getComponent(2).isEnabled());
            invokeMenuSelection((JMenuItem) split.popupMenu().getComponent(2));

            JDialog confirmation =
                    waitDialog(frame, "Install latest MekHQ Development (1.2.4)");
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
            assertNotNull(waitButton(frame, "launch-mekhq-button"));
            assertNull(find(frame, "installationReadyNotice"));
            assertNoShowingDialog(frame, "Install complete");
            assertNoDisplayableProgress(frame);

            assertEquals("Launch MekHQ Development (1.2.4)",
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
    @org.junit.jupiter.api.Tag("native-gui")
    void concurrentMainIsPreservedAndInstallNoticePointsToInstallations() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("concurrent-main.json"));
        services.installBecameMain = false;
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton primary = waitButton(frame, "downloadAndInstallButton");
            waitUntil(primary::isEnabled);
            SwingUtilities.invokeAndWait(primary::doClick);
            JDialog confirmation =
                    waitDialog(frame, "Install latest MekHQ Milestone (1.2.3)");
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(confirmation,
                            "confirmNormalInstallButton")).doClick());

            assertNotNull(waitButton(frame, "launch-megamek-button"));
            assertNull(find(frame, "installationReadyNotice"));
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
    @org.junit.jupiter.api.Tag("native-gui")
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
            JButton download = waitButton(frame, "downloadAndInstallButton");
            waitUntil(download::isEnabled);
            SwingUtilities.invokeAndWait(download::doClick);
            JDialog confirmation =
                    waitDialog(frame, "Install latest MekHQ Milestone (1.2.3)");
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
            assertNoShowingDialog(frame, "Installing MekHQ Milestone (1.2.3) failed");
            assertNoShowingDialog(frame, "Install complete");

            SwingUtilities.invokeAndWait(repair::doClick);
            assertNotNull(waitButton(frame, "manageAddExistingButton"));
            assertFalse(failure.isDisplayable());
        } finally {
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
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
    @org.junit.jupiter.api.Tag("native-gui")
    void moreMenuPreferencesResetHonorsCancelAndReportsBackupOnConfirmation()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("reset-menu.json"));
        services.installed = true;
        services.main = services.first;
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            clickShowingButton(frame, "manageInstallationsButton");
            for (boolean confirm : List.of(false, true)) {
                JPopupMenu popup = SwingTestSupport.installationMenu(frame, services.first.id());
                JMenuItem reset = onEdt(() -> find(
                        popup, "resetPreferences-" + services.first.id()));
                invokeMenuSelection(reset);
                JDialog dialog = waitDialog(frame, "Reset preferences");
                JLabel consent = find(dialog, "launcherAlertMessage");
                String text = consent.getAccessibleContext().getAccessibleName();
                assertTrue(text.contains("Close MegaMek, MekHQ, and MegaMekLab"));
                assertTrue(text.contains("preferences for the programs in this installation"));
                assertTrue(text.contains("Your saves, campaigns, and custom files will stay"));
                assertTrue(text.contains("A backup of your current preferences will be kept"));
                assertTrue(text.contains("Its location will be shown after the reset"));
                assertFalse(text.contains(services.resetBackup.toString()));
                assertFalse(text.contains("mmconf/"));
                assertFalse(text.contains(services.first.name()));
                click(dialog, confirm ? "Reset preferences" : "Cancel");
                if (confirm) {
                    waitUntil(() -> services.resetCalls.get() == 1);
                    JDialog result = waitDialog(frame, "Preferences reset");
                    JLabel body = find(result, "launcherAlertMessage");
                    assertEquals("Your previous preferences were backed up here:\n"
                            + services.resetBackup,
                            body.getAccessibleContext().getAccessibleName());
                    click(result, "OK");
                } else {
                    assertEquals(0, services.resetCalls.get());
                    clickShowingButton(frame, "homeButton");
                    clickShowingButton(frame, "manageInstallationsButton");
                }
            }
            assertEquals(2, services.resetPlans.get());
        } finally {
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void repairMenuRequiresHealthyManagedCopyAndNoPendingRecovery() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeServices services = new FakeServices(temp.resolve("repair-menu.json"));
        services.installed = true;
        services.main = services.first;
        services.repairManaged = true;
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            clickShowingButton(frame, "manageInstallationsButton");
            JPopupMenu menu = SwingTestSupport.installationMenu(frame, services.first.id());
            assertNotNull(find(menu, "repairInstallation-" + services.first.id()));
            assertNull(find(menu, "repairInstallation-" + services.second.id()));
            SwingUtilities.invokeAndWait(() ->
                    javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath());
            dispose(frame);
            services.pending = true;
            LauncherFrame pendingFrame = onEdt(() -> new LauncherFrame(services));
            try {
                SwingUtilities.invokeAndWait(pendingFrame::showWindow);
                clickShowingButton(pendingFrame, "manageInstallationsButton");
                JPopupMenu pendingMenu = SwingTestSupport.installationMenu(pendingFrame, services.first.id());
                assertNull(find(pendingMenu, "repairInstallation-" + services.first.id()));
            } finally {
                dispose(pendingFrame);
            }

        } finally {
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void adoptedManagedCopyHasNoRepairMenuAction() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeServices services = new FakeServices(temp.resolve("adopted-repair-menu.json"));
        services.installed = true;
        services.main = services.first;
        services.repairManaged = true;
        services.repairAdopted = true;
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            clickShowingButton(frame, "manageInstallationsButton");
            JPopupMenu menu = SwingTestSupport.installationMenu(frame, services.first.id());
            assertNull(find(menu, "repairInstallation-" + services.first.id()));
            assertNotNull(find(menu, "installationMenuSeparator"));
        } finally {
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void repairRequiresConsentBeforeCallingFacade() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeServices services = new FakeServices(temp.resolve("repair-consent.json"));
        services.installed = true;
        services.main = services.first;
        services.repairManaged = true;
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            clickShowingButton(frame, "manageInstallationsButton");
            JPopupMenu repairMenu = SwingTestSupport.installationMenu(frame, services.first.id());
            JMenuItem repair = onEdt(() -> find(repairMenu, "repairInstallation-" + services.first.id()));
            invokeMenuSelection(repair);
            JDialog consent = waitDialog(frame, "Repair installation");
            JLabel message = find(consent, "launcherAlertMessage");
            String text = message.getAccessibleContext().getAccessibleName();
            assertTrue(text.contains("Download size: "));
            assertTrue(text.contains("Close all MegaMek, MekHQ, and MegaMekLab windows"));
            assertTrue(text.contains("Your saves and settings will stay"));
            assertTrue(text.contains("back them up first"));
            assertFalse(text.contains("won't keep a backup"));
            assertFalse(text.contains("Installation:"));
            assertFalse(text.contains("Folder:"));
            assertFalse(text.contains("v1.2.3"));
            assertFalse(text.contains("official managed"));
            assertFalse(text.contains("rollback"));
            assertFalse(text.contains("unowned"));
            assertEquals(0, services.repairs.get());
            click(consent, "Cancel");
            assertEquals(0, services.repairs.get());
            JPopupMenu retryMenu = SwingTestSupport.installationMenu(frame, services.first.id());
            JMenuItem retry = onEdt(() -> find(retryMenu, "repairInstallation-" + services.first.id()));
            invokeMenuSelection(retry);
            JDialog approved = waitDialog(frame, "Repair installation");
            click(approved, "Repair");
            waitUntil(() -> services.repairs.get() == 1);
            JDialog completed = waitDialog(frame, "Repair complete");
            JLabel summary = find(completed, "operationProgressDetail");
            assertEquals("Repair complete. Your installation is ready to use.",
                    summary.getText());
            assertEquals("Some added files may still affect the game. View logs for details.",
                    ((JLabel) find(completed, "operationLoggingWarning")).getText());
            JTextArea log = ((OperationProgressDialog) completed).logArea();
            assertTrue(log.getText().contains("lib/mod.jar"));
            click(completed, "Close");
        } finally {
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void repairFailureKeepsProgressAndRecoveryGuidanceVisible() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeServices services = new FakeServices(temp.resolve("repair-failure.json"));
        services.installed = true;
        services.main = services.first;
        services.repairManaged = true;
        services.repairFailure = new IOException("fixture repair failed");
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            clickShowingButton(frame, "manageInstallationsButton");
            JPopupMenu repairMenu = SwingTestSupport.installationMenu(frame, services.first.id());
            JMenuItem repair = onEdt(() -> find(repairMenu, "repairInstallation-" + services.first.id()));
            invokeMenuSelection(repair);
            click(waitDialog(frame, "Repair installation"), "Repair");
            JDialog failed = waitDialog(frame, "Repair failed — recovery may be required");
            JTextArea log = ((OperationProgressDialog) failed).logArea();
            assertTrue(log.getText().contains("fixture repair failed"));
            assertTrue(log.getText().contains("Recover interrupted update"));
            assertEquals(1, services.repairs.get());
        } finally {
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void repairProgressUsesDownloadOnlyPercentageAndPlainLanguageStages() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        LauncherFrame frame = onEdt(() -> new LauncherFrame(
                new FakeServices(temp.resolve("repair-stages.json"))));
        OperationProgressDialog dialog = onEdt(() -> new OperationProgressDialog(frame,
                "Repairing installation", "repairProgressLog", () -> {}));
        try {
            SwingUtilities.invokeAndWait(() -> {
                dialog.useRepairProgress();
                dialog.startNonCancellable(
                        "Please keep the launcher open until repair finishes.");
                dialog.setVisible(true);
            });
            OperationContext context = new OperationContext(
                    org.megamek.launcher.operation.OperationType.UPDATE_APPLY, dialog);
            context.enterFinalization("Repair cannot be cancelled");
            context.phase(OperationPhase.METADATA, "Technical metadata");
            assertRepairStage(dialog, "Downloading installation files", true);
            context.progress(OperationPhase.DOWNLOAD, 250, 1000, ProgressUnit.BYTES,
                    "Downloaded 250 of 1000 bytes");
            waitUntil(() -> "25%".equals(((JProgressBar) find(dialog,
                    "operationProgressBar")).getString()));
            assertEquals("Downloaded 250 B of 1 KB",
                    ((JLabel) find(dialog, "operationProgressDetail")).getText());
            assertRepairStage(dialog, "Downloading installation files", false);
            context.phase(OperationPhase.EXTRACT, "Technical extraction");
            assertRepairStage(dialog, "Preparing installation files", true);
            context.phase(OperationPhase.APPLY, "Technical transaction");
            assertRepairStage(dialog, "Repairing installation", true);
            context.cleanupPhase("Technical cleanup");
            assertRepairStage(dialog, "Finishing up", true);
            assertFalse(((JButton) find(dialog, "operationCancelButton")).isVisible());
            SwingUtilities.invokeAndWait(() -> dialog.showRepairComplete(
                    "Repair complete. No missing or changed installation files were found.",
                    null));
            assertEquals("Repair complete. No missing or changed installation files were found.",
                    ((JLabel) find(dialog, "operationProgressDetail")).getText());
        } finally {
            SwingUtilities.invokeAndWait(dialog::dispose);
            dispose(frame);
        }
    }

    private static void assertRepairStage(JDialog dialog, String title, boolean indeterminate)
            throws Exception {
        waitUntil(() -> title.equals(((JLabel) find(dialog, "operationPhaseLabel")).getText()));
        assertEquals(indeterminate,
                ((JProgressBar) find(dialog, "operationProgressBar")).isIndeterminate());
        if (indeterminate) {
            assertEquals("Please keep the launcher open until repair finishes.",
                    ((JLabel) find(dialog, "operationProgressDetail")).getText());
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void homeUsesUnionOfProductsEvenWhenOldDefaultContainsOnlyMegaMek() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("megamek-only.json"));
        Product megamek = services.first.products().stream()
                .filter(product -> product.key().equals("megamek")).findFirst().orElseThrow();
        services.installed = true;
        services.main = new InstallationRecord(services.first.id(), "MegaMek Main",
                services.first.canonicalRoot(), services.first.observedBuild(),
                List.of(megamek), services.first.pin(),
                services.first.updateEligible(), services.first.registeredAt());
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            assertEquals("Launch MegaMek (1.2.3)",
                    waitButton(frame, "launch-megamek-button").getText());
            assertEquals("Launch MekHQ (1.2.3)",
                    waitButton(frame, "launch-mekhq-button").getText());
            assertEquals("Launch MegaMekLab (1.2.3)",
                    waitButton(frame, "launch-lab-button").getText());
            HomeLaunchSplitButton split = find(frame, "launch-megamek-split-button");
            assertTrue(split.hasOptions(),
                    "matching alternate copies remain one-off launch choices");
        } finally {
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void managedHomeFitsAllActionsAtTheInitialAndConstrainedWindowSizes() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing layout requires a display");
        FakeServices services = new FakeServices(temp.resolve("home-window-size.json"));
        services.installed = true;
        services.main = services.first;
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            waitButton(frame, "launch-lab-button");
            onEdt(() -> {
                Rectangle available = GuiScale.usableBounds(frame.getGraphicsConfiguration());
                assertTrue(available.contains(frame.getBounds()));
                if (available.width >= 1250 && available.height >= 870) {
                    assertTrue(frame.getWidth() >= 1180);
                    assertTrue(frame.getHeight() >= 820);
                }
                return null;
            });
            assertHomeAtSize(frame, 1044, 714, true);
            assertHomeAtSize(frame, 1100, 760, true);
            assertHomeAtSize(frame, 720, 600, false);
        } finally {
            dispose(frame);
        }
    }

    private static void assertHomeAtSize(LauncherFrame frame, int width, int height,
                                         boolean threeColumns) throws Exception {
        onEdt(() -> {
            frame.setSize(width, height);
            frame.validate();
            return null;
        });
        waitUntil(() -> {
            frame.validate();
            JPanel actions = find(frame, "homeLaunchActions");
            int preferred = actions.getComponent(0).getPreferredSize().height;
            return java.util.Arrays.stream(actions.getComponents())
                    .allMatch(button -> button.getHeight() <= preferred + 2)
                    && (threeColumns
                    ? actions.getComponent(0).getY() == actions.getComponent(2).getY()
                    : actions.getComponent(2).getY() > actions.getComponent(0).getY())
                    && homeActionsVisible(frame);
        });
        onEdt(() -> {
            assertCompactLaunches(frame, threeColumns);
            assertHomeActionsVisible(frame);
            return null;
        });
    }

    private static boolean homeActionsVisible(LauncherFrame frame) {
        javax.swing.JScrollPane scroller = find(frame, "managedHomeDeckScroller");
        return homeActions(frame).stream().allMatch(action ->
                scroller.getViewport().getViewRect().contains(
                        SwingUtilities.convertRectangle(action.getParent(),
                                action.getBounds(), scroller.getViewport().getView())));
    }

    private static List<Component> homeActions(LauncherFrame frame) {
        return List.of(find(frame, "managedHomeNavigation"), find(frame, "launch-lab-button"));
    }

    private static void assertHomeActionsVisible(LauncherFrame frame) {
        javax.swing.JScrollPane scroller = find(frame, "managedHomeDeckScroller");
        for (Component action : homeActions(frame)) {
            Rectangle bounds = SwingUtilities.convertRectangle(action.getParent(),
                    action.getBounds(), scroller.getViewport().getView());
            assertTrue(scroller.getViewport().getViewRect().contains(bounds),
                    () -> action.getName() + " should be visible without scrolling: "
                            + bounds + " viewport=" + scroller.getViewport().getViewRect());
        }
    }

    private static void assertCompactLaunches(LauncherFrame frame, boolean threeColumns) {
        JPanel actions = find(frame, "homeLaunchActions");
        assertEquals(3, actions.getComponentCount());
        int buttonHeight = actions.getComponent(0).getPreferredSize().height;
        for (Component button : actions.getComponents()) {
            assertTrue(button.getHeight() <= buttonHeight + 2,
                    () -> "launch buttons must not be stretched to a multi-row deck height: "
                            + button.getHeight() + " > " + (buttonHeight + 2)
                            + " at window " + frame.getSize());
        }
        if (threeColumns) {
            assertEquals(actions.getComponent(0).getY(), actions.getComponent(2).getY());
            assertTrue(actions.getComponent(0).getX() < actions.getComponent(2).getX());
        } else {
            assertTrue(actions.getComponent(2).getY() > actions.getComponent(0).getY());
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void settingsColumnsStayEqualAndStackWithoutLosingNavigation() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing bounds require a display");
        FakeServices services = new FakeServices(temp.resolve("settings-layout.json"));
        services.installed = true;
        services.main = services.first;
        CountDownLatch releaseNews = new CountDownLatch(1);
        LauncherNewsFeed feed = new LauncherNewsFeed((uri, accept) -> {
            if (!releaseNews.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IOException("Test news release timed out");
            }
            return new ReleaseTransport.Response(503, Map.of(),
                    new ByteArrayInputStream(new byte[0]));
        });
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services, feed));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            SwingUtilities.invokeAndWait(waitButton(frame, "settingsButton")::doClick);
            assertNotNull(waitFor(() -> find(frame, "changeDefaultJavaButton")));
            // The news worker is still pending while settings finishes loading.
            onEdt(() -> {
                frame.setSize(1080, 760);
                frame.validate();
                assertSettingsGeometry(frame, true, "newsLoadingMessage");
                frame.setSize(720, 600);
                frame.validate();
                assertSettingsGeometry(frame, false, "newsLoadingMessage");
                return null;
            });
            releaseNews.countDown();
            assertNotNull(waitFor(() -> find(frame, "newsUnavailableMessage")));
            onEdt(() -> {
                frame.setSize(1080, 760);
                frame.validate();
                assertSettingsGeometry(frame, true, "newsUnavailableMessage");
                frame.setSize(720, 600);
                frame.validate();
                assertSettingsGeometry(frame, false, "newsUnavailableMessage");
                return null;
            });
        } finally {
            releaseNews.countDown();
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void settingsLoadingAndErrorKeepResponsiveGroupsAndNewsActions() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeServices services = new FakeServices(temp.resolve("settings-error-layout.json"));
        services.installed = true;
        services.main = services.first;
        services.releaseSettings = new CountDownLatch(1);
        services.settingsFailure = new IOException("Unreadable test settings");
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services,
                new LauncherNewsFeed((uri, accept) ->
                        new ReleaseTransport.Response(503, Map.of(),
                                new ByteArrayInputStream(new byte[0])))));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            SwingUtilities.invokeAndWait(waitButton(frame, "settingsButton")::doClick);
            onEdt(() -> {
                frame.setSize(1080, 760);
                frame.validate();
                assertEquals(0, ((JPanel) find(frame, "settingsRightColumn")).getY());
                assertNotNull(find(frame, "settingsLoadingMessage"));
                assertNotNull(find(frame, "allNewsButton"));
                return null;
            });
            services.releaseSettings.countDown();
            assertNotNull(waitFor(() -> find(frame, "settingsErrorMessage")));
            onEdt(() -> {
                frame.setSize(720, 600);
                frame.validate();
                JPanel left = find(frame, "settingsLeftColumn");
                JPanel right = find(frame, "settingsRightColumn");
                assertEquals(left.getWidth(), right.getWidth());
                assertTrue(right.getY() >= left.getHeight());
                assertNotNull(find(frame, "retrySettingsButton"));
                assertNotNull(find(frame, "allNewsButton"));
                return null;
            });
        } finally {
            services.releaseSettings.countDown();
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void successfulGameExitDuringSettingsLoadDoesNotReloadOrShowBusyDialog()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeServices services = new FakeServices(temp.resolve("exit-during-settings.json"));
        services.installed = true;
        services.main = services.first;
        services.releaseSettings = new CountDownLatch(1);
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            SwingUtilities.invokeAndWait(waitButton(frame, "settingsButton")::doClick);
            assertNotNull(waitFor(() -> find(frame, "settingsLoadingMessage")));
            SwingUtilities.invokeAndWait(() -> frame.reloadAfterSuccessfulGameExit("megamek"));
            assertNotNull(onEdt(() -> find(frame, "settingsLoadingMessage")),
                    "the in-flight Settings page must not be replaced by a home reload");
            assertNoShowingDialog(frame, "Please wait");
            services.releaseSettings.countDown();
            assertNotNull(waitButton(frame, "changeDefaultJavaButton"),
                    "the Settings worker must still be able to finish");
        } finally {
            services.releaseSettings.countDown();
            dispose(frame);
        }
    }

    private static void assertSettingsGeometry(LauncherFrame frame, boolean wide, String newsState) {
        JPanel left = find(frame, "settingsLeftColumn");
        JPanel right = find(frame, "settingsRightColumn");
        javax.swing.JScrollPane scroll = find(frame, "settingsScrollPane");
        Component navigation = find(frame, "homeButton");
        assertNotNull(find(frame, newsState));
        assertNotNull(find(frame, "allNewsButton"));
        assertNotNull(find(frame, "defaultJavaPath"));
        assertNotNull(find(frame, "viewOperationLogsButton"));
        Component newsAction = find(frame, newsState.equals("newsArticleButton0")
                ? "newsArticleButton0" : "allNewsButton");
        Rectangle actionBounds = SwingUtilities.convertRectangle(newsAction.getParent(),
                newsAction.getBounds(), right);
        assertTrue(actionBounds.x + actionBounds.width <= right.getWidth(),
                "news action must fit inside its column");
        if (newsState.equals("newsArticleButton0")) {
            JPanel news = find(frame, "settingsLatestnewsSection");
            java.awt.Insets insets = news.getInsets();
            assertEquals(news.getWidth() - insets.left - insets.right,
                    newsAction.getWidth(), "headline button should use the whole news column");
            assertEquals(javax.swing.SwingConstants.LEFT, ((JButton) newsAction).getHorizontalAlignment(),
                    "headline text should align with the other Settings content");
        }
        assertEquals(0, left.getX());
        if (wide) {
            assertEquals(0, left.getY());
            assertEquals(0, right.getY());
            assertTrue(Math.abs(left.getWidth() - right.getWidth()) <= 1);
            assertTrue(right.getX() >= left.getX() + left.getWidth());
            assertTrue(find(frame, "settingsCommunitySection").getY()
                    < find(frame, "settingsLatestnewsSection").getY());
        } else {
            assertEquals(left.getWidth(), right.getWidth());
            assertEquals(0, right.getX());
            assertTrue(right.getY() >= left.getY() + left.getHeight());
            assertFalse(scroll.getHorizontalScrollBar().isVisible());
        }
        Rectangle nav = SwingUtilities.convertRectangle(navigation.getParent(),
                navigation.getBounds(), frame.getContentPane());
        Rectangle viewport = SwingUtilities.convertRectangle(scroll.getParent(),
                scroll.getBounds(), frame.getContentPane());
        assertTrue(nav.y >= viewport.y + viewport.height,
                "bottom navigation stays outside and below the scrolling settings");
        assertTrue(nav.y + nav.height <= frame.getContentPane().getHeight());
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void latestNewsLoadsIndependentlyOfSettingsAndOnlyOncePerWindow() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeServices services = new FakeServices(temp.resolve("news.json"));
        services.installed = true;
        services.main = services.first;
        CountDownLatch releaseNews = new CountDownLatch(1);
        CountDownLatch newsStarted = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        byte[] xml = ("<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry>"
                + "<title>MegaMek update and improvements for players and campaign testers</title>"
                + "<published>2026-08-14T00:00:00Z</published>"
                + "<link rel=\"alternate\" href=\"https://megamek.org/news\"/>"
                + "</entry></feed>").getBytes(StandardCharsets.UTF_8);
        LauncherNewsFeed feed = new LauncherNewsFeed((uri, accept) -> {
            requests.incrementAndGet();
            newsStarted.countDown();
            if (!releaseNews.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IOException("Test news release timed out");
            }
            return new ReleaseTransport.Response(200, Map.of(),
                    new ByteArrayInputStream(xml));
        });
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services, feed));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            SwingUtilities.invokeAndWait(waitButton(frame, "settingsButton")::doClick);
            assertTrue(newsStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNotNull(waitButton(frame, "allNewsButton"));
            assertNotNull(onEdt(() -> find(frame, "newsLoadingMessage")));
            assertNotNull(onEdt(() -> find(frame, "homeButton")),
                    "the news request does not block Settings navigation");
            releaseNews.countDown();
            JButton headline = waitButton(frame, "newsArticleButton0");
            assertEquals("Aug 14, 2026 - MegaMek update and improvements for players and campaign testers",
                    headline.getText());
            assertNotNull(waitFor(() -> find(frame, "defaultJavaPath")));
            onEdt(() -> {
                frame.setSize(2000, 760);
                frame.validate();
                assertSettingsGeometry(frame, true, "newsArticleButton0");
                assertEquals(headline.getText(), headline.getToolTipText());
                assertEquals(headline.getText(),
                        headline.getAccessibleContext().getAccessibleName());
                frame.setSize(1080, 760);
                frame.validate();
                assertSettingsGeometry(frame, true, "newsArticleButton0");
                frame.setSize(720, 600);
                frame.validate();
                assertSettingsGeometry(frame, false, "newsArticleButton0");
                return null;
            });
            assertEquals(1, requests.get());
            SwingUtilities.invokeAndWait(waitButton(frame, "homeButton")::doClick);
            SwingUtilities.invokeAndWait(waitButton(frame, "settingsButton")::doClick);
            assertNotNull(waitButton(frame, "newsArticleButton0"));
            assertEquals(1, requests.get());
        } finally {
            releaseNews.countDown();
            dispose(frame);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void unavailableNewsLeavesArchiveAndSettingsUsable() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeServices services = new FakeServices(temp.resolve("news-unavailable.json"));
        services.installed = true;
        services.main = services.first;
        LauncherNewsFeed feed = new LauncherNewsFeed((uri, accept) ->
                new ReleaseTransport.Response(503, Map.of(), new ByteArrayInputStream(new byte[0])));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services, feed));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            SwingUtilities.invokeAndWait(waitButton(frame, "settingsButton")::doClick);
            assertNotNull(waitFor(() -> find(frame, "newsUnavailableMessage")));
            assertNotNull(waitButton(frame, "allNewsButton"));
            assertNotNull(waitButton(frame, "changeDefaultJavaButton"));
        } finally {
            dispose(frame);
        }
    }

    @Test
    void launcherTitleShowsPackagedVersionOrDevelopmentBuild() {
        assertEquals("MegaMek Launcher 0.14.0", LauncherFrame.titleFor("0.14.0"));
        assertEquals("MegaMek Launcher (development build)", LauncherFrame.titleFor(null));
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void directLaunchMinimizesSafelyAndRestoredWindowAllowsAnotherLaunch()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual frame state and dialogs require a display");
        FakeServices services = new FakeServices(temp.resolve("direct-launch.json"));
        services.installed = true;
        services.main = services.first;
        services.installedRecord = services.first;
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton primary = waitButton(frame, "launch-megamek-button");

            services.launchStarted = new CountDownLatch(1);
            services.releaseLaunch = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(primary::doClick);
            assertTrue(services.launchStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(services.first, services.launchedRecord);
            assertEquals("megamek", services.launchedProduct);
            assertTrue((onEdt(frame::getExtendedState) & javax.swing.JFrame.ICONIFIED) != 0);
            assertNoShowingDialog(frame, "Confirm launch");
            services.releaseLaunch.countDown();
            waitUntil(() -> services.launchAttempts.get() == 1
                    && find(frame, "launch-megamek-button") != null
                    && ((JButton) find(frame, "launch-megamek-button")).isEnabled());
            assertTrue((onEdt(frame::getExtendedState) & javax.swing.JFrame.ICONIFIED) != 0,
                    "zero exit stays minimized for manual restoration");
            assertNoShowingDialog(frame, "Game finished");

            SwingUtilities.invokeAndWait(() ->
                    frame.setExtendedState(javax.swing.JFrame.NORMAL));
            services.launchExit = 7;
            services.launchStarted = new CountDownLatch(1);
            services.releaseLaunch = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(frame, "launch-megamek-button")).doClick());
            assertTrue(services.launchStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue((onEdt(frame::getExtendedState) & javax.swing.JFrame.ICONIFIED) != 0);
            services.releaseLaunch.countDown();
            JDialog nonzero = waitDialog(frame, "Game exited with code 7");
            assertEquals(javax.swing.JFrame.NORMAL,
                    onEdt(frame::getExtendedState) & ~javax.swing.JFrame.MAXIMIZED_BOTH);
            SwingUtilities.invokeAndWait(nonzero::dispose);

            services.launchExit = 0;
            services.launchFailure = new IOException("fixture start failure");
            services.launchStarted = new CountDownLatch(1);
            services.releaseLaunch = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(frame, "launch-megamek-button")).doClick());
            assertTrue(services.launchStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            services.releaseLaunch.countDown();
            JDialog failed = waitDialog(frame, "Launching MegaMek failed");
            assertEquals(javax.swing.JFrame.NORMAL,
                    onEdt(frame::getExtendedState) & ~javax.swing.JFrame.MAXIMIZED_BOTH);
            SwingUtilities.invokeAndWait(failed::dispose);

            services.launchFailure = null;
            services.launchStarted = new CountDownLatch(1);
            services.releaseLaunch = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(frame, "launch-megamek-button")).doClick());
            assertTrue(services.launchStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() ->
                    frame.setExtendedState(javax.swing.JFrame.NORMAL));
            int attempts = services.launchAttempts.get();
            CountDownLatch firstRelease = services.releaseLaunch;
            services.launchStarted = new CountDownLatch(1);
            services.releaseLaunch = new CountDownLatch(1);
            SwingUtilities.invokeLater(
                    () -> frame.launchDirectly(services.second, "megamek"));
            assertTrue(services.launchStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(attempts + 1, services.launchAttempts.get(),
                    "another launch starts before the earlier child exits");
            assertEquals(services.second, services.launchedRecord);
            firstRelease.countDown();
            services.releaseLaunch.countDown();
            assertEquals(0, services.selections.get(),
                    "neither primary nor alternate direct launch mutates preferences");
        } finally {
            if (services.releaseLaunch != null) services.releaseLaunch.countDown();
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
        private final AtomicInteger launchAttempts = new AtomicInteger();
        private final AtomicInteger resetPlans = new AtomicInteger();
        private final AtomicInteger resetCalls = new AtomicInteger();
        private final AtomicInteger repairs = new AtomicInteger();
        private final Path resetBackup;
        private final List<QuickInstallOption.Key> plannedChoices =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<Path> plannedDestinations =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<String> plannedSources =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private NormalInstallService.TargetKind capturedTargetKind =
                NormalInstallService.TargetKind.CAPTURED_CURRENT;
        private volatile String currentBrowsedSource;
        private final QuickInstallSnapshot quickInstallSnapshot =
                QuickInstallTestData.snapshot("1.2.3", "1.2.4");
        private volatile boolean installed;
        private volatile InstallationRecord installedRecord;
        private volatile boolean pending;
        private volatile boolean repairManaged;
        private volatile boolean repairAdopted;
        private volatile IOException repairFailure;
        private volatile boolean launched;
        private volatile InstallationRecord previewedRecord;
        private volatile String previewedProduct;
        private volatile InstallationRecord launchedRecord;
        private volatile String launchedProduct;
        private volatile InstallationRecord main;
        private volatile OfficialRepository lastPlannedRepository;
        private volatile FollowChannel lastPlannedChannel;
        private volatile FollowChannel installedChannel;
        private volatile CountDownLatch planStarted;
        private volatile CountDownLatch releasePlan;
        private volatile CountDownLatch snapshotStarted;
        private volatile CountDownLatch releaseSnapshot;
        private final AtomicInteger snapshotFailures = new AtomicInteger();
        private volatile CountDownLatch installStarted;
        private volatile CountDownLatch releaseInstall;
        private volatile CountDownLatch launchStarted;
        private volatile CountDownLatch releaseLaunch;
        private volatile int launchExit;
        private volatile IOException launchFailure;
        private volatile boolean installBecameMain = true;
        private volatile IOException installFailure;
        private volatile CountDownLatch releaseSettings;
        private volatile IOException settingsFailure;

        private FakeServices(Path registry) {
            super(registry);
            this.registry = registry.toAbsolutePath().normalize();
            resetBackup = registry.getParent().resolve("reset-backup");
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
                    first.canonicalRoot(), first.observedBuild(), first.products(),
                    first.pin(), false, first.registeredAt());
            second = record("2", "Other copy", registry.getParent().resolve("Other"),
                    products, null);
            records = List.of(firstWithoutJava, second);
            main = first;
        }

        @Override public void requireInstallationPresent(InstallationRecord record) {
            assertTrue(records.stream().anyMatch(candidate -> candidate.id().equals(record.id())));
        }

        @Override public PreferencesResetService.Plan planPreferencesReset(
                InstallationRecord record) {
            resetPlans.incrementAndGet();
            return new PreferencesResetService.Plan(record, "mekhq",
                    List.of("mm.preferences"), resetBackup, Map.of());
        }

        @Override public RealUpdateService.Snapshot repairSource(InstallationRecord record) {
            CurrentUpdateState current = new CurrentUpdateState(1, 1, record.id(),
                    record.canonicalRoot(), record.registeredAt(), "mekhq", "v1.2.3",
                    asset.name(), asset.size(), "a".repeat(64), null, List.of(),
                    List.of(), List.of(), null);
            return new RealUpdateService.Snapshot(registry, record, null, current,
                    Path.of(record.canonicalRoot()));
        }

        @Override public RealUpdateService.RepairResult repair(
                RealUpdateService.Snapshot consented, java.io.PrintStream progress)
                throws IOException {
            repairs.incrementAndGet();
            if (repairFailure != null) throw repairFailure;
            return new RealUpdateService.RepairResult(consented.record(), 1, 1,
                    List.of("lib/mod.jar"),
                    "Temporary backups removed.");
        }

        @Override public RealUpdateService.RepairResult repair(
                RealUpdateService.Snapshot consented, java.io.PrintStream progress,
                OperationContext context) throws IOException {
            return repair(consented, progress);
        }

        @Override public PreferencesResetService.Result resetPreferences(
                PreferencesResetService.Plan plan) {
            resetCalls.incrementAndGet();
            return new PreferencesResetService.Result(plan.files(), plan.backup());
        }

        @Override public SettingsView settingsView() throws IOException, InterruptedException {
            CountDownLatch release = releaseSettings;
            if (release != null) release.await();
            if (settingsFailure != null) throw settingsFailure;
            return super.settingsView();
        }

        @Override public QuickInstallSnapshot quickInstallSnapshot()
                throws IOException, InterruptedException {
            catalogRequests.incrementAndGet();
            CountDownLatch started = snapshotStarted;
            CountDownLatch release = releaseSnapshot;
            if (started != null) started.countDown();
            if (release != null) release.await();
            if (snapshotFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IOException("fixture metadata unavailable");
            }
            return quickInstallSnapshot;
        }

        @Override
        public Path normalInstallDestination(OfficialRepository repository,
                                             FollowChannel channel) {
            return destination;
        }

        @Override
        public NormalInstallService.Plan prepareNormalInstall(OfficialRepository repository,
                                                              FollowChannel channel, Path target)
                throws InterruptedException {
            return plan(new QuickInstallOption.Key(repository, channel), target,
                    NormalInstallService.TargetKind.CURRENT_CHANNEL);
        }

        @Override
        public NormalInstallService.Plan prepareCapturedNormalInstall(QuickInstallOption option,
                                                                      Path target)
                throws InterruptedException {
            return plan(option.key(), target,
                    capturedTargetKind);
        }

        @Override
        public NormalInstallService.Plan prepareExactNormalInstall(OfficialRepository repository,
                FollowChannel channel, String tag, Path target) throws InterruptedException {
            return plan(new QuickInstallOption.Key(repository, channel), target,
                    NormalInstallService.TargetKind.BROWSED_EXACT, currentBrowsedSource);
        }

        @Override
        public NormalInstallService.Plan prepareExactNormalInstall(OfficialRepository repository,
                FollowChannel channel, String tag, String source, Path target) throws InterruptedException {
            return plan(new QuickInstallOption.Key(repository, channel), target,
                    NormalInstallService.TargetKind.BROWSED_EXACT, source);
        }

        private NormalInstallService.Plan plan(QuickInstallOption.Key key, Path target,
                                               NormalInstallService.TargetKind targetKind)
                throws InterruptedException {
            return plan(key, target, targetKind, null);
        }

        private NormalInstallService.Plan plan(QuickInstallOption.Key key, Path target,
                                               NormalInstallService.TargetKind targetKind, String source)
                throws InterruptedException {
            plans.incrementAndGet();
            OfficialRepository repository = key.repository();
            FollowChannel channel = key.channel();
            lastPlannedRepository = key.repository();
            lastPlannedChannel = key.channel();
            plannedDestinations.add(target.toAbsolutePath().normalize());
            plannedChoices.add(key);
            CountDownLatch started = planStarted;
            CountDownLatch releasePlanning = releasePlan;
            if (started != null) started.countDown();
            if (releasePlanning != null) releasePlanning.await();
            RegistryData empty = new RegistryData(RegistryStore.SCHEMA, null, List.of());
            QuickInstallOption option = quickInstallSnapshot.option(key);
            String capturedSource = source == null ? option.target().source() : source;
            plannedSources.add(capturedSource);
            java.util.Set<String> expectedProducts = switch (repository) {
                case MEKHQ -> NormalInstallService.SUITE_PRODUCTS;
                case MEGAMEK -> java.util.Set.of("megamek");
                case LAB -> java.util.Set.of("lab");
            };
            return new NormalInstallService.Plan(
                    registry, new NormalInstallService.RegistrySnapshot(false, empty), target,
                    List.of(), channel, repository, option.version(), expectedProducts,
                    option.release(), option.asset(),
                    capturedSource,
                    targetKind);
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
            installedRecord = new InstallationRecord(
                    first.id(), first.name(), first.canonicalRoot(), plan.version(),
                    first.products(), first.pin(),
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
            List<InstallationRecord> currentRecords = installedRecord == null
                    ? records : List.of(installedRecord, second);
            Inspection inspection = new Inspection(current.canonicalRoot(), current.products(),
                    current.observedBuild(), "fixture");
            ChannelPreference preference = new ChannelPreference(1, current.id(),
                    current.canonicalRoot(), current.registeredAt(),
                    installedChannel == null ? FollowChannel.MILESTONE : installedChannel, true);
            return new HomeState(new RegistryData(
                    RegistryStore.SCHEMA, current.id(), currentRecords), current,
                    inspection, null,
                    new UpdatePreviewService.Eligibility(current, true,
                            "fixture managed copy", null, null),
                    pending && current.id().equals(first.id()),
                    new ChannelPreferenceStore.ReadResult(
                            ChannelPreferenceStore.Status.CONFIGURED,
                            preference, "Configured"), Map.of(),
                    repairManaged ? Map.of(current.id(), new InstallationStatus(
                            new ChannelPreferenceStore.ReadResult(
                                    ChannelPreferenceStore.Status.CONFIGURED,
                                    preference, "Configured"),
                            new UpdatePreviewService.Eligibility(current, true,
                                    "fixture managed copy", null, null),
                            pending, false, null,
                            ImportedCopyAdoptionService.Availability.MANAGED,
                            repairAdopted)) : Map.of());
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
        public int launch(InstallationRecord record, String product)
                throws IOException, InterruptedException {
            launchAttempts.incrementAndGet();
            launched = true;
            launchedRecord = record;
            launchedProduct = product;
            CountDownLatch started = launchStarted;
            CountDownLatch release = releaseLaunch;
            if (started != null) started.countDown();
            if (release != null) release.await();
            if (launchFailure != null) throw launchFailure;
            return launchExit;
        }

        private static InstallationRecord record(String suffix, String name, Path root,
                                                 List<Product> products, String java) {
            return new InstallationRecord("00000000-0000-0000-0000-00000000000" + suffix,
                    name, root.toAbsolutePath().normalize().toString(), "1.2.3", products,
                    null, false, "2026-09-17T00:00:0" + suffix + "Z");
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
        SwingTestSupport.startClickText(dialog, text);
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
        String releaseLabel =
            VersionDisplay.installLatest(product, channel.toString(), version);
        assertEquals(releaseLabel, dialog.getTitle());
        assertEquals(releaseLabel,
            ((JLabel) find(dialog, "normalInstallHeading")).getText());
        assertNull(find(dialog, "normalInstallVersion"));
        assertEquals(expectedIncludes(repository),
                ((JLabel) find(dialog, "normalInstallIncludes")).getText());
        assertEquals("Download: "
                        + NormalInstallConfirmationDialog.formatBinaryBytes(asset.size()),
                ((JLabel) find(dialog, "normalInstallDownloadSize")).getText());
        assertNull(find(dialog, "normalInstallChannelMeaning"));

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
        return SwingTestSupport.dialog(frame, title);
    }

    private static JButton waitButton(Container root, String name) throws Exception {
        return waitFor(() -> find(root, name));
    }

    private static JButton waitShowingButton(Container root, String name) throws Exception {
        return waitFor(() -> {
            JButton button = find(root, name);
            return button != null && button.isShowing() ? button : null;
        });
    }

    private static void clickShowingButton(Container root, String name) throws Exception {
        SwingTestSupport.click(root, name);
    }

    private static <T> T waitFor(java.util.function.Supplier<T> probe) throws Exception {
        return SwingTestSupport.await("home presentation", probe::get);
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        SwingTestSupport.awaitCondition("home state", condition::getAsBoolean);
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

    private static void saveReviewImage(String name, Container component) throws Exception {
        String directory = System.getenv("MM_LAUNCHER_REVIEW_IMAGES");
        if (directory == null) return;
        BufferedImage image = onEdt(() -> {
            BufferedImage rendered = new BufferedImage(component.getWidth(),
                    component.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = rendered.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                component.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            return rendered;
        });
        Path path = Path.of(directory);
        Files.createDirectories(path);
        ImageIO.write(image, "PNG", path.resolve(name).toFile());
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
        return SwingTestSupport.onEdt(work);
    }

    private static void dispose(LauncherFrame frame) throws Exception {
        SwingTestSupport.dispose(frame);
    }
}
