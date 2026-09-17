package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.onboarding.Product;
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
import org.megamek.launcher.update.RealUpdateService;
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
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

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
            JButton update = waitFor(() -> find(frame, "applyUpdateButton"));
            assertTrue(update.isEnabled());

            Picker first = openPicker(frame, update);
            SwingUtilities.invokeLater(first.inspect()::doClick);
            JDialog firstConsent = waitForDialog("Confirm read-only preview download");
            String firstText = componentText(firstConsent);
            assertTrue(firstText.contains("READ-ONLY"));
            assertTrue(firstText.contains("v-target"));
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
            assertTrue(applyText.contains("987,654") || applyText.contains("987654"));
            click(applyConsent, "Cancel");
            waitUntil(() -> !applyConsent.isDisplayable());
            assertEquals(1, fixture.services.previewCalls);
            assertEquals(0, fixture.services.applyCalls,
                    "second-consent cancellation must perform zero Apply calls");

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
            assertEquals("v-target", fixture.services.appliedTag);
            assertEquals(fixture.services.asset.size(), fixture.services.appliedSize);
            assertEquals(fixture.services.asset.digest(), fixture.services.appliedDigest);
            assertEquals(RealUpdateService.CONFIRM, fixture.services.appliedConfirmation);
            assertFalse(fixture.services.applyOnEdt, "Apply backend must run off the EDT");
            assertEquals(fixture.second.id(),
                    fixture.services.currentHome().defaultInstallationId());
            waitUntil(() -> componentText(frame).contains("New preference"));
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
            JButton update = waitFor(() -> find(frame, "applyUpdateButton"));
            Picker picker = openPicker(frame, update);
            SwingUtilities.invokeLater(picker.inspect()::doClick);
            click(waitForDialog("Confirm read-only preview download"), "OK");
            click(waitForDialog("Authorize update Apply"), "OK");
            waitUntil(() -> fixture.services.applyCalls == 1);

            JDialog failed = waitForDialog("Update failed — recovery may be required");
            assertTrue(componentText(failed).contains("APPLY FAILED"));
            assertTrue(componentText(failed).contains("recovery"));
            JButton recovery = waitFor(() -> find(frame, "recoverUpdateButton"));
            assertNotNull(recovery);
            assertTrue(recovery.isEnabled());
            assertNotNull(find(frame, "preferredUnavailableTitle"));
        } finally {
            dispose(frame);
        }
    }

    private Fixture fixture(boolean unavailableInspection) throws Exception {
        Path firstRoot = Files.createDirectory(temp.resolve("first-root"));
        Path secondRoot = Files.createDirectory(temp.resolve("second-root"));
        Product product = new Product("megamek", "MegaMek.jar", "megamek.MegaMek",
                "1.0.0", List.of());
        InstallationRecord first = new InstallationRecord("first-id", "Original source",
                firstRoot.toString(), "1.0.0", List.of(product), null, null, false,
                "2026-01-01T00:00:00Z");
        InstallationRecord second = new InstallationRecord("second-id", "New preference",
                secondRoot.toString(), "9.0.0", List.of(product), null, null, false,
                "2026-02-01T00:00:00Z");
        Manifest manifest = new Manifest(1, "megamek", "fixture", "v-source", List.of(),
                List.of(new FileEntry("MegaMek.jar", "a".repeat(64))));
        OwnershipReceipt firstReceipt = new OwnershipReceipt(1, OwnershipPolicy.VERSION,
                first.id(), first.canonicalRoot(), first.registeredAt(), "megamek", "v-source",
                "MegaMek-v-source.tar.gz", 123, "a".repeat(64), manifest, List.of());
        OwnershipReceipt secondReceipt = new OwnershipReceipt(1, OwnershipPolicy.VERSION,
                second.id(), second.canonicalRoot(), second.registeredAt(), "megamek", "v-new",
                "MegaMek-v-new.tar.gz", 456, "b".repeat(64), manifest, List.of());
        CurrentUpdateState firstCurrent = CurrentUpdateState.initial(firstReceipt);
        CurrentUpdateState secondCurrent = CurrentUpdateState.initial(secondReceipt);
        FakeServices services = new FakeServices(temp.resolve("fake-registry.json"),
                first, second, product, firstReceipt, secondReceipt, firstCurrent, secondCurrent,
                unavailableInspection);
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
        private final ReleaseCatalog.Asset asset = new ReleaseCatalog.Asset(
                "MegaMek-v-target.tar.gz", 987_654, "sha256:" + "c".repeat(64),
                URI.create("https://github.com/MegaMek/megamek/releases/download/v-target/"
                        + "MegaMek-v-target.tar.gz"));
        private final ReleaseCatalog.Release release = new ReleaseCatalog.Release(
                "v-target", "Target", false, false,
                URI.create("https://github.com/MegaMek/megamek/releases/tag/v-target"),
                List.of(asset));
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

        private FakeServices(Path registry, InstallationRecord first, InstallationRecord second,
                             Product product, OwnershipReceipt firstReceipt,
                             OwnershipReceipt secondReceipt, CurrentUpdateState firstCurrent,
                             CurrentUpdateState secondCurrent, boolean unavailableInspection) {
            super(registry, new RegistryStore(), new InstallationInspector(),
                    new NoNetworkTransport(), new JavaRuntime(), new ApplicationLauncher());
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
        public HomeState loadHome() {
            loadHomeCalls++;
            InstallationRecord record = preferred;
            OwnershipReceipt receipt = record.equals(first) ? firstReceipt : secondReceipt;
            CurrentUpdateState current = record.equals(first) ? firstCurrent : secondCurrent;
            RegistryData registry = currentHome();
            Inspection inspection = unavailableInspection ? null
                    : new Inspection(record.canonicalRoot(), List.of(product),
                    record.observedBuild(), "fixture");
            return new HomeState(registry, record, inspection,
                    unavailableInspection ? "Fixture inspection unavailable" : null,
                    new UpdatePreviewService.Eligibility(record, true,
                            "Verified update provenance is valid", receipt, current), false);
        }

        private RegistryData currentHome() {
            return new RegistryData(RegistryStore.SCHEMA, preferred.id(),
                    List.of(first, second));
        }

        private void selectNewPreference() {
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
        public UpdatePreviewService.Preview previewUpdate(
                InstallationRecord record, OwnershipReceipt receipt, String tag,
                PrintStream progress) {
            previewCalls++;
            CurrentUpdateState current = record.equals(first) ? firstCurrent : secondCurrent;
            EnumMap<Action, Long> counts = new EnumMap<>(Action.class);
            for (Action action : Action.values()) counts.put(action, 0L);
            counts.put(Action.ADD, 1L);
            counts.put(Action.REPLACE, 2L);
            counts.put(Action.REMOVE, 3L);
            counts.put(Action.SKIP, 4L);
            return new UpdatePreviewService.Preview(record, receipt, release, asset,
                    receipt.officialManifest(), receipt.excludedOfficialPaths(), List.of(),
                    Map.copyOf(counts), current);
        }

        @Override
        public RealUpdateService.ApplyResult applyUpdate(
                InstallationRecord expectedRecord, CurrentUpdateState expectedState,
                String tag, long size, String digest, String confirmation, PrintStream progress)
                throws IOException {
            applyCalls++;
            appliedRecord = expectedRecord;
            appliedState = expectedState;
            appliedTag = tag;
            appliedSize = size;
            appliedDigest = digest;
            appliedConfirmation = confirmation;
            applyOnEdt = SwingUtilities.isEventDispatchThread();
            if (failApply) throw new IOException("simulated apply failure");
            return new RealUpdateService.ApplyResult(expectedRecord, expectedState,
                    List.of(), 0, 0);
        }
    }

    private static final class NoNetworkTransport implements ReleaseTransport {
        @Override
        public Response get(URI uri, String accept) throws IOException {
            throw new IOException("network is forbidden in this Swing test");
        }
    }
}
