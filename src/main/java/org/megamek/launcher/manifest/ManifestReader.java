package org.megamek.launcher.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ManifestReader {
    public static final int SCHEMA_VERSION = 1;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DRIVE_OR_SCHEME = Pattern.compile("^[A-Za-z]:.*");
    private static final Pattern WINDOWS_DEVICE = Pattern.compile(
            "(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$");
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Manifest read(Path path) throws ManifestException {
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(path));
            if (root == null || !root.isObject()) {
                throw new ManifestException(path + ": manifest root must be an object");
            }
            Manifest manifest = mapper.treeToValue(root, Manifest.class);
            validateInMemory(manifest, path.toString());
            return manifest;
        } catch (JsonProcessingException e) {
            throw new ManifestException(path + ": malformed or nonconforming JSON: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new ManifestException(path + ": cannot read manifest: " + e.getMessage(), e);
        }
    }

    public void validatePair(Manifest baseline, Manifest target) throws ManifestException {
        validatePairContract(baseline, target);

        Map<String, String> combined = new HashMap<>();
        addPaths(combined, baseline.files(), "baseline");
        for (FileEntry entry : target.files()) {
            String key = key(entry.path());
            String previous = combined.get(key);
            if (previous != null && !previous.equals(entry.path())) {
                throw new ManifestException("path case/normalization collision across manifests: "
                        + previous + " and " + entry.path());
            }
            combined.putIfAbsent(key, entry.path());
        }
        validateParentConflicts(combined.values(), "combined manifests");
    }

    /**
     * Validates a pair for read-only preview while reporting, rather than accepting, cross-release
     * capitalization changes. All validation within either manifest and all pair contracts remain
     * strict. The returned prefixes are the outermost affected prefixes, so callers can avoid
     * probing every descendant of a directory capitalization change.
     */
    public PreviewPairValidation validatePreviewPair(Manifest baseline, Manifest target)
            throws ManifestException {
        validatePairContract(baseline, target);

        List<String> combined = new ArrayList<>();
        baseline.files().forEach(entry -> combined.add(entry.path()));
        target.files().forEach(entry -> combined.add(entry.path()));
        // A file in one release may never become a parent in the other release. Check this before
        // identifying skippable prefixes so a structural conflict cannot hide beneath one.
        validateParentConflicts(combined, "combined manifests");

        Map<String, String> baselinePrefixes = indexPrefixes(baseline.files());
        Map<String, String> targetPrefixes = indexPrefixes(target.files());
        List<CaseOnlyRename> candidates = new ArrayList<>();
        baselinePrefixes.keySet().stream()
                .filter(targetPrefixes::containsKey)
                .sorted()
                .forEach(folded -> {
                    String before = baselinePrefixes.get(folded);
                    String after = targetPrefixes.get(folded);
                    if (!before.equals(after)) {
                        candidates.add(new CaseOnlyRename(before, after));
                    }
                });

        List<CaseOnlyRename> outermost = new ArrayList<>();
        for (CaseOnlyRename candidate : candidates) {
            String candidateKey = key(candidate.baselinePrefix());
            boolean belowExisting = outermost.stream().anyMatch(existing -> {
                String existingKey = key(existing.baselinePrefix());
                return candidateKey.startsWith(existingKey + "/");
            });
            if (!belowExisting) {
                outermost.add(candidate);
            }
        }
        outermost.sort(Comparator
                .comparing((CaseOnlyRename rename) -> key(rename.baselinePrefix()))
                .thenComparing(CaseOnlyRename::baselinePrefix)
                .thenComparing(CaseOnlyRename::targetPrefix));
        return new PreviewPairValidation(outermost);
    }

    private void validatePairContract(Manifest baseline, Manifest target)
            throws ManifestException {
        validateInMemory(baseline, "baseline");
        validateInMemory(target, "target");
        if (!baseline.product().equals(target.product())) {
            throw new ManifestException("incompatible manifests: product differs");
        }
        if (!baseline.packageId().equals(target.packageId())) {
            throw new ManifestException("incompatible manifests: packageId differs");
        }
        if (!baseline.protectedPaths().equals(target.protectedPaths())) {
            throw new ManifestException("incompatible manifests: protectedPaths contract differs");
        }
    }

    public void validateInMemory(Manifest manifest, String source) throws ManifestException {
        if (manifest.schemaVersion() != SCHEMA_VERSION) {
            throw new ManifestException(source + ": unsupported schemaVersion "
                    + manifest.schemaVersion() + " (expected 1)");
        }
        requireText(manifest.product(), "product", source);
        requireText(manifest.packageId(), "packageId", source);
        requireText(manifest.releaseId(), "releaseId", source);
        if (manifest.protectedPaths() == null || manifest.protectedPaths().isEmpty()) {
            throw new ManifestException(source + ": protectedPaths must be a non-empty array");
        }
        if (manifest.files() == null) {
            throw new ManifestException(source + ": files must be an array");
        }

        Set<String> protectedKeys = new HashSet<>();
        for (String protectedPath : manifest.protectedPaths()) {
            validatePortablePath(protectedPath, "protected path", source);
            rejectReserved(protectedPath, source);
            if (!protectedKeys.add(key(protectedPath))) {
                throw new ManifestException(source + ": duplicate protected path: " + protectedPath);
            }
        }

        Map<String, String> paths = new HashMap<>();
        for (FileEntry entry : manifest.files()) {
            if (entry == null) {
                throw new ManifestException(source + ": null file entry");
            }
            validatePortablePath(entry.path(), "file path", source);
            rejectReserved(entry.path(), source);
            if (entry.sha256() == null || !HASH.matcher(entry.sha256()).matches()) {
                throw new ManifestException(source + ": invalid lowercase SHA-256 for " + entry.path());
            }
            String prior = paths.putIfAbsent(key(entry.path()), entry.path());
            if (prior != null) {
                throw new ManifestException(source + ": duplicate or case-aliased path: "
                        + prior + " and " + entry.path());
            }
            for (String protectedPath : manifest.protectedPaths()) {
                if (isAtOrBelow(entry.path(), protectedPath)) {
                    throw new ManifestException(source + ": managed file is in protected user-data path: "
                            + entry.path());
                }
            }
        }
        validatePrefixAliases(paths.values(), source);
        validateParentConflicts(paths.values(), source);
        for (String protectedPath : manifest.protectedPaths()) {
            for (String file : paths.values()) {
                if (isAtOrBelow(protectedPath, file)) {
                    throw new ManifestException(source + ": protected path has managed-file parent: "
                            + protectedPath + " and " + file);
                }
            }
        }
    }

    private static Map<String, String> indexPrefixes(List<FileEntry> entries) {
        Map<String, String> result = new HashMap<>();
        for (FileEntry entry : entries) {
            String path = entry.path();
            int end = path.indexOf('/');
            while (end >= 0) {
                String prefix = path.substring(0, end);
                result.putIfAbsent(key(prefix), prefix);
                end = path.indexOf('/', end + 1);
            }
            result.putIfAbsent(key(path), path);
        }
        return result;
    }

    private static void validatePrefixAliases(Iterable<String> paths, String source)
            throws ManifestException {
        Map<String, String> prefixes = new HashMap<>();
        for (String path : sorted(paths)) {
            int end = path.indexOf('/');
            while (end >= 0) {
                addPrefix(prefixes, path.substring(0, end), source);
                end = path.indexOf('/', end + 1);
            }
            addPrefix(prefixes, path, source);
        }
    }

    private static void addPrefix(Map<String, String> prefixes, String prefix, String source)
            throws ManifestException {
        String prior = prefixes.putIfAbsent(key(prefix), prefix);
        if (prior != null && !prior.equals(prefix)) {
            throw new ManifestException(source + ": duplicate or case-aliased path component: "
                    + prior + " and " + prefix);
        }
    }

    private static void rejectReserved(String path, String source) throws ManifestException {
        if (isAtOrBelow(path, ".mm-launcher") || isAtOrBelow(".mm-launcher", path)) {
            throw new ManifestException(source + ": reserved launcher metadata path conflict: " + path);
        }
    }

    private static void addPaths(Map<String, String> destination, List<FileEntry> entries, String label)
            throws ManifestException {
        for (FileEntry entry : entries) {
            String prior = destination.putIfAbsent(key(entry.path()), entry.path());
            if (prior != null && !prior.equals(entry.path())) {
                throw new ManifestException(label + " path collision: " + prior + " and " + entry.path());
            }
        }
    }

    private static void validateParentConflicts(Iterable<String> paths, String source)
            throws ManifestException {
        Set<String> keys = new HashSet<>();
        List<String> values = sorted(paths);
        values.forEach(path -> keys.add(key(path)));
        for (String path : values) {
            int slash = path.indexOf('/');
            while (slash >= 0) {
                String parent = path.substring(0, slash);
                if (keys.contains(key(parent))) {
                    throw new ManifestException(source + ": file/parent path conflict: "
                            + parent + " and " + path);
                }
                slash = path.indexOf('/', slash + 1);
            }
        }
    }

    private static List<String> sorted(Iterable<String> paths) {
        List<String> values = new ArrayList<>();
        paths.forEach(values::add);
        values.sort(Comparator.comparing(ManifestReader::key).thenComparing(
                Comparator.naturalOrder()));
        return values;
    }

    public static void validatePortablePath(String path, String kind, String source)
            throws ManifestException {
        if (path == null || path.isBlank()) {
            throw new ManifestException(source + ": empty " + kind);
        }
        if (!path.equals(Normalizer.normalize(path, Normalizer.Form.NFC))) {
            throw new ManifestException(source + ": " + kind + " is not Unicode NFC: " + path);
        }
        if (path.startsWith("/") || path.startsWith("\\") || path.contains("\\")
                || DRIVE_OR_SCHEME.matcher(path).matches() || path.indexOf('\0') >= 0) {
            throw new ManifestException(source + ": non-portable or absolute " + kind + ": " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(" ") || segment.endsWith(".")
                    || segment.chars().anyMatch(character -> "<>:\"|?*".indexOf(character) >= 0)
                    || WINDOWS_DEVICE.matcher(segment).matches()
                    || segment.chars().anyMatch(Character::isISOControl)) {
                throw new ManifestException(source + ": unsafe " + kind + ": " + path);
            }
        }
    }

    private static boolean isAtOrBelow(String path, String directory) {
        String pathKey = key(path);
        String directoryKey = key(directory);
        return pathKey.equals(directoryKey) || pathKey.startsWith(directoryKey + "/");
    }

    private static String key(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static void requireText(String value, String field, String source) throws ManifestException {
        if (value == null || value.isBlank()) {
            throw new ManifestException(source + ": " + field + " must be non-blank");
        }
    }

    public record CaseOnlyRename(String baselinePrefix, String targetPrefix) {
    }

    public record PreviewPairValidation(List<CaseOnlyRename> caseOnlyRenames) {
        public PreviewPairValidation {
            caseOnlyRenames = List.copyOf(caseOnlyRenames);
        }
    }
}
