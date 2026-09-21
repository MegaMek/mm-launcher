package org.megamek.launcher.release;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ReleaseCatalog {
    public static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_METADATA = 8 * 1024 * 1024;
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern ASSET_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}\\.tar\\.gz");
    private static final URI API = URI.create("https://api.github.com");
    private final ReleaseTransport transport;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ReleaseCatalog(ReleaseTransport transport) {
        this.transport = transport;
    }

    public Page list(OfficialRepository repository, int page, int perPage)
            throws IOException, InterruptedException {
        if (page < 1 || page > 1000 || perPage < 1 || perPage > MAX_PAGE_SIZE) {
            throw new IOException("page must be 1-1000 and per-page must be 1-" + MAX_PAGE_SIZE);
        }
        URI uri = API.resolve("/repos/" + repository.slug() + "/releases?per_page="
                + perPage + "&page=" + page);
        JsonNode root = requestJson(uri);
        if (!root.isArray()) throw new IOException("GitHub release response must be an array");
        if (root.size() > perPage) {
            throw new IOException("GitHub release response exceeds the requested page size");
        }
        List<Release> releases = new ArrayList<>();
        for (JsonNode node : root) releases.add(parseRelease(repository, node, false));
        return new Page(page, perPage, List.copyOf(releases),
                releases.size() == perPage);
    }

    public Release exact(OfficialRepository repository, String tag)
            throws IOException, InterruptedException {
        URI source = exactMetadataUri(repository, tag);
        JsonNode root = requestJson(source);
        Release release = parseRelease(repository, root, true);
        if (!tag.equals(release.tag())) {
            throw new IOException("GitHub returned a different tag than requested");
        }
        return release;
    }

    /**
     * Returns the one fixed official API location used to revalidate an exact historical choice.
     * This is metadata identity only; package URLs remain separately constrained and verified.
     */
    public static URI exactMetadataUri(OfficialRepository repository, String tag)
            throws IOException {
        if (repository == null) throw new IOException("official repository is required");
        requireTag(tag);
        String encoded = URLEncoder.encode(tag, StandardCharsets.UTF_8).replace("+", "%20");
        return API.resolve("/repos/" + repository.slug() + "/releases/tags/" + encoded);
    }

    public Asset selectInstallAsset(OfficialRepository repository, Release release)
            throws IOException {
        Assessment assessment = assess(repository, release);
        if (!assessment.eligible()) throw new IOException(assessment.reason());
        return assessment.asset();
    }

    /**
     * Applies the same release and asset rules used by the installer, while retaining a reason
     * suitable for a release picker.  Callers must still let {@link #selectInstallAsset} enforce
     * the decision when installation begins.
     */
    public Assessment assess(OfficialRepository repository, Release release) {
        if (release.draft()) return new Assessment(false, null,
                "Draft releases cannot be installed");
        List<Asset> matches = release.assets().stream()
                .filter(a -> a.name().startsWith(repository.assetPrefix())
                        && a.name().endsWith(".tar.gz")).toList();
        if (matches.size() != 1) {
            return new Assessment(false, null, matches.isEmpty()
                    ? "No supported " + repository.assetPrefix() + "*.tar.gz asset"
                    : "Multiple supported tar.gz assets are ambiguous");
        }
        Asset asset = matches.getFirst();
        if (asset.publishedDigest().orElse(null)
                instanceof PackageDigest.MalformedPublished) {
            return new Assessment(false, asset, "Published SHA-256 checksum is invalid");
        }
        if (!isInstallAssetName(repository, asset.name())) {
            return new Assessment(false, asset, "Install asset name is unsupported");
        }
        if (asset.size() <= 0 || asset.size() > FreshInstaller.MAX_DOWNLOAD) {
            return new Assessment(false, asset, "Install asset size is missing or outside limits");
        }
        try {
            validateInitialAssetUri(repository, release.tag(), asset);
        } catch (IOException e) {
            return new Assessment(false, asset, e.getMessage());
        }
        return new Assessment(true, asset, "Available");
    }

    /** Exact asset-name grammar shared by catalog selection and persisted provenance checks. */
    public static boolean isInstallAssetName(OfficialRepository repository, String name) {
        return name != null && ASSET_NAME.matcher(name).matches()
                && name.startsWith(repository.assetPrefix()) && name.endsWith(".tar.gz");
    }

    private JsonNode requestJson(URI uri) throws IOException, InterruptedException {
        validateApiUri(uri);
        try (ReleaseTransport.Response response =
                     transport.get(uri, "application/vnd.github+json")) {
            if (response.status() != 200) throw httpError(response);
            byte[] bytes = bounded(response.body(), MAX_METADATA);
            try {
                JsonNode node = mapper.readTree(bytes);
                if (node == null) throw new IOException("empty GitHub metadata response");
                return node;
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IOException("malformed GitHub metadata: " + e.getOriginalMessage(), e);
            }
        }
    }

    private Release parseRelease(OfficialRepository repository, JsonNode node, boolean installing)
            throws IOException {
        if (!node.isObject()) throw new IOException("release entry must be an object");
        String tag = requiredText(node, "tag_name");
        requireTag(tag);
        String title = requiredText(node, "name");
        boolean draft = requiredBoolean(node, "draft");
        boolean prerelease = requiredBoolean(node, "prerelease");
        String notes = requiredText(node, "html_url");
        URI notesUri = checkedUri(notes, "release notes URL");
        if (!"https".equals(notesUri.getScheme()) || !"github.com".equalsIgnoreCase(notesUri.getHost())
                || !notesUri.getPath().startsWith("/" + repository.slug() + "/releases/")) {
            throw new IOException("release notes URL is not in the selected official repository");
        }
        JsonNode array = node.get("assets");
        if (array == null || !array.isArray()) throw new IOException("release assets must be an array");
        List<Asset> assets = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode item : array) {
            if (!item.isObject()) throw new IOException("release asset must be an object");
            String name = requiredText(item, "name");
            if (!names.add(name)) throw new IOException("duplicate release asset name: " + name);
            JsonNode sizeNode = item.get("size");
            if (sizeNode == null || !sizeNode.isIntegralNumber() || !sizeNode.canConvertToLong()) {
                throw new IOException("release asset size must be an integer");
            }
            long size = sizeNode.longValue();
            String digest = optionalText(item, "digest");
            String url = requiredText(item, "browser_download_url");
            Asset asset = new Asset(name, size, PackageDigest.published(digest),
                    checkedUri(url, "asset URL"));
            if (installing && name.startsWith(repository.assetPrefix()) && name.endsWith(".tar.gz")) {
                validateInitialAssetUri(repository, tag, asset);
            }
            assets.add(asset);
        }
        return new Release(tag, title, draft, prerelease, notesUri, List.copyOf(assets));
    }

    static void validateInitialAssetUri(OfficialRepository repository, String tag, Asset asset)
            throws IOException {
        URI uri = asset.url();
        requireSafeHttps(uri);
        String expectedPrefix = "/" + repository.slug() + "/releases/download/" + tag + "/";
        if (!"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getRawQuery() != null
                || !uri.getRawPath().equals(expectedPrefix + asset.name())) {
            throw new IOException("asset URL is not the exact official GitHub release asset");
        }
    }

    static void validateDownloadUri(URI uri, boolean initial) throws IOException {
        requireSafeHttps(uri);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (initial ? !host.equals("github.com")
                : !(host.equals("release-assets.githubusercontent.com")
                || host.equals("objects.githubusercontent.com")
                || host.equals("github-releases.githubusercontent.com"))) {
            throw new IOException("unsafe release download host: " + host);
        }
    }

    private static void requireSafeHttps(URI uri) throws IOException {
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() != -1
                || uri.getRawFragment() != null) {
            throw new IOException("URL must be ordinary HTTPS with no credentials, port, or fragment");
        }
    }

    private static void validateApiUri(URI uri) throws IOException {
        requireSafeHttps(uri);
        if (!"api.github.com".equalsIgnoreCase(uri.getHost())) {
            throw new IOException("metadata API host must be api.github.com");
        }
    }

    private static IOException httpError(ReleaseTransport.Response response) {
        String remaining = response.firstHeader("x-ratelimit-remaining");
        String reset = response.firstHeader("x-ratelimit-reset");
        String detail = response.status() == 403 || response.status() == 429
                ? " (GitHub rate limit; remaining=" + (remaining == null ? "unknown" : remaining)
                + ", reset=" + (reset == null ? "unknown" : reset) + ")" : "";
        return new IOException("GitHub API returned HTTP " + response.status() + detail);
    }

    private static byte[] bounded(java.io.InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            if (out.size() > maximum - count) throw new IOException("GitHub metadata exceeds limit");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IOException("required release field is missing or not text: " + field);
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IOException("release field is not text: " + field);
        }
        return value.textValue();
    }

    private static boolean requiredBoolean(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IOException("required release field is not boolean: " + field);
        }
        return value.booleanValue();
    }

    private static void requireTag(String tag) throws IOException {
        if (tag == null || !TAG.matcher(tag).matches()) {
            throw new IOException("tag must be an explicit 1-100 character GitHub tag");
        }
    }

    private static URI checkedUri(String value, String label) throws IOException {
        try { return URI.create(value); }
        catch (IllegalArgumentException e) { throw new IOException("invalid " + label, e); }
    }

    public record Asset(String name, long size,
                        java.util.Optional<PackageDigest.Published> publishedDigest, URI url) {
        public Asset {
            publishedDigest = publishedDigest == null
                    ? java.util.Optional.empty() : publishedDigest;
        }

        /** Compatibility view for diagnostics and older callers; trust code uses publishedDigest. */
        public String digest() {
            return publishedDigest.map(PackageDigest.Published::raw).orElse(null);
        }

        public Asset(String name, long size, String digest, URI url) {
            this(name, size, PackageDigest.published(digest), url);
        }
    }
    public record Assessment(boolean eligible, Asset asset, String reason) {}
    public record Release(String tag, String title, boolean draft, boolean prerelease,
                          URI notesUrl, List<Asset> assets) {}
    public record Page(int page, int perPage, List<Release> releases, boolean mayHaveNextPage) {}
}
