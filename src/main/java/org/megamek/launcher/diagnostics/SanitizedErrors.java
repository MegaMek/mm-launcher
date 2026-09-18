package org.megamek.launcher.diagnostics;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SanitizedErrors {
    private static final int MAX_MESSAGE = 4_096;
    private static final int MAX_CHAIN = 24;
    private static final int MAX_FRAMES = 80;
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\\])}>]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(\"?(?:password|passwd|secret|token|access[_-]?token|api[_-]?key|"
                    + "authorization|auth)\"?\\s*[:=]\\s*)(\"(?:\\\\.|[^\"])*\"|[^,\\s;}]+)");

    private SanitizedErrors() {
    }

    public static String display(Throwable error) {
        if (error == null) return "(no error details)";
        StringBuilder result = new StringBuilder();
        appendDisplay(result, error, "", identitySet(), 0);
        return result.toString();
    }

    public static String persistent(Throwable error) {
        if (error == null) return null;
        StringBuilder result = new StringBuilder();
        appendPersistent(result, error, "", identitySet(), 0);
        return bound(result.toString(), 128 * 1024);
    }

    public static String text(String value) {
        return sanitizeText(value, MAX_MESSAGE);
    }

    public static String document(String value) {
        return sanitizeText(value, 1024 * 1024);
    }

    private static String sanitizeText(String value, int limit) {
        if (value == null || value.isBlank()) return "(no detail)";
        String bounded = bound(value, limit);
        String lower = bounded.toLowerCase(Locale.ROOT);
        if (lower.contains("command line:") || lower.contains("environment variables:")
                || lower.contains("environment dump:")) {
            return "[command-line or environment details omitted]";
        }
        String sanitized = replaceUrls(bounded);
        sanitized = BEARER.matcher(sanitized).replaceAll("Bearer [redacted]");
        sanitized = SECRET_FIELD.matcher(sanitized).replaceAll("$1[redacted]");
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            String normalizedHome = Path.of(home).toAbsolutePath().normalize().toString();
            sanitized = replaceIgnoreCase(sanitized, normalizedHome, "~");
            sanitized = replaceIgnoreCase(sanitized,
                    normalizedHome.replace('\\', '/'), "~");
        }
        return sanitized;
    }

    private static void appendDisplay(StringBuilder result, Throwable error, String prefix,
                                      Set<Throwable> seen, int depth) {
        if (error == null || depth >= MAX_CHAIN || !seen.add(error)) return;
        if (!result.isEmpty()) result.append('\n');
        result.append(prefix).append(error.getClass().getSimpleName()).append(": ")
                .append(message(error));
        for (Throwable suppressed : error.getSuppressed()) {
            appendDisplay(result, suppressed, "Suppressed: ", seen, depth + 1);
        }
        appendDisplay(result, error.getCause(), "Caused by: ", seen, depth + 1);
    }

    private static void appendPersistent(StringBuilder result, Throwable error, String prefix,
                                         Set<Throwable> seen, int depth) {
        if (error == null || depth >= MAX_CHAIN || !seen.add(error)) return;
        if (!result.isEmpty()) result.append('\n');
        result.append(prefix).append(error.getClass().getName()).append(": ")
                .append(message(error)).append('\n');
        StackTraceElement[] frames = error.getStackTrace();
        for (int index = 0; index < Math.min(frames.length, MAX_FRAMES); index++) {
            result.append("  at ").append(frames[index]).append('\n');
        }
        if (frames.length > MAX_FRAMES) {
            result.append("  ... ").append(frames.length - MAX_FRAMES)
                    .append(" more frames omitted\n");
        }
        for (Throwable suppressed : error.getSuppressed()) {
            appendPersistent(result, suppressed, "Suppressed: ", seen, depth + 1);
        }
        appendPersistent(result, error.getCause(), "Caused by: ", seen, depth + 1);
    }

    private static String message(Throwable error) {
        if (hasJsonProcessingCause(error, identitySet())) {
            return "[configuration source content omitted; type and stack retained]";
        }
        return text(error.getMessage());
    }

    private static boolean hasJsonProcessingCause(Throwable error, Set<Throwable> seen) {
        if (error == null || !seen.add(error)) return false;
        if (error instanceof JsonProcessingException) return true;
        if (hasJsonProcessingCause(error.getCause(), seen)) return true;
        for (Throwable suppressed : error.getSuppressed()) {
            if (hasJsonProcessingCause(suppressed, seen)) return true;
        }
        return false;
    }

    private static String replaceUrls(String value) {
        Matcher matcher = URL.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(safeUrl(matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String safeUrl(String candidate) {
        try {
            URI uri = URI.create(candidate);
            if (uri.getScheme() == null || uri.getHost() == null) return "[URL redacted]";
            StringBuilder result = new StringBuilder(uri.getScheme()).append("://")
                    .append(uri.getHost());
            if (uri.getPort() >= 0) result.append(':').append(uri.getPort());
            String path = uri.getPath();
            if (path != null && !path.isBlank()) result.append(path);
            if (uri.getRawQuery() != null) result.append("?[redacted]");
            if (uri.getRawFragment() != null) result.append("#[redacted]");
            return result.toString();
        } catch (IllegalArgumentException ignored) {
            return "[URL redacted]";
        }
    }

    private static String replaceIgnoreCase(String value, String target, String replacement) {
        if (target.isEmpty()) return value;
        return Pattern.compile(Pattern.quote(target), Pattern.CASE_INSENSITIVE)
                .matcher(value).replaceAll(Matcher.quoteReplacement(replacement));
    }

    private static String bound(String value, int limit) {
        if (value.length() <= limit) return value;
        return value.substring(0, limit) + "\n[details truncated]";
    }

    private static Set<Throwable> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
