package org.megamek.launcher.onboarding;

import org.megamek.launcher.channel.ChannelCatalog;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.OfficialYamlChannelCatalog;
import org.megamek.launcher.diagnostics.OperationLogStore;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.FreshInstaller;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.release.VerifiedPackageFetcher;
import org.megamek.launcher.update.ReceiptStore;
import org.megamek.launcher.update.StrictPathSafety;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Metadata-only planning and explicitly confirmed installation for the simple first-run path.
 * A plan is an immutable consent quote: it binds registry path/state/default, product set,
 * destination, runtime, repository/channel/source version, tag, asset URL/name/size/digest, and
 * the revisioned automatic-check setting for the new copy.
 */
public final class NormalInstallService {
    public static final String NORMAL_NAME = "Default";
    public static final Set<String> SUITE_PRODUCTS =
            Set.of("megamek", "mekhq", "lab");
    private static final Set<String> MEGAMEK_PRODUCTS = Set.of("megamek");
    private static final Set<String> LAB_PRODUCTS = Set.of("lab");
    private static final int MAX_CREATED_PARENTS = 4;

    private final Path registry;
    private final RegistryStore registries;
    private final ReleaseTransport transport;
    private final JavaRuntime javaRuntime;
    private final ChannelCatalog channels;
    private final ChannelPreferenceStore preferences;
    private final CheckConfigurationSource checkConfigurations;
    private final PlatformInstallLocations installLocations;

    public NormalInstallService(Path registry, ReleaseTransport transport,
                                JavaRuntime javaRuntime) {
        this(registry, transport, javaRuntime, new RegistryStore(),
                new OfficialYamlChannelCatalog(transport), new ChannelPreferenceStore(),
                () -> CheckConfiguration.DEFAULT, PlatformInstallLocations.customRegistry());
    }

    public NormalInstallService(Path registry, ReleaseTransport transport,
                                JavaRuntime javaRuntime,
                                CheckConfigurationSource checkConfigurations) {
        this(registry, transport, javaRuntime, new RegistryStore(),
                new OfficialYamlChannelCatalog(transport), new ChannelPreferenceStore(),
                checkConfigurations, PlatformInstallLocations.customRegistry());
    }

    public NormalInstallService(Path registry, ReleaseTransport transport,
                                JavaRuntime javaRuntime,
                                CheckConfigurationSource checkConfigurations,
                                PlatformInstallLocations installLocations) {
        this(registry, transport, javaRuntime, new RegistryStore(),
                new OfficialYamlChannelCatalog(transport), new ChannelPreferenceStore(),
                checkConfigurations, installLocations);
    }

    public NormalInstallService(Path registry, ReleaseTransport transport,
                                JavaRuntime javaRuntime, RegistryStore registries,
                                ChannelCatalog channels,
                                ChannelPreferenceStore preferences) {
        this(registry, transport, javaRuntime, registries, channels, preferences,
                () -> CheckConfiguration.DEFAULT, PlatformInstallLocations.customRegistry());
    }

    public NormalInstallService(Path registry, ReleaseTransport transport,
                                JavaRuntime javaRuntime, RegistryStore registries,
                                ChannelCatalog channels, ChannelPreferenceStore preferences,
                                CheckConfigurationSource checkConfigurations) {
        this(registry, transport, javaRuntime, registries, channels, preferences,
                checkConfigurations, PlatformInstallLocations.customRegistry());
    }

    public NormalInstallService(Path registry, ReleaseTransport transport,
                                JavaRuntime javaRuntime, RegistryStore registries,
                                ChannelCatalog channels, ChannelPreferenceStore preferences,
                                CheckConfigurationSource checkConfigurations,
                                PlatformInstallLocations installLocations) {
        this.registry = registry.toAbsolutePath().normalize();
        this.transport = transport;
        this.javaRuntime = javaRuntime;
        this.registries = registries;
        this.channels = channels;
        this.preferences = preferences;
        this.checkConfigurations = java.util.Objects.requireNonNull(
                checkConfigurations, "checkConfigurations");
        this.installLocations = java.util.Objects.requireNonNull(
                installLocations, "installLocations");
    }

