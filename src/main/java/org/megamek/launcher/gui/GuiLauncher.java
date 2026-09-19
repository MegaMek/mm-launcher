package org.megamek.launcher.gui;

import org.megamek.launcher.onboarding.PlatformInstallLocations;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class GuiLauncher {
    private GuiLauncher() {
    }

    public static int start(String[] args, PrintStream error) {
        RegistrySelection selection;
        try {
            selection = registrySelection(args);
        } catch (IllegalArgumentException e) {
            error.println("ERROR: " + e.getMessage());
            error.println("Usage: mm-launcher gui [--registry <path>]");
            return 2;
        }
        if (GraphicsEnvironment.isHeadless()) {
            error.println("ERROR: the graphical launcher cannot start in a headless environment");
            return 2;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ignored) {
                // The cross-platform Swing look and feel remains usable.
            }
            PlatformInstallLocations installLocations = selection.defaultRegistry()
                    ? PlatformInstallLocations.system(selection.registry())
                    : PlatformInstallLocations.customRegistry();
            new LauncherFrame(new LauncherServices(
                    selection.registry(), installLocations)).showWindow();
        });
        return 0;
    }

    static Path registryArgument(String[] args) {
        return registrySelection(args).registry();
    }

    private static RegistrySelection registrySelection(String[] args) {
        if (args.length == 1) return new RegistrySelection(defaultRegistry(), true);
        if (args.length == 3 && args[1].equals("--registry") && !args[2].isBlank()) {
            return new RegistrySelection(
                    Path.of(args[2]).toAbsolutePath().normalize(), false);
        }
        throw new IllegalArgumentException("gui accepts only an optional --registry <path>");
    }

    public static Path defaultRegistry() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String base;
        if (os.contains("win") && System.getenv("LOCALAPPDATA") != null) {
            base = System.getenv("LOCALAPPDATA");
        } else if (os.contains("mac")) {
            base = absoluteUserHome().resolve("Library")
                    .resolve("Application Support").toString();
        } else {
            base = System.getenv("XDG_STATE_HOME");
            if (base == null || base.isBlank()) {
                return absoluteUserHome().resolve(".megamek")
                        .resolve("launcher-registry.json").toAbsolutePath().normalize();
            }
        }
        return Path.of(base, "MegaMek", "launcher-registry.json").toAbsolutePath().normalize();
    }

    private static Path absoluteUserHome() {
        String value = System.getProperty("user.home");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "user.home must be a nonblank absolute path");
        }
        try {
            Path home = Path.of(value);
            if (!home.isAbsolute()) {
                throw new IllegalArgumentException(
                        "user.home must be a nonblank absolute path");
            }
            return home.normalize();
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException(
                    "user.home must be a valid absolute path", error);
        }
    }

    private record RegistrySelection(Path registry, boolean defaultRegistry) {
    }
}
