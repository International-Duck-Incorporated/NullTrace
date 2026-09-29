package net.nulltrace.mod.config;

import net.nulltrace.mod.NullTrace;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Async update checker that queries the GitLab releases API to detect new versions.
 * All state fields are volatile for cross-thread visibility since the check runs
 * on a background thread and results are read on the render thread.
 */
public final class UpdateChecker {

    private static final String RELEASES_URL = "https://gitlab.com/api/v4/projects/international-duck-incorporated%2FNullTrace/releases/permalink/latest";
    private static final String FALLBACK_RELEASE_URL = "https://gitlab.com/international-duck-incorporated/NullTrace/-/releases";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile String latestVersion = null;
    private static volatile String releaseUrl = null;
    private static volatile boolean updateAvailable = false;
    private static volatile boolean checkComplete = false;
    private static volatile boolean shownThisSession = false;

    private UpdateChecker() {
        // Utility class
    }

    /**
     * Fires an async HTTP GET to the GitLab releases API to check for updates.
     * Non-blocking: runs on a daemon thread via CompletableFuture.
     */
    public static void checkForUpdate() {
        CompletableFuture.runAsync(() -> {
            try {
                String currentVersion = NullTrace.getVersion();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(RELEASES_URL))
                        .header("User-Agent", "NullTrace-Mod/" + currentVersion)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

                    String tagName = json.has("tag_name") ? json.get("tag_name").getAsString() : null;
                    String htmlUrl = json.has("_links") && json.getAsJsonObject("_links").has("web")
                            ? json.getAsJsonObject("_links").get("web").getAsString() : null;

                    if (tagName != null) {
                        // Strip leading "v" or "V" if present (e.g., "V1.0.5" -> "1.0.5")
                        String version = (tagName.startsWith("v") || tagName.startsWith("V")) ? tagName.substring(1) : tagName;
                        latestVersion = version;
                        releaseUrl = htmlUrl != null ? htmlUrl : FALLBACK_RELEASE_URL;

                        // Any difference means user should update
                        if (!version.equals(currentVersion)) {
                            updateAvailable = true;
                            NullTrace.LOGGER.info("[NullTrace] Update available: {} -> {} ({})", currentVersion, version, releaseUrl);
                        } else {
                            NullTrace.LOGGER.debug("[NullTrace] Mod is up to date ({})", currentVersion);
                        }
                    }
                } else {
                    NullTrace.LOGGER.debug("[NullTrace] GitLab API returned status {}", response.statusCode());
                }
            } catch (Exception e) {
                NullTrace.LOGGER.debug("[NullTrace] Update check failed: {}", e.getMessage());
            } finally {
                checkComplete = true;
            }
        });
    }

    /**
     * Returns true if an update is available, the check is complete, the
     * update screen has not been shown this session, and the user hasn't
     * skipped this specific version.
     */
    public static boolean isUpdateAvailable() {
        return checkComplete && updateAvailable && !shownThisSession
                && !NullTraceConfig.getInstance().getSettings().isVersionSkipped(latestVersion);
    }

    /**
     * Returns the latest version string from GitLab, or null if not yet checked.
     */
    public static String getLatestVersion() {
        return latestVersion;
    }

    /**
     * Returns the URL to the latest release page on GitLab.
     * Falls back to the generic latest release URL if not available.
     */
    public static String getReleaseUrl() {
        return releaseUrl != null ? releaseUrl : FALLBACK_RELEASE_URL;
    }

    /**
     * Marks the update notification as shown for this session.
     * Prevents the screen from appearing again even if user navigates back to title screen.
     */
    public static void markShown() {
        shownThisSession = true;
    }

    /**
     * Resets the session-shown flag so the update screen can appear again.
     * Called when the user resets all settings from the config screen.
     */
    public static void resetShown() {
        shownThisSession = false;
    }

    /**
     * Returns the current mod version. Delegates to {@link NullTrace#getVersion()}.
     */
    public static String getCurrentVersion() {
        return NullTrace.getVersion();
    }
}
