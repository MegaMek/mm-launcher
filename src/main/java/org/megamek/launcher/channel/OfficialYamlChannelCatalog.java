package org.megamek.launcher.channel;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.NodeEvent;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/** Reads only the fixed website data file, then resolves the exact tag through ReleaseCatalog. */
public final class OfficialYamlChannelCatalog implements ChannelCatalog {
    public static final URI SOURCE = URI.create(
            "https://raw.githubusercontent.com/MegaMek/megamek.github.io/main/"
                    + "_data/current_releases.yml");
    static final int MAX_BYTES = 16 * 1024;
    private static final Pattern VERSION =
            Pattern.compile("[0-9]{1,18}(?:\\.[0-9]{1,18}){2,3}");
    private final ReleaseTransport transport;
    private final ObjectMapper yaml;
    private final LoaderOptions loader;

    public OfficialYamlChannelCatalog(ReleaseTransport transport) {
        this.transport = transport;
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(8).maxStringLength(256).maxDocumentLength(MAX_BYTES).build();
        LoaderOptions loader = new LoaderOptions();
        loader.setAllowDuplicateKeys(false);
        loader.setMaxAliasesForCollections(0);
        loader.setNestingDepthLimit(8);
        loader.setCodePointLimit(MAX_BYTES);
        loader.setAllowRecursiveKeys(false);
        loader.setTagInspector(tag -> false);
        YAMLFactory factory = YAMLFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .loaderOptions(loader).streamReadConstraints(constraints).build();
        this.yaml = new ObjectMapper(factory);
        this.loader = loader;
    }

    @Override
    public Target target(FollowChannel channel, OfficialRepository repository)
            throws IOException, InterruptedException {
        if (channel == null || repository == null) throw new IOException("channel and repository are required");
        Map<FollowChannel, String> versions = versions();
        String version = versions.get(channel);
        String tag = "v" + version;
        ReleaseCatalog catalog = new ReleaseCatalog(transport);
        ReleaseCatalog.Release release = catalog.exact(repository, tag);
        ReleaseCatalog.Assessment assessment = catalog.assess(repository, release);
        if (!assessment.eligible()) {
            throw new IOException("official " + channel + " target is unavailable: "
                    + assessment.reason());
        }
        return new Target(channel, version, repository, release, assessment.asset(),
                SOURCE.toString());
    }

    Map<FollowChannel, String> versions() throws IOException, InterruptedException {
        try (ReleaseTransport.Response response = transport.get(SOURCE,
                "application/yaml, text/yaml, text/plain")) {
            if (response.status() != 200) {
                if (response.status() >= 300 && response.status() < 400) {
                    throw new IOException("official channel metadata redirect was refused (HTTP "
                            + response.status() + ")");
                }
                throw new IOException("official channel metadata returned HTTP " + response.status());
            }
            byte[] bytes = bounded(response);
            final JsonNode root;
            try {
                rejectAliases(bytes);
                root = yaml.readTree(bytes);
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IOException("malformed or unsafe official channel YAML: "
                        + error.getOriginalMessage(), error);
            }

            if (root == null || !root.isObject()) {
                throw new IOException("official channel YAML must be one scalar mapping");
            }
            String stable = null;
            String development = null;
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (!value.isValueNode() || value.isNull()) {
                    throw new IOException("unsupported structured official channel field: "
                            + field.getKey());
                }
                if (field.getKey().equals("stable")) stable = version(value, "stable");
                if (field.getKey().equals("dev")) development = version(value, "dev");
            }
            if (stable == null || development == null) {
                throw new IOException("official channel YAML requires scalar stable and dev values");
            }
            return Map.of(FollowChannel.MILESTONE, stable,
                    FollowChannel.DEVELOPMENT, development);
        }
    }

    private void rejectAliases(byte[] bytes) throws IOException {
        try {
            for (org.yaml.snakeyaml.events.Event event : new Yaml(loader).parse(
                    new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
                if (event instanceof AliasEvent
                        || event instanceof NodeEvent node && node.getAnchor() != null) {
                    throw new IOException(
                            "official channel YAML aliases and anchors are unsupported");
                }
            }
        } catch (org.yaml.snakeyaml.error.YAMLException error) {
            throw new IOException("malformed or unsafe official channel YAML: "
                    + error.getMessage(), error);
        }
    }

    private static String version(JsonNode node, String key) throws IOException {
        String value = node.isTextual() ? node.textValue() : node.asText(null);
        if (value == null || !VERSION.matcher(value).matches()) {
            throw new IOException("official " + key
                    + " version must have three or four numeric components");
        }
        return value;
    }

    private static byte[] bounded(ReleaseTransport.Response response) throws IOException {
        String length = response.firstHeader("content-length");
        if (length != null) {
            try {
                if (Long.parseLong(length) > MAX_BYTES) {
                    throw new IOException("official channel metadata exceeds size limit");
                }
            } catch (NumberFormatException error) {
                throw new IOException("official channel metadata has invalid content length", error);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while ((count = response.body().read(buffer)) >= 0) {
            if (count == 0) continue;
            if (output.size() > MAX_BYTES - count) {
                throw new IOException("official channel metadata exceeds size limit");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