    /** The per-user default is outside the launcher program tree and is not created by this call. */
    public Path defaultDestination() throws IOException {
        return defaultDestination(OfficialRepository.MEKHQ, FollowChannel.MILESTONE);
    }

    /**
     * Uses a distinct, truthful folder for every fixed product/channel pair. The registered
     * first copy is still named Main and becomes the default only when Main is empty.
     */
    public Path defaultDestination(OfficialRepository requestedRepository,
                                   FollowChannel requestedChannel) throws IOException {
        OfficialRepository repository = requireAllowedRepository(requestedRepository);
        FollowChannel channel = requireAllowedChannel(requestedChannel);
        return installLocations.destination(registry, defaultFolderName(repository, channel));
    }

    public static String defaultFolderName(OfficialRepository repository, FollowChannel channel)
            throws IOException {
        requireAllowedRepository(repository);
        requireAllowedChannel(channel);
        String product = switch (repository) {
            case MEKHQ -> "MekHQ";
            case MEGAMEK -> "MegaMek";
            case LAB -> "MegaMekLab";
        };
        return product + " " + channel;
    }

    /**
     * Resolves current official metadata and validates Java, but performs no filesystem write and
     * no package download. Passing null selects only the Java which is running the launcher.
     */
    public Plan prepare(Path requestedDestination, Path selectedJava)
            throws IOException, InterruptedException {
        return prepare(OfficialRepository.MEKHQ, FollowChannel.MILESTONE,
                requestedDestination, selectedJava);
    }

    /** Compatibility overload for one of the two explicit official MekHQ channels. */
    public Plan prepare(FollowChannel requestedChannel, Path requestedDestination,
                        Path selectedJava) throws IOException, InterruptedException {
        return prepare(OfficialRepository.MEKHQ, requestedChannel,
                requestedDestination, selectedJava);
    }

    /**
     * Resolves one fixed official product/channel pair. No custom repository, arbitrary URL,
     * inferred bundle, or channel fallback is accepted.
     */
    public Plan prepare(OfficialRepository requestedRepository, FollowChannel requestedChannel,
                        Path requestedDestination, Path selectedJava)
            throws IOException, InterruptedException {
        OfficialRepository repository = requireAllowedRepository(requestedRepository);
        FollowChannel channel = requireAllowedChannel(requestedChannel);
        if (requestedDestination == null) {
            throw new IOException("normal install destination is required");
        }
        Path destination = requestedDestination.toAbsolutePath().normalize();
        RegistrySnapshot snapshot = snapshot();
        List<Path> missingParents = validateProposedDestination(destination, snapshot.data());
        Path executable = selectedJava == null
                ? javaRuntime.launcherJava() : javaRuntime.resolve(selectedJava.toString());
        if (executable.startsWith(destination)) {
            throw new IOException("Java must be outside the proposed application folder");
        }
        Path workingDirectory = nearestExistingDirectory(destination);
        int javaFeature = javaRuntime.validate(executable, workingDirectory);
        ChannelCatalog.Target target = channels.target(channel, repository);
        requireTarget(target, repository, channel);
        CheckConfiguration checkConfiguration = checkConfiguration();
        return new Plan(registry, snapshot, destination, List.copyOf(missingParents), executable,
                javaFeature, target.channel(), target.repository(), target.version(),
                requiredProducts(repository), target.release(), target.asset(), target.source(),
                checkConfiguration);
    }

