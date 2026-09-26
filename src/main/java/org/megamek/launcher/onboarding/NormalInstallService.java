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

package org.megamek.launcher.onboarding;

import org.megamek.launcher.channel.ChannelCatalog;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.OfficialYamlChannelCatalog;
import org.megamek.launcher.channel.QuickInstallOption;
import org.megamek.launcher.diagnostics.OperationLogStore;
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
 * destination, repository/channel/source version, tag, asset URL/name/size/digest.
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
    private final ChannelCatalog channels;
    private final ChannelPreferenceStore preferences;
    private final PlatformInstallLocations installLocations;

    public NormalInstallService(Path registry, ReleaseTransport transport) {
        this(registry, transport, new RegistryStore(),
                new OfficialYamlChannelCatalog(transport), new ChannelPreferenceStore(),
                PlatformInstallLocations.customRegistry());
    }

    public NormalInstallService(Path registry, ReleaseTransport transport,
                                PlatformInstallLocations installLocations) {
        this(registry, transport, new RegistryStore(),
                new OfficialYamlChannelCatalog(transport), new ChannelPreferenceStore(),
                installLocations);
    }

    public NormalInstallService(Path registry, ReleaseTransport transport,
                                RegistryStore registries,
                                ChannelCatalog channels,
                                ChannelPreferenceStore preferences) {
        this(registry, transport, registries, channels, preferences,
                PlatformInstallLocations.customRegistry());
    }

    public NormalInstallService(Path registry, ReleaseTransport transport,
                                RegistryStore registries,
                                ChannelCatalog channels, ChannelPreferenceStore preferences,
                                PlatformInstallLocations installLocations) {
        this.registry = registry.toAbsolutePath().normalize();
        this.transport = transport;
        this.registries = registries;
        this.channels = channels;
        this.preferences = preferences;
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
     * Resolves current official metadata, but performs no filesystem write or package download.
     */
    public Plan prepare(Path requestedDestination)
            throws IOException, InterruptedException {
        return prepare(OfficialRepository.MEKHQ, FollowChannel.MILESTONE,
                requestedDestination);
    }

    /** Compatibility overload for one of the two explicit official MekHQ channels. */
    public Plan prepare(FollowChannel requestedChannel, Path requestedDestination)
            throws IOException, InterruptedException {
        return prepare(OfficialRepository.MEKHQ, requestedChannel,
                requestedDestination);
    }

    /**
     * Resolves one fixed official product/channel pair. No custom repository, arbitrary URL,
     * inferred bundle, or channel fallback is accepted.
     */
    public Plan prepare(OfficialRepository requestedRepository, FollowChannel requestedChannel,
                        Path requestedDestination)
            throws IOException, InterruptedException {
        OfficialRepository repository = requireAllowedRepository(requestedRepository);
        FollowChannel channel = requireAllowedChannel(requestedChannel);
        return prepareTarget(repository, channel, requestedDestination,
                TargetKind.CURRENT_CHANNEL,
                () -> channels.target(channel, repository));
    }

    /**
     * Plans from one immutable current-channel option already captured for this launcher
     * session. No channel pointer or release endpoint is read while building the local quote.
     */
    public Plan prepareCapturedCurrent(QuickInstallOption requestedOption,
                                       Path requestedDestination)
            throws IOException, InterruptedException {
        if (requestedOption == null) {
            throw new IOException("a captured quick-install option is required");
        }
        QuickInstallOption.Key key = requestedOption.key();
        OfficialRepository repository = requireAllowedRepository(key.repository());
        FollowChannel channel = requireAllowedChannel(key.channel());
        ChannelCatalog.Target target = requestedOption.target();
        if (!OfficialYamlChannelCatalog.SOURCE.toString().equals(target.source())) {
            throw new IOException("captured quick-install target must come from the official "
                    + "current-channel snapshot");
        }
        return prepareTarget(repository, channel, requestedDestination,
                TargetKind.CAPTURED_CURRENT, () -> target);
    }

    /**
     * Plans one exact release selected from the unified release browser. The channel is the new
     * installation's immutable update track; it does not classify an otherwise unknown release.
     */
    public Plan prepareExact(OfficialRepository requestedRepository,
                             FollowChannel requestedFutureChannel, String requestedTag,
                             Path requestedDestination)
            throws IOException, InterruptedException {
        OfficialRepository repository = requireAllowedRepository(requestedRepository);
        FollowChannel futureChannel = requireAllowedChannel(requestedFutureChannel);
        return prepareTarget(repository, futureChannel, requestedDestination,
                TargetKind.BROWSED_EXACT, () -> {
            ReleaseCatalog catalog = new ReleaseCatalog(transport);
            ReleaseCatalog.Release release = catalog.exact(repository, requestedTag);
            ReleaseCatalog.Assessment assessment = catalog.assess(repository, release);
            if (!assessment.eligible()) {
                throw new IOException("official exact " + repository.key()
                        + " release is unavailable: " + assessment.reason());
            }
            return new ChannelCatalog.Target(futureChannel, displayVersion(release.tag()),
                    repository, release, assessment.asset(),
                    ReleaseCatalog.exactMetadataUri(repository, release.tag()).toString());
        });
    }

    private Plan prepareTarget(OfficialRepository repository, FollowChannel channel,
                               Path requestedDestination,
                               TargetKind targetKind,
                               TargetResolver targetResolver)
            throws IOException, InterruptedException {
        if (requestedDestination == null) {
            throw new IOException("normal install destination is required");
        }
        Path destination = requestedDestination.toAbsolutePath().normalize();
        RegistrySnapshot snapshot = snapshot();
        List<Path> missingParents = validateProposedDestination(destination, snapshot.data());
        ChannelCatalog.Target target = targetResolver.resolve();
        requireTarget(target, repository, channel);
        String automaticName = friendlyName(repository, channel, target.version());
        if (snapshot.data().installations().stream()
                .anyMatch(record -> record.name().equals(automaticName))) {
            throw new IOException("an installation named " + automaticName
                    + " is already registered");
        }
        return new Plan(registry, snapshot, destination, List.copyOf(missingParents),
                target.channel(), target.repository(), target.version(),
                requiredProducts(repository), target.release(), target.asset(), target.source(),
                targetKind);
    }

    /**
     * Rechecks the complete quote before binary transfer, creates only consented parent
     * directories, publishes atomically, and then configures channel/Main selection.
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
        ChannelCatalog.Target fresh = freshTarget(plan);
        requireTarget(fresh, repository, channel);
        if (!sameQuote(plan, fresh)) {
            throw new IOException("official release or asset metadata changed; review and consent "
                    + "to a fresh install confirmation");
        }
        context.checkpoint();
        createPlannedParents(plan);
        VerifiedPackageFetcher.ExpectedAsset expected =
                new VerifiedPackageFetcher.ExpectedAsset(fresh.asset().name(),
                        fresh.asset().size(), fresh.asset().digest(), fresh.asset().url());
        final FreshInstaller.Result installed;
        try {
            installed = new FreshInstaller(transport).installMatchingProducts(plan.repository(),
                    plan.release().tag(), plan.destination(), registry, friendlyName(plan), progress,
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
            preferences.initializeManaged(registry, configured, installed.ownershipReceipt(),
                    plan.channel(), true);
            boolean becameMain = requireExpectedPostInstall(plan, configured.id());
            configured = resolve(configured.id());
            return new Result(configured, installed.release(), installed.asset(),
                    installed.destination(), installed.ownershipReceipt(), becameMain);
        } catch (IOException error) {
            throw new PublishedInstallationException(installed.destination(), configured.id(),
                    "The downloaded copy is valid and registered, but "
                            + plan.channel() + " " + plan.repository().key() + " fixed-channel "
                            + "provenance, or default selection still needs repair in "
                            + "Installations. Do not download over the retained copy or assign "
                            + "it another channel. " + detail(error), error);
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
                || target.version() == null || target.version().isBlank()
                || !target.version().equals(displayVersion(target.release().tag()))
                || OfficialYamlChannelCatalog.SOURCE.toString().equals(target.source())
                && !target.release().tag().equals("v" + target.version())
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

    private static boolean sameQuote(Plan plan, ChannelCatalog.Target target) {
        return plan.channel() == target.channel()
                && plan.repository() == target.repository()
                && plan.version().equals(target.version())
                && plan.release().tag().equals(target.release().tag())
                && plan.asset().name().equals(target.asset().name())
                && plan.asset().size() == target.asset().size()
                && plan.asset().url().equals(target.asset().url())
                && publishedDigestCompatible(plan.asset(), target.asset())
                && plan.source().equals(target.source());
    }

    /**
     * A quoted published digest is immutable. Quoted absence may become a valid published digest
     * only at the exact pre-transfer refresh; all other selected-asset identity remains fixed.
     */
    private static boolean publishedDigestCompatible(ReleaseCatalog.Asset quoted,
                                                     ReleaseCatalog.Asset refreshed) {
        if (quoted.publishedDigest().isEmpty()) {
            return refreshed.publishedDigest().isEmpty()
                    || refreshed.publishedDigest().orElse(null)
                    instanceof org.megamek.launcher.release.PackageDigest.ValidPublished;
        }
        return quoted.publishedDigest().equals(refreshed.publishedDigest());
    }

    private ChannelCatalog.Target freshTarget(Plan plan)
            throws IOException, InterruptedException {
        if (plan.targetKind() == TargetKind.CURRENT_CHANNEL) {
            return channels.target(plan.channel(), plan.repository());
        }
        String exactSource = ReleaseCatalog.exactMetadataUri(
                plan.repository(), plan.release().tag()).toString();
        if (plan.targetKind() == TargetKind.BROWSED_EXACT
                && !exactSource.equals(plan.source())) {
            throw new IOException("normal install metadata source is not an approved exact "
                    + "official release endpoint");
        }
        if (plan.targetKind() == TargetKind.CAPTURED_CURRENT
                && !OfficialYamlChannelCatalog.SOURCE.toString().equals(plan.source())) {
            throw new IOException("captured current install metadata source is not the "
                    + "official channel snapshot");
        }
        ReleaseCatalog catalog = new ReleaseCatalog(transport);
        ReleaseCatalog.Release release = catalog.exact(
                plan.repository(), plan.release().tag());
        ReleaseCatalog.Assessment assessment = catalog.assess(plan.repository(), release);
        if (!assessment.eligible()) {
            throw new IOException("official exact " + plan.repository().key()
                    + " release is unavailable: " + assessment.reason());
        }
        return new ChannelCatalog.Target(plan.channel(), displayVersion(release.tag()),
                plan.repository(), release, assessment.asset(), plan.source());
    }

    private static String displayVersion(String tag) {
        return tag != null && tag.length() > 1
                && (tag.charAt(0) == 'v' || tag.charAt(0) == 'V')
                ? tag.substring(1) : tag;
    }

    /** Friendly registration/folder label with no repository slug or duplicated tag prefix. */
    public static String friendlyName(OfficialRepository repository, FollowChannel channel,
                                      String versionOrTag) throws IOException {
        requireAllowedRepository(repository);
        requireAllowedChannel(channel);
        String product = switch (repository) {
            case MEKHQ -> "MekHQ";
            case MEGAMEK -> "MegaMek";
            case LAB -> "MegaMekLab";
        };
        String version = displayVersion(versionOrTag);
        return product + " " + channel + (version == null || version.isBlank()
                ? "" : " (" + version + ")");
    }

    public static String friendlyName(Plan plan) throws IOException {
        if (plan == null) throw new IOException("normal install plan is required");
        return friendlyName(plan.repository(), plan.channel(), plan.version());
    }

    private static boolean isExactSource(OfficialRepository repository, String tag,
                                         String source) {
        try {
            return ReleaseCatalog.exactMetadataUri(repository, tag).toString().equals(source);
        } catch (IOException invalid) {
            return false;
        }
    }

    @FunctionalInterface
    private interface TargetResolver {
        ChannelCatalog.Target resolve() throws IOException, InterruptedException;
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
                       List<Path> missingParents,
                       FollowChannel channel, OfficialRepository repository, String version,
                       Set<String> requiredProducts, ReleaseCatalog.Release release,
                       ReleaseCatalog.Asset asset,
                       String source, TargetKind targetKind) {
        public Plan(Path registry, RegistrySnapshot registrySnapshot, Path destination,
                    List<Path> missingParents,
                    FollowChannel channel, OfficialRepository repository, String version,
                    Set<String> requiredProducts, ReleaseCatalog.Release release,
                    ReleaseCatalog.Asset asset, String source) {
            this(registry, registrySnapshot, destination, missingParents,
                    channel, repository, version, requiredProducts, release, asset,
                    source,
                    OfficialYamlChannelCatalog.SOURCE.toString().equals(source)
                            ? TargetKind.CURRENT_CHANNEL : TargetKind.BROWSED_EXACT);
        }

        public Plan {
            java.util.Objects.requireNonNull(registrySnapshot, "registrySnapshot");
            java.util.Objects.requireNonNull(requiredProducts, "requiredProducts");
            java.util.Objects.requireNonNull(targetKind, "targetKind");
            registry = registry.toAbsolutePath().normalize();
            destination = destination.toAbsolutePath().normalize();
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
                    || !version.equals(displayVersion(release.tag()))
                    || OfficialYamlChannelCatalog.SOURCE.toString().equals(source)
                    && !release.tag().equals("v" + version)
                    || !release.assets().contains(asset)
                    || source == null || source.isBlank()) {
                throw new IllegalArgumentException(
                        "normal install release target is incomplete or mismatched");
            }
            boolean currentSource =
                    OfficialYamlChannelCatalog.SOURCE.toString().equals(source);
            if ((targetKind == TargetKind.CURRENT_CHANNEL
                    || targetKind == TargetKind.CAPTURED_CURRENT) && !currentSource) {
                throw new IllegalArgumentException(
                        "current normal install must use the official channel source");
            }
            if (targetKind == TargetKind.BROWSED_EXACT
                    && !isExactSource(repository, release.tag(), source)) {
                throw new IllegalArgumentException(
                        "browsed normal install must use its exact release source");
            }
        }

        /**
         * True only when the release itself came from the authoritative current channel pointer.
         */
        public boolean currentChannelTarget() {
            return targetKind != TargetKind.BROWSED_EXACT;
        }

        /** True when Home captured the current pointer once for this frame session. */
        public boolean capturedCurrentTarget() {
            return targetKind == TargetKind.CAPTURED_CURRENT;
        }
    }

    public enum TargetKind {
        CURRENT_CHANNEL,
        CAPTURED_CURRENT,
        BROWSED_EXACT
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
