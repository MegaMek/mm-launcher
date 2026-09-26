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

package org.megamek.launcher.operation;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class OperationContext {
    private final UUID id;
    private final Instant startedAt;
    private final OperationType type;
    private final OperationProgressListener listener;
    private final boolean cancellationSupported;
    private final AtomicReference<State> state = new AtomicReference<>(State.CANCELLABLE);
    private final AtomicReference<OperationProgress> latest = new AtomicReference<>();
    private final AtomicReference<IOException> cancellationCloseFailure = new AtomicReference<>();
    private Thread worker;
    private int workerRegistrations;
    private AutoCloseable interruptibleResource;
    private String finalizationReason;

    public OperationContext(OperationType type) {
        this(type, OperationProgressListener.NONE);
    }

    public OperationContext(OperationType type, OperationProgressListener listener) {
        this(UUID.randomUUID(), Instant.now(), type, listener, true);
    }

    private OperationContext(UUID id, Instant startedAt, OperationType type,
                             OperationProgressListener listener, boolean cancellationSupported) {
        this.id = Objects.requireNonNull(id);
        this.startedAt = Objects.requireNonNull(startedAt);
        this.type = Objects.requireNonNull(type);
        this.listener = Objects.requireNonNull(listener);
        this.cancellationSupported = cancellationSupported;
    }

    public static OperationContext none(OperationType type) {
        return new OperationContext(UUID.randomUUID(), Instant.now(), type,
                OperationProgressListener.NONE, false);
    }

    public UUID id() {
        return id;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public OperationType type() {
        return type;
    }

    public WorkerRegistration activate() throws OperationCancelledException {
        synchronized (this) {
            checkpointLocked();
            Thread current = Thread.currentThread();
            if (worker != null && worker != current) {
                throw new IllegalStateException("operation context is already active on another thread");
            }
            worker = current;
            workerRegistrations++;
            return new WorkerRegistration(current);
        }
    }

    public ResourceRegistration interruptible(AutoCloseable resource)
            throws OperationCancelledException {
        Objects.requireNonNull(resource);
        synchronized (this) {
            checkpointLocked();
            if (interruptibleResource != null) {
                throw new IllegalStateException("an interruptible operation resource is already active");
            }
            interruptibleResource = resource;
            return new ResourceRegistration(resource);
        }
    }

    public void phase(OperationPhase phase, String detail)
            throws OperationCancelledException {
        progress(phase, -1, -1, ProgressUnit.NONE, detail);
    }

    public void progress(OperationPhase phase, long completed, long total, ProgressUnit unit,
                         String detail) throws OperationCancelledException {
        checkpoint();
        OperationProgress prior = latest.get();
        if (prior != null && phase.ordinal() < prior.phase().ordinal()) {
            throw new IllegalStateException("operation phase regressed from " + prior.phase()
                    + " to " + phase);
        }
        long reported = completed;
        if (prior != null && prior.phase() == phase && prior.unit() == unit
                && reported >= 0 && prior.completed() >= 0) {
            reported = Math.max(reported, prior.completed());
        }
        emit(phase, reported, total, unit, detail, OperationOutcome.RUNNING);
    }

    public void checkpoint() throws OperationCancelledException {
        synchronized (this) {
            checkpointLocked();
        }
    }

    public void cleanupPhase(String detail) {
        OperationProgress prior = latest.get();
        if (prior != null && prior.phase().ordinal() > OperationPhase.CLEANUP.ordinal()) return;
        state.compareAndSet(State.CANCELLABLE, State.CLEANING);
        emit(OperationPhase.CLEANUP, -1, -1, ProgressUnit.NONE, detail,
                OperationOutcome.RUNNING);
    }

    public boolean enterFinalization(String reason) throws OperationCancelledException {
        Objects.requireNonNull(reason);
        if (!cancellationSupported) {
            finalizationReason = reason;
            state.set(State.FINALIZING);
            emitLatestBoundary();
            return true;
        }
        if (state.compareAndSet(State.CANCELLABLE, State.FINALIZING)) {
            finalizationReason = reason;
            emitLatestBoundary();
            return true;
        }
        checkpoint();
        return state.get() == State.FINALIZING;
    }

    public CancellationRequest requestCancellation() {
        if (!cancellationSupported) {
            return new CancellationRequest(false, "This operation does not support cancellation.",
                    null);
        }
        if (!state.compareAndSet(State.CANCELLABLE, State.CANCEL_REQUESTED)) {
            String reason = state.get() == State.FINALIZING
                    ? finalizationReason
                    : state.get() == State.CLEANING
                    ? "Owned temporary cleanup is already running and cannot be interrupted safely."
                    : "This operation has already finished or cancellation was already requested.";
            return new CancellationRequest(false, reason, cancellationCloseFailure.get());
        }
        AutoCloseable resource;
        Thread activeWorker;
        synchronized (this) {
            resource = interruptibleResource;
            activeWorker = worker;
        }
        if (activeWorker != null && activeWorker != Thread.currentThread()) {
            activeWorker.interrupt();
        }
        if (resource != null) {
            Thread closer = new Thread(() -> {
                IOException closeFailure = closeForCancellation(resource);
                if (closeFailure != null) {
                    cancellationCloseFailure.compareAndSet(null, closeFailure);
                }
            }, "mm-launcher-cancel-close");
            closer.setDaemon(true);
            closer.start();
        }
        emitLatestBoundary();
        return new CancellationRequest(true, "Cancelling at the next safe checkpoint.",
                cancellationCloseFailure.get());
    }

    public void finish(OperationOutcome outcome, String detail) {
        if (outcome == null || outcome == OperationOutcome.RUNNING) {
            throw new IllegalArgumentException("a terminal operation outcome is required");
        }
        synchronized (this) {
            interruptibleResource = null;
            worker = null;
            workerRegistrations = 0;
            state.set(State.FINISHED);
        }
        long completed = outcome == OperationOutcome.SUCCEEDED ? 1 : -1;
        long total = outcome == OperationOutcome.SUCCEEDED ? 1 : -1;
        emit(OperationPhase.FINAL, completed, total, ProgressUnit.NONE, detail, outcome);
    }

    public boolean cancellationRequested() {
        return state.get() == State.CANCEL_REQUESTED;
    }

    public boolean finalizationStarted() {
        return state.get() == State.FINALIZING || state.get() == State.FINISHED;
    }

    public OperationProgress latest() {
        return latest.get();
    }

    public static <T> T cleanup(Cleanup<T> cleanup) throws Exception {
        boolean interrupted = Thread.interrupted();
        try {
            return cleanup.run();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public static void cleanup(CleanupAction cleanup) throws Exception {
        cleanup(() -> {
            cleanup.run();
            return null;
        });
    }

    public void cleanupPreservingCancellation(CleanupAction cleanup) throws Exception {
        boolean interrupted = Thread.interrupted();
        try {
            cleanup.run();
        } finally {
            if (interrupted || cancellationRequested()) Thread.currentThread().interrupt();
        }
    }

    private void emitLatestBoundary() {
        OperationProgress prior = latest.get();
        if (prior != null) {
            emit(prior.phase(), prior.completed(), prior.total(), prior.unit(), prior.detail(),
                    prior.outcome());
        }
    }

    private void emit(OperationPhase phase, long completed, long total, ProgressUnit unit,
                      String detail, OperationOutcome outcome) {
        State current = state.get();
        boolean allowed = cancellationSupported && current == State.CANCELLABLE;
        String reason = allowed ? null : switch (current) {
            case CANCEL_REQUESTED -> "Cancellation was accepted and is in progress.";
            case FINALIZING -> finalizationReason == null
                    ? "Installation finalization has started." : finalizationReason;
            case CLEANING -> "Owned temporary cleanup is running and cannot be interrupted safely.";
            case FINISHED -> "The operation has finished.";
            case CANCELLABLE -> "This operation does not support cancellation.";
        };
        OperationProgress event = new OperationProgress(id, Instant.now(), type, phase,
                completed, total, Objects.requireNonNull(unit),
                detail == null ? "" : detail, outcome, allowed, reason);
        latest.set(event);
        listener.onProgress(event);
    }

    private void checkpointLocked() throws OperationCancelledException {
        if (state.get() != State.CANCEL_REQUESTED) return;
        OperationCancelledException cancelled =
                new OperationCancelledException("operation cancelled before finalization");
        IOException closeFailure = cancellationCloseFailure.get();
        if (closeFailure != null) cancelled.addSuppressed(closeFailure);
        throw cancelled;
    }

    private static IOException closeForCancellation(AutoCloseable resource) {
        if (resource == null) return null;
        try {
            resource.close();
            return null;
        } catch (IOException error) {
            return error;
        } catch (Exception error) {
            return new IOException("interruptible operation resource close failed", error);
        }
    }

    private enum State {
        CANCELLABLE,
        CANCEL_REQUESTED,
        FINALIZING,
        CLEANING,
        FINISHED
    }

    public record CancellationRequest(boolean accepted, String reason, IOException closeFailure) {
    }

    public final class WorkerRegistration implements AutoCloseable {
        private final Thread registered;
        private boolean closed;

        private WorkerRegistration(Thread registered) {
            this.registered = registered;
        }

        @Override
        public void close() {
            synchronized (OperationContext.this) {
                if (closed) return;
                closed = true;
                if (worker == registered && --workerRegistrations == 0) worker = null;
            }
        }
    }

    public final class ResourceRegistration implements AutoCloseable {
        private final AutoCloseable registered;
        private boolean closed;

        private ResourceRegistration(AutoCloseable registered) {
            this.registered = registered;
        }

        @Override
        public void close() {
            synchronized (OperationContext.this) {
                if (closed) return;
                closed = true;
                if (interruptibleResource == registered) interruptibleResource = null;
            }
        }
    }

    @FunctionalInterface
    public interface Cleanup<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    public interface CleanupAction {
        void run() throws Exception;
    }
}
