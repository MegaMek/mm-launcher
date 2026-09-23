package org.megamek.launcher.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Applies a downloaded launcher update by handing off to Windows Installer
 * instead of touching the launcher's own running files.
 *
 * <p>The launcher cannot replace its own executable, JAR, or bundled runtime
 * while its own process still holds those files open; on Windows, an
 * in-process file replace of a running executable simply fails. Instead this
 * class starts {@code msiexec} as an independent process against a downloaded
 * MSI built with the launcher's fixed, permanent upgrade code (see
 * {@code build.gradle.kts}), then asks the caller to exit the current JVM
 * immediately. Once the launcher process is gone, {@code msiexec} performs
 * its normal in-place upgrade of the per-user install.</p>
 *
 * <p>This class only starts the child process and never waits for it to
 * finish; by design, the launcher must already be gone before the upgrade's
 * file replacement step begins.</p>
 */
public final class LauncherSelfUpdateInstaller {

    private final ProcessStarter processStarter;

    public LauncherSelfUpdateInstaller() {
        this(LauncherSelfUpdateInstaller::startMsiExec);
    }

    LauncherSelfUpdateInstaller(ProcessStarter processStarter) {
        this.processStarter = processStarter;
    }

    /**
     * Starts the silent, per-user in-place MSI upgrade and requests the
     * launcher to exit.
     *
     * @param msiPath the exact absolute path to the downloaded,
     *                already checksum-verified MSI package
     * @param exit     invoked exactly once, with exit code {@code 0}, after
     *                 the updater process has started; the caller must exit
     *                 the JVM in response and must not perform further file
     *                 I/O on the launcher's own installed files afterwards
     * @throws IOException if the package is missing, is not an {@code .msi}
     *                      file, or the updater process could not be started
     */
    public void applyAndExit(Path msiPath, IntConsumer exit) throws IOException {
        Path absolute = msiPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IOException("Downloaded update package is missing at " + absolute);
        }
        String lowerCaseName = absolute.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lowerCaseName.endsWith(".msi")) {
            throw new IOException("Downloaded update package must be an .msi file: " + absolute);
        }
        processStarter.start(absolute);
        exit.accept(0);
    }

    private static void startMsiExec(Path msiPath) throws IOException {
        // /qn: fully silent, no dialogs. /norestart: never reboot the user's
        // machine unattended. The per-user MSI (built with
        // --win-per-user-install) never needs an elevation prompt, so this
        // can run unattended from a normal user-level launcher process.
        new ProcessBuilder(
                "msiexec.exe", "/i", msiPath.toString(), "/qn", "/norestart"
        )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    @FunctionalInterface
    interface ProcessStarter {
        void start(Path msiPath) throws IOException;
    }
}
