package org.megamek.launcher.launch;

import java.time.Instant;

/** PID plus creation time; creation time prevents treating a reused PID as the tracked process. */
public record ProcessIdentity(long pid, String startedAt) {
    public static ProcessIdentity of(ProcessHandle process) {
        String start = process.info().startInstant().map(Instant::toString).orElse(null);
        return new ProcessIdentity(process.pid(), start);
    }
}
