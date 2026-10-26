package net.nulltrace.mod.config;

import net.nulltrace.mod.NullTrace;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.nulltrace.mod.util.HttpClients;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Async jar integrity checker that compares the running mod jar's SHA-256 hash
 * against the checksum published with the matching GitLab release asset.
 * All state fields are volatile for cross-thread visibility since the check runs
 * on a background thread and results are read on the render thread.
 */
public final class JarIntegrityChecker {

    private static final String RELEASES_BASE_URL = "https://gitlab.com/api/v4/projects/international-duck-incorporated%2FNullTrace/releases/v";

    /** Shared, proxy-aware client; invalidated by {@link HttpClients#reset()} on a proxy change. */
    private static HttpClient http() {
        return HttpClients.outbound(Duration.ofSeconds(10), true);
    }

    private static volatile boolean tamperDetected = false;
    private static volatile boolean checkComplete = false;
    private static volatile boolean shownThisSession = false;
    private static volatile String expectedDigest = null;
    private static volatile String actualDigest = null;

    private JarIntegrityChecker() {
        // Utility class
    }

    /**
     * Fires an async integrity check that computes the local jar's SHA-256 and
     * compares it against the SHA-256 checksum published with the matching GitLab release asset.
     * Non-blocking: runs on a daemon thread via CompletableFuture.
     */
    public static void checkIntegrity() {
        String runningVersion = NullTrace.getVersion();
        // Only official release builds have a published checksum to compare against. Builds from
        // a branch or a local build carry a non-release version and would always "fail".
        if (runningVersion == null || runningVersion.contains("-")) {
            NullTrace.LOGGER.debug("[NullTrace] Non-release version {}; skipping jar integrity check", runningVersion);
            checkComplete = true;
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // Step 1: Get jar path from FabricLoader
                Path jarPath = FabricLoader.getInstance()
                        .getModContainer(NullTrace.MOD_ID)
                        .flatMap(mod -> mod.getOrigin().getPaths().stream().findFirst())
                        .orElse(null);

                if (jarPath == null || !Files.isRegularFile(jarPath)) {
                    NullTrace.LOGGER.debug("[NullTrace] Not running from jar, skipping integrity check");
                    return;
                }

                if (!jarPath.toString().endsWith(".jar")) {
                    NullTrace.LOGGER.debug("[NullTrace] Not running from jar file, skipping integrity check");
                    return;
                }

                // Step 2: Compute local SHA-256
                byte[] jarBytes = Files.readAllBytes(jarPath);
                MessageDigest localDigest = MessageDigest.getInstance("SHA-256");
                actualDigest = bytesToHex(localDigest.digest(jarBytes));

                // Step 3: Get Minecraft version
                String mcVersion = FabricLoader.getInstance()
                        .getModContainer("minecraft")
                        .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                        .orElse(null);

                if (mcVersion == null) {
                    NullTrace.LOGGER.debug("[NullTrace] Could not determine Minecraft version, skipping integrity check");
                    return;
                }

                // Step 4: Fetch the GitLab release matching the current mod version
                String currentVersion = NullTrace.getVersion();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(RELEASES_BASE_URL + currentVersion))
                        .header("User-Agent", "NullTrace-Mod/" + currentVersion)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 404) {
                    // No release for this version — dev/unreleased build
                    NullTrace.LOGGER.debug("[NullTrace] No release found for version {}, skipping integrity check", currentVersion);
                    return;
                }