    /**
     * Rechecks the complete quote before binary transfer, creates only consented parent
     * directories, publishes atomically, and then configures Java/channel/Main selection.
     */
    public Result install(Plan plan, PrintStream progress, OperationContext context)
            throws IOException, InterruptedException {
        if (plan == null) throw new IOException("a confirmed normal-install plan is required");
        if (context == null) throw new IOException("operation context is required");
        FollowChannel channel = requireAllowedChannel(plan.channel());
        OfficialRepository repository = requireAllowedRepository(plan.repository());
        if (!plan.registry().equals(registry)) {
            throw new IOException("normal install plan belongs to another launcher registry");
        }
        if (!plan.requiredProducts().equals(requiredProducts(repository))) {
            throw new IOException("normal install plan product does not match its repository");
        }
        context.checkpoint();
        RegistrySnapshot now = snapshot();
        if (!plan.registrySnapshot().equals(now)) {
            throw new IOException("launcher installations or default selection changed; review a "
                    + "fresh install confirmation");
        }
        List<Path> currentMissing =
                validateProposedDestination(plan.destination(), now.data());
        if (!currentMissing.equals(plan.missingParents())) {
            throw new IOException("proposed destination parent state changed; choose the location "
                    + "again");
        }
        Path resolvedJava = javaRuntime.resolve(plan.javaExecutable().toString());
        if (!resolvedJava.equals(plan.javaExecutable())
                || javaRuntime.validate(resolvedJava,
                nearestExistingDirectory(plan.destination())) != plan.javaFeature()) {
            throw new IOException("selected Java changed; review a fresh install confirmation");
        }
        ChannelCatalog.Target fresh = channels.target(channel, repository);
        requireTarget(fresh, repository, channel);
        if (!sameQuote(plan, fresh)) {
            throw new IOException("official release or asset metadata changed; review and consent "
                    + "to a fresh install confirmation");
        }
        CheckConfiguration currentChecks = checkConfiguration();
        if (!plan.checkConfiguration().equals(currentChecks)) {
            throw new IOException("launcher automatic-check setting changed; review a fresh "
                    + "install confirmation");
        }

        context.checkpoint();
        createPlannedParents(plan);
        VerifiedPackageFetcher.ExpectedAsset expected =
                new VerifiedPackageFetcher.ExpectedAsset(plan.asset().name(),
                        plan.asset().size(), plan.asset().digest(), plan.asset().url());
        final FreshInstaller.Result installed;
        try {
            installed = new FreshInstaller(transport).installMatchingProducts(plan.repository(),
                    plan.release().tag(), plan.destination(), registry, NORMAL_NAME, progress,
                    context, Optional.of(expected), plan.requiredProducts());
        } catch (IOException error) {
            if (Files.exists(plan.destination(), LinkOption.NOFOLLOW_LINKS)) {
                throw new PublishedInstallationException(plan.destination(), null,
                        "The downloaded copy was published but setup did not finish. Keep the "
                                + "copy; use Installations to register or repair it instead of "
                                + "downloading over it. " + detail(error), error);
            }
            throw error;
        }

        InstallationRecord configured = installed.record();
        try {
            // Revalidation here prevents a runtime changed during a long download from being
            // persisted as ready.
            Path java = javaRuntime.resolve(plan.javaExecutable().toString());
            if (!java.equals(plan.javaExecutable())
                    || javaRuntime.validate(java, plan.destination()) != plan.javaFeature()) {
                throw new IOException("selected Java changed during installation");
            }
            registries.selectJava(registry, configured.id(), java.toString());
            configured = resolve(configured.id());
            preferences.set(registry, configured, plan.channel(),
                    plan.checkConfiguration().checkOnOpen());
            boolean becameMain = requireExpectedPostInstall(plan, configured.id());
            configured = resolve(configured.id());
            return new Result(configured, installed.release(), installed.asset(),
                    installed.destination(), installed.ownershipReceipt(), becameMain);
        } catch (IOException | InterruptedException error) {
            throw new PublishedInstallationException(installed.destination(), configured.id(),
                    "The downloaded copy is valid and registered, but Java, "
                            + plan.channel() + " " + plan.repository().key() + " update "
                            + "checks, or default selection still needs repair in Installations. "
                            + "Do not download over the retained copy. " + detail(error), error);
        }
    }

