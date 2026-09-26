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

package org.megamek.launcher.update;

import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.release.VerifiedPackageFetcher;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Process-local preparation/Apply facade. Its private owner identity prevents a prepared package
 * from being redirected through another facade or revived as a filesystem cache token.
 */
public final class PreparedUpdateService {
    private final ReleaseTransport transport;
    private final RegistryStore registries;
    private final ReceiptStore receipts;
    private final RootCoordinator coordinator;
    private final RealUpdateService.FailureHook failureHook;
    private final Object owner = new Object();

    public PreparedUpdateService(ReleaseTransport transport, RootCoordinator coordinator) {
        this(transport, new RegistryStore(), new ReceiptStore(), coordinator, point -> {});
    }

    PreparedUpdateService(ReleaseTransport transport, RegistryStore registries,
                          ReceiptStore receipts, RootCoordinator coordinator,
                          RealUpdateService.FailureHook failureHook) {
        this.transport = Objects.requireNonNull(transport);
        this.registries = Objects.requireNonNull(registries);
        this.receipts = Objects.requireNonNull(receipts);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.failureHook = Objects.requireNonNull(failureHook);
    }

    public PreparedUpdate prepare(Path registry, InstallationRecord record,
                                  OwnershipReceipt receipt, CurrentUpdateState current,
                                  String targetTag,
                                  String assetName, long assetSize, String assetDigest,
                                  PrintStream progress)
            throws IOException, InterruptedException, ManifestException {
        return previewService().prepare(registry, record, receipt, current, targetTag,
                new VerifiedPackageFetcher.ExpectedAsset(assetName, assetSize, assetDigest),
                null, owner, progress);
    }

    public PreparedUpdate prepare(Path registry, InstallationRecord record,
                                  OwnershipReceipt receipt, CurrentUpdateState current,
                                  String targetTag,
                                  String assetName, long assetSize, String assetDigest,
                                  PrintStream progress, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        return previewService().prepare(registry, record, receipt, current, targetTag,
                new VerifiedPackageFetcher.ExpectedAsset(assetName, assetSize, assetDigest),
                null, owner, progress, context);
    }

    public PreparedUpdate prepareRecommended(Path registry, InstallationRecord record,
                                             OwnershipReceipt receipt,
                                             CurrentUpdateState current,
                                             ChannelUpdateChecker.Result expected,
                                             PrintStream progress)
            throws IOException, InterruptedException, ManifestException {
        UpdatePreviewService.Eligibility local = previewService().eligibility(
                registry, record.id());
        if (!local.available() || !local.record().equals(record)
                || !receipt.equals(local.receipt()) || !current.equals(local.current())) {
            throw new IOException("recommended-update source or verified provenance changed "
                    + "before package preparation");
        }
        ChannelUpdateChecker.Recommendation recommendation =
                requireCurrentRecommendation(registry, record, expected);
        return previewService().prepare(registry, record, receipt, current,
                recommendation.targetTag(),
                new VerifiedPackageFetcher.ExpectedAsset(recommendation.assetName(),
                        recommendation.assetSize(), recommendation.assetDigest()),
                recommendation, owner, progress);
    }

    public PreparedUpdate prepareRecommended(Path registry, InstallationRecord record,
                                             OwnershipReceipt receipt,
                                             CurrentUpdateState current,
                                             ChannelUpdateChecker.Result expected,
                                             PrintStream progress, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        UpdatePreviewService.Eligibility local = previewService().eligibility(
                registry, record.id());
        if (!local.available() || !local.record().equals(record)
                || !receipt.equals(local.receipt()) || !current.equals(local.current())) {
            throw new IOException("recommended-update source or verified provenance changed "
                    + "before package preparation");
        }
        ChannelUpdateChecker.Recommendation recommendation =
                requireCurrentRecommendation(registry, record, expected);
        return previewService().prepare(registry, record, receipt, current,
                recommendation.targetTag(),
                new VerifiedPackageFetcher.ExpectedAsset(recommendation.assetName(),
                        recommendation.assetSize(), recommendation.assetDigest()),
                recommendation, owner, progress, context);
    }

    public RealUpdateService.ApplyResult apply(PreparedUpdate prepared, String confirmation,
                                               PrintStream progress)
            throws IOException, InterruptedException, ManifestException {
        return new RealUpdateService(transport, registries, receipts, coordinator, failureHook)
                .applyPrepared(prepared, owner, confirmation, progress);
    }

    public RealUpdateService.ApplyResult apply(PreparedUpdate prepared, String confirmation,
                                               PrintStream progress, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        return new RealUpdateService(transport, registries, receipts, coordinator, failureHook)
                .applyPrepared(prepared, owner, confirmation, progress, context);
    }

    private UpdatePreviewService previewService() {
        return new UpdatePreviewService(transport, registries, receipts);
    }

    private ChannelUpdateChecker.Recommendation requireCurrentRecommendation(
            Path registry, InstallationRecord record, ChannelUpdateChecker.Result expected)
            throws IOException, InterruptedException {
        if (expected == null || !expected.updateAvailable() || !expected.record().equals(record)) {
            throw new IOException("recommended update is missing or belongs to another installation");
        }
        ChannelUpdateChecker.Recommendation captured = expected.recommendation();
        if (!captured.installationId().equals(record.id())
                || !captured.canonicalRoot().equals(record.canonicalRoot())
                || !captured.registeredAt().equals(record.registeredAt())) {
            throw new IOException("recommended update installation binding is stale");
        }
        ChannelUpdateChecker.Result fresh =
                new ChannelUpdateChecker(registry, transport).check(record.id());
        if (!fresh.updateAvailable()
                || !compatibleRecommendation(captured, fresh.recommendation())) {
            throw new IOException("channel source, preference, or target metadata changed; "
                    + "check again and restart the update attempt");
        }
        return fresh.recommendation();
    }

    /**
     * Published-digest absence may become a valid digest at the final metadata refresh. Every
     * other recommendation field remains consent-bound, and a quoted digest may not disappear or
     * change.
     */
    private static boolean compatibleRecommendation(ChannelUpdateChecker.Recommendation quoted,
            ChannelUpdateChecker.Recommendation refreshed) {
        if (refreshed == null) return false;
        boolean digestCompatible = quoted.assetDigest() == null
                ? refreshed.assetDigest() == null
                    || org.megamek.launcher.release.PackageDigest
                    .expectedPublished(refreshed.assetDigest()).isPresent()
                : quoted.assetDigest().equalsIgnoreCase(refreshed.assetDigest());
        return digestCompatible
                && quoted.installationId().equals(refreshed.installationId())
                && quoted.canonicalRoot().equals(refreshed.canonicalRoot())
                && quoted.registeredAt().equals(refreshed.registeredAt())
                && quoted.preference().equals(refreshed.preference())
                && quoted.repository() == refreshed.repository()
                && quoted.source().equals(refreshed.source())
                && quoted.targetTag().equals(refreshed.targetTag())
                && quoted.assetName().equals(refreshed.assetName())
                && quoted.assetSize() == refreshed.assetSize()
                && quoted.notesUrl().equals(refreshed.notesUrl());
    }
}
