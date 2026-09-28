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

package org.megamek.launcher.launch;

import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.registry.InstallationRecord;

import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class ApplicationLauncher {
    private final ProcessRunner runner;
    private final RootCoordinator coordinator;

    public ApplicationLauncher() {
        this(new DirectProcessRunner(), new RootCoordinator());
    }

    public ApplicationLauncher(ProcessRunner runner) {
        this(runner, RootCoordinator.inMemory());
    }

    public ApplicationLauncher(ProcessRunner runner, RootCoordinator coordinator) {
        this.runner = runner;
        this.coordinator = coordinator;
    }

    public List<String> command(InstallationRecord record, String productKey,
                                JavaRuntime.CurrentJava gameJava)
            throws IOException, InterruptedException {
        if (gameJava == null) {
            throw new IOException("effective Game Java was not resolved");
        }
        Path root = Path.of(record.canonicalRoot());
        Inspection current = new InstallationInspector().inspect(root);
        if (!current.canonicalRoot().equals(record.canonicalRoot())) {
            throw new IOException("registered installation moved or root identity changed");
        }
        if (!current.observedBuild().equals(record.observedBuild())) {
            throw new IOException("registered installation build changed: expected "
                    + record.observedBuild() + ", observed " + current.observedBuild());
        }
        Product product = current.products().stream()
                .filter(item -> item.key().equals(productKey)).findFirst()
                .orElseThrow(() -> new IOException("product is unavailable in current layout: "
                        + productKey));
        Product registered = record.products().stream()
                .filter(item -> item.key().equals(productKey)).findFirst()
                .orElseThrow(() -> new IOException("product was not registered: " + productKey));
        if (!product.equals(registered)) {
            throw new IOException("registered product layout changed; inspect and register again");
        }
        Path java = gameJava.requireUnchanged();
        if (java.startsWith(root)) {
            throw new IOException("Game Java must be external to the application directory");
        }
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-Xmx" + InstallationInspector.heapMb(productKey) + "m");
        command.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.util.concurrent=ALL-UNNAMED");
        command.add("-Dsun.awt.disablegrab=true");
        command.add("-cp");
        List<String> jars = new ArrayList<>();
        jars.add(product.jar());
        jars.addAll(product.classPath());
        command.add(String.join(File.pathSeparator, jars));
        command.add(product.mainClass());
        return List.copyOf(command);
    }

    public int launch(InstallationRecord record, String product,
                      JavaRuntime.CurrentJava gameJava)
            throws IOException, InterruptedException {
        Path root = Path.of(record.canonicalRoot());
        try (RootCoordinator.Lease lease = coordinator.acquireForLaunch(root)) {
            lease.requireNoPendingUpdate();
            List<String> command = command(record, product, gameJava);
            lease.markLaunchStarting();
            boolean childCompleted = false;
            java.util.concurrent.atomic.AtomicReference<IOException> markerFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean childPublished =
                    new java.util.concurrent.atomic.AtomicBoolean();
            try {
                ProcessRunner.Result result = runner.runTracked(command, root,
                        Duration.ofMillis(Long.MAX_VALUE), true, identity -> {
                            try {
                                lease.markChild(identity);
                                childPublished.set(true);
                                // Publication is durable before the OS gate is released. The
                                // child wait must not serialize subsequent launches.
                                lease.close();
                            } catch (IOException e) {
                                markerFailure.compareAndSet(null, e);
                            }
                        });
                childCompleted = true;
                if (markerFailure.get() != null) throw markerFailure.get();
                if (!childPublished.get() && coordinator.persistent()) {
                    throw new IOException("child identity was not published; launch state is unknown");
                }
                if (result.timedOut()) {
                    throw new IOException("application exceeded launcher wait limit");
                }
                return result.exitCode();
            } finally {
                // Any runner failure after STARTING is ambiguous: ProcessBuilder.start may have
                // returned just before callback publication failed or the launcher was
                // interrupted. Keep STARTING/RUNNING unless the child wait completed.
                if (childCompleted && markerFailure.get() == null
                        && (childPublished.get() || !coordinator.persistent())) {
                    try (RootCoordinator.Lease cleanup = coordinator.acquireForLaunch(root)) {
                        cleanup.clearLaunchMarker(lease.launchId());
                    }
                }
            }
        }
    }
}