    private boolean requireExpectedPostInstall(Plan plan, String newId) throws IOException {
        RegistryData current = registries.read(registry);
        List<InstallationRecord> withoutNew = current.installations().stream()
                .filter(record -> !record.id().equals(newId)).toList();
        if (!withoutNew.equals(plan.registrySnapshot().data().installations())
                || !java.util.Objects.equals(current.defaultInstallationId(),
                expectedDefaultAfterRegistration(plan.registrySnapshot().data(), newId))) {
            throw new IOException("installations or default selection changed while downloading; "
                    + "the new registered copy was retained but was not made the default");
        }
        return newId.equals(current.defaultInstallationId());
    }

    private static String expectedDefaultAfterRegistration(RegistryData before, String newId) {
        return before.defaultInstallationId() == null ? newId : before.defaultInstallationId();
    }

    private InstallationRecord resolve(String id) throws IOException {
        RegistryData current = registries.read(registry);
        return registries.resolve(current, id);
    }

    private void createPlannedParents(Plan plan) throws IOException {
        for (Path directory : plan.missingParents()) {
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException error) {
                throw new IOException("a planned parent appeared before it could be created: "
                        + directory, error);
            }
            StrictPathSafety.requireDirectory(directory, "created install parent");
        }
    }

    private List<Path> validateProposedDestination(Path target, RegistryData data)
            throws IOException {
        if (target.getFileName() == null) {
            throw new IOException("installation destination must name a new folder");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("destination must be wholly nonexistent, including empty "
                    + "folders; choose another folder or import the existing copy");
        }
        Path registryParent = registry.getParent();
        if (registryParent == null) throw new IOException("registry has no parent");
        Path receipts = new ReceiptStore().metadataDirectory(registry);
        Path logs = new OperationLogStore(registry).defaultDirectory();
        for (Path reserved : List.of(registry, receipts, logs)) {
            if (overlaps(target, reserved)) {
                throw new IOException("destination overlaps launcher state or diagnostics: "
                        + reserved);
            }
        }
        for (InstallationRecord record : data.installations()) {
            if (overlaps(target, Path.of(record.canonicalRoot()))) {
                throw new IOException("destination overlaps registered installation: "
                        + record.canonicalRoot());
            }
            if (record.name().equals(NORMAL_NAME)) {
                throw new IOException("an installation named Default already exists; use "
                        + "Installations to select it or choose another name");
            }
        }

        List<Path> registryParents = missingParentDirectories(registry);
        List<Path> destinationParents = missingParentDirectories(target);
        LinkedHashSet<Path> plannedParents = new LinkedHashSet<>(registryParents);
        plannedParents.addAll(destinationParents);
        if (plannedParents.size() > MAX_CREATED_PARENTS) {
            throw new IOException("normal install needs too many new parent folders; choose a "
                    + "writable existing parent");
        }
        requireWritableAncestor(registry, registryParents, "launcher state parent");
        requireWritableAncestor(target, destinationParents, "install parent");
        return List.copyOf(plannedParents);
    }

    private static void requireWritableAncestor(Path target, List<Path> missing, String label)
            throws IOException {
        Path existing = missing.isEmpty() ? target.getParent() : missing.getFirst().getParent();
        if (existing == null) throw new IOException(label + " has no existing ancestor");
        StrictPathSafety.requireDirectory(existing, label + " ancestor");
        if (!Files.isWritable(existing)) {
            throw new IOException(label + " is not writable: " + existing);
        }
    }

    /** Returns absent parent directories in top-down creation order. */
    private static List<Path> missingParentDirectories(Path target) throws IOException {
        List<Path> reversed = new ArrayList<>();
        Path cursor = target.getParent();
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            reversed.add(cursor);
            cursor = cursor.getParent();
        }
        if (cursor == null) throw new IOException("destination has no existing parent ancestor");
        StrictPathSafety.requireDirectory(cursor, "install parent ancestor");
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static Path nearestExistingDirectory(Path target) throws IOException {
        Path cursor = target.getParent();
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            cursor = cursor.getParent();
        }
        if (cursor == null) throw new IOException("destination has no existing parent ancestor");
        return StrictPathSafety.requireDirectory(cursor, "Java validation working directory");
    }

    private RegistrySnapshot snapshot() throws IOException {
        try {
            StrictPathSafety.requireFile(registry, "launcher registry");
            return new RegistrySnapshot(true, registries.read(registry));
        } catch (NoSuchFileException error) {
            verifyAbsentPath(registry);
            return new RegistrySnapshot(false,
                    new RegistryData(RegistryStore.SCHEMA, null, List.of()));
        }
    }

    private static void verifyAbsentPath(Path file) throws IOException {
        Path cursor = file.getRoot();
        for (Path component : file) {
            cursor = cursor == null ? component : cursor.resolve(component);
            if (cursor.equals(file)) return;
            try {
                BasicFileAttributes attributes = Files.readAttributes(cursor,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isDirectory()) {
                    throw new IOException("launcher state path traverses a linked, reparse, "
                            + "special, or non-directory object: " + cursor);
                }
            } catch (NoSuchFileException missing) {
                return;
            }
        }
    }

    private static FollowChannel requireAllowedChannel(FollowChannel channel)
            throws IOException {
        if (channel != FollowChannel.MILESTONE && channel != FollowChannel.DEVELOPMENT) {
            throw new IOException("normal install channel must be Milestone or Development");
        }
        return channel;
    }

    private static OfficialRepository requireAllowedRepository(OfficialRepository repository)
            throws IOException {
        if (repository != OfficialRepository.MEKHQ
                && repository != OfficialRepository.MEGAMEK
                && repository != OfficialRepository.LAB) {
            throw new IOException(
                    "normal install application must be MekHQ, MegaMek, or MegaMekLab");
        }
        return repository;
    }

    private static Set<String> requiredProducts(OfficialRepository repository)
            throws IOException {
        return switch (requireAllowedRepository(repository)) {
            case MEKHQ -> SUITE_PRODUCTS;
            case MEGAMEK -> MEGAMEK_PRODUCTS;
            case LAB -> LAB_PRODUCTS;
        };
    }

    private void requireTarget(ChannelCatalog.Target target,
                               OfficialRepository requestedRepository,
                               FollowChannel requestedChannel) throws IOException {
        if (target == null || target.channel() != requestedChannel
                || target.repository() != requestedRepository
                || target.release() == null || target.asset() == null
                || !target.release().tag().equals("v" + target.version())
                || !target.release().assets().contains(target.asset())
                || target.source() == null || target.source().isBlank()) {
            throw new IOException("official " + requestedChannel + " "
                    + requestedRepository.key() + " target is incomplete or mismatched");
        }
        ReleaseCatalog.Assessment assessment =
                new ReleaseCatalog(transport).assess(requestedRepository, target.release());
        if (!assessment.eligible() || !target.asset().equals(assessment.asset())) {
            throw new IOException("official " + requestedChannel + " "
                    + requestedRepository.key() + " target has no uniquely eligible asset: "
                    + assessment.reason());
        }
    }

    private CheckConfiguration checkConfiguration() throws IOException {
        final CheckConfiguration configuration;
        try {
            configuration = checkConfigurations.read();
        } catch (IOException error) {
            throw new IOException("launcher automatic-check setting is invalid or unreadable; "
                    + "review a fresh install confirmation: " + detail(error), error);
        }
        if (configuration == null) {
            throw new IOException("launcher automatic-check setting is unavailable; review a "
                    + "fresh install confirmation");
        }
        return configuration;
    }

    private static boolean sameQuote(Plan plan, ChannelCatalog.Target target) {
        return plan.channel() == target.channel()
                && plan.repository() == target.repository()
                && plan.version().equals(target.version())
                && plan.release().equals(target.release())
                && plan.asset().equals(target.asset())
                && plan.source().equals(target.source());
    }

    private static boolean overlaps(Path first, Path second) {
        Path a = first.toAbsolutePath().normalize();
        Path b = second.toAbsolutePath().normalize();
        return a.equals(b) || a.startsWith(b) || b.startsWith(a);
    }

    private static String detail(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    public record RegistrySnapshot(boolean present, RegistryData data) {
    }

    public record Plan(Path registry, RegistrySnapshot registrySnapshot, Path destination,
                       List<Path> missingParents, Path javaExecutable, int javaFeature,
                       FollowChannel channel, OfficialRepository repository, String version,
                       Set<String> requiredProducts, ReleaseCatalog.Release release,
                       ReleaseCatalog.Asset asset,
                       String source, CheckConfiguration checkConfiguration) {
        public Plan {
            java.util.Objects.requireNonNull(registrySnapshot, "registrySnapshot");
            java.util.Objects.requireNonNull(requiredProducts, "requiredProducts");
            java.util.Objects.requireNonNull(checkConfiguration, "checkConfiguration");
            registry = registry.toAbsolutePath().normalize();
            destination = destination.toAbsolutePath().normalize();
            javaExecutable = javaExecutable.toAbsolutePath().normalize();
            missingParents = List.copyOf(missingParents);
            requiredProducts = Set.copyOf(requiredProducts);
            if (channel != FollowChannel.MILESTONE
                    && channel != FollowChannel.DEVELOPMENT) {
                throw new IllegalArgumentException(
                        "normal install channel must be Milestone or Development");
            }
            if (repository != OfficialRepository.MEKHQ
                    && repository != OfficialRepository.MEGAMEK
                    && repository != OfficialRepository.LAB) {
                throw new IllegalArgumentException(
                        "normal install repository must be official");
            }
            try {
                if (!requiredProducts.equals(NormalInstallService.requiredProducts(repository))) {
                    throw new IllegalArgumentException(
                            "normal install product must match its official repository");
                }
            } catch (IOException impossible) {
                throw new IllegalArgumentException(impossible.getMessage(), impossible);
            }
            if (version == null || version.isBlank() || release == null || asset == null
                    || !release.tag().equals("v" + version)
                    || !release.assets().contains(asset)
                    || source == null || source.isBlank()) {
                throw new IllegalArgumentException(
                        "normal install release target is incomplete or mismatched");
            }
            if (checkConfiguration == null) {
                throw new IllegalArgumentException("check configuration is required");
            }
        }
    }

    @FunctionalInterface
    public interface CheckConfigurationSource {
        CheckConfiguration read() throws IOException;
    }

    public record CheckConfiguration(boolean checkOnOpen, String revision) {
        private static final CheckConfiguration DEFAULT =
                new CheckConfiguration(true, "built-in:true");

        public CheckConfiguration {
            if (revision == null || revision.isBlank()) {
                throw new IllegalArgumentException("check configuration revision is required");
            }
        }
    }

    public record Result(InstallationRecord record, ReleaseCatalog.Release release,
                         ReleaseCatalog.Asset asset, Path destination,
                         org.megamek.launcher.update.OwnershipReceipt ownershipReceipt,
                         boolean becameMain) {
    }

    public static final class PublishedInstallationException extends IOException {
        private final Path destination;
        private final String installationId;

        public PublishedInstallationException(Path destination, String installationId,
                                              String message, Throwable cause) {
            super(message, cause);
            this.destination = destination;
            this.installationId = installationId;
        }

        public Path destination() {
            return destination;
        }

        public String installationId() {
            return installationId;
        }
    }
}
