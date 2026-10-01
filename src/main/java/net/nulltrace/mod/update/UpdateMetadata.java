package net.nulltrace.mod.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses release metadata without Minecraft client state or network access.
 * CurseForge's "version" field is a Minecraft version, not the mod version;
 * the latter comes from our published JAR names.
 */
public final class UpdateMetadata {
    public static final long CURSEFORGE_PROJECT_ID = 1712126;
    public static final String CURSEFORGE_PROJECT_URL = "https://www.curseforge.com/minecraft/mc-mods/nulltrace";
    public static final String GITLAB_RELEASES_URL = "https://gitlab.com/international-duck-incorporated/NullTrace/-/releases";

    private static final String MC_VERSION = "[0-9]+(?:\\.[0-9]+){1,2}";
    private static final Pattern JAR_NAME = Pattern.compile(
            "^nulltrace-(" + MC_VERSION + ")(?:-(" + MC_VERSION + "))?\\+v(.+)\\.jar$",
            Pattern.CASE_INSENSITIVE);
    // GitLab's initial releases used dated names instead of the +v suffix.
    private static final Pattern LEGACY_JAR_NAME = Pattern.compile(
            "^nulltrace-(" + MC_VERSION + ")(?:-(" + MC_VERSION + "))?-"
                    + "[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}-[0-9a-f]+\\.jar$",
            Pattern.CASE_INSENSITIVE);

    private UpdateMetadata() {}

    public record Release(String version, String url) {}

    /** Selects the newest stable Fabric file explicitly supporting this Minecraft version. */
    public static Release fromCurseForge(String body, String minecraftVersion) {
        JsonObject project = parseObject(body);
        if (project == null || !Long.toString(CURSEFORGE_PROJECT_ID).equals(primitive(project, "id"))) {
            return null;
        }
        JsonArray files = array(project, "files");
        if (files == null) return null;

        Release latest = null;
        for (JsonElement element : files) {
            if (!element.isJsonObject()) continue;
            JsonObject file = element.getAsJsonObject();
            if (!"release".equals(string(file, "type"))) continue;

            JsonArray versions = array(file, "versions");
            if (!contains(versions, "Fabric") || !contains(versions, minecraftVersion)) continue;

            String name = string(file, "name");
            Matcher jar = name == null ? null : JAR_NAME.matcher(name);
            if (jar == null || !jar.matches() || !supportsMinecraft(jar, minecraftVersion)) continue;
            String version = stableVersion(jar.group(3));
            String fileId = primitive(file, "id");
            if (version == null || fileId == null || !fileId.matches("[1-9][0-9]{0,18}")) continue;

            // Build a trusted project URL rather than opening a URL supplied by the feed.
            latest = newest(latest, new Release(version, CURSEFORGE_PROJECT_URL + "/files/" + fileId));
        }
        return latest;
    }

    /** Selects a GitLab release only when it includes a compatible installable JAR. */
    public static Release fromGitLab(String body, String minecraftVersion) {
        JsonObject release = parseObject(body);
        if (release == null || "true".equals(primitive(release, "upcoming_release"))) return null;
        String tag = string(release, "tag_name");
        String version = stableVersion(tag);
        JsonObject assets = object(release, "assets");
        JsonArray links = assets == null ? null : array(assets, "links");
        if (version == null || links == null) return null;

        for (JsonElement element : links) {
            if (!element.isJsonObject()) continue;
            String name = string(element.getAsJsonObject(), "name");
            if (name == null) continue;
            Matcher jar = JAR_NAME.matcher(name);
            Matcher legacyJar = LEGACY_JAR_NAME.matcher(name);
            boolean compatible = jar.matches() && supportsMinecraft(jar, minecraftVersion)
                    && version.equals(stableVersion(jar.group(3)));
            if (compatible || (legacyJar.matches() && supportsMinecraft(legacyJar, minecraftVersion))) {
                return new Release(version, GITLAB_RELEASES_URL + "/"
                        + URLEncoder.encode(tag, StandardCharsets.UTF_8));
            }
        }
        return null;
    }

    /** Returns the newer release, preferring the first source when versions are equal. */
    public static Release newest(Release first, Release second) {
        if (first == null) return second;
        if (second == null) return first;
        return isNewerVersion(second.version(), first.version()) ? second : first;
    }

    /** Semantic comparison prevents downgrade prompts and ignores build metadata. */
    public static boolean isNewerVersion(String candidate, String current) {
        SemanticVersion candidateVersion = semanticVersion(candidate);
        Version currentVersion = semanticVersion(current);
        return candidateVersion != null && currentVersion != null
                && candidateVersion.compareTo(currentVersion) > 0;
    }

    private static boolean supportsMinecraft(Matcher jar, String minecraftVersion) {
        SemanticVersion current = semanticVersion(minecraftVersion);
        Version minimum = semanticVersion(jar.group(1));
        Version maximum = semanticVersion(jar.group(2));
        if (current == null || minimum == null) return false;
        if (jar.group(2) == null) return current.compareTo(minimum) == 0;
        return maximum != null && current.compareTo(minimum) >= 0 && current.compareTo(maximum) <= 0;
    }

    private static String stableVersion(String raw) {
        String normalized = normalizeVersion(raw);
        SemanticVersion version = semanticVersion(normalized);
        return version != null && version.getPrereleaseKey().isEmpty() ? normalized : null;
    }

    private static SemanticVersion semanticVersion(String raw) {
        String normalized = normalizeVersion(raw);
        if (normalized == null) return null;
        try {
            return SemanticVersion.parse(normalized);
        } catch (VersionParsingException e) {
            return null;
        }
    }

    private static String normalizeVersion(String raw) {
        if (raw == null) return null;
        String version = raw.trim();
        if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1);
        return version.isEmpty() ? null : version;
    }

    private static boolean contains(JsonArray array, String value) {
        if (array == null || value == null) return false;
        for (JsonElement element : array) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                    && value.equals(element.getAsString())) return true;
        }
        return false;
    }

    private static JsonObject parseObject(String body) {
        if (body == null) return null;
        try {
            JsonElement element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static JsonObject object(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String string(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    private static String primitive(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
