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
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Explicit digest state at the official-package trust boundary.
 *
 * <p>GitHub metadata may omit a digest. A value that is present is retained as either a valid
 * SHA-256 or a malformed published value, so malformed metadata can never be mistaken for
 * absence. A resolved digest records whether the authoritative identity was published or was
 * computed while streaming the one validated official asset body.</p>
 */
public final class PackageDigest {
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-fA-F]{64}");

    private PackageDigest() {
    }

    public sealed interface Published permits ValidPublished, MalformedPublished {
        String raw();
    }

    public record ValidPublished(Sha256 value) implements Published {
        public ValidPublished {
            if (value == null) throw new IllegalArgumentException("SHA-256 is required");
        }

        @Override
        public String raw() {
            return value.canonical();
        }
    }

    public record MalformedPublished(String raw) implements Published {
        public MalformedPublished {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("malformed published digest is required");
            }
        }
    }

    public enum Origin {
        PUBLISHED,
        COMPUTED
    }

    public record Sha256(String hex, Origin origin) {
        public Sha256 {
            if (hex == null || !hex.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("SHA-256 must contain exactly 64 hex digits");
            }
            if (origin == null) throw new IllegalArgumentException("digest origin is required");
            hex = hex.toLowerCase(Locale.ROOT);
        }

        public String canonical() {
            return "sha256:" + hex;
        }
    }

    public static Optional<Published> published(String raw) {
        if (raw == null) return Optional.empty();
        if (!SHA256.matcher(raw).matches()) {
            return Optional.of(new MalformedPublished(raw));
        }
        return Optional.of(new ValidPublished(new Sha256(
                raw.substring("sha256:".length()), Origin.PUBLISHED)));
    }

    public static Optional<ValidPublished> expectedPublished(String raw) {
        Optional<Published> parsed = published(raw);
        if (parsed.isEmpty()) return Optional.empty();
        if (parsed.get() instanceof ValidPublished valid) return Optional.of(valid);
        throw new IllegalArgumentException("expected published SHA-256 is malformed");
    }

    public static Sha256 computed(String hex) {
        return new Sha256(hex, Origin.COMPUTED);
    }

    public static Sha256 requireStored(String value) throws IOException {
        if (value == null) throw new IOException("stored package SHA-256 is missing");
        String canonical = value.startsWith("sha256:") ? value : "sha256:" + value;
        if (!SHA256.matcher(canonical).matches()) {
            throw new IOException("stored package SHA-256 is invalid");
        }
        return new Sha256(canonical.substring("sha256:".length()), Origin.COMPUTED);
    }
}
