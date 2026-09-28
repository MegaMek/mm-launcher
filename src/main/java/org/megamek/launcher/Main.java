/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek Launcher is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package org.megamek.launcher;

import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.channel.FollowChannel;
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
import org.megamek.launcher.gui.LauncherServices;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.UpdatePreviewService;
import org.megamek.launcher.update.RealUpdateService;
import org.megamek.launcher.launch.RootCoordinator;

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
            if (command.equals("channel-set")) return rejectChannelMutation();
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
                case "check-updates" -> checkUpdates(options, out, releaseTransport);
                case "releases" -> releases(options, out, releaseTransport);
                case "install-release" -> installRelease(options, out, releaseTransport);
                case "preview-update" -> previewUpdate(options, out, releaseTransport);
                case "apply-update" -> applyUpdate(options, out, releaseTransport);
                case "recover-update" -> recoverUpdate(options, out, releaseTransport);
                case "unblock-launch" -> unblockLaunch(options, out);
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
            requireKeys(options, Set.of("--application", "--tag", "--destination",
                    "--registry", "--name", "--channel"), Set.of());
            FollowChannel channel = FollowChannel.parse(options.get("--channel"));
            OfficialRepository repository = OfficialRepository.parse(options.get("--application"));
            Path registry = path(options, "--registry");
            LauncherServices services = new LauncherServices(registry, new RegistryStore(),
                    new InstallationInspector(), transport, new JavaRuntime(),
                    new ApplicationLauncher());
            FreshInstaller.Result result = services.install(repository, options.get("--tag"),
                    path(options, "--destination"), options.get("--name"), channel, out);
            out.printf("INSTALLED-AND-REGISTERED id=%s application=%s tag=%s asset=%s "
                            + "root=%s fixedChannel=%s%n",
                    result.record().id(), repository.key(), result.release().tag(),
                    result.asset().name(), result.destination(), channel.cliName());
            out.printf("FIXED-CHANNEL-SAVED id=%s channel=%s checkOnOpen=true%n",
                    result.record().id(), channel.cliName());
            out.println("NOT LAUNCHED: effective Game Java is resolved only when launched.");
            return 0;
        }

    private static int rejectChannelMutation() throws IOException {
        throw new IOException("Channel is fixed; install another managed copy");
    }

    private static int checkUpdates(Map<String, String> options, PrintStream out,
                                    ReleaseTransport transport)
            throws IOException, InterruptedException {
        requireKeys(options, Set.of("--registry"), Set.of("--id"));
        ChannelUpdateChecker.Result result = new ChannelUpdateChecker(
                path(options, "--registry"), transport).check(options.get("--id"));
        String channel = result.preference() == null
                ? "unknown" : result.preference().channel().cliName();
        String target = result.recommendation() == null
                ? "unavailable" : result.recommendation().targetTag();
        String notes = result.recommendation() == null
                ? "unavailable" : result.recommendation().notesUrl().toString();
        String size = result.recommendation() == null
                ? "unavailable" : Long.toString(result.recommendation().assetSize());
        String digest = result.recommendation() == null
                ? "unavailable" : result.recommendation().assetDigest();
        out.printf("UPDATE-CHECK id=%s currentTag=%s channel=%s target=%s status=%s "
                        + "reason=%s notes=%s size=%s digest=%s%n",
                result.record().id(),
                result.currentTag() == null ? "unavailable" : result.currentTag(),
                channel, target, result.status().name().toLowerCase(java.util.Locale.ROOT),
                oneLine(result.reason()), notes, size, digest);
        out.println("METADATA-ONLY: no release package or installed file was downloaded or changed.");
        return 0;
    }

    private static String oneLine(String value) {
        if (value == null || value.isBlank()) return "unavailable";
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static int previewUpdate(Map<String, String> options, PrintStream out,
                                     ReleaseTransport transport)
            throws IOException, InterruptedException, ManifestException {
        requireKeys(options, Set.of("--registry", "--tag"), Set.of("--id"));
        UpdatePreviewService.Preview preview = new UpdatePreviewService(transport).preview(
                path(options, "--registry"), options.get("--id"), options.get("--tag"), out);
        out.printf("BASELINE id=%s repository=%s tag=%s asset=%s size=%d sha256=%s%n",
                preview.record().id(), preview.baseline().repository(), preview.baseline().tag(),
                preview.baseline().assetName(), preview.baseline().assetSize(),
                preview.baseline().assetSha256());
        out.printf("TARGET repository=%s tag=%s asset=%s size=%d digest=%s%n",
                preview.baseline().repository(), preview.targetRelease().tag(),
                preview.targetAsset().name(), preview.targetAsset().size(),
                preview.resolvedAssetDigest());
        out.printf("POLICY version=%d managedBaseline=%d managedTarget=%d%n",
                preview.baseline().ownershipPolicyVersion(),
                preview.baseline().officialManifest().files().size(),
                preview.targetManifest().files().size());
        out.println("PROTECTED " + String.join(", ", OwnershipPolicy.PROTECTED_PATHS));
        out.printf("EXCLUDED-BASELINE count=%d%n",
                preview.baseline().excludedOfficialPaths().size());
        preview.baseline().excludedOfficialPaths().forEach(path ->
                out.println("EXCLUDED-BASELINE-PATH " + path));
        out.printf("EXCLUDED-TARGET count=%d%n", preview.targetExcludedPaths().size());
        preview.targetExcludedPaths().forEach(path -> out.println("EXCLUDED-TARGET-PATH " + path));
        out.printf("SUMMARY ADD=%d REPLACE=%d REMOVE=%d KEEP=%d SKIP=%d%n",
                preview.counts().get(org.megamek.launcher.plan.Action.ADD),
                preview.counts().get(org.megamek.launcher.plan.Action.REPLACE),
                preview.counts().get(org.megamek.launcher.plan.Action.REMOVE),
                preview.counts().get(org.megamek.launcher.plan.Action.KEEP),
                preview.counts().get(org.megamek.launcher.plan.Action.SKIP));
        preview.decisions().forEach(decision -> printDecision(out, decision));
        for (String jar : OwnershipPolicy.extraLibJars(
                Path.of(preview.record().canonicalRoot()), preview.currentState().officialManifest(),
                preview.targetManifest())) {
            out.println("WARNING preserved unowned lib JAR " + jar
                    + " may affect the game; update will not remove it.");
        }
        out.println("READ-ONLY PREVIEW: no installed application, registry, or receipt files changed.");
        out.println("POINT-IN-TIME ONLY: this plan is not authority to apply later.");
        return 0;
    }

    private static int applyUpdate(Map<String, String> options, PrintStream out,
                                   ReleaseTransport transport)
            throws IOException, InterruptedException, ManifestException {
        requireKeys(options, Set.of("--registry", "--from-tag", "--tag", "--size", "--digest",
                "--confirm"), Set.of("--id"));
        long size = positiveLong(options.get("--size"), "--size");
        RealUpdateService.ApplyResult result = new RealUpdateService(transport).apply(
                path(options, "--registry"), options.get("--id"), options.get("--from-tag"),
                options.get("--tag"), size, options.get("--digest"), options.get("--confirm"), out);
        out.printf("UPDATED id=%s build=%s tag=%s skipped=%d retainedOverrides=%d%n",
                result.record().id(), result.record().observedBuild(), result.state().tag(),
                result.skippedDecisions(), result.retainedOverrides());
        result.decisions().forEach(decision -> printDecision(out, decision));
        try {
            for (String jar : OwnershipPolicy.extraLibJars(
                    Path.of(result.record().canonicalRoot()), result.state().officialManifest(),
                    result.state().officialManifest())) {
                out.println("WARNING preserved unowned lib JAR " + jar
                        + " may affect the game; update did not remove it.");
            }
        } catch (IOException e) {
            out.println("WARNING update committed; could not inspect extra lib JARs: "
                    + e.getMessage());
        }
        out.println(result.skippedDecisions() == 0
                ? "UPDATE COMPLETE: verified managed program files now match the target."
                : "UPDATE COMPLETE WITH PRESERVATION: skipped user data/custom collisions remain.");
        return 0;
    }

    private static int recoverUpdate(Map<String, String> options, PrintStream out,
                                     ReleaseTransport transport)
            throws IOException, ManifestException {
        requireKeys(options, Set.of("--registry", "--confirm"), Set.of("--id"));
        RealUpdateService.RecoveryResult result = new RealUpdateService(transport).recover(
                path(options, "--registry"), options.get("--id"), options.get("--confirm"));
        out.println("RECOVERY: " + result.outcome());
        return 0;
    }

    private static int unblockLaunch(Map<String, String> options, PrintStream out)
            throws IOException {
        requireKeys(options, Set.of("--registry", "--confirm"), Set.of("--id"));
        if (!RealUpdateService.CONFIRM.equals(options.get("--confirm"))) {
            throw new IOException("--confirm must be exactly " + RealUpdateService.CONFIRM);
        }
        RegistryStore store = new RegistryStore();
        InstallationRecord record = store.resolve(store.read(path(options, "--registry")),
                options.get("--id"));
        try (RootCoordinator.Lease ignored = new RootCoordinator().acquire(
                Path.of(record.canonicalRoot()), true)) {
            out.println("COORDINATION CHECK COMPLETE: no live launcher-tracked application "
                    + "was found. No process was killed.");
        }
        return 0;
    }

    private static long positiveLong(String value, String option) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " must be a positive integer", e);
        }
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

        private static int register(Map<String, String> options, PrintStream out)
                throws IOException, InterruptedException {
            requireExactly(options,
                    Set.of("--registry", "--installation", "--name", "--confirm"));
            if (!RegistryStore.CONFIRM.equals(options.get("--confirm"))) {
                throw new IllegalArgumentException("--confirm must be exactly " + RegistryStore.CONFIRM);
            }
            Path registry = path(options, "--registry");
            LauncherServices services = new LauncherServices(registry);
            OperationContext context = OperationContext.none(OperationType.IMPORT_EXISTING);
            var plan = services.prepareExistingImport(path(options, "--installation"), context);
            InstallationRecord record = services.importExisting(
                    plan, options.get("--name"), context).record();
            out.printf("REGISTERED id=%s name=%s root=%s build=%s updateEligible=false%n",
                    record.id(), record.name(), record.canonicalRoot(), record.observedBuild());
            out.println("LAUNCH-ONLY: registration did not launch or modify the application.");
            return 0;
        }

        private static int list(Map<String, String> options, PrintStream out) throws IOException {
            requireExactly(options, Set.of("--registry"));
            RegistryData data = new RegistryStore().read(path(options, "--registry"));
            for (InstallationRecord record : data.installations()) {
                out.printf("%s id=%s name=%s root=%s build=%s pin=%s updateEligible=false%n",
                        record.id().equals(data.defaultInstallationId()) ? "DEFAULT" : "INSTALL",
                        record.id(), record.name(), record.canonicalRoot(), record.observedBuild(),
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
            Path registry = path(options, "--registry");
            RegistryStore store = new RegistryStore();
            InstallationRecord record = store.resolve(store.read(registry), options.get("--id"));
            new LauncherServices(registry).removeFromLauncher(record);
            out.println("REMOVED from launcher; application files were unchanged.");
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
            requireExactly(options, Set.of("--registry", "--java"));
            Path registry = path(options, "--registry");
            Path executable = path(options, "--java");
            LauncherServices services = new LauncherServices(registry);
            services.selectDefaultJava(executable);
            LauncherServices.SettingsView selected = services.settingsView();
            out.printf("GAME-JAVA-SELECTED executable=%s feature=%d%n",
                    selected.defaultJava(), selected.defaultJavaFeature());
            return 0;
        }

        private static int launch(Map<String, String> options, PrintStream out)
                throws IOException, InterruptedException {
            requireKeys(options, Set.of("--registry", "--product"), Set.of("--id", "--dry-run"));
            Path registry = path(options, "--registry");
            RegistryStore store = new RegistryStore();
            InstallationRecord record = store.resolve(store.read(registry), options.get("--id"));
            LauncherServices services = new LauncherServices(registry);
            if (options.containsKey("--dry-run")) {
                if (!"true".equals(options.get("--dry-run"))) {
                    throw new IllegalArgumentException("--dry-run value must be true");
                }
                out.println("LAUNCH-PREVIEW argv="
                        + services.preview(record, options.get("--product")));
                out.println("DRY-RUN ONLY: application was not started.");
                return 0;
            }
            return services.launch(record, options.get("--product"));
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
        Set<String> allowed = java.util.stream.Stream.concat(
                required.stream(), optional.stream()).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
        String unknown = actual.keySet().stream()
                .filter(key -> !allowed.contains(key)).findFirst().orElse(null);
        if (unknown != null) {
            throw new IllegalArgumentException("unknown option: " + unknown);
        }
        if (!actual.keySet().containsAll(required)) {
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
                  mm-launcher register --registry <json> --installation <portable-dir> --name <name> --confirm REGISTER-LAUNCH-ONLY
                  mm-launcher list --registry <json>
                  mm-launcher select --registry <json> --id <uuid>
                  mm-launcher remove --registry <json> --id <uuid> --confirm REMOVE-LAUNCH-ONLY
                  mm-launcher java-discover
                  mm-launcher java-select --registry <json> --java <java-home-or-executable>
                  mm-launcher launch --registry <json> [--id <uuid>] --product <megamek|mekhq|lab> [--dry-run true]
                  mm-launcher check-updates --registry <json> [--id <uuid>]
                  mm-launcher releases --application <megamek|mekhq|lab> [--page <1-1000>] [--per-page <1-50>]
                  mm-launcher install-release --application <megamek|mekhq|lab> --tag <exact-tag> --destination <new-dir> --registry <json> --name <name> --channel <milestone|development>
                  mm-launcher preview-update --registry <json> [--id <uuid>] --tag <exact-tag>
                  mm-launcher apply-update --registry <json> [--id <uuid>] --from-tag <current-tag> --tag <exact-tag> --size <bytes> --digest <sha256:hex> --confirm CLOSE-ALL-SUITE-APPS-AND-APPLY
                  mm-launcher recover-update --registry <json> [--id <uuid>] --confirm CLOSE-ALL-SUITE-APPS-AND-APPLY
                  mm-launcher unblock-launch --registry <json> [--id <uuid>] --confirm CLOSE-ALL-SUITE-APPS-AND-APPLY""";
    }
}
