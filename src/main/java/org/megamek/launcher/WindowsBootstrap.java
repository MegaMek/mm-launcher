package org.megamek.launcher;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Minimal Java bridge embedded by Launch4j. Launch4j changes to the executable
 * directory before this code resolves the one shared lib payload inside the
 * sibling macOS app bundle.
 */
public final class WindowsBootstrap {
    private static final Path SHARED_LIB = Path.of(
            "MegaMek Launcher.app", "Contents", "app", "lib");

    private WindowsBootstrap() {
    }

    public static void main(String[] args) {
        try {
            Path lib = SHARED_LIB.toAbsolutePath().normalize();
            if (!Files.isDirectory(lib)) {
                throw new IllegalStateException(
                        "MegaMek Launcher shared payload is missing at " + lib + ".");
            }
            URL[] jars;
            try (var files = Files.list(lib)) {
                jars = files
                        .filter(path -> path.getFileName().toString()
                                .toLowerCase().endsWith(".jar"))
                        .sorted(Comparator.comparing(path ->
                                path.getFileName().toString().toLowerCase()))
                        .map(WindowsBootstrap::url)
                        .toArray(URL[]::new);
            }
            if (jars.length == 0) {
                throw new IllegalStateException(
                        "MegaMek Launcher shared payload at " + lib
                                + " contains no application JARs.");
            }

            URLClassLoader loader = new URLClassLoader(jars,
                    ClassLoader.getPlatformClassLoader());
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> desktop = Class.forName(
                    "org.megamek.launcher.DesktopLauncher", true, loader);
            Method main = desktop.getMethod("main", String[].class);
            main.invoke(null, (Object) Arrays.copyOf(args, args.length));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            cause.printStackTrace(System.err);
            System.exit(1);
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException("invalid lib payload path", e);
        }
    }
}
