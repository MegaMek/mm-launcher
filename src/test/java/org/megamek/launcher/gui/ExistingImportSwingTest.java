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

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.ProcessRunner;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.update.ImportedCopyAdoptionService;
import org.megamek.launcher.update.PreparedAdoption;
import org.megamek.launcher.update.ReceiptStore;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("gui-smoke")
class ExistingImportSwingTest {
    @TempDir Path temp;

    @Test
    void directImportRemainsAvailableWhileQuickInstallMetadataIsLoading()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Path existing = suite("loading-import", List.of("megamek"));
        Path registry = temp.resolve("loading-import-registry.json");
        CountDownLatch snapshotStarted = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        AtomicInteger snapshots = new AtomicInteger();
        LauncherServices services = services(registry, new RecordingRunner(), () -> {
            snapshots.incrementAndGet();
            snapshotStarted.countDown();
            try {
                releaseSnapshot.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return QuickInstallTestData.snapshot("0.51.0", "0.52.0");
        });
        RecordingPrompts prompts = new RecordingPrompts(List.of(existing));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services, prompts));
        try {
            onEdt(() -> {
                frame.showWindow();
                return null;
            });
            assertTrue(snapshotStarted.await(5, TimeUnit.SECONDS));
            JButton importButton = waitFor(() -> findButton(frame, "useExistingCopyButton"));
            assertTrue(importButton.isEnabled());
            assertFalse(waitFor(() -> findButton(
                    frame, "downloadAndInstallButton")).isEnabled());

            onEdt(() -> {
                importButton.doClick();
                return null;
            });

            assertNotNull(waitFor(() -> findButton(frame, "launch-megamek-button")));
            releaseSnapshot.countDown();
            waitUntil(() -> !frame.firstLaunchMetadataPending());
            assertNotNull(onEdt(() -> findButton(frame, "launch-megamek-button")));
            assertNull(onEdt(() -> find(frame, "firstLaunchSplitButton")));
            assertEquals(1, snapshots.get());
            assertEquals(1, prompts.folderRequests);
        } finally {
            releaseSnapshot.countDown();
            dispose(frame);
        }
    }

    @Test
    void invalidExistingFolderShowsPlainGuidanceInsteadOfLayoutDetails() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Path invalid = Files.createDirectory(temp.resolve("not-an-installation"));
        Path registry = temp.resolve("invalid-folder-registry.json");
        LauncherServices services = services(registry, new RecordingRunner());
        LauncherFrame frame = onEdt(() -> new LauncherFrame(
                services, new RecordingPrompts(List.of(invalid))));
        try {
            onEdt(() -> {
                frame.showWindow();
                return null;
            });
            JButton importButton = waitFor(() -> findButton(frame, "useExistingCopyButton"));
            onEdt(() -> {
                importButton.doClick();
                return null;
            });

            JLabel heading = waitFor(() -> {
                JLabel label = (JLabel) findOwned(frame, "operationPhaseLabel");
                return label != null && "This folder can't be used".equals(label.getText())
                        ? label : null;
            });
            JLabel detail = (JLabel) findOwned(frame, "operationProgressDetail");
            assertEquals("This folder can't be used", heading.getText());
            assertTrue(detail.getText().contains(
                    "Choose the main MegaMek, MekHQ, or MegaMekLab installation folder"));
            assertFalse(detail.getText().contains("unsupported layout"));
            assertFalse(((JButton) findOwned(frame, "operationViewDetailsButton")).isVisible());
            assertEquals(1, countButtonText(
                    (Container) detail.getTopLevelAncestor(), "Close"));
            assertEquals("", ((JLabel) find(frame, "homeStatusLabel")).getText(),
                    "the operation dialog is the only failure status surface");
        } finally {
            dispose(frame);
        }
    }

    @Test
    void ineligibleAdoptionExplainsTheMismatchWithStandardStyling() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        LauncherFrame frame = onEdt(() -> new LauncherFrame(
                new LauncherServices(temp.resolve("ineligible-adoption-registry.json"))));
        try {
            onEdt(() -> {
                frame.setVisible(true);
                frame.showIneligibleAdoption(new PreparedAdoption.Report(
                        false, "MekHQ", "0.50.7",
                        org.megamek.launcher.channel.FollowChannel.DEVELOPMENT,
                        "Core application files differ from the official release."));
                return null;
            });

            JDialog dialog = waitFor(() -> findDialog(frame, "adoptionResultDialog"));
            JLabel heading = (JLabel) find(dialog, "adoptionResultHeading");
            JLabel message = (JLabel) find(dialog, "adoptionResultMessage");
            assertEquals("Updates can't be enabled", dialog.getTitle());
            assertEquals("Updates can't be enabled", heading.getText());
            assertEquals(FirstLaunchPanel.GOLD, heading.getForeground());
            assertEquals(java.awt.Font.PLAIN, message.getFont().getStyle());
            assertTrue(message.getText().contains(
                    "Core application files differ from the official release."));
            assertTrue(message.getText().contains("No files were changed."));
            assertFalse(message.getText().contains("currently a Milestone release"));
            assertFalse(message.getText().contains("currently a Development release"));
            assertNull(find(dialog, "viewAdoptionLogsButton"));
            assertNull(find(dialog, "operationViewDetailsButton"));
            assertNull(find(dialog, "retrySuggestedAdoptionButton"));
            assertFalse(dialog.isResizable());
            assertEquals(1, countButtonText(dialog, "Close"));
            assertEquals(find(dialog, "closeAdoptionResultButton"),
                    dialog.getRootPane().getDefaultButton());
        } finally {
            dispose(frame);
        }
    }

    @Test
    void visibleAndAdvancedActionsShareAtomicImportAndHomeShowsActualProducts()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Path megaMek = suite("MegaMek-only", List.of("megamek"));
        Path full = suite("MekHQ-bundle", List.of("megamek", "mekhq", "lab"));
        Path registry = temp.resolve("registry.json");
        RecordingRunner runner = new RecordingRunner();
        LauncherServices services = services(registry, runner);
        RecordingPrompts prompts = new RecordingPrompts(List.of(megaMek, full));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services, prompts));
        try {
            onEdt(() -> {
                frame.showWindow();
                return null;
            });
            JButton visibleImport = waitFor(() -> findButton(frame, "useExistingCopyButton"));
            assertEquals(1, countNamed(frame, "useExistingCopyButton"));
            assertEquals("Use existing installation", visibleImport.getText());
            JLabel caption = waitFor(() -> (JLabel) find(frame, "existingCopyCaption"));
            assertEquals("MegaMek, MekHQ, or MegaMekLab", caption.getText());

            onEdt(() -> {
                visibleImport.doClick();
                return null;
            });
            assertNotNull(waitFor(() -> findButton(frame, "launch-megamek-button")));
            assertNull(onEdt(() -> findButton(frame, "launch-mekhq-button")));
            assertNull(onEdt(() -> findButton(frame, "launch-lab-button")));
            assertFalse(hasShowingDialog(frame, "Input"));
            assertFalse(hasShowingDialog(frame, "Confirm existing installation"));
            assertFalse(hasShowingDialog(frame, "Import complete"));
            assertEquals("MegaMek existing installation",
                    services.loadHome().preferred().name());

            JButton installations =
                    waitFor(() -> findButton(frame, "manageInstallationsButton"));
            onEdt(() -> {
                installations.doClick();
                return null;
            });
            JButton advancedImport =
                    waitFor(() -> findButton(frame, "manageAddExistingButton"));
            onEdt(() -> {
                advancedImport.doClick();
                return null;
            });
            assertNotNull(waitFor(() -> findButton(frame, "manageInstallationsButton")),
                    "successful import follows the normal install flow back to Home");
            assertFalse(hasShowingDialog(frame, "Input"));
            assertFalse(hasShowingDialog(frame, "Confirm existing installation"));
            assertFalse(hasShowingDialog(frame, "Import complete"));

            RegistryData data = services.readRegistry();
            assertEquals(2, data.installations().size());
            assertEquals("MegaMek existing installation",
                    services.loadHome().preferred().name());
            assertTrue(data.installations().stream().anyMatch(record ->
                    "MekHQ existing installation".equals(record.name())));
            assertFalse(Files.readString(registry).contains("javaExecutable"));
            assertEquals(2, prompts.folderRequests);
            assertTrue(runner.commands.isEmpty(), "import must not execute Java");
            ChannelPreferenceStore channels = new ChannelPreferenceStore();
            for (var record : data.installations()) {
                assertFalse(Files.exists(channels.path(registry, record.id())),
                        "an imported folder must not acquire a channel sidecar");
                assertFalse(Files.exists(new ReceiptStore().receiptPath(registry, record.id())),
                        "an imported folder must remain receipt-less");
            }

            JButton manageAgain = waitFor(() -> findButton(frame,
                    "manageInstallationsButton"));
            onEdt(() -> {
                manageAgain.doClick();
                return null;
            });
            for (var record : data.installations()) {
                JLabel provenance = waitFor(() -> (JLabel) find(frame,
                        "installationProvenance-" + record.id()));
                assertEquals("Imported copy · Launch only · Updates unavailable",
                        provenance.getText());
                assertNull(find(frame, "installationStatus-" + record.id()),
                        "the imported-copy provenance already explains launch-only status");
                JLabel title = (JLabel) find(frame, "installationName-" + record.id());
                int[] positions = onEdt(() -> new int[]{
                        SwingUtilities.convertPoint(title, 0, 0, frame).x,
                        SwingUtilities.convertPoint(provenance, 0, 0, frame).x,
                        SwingUtilities.convertPoint(provenance, 0, 0, frame).y
                                - SwingUtilities.convertPoint(title, 0, title.getHeight(), frame).y
                });
                assertEquals(positions[0], positions[1], "title and body share the left edge");
                assertTrue(positions[2] > 0, "the title has space before the body");
                assertNotNull(waitFor(() -> findButton(frame,
                        "enableManagedUpdatesButton-" + record.id())));
                assertNull(find(frame, "checkOnOpenCheckbox-" + record.id()));
            }
            assertNull(onEdt(() -> findButton(frame, "chooseChannelButton")));
            assertNull(onEdt(() -> findButton(frame, "updateChecksButton")));
            assertNull(onEdt(() -> findButton(frame, "checkUpdatesButton")));
            assertNull(onEdt(() -> findButton(frame, "applyUpdateButton")));
            assertNull(onEdt(() -> findButton(frame, "recoverUpdateButton")));
        } finally {
            dispose(frame);
        }
    }

    @Test
    void cancellingFolderChoiceLeavesRegistryAndSelectedFilesUntouched() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Path root = suite("cancelled", List.of("megamek"));
        Map<Path, Long> before = inventory(root);
        Path registry = temp.resolve("cancelled-registry.json");
        RecordingPrompts prompts = new RecordingPrompts(List.of());
        LauncherFrame frame = onEdt(() -> new LauncherFrame(
                services(registry, new RecordingRunner()), prompts));
        try {
            onEdt(() -> {
                frame.showWindow();
                return null;
            });
            JButton importButton = waitFor(() -> findButton(frame, "useExistingCopyButton"));
            onEdt(() -> {
                importButton.doClick();
                return null;
            });
            assertFalse(Files.exists(registry));
            assertEquals(before, inventory(root));
            assertEquals(1, prompts.folderRequests);
            assertNotNull(waitFor(() -> findButton(frame, "useExistingCopyButton")));
        } finally {
            dispose(frame);
        }
    }

    @Test
    void adoptionStartIsCompactAndCancelOrEscapeMakesNoRequest() throws Exception {
            Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                    "actual Swing controls require a display");
            Path root = versionedMegaMekSuite("adoption-dialog");
            Path registry = temp.resolve("adoption-dialog-registry.json");
            AtomicInteger requests = new AtomicInteger();
            ReleaseTransport recording = (uri, accept) -> {
                requests.incrementAndGet();
                throw new IOException("network must not run before Continue");
            };
            LauncherServices services = new LauncherServices(
                    registry, new RegistryStore(), new InstallationInspector(), recording,
                    new JavaRuntime(new RecordingRunner()),
                    new ApplicationLauncher(new RecordingRunner()));
            var record = services.register("Imported", root);
            LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
            try {
                onEdt(() -> {
                    frame.showWindow();
                    return null;
                });
                JButton manage = waitFor(() -> findButton(frame, "manageInstallationsButton"));
                onEdt(() -> {
                    manage.doClick();
                    return null;
                });
                JButton enable = waitFor(() -> findButton(frame,
                        "enableManagedUpdatesButton-" + record.id()));
                assertEquals("Enable Updates", enable.getText());
                onEdt(() -> {
                    enable.doClick();
                    return null;
                });
                JDialog dialog = waitFor(() -> findDialog(frame, "adoptionCandidateDialog"));
                JButton cancel = (JButton) find(dialog, "cancelAdoptionButton");
                JButton proceed = (JButton) find(dialog, "continueAdoptionButton");
                JLabel application = (JLabel) find(dialog,
                        "adoptionDetectedApplicationVersion");
                JComboBox<?> channel = (JComboBox<?>) find(dialog, "adoptionChannelCombo");
                assertEquals("MegaMek (0.50.7)", application.getText());
                assertEquals(org.megamek.launcher.channel.FollowChannel.MILESTONE,
                        channel.getSelectedItem());
                assertTrue(cancel.getX() < proceed.getX());
                assertTrue(dialog.getHeight() < dialog.getWidth(), "dialog remains compact");
                assertNull(find(dialog, "chooseAdoptionReleaseButton"));
                assertNull(find(dialog, "verifyAdoptionButton"));
                assertEquals(0, countButtonText(dialog, "Verify copy"));
                assertEquals(0, countButtonText(dialog, "Choose official release…"));
                onEdt(() -> {
                    dialog.getRootPane().getActionMap().get("cancelAdoption").actionPerformed(
                            new java.awt.event.ActionEvent(dialog, 0, "escape"));
                    return null;
                });
                assertEquals(0, requests.get());

                onEdt(() -> {
                    enable.doClick();
                    return null;
                });
                JDialog reopened = waitFor(() -> findDialog(frame, "adoptionCandidateDialog"));
                onEdt(() -> {
                    ((JButton) find(reopened, "cancelAdoptionButton")).doClick();
                    return null;
                });
                assertEquals(0, requests.get());
            } finally {
                dispose(frame);
            }
    }

    @Test
    void successfulAdoptionEnablesImmediatelyWithoutAnotherConfirmation() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        AdoptionFixture fixture = adoptableMegaMekSuite("automatic-adoption");
        Path registry = temp.resolve("automatic-adoption-registry.json");
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger packageRequests = new AtomicInteger();
        AtomicReference<OperationContext> preparationContext = new AtomicReference<>();
        AtomicReference<OperationContext> publicationContext = new AtomicReference<>();
        ReleaseTransport transport = automaticAdoptionTransport(
                fixture.archive(), requests, packageRequests);
        LauncherServices services = new LauncherServices(
                registry, new RegistryStore(), new InstallationInspector(), transport,
                new JavaRuntime(new RecordingRunner()),
                new ApplicationLauncher(new RecordingRunner())) {
            @Override
            public PreparedAdoption prepareAutomaticAdoption(
                    org.megamek.launcher.registry.InstallationRecord record,
                    org.megamek.launcher.release.OfficialRepository repository,
                    org.megamek.launcher.channel.FollowChannel channel,
                    PrintStream progress, OperationContext context)
                    throws IOException, InterruptedException,
                    org.megamek.launcher.manifest.ManifestException {
                preparationContext.set(context);
                return super.prepareAutomaticAdoption(
                        record, repository, channel, progress, context);
            }

            @Override
            public ImportedCopyAdoptionService.CommitResult commitAdoption(
                    PreparedAdoption prepared, OperationContext context,
                    PrintStream progress)
                    throws IOException, InterruptedException,
                    org.megamek.launcher.manifest.ManifestException {
                publicationContext.set(context);
                return super.commitAdoption(prepared, context, progress);
            }
        };
        var record = services.register("Imported", fixture.root());
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            onEdt(() -> {
                frame.showWindow();
                return null;
            });
            JButton manage = waitFor(() -> findButton(frame, "manageInstallationsButton"));
            onEdt(() -> {
                manage.doClick();
                return null;
            });
            JButton enable = waitFor(() -> findButton(frame,
                    "enableManagedUpdatesButton-" + record.id()));
            onEdt(() -> {
                enable.doClick();
                return null;
            });
            JDialog start = waitFor(() -> findDialog(frame, "adoptionCandidateDialog"));
            onEdt(() -> {
                ((JButton) find(start, "continueAdoptionButton")).doClick();
                return null;
            });

            assertNotNull(waitFor(() -> findButton(frame, "manageAddExistingButton")),
                    "successful validation and publication remain on Installations");
            JLabel updateStatus = waitFor(() -> {
                JLabel label = (JLabel) find(frame, "installationStatus-" + record.id());
                return label != null && "Up to date".equals(label.getText()) ? label : null;
            });
            assertEquals("Up to date", updateStatus.getText());
            assertNull(findButton(frame, "manageInstallationsButton"));
            assertFalse(hasShowingDialog(frame, "Copy verified"));
            var preference = new ChannelPreferenceStore()
                    .read(registry, services.readRegistry(), record).preference();
            assertEquals(org.megamek.launcher.channel.FollowChannel.MILESTONE,
                    preference.channel());
            assertTrue(preference.checkOnOpen());
            assertSame(preparationContext.get(), publicationContext.get(),
                    "verification and publication must use one active operation context");
            assertEquals(19, requests.get(),
                    "publication performs no remote metadata request");
            assertEquals(1, packageRequests.get(),
                    "publication reuses the verified download");
        } finally {
            dispose(frame);
        }
    }

    @Test
    void channelMismatchOffersDirectTruthfulRetryWithoutDownloading() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        AdoptionFixture fixture = adoptableMegaMekSuite("channel-mismatch-adoption");
        Path registry = temp.resolve("channel-mismatch-adoption-registry.json");
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger packageRequests = new AtomicInteger();
        ReleaseTransport transport = channelMismatchAdoptionTransport(
                fixture.archive(), requests, packageRequests);
        String privatePath = temp.resolve(".adoption-attempt-private").toString();
        LauncherServices services = new LauncherServices(
                registry, new RegistryStore(), new InstallationInspector(), transport,
                new JavaRuntime(new RecordingRunner()),
                new ApplicationLauncher(new RecordingRunner())) {
            @Override public PreparedAdoption prepareAutomaticAdoption(
                    org.megamek.launcher.registry.InstallationRecord selected,
                    org.megamek.launcher.release.OfficialRepository repository,
                    FollowChannel channel, PrintStream progress, OperationContext context)
                    throws IOException, InterruptedException,
                    org.megamek.launcher.manifest.ManifestException {
                try {
                    return super.prepareAutomaticAdoption(
                            selected, repository, channel, progress, context);
                } catch (ImportedCopyAdoptionService.ChannelMismatchException mismatch) {
                    // The resolver rejects before creating an attempt directory; simulate
                    // a late cleanup diagnostic without changing the typed primary result.
                    mismatch.addSuppressed(
                            new IOException("injected cleanup failure: " + privatePath));
                    throw mismatch;
                }
            }
        };
        var record = services.register("Imported", fixture.root());
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            onEdt(() -> {
                frame.showWindow();
                return null;
            });
            JButton manage = waitFor(() -> findButton(frame, "manageInstallationsButton"));
            onEdt(() -> {
                manage.doClick();
                return null;
            });
            JButton enable = waitFor(() -> findButton(frame,
                    "enableManagedUpdatesButton-" + record.id()));
            onEdt(() -> {
                enable.doClick();
                return null;
            });
            JDialog start = waitFor(() -> findDialog(frame, "adoptionCandidateDialog"));
            onEdt(() -> {
                JComboBox<?> channel = (JComboBox<?>) find(start, "adoptionChannelCombo");
                channel.setSelectedItem(FollowChannel.DEVELOPMENT);
                ((JButton) find(start, "continueAdoptionButton")).doClick();
                return null;
            });

            JDialog mismatch = waitFor(() -> findDialog(frame, "adoptionResultDialog"));
            JLabel heading = (JLabel) find(mismatch, "adoptionResultHeading");
            JLabel message = (JLabel) find(mismatch, "adoptionResultMessage");
            JButton retry = (JButton) find(mismatch, "retrySuggestedAdoptionButton");
            assertEquals("Use Milestone for this installation", mismatch.getTitle());
            assertEquals("Use Milestone for this installation", heading.getText());
            assertEquals(FirstLaunchPanel.GOLD, heading.getForeground());
            assertEquals(java.awt.Font.PLAIN, message.getFont().getStyle());
            assertTrue(message.getText().contains(
                    "Version 0.50.07 is currently a Milestone release"));
            assertTrue(message.getText().contains("No files were changed."));
            assertTrue(message.getText().contains("Select Milestone"));
            assertEquals("Try Milestone", retry.getText());
            assertEquals(1, countButtonText(mismatch, "Close"));
            assertFalse(mismatch.isResizable());
            assertEquals(retry, mismatch.getRootPane().getDefaultButton());
            assertFalse(message.getText().contains(privatePath));
            assertNull(find(mismatch, "operationViewDetailsButton"));
            assertNull(find(mismatch, "browseAfterAdoptionFailureButton"));
            assertFalse(hasShowingDialog(frame, "Verifying imported copy"));
            assertFalse(hasShowingDialog(frame, "This copy remains launch-only"));
            assertFalse(hasShowingDialog(frame, "Details"));
            assertEquals(0, packageRequests.get(),
                    "channel mismatch must be decided before package transfer");
            assertEquals(9, requests.get(),
                    "channel mismatch needs only the canonical current pointers");

            onEdt(() -> {
                retry.doClick();
                return null;
            });

            JLabel updateStatus = waitFor(() -> {
                JLabel label = (JLabel) find(frame, "installationStatus-" + record.id());
                return label != null && "Up to date".equals(label.getText()) ? label : null;
            });
            assertEquals("Up to date", updateStatus.getText());
            assertEquals(1, packageRequests.get());
            assertEquals(28, requests.get());
            var preference = new ChannelPreferenceStore()
                    .read(registry, services.readRegistry(), record).preference();
            assertEquals(FollowChannel.MILESTONE, preference.channel());
        } finally {
            dispose(frame);
        }
    }

    @Test
    void automaticMatchFailureIsSimpleDeduplicatedAndOnlyThenOffersChooser()
            throws Exception {
            Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                    "actual Swing controls require a display");
            Path root = versionedMegaMekSuite("adoption-failure");
            Path registry = temp.resolve("adoption-failure-registry.json");
            AtomicInteger requests = new AtomicInteger();
            ReleaseTransport missing = (uri, accept) -> {
                requests.incrementAndGet();
                return new ReleaseTransport.Response(404, Map.of(),
                        new java.io.ByteArrayInputStream(
                                "{\"message\":\"Not Found\"}".getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8)));
            };
            LauncherServices services = new LauncherServices(
                    registry, new RegistryStore(), new InstallationInspector(), missing,
                    new JavaRuntime(new RecordingRunner()),
                    new ApplicationLauncher(new RecordingRunner()));
            var record = services.register("Imported", root);
            LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
            try {
                onEdt(() -> {
                    frame.showWindow();
                    return null;
                });
                JButton manage = waitFor(() -> findButton(frame, "manageInstallationsButton"));
                onEdt(() -> {
                    manage.doClick();
                    return null;
                });
                JButton enable = waitFor(() -> findButton(frame,
                        "enableManagedUpdatesButton-" + record.id()));
                onEdt(() -> {
                    enable.doClick();
                    return null;
                });
                JDialog start = waitFor(() -> findDialog(frame, "adoptionCandidateDialog"));
                JButton proceed = (JButton) find(start, "continueAdoptionButton");
                onEdt(() -> {
                    proceed.doClick();
                    proceed.doClick();
                    return null;
                });

                JButton fallback = waitFor(() -> {
                    JButton button = (JButton) findOwned(frame,
                            "browseAfterAdoptionFailureButton");
                    return button != null && button.isShowing() && button.isEnabled()
                            ? button : null;
                });
                JLabel detail = waitFor(() -> (JLabel) findOwned(
                        frame, "operationProgressDetail"));
                assertTrue(detail.getText().contains(
                        "We couldn’t find the matching official version."));
                assertFalse(detail.getText().contains("404"));
                assertEquals(1, requests.get(), "Continue is single-flight");
                assertTrue(services.operationLogsForViewer().contains("HTTP 404"),
                        "technical reason remains in sanitized local diagnostics");

                onEdt(() -> {
                    fallback.doClick();
                    return null;
                });
                assertNotNull(waitFor(() -> findDialog(frame, "adoptionReleaseBrowser")));
                assertEquals(1, requests.get(),
                        "opening fallback does not fetch until its explicit Fetch versions action");
            } finally {
                dispose(frame);
        }
    }

    private LauncherServices services(Path registry, RecordingRunner runner) {
        return services(registry, runner,
                () -> QuickInstallTestData.snapshot("0.51.0", "0.52.0"));
    }

    private LauncherServices services(
            Path registry, RecordingRunner runner,
            Supplier<org.megamek.launcher.channel.QuickInstallSnapshot> snapshots) {
        ReleaseTransport noNetwork = new ReleaseTransport() {
            @Override
            public Response get(URI uri, String accept) {
                throw new AssertionError("existing import must not use the network");
            }
        };
        return new LauncherServices(
                registry, new RegistryStore(), new InstallationInspector(),
                noNetwork, new JavaRuntime(runner), new ApplicationLauncher(runner)) {
            @Override public org.megamek.launcher.channel.QuickInstallSnapshot
                    quickInstallSnapshot() {
                return snapshots.get();
            }
        };
    }

    private Path suite(String name, List<String> products) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        Map<String, String> jars = Map.of(
                "megamek", "MegaMek.jar",
                "mekhq", "MekHQ.jar",
                "lab", "MegaMekLab.jar");
        Map<String, String> mains = Map.of(
                "megamek", "megamek.MegaMek",
                "mekhq", "mekhq.MekHQ",
                "lab", "megameklab.MegaMekLab");
        for (String product : products) {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mains.get(product));
            try (JarOutputStream ignored = new JarOutputStream(
                    Files.newOutputStream(root.resolve(jars.get(product))), manifest)) {
            }
        }
        return root;
    }

    private Path versionedMegaMekSuite(String name) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        try (JarOutputStream jar = new JarOutputStream(
                Files.newOutputStream(root.resolve("MegaMek.jar")), manifest)) {
            jar.putNextEntry(new java.util.jar.JarEntry("megamek/Version.properties"));
            jar.write("major=0\nminor=50\npatch=7\n".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return root;
    }

    private AdoptionFixture adoptableMegaMekSuite(String name) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        byte[] jar = versionedMegaMekJar();
        byte[] dependency = {1, 2, 3, 4};
        Files.write(root.resolve("MegaMek.jar"), jar);
        Files.write(root.resolve("lib/library.jar"), dependency);
        Files.writeString(root.resolve("data/base.txt"), "official-data");
        Files.writeString(root.resolve("mmconf/user.cfg"), "local-setting");

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            addArchiveEntry(tar, "MegaMek-0.50.07/", null);
            addArchiveEntry(tar, "MegaMek-0.50.07/data/", null);
            addArchiveEntry(tar, "MegaMek-0.50.07/mmconf/", null);
            addArchiveEntry(tar, "MegaMek-0.50.07/lib/", null);
            addArchiveEntry(tar, "MegaMek-0.50.07/MegaMek.jar", jar);
            addArchiveEntry(tar, "MegaMek-0.50.07/lib/library.jar", dependency);
            addArchiveEntry(tar, "MegaMek-0.50.07/data/base.txt",
                    "official-data".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            addArchiveEntry(tar, "MegaMek-0.50.07/mmconf/user.cfg",
                    "official-setting".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new AdoptionFixture(root, bytes.toByteArray());
    }

    private static byte[] versionedMegaMekJar() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.putNextEntry(new java.util.jar.JarEntry("megamek/Version.properties"));
            jar.write("major=0\nminor=50\npatch=7\n".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void addArchiveEntry(
            TarArchiveOutputStream tar, String name, byte[] content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content == null ? 0 : content.length);
        if (content == null) entry.setMode(0755);
        tar.putArchiveEntry(entry);
        if (content != null) tar.write(content);
        tar.closeArchiveEntry();
    }

    private static ReleaseTransport automaticAdoptionTransport(
            byte[] archive, AtomicInteger requests, AtomicInteger packageRequests)
            throws Exception {
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(archive));
        String metadata = """
                {"tag_name":"v0.50.07","name":"Version 0.50.07","draft":false,
                "prerelease":false,
                "html_url":"https://github.com/MegaMek/megamek/releases/tag/v0.50.07",
                "assets":[{"name":"MegaMek-0.50.07.tar.gz","size":%d,
                "digest":"sha256:%s",
                "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/\
                v0.50.07/MegaMek-0.50.07.tar.gz"}]}
                """.formatted(archive.length, digest);
        Deque<java.util.function.Supplier<ReleaseTransport.Response>> responses =
                new ArrayDeque<>(List.of(
                        () -> org.megamek.launcher.channel.SuiteTestData.channels("0.50.07", "0.51.0"),
                        response("[" + metadata + "]"),
                        response(metadata),
                        response(metadata),
                        binaryResponse(archive),
                        () -> org.megamek.launcher.channel.SuiteTestData.channels("0.50.07", "0.51.0"),
                        response(metadata)));
        var metadataRouter = new org.megamek.launcher.channel.SuiteTestData.MetadataRouter();
        return (uri, accept) -> {
            requests.incrementAndGet();
            var generated = metadataRouter.response(uri, accept, responses.peekFirst());
            if (generated != null) return generated;
            if (uri.getPath().endsWith(".tar.gz")) packageRequests.incrementAndGet();
            var response = responses.pollFirst();
            if (response == null) throw new IOException("unexpected request: " + uri);
            return response.get();
        };
    }

    private static ReleaseTransport channelMismatchAdoptionTransport(
            byte[] archive, AtomicInteger requests, AtomicInteger packageRequests)
            throws Exception {
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(archive));
        String metadata = """
                {"tag_name":"v0.50.07","name":"Version 0.50.07","draft":false,
                "prerelease":false,
                "html_url":"https://github.com/MegaMek/megamek/releases/tag/v0.50.07",
                "assets":[{"name":"MegaMek-0.50.07.tar.gz","size":%d,
                "digest":"sha256:%s",
                "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/\
                v0.50.07/MegaMek-0.50.07.tar.gz"}]}
                """.formatted(archive.length, digest);
        Deque<java.util.function.Supplier<ReleaseTransport.Response>> responses =
                new ArrayDeque<>(List.of(
                        () -> org.megamek.launcher.channel.SuiteTestData.channels("0.50.07", "0.51.0"),
                        () -> org.megamek.launcher.channel.SuiteTestData.channels("0.50.07", "0.51.0"),
                        response("[" + metadata + "]"),
                        response(metadata),
                        response(metadata),
                        binaryResponse(archive),
                        () -> org.megamek.launcher.channel.SuiteTestData.channels("0.50.07", "0.51.0"),
                        response(metadata)));
        var metadataRouter = new org.megamek.launcher.channel.SuiteTestData.MetadataRouter();
        return (uri, accept) -> {
            requests.incrementAndGet();
            var generated = metadataRouter.response(uri, accept, responses.peekFirst());
            if (generated != null) return generated;
            if (uri.getPath().endsWith(".tar.gz")) packageRequests.incrementAndGet();
            var response = responses.pollFirst();
            if (response == null) throw new IOException("unexpected request: " + uri);
            return response.get();
        };
    }

    private static java.util.function.Supplier<ReleaseTransport.Response> response(String body) {
        return () -> new ReleaseTransport.Response(200, Map.of(),
                new java.io.ByteArrayInputStream(
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static java.util.function.Supplier<ReleaseTransport.Response> binaryResponse(
            byte[] body) {
        return () -> new ReleaseTransport.Response(200, Map.of(),
                new java.io.ByteArrayInputStream(body));
    }

    private static Map<Path, Long> inventory(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toMap(
                    root::relativize, path -> {
                        try {
                            return Files.size(path);
                        } catch (java.io.IOException error) {
                            throw new java.io.UncheckedIOException(error);
                        }
                    }));
        }
    }

    private static int countNamed(Container root, String name) {
        int count = 0;
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) count++;
            if (child instanceof Container nested) count += countNamed(nested, name);
        }
        return count;
    }

    private static Component find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                Component found = find(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JButton findButton(Container root, String name) {
        Component found = find(root, name);
        return found instanceof JButton button ? button : null;
    }

    private static int countButtonText(Container root, String text) {
        int count = 0;
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) count++;
            if (child instanceof Container nested) count += countButtonText(nested, text);
        }
        return count;
    }

    private static JDialog findDialog(LauncherFrame frame, String name) {
        for (java.awt.Window window : frame.getOwnedWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing()
                    && name.equals(dialog.getName())) return dialog;
        }
        return null;
    }

    private static Component findOwned(LauncherFrame frame, String name) {
        for (java.awt.Window window : frame.getOwnedWindows()) {
            Component found = find(window, name);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean hasShowingDialog(LauncherFrame frame, String title) {
        for (java.awt.Window window : frame.getOwnedWindows()) {
            if (window instanceof javax.swing.JDialog dialog && dialog.isShowing()
                    && title.equals(dialog.getTitle())) return true;
        }
        return false;
    }

    private static <T> T waitFor(java.util.function.Supplier<T> probe) throws Exception {
        return SwingTestSupport.await("import presentation", probe::get);
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        SwingTestSupport.awaitCondition("import state", condition::getAsBoolean);
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> action) throws Exception {
        return SwingTestSupport.onEdt(action);
    }

    private static void dispose(LauncherFrame frame) throws Exception {
        SwingTestSupport.dispose(frame);
    }

    private static final class RecordingRunner implements ProcessRunner {
        private final List<List<String>> commands =
                java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public Result run(List<String> command, Path workingDirectory, Duration timeout,
                          boolean inheritIo) {
            assertFalse(SwingUtilities.isEventDispatchThread(),
                    "any Java process must run off the EDT");
            commands.add(List.copyOf(command));
            return new Result(0, "openjdk version \"21.0.8\"", false);
        }

    }

    private record AdoptionFixture(Path root, byte[] archive) {
    }

    private static final class RecordingPrompts implements LauncherFrame.ExistingImportPrompts {
        private final Deque<Path> folders;
        private int folderRequests;

        private RecordingPrompts(List<Path> folders) {
            this.folders = new ArrayDeque<>(folders);
        }

        @Override
        public Path chooseFolder(Component parent) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            folderRequests++;
            return folders.isEmpty() ? null : folders.removeFirst();
        }
    }
}
