package org.megamek.launcher.gui;

import java.util.concurrent.atomic.AtomicBoolean;

/** A process-wide single-flight gate shared by every window and dialog action. */
public final class BusyGate {
    private final AtomicBoolean busy = new AtomicBoolean();

    public boolean tryEnter() {
        return busy.compareAndSet(false, true);
    }

    public void leave() {
        busy.set(false);
    }

    public boolean isBusy() {
        return busy.get();
    }
}
