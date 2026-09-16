package org.megamek.launcher;

import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.plan.Decision;
import org.megamek.launcher.plan.UpdatePlanner;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.FreshInstaller;
import org.megamek.launcher.release.JavaReleaseTransport;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.sandbox.OverrideEntry;
import org.megamek.launcher.sandbox.SandboxState;
import org.megamek.launcher.sandbox.SandboxUpdater;
import org.megamek.launcher.gui.GuiLauncher;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("gui")) {
            int result = GuiLauncher.start(args, System.err);
            if (result != 0) System.exit(result);
            return;
        }
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, new JavaReleaseTransport());
    }

    public static int run(String[] args, PrintStream out, PrintStream err,
                          ReleaseTransport releaseTransport) {
        try {
            if (args.length == 0) {
                throw new IllegalArgumentException("a command or the legacy dry-run options are required");
            }
            String command = args[0].startsWith("--") ? "plan" : args[0];
            int offset = command.equals("plan") && args[0].startsWith("--") ? 0 : 1;
            Map<String, String> options = parse(args, offset);
            return switch (command) {
                case "gui" -> GuiLauncher.start(args, err);
                case "plan" -> plan(options, out);
                case "init-test-sandbox" -> initialize(options, out);
                case "apply-test-sandbox" -> apply(options, out);
                case "recover-test-sandbox" -> recover(options, out);
                case "inspect" -> inspect(options, out);
                case "register" -> register(options, out);
                case "list" -> list(options, out);
                case "select" -> select(options, out);
                case "remove" -> remove(options, out);
                case "java-discover" -> javaDiscover(options, out);
                case "java-select" -> javaSelect(options, out);
                case "launch" -> launch(options, out);
                case "releases" -> releases(options, out, releaseTransport);
                case "install-release" -> installRelease(options, out, releaseTransport);
                default -> throw new IllegalArgumentException("unknown command: " + command);
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("ERROR: operation interrupted; network streams were closed");
            return 130;
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                err.println("ERROR: operation interrupted; network streams were closed");
                return 130;
            }
            err.println("ERROR: " + e.getMessage());
            err.println(usage());
            return 2;
        } catch (ManifestException | IllegalArgumentException e) {
            err.println("ERROR: " + e.getMessage());
            err.println(usage());
            return 2;
        }
    }

    private static int releases(Map<String, String> options, PrintStream out,
                                    ReleaseTransport transport)
                throws IOException, InterruptedException {
            requireKeys(options, Set.of("--application"), Set.of("--page", "--per-page"));
            OfficialRepository repository = OfficialRepository.parse(options.get("--application"));
            int page = boundedInteger(options.getOrDefault("--page", "1"), "--page");
            int perPage = boundedInteger(options.getOrDefault("--per-page", "10"), "--per-page");
            ReleaseCatalog.Page result = new ReleaseCatalog(transport).list(repository, page, perPage);
            out.printf("OFFICIAL-RELEASES application=%s page=%d perPage=%d returned=%d%n",
                    repository.key(), result.page(), result.perPage(), result.releases().size());
            for (ReleaseCatalog.Release release : result.releases()) {
                out.printf("RELEASE tag=%s title=%s draft=%s prerelease=%s notes=%s%n",
                        release.tag(), release.title(), release.draft(), release.prerelease(),
                        release.notesUrl());
                for (ReleaseCatalog.Asset asset : release.assets()) {
                    out.printf("  ASSET name=%s size=%d digest=%s%n", asset.name(), asset.size(),
                            asset.digest() == null ? "unavailable" : asset.digest());
                }
            }
            out.println(result.mayHaveNextPage()
                    ? "MORE-MAY-EXIST: request the next explicit --page; this is not an all-releases claim."
                    : "PAGE-COMPLETE: fewer than per-page results; no next page was indicated.");
            return 0;
        }

    private static int installRelease(Map<String, String> options, PrintStream out,
                                          ReleaseTransport transport)
                throws IOException, InterruptedException {
            requireExactly(options, Set.of("--application", "--tag", "--destination",
                    "--registry", "--name"));
            OfficialRepository repository = OfficialRepository.parse(options.get("--application"));
            FreshInstaller.Result result = new FreshInstaller(transport).install(repository,
                    options.get("--tag"), path(options, "--destination"), path(options, "--registry"),
                    options.get("--name"), out);
            out.printf("INSTALLED-AND-REGISTERED id=%s application=%s tag=%s asset=%s root=%s "
                            + "updateEligible=false%n", result.record().id(), repository.key(),
                    result.release().tag(), result.asset().name(), result.destination());
            out.println("NOT LAUNCHED: select an external Java 21+ runtime and preview explicitly.");
            out.println("INTEGRITY NOTE: GitHub HTTPS plus GitHub's same-source SHA-256 digest "
                    + "detects transfer corruption but is not an independent signature.");
            return 0;
        }

    private static int boundedInteger(String value, String option) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " must be an integer", e);
            }
    }

    private static int inspect(Map<String, String> options, PrintStream out) throws IOException {
            requireExactly(options, Set.of("--installation"));
            printInspection(out, new InstallationInspector().inspect(path(options, "--installation")));
            out.println("READ-ONLY: no application files were executed or changed.");
            return 0;
        }

        private static int register(Map<String, String> options, PrintStream out) throws IOException {
            requireKeys(options, Set.of("--registry", "--installation", "--name", "--confirm"),
                    Set.of("--pin"));
            if (!RegistryStore.CONFIRM.equals(options.get("--confirm"))) {
                throw new IllegalArgumentException("--confirm must be exactly " + RegistryStore.CONFIRM);
            }
            InstallationRecord record = new RegistryStore().register(path(options, "--registry"),
                    options.get("--name"), path(options, "--installation"), options.get("--pin"));
            out.printf("REGISTERED id=%s name=%s root=%s build=%s updateEligible=false%n",
                    record.id(), record.name(), record.canonicalRoot(), record.observedBuild());
            out.println("LAUNCH-ONLY: registration did not launch or modify the application.");
            return 0;
        }

        private static int list(Map<String, String> options, PrintStream out) throws IOException {
            requireExactly(options, Set.of("--registry"));
            RegistryData data = new RegistryStore().read(path(options, "--registry"));
            for (InstallationRecord record : data.installations()) {
                out.printf("%s id=%s name=%s root=%s build=%s java=%s pin=%s updateEligible=false%n",
                        record.id().equals(data.defaultInstallationId()) ? "DEFAULT" : "INSTALL",
                        record.id(), record.name(), record.canonicalRoot(), record.observedBuild(),
                        record.javaExecutable() == null ? "unselected" : record.javaExecutable(),
                        record.pin() == null ? "none" : record.pin());
            }
            return 0;
        }

        private static int select(Map<String, String> options, PrintStream out) throws IOException {
            requireExactly(options, Set.of("--registry", "--id"));
            new RegistryStore().select(path(options, "--registry"), options.get("--id"));
            out.println("DEFAULT id=" + options.get("--id"));
            return 0;
        }

        private static int remove(Map<String, String> options, PrintStream out) throws IOException {
            requireExactly(options, Set.of("--registry", "--id", "--confirm"));
            if (!"REMOVE-LAUNCH-ONLY".equals(options.get("--confirm"))) {
                throw new IllegalArgumentException("--confirm must be exactly REMOVE-LAUNCH-ONLY");
            }
            new RegistryStore().remove(path(options, "--registry"), options.get("--id"));
            out.println("REMOVED registry metadata only; application files were unchanged.");
            return 0;
        }

        private static int javaDiscover(Map<String, String> options, PrintStream out) {
            requireExactly(options, Set.of());
            for (Path candidate : new JavaRuntime().candidates()) {
                out.println("JAVA-CANDIDATE " + candidate);
            }
            out.println("DISCOVERY-ONLY: candidates were not executed; select one explicitly.");
            return 0;
        }

        private static int javaSelect(Map<String, String> options, PrintStream out)
                throws IOException, InterruptedException {
            requireExactly(options, Set.of("--registry", "--id", "--java"));
            RegistryStore store = new RegistryStore();
            Path registry = path(options, "--registry");
            InstallationRecord record = store.resolve(store.read(registry), options.get("--id"));
            Path executable = new JavaRuntime().resolve(options.get("--java"));
            if (executable.startsWith(Path.of(record.canonicalRoot()))) {
                throw new IOException("selected Java must be external to the application directory");
            }
            int feature = new JavaRuntime().validate(executable, Path.of(record.canonicalRoot()));
            store.selectJava(registry, record.id(), executable.toString());
            out.printf("JAVA-SELECTED id=%s executable=%s feature=%d%n",
                    record.id(), executable, feature);
            return 0;
        }

        private static int launch(Map<String, String> options, PrintStream out)
                throws IOException, InterruptedException {
            requireKeys(options, Set.of("--registry", "--product"), Set.of("--id", "--dry-run"));
            RegistryStore store = new RegistryStore();
            InstallationRecord record = store.resolve(store.read(path(options, "--registry")),
                    options.get("--id"));
            ApplicationLauncher launcher = new ApplicationLauncher();
            if (options.containsKey("--dry-run")) {
                if (!"true".equals(options.get("--dry-run"))) {
                    throw new IllegalArgumentException("--dry-run value must be true");
                }
                out.println("LAUNCH-PREVIEW argv=" + launcher.command(record, options.get("--product")));
                out.println("DRY-RUN ONLY: application was not started.");
                return 0;
            }
            return launcher.launch(record, options.get("--product"));
        }

    private static void printInspection(PrintStream out, Inspection inspection) {
            out.printf("INSPECT root=%s build=%s confidence=%s updateEligible=false%n",
                    inspection.canonicalRoot(), inspection.observedBuild(), inspection.confidence());
            for (Product product : inspection.products()) {
                out.printf("PRODUCT key=%s jar=%s main=%s build=%s classpath=%s%n",
                        product.key(), product.jar(), product.mainClass(), product.build(),
                        product.classPath());
        }
    }

    private static int plan(Map<String, String> options, PrintStream out)
            throws ManifestException, IOException {
        requireExactly(options, Set.of("--baseline", "--target", "--installation"));
        ManifestReader reader = new ManifestReader();
        Manifest baseline = reader.read(path(options, "--baseline"));
        Manifest target = reader.read(path(options, "--target"));
        reader.validatePair(baseline, target);
        Path installation = path(options, "--installation");
        out.printf("DRY-RUN product=%s package=%s baseline=%s target=%s installation=%s%n",
                baseline.product(), baseline.packageId(), baseline.releaseId(), target.releaseId(),
                installation.toAbsolutePath().normalize());
        for (Decision decision : new UpdatePlanner().plan(baseline, target, installation)) {
            printDecision(out, decision);
        }
        out.println("DRY-RUN ONLY: no files were changed.");
        return 0;
    }

    private static int initialize(Map<String, String> options, PrintStream out)
            throws ManifestException, IOException {
        requireExactly(options, Set.of("--baseline", "--fixture", "--sandbox", "--acknowledge"));
        acknowledge(options);
        SandboxState state = new SandboxUpdater().initialize(path(options, "--baseline"),
                path(options, "--fixture"), path(options, "--sandbox"));
        printState(out, "INITIALIZED", state);
        return 0;
    }

    private static int apply(Map<String, String> options, PrintStream out)
            throws ManifestException, IOException {
        requireExactly(options, Set.of("--target", "--payload", "--sandbox", "--acknowledge"));
        acknowledge(options);
        SandboxUpdater.ApplyResult result = new SandboxUpdater().apply(path(options, "--sandbox"),
                path(options, "--target"), path(options, "--payload"));
        for (Decision decision : result.decisions()) {
            printDecision(out, decision);
        }
        printState(out, "APPLIED", result.state());
        return 0;
    }

    private static int recover(Map<String, String> options, PrintStream out)
            throws ManifestException, IOException {
        requireExactly(options, Set.of("--sandbox", "--acknowledge"));
        acknowledge(options);
        SandboxUpdater.RecoveryResult result =
                new SandboxUpdater().recover(path(options, "--sandbox"));
        out.println("RECOVERY: " + result.outcome());
        printState(out, "RECOVERED", result.state());
        return 0;
    }

    private static void printDecision(PrintStream out, Decision decision) {
        out.printf("%-7s %s -- %s%n", decision.action(), decision.path(), decision.reason());
    }

    private static void printState(PrintStream out, String verb, SandboxState state) {
        out.printf("%s sandboxId=%s release=%s root=%s overrides=%d%n",
                verb, state.sandboxId(), state.officialManifest().releaseId(),
                state.canonicalRoot(), state.overrides().size());
        for (OverrideEntry override : state.overrides()) {
            out.printf("OVERRIDE %-18s %s trustedOfficialAncestors=%d%n",
                    override.kind(), override.path(), override.officialHashes().size());
        }
    }

    private static void acknowledge(Map<String, String> options) {
        if (!SandboxUpdater.ACKNOWLEDGEMENT.equals(options.get("--acknowledge"))) {
            throw new IllegalArgumentException("--acknowledge must be exactly "
                    + SandboxUpdater.ACKNOWLEDGEMENT);
        }
    }

    private static Map<String, String> parse(String[] args, int offset) {
        if ((args.length - offset) % 2 != 0) {
            throw new IllegalArgumentException("every option requires one value");
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = offset; i < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected named option, found: " + args[i]);
            }
            if (options.putIfAbsent(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("duplicate option: " + args[i]);
            }
        }
        return options;
    }

    private static void requireExactly(Map<String, String> actual, Set<String> expected) {
        if (!actual.keySet().equals(expected)) {
            throw new IllegalArgumentException("required options are " + expected);
        }
    }

    private static void requireKeys(Map<String, String> actual, Set<String> required,
                                    Set<String> optional) {
            if (!actual.keySet().containsAll(required)
                    || !java.util.stream.Stream.concat(required.stream(), optional.stream()).toList()
                    .containsAll(actual.keySet())) {
                throw new IllegalArgumentException("required options are " + required
                        + "; optional options are " + optional);
        }
    }

    private static Path path(Map<String, String> options, String name) {
        try {
            return Path.of(options.get(name));
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid path for " + name + ": " + e.getInput(), e);
        }
    }

    private static String usage() {
        return """
                Usage:
                  mm-launcher [plan] --baseline <json> --target <json> --installation <dir>
                  mm-launcher init-test-sandbox --baseline <json> --fixture <dir> --sandbox <new-dir> --acknowledge I-UNDERSTAND-THIS-IS-A-DISPOSABLE-TEST
                  mm-launcher apply-test-sandbox --target <json> --payload <dir> --sandbox <dir> --acknowledge I-UNDERSTAND-THIS-IS-A-DISPOSABLE-TEST
                  mm-launcher recover-test-sandbox --sandbox <dir> --acknowledge I-UNDERSTAND-THIS-IS-A-DISPOSABLE-TEST
                  mm-launcher inspect --installation <portable-dir>
                  mm-launcher register --registry <json> --installation <portable-dir> --name <name> --confirm REGISTER-LAUNCH-ONLY [--pin <metadata>]
                  mm-launcher list --registry <json>
                  mm-launcher select --registry <json> --id <uuid>
                  mm-launcher remove --registry <json> --id <uuid> --confirm REMOVE-LAUNCH-ONLY
                  mm-launcher java-discover
                  mm-launcher java-select --registry <json> --id <uuid> --java <java-home-or-executable>
                  mm-launcher launch --registry <json> [--id <uuid>] --product <megamek|mekhq|lab> [--dry-run true]
                  mm-launcher releases --application <megamek|mekhq|lab> [--page <1-1000>] [--per-page <1-50>]
                  mm-launcher install-release --application <megamek|mekhq|lab> --tag <exact-tag> --destination <new-dir> --registry <json> --name <name>""";
    }
}
