package org.megamek.launcher.update;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.Main;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.plan.Action;
import org.megamek.launcher.plan.Decision;
import org.megamek.launcher.release.FreshInstaller;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdatePreviewTest {
    @TempDir Path temp;

    @Test
    void caseOnlyReleaseRenamesAreVisibleSkipsWithoutTouchingReceiptBackedInstall()
            throws Exception {
        byte[] launcherJar = jar();
        byte[] baseline = archive("MegaMek-old", Map.of(
                "MegaMek.jar", launcherJar,
                "data/images/units/meks/Archer_5CS.png", bytes("official archer"),
                "data/Icons/old.png", bytes("old icon"),
                "lib/replace.txt", bytes("old library"),
                "lib/remove.txt", bytes("obsolete"),
                "mmconf/clientsettings.xml", bytes("baseline preferences")));
        Path registry = temp.resolve("case-registry.json");
        Path installed = temp.resolve("case-installed");
        FreshInstaller.Result installedResult = new FreshInstaller(transportFor("v-old", baseline))
                .install(OfficialRepository.MEGAMEK, "v-old", installed, registry, "Official",
                        new PrintStream(new ByteArrayOutputStream()));
        initializeChannel(registry, installedResult);
        Path localArcher = installed.resolve("data/images/units/meks/Archer_5CS.png");
        Files.writeString(localArcher, "locally customized archer");

        byte[] target = archive("MegaMek-new", Map.of(
                "MegaMek.jar", launcherJar,
                "data/images/units/meks/Archer_5Cs.png", bytes("new official archer"),
                "data/icons/new.png", bytes("new icon"),
                "docs/add.txt", bytes("new documentation"),
                "lib/replace.txt", bytes("new library"),
                "mmconf/clientsettings.xml", bytes("target preferences")));
        Map<String, Stamp> before = snapshot(temp);

        UpdatePreviewService.Preview first = preview(
                registry, installedResult.record().id(), target);
        assertCaseRenameReport(first);
        assertEquals("locally customized archer", Files.readString(localArcher));
        assertEquals(before, snapshot(temp));

        UpdatePreviewService.Preview repeated = preview(
                registry, installedResult.record().id(), target);
        assertEquals(first.decisions(), repeated.decisions());
        assertEquals(first.counts(), repeated.counts());
        assertEquals(before, snapshot(temp));

        for (String id : new String[]{null, installedResult.record().id()}) {
            QueueTransport cliNetwork = transportFor("v-new", target);
            ByteArrayOutputStream cliOut = new ByteArrayOutputStream();
            List<String> arguments = new java.util.ArrayList<>(List.of(
                    "preview-update", "--registry", registry.toString(), "--tag", "v-new"));
            if (id != null) arguments.addAll(List.of("--id", id));
            assertEquals(0, Main.run(arguments.toArray(String[]::new), new PrintStream(cliOut),
                    new PrintStream(new ByteArrayOutputStream()), cliNetwork));
            String report = cliOut.toString(StandardCharsets.UTF_8);
            assertTrue(report.contains("SUMMARY ADD=1 REPLACE=1 REMOVE=1 KEEP=1 SKIP=4"));
            assertTrue(report.contains("SKIP    data/images/units/meks/Archer_5CS.png --"));
            assertTrue(report.contains("SKIP    data/images/units/meks/Archer_5Cs.png --"));
            assertTrue(report.contains("baseline prefix: data/images/units/meks/Archer_5CS.png"));
            assertTrue(report.contains("target prefix: data/images/units/meks/Archer_5Cs.png"));
            assertTrue(report.contains("SKIP    data/Icons/old.png --"));
            assertTrue(report.contains("SKIP    data/icons/new.png --"));
            assertTrue(report.contains("ADD     docs/add.txt --"));
            assertTrue(report.contains("REPLACE lib/replace.txt --"));
            assertTrue(report.contains("REMOVE  lib/remove.txt --"));
            assertTrue(report.contains("KEEP    MegaMek.jar --"));
            assertTrue(report.contains("case-only rename/capitalization"));
            assertTrue(report.contains("preserved; rename unsupported"));
            assertEquals(2, cliNetwork.uris.size(), "one metadata request and one target fetch");
            assertEquals(before, snapshot(temp));
        }

        assertEquals("locally customized archer", Files.readString(localArcher));
        try (var children = Files.list(registry.resolveSibling("case-registry.json.metadata"))) {
            assertTrue(children.noneMatch(path ->
                    path.getFileName().toString().startsWith(".preview-")));
        }
    }

    @Test
    void freshReceiptDrivesReadOnlyPlanWithEveryActionAndProtectedExclusions()
            throws Exception {
        byte[] jar = jar();
        byte[] baseline = archive("MegaMek-old",
                Map.of("MegaMek.jar", jar, "lib/replace.txt", bytes("old"),
                        "lib/remove.txt", bytes("remove"), "lib/keep.txt", bytes("keep"),
                        "data/modified.txt", bytes("official"),
                        "campaigns/sample.cpnx.gz", bytes("sample"),
                        "mmconf/clientsettings.xml", bytes("official preferences"),
                        "settings.properties", bytes("default")));
        Path registry = temp.resolve("registry.json");
        Path installed = temp.resolve("installed");
        QueueTransport installing = transportFor("v-old", baseline);
        FreshInstaller.Result installedResult = new FreshInstaller(installing).install(
                OfficialRepository.MEGAMEK, "v-old", installed, registry, "Official",
                new PrintStream(new ByteArrayOutputStream()));
        initializeChannel(registry, installedResult);
        assertFalse(installedResult.record().updateEligible());
        assertTrue(installedResult.ownershipReceipt().excludedOfficialPaths()
                .contains("campaigns/sample.cpnx.gz"));
        assertTrue(installedResult.ownershipReceipt().excludedOfficialPaths()
                .contains("mmconf/clientsettings.xml"));
        assertTrue(installedResult.ownershipReceipt().excludedOfficialPaths()
                .contains("settings.properties"));

        Files.writeString(installed.resolve("data/modified.txt"), "user edit");
        Files.writeString(installed.resolve("lib/add.txt"), "collision");
        Files.writeString(installed.resolve("unknown.local"), "preserve");
        Map<String, Stamp> before = snapshot(temp);

        byte[] target = archive("MegaMek-new",
                Map.of("MegaMek.jar", jar, "lib/replace.txt", bytes("new"),
                        "lib/keep.txt", bytes("keep"), "lib/add.txt", bytes("target"),
                        "docs/new.txt", bytes("new"), "data/modified.txt", bytes("new official"),
                        "campaigns/sample.cpnx.gz", bytes("changed sample"),
                        "mmconf/clientsettings.xml", bytes("changed preferences"),
                        "settings.properties", bytes("new default")));
        QueueTransport previewing = transportFor("v-new", target);
        UpdatePreviewService.Preview preview = new UpdatePreviewService(previewing).preview(
                registry, installedResult.record().id(), "v-new",
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(1, preview.counts().get(Action.ADD));
        assertEquals(1, preview.counts().get(Action.REMOVE));
        assertEquals(1, preview.counts().get(Action.REPLACE));
        assertEquals(2, preview.counts().get(Action.KEEP));
        assertEquals(2, preview.counts().get(Action.SKIP));
        assertTrue(preview.decisions().stream().anyMatch(decision ->
                decision.path().equals("data/modified.txt") && decision.action() == Action.SKIP));
        assertTrue(preview.decisions().stream().noneMatch(decision ->
                decision.path().startsWith("campaigns/")));
        assertTrue(preview.decisions().stream().noneMatch(decision ->
                decision.path().startsWith("mmconf/")));
        assertTrue(preview.decisions().stream().noneMatch(decision ->
                decision.path().equals("settings.properties")));

        for (String id : List.of("", installedResult.record().id())) {
            QueueTransport cliNetwork = transportFor("v-new", target);
            ByteArrayOutputStream cliOut = new ByteArrayOutputStream();
            List<String> arguments = new java.util.ArrayList<>(List.of(
                    "preview-update", "--registry", registry.toString(), "--tag", "v-new"));
            if (!id.isEmpty()) arguments.addAll(List.of("--id", id));
            assertEquals(0, Main.run(arguments.toArray(String[]::new), new PrintStream(cliOut),
                    new PrintStream(new ByteArrayOutputStream()), cliNetwork));
            String report = cliOut.toString(StandardCharsets.UTF_8);
            assertTrue(report.contains("BASELINE id=" + installedResult.record().id()));
            assertTrue(report.contains("TARGET repository=megamek tag=v-new"));
            assertTrue(report.contains("POLICY version=1"));
            assertTrue(report.contains("PROTECTED campaigns"));
            assertTrue(report.contains("EXCLUDED-BASELINE-PATH mmconf/clientsettings.xml"));
            assertTrue(report.contains("EXCLUDED-TARGET-PATH mmconf/clientsettings.xml"));
            assertTrue(report.contains("SUMMARY ADD=1 REPLACE=1 REMOVE=1 KEEP=2 SKIP=2"));
            assertTrue(report.contains("SKIP    data/modified.txt --"));
            assertTrue(report.contains("READ-ONLY PREVIEW"));
            assertEquals(2, cliNetwork.uris.size(), "one metadata request and one target fetch");
            assertEquals(before, snapshot(temp));
        }
        assertEquals(before, snapshot(temp));
        assertTrue(Files.readString(installed.resolve("unknown.local")).equals("preserve"));
        try (var children = Files.list(registry.resolveSibling("registry.json.metadata"))) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(".preview-")));
        }
    }

    @Test
    void importedCopyWithoutFixedChannelFailsBeforeNetworkAndRemainsUsable() throws Exception {
        Path root = Files.createDirectory(temp.resolve("existing"));
        Files.write(root.resolve("MegaMek.jar"), jar());
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("mmconf"));
        Path registry = temp.resolve("existing-registry.json");
        var record = new org.megamek.launcher.registry.RegistryStore()
                .register(registry, "Old copy", root, null);
        Files.createDirectory(registry.resolveSibling("existing-registry.json.metadata"));
        QueueTransport network = new QueueTransport();
        IOException error = assertThrows(IOException.class, () ->
                new UpdatePreviewService(network).preview(registry, record.id(), "v-new",
                        new PrintStream(new ByteArrayOutputStream())));
        assertTrue(error.getMessage().contains("fixed channel provenance"));
        assertTrue(error.getMessage().contains("launch-only"));
        assertTrue(network.uris.isEmpty());
        assertEquals(record.id(), new org.megamek.launcher.registry.RegistryStore()
                .read(registry).installations().getFirst().id());
    }

    @Test
    void receiptWithoutFixedChannelIsLaunchOnlyAndCannotPreview() throws Exception {
        byte[] baseline = archive("MegaMek-old", Map.of(
                "MegaMek.jar", jar(), "data/file.txt", bytes("official"),
                "lib/file.txt", bytes("official"),
                "mmconf/clientsettings.xml", bytes("preferences")));
        Path registry = temp.resolve("incomplete-registry.json");
        Path installed = temp.resolve("incomplete-installed");
        FreshInstaller.Result published = new FreshInstaller(transportFor("v-old", baseline))
                .install(OfficialRepository.MEGAMEK, "v-old", installed, registry, "Incomplete",
                        new PrintStream(new ByteArrayOutputStream()));
        QueueTransport network = new QueueTransport();

        UpdatePreviewService.Eligibility eligibility =
                new UpdatePreviewService(network).eligibility(registry, published.record().id());
        assertFalse(eligibility.available());
        assertEquals(published.ownershipReceipt(), eligibility.receipt());
        assertTrue(eligibility.reason().contains("Fixed channel provenance"));
        IOException failure = assertThrows(IOException.class, () ->
                new UpdatePreviewService(network).preview(registry, published.record().id(),
                        "v-new", new PrintStream(new ByteArrayOutputStream())));
        assertTrue(failure.getMessage().contains("fixed channel provenance"));
        assertTrue(network.uris.isEmpty());
    }

    @Test
    void cliRejectsMissingPreviewArgumentsBeforeNetwork() {
        for (String[] arguments : List.of(
                new String[]{"preview-update"},
                new String[]{"preview-update", "--registry", temp.resolve("registry.json").toString()},
                new String[]{"preview-update", "--tag", "v-new"})) {
            QueueTransport network = new QueueTransport();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            assertEquals(2, Main.run(arguments, new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(error), network));
            assertTrue(error.toString(StandardCharsets.UTF_8).contains("ERROR:"));
            assertTrue(network.uris.isEmpty());
        }
    }

    private UpdatePreviewService.Preview preview(Path registry, String id, byte[] target)
            throws Exception {
        QueueTransport network = transportFor("v-new", target);
        UpdatePreviewService.Preview preview = new UpdatePreviewService(network).preview(
                registry, id, "v-new", new PrintStream(new ByteArrayOutputStream()));
        assertEquals(2, network.uris.size(), "one metadata request and one target fetch");
        return preview;
    }

    private static void initializeChannel(Path registry, FreshInstaller.Result installed)
            throws IOException {
        new ChannelPreferenceStore().initializeManaged(registry, installed.record(),
                installed.ownershipReceipt(), FollowChannel.MILESTONE, false);
    }

    private static void assertCaseRenameReport(UpdatePreviewService.Preview preview) {
        assertEquals(1, preview.counts().get(Action.ADD));
        assertEquals(1, preview.counts().get(Action.REPLACE));
        assertEquals(1, preview.counts().get(Action.REMOVE));
        assertEquals(1, preview.counts().get(Action.KEEP));
        assertEquals(4, preview.counts().get(Action.SKIP));
        assertEquals(8, preview.decisions().size());
        for (String path : List.of(
                "data/images/units/meks/Archer_5CS.png",
                "data/images/units/meks/Archer_5Cs.png",
                "data/Icons/old.png",
                "data/icons/new.png")) {
            Decision decision = preview.decisions().stream()
                    .filter(item -> item.path().equals(path))
                    .findFirst().orElseThrow();
            assertEquals(Action.SKIP, decision.action());
            assertTrue(decision.reason().contains("case-only rename/capitalization"));
            assertTrue(decision.reason().contains("preserved; rename unsupported"));
        }
        assertTrue(preview.decisions().stream().anyMatch(decision ->
                decision.path().equals("docs/add.txt") && decision.action() == Action.ADD));
        assertTrue(preview.decisions().stream().anyMatch(decision ->
                decision.path().equals("lib/replace.txt") && decision.action() == Action.REPLACE));
        assertTrue(preview.decisions().stream().anyMatch(decision ->
                decision.path().equals("lib/remove.txt") && decision.action() == Action.REMOVE));
        assertTrue(preview.decisions().stream().anyMatch(decision ->
                decision.path().equals("MegaMek.jar") && decision.action() == Action.KEEP));
        assertTrue(preview.decisions().stream().noneMatch(decision ->
                decision.path().contains("Archer_5C") && decision.action() != Action.SKIP));
    }

    private static Map<String, Stamp> snapshot(Path root) throws Exception {
        Map<String, Stamp> result = new LinkedHashMap<>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
                result.put(root.relativize(path).toString(),
                        new Stamp(Files.getLastModifiedTime(path).toMillis(),
                                sha(Files.readAllBytes(path))));
            }
        }
        return result;
    }

    private static QueueTransport transportFor(String tag, byte[] archive) throws Exception {
        String json = """
                {"tag_name":"%s","name":"%s","draft":false,"prerelease":false,
                "html_url":"https://github.com/MegaMek/megamek/releases/tag/%s",
                "assets":[{"name":"MegaMek-%s.tar.gz","size":%d,"digest":"sha256:%s",
                "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/%s/MegaMek-%s.tar.gz"}]}
                """.formatted(tag, tag, tag, tag, archive.length, sha(archive), tag, tag);
        return new QueueTransport(response(json), () -> new ReleaseTransport.Response(
                200, Map.of("content-length", List.of(Integer.toString(archive.length))),
                new ByteArrayInputStream(archive)));
    }

    private static byte[] archive(String root, Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry rootEntry = new TarArchiveEntry(root + "/");
            tar.putArchiveEntry(rootEntry);
            tar.closeArchiveEntry();
            for (Map.Entry<String, byte[]> item : files.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                TarArchiveEntry entry = new TarArchiveEntry(root + "/" + item.getKey());
                entry.setSize(item.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(item.getValue());
                tar.closeArchiveEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] jar() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output, manifest)) {
            jar.putNextEntry(new JarEntry("megamek/Version.properties"));
            jar.write(bytes("major=1\nminor=2\npatch=3\n"));
            jar.closeEntry();
        }
        return output.toByteArray();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static Supplier<ReleaseTransport.Response> response(String body) {
        return () -> new ReleaseTransport.Response(200, Map.of(),
                new ByteArrayInputStream(bytes(body)));
    }

    private static final class QueueTransport implements ReleaseTransport {
        private final ArrayDeque<Supplier<Response>> responses;
        private final java.util.ArrayList<URI> uris = new java.util.ArrayList<>();

        @SafeVarargs
        QueueTransport(Supplier<Response>... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Response get(URI uri, String accept) throws IOException {
            uris.add(uri);
            Supplier<Response> response = responses.poll();
            if (response == null) throw new IOException("unexpected network request");
            return response.get();
        }
    }

    private record Stamp(long modified, String sha256) {
    }
}
