/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPopupMenu;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

final class SwingTestSupport {
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final ThreadLocal<List<PendingAction>> ACTIONS =
            ThreadLocal.withInitial(CopyOnWriteArrayList::new);

    private SwingTestSupport() {}

    static <T> T onEdt(Callable<T> operation) throws Exception {
        checkActions();
        return onEdt(operation, TIMEOUT);
    }

    private static <T> T onEdt(Callable<T> operation, Duration timeout) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return operation.call();
        FutureTask<T> task = new FutureTask<>(operation);
        SwingUtilities.invokeLater(task);
        return result(task, timeout, "EDT operation");
    }

    static <T> T await(String description, Callable<T> probe) throws Exception {
        return await(description, probe, TIMEOUT);
    }

    static <T> T await(String description, Callable<T> probe, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            checkActions();
            T value = onEdt(probe, remaining(deadline));
            if (value != null) return value;
            Thread.sleep(20);
        }
        checkActions();
        throw new AssertionError("Timed out waiting for " + description);
    }

    static void awaitCondition(String description, Callable<Boolean> condition) throws Exception {
        await(description, () -> condition.call() ? Boolean.TRUE : null);
    }

    static AbstractButton click(Container root, String name) throws Exception {
        PendingAction action = startAction(root, name, false);
        // Teardown must still be able to join a modal action after its caller times out.
        result(action.completion(), TIMEOUT, "click " + name, false);
        checkActions();
        return action.button();
    }

    static void startClick(Container root, String name) throws Exception {
        startAction(root, name, false);
    }

    static void startClickText(Container root, String text) throws Exception {
        startAction(root, text, true);
    }

    private static PendingAction startAction(Container root, String target, boolean byText)
            throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Start test interactions from the test thread");
        }
        List<PendingAction> actions = ACTIONS.get();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            checkActions();
            CompletableFuture<PendingAction> attempt = new CompletableFuture<>();
            SwingUtilities.invokeLater(() -> {
                if (attempt.isCancelled()) return;
                try {
                    AbstractButton button = byText ? buttonText(root, target)
                            : find(root, target, AbstractButton.class);
                    if (button == null || !button.isShowing() || !button.isEnabled()) {
                        attempt.complete(null);
                        return;
                    }
                    Window owner = root instanceof Window window
                            ? window : SwingUtilities.getWindowAncestor(root);
                    if (owner == null) throw new AssertionError("Click target has no owning window: " + target);
                    PendingAction action = new PendingAction(owner, button, new CompletableFuture<>());
                    actions.add(action);
                    attempt.complete(action);
                    try {
                        button.doClick(0);
                        action.completion().complete(null);
                    } catch (Throwable error) {
                        action.completion().completeExceptionally(error);
                    }
                } catch (Throwable error) {
                    attempt.completeExceptionally(error);
                }
            });
            PendingAction action = result(attempt, remaining(deadline), "starting click " + target);
            if (action != null) {
                checkActions();
                return action;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for showing, enabled click target " + target);
    }

    static JPopupMenu installationMenu(Container root, String recordId) throws Exception {
        AbstractButton invoker = click(root, "installationMenuButton-" + recordId);
        return await("installation menu for " + recordId, () -> {
            for (var element : MenuSelectionManager.defaultManager().getSelectedPath()) {
                if (element instanceof JPopupMenu menu && menu.isShowing()
                        && menu.getInvoker() == invoker) return menu;
            }
            return null;
        });
    }

    static JDialog dialog(Window owner, String title) throws Exception {
        return await("owned dialog " + title, () -> showingDialog(owner, title));
    }

    static JDialog showingDialog(Window owner, String title) {
        for (Window child : owner.getOwnedWindows()) {
            if (child instanceof JDialog dialog && dialog.isShowing()
                    && title.equals(dialog.getTitle())) return dialog;
            JDialog nested = showingDialog(child, title);
            if (nested != null) return nested;
        }
        return null;
    }

    static <T extends Window> T showingWindow(Window owner, Class<T> type) {
        for (Window child : owner.getOwnedWindows()) {
            if (type.isInstance(child) && child.isShowing()) return type.cast(child);
            T nested = showingWindow(child, type);
            if (nested != null) return nested;
        }
        return null;
    }

    static Component find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                Component found = find(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    static <T extends Component> T find(Container root, String name, Class<T> type) {
        Component component = find(root, name);
        if (component == null) return null;
        if (!type.isInstance(component)) {
            throw new AssertionError(name + " is " + component.getClass().getName()
                    + ", expected " + type.getName());
        }
        return type.cast(component);
    }

    static int countNamed(Container root, String name) throws Exception {
        return onEdt(() -> count(root, child -> name.equals(child.getName())));
    }

    static int countButtonText(Container root, String text) throws Exception {
        return onEdt(() -> count(root, child ->
                child instanceof JButton button && text.equals(button.getText())));
    }

    private static int count(Container root, Predicate<Component> matches) {
        int count = 0;
        for (Component child : root.getComponents()) {
            if (matches.test(child)) count++;
            if (child instanceof Container nested) count += count(nested, matches);
        }
        return count;
    }

    private static AbstractButton buttonText(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) {
                AbstractButton found = buttonText(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    static void dispose(Window owner) throws Exception {
        List<PendingAction> actions = ACTIONS.get();
        List<PendingAction> owned = onEdt(() -> {
            List<PendingAction> selected = new ArrayList<>();
            for (PendingAction action : actions) {
                for (Window current = action.owner(); current != null; current = current.getOwner()) {
                    if (current == owner) {
                        selected.add(action);
                        break;
                    }
                }
            }
            disposeTree(owner);
            return selected;
        }, TIMEOUT);
        try {
            for (PendingAction action : owned) {
                result(action.completion(), TIMEOUT, "finishing disposed window interaction", false);
            }
        } finally {
            actions.removeAll(owned);
        }
    }

    private static void disposeTree(Window window) {
        for (Window child : window.getOwnedWindows()) disposeTree(child);
        window.dispose();
    }

    private static void checkActions() throws Exception {
        List<PendingAction> actions = ACTIONS.get();
        for (PendingAction action : actions) {
            if (action.completion().isDone()) {
                actions.remove(action);
                result(action.completion(), TIMEOUT, "asynchronous click");
            }
        }
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
    }

    private static <T> T result(Future<T> future, Duration timeout, String description) throws Exception {
        return result(future, timeout, description, true);
    }

    private static <T> T result(Future<T> future, Duration timeout, String description,
                                boolean cancelWait) throws Exception {
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error failure) throw failure;
            throw new AssertionError(description + " failed", cause);
        } catch (TimeoutException error) {
            if (cancelWait) future.cancel(false);
            throw new AssertionError("Timed out waiting for " + description, error);
        } catch (InterruptedException error) {
            if (cancelWait) future.cancel(false);
            Thread.currentThread().interrupt();
            throw error;
        }
    }

    private record PendingAction(Window owner, AbstractButton button, CompletableFuture<Void> completion) {}
}
