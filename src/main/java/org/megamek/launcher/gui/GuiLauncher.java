package org.megamek.launcher.gui;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.io.PrintStream;
import java.nio.file.Path;

public final class GuiLauncher {
    private GuiLauncher() {
    }

    public static int start(String[] args, PrintStream error) {
        Path registry;
        try {
            registry = registryArgument(args);
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
            new LauncherFrame(new LauncherServices(registry)).showWindow();
        });
        return 0;
    }

    static Path registryArgument(String[] args) {
        if (args.length == 1) return defaultRegistry();
        if (args.length == 3 && args[1].equals("--registry") && !args[2].isBlank()) {
            return Path.of(args[2]).toAbsolutePath().normalize();
        }
        throw new IllegalArgumentException("gui accepts only an optional --registry <path>");
    }

    public static Path defaultRegistry() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String base;
        if (os.contains("win") && System.getenv("LOCALAPPDATA") != null) {
            base = System.getenv("LOCALAPPDATA");
        } else if (os.contains("mac")) {
            base = Path.of(System.getProperty("user.home"), "Library", "Application Support").toString();
        } else {
            base = System.getenv("XDG_STATE_HOME");
            if (base == null || base.isBlank()) {
                return Path.of(System.getProperty("user.home"), ".megamek",
                        "launcher-registry.json").toAbsolutePath().normalize();
            }
        }
        return Path.of(base, "MegaMek", "launcher-registry.json").toAbsolutePath().normalize();
    }
}
