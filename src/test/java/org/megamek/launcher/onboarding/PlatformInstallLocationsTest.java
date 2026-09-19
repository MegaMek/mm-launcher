package org.megamek.launcher.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformInstallLocationsTest {
    @TempDir Path temp;

    @Test
    void windowsDefaultRegistryUsesLocalApplicationDataAndProductChannelChild()
            throws Exception {
        Path localAppData = temp.resolve("LocalAppData").toAbsolutePath();
        Path registry = temp.resolve("state/windows-registry.json").toAbsolutePath();
        PlatformInstallLocations locations = locations("Windows 11",
                Map.of("LOCALAPPDATA", localAppData.toString()),
                temp.resolve("home"), registry);

        Path destination = locations.destination(registry, "MekHQ Milestone");

        assertEquals(localAppData.resolve("MegaMek").resolve("MekHQ Milestone"),
                destination);
        assertFalse(Files.exists(destination));
    }

    @Test
    void macDefaultRegistryUsesApplicationSupportForManagedPayloadData()
            throws Exception {
        Path home = temp.resolve("Users/example").toAbsolutePath();
        Path registry = temp.resolve("state/mac-registry.json").toAbsolutePath();
        PlatformInstallLocations locations =
                locations("Mac OS X", Map.of(), home, registry);

        Path destination = locations.destination(registry, "MegaMek Development");

        assertEquals(home.resolve("Library").resolve("Application Support")
                .resolve("MegaMek").resolve("MegaMek Development"), destination);
        assertFalse(Files.exists(destination));
    }

    @Test
    void linuxUsesAbsoluteXdgDataHomeAndFallsBackForRelativeValue() throws Exception {
        Path home = temp.resolve("home/example").toAbsolutePath();
        Path xdgData = temp.resolve("xdg-data").toAbsolutePath();
        Path registry = temp.resolve("state/linux-registry.json").toAbsolutePath();

        PlatformInstallLocations configured = locations("Linux",
                Map.of("XDG_DATA_HOME", xdgData.toString()), home, registry);
        assertEquals(xdgData.resolve("MegaMek").resolve("MegaMekLab Milestone"),
                configured.destination(registry, "MegaMekLab Milestone"));

        PlatformInstallLocations relativeIgnored = locations("Linux",
                Map.of("XDG_DATA_HOME", "relative/data"), home, registry);
        assertEquals(home.resolve(".local").resolve("share").resolve("MegaMek")
                        .resolve("MegaMekLab Milestone"),
                relativeIgnored.destination(registry, "MegaMekLab Milestone"));
    }

    @Test
    void customRegistryRetainsDisposableInstallationsRootWithoutPlatformRequirements()
            throws Exception {
        Path defaultRegistry = temp.resolve("state/default.json").toAbsolutePath();
        Path customRegistry = temp.resolve("isolated/custom.json").toAbsolutePath();
        PlatformInstallLocations missingWindowsEnvironment =
                locations("Windows 11", Map.of(), temp.resolve("home"), defaultRegistry);

        assertEquals(customRegistry.getParent().resolve("installations")
                        .resolve("MekHQ Milestone"),
                missingWindowsEnvironment.destination(
                        customRegistry, "MekHQ Milestone"));
        assertEquals(defaultRegistry.getParent().resolve("installations")
                        .resolve("MekHQ Milestone"),
                PlatformInstallLocations.customRegistry().destination(
                        defaultRegistry, "MekHQ Milestone"));
    }

    @Test
    void windowsDefaultPathRejectsMissingOrRelativeLocalAppData() {
        Path home = temp.resolve("home").toAbsolutePath();
        Path registry = temp.resolve("state/default.json").toAbsolutePath();

        IOException missing = assertThrows(IOException.class,
                () -> locations("Windows 11", Map.of(), home, registry)
                        .destination(registry, "MekHQ Milestone"));
        assertTrue(missing.getMessage().contains("LOCALAPPDATA"));

        IOException relative = assertThrows(IOException.class,
                () -> locations("Windows 11",
                        Map.of("LOCALAPPDATA", "relative/local"), home, registry)
                        .destination(registry, "MekHQ Milestone"));
        assertTrue(relative.getMessage().contains("absolute"));
    }

    @Test
    void macDefaultPathRequiresAnAbsoluteNonblankHome() {
        Path registry = temp.resolve("state/default.json").toAbsolutePath();
        PlatformInstallLocations blankHome = new PlatformInstallLocations(
                source("Mac OS X", Map.of(), "", registry));
        PlatformInstallLocations relativeHome = new PlatformInstallLocations(
                source("Mac OS X", Map.of(), "relative/home", registry));

        IOException blank = assertThrows(IOException.class,
                () -> blankHome.destination(registry, "MekHQ Milestone"));
        assertTrue(blank.getMessage().contains("user.home"));
        IOException relative = assertThrows(IOException.class,
                () -> relativeHome.destination(registry, "MekHQ Milestone"));
        assertTrue(relative.getMessage().contains("absolute"));
    }

    private static PlatformInstallLocations locations(
            String os, Map<String, String> environment, Path home, Path defaultRegistry) {
        return new PlatformInstallLocations(
                source(os, environment, home.toString(), defaultRegistry));
    }

    private static PlatformInstallLocations.PlatformSource source(
            String os, Map<String, String> environment, String home, Path defaultRegistry) {
        return new PlatformInstallLocations.PlatformSource() {
            @Override
            public String operatingSystem() {
                return os;
            }

            @Override
            public String environment(String name) {
                return environment.get(name);
            }

            @Override
            public String userHome() {
                return home;
            }

            @Override
            public Path defaultRegistry() {
                return defaultRegistry;
            }
        };
    }
}
