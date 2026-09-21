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
