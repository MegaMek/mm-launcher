package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.plan.Action;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.update.CurrentUpdateState;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.OwnershipReceipt;
import org.megamek.launcher.update.PreparedUpdate;
import org.megamek.launcher.update.RealUpdateService;
import org.megamek.launcher.update.ReceiptStore;
import org.megamek.launcher.update.UpdatePreviewService;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateApplySwingTest {
    @TempDir Path temp;

    @Test
    void applyWorkflowBindsBothConsentsToPreviewSnapshotAndRefreshesAfterSuccess()
            throws Exception {
        Fixture fixture = fixture(false);
        LauncherFrame frame = onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            navigateToInstallations(frame);
            JButton update = waitFor(() -> find(frame, "applyUpdateButton"));
            assertTrue(update.isEnabled());

            Picker first = openPicker(frame, update);
            SwingUtilities.invokeLater(first.inspect()::doClick);
            JDialog firstConsent = waitForDialog("Confirm read-only preview download");
            String firstText = componentText(firstConsent);
            assertTrue(firstText.contains("READ-ONLY"));
            assertTrue(firstText.contains("v2.0.0"));
            click(firstConsent, "Cancel");
            waitUntil(() -> !firstConsent.isDisplayable());
            assertEquals(0, fixture.services.previewCalls);
            assertEquals(0, fixture.services.applyCalls);

            SwingUtilities.invokeLater(first.inspect()::doClick);
            JDialog secondFirstConsent = waitForDialog("Confirm read-only preview download");
            click(secondFirstConsent, "OK");
            JDialog applyConsent = waitForDialog("Authorize update Apply");
            String applyText = componentText(applyConsent);
            assertTrue(applyText.contains("destructive"));
            assertTrue(applyText.contains("CLOSE ALL"));
            assertTrue(applyText.contains(fixture.services.asset.digest()));
            assertTrue(applyText.contains("already downloaded"));
            assertFalse(applyText.contains("AGAIN"));
            assertTrue(applyText.contains("NOT be downloaded a second time"));
            click(applyConsent, "Cancel");
            waitUntil(() -> !applyConsent.isDisplayable());
            assertEquals(1, fixture.services.previewCalls);
            assertEquals(0, fixture.services.applyCalls,
                    "second-consent cancellation must perform zero Apply calls");
            waitUntil(update::isEnabled);
            Path metadata = fixture.services.registry().resolveSibling(
                    fixture.services.registry().getFileName() + ".metadata");
            try (var entries = Files.list(metadata)) {
                assertTrue(entries.noneMatch(path ->
                                path.getFileName().toString().startsWith(".preview-")),
                        "cancel after preparation must discard its exact workspace");
            }

            Picker finalPicker = openPicker(frame, update);
            SwingUtilities.invokeLater(finalPicker.inspect()::doClick);
            JDialog finalFirstConsent = waitForDialog("Confirm read-only preview download");
            click(finalFirstConsent, "OK");
            JDialog finalApplyConsent = waitForDialog("Authorize update Apply");

            fixture.services.selectNewPreference();
            click(finalApplyConsent, "OK");
            waitUntil(() -> fixture.services.applyCalls == 1);
            JDialog complete = waitForDialog("Update complete");
            assertTrue(componentText(complete).contains("Update completed"));
            click(complete, "OK");
            waitUntil(() -> fixture.services.loadHomeCalls >= 2);

            assertEquals(2, fixture.services.previewCalls);
            assertEquals(fixture.first, fixture.services.appliedRecord);
            assertEquals(fixture.firstCurrent, fixture.services.appliedState);
            assertEquals("v2.0.0", fixture.services.appliedTag);
            assertEquals(fixture.services.asset.size(), fixture.services.appliedSize);
            assertEquals(fixture.services.asset.digest(), fixture.services.appliedDigest);
            assertEquals(RealUpdateService.CONFIRM, fixture.services.appliedConfirmation);
            assertFalse(fixture.services.applyOnEdt, "Apply backend must run off the EDT");
            assertEquals(2, fixture.services.network.binaryRequests,
                    "each of the two accepted preparation attempts downloads one package");
            assertEquals(2L * fixture.services.archive.length,
                    fixture.services.network.binaryBytes);
            assertEquals("v2.0.0", new RealUpdateService(fixture.services.network)
                    .snapshot(fixture.services.registry(), fixture.first.id()).current().tag());
            assertEquals("runtime-2", Files.readString(
                    Path.of(fixture.first.canonicalRoot()).resolve("lib/runtime.txt")));
            assertEquals(fixture.second.id(),
                    fixture.services.currentHome().defaultInstallationId());
            waitUntil(() -> {
                JButton button = find(frame, "homeButton");
                return button != null && button.isEnabled();
            });
            JButton home = waitFor(() -> find(frame, "homeButton"));
            SwingUtilities.invokeAndWait(home::doClick);
            JLabel mainName = waitFor(() -> find(frame, "mainInstallationName"));
            assertEquals("New preference", mainName.getText());
        } finally {
            dispose(frame);
        }
    }

    @Test
    void recommendedGuiAttemptUsesOnePackageBodyAcrossPreviewAndApply() throws Exception {
        Fixture fixture = fixture(false);
        LauncherFrame frame = onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton check = waitFor(() -> find(frame, "checkUpdatesButton"));
            SwingUtilities.invokeAndWait(check::doClick);
            JButton recommended = waitFor(() -> find(frame, "recommendedUpdateButton"));

            SwingUtilities.invokeLater(recommended::doClick);
            JDialog firstConsent = waitForDialog("Confirm recommended preview download");
            assertTrue(componentText(firstConsent).contains("v2.0.0"));
            click(firstConsent, "Cancel");
            assertEquals(0, fixture.services.network.binaryRequests,
                    "cancel before preparation must fetch no package body");

            SwingUtilities.invokeLater(recommended::doClick);
            click(waitForDialog("Confirm recommended preview download"), "OK");
            JDialog applyConsent = waitForDialog("Authorize update Apply");
            String applyText = componentText(applyConsent);
            assertTrue(applyText.contains("already downloaded"));
            assertFalse(applyText.contains("AGAIN"));
            click(applyConsent, "OK");
            JDialog complete = waitForDialog("Update complete");
            assertTrue(componentText(complete).contains("Update completed"));
            click(complete, "OK");

            assertEquals(1, fixture.services.network.binaryRequests);
            assertEquals(fixture.services.archive.length,
                    fixture.services.network.binaryBytes);
            assertEquals(1, fixture.services.applyCalls);
            assertFalse(fixture.services.applyOnEdt);
            assertEquals("v2.0.0", fixture.services.appliedTag);
            RealUpdateService.Snapshot installed =
                    new RealUpdateService(fixture.services.network)
                            .snapshot(fixture.services.registry(), fixture.first.id());
            assertEquals("v2.0.0", installed.current().tag());
            assertEquals(fixture.first.canonicalRoot(), installed.current().canonicalRoot());
            assertEquals("runtime-2", Files.readString(
                    Path.of(fixture.first.canonicalRoot()).resolve("lib/runtime.txt")));
        } finally {
            dispose(frame);
        }
    }

    @Test
    void disposingFrameDuringPreparationCleansLatePreparedWorkspaceWithoutApply()
            throws Exception {
        Fixture fixture = fixture(false);
        fixture.services.network.blockNextBinary();
        LauncherFrame frame = onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            navigateToInstallations(frame);
            Picker picker = openPicker(frame, waitFor(() -> find(frame, "applyUpdateButton")));
            SwingUtilities.invokeLater(picker.inspect()::doClick);
            click(waitForDialog("Confirm read-only preview download"), "OK");
            assertTrue(fixture.services.network.binaryStarted.await(5, TimeUnit.SECONDS));

            SwingUtilities.invokeAndWait(frame::dispose);
            fixture.services.network.releaseBinary.countDown();
            Path metadata = fixture.services.registry().resolveSibling(
                    fixture.services.registry().getFileName() + ".metadata");
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            boolean clean = false;
            while (System.nanoTime() < deadline) {
                try (var entries = Files.list(metadata)) {
                    clean = entries.noneMatch(path ->
                            path.getFileName().toString().startsWith(".preview-"));
                }
                if (clean) break;
                Thread.sleep(20);
            }
            assertTrue(clean, "late preparation result must discard its exact workspace");
            Path logs = fixture.services.registry().resolveSibling(
                    fixture.services.registry().getFileName() + ".launcher-logs");
            boolean logged = false;
            long logDeadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (System.nanoTime() < logDeadline) {
                if (Files.isDirectory(logs)) {
                    try (var entries = Files.list(logs)) {
                        if (entries.anyMatch(path ->
                                path.getFileName().toString().startsWith("operation-"))) {
                            logged = true;
                            break;
                        }
                    }
                }
                Thread.sleep(20);
            }
            assertTrue(logged,
                    "disposed operation must finish its local terminal log before test cleanup");
            assertEquals(0, fixture.services.applyCalls);
            assertEquals(1, fixture.services.network.binaryRequests);
            assertEquals("runtime-1", Files.readString(
                    Path.of(fixture.first.canonicalRoot()).resolve("lib/runtime.txt")));
        } finally {
            fixture.services.network.releaseBinary.countDown();
            dispose(frame);
        }
    }

    @Test
    void recommendedChannelDriftAfterPreparationFailsWithoutSecondPackageOrRootWrite()
            throws Exception {
        Fixture fixture = fixture(false);
        LauncherFrame frame = onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton check = waitFor(() -> find(frame, "checkUpdatesButton"));
            SwingUtilities.invokeAndWait(check::doClick);
            JButton recommended = waitFor(() -> find(frame, "recommendedUpdateButton"));
            SwingUtilities.invokeLater(recommended::doClick);
            click(waitForDialog("Confirm recommended preview download"), "OK");
            JDialog applyConsent = waitForDialog("Authorize update Apply");

            new ChannelPreferenceStore().set(fixture.services.registry(), fixture.first,
                    FollowChannel.DEVELOPMENT, false);
            click(applyConsent, "OK");
            JDialog failed = waitForDialog("Update failed — recovery may be required");
            assertTrue(componentText(failed).contains("channel source")
                    || componentText(failed).contains("Channel check failed"));
            assertEquals(1, fixture.services.network.binaryRequests);
            assertEquals(fixture.services.archive.length,
                    fixture.services.network.binaryBytes);
            assertEquals("runtime-1", Files.readString(
                    Path.of(fixture.first.canonicalRoot()).resolve("lib/runtime.txt")));
            assertFalse(Files.exists(Path.of(fixture.first.canonicalRoot())
                    .resolve(RootCoordinator.UPDATE_NAMESPACE)));
        } finally {
            dispose(frame);
        }
    }

    @Test
    void applyFailureKeepsRecoveryReachableWhenInspectionStateIsUnavailable()
            throws Exception {
        Fixture fixture = fixture(true);
        fixture.services.failApply = true;
        LauncherFrame frame = onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            navigateToInstallations(frame);
            JButton update = waitFor(() -> find(frame, "applyUpdateButton"));
            Picker picker = openPicker(frame, update);
            SwingUtilities.invokeLater(picker.inspect()::doClick);
            click(waitForDialog("Confirm read-only preview download"), "OK");
            click(waitForDialog("Authorize update Apply"), "OK");
            waitUntil(() -> fixture.services.applyCalls == 1);

            JDialog failed = waitForDialog("Update failed — recovery may be required");
            assertTrue(componentText(failed).contains("UPDATE ATTEMPT FAILED"));
            assertTrue(componentText(failed).contains("recovery"));
            JButton recovery = waitFor(() -> find(frame, "recoverUpdateButton"));
            assertNotNull(recovery);
            assertTrue(recovery.isEnabled());
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(frame, "homeButton")).doClick());
            assertNotNull(find(frame, "preferredUnavailableTitle"));
        } finally {
            dispose(frame);
        }
    }

    private Fixture fixture(boolean unavailableInspection) throws Exception {
        Path firstRoot = createSuite(temp.resolve("first-root"), 1, "old");
        Path secondRoot = createSuite(temp.resolve("second-root"), 9, "other");
        Path registry = temp.resolve("fake-registry.json");
        RegistryStore store = new RegistryStore();
        InstallationRecord first = store.register(registry, "Original source", firstRoot, null);
        InstallationRecord second = store.register(registry, "New preference", secondRoot, null);
        store.select(registry, first.id());
        var firstBuild = new OwnershipPolicy().build(
                firstRoot, OfficialRepository.MEGAMEK, "v1.0.0");
        new ReceiptStore().write(registry, store.read(registry), first,
                OfficialRepository.MEGAMEK, "v1.0.0", "MegaMek-v1.0.0.tar.gz", 123,
                "sha256:" + "a".repeat(64), firstBuild);
        OwnershipReceipt firstReceipt =
                new ReceiptStore().read(registry, store.read(registry), first);
        var secondBuild = new OwnershipPolicy().build(
                secondRoot, OfficialRepository.MEGAMEK, "v-other");
        OwnershipReceipt secondReceipt = new OwnershipReceipt(1, OwnershipPolicy.VERSION,
                second.id(), second.canonicalRoot(), second.registeredAt(), "megamek", "v-other",
                "MegaMek-v-other.tar.gz", 456, "b".repeat(64), secondBuild.manifest(),
                secondBuild.excludedPaths());
        CurrentUpdateState firstCurrent = CurrentUpdateState.initial(firstReceipt);
        CurrentUpdateState secondCurrent = CurrentUpdateState.initial(secondReceipt);
        new ChannelPreferenceStore().set(registry, first, FollowChannel.MILESTONE, false);
        byte[] targetArchive = targetArchive();
        PreparedTransport transport = new PreparedTransport(targetArchive);
        FakeServices services = new FakeServices(registry, store, transport,
                first, second, first.products().getFirst(), firstReceipt, secondReceipt,
                firstCurrent, secondCurrent, unavailableInspection, targetArchive,
                new RootCoordinator(temp.resolve("swing-coordination")));
        return new Fixture(services, first, second, firstCurrent);
    }

    private static Picker openPicker(LauncherFrame frame, JButton update) throws Exception {
        SwingUtilities.invokeAndWait(update::doClick);
        JDialog picker = waitForDialog("Choose update");
        JButton fetch = find(picker, "fetchPreviewReleasesButton");
        SwingUtilities.invokeAndWait(fetch::doClick);
        JList<?> releases = waitFor(() -> find(picker, "previewReleaseList"));
        waitUntil(() -> releases.getModel().getSize() == 1);
        SwingUtilities.invokeAndWait(() -> releases.setSelectedIndex(0));
        JButton inspect = find(picker, "runPreviewButton");
        waitUntil(inspect::isEnabled);
        return new Picker(picker, inspect);
    }

    private static void navigateToInstallations(LauncherFrame frame) throws Exception {
        JButton installations = waitFor(() -> find(frame, "manageInstallationsButton"));
        SwingUtilities.invokeAndWait(installations::doClick);
        waitFor(() -> find(frame, "installationList"));
    }

    private static void click(Container dialog, String text) throws Exception {
        JButton button = findButtonText(dialog, text);
        assertNotNull(button, "missing dialog button " + text);
        SwingUtilities.invokeAndWait(button::doClick);
    }

    private static JDialog waitForDialog(String title) throws Exception {
        return waitFor(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()
                        && title.equals(dialog.getTitle())) {
                    return dialog;
                }
            }
            return null;
        });
    }

    private static <T> T waitFor(java.util.function.Supplier<T> probe) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            AtomicReference<T> value = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> value.set(probe.get()));
            if (value.get() != null) return value.get();
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

    private static JButton findButtonText(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) {
                JButton found = findButtonText(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String componentText(Container root) {
        StringBuilder result = new StringBuilder();
        appendText(root, result);
        return result.toString();
    }

    private static void appendText(Container root, StringBuilder result) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label) result.append(label.getText()).append('\n');
            if (child instanceof JTextArea area) result.append(area.getText()).append('\n');
            if (child instanceof Container nested) appendText(nested, result);
        }
    }

    private static <T> T onEdt(ThrowingSupplier<T> supplier) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(supplier.get());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw new RuntimeException(failure.get());
        return value.get();
    }

    private static void dispose(LauncherFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Window window : Window.getWindows()) {
                if (window == frame || window.getOwner() == frame
                        || window.getOwner() instanceof JDialog) {
                    window.dispose();
                }
            }
            frame.dispose();
        });
    }

    private static Path createSuite(Path root, int version, String document) throws Exception {
        Files.createDirectory(root);
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("docs"));
        Files.write(root.resolve("MegaMek.jar"), jar(version));
        Files.writeString(root.resolve("lib/runtime.txt"), "runtime-" + version);
        Files.writeString(root.resolve("data/default.txt"), "data-" + version);
        Files.writeString(root.resolve("mmconf/clientsettings.xml"), "protected-" + version);
        Files.writeString(root.resolve("docs/change.txt"), document);
        return root;
    }

    private static byte[] targetArchive() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("MegaMek.jar", jar(2));
        files.put("lib/runtime.txt", "runtime-2".getBytes(StandardCharsets.UTF_8));
        files.put("data/default.txt", "data-2".getBytes(StandardCharsets.UTF_8));
        files.put("mmconf/clientsettings.xml", "target-protected".getBytes(StandardCharsets.UTF_8));
        files.put("docs/change.txt", "new".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.putArchiveEntry(new TarArchiveEntry("MegaMek-v-target/"));
            tar.closeArchiveEntry();
            for (Map.Entry<String, byte[]> item : files.entrySet()) {
                TarArchiveEntry entry =
                        new TarArchiveEntry("MegaMek-v-target/" + item.getKey());
                entry.setSize(item.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(item.getValue());
                tar.closeArchiveEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] jar(int version) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output, manifest)) {
            jar.putNextEntry(new JarEntry("megamek/Version.properties"));
            jar.write(("major=" + version + "\nminor=0\npatch=0\n")
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return output.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Picker(JDialog dialog, JButton inspect) {
    }

    private record Fixture(FakeServices services, InstallationRecord first,
                           InstallationRecord second, CurrentUpdateState firstCurrent) {
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class FakeServices extends LauncherServices {
        private final ReleaseCatalog.Asset asset;
        private final ReleaseCatalog.Release release;
        private final PreparedTransport network;
        private final byte[] archive;
        private final RegistryStore registryStore;
        private final InstallationRecord first;
        private final InstallationRecord second;
        private final Product product;
        private final OwnershipReceipt firstReceipt;
        private final OwnershipReceipt secondReceipt;
        private final CurrentUpdateState firstCurrent;
        private final CurrentUpdateState secondCurrent;
        private final boolean unavailableInspection;
        private volatile InstallationRecord preferred;
        private volatile int loadHomeCalls;
        private volatile int previewCalls;
        private volatile int applyCalls;
        private volatile boolean failApply;
        private volatile InstallationRecord appliedRecord;
        private volatile CurrentUpdateState appliedState;
        private volatile String appliedTag;
        private volatile long appliedSize;
        private volatile String appliedDigest;
        private volatile String appliedConfirmation;
        private volatile boolean applyOnEdt;

        private FakeServices(Path registry, RegistryStore store, PreparedTransport network,
                             InstallationRecord first, InstallationRecord second,
                             Product product, OwnershipReceipt firstReceipt,
                             OwnershipReceipt secondReceipt, CurrentUpdateState firstCurrent,
                             CurrentUpdateState secondCurrent, boolean unavailableInspection,
                             byte[] archive, RootCoordinator coordinator) {
            super(registry, store, new InstallationInspector(), network, new JavaRuntime(),
                    new ApplicationLauncher(), coordinator);
            this.network = network;
            this.archive = archive;
            this.registryStore = store;
            try {
                this.asset = new ReleaseCatalog.Asset("MegaMek-v2.0.0.tar.gz", archive.length,
                        "sha256:" + sha(archive),
                        URI.create("https://github.com/MegaMek/megamek/releases/download/"
                                + "v2.0.0/MegaMek-v2.0.0.tar.gz"));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            this.release = new ReleaseCatalog.Release(
                    "v2.0.0", "Target", false, false,
                    URI.create("https://github.com/MegaMek/megamek/releases/tag/v2.0.0"),
                    List.of(asset));
            this.first = first;
            this.second = second;
            this.product = product;
            this.firstReceipt = firstReceipt;
            this.secondReceipt = secondReceipt;
            this.firstCurrent = firstCurrent;
            this.secondCurrent = secondCurrent;
            this.unavailableInspection = unavailableInspection;
            this.preferred = first;
        }

        @Override
        public HomeState loadHome() throws IOException {
            loadHomeCalls++;
            InstallationRecord record = preferred;
            OwnershipReceipt receipt = record.equals(first) ? firstReceipt : secondReceipt;
            CurrentUpdateState current = record.equals(first) ? firstCurrent : secondCurrent;
            RegistryData registry = currentHome();
            Inspection inspection = unavailableInspection ? null
                    : new Inspection(record.canonicalRoot(), List.of(product),
                    record.observedBuild(), "fixture");
            ChannelPreferenceStore.ReadResult channel =
                    new ChannelPreferenceStore().read(registry(), registry, record);
            return new HomeState(registry, record, inspection,
                    unavailableInspection ? "Fixture inspection unavailable" : null,
                    new UpdatePreviewService.Eligibility(record, true,
                            "Verified update provenance is valid", receipt, current), false,
                    channel);
        }

        private RegistryData currentHome() {
            return new RegistryData(RegistryStore.SCHEMA, preferred.id(),
                    List.of(first, second));
        }

        private void selectNewPreference() {
            try {
                registryStore.select(registry(), second.id());
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
            preferred = second;
        }

        @Override
        public ReleaseCatalog.Page releases(OfficialRepository repository, int page) {
            return new ReleaseCatalog.Page(1, 10, List.of(release), false);
        }

        @Override
        public ReleaseCatalog.Assessment assess(OfficialRepository repository,
                                                 ReleaseCatalog.Release ignored) {
            return new ReleaseCatalog.Assessment(true, asset, "Available");
        }

        @Override
        public PreparedUpdate prepareUpdate(
                InstallationRecord record, OwnershipReceipt receipt,
                CurrentUpdateState current, String tag, String assetName,
                long size, String digest, PrintStream progress)
                throws IOException, InterruptedException,
                org.megamek.launcher.manifest.ManifestException {
            previewCalls++;
            return super.prepareUpdate(
                    record, receipt, current, tag, assetName, size, digest, progress);
        }

        @Override
        public PreparedUpdate prepareUpdate(
                InstallationRecord record, OwnershipReceipt receipt,
                CurrentUpdateState current, String tag, String assetName,
                long size, String digest, PrintStream progress, OperationContext context)
                throws IOException, InterruptedException,
                org.megamek.launcher.manifest.ManifestException {
            previewCalls++;
            return super.prepareUpdate(
                    record, receipt, current, tag, assetName, size, digest, progress, context);
        }

        @Override
        public PreparedUpdate prepareRecommended(
                InstallationRecord record, OwnershipReceipt receipt,
                CurrentUpdateState current,
                ChannelUpdateChecker.Result expected, PrintStream progress)
                throws IOException, InterruptedException,
                org.megamek.launcher.manifest.ManifestException {
            previewCalls++;
            return super.prepareRecommended(record, receipt, current, expected, progress);
        }

        @Override
        public PreparedUpdate prepareRecommended(
                InstallationRecord record, OwnershipReceipt receipt,
                CurrentUpdateState current,
                ChannelUpdateChecker.Result expected, PrintStream progress,
                OperationContext context)
                throws IOException, InterruptedException,
                org.megamek.launcher.manifest.ManifestException {
            previewCalls++;
            return super.prepareRecommended(
                    record, receipt, current, expected, progress, context);
        }

        @Override
        public RealUpdateService.ApplyResult applyPrepared(
                PreparedUpdate prepared, String confirmation, PrintStream progress)
                throws IOException, InterruptedException,
                org.megamek.launcher.manifest.ManifestException {
            applyCalls++;
            UpdatePreviewService.Preview preview = prepared.preview();
            appliedRecord = preview.record();
            appliedState = preview.currentState();
            appliedTag = preview.targetRelease().tag();
            appliedSize = preview.targetAsset().size();
            appliedDigest = preview.targetAsset().digest();
            appliedConfirmation = confirmation;
            applyOnEdt = SwingUtilities.isEventDispatchThread();
            if (failApply) {
                prepared.close();
                throw new IOException("simulated apply failure");
            }
            return super.applyPrepared(prepared, confirmation, progress);
        }

        @Override
        public RealUpdateService.ApplyResult applyPrepared(
                PreparedUpdate prepared, String confirmation, PrintStream progress,
                OperationContext context)
                throws IOException, InterruptedException,
                org.megamek.launcher.manifest.ManifestException {
            applyCalls++;
            UpdatePreviewService.Preview preview = prepared.preview();
            appliedRecord = preview.record();
            appliedState = preview.currentState();
            appliedTag = preview.targetRelease().tag();
            appliedSize = preview.targetAsset().size();
            appliedDigest = preview.targetAsset().digest();
            appliedConfirmation = confirmation;
            applyOnEdt = SwingUtilities.isEventDispatchThread();
            if (failApply) {
                prepared.close();
                throw new IOException("simulated apply failure");
            }
            return super.applyPrepared(prepared, confirmation, progress, context);
        }
    }

    private static final class PreparedTransport implements ReleaseTransport {
        private final byte[] archive;
        private final String digest;
        private volatile int metadataRequests;
        private volatile int binaryRequests;
        private volatile long binaryBytes;
        private volatile boolean blockBinary;
        private final CountDownLatch binaryStarted = new CountDownLatch(1);
        private final CountDownLatch releaseBinary = new CountDownLatch(1);

        private PreparedTransport(byte[] archive) throws Exception {
            this.archive = archive;
            this.digest = sha(archive);
        }

        @Override
        public Response get(URI uri, String accept) throws IOException, InterruptedException {
            if ("application/octet-stream".equals(accept)) {
                assertFalse(SwingUtilities.isEventDispatchThread(),
                        "package transfer must not run on EDT");
                binaryRequests++;
                binaryStarted.countDown();
                if (blockBinary) releaseBinary.await();
                return new Response(200,
                        Map.of("content-length", List.of(Integer.toString(archive.length))),
                        new ByteArrayInputStream(archive) {
                            @Override
                            public synchronized int read(byte[] buffer, int offset, int length) {
                                int count = super.read(buffer, offset, length);
                                if (count > 0) binaryBytes += count;
                                return count;
                            }

                            @Override
                            public synchronized int read() {
                                int value = super.read();
                                if (value >= 0) binaryBytes++;
                                return value;
                            }
                        });
            }
            metadataRequests++;
            if (accept.contains("yaml")) {
                byte[] yaml = "stable: 2.0.0\ndev: 2.1.0\n"
                        .getBytes(StandardCharsets.UTF_8);
                return new Response(200,
                        Map.of("content-length", List.of(Integer.toString(yaml.length))),
                        new ByteArrayInputStream(yaml));
            }
            String json = """
                    {"tag_name":"v2.0.0","name":"Target","draft":false,"prerelease":false,
                    "html_url":"https://github.com/MegaMek/megamek/releases/tag/v2.0.0",
                    "assets":[{"name":"MegaMek-v2.0.0.tar.gz","size":%d,
                    "digest":"sha256:%s",
                    "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/v2.0.0/MegaMek-v2.0.0.tar.gz"}]}
                    """.formatted(archive.length, digest);
            return new Response(200, Map.of(),
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        }

        private void blockNextBinary() {
            blockBinary = true;
        }
    }
}
