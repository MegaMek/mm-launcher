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

package org.megamek.launcher.release;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Optional;

/**
 * GitHub refused an API request because this network used up its request allowance. Without a
 * signed-in account GitHub allows 60 API requests an hour per IP address.
 */
public final class GitHubRateLimitException extends IOException {
    private final transient Instant reset;

    public GitHubRateLimitException(Instant reset) {
        this(reset, ZoneId.systemDefault());
    }

    GitHubRateLimitException(Instant reset, ZoneId zone) {
        super(message(reset, zone));
        this.reset = reset;
    }

    /** When GitHub accepts requests again, if it said so. */
    public Optional<Instant> reset() {
        return Optional.ofNullable(reset);
    }

    /** The rate-limit refusal carried by {@code response}, or null when it is some other failure. */
    public static GitHubRateLimitException from(ReleaseTransport.Response response, Clock clock) {
        if (response.status() != 403 && response.status() != 429) return null;
        String retryAfter = response.firstHeader("retry-after");
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds >= 0) return new GitHubRateLimitException(clock.instant().plusSeconds(seconds));
            } catch (NumberFormatException ignored) {
                return new GitHubRateLimitException(null);
            }
        }
        if (!"0".equals(trimmed(response.firstHeader("x-ratelimit-remaining")))) {
            return response.status() == 429 ? new GitHubRateLimitException(null) : null;
        }
        try {
            String reset = trimmed(response.firstHeader("x-ratelimit-reset"));
            return new GitHubRateLimitException(reset == null ? null
                    : Instant.ofEpochSecond(Long.parseLong(reset)));
        } catch (NumberFormatException | java.time.DateTimeException malformed) {
            return new GitHubRateLimitException(null);
        }
    }

    public static GitHubRateLimitException from(ReleaseTransport.Response response) {
        return from(response, Clock.systemUTC());
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static String message(Instant reset, ZoneId zone) {
        String retry = reset == null ? "Try again in a little while."
                : "Try again after " + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                        .withZone(zone).format(reset) + ".";
        return "GitHub is limiting how often this network can check for releases. " + retry;
    }
}
