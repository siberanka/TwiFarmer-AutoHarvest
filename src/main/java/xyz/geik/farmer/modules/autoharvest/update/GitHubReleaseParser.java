package xyz.geik.farmer.modules.autoharvest.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.Optional;

/** Parses and validates the small trusted surface used from GitHub's release API. */
public final class GitHubReleaseParser {

    private static final String GITHUB_HOST = "github.com";
    private static final String RELEASE_PATH = "/siberanka/TwiFarmer-AutoHarvest/releases/";
    static final int MAX_RESPONSE_LENGTH = 65_536;

    private GitHubReleaseParser() {
    }

    public static @NotNull Optional<ReleaseInfo> parse(String body) {
        if (body == null || body.isBlank() || body.length() > MAX_RESPONSE_LENGTH) {
            return Optional.empty();
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject release = root.getAsJsonObject();
            String tag = string(release, "tag_name");
            if (tag == null || ReleaseVersion.parse(tag).isEmpty()) {
                return Optional.empty();
            }
            String releaseUrl = validatedReleaseUrl(string(release, "html_url")).orElse(null);
            String downloadUrl = findJarAsset(release.getAsJsonArray("assets")).orElse(releaseUrl);
            if (downloadUrl == null) {
                return Optional.empty();
            }
            return Optional.of(new ReleaseInfo(tag, downloadUrl));
        }
        catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> findJarAsset(JsonArray assets) {
        if (assets == null || assets.size() > 128) {
            return Optional.empty();
        }
        for (JsonElement element : assets) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject asset = element.getAsJsonObject();
            String name = string(asset, "name");
            String url = string(asset, "browser_download_url");
            if (name != null && name.startsWith("Farmer-AutoHarvest-") && name.endsWith(".jar")) {
                Optional<String> validated = validatedReleaseUrl(url);
                if (validated.isPresent()) {
                    return validated;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validatedReleaseUrl(String raw) {
        if (raw == null || raw.length() > 512) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !GITHUB_HOST.equalsIgnoreCase(uri.getHost())
                    || uri.getRawUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPath() == null
                    || !uri.getPath().startsWith(RELEASE_PATH)) {
                return Optional.empty();
            }
            return Optional.of(uri.toASCIIString());
        }
        catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    public record ReleaseInfo(@NotNull String tag, @NotNull String downloadUrl) {
    }
}
