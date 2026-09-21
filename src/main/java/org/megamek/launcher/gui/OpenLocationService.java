package org.megamek.launcher.gui;

import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.update.StrictPathSafety;

import java.awt.Desktop;
import java.awt.EventQueue;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Exact-record, off-EDT boundary for opening an installation in the platform file manager. */
public final class OpenLocationService {
    private final Path registry;
    private final RegistryStore registries;
    private final InstallationInspector inspector;
    private final DesktopGateway desktop;

    public OpenLocationService(Path registry) {
        this(registry, new RegistryStore(), new InstallationInspector(),
                new AwtDesktopGateway());
    }

    OpenLocationService(Path registry, RegistryStore registries,
                        InstallationInspector inspector, DesktopGateway desktop) {
        this.registry = registry.toAbsolutePath().normalize();
        this.registries = Objects.requireNonNull(registries);
        this.inspector = Objects.requireNonNull(inspector);
        this.desktop = Objects.requireNonNull(desktop);
    }

    public void open(InstallationRecord expected) throws IOException {
        if (EventQueue.isDispatchThread()) {
            throw new IOException("Open location must run off the UI thread");
        }
        RegistryData data = registries.read(registry);
        InstallationRecord current = registries.resolve(data, expected.id());
        if (!current.equals(expected)) {
            throw new IOException("selected installation changed; reopen Installations");
        }
        Path root = StrictPathSafety.requireDirectory(Path.of(current.canonicalRoot()),
                "installation folder");
        Inspection observed = inspector.inspect(root);
        if (!observed.canonicalRoot().equals(current.canonicalRoot())
                || !observed.observedBuild().equals(current.observedBuild())
                || !observed.products().equals(current.products())) {
            throw new IOException("selected installation changed; reopen Installations");
        }
        if (!desktop.supported()) {
            throw new IOException("Opening folders is not supported on this platform");
        }
        desktop.open(root);
    }

    interface DesktopGateway {
        boolean supported();

        void open(Path directory) throws IOException;
    }

    private static final class AwtDesktopGateway implements DesktopGateway {
        @Override
        public boolean supported() {
            return Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN);
        }

        @Override
        public void open(Path directory) throws IOException {
            Desktop.getDesktop().open(directory.toFile());
        }
    }
}
