package net.nulltrace.mod.config;

import net.fabricmc.loader.api.FabricLoader;
import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.update.UpdateMetadata;
import net.nulltrace.mod.update.UpdateMetadata.Release;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Async update checker for CurseForge and GitLab. Each source can fail independently;
 * a single immutable result is published once both checks have finished.
 */
public final class UpdateChecker {
    // The official CurseForge API needs a private key. CFWidget provides public
    // CurseForge metadata without shipping credentials in the client JAR.
    private static final String CURSEFORGE_API_URL = "https://api.cfwidget.com/" + UpdateMetadata.CURSEFORGE_PROJECT_ID;
    private static final String GITLAB_API_URL = "https://gitlab.com/api/v4/projects/international-duck-incorporated%2FNullTrace/releases/permalink/latest";
    private static final String FALLBACK_RELEASE_URL = UpdateMetadata.CURSEFORGE_PROJECT_URL + "/files/all";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final AtomicBoolean CHECK_STARTED = new AtomicBoolean();
    private static volatile CheckResult result = new CheckResult(null, false, false);
    private static volatile boolean shownThisSession = false;

    private record CheckResult(Release release, boolean updateAvailable, boolean complete) {}

    private UpdateChecker() {}

    /** Checks both feeds in parallel, once per client session, without blocking the render thread. */
    public static void checkForUpdate() {
        if (!CHECK_STARTED.compareAndSet(false, true)) return;
        String currentVersion = NullTrace.getVersion();
        String minecraftVersion = FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
        if (minecraftVersion == null) {
            NullTrace.LOGGER.debug("[NullTrace] Could not determine Minecraft version, skipping update check");
            result = new CheckResult(null, false, true);
            return;
        }

        CompletableFuture<Release> curseForge = fetchRelease("CurseForge (CFWidget)", CURSEFORGE_API_URL,
                currentVersion, body -> UpdateMetadata.fromCurseForge(body, minecraftVersion));
        CompletableFuture<Release> gitLab = fetchRelease("GitLab", GITLAB_API_URL,
                currentVersion, body -> UpdateMetadata.fromGitLab(body, minecraftVersion));

        // Prefer CurseForge on a tie, but do not hide a newer GitLab-only release.
        curseForge.thenCombine(gitLab, UpdateMetadata::newest).whenComplete((release, error) -> {
            boolean available = error == null && release != null
                    && UpdateMetadata.isNewerVersion(release.version(), currentVersion);
            result = new CheckResult(error == null ? release : null, available, true);
            if (error != null) {
                NullTrace.LOGGER.debug("[NullTrace] Update check failed: {}", error.getMessage());
            } else if (available) {
                NullTrace.LOGGER.info("[NullTrace] Update available: {} -> {} ({})",
                        currentVersion, release.version(), release.url());
            } else if (release != null) {
                NullTrace.LOGGER.debug("[NullTrace] No newer compatible release found (installed: {}, published: {})",
                        currentVersion, release.version());
            } else {
                NullTrace.LOGGER.debug("[NullTrace] No compatible release metadata found for Minecraft {}", minecraftVersion);
            }
        });
    }

    private static CompletableFuture<Release> fetchRelease(String source, String url, String currentVersion,
                                                           Function<String, Release> parse) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "NullTrace-Mod/" + currentVersion)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return parse.apply(response.body());
                NullTrace.LOGGER.debug("[NullTrace] {} API returned status {}", source, response.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                NullTrace.LOGGER.debug("[NullTrace] {} update check interrupted", source);
            } catch (Exception e) {
                NullTrace.LOGGER.debug("[NullTrace] {} update check failed: {}", source, e.getMessage());
            }
            return null;
        });
    }

    /** Notification eligibility, including per-session dismissal and persistent version skipping. */
    public static boolean isUpdateAvailable() {
        CheckResult snapshot = result;
        return snapshot.complete() && snapshot.updateAvailable() && !shownThisSession
                && !NullTraceConfig.getInstance().getSettings().isVersionSkipped(snapshot.release().version());
    }

    /** Actual version status, independent of whether the user dismissed the notification. */
    public static boolean isOutdated() {
        CheckResult snapshot = result;
        return snapshot.complete() && snapshot.updateAvailable();
    }

    /** Latest compatible stable mod version from either source, or null if unavailable. */
    public static String getLatestVersion() {
        Release release = result.release();
        return release != null ? release.version() : null;
    }

    /** Opens the selected CurseForge file or GitLab release page, never an automatic download. */
    public static String getReleaseUrl() {
        Release release = result.release();
        return release != null ? release.url() : FALLBACK_RELEASE_URL;
    }

    public static void markShown() {
        shownThisSession = true;
    }

    /** Called when the user resets all settings from the configuration screen. */
    public static void resetShown() {
        shownThisSession = false;
    }

    public static String getCurrentVersion() {
        return NullTrace.getVersion();
    }
}