                if (response.statusCode() != 200) {
                    NullTrace.LOGGER.debug("[NullTrace] GitLab API returned status {}, skipping integrity check", response.statusCode());
                    return;
                }

                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!json.has("assets") || !json.getAsJsonObject("assets").has("links")) {
                    NullTrace.LOGGER.debug("[NullTrace] No GitLab release assets, skipping integrity check");
                    return;
                }

                JsonArray links = json.getAsJsonObject("assets").getAsJsonArray("links");
                JsonObject matchingAsset = findMatchingAsset(links, mcVersion);
                if (matchingAsset == null) {
                    NullTrace.LOGGER.debug("[NullTrace] No release asset found for MC version {}, skipping integrity check", mcVersion);
                    return;
                }

                String jarName = matchingAsset.get("name").getAsString();
                JsonObject checksumAsset = findChecksumAsset(links, jarName + ".sha256");
                if (checksumAsset == null || !checksumAsset.has("url")) {
                    NullTrace.LOGGER.debug("[NullTrace] No SHA-256 checksum asset for {}, skipping integrity check", jarName);
                    return;
                }

                HttpRequest checksumRequest = HttpRequest.newBuilder()
                        .uri(URI.create(checksumAsset.get("url").getAsString()))
                        .header("User-Agent", "NullTrace-Mod/" + currentVersion)
                        .timeout(Duration.ofSeconds(10))
                        .GET().build();
                HttpResponse<String> checksumResponse = http().send(
                        checksumRequest, HttpResponse.BodyHandlers.ofString());
                if (checksumResponse.statusCode() != 200) {
                    NullTrace.LOGGER.debug("[NullTrace] Checksum asset returned status {}, skipping integrity check", checksumResponse.statusCode());
                    return;
                }
                String[] checksumParts = checksumResponse.body().trim().split("\\s+");
                if (checksumParts.length == 0 || !checksumParts[0].matches("[0-9a-fA-F]{64}")) {
                    NullTrace.LOGGER.debug("[NullTrace] Invalid SHA-256 checksum asset, skipping integrity check");
                    return;
                }
                expectedDigest = checksumParts[0].toLowerCase(java.util.Locale.ROOT);

                // Step 6: Compare digests (constant-time comparison)
                if (!MessageDigest.isEqual(expectedDigest.getBytes(), actualDigest.getBytes())) {
                    tamperDetected = true;
                    NullTrace.LOGGER.warn("[NullTrace] JAR INTEGRITY CHECK FAILED - Expected: {}, Actual: {}", expectedDigest, actualDigest);
                } else {
                    NullTrace.LOGGER.debug("[NullTrace] Jar integrity verified");
                }
            } catch (Exception e) {
                NullTrace.LOGGER.debug("[NullTrace] Integrity check failed: {}", e.getMessage());
            } finally {
                checkComplete = true;
            }
        });
    }

    /**
     * Returns true once the integrity check has finished (regardless of result).
     */
    public static boolean isCheckComplete() {
        return checkComplete;
    }

    /**
     * Returns true if tamper was detected, the check is complete, and the
     * warning screen has not been shown this session.
     */
    public static boolean isTamperDetected() {
        return checkComplete && tamperDetected && !shownThisSession
                && !NullTraceConfig.getInstance().getSettings().isTamperWarningDismissed();
    }

    /**
     * Marks the tamper warning as shown for this session.
     * Prevents the screen from appearing again even if user navigates back.
     */
    public static void markShown() {
        shownThisSession = true;
    }

    /**
     * Resets the shown flag. Used by the config screen reset button.
     */
    public static void resetShown() {
        shownThisSession = false;
    }

    /**
     * Returns the expected SHA-256 digest from the GitLab release checksum asset, or null if not yet checked.
     */
    public static String getExpectedDigest() {
        return expectedDigest;
    }

    /**
     * Returns the actual SHA-256 digest of the running jar, or null if not yet checked.
     */
    public static String getActualDigest() {
        return actualDigest;
    }

    /**
     * Finds the release asset whose version range covers the given MC version.
     * Asset names follow the pattern: nulltrace-{range}+v{mod_version}.jar
     * where range is either a single version (e.g., "26.1") or a range (e.g., "1.21.2-1.21.5").
     */
    private static JsonObject findMatchingAsset(JsonArray assets, String mcVersion) {
        for (JsonElement element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.has("name") ? asset.get("name").getAsString() : "";
            if (!name.startsWith("nulltrace-") || !name.endsWith(".jar")) continue;

            // Extract version range: between "nulltrace-" and "+v"
            int rangeStart = "nulltrace-".length();
            int rangeEnd = name.indexOf("+v");
            if (rangeEnd <= rangeStart) continue;

            String range = name.substring(rangeStart, rangeEnd);
            int dash = range.indexOf('-');

            if (dash == -1) {
                // Single version: exact match
                if (range.equals(mcVersion)) return asset;
            } else {
                // Range: min-max (inclusive)
                String min = range.substring(0, dash);
                String max = range.substring(dash + 1);
                if (compareVersions(mcVersion, min) >= 0 && compareVersions(mcVersion, max) <= 0) {
                    return asset;
                }
            }
        }
        return null;
    }

    private static JsonObject findChecksumAsset(JsonArray links, String checksumName) {
        for (JsonElement element : links) {
            JsonObject link = element.getAsJsonObject();
            if (checksumName.equals(link.has("name") ? link.get("name").getAsString() : "")) {
                return link;
            }
        }
        return null;
    }

    /**
     * Compares two dot-separated version strings numerically.
     * Returns negative if a < b, zero if equal, positive if a > b.
     */
    private static int compareVersions(String a, String b) {
        String[] aParts = a.split("\\.");
        String[] bParts = b.split("\\.");
        int len = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < len; i++) {
            int aNum = i < aParts.length ? Integer.parseInt(aParts[i]) : 0;
            int bNum = i < bParts.length ? Integer.parseInt(bParts[i]) : 0;
            if (aNum != bNum) return Integer.compare(aNum, bNum);
        }
        return 0;
    }

    /**
     * Converts a byte array to a lowercase hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
