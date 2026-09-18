package org.megamek.launcher.operation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationContextTest {
    @Test
    void reportsOrderedTypedProgressWithoutInventingUnknownTotals() throws Exception {
        List<OperationProgress> events = new ArrayList<>();
        OperationContext context = new OperationContext(OperationType.UPDATE_PREVIEW, events::add);

        context.phase(OperationPhase.METADATA, "metadata");
        context.progress(OperationPhase.DOWNLOAD, 10, 100, ProgressUnit.BYTES, "download");
        context.progress(OperationPhase.DOWNLOAD, 8, 100, ProgressUnit.BYTES, "stale");
        context.progress(OperationPhase.VERIFY, 100, 100, ProgressUnit.BYTES, "verified");
        context.progress(OperationPhase.EXTRACT, 3, -1, ProgressUnit.FILES, "extracting");
        context.finish(OperationOutcome.SUCCEEDED, "done");

        assertEquals(10, events.get(2).completed(),
                "same-phase byte progress must not move backwards");
        assertTrue(events.get(1).determinate());
        assertFalse(events.get(4).determinate(),
                "unknown archive entry totals must remain indeterminate");
        assertEquals(OperationPhase.FINAL, events.getLast().phase());
        assertEquals(OperationOutcome.SUCCEEDED, events.getLast().outcome());
        assertThrows(IllegalStateException.class,
                () -> context.progress(OperationPhase.DOWNLOAD, 1, 1,
                        ProgressUnit.BYTES, "regression"));
    }

    @Test
    void cancelAndFinalizationRaceHasExactlyOneWinnerAndLateRequestsNeverInterruptReuse()
            throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            OperationContext context = new OperationContext(OperationType.UPDATE_APPLY);
            CountDownLatch start = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var cancel = executor.submit(() -> {
                    start.await();
                    return context.requestCancellation();
                });
                var boundary = executor.submit(() -> {
                    start.await();
                    try {
                        return context.enterFinalization("transaction started");
                    } catch (OperationCancelledException cancelled) {
                        return false;
                    }
                });
                start.countDown();
                boolean finalizationWon = boundary.get(5, TimeUnit.SECONDS);
                OperationContext.CancellationRequest request =
                        cancel.get(5, TimeUnit.SECONDS);
                assertTrue(finalizationWon ^ request.accepted(),
                        "cancel and finalization cannot both win");
            } finally {
                executor.shutdownNow();
            }
        }

        OperationContext finished = new OperationContext(OperationType.UPDATE_APPLY);
        AtomicBoolean interruptedOnReuse = new AtomicBoolean();
        var single = Executors.newSingleThreadExecutor();
        try {
            single.submit(() -> {
                try (OperationContext.WorkerRegistration ignored = finished.activate()) {
                    finished.phase(OperationPhase.APPLY, "boundary");
                    finished.enterFinalization("transaction started");
                } catch (InterruptedException impossible) {
                    Thread.currentThread().interrupt();
                }
            }).get(5, TimeUnit.SECONDS);
            finished.finish(OperationOutcome.SUCCEEDED, "done");
            assertFalse(finished.requestCancellation().accepted());
            single.submit(() -> interruptedOnReuse.set(Thread.currentThread().isInterrupted()))
                    .get(5, TimeUnit.SECONDS);
            assertFalse(interruptedOnReuse.get(),
                    "a stale cancellation must not interrupt a reused pool thread");
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void acceptedCancellationClosesOnlyOwnedResourceAndInterruptsRegisteredWorker()
            throws Exception {
        OperationContext context = new OperationContext(OperationType.FRESH_INSTALL);
        CountDownLatch registered = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        CountDownLatch resourceClosed = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> {
                try (OperationContext.WorkerRegistration ignored = context.activate();
                     OperationContext.ResourceRegistration resource =
                             context.interruptible(() -> {
                                 closed.set(true);
                                 resourceClosed.countDown();
                             })) {
                    registered.countDown();
                    CountDownLatch never = new CountDownLatch(1);
                    never.await();
                    return false;
                } catch (InterruptedException cancelled) {
                    Thread.interrupted();
                    return true;
                }
            });
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertTrue(context.requestCancellation().accepted());
            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertTrue(resourceClosed.await(5, TimeUnit.SECONDS));
            assertTrue(closed.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
