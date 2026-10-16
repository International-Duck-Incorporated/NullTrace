package net.nulltrace.mod.config;

import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.tracking.ModRegistry;
import net.nulltrace.mod.privacy.PrivacyAction;
import net.nulltrace.mod.privacy.PrivacyPolicy;
import net.nulltrace.mod.privacy.PrivacySurface;
import net.nulltrace.mod.privacy.ServerProfile;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Main configuration manager for NullTrace mod.
 * Handles loading, saving, and validating configuration settings.
 * Uses thread-safe singleton pattern with double-checked locking.
 */
public class NullTraceConfig {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("nulltrace.json");

    public static final boolean EXPLOIT_PREVENTER_LOADED =
        FabricLoader.getInstance().isModLoaded("exploitpreventer");

    // Chat signing and telemetry blocking overlap with dedicated mods. When one is
    // present NullTrace stands down on the shared feature (greys the control out) rather
    // than double-stripping signatures / double-disabling telemetry. Unlike EP this is
    // per-feature: No Chat Reports only covers signing; No Prying Eyes covers both.
    public static final boolean NO_CHAT_REPORTS_LOADED =
        FabricLoader.getInstance().isModLoaded("nochatreports");
    public static final boolean NO_PRYING_EYES_LOADED =
        FabricLoader.getInstance().isModLoaded("nopryingeyes");

    /** Chat signing is handled by an external mod (No Chat Reports or No Prying Eyes). */
    public static final boolean CHAT_SIGNING_MANAGED_EXTERNALLY =
        NO_CHAT_REPORTS_LOADED || NO_PRYING_EYES_LOADED;

    /** Telemetry blocking is handled by an external mod (No Chat Reports or No Prying Eyes). */
    public static final boolean TELEMETRY_MANAGED_EXTERNALLY =
        NO_CHAT_REPORTS_LOADED || NO_PRYING_EYES_LOADED;

    // Per-MC-version feature gate: DownloadQueue + multi-pack stacking arrived 1.20.3.
    // 1.20.1/1.20.2 isolate the pack cache via LegacyDownloadedPackSourceMixin instead.
    //? if >=1.20.3 {
    public static final boolean MC_VERSION_HAS_MULTI_PACK = true;
    //?} else {
    /*public static final boolean MC_VERSION_HAS_MULTI_PACK = false;
    *///?}

    // Block Local URLs: 1.20.1's HttpUtil lambda targeting was unreliable in practice; off there.
    //? if >=1.20.2 {
    public static final boolean MC_VERSION_HAS_BLOCK_LOCAL_URLS = true;
    //?} else {
    /*public static final boolean MC_VERSION_HAS_BLOCK_LOCAL_URLS = false;
    *///?}

    private static volatile NullTraceConfig INSTANCE;
    private static final Object LOCK = new Object();

    private SpoofSettings settings = new SpoofSettings();
    private volatile String currentServer = null;
    private final Map<String, ServerProfile> serverProfiles = new LinkedHashMap<>();

    private NullTraceConfig() {
        load();
        if (EXPLOIT_PREVENTER_LOADED) {
            NullTrace.LOGGER.info(
                "[NullTrace] ExploitPreventer detected - compatibility mode active."
            );
        }
        if (NO_CHAT_REPORTS_LOADED) {
            NullTrace.LOGGER.info(
                "[NullTrace] No Chat Reports detected - deferring chat signing and telemetry to it."
            );
        }
        if (NO_PRYING_EYES_LOADED) {
            NullTrace.LOGGER.info(
                "[NullTrace] No Prying Eyes detected - deferring chat signing and telemetry to it."
            );
        }
    }

    /** Display name of the mod managing chat signing, or {@code null} if NullTrace manages it. */
    public static String chatSigningManagerName() {
        if (NO_CHAT_REPORTS_LOADED) return "No Chat Reports";
        if (NO_PRYING_EYES_LOADED) return "No Prying Eyes";
        return null;
    }

    /** Display name of the mod managing telemetry, or {@code null} if NullTrace manages it. */
    public static String telemetryManagerName() {
        if (NO_CHAT_REPORTS_LOADED) return "No Chat Reports";
        if (NO_PRYING_EYES_LOADED) return "No Prying Eyes";
        return null;
    }

    public static NullTraceConfig getInstance() {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) {
                    INSTANCE = new NullTraceConfig();
                }
            }
        }
        return INSTANCE;
    }

    public void load() {
        if (!Files.exists(CONFIG_PATH)) {
            NullTrace.LOGGER.info("[NullTrace] Creating default config");
            save();
            return;
        }

        try {
            String content = Files.readString(CONFIG_PATH);
            if (content == null || content.trim().isEmpty()) {
                NullTrace.LOGGER.warn(
                    "[NullTrace] Config file is empty, using defaults"
                );
                settings = new SpoofSettings();
                save();
                return;
            }

            JsonObject json = JsonParser.parseString(content).getAsJsonObject();

            if (json.has("settings")) {
                settings = SpoofSettings.fromJson(
                    json.getAsJsonObject("settings")
                );
            } else if (json.has("defaultSettings")) {
                settings = SpoofSettings.fromJson(
                    json.getAsJsonObject("defaultSettings")
                );
            }

            serverProfiles.clear();
            if (json.has("serverProfiles") && json.get("serverProfiles").isJsonObject()) {
                for (Map.Entry<String, com.google.gson.JsonElement> entry
                        : json.getAsJsonObject("serverProfiles").entrySet()) {
                    if (entry.getValue().isJsonObject()) {
                        String server = normalizeAddress(entry.getKey());
                        serverProfiles.put(server, ServerProfile.fromJson(server, entry.getValue().getAsJsonObject()));
                    }
                }
            }

            if (!validateAndCorrectSettings(settings)) {
                NullTrace.LOGGER.warn(
                    "[NullTrace] Config validation failed, resetting to defaults"
                );
                settings = new SpoofSettings();
                save();
                return;
            }

            NullTrace.LOGGER.info(
                "[NullTrace] Loaded config - spoofAsVanilla: {}",
                settings.isSpoofAsVanilla()
            );
        } catch (IOException e) {
            NullTrace.LOGGER.error(
                "[NullTrace] Failed to read config file: {}",
                e.getMessage()
            );
            settings = new SpoofSettings();
            save();
        } catch (
            com.google.gson.JsonSyntaxException
            | IllegalStateException e
        ) {
            NullTrace.LOGGER.error(
                "[NullTrace] Invalid JSON in config file: {}",
                e.getMessage()
            );
            settings = new SpoofSettings();
            save();
        }
    }

    /**
     * Validates and auto-corrects settings if necessary.
     * This method modifies invalid settings to valid defaults.
     *
     * @param settings The settings to validate
     * @return true if settings were valid or have been corrected, false if settings is null
     */
    private boolean validateAndCorrectSettings(SpoofSettings settings) {
        if (settings == null) {
            NullTrace.LOGGER.error("[NullTrace] Validation failed: settings is null");
            return false;
        }

        boolean modified = false;

        if (settings.getSigningMode() == null) {
            NullTrace.LOGGER.warn(
                "[NullTrace] Invalid signing mode, resetting to SIGN"
            );
            settings.setSigningMode(SpoofSettings.SigningMode.SIGN);
            modified = true;
        }

        if (settings.getWhitelistMode() == null) {
            NullTrace.LOGGER.warn(
                "[NullTrace] Invalid whitelist mode, resetting to AUTO"
            );
            settings.setWhitelistMode(SpoofSettings.WhitelistMode.AUTO);
            modified = true;
        }

        // Spoof-as-vanilla is the master mode: it implies "block ALL custom payloads",
        // so an active whitelist (Auto/Custom) would be moot. Force the whitelist to
        // OFF (block all) whenever spoofAsVanilla is on. setSpoofAsVanilla normally
        // handles the swap, but hand-edited configs can still land here inconsistent.
        if (settings.isSpoofAsVanilla() && settings.getWhitelistMode() != SpoofSettings.WhitelistMode.OFF) {
            NullTrace.LOGGER.warn(
                "[NullTrace] active whitelist incompatible with spoofAsVanilla; forcing whitelist OFF (block all)"
            );
            settings.setWhitelistMode(SpoofSettings.WhitelistMode.OFF);
            modified = true;
        }

        // Block-All is no longer user-selectable: when spoof-vanilla is off, migrate
        // any leftover OFF mode (from older configs that exposed it as a tri-state)
        // back to AUTO so the user isn't stuck in an unreachable state.
        if (!settings.isSpoofAsVanilla() && settings.getWhitelistMode() == SpoofSettings.WhitelistMode.OFF) {
            NullTrace.LOGGER.info(
                "[NullTrace] Migrating legacy whitelist OFF (block all) to AUTO — option is no longer user-selectable"
            );
            settings.setWhitelistMode(SpoofSettings.WhitelistMode.AUTO);
            modified = true;
        }

        if (modified) {
            save();
        }

        return true;
    }

    public void save() {
        try {
            JsonObject json = new JsonObject();
            json.add("settings", settings.toJson());
            JsonObject profiles = new JsonObject();
            for (Map.Entry<String, ServerProfile> entry : serverProfiles.entrySet()) {
                profiles.add(entry.getKey(), entry.getValue().toJson());
            }
            json.add("serverProfiles", profiles);

            Files.createDirectories(CONFIG_PATH.getParent());

            Path tempFile = CONFIG_PATH.resolveSibling(
                CONFIG_PATH.getFileName() + ".tmp"
            );
            Files.writeString(tempFile, GSON.toJson(json));
            Files.move(
                tempFile,
                CONFIG_PATH,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException e) {
            NullTrace.LOGGER.error(
                "[NullTrace] Failed to save config to {}: {}",
                CONFIG_PATH,
                e.getMessage()
            );
        }
        // Null-guard avoids constructor's save→getInstance recursion; NullTraceClient seeds the first build.
        if (INSTANCE != null) {
            ModRegistry.rebuildDependencyClosure();
        }
    }

    public SpoofSettings getSettings() {
        return settings;
    }

    public void setCurrentServer(String server) {
        this.currentServer = normalizeAddress(server);
    }

    public String getCurrentServer() {
        return currentServer;
    }

    /** Returns a defensive copy of the configured profile for a server. */
    public synchronized ServerProfile getServerProfile(String server) {
        String key = normalizeAddress(server);
        ServerProfile profile = serverProfiles.get(key);
        if (profile == null) return new ServerProfile(key);
        ServerProfile copy = ServerProfile.fromJson(key, profile.toJson());
        return copy;
    }

    public synchronized ServerProfile.Mode getCurrentProfileMode() {
        return getServerProfile(currentServer).mode();
    }

    public synchronized void setCurrentProfileMode(ServerProfile.Mode mode) {
        String key = normalizeAddress(currentServer);
        ServerProfile profile = serverProfiles.computeIfAbsent(key, ServerProfile::new);
        profile.setMode(mode);
        save();
    }

    /**
     * Sets the profile mode for a specific server without touching {@link #currentServer}.
     * Used by the pre-connection warning, which knows the target server before the join
     * path stores it.
     */
    public synchronized void setProfileMode(String server, ServerProfile.Mode mode) {
        String key = normalizeAddress(server);
        ServerProfile profile = serverProfiles.computeIfAbsent(key, ServerProfile::new);
        profile.setMode(mode);
        save();
    }

    public synchronized Map<String, ServerProfile> getServerProfiles() {
        Map<String, ServerProfile> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ServerProfile> entry : serverProfiles.entrySet()) {
            copy.put(entry.getKey(), ServerProfile.fromJson(entry.getKey(), entry.getValue().toJson()));
        }
        return Map.copyOf(copy);
    }

    /** Effective policy for the current connection. */
    public synchronized PrivacyPolicy privacyPolicy() {
        PrivacyPolicy.Builder builder = PrivacyPolicy.builder();
        builder.action(PrivacySurface.CLIENT_BRAND,
                shouldSpoofBrand() ? PrivacyAction.BLOCK : PrivacyAction.ALLOW)
            .action(PrivacySurface.MOD_CHANNEL, settings.isVanillaMode()
                ? PrivacyAction.BLOCK : PrivacyAction.WARN)
            .action(PrivacySurface.KNOWN_PACK, shouldSpoofKnownPacks()
                ? PrivacyAction.BLOCK : PrivacyAction.ALLOW)
            .action(PrivacySurface.TRANSLATION_PROBE, isTranslationProtectionEnabled()
                ? PrivacyAction.BLOCK : PrivacyAction.ALLOW)
            .action(PrivacySurface.KEYBIND_PROBE, settings.isFakeDefaultKeybinds()
                ? PrivacyAction.BLOCK : PrivacyAction.ALLOW)
            .action(PrivacySurface.RESOURCE_PACK, shouldStripPack()
                ? PrivacyAction.BLOCK : PrivacyAction.ALLOW)
            .action(PrivacySurface.LOCAL_REQUEST, shouldBlockLocalPackUrls()
                ? PrivacyAction.BLOCK : PrivacyAction.WARN)
            .action(PrivacySurface.SHADER_OVERRIDE, shouldStripModShaders()
                ? PrivacyAction.BLOCK : PrivacyAction.ALLOW)
            .action(PrivacySurface.CLIENT_INFORMATION, shouldNormalizeClientInformation()
                ? PrivacyAction.BLOCK : PrivacyAction.ALLOW)
            .action(PrivacySurface.SERVER_COOKIE, settings.isBlockServerCookies()
                ? PrivacyAction.BLOCK : PrivacyAction.ALLOW)
            .action(PrivacySurface.SERVER_TRANSFER, settings.isBlockServerTransfers()
                ? PrivacyAction.BLOCK : PrivacyAction.ALLOW);
        ServerProfile profile = serverProfiles.get(normalizeAddress(currentServer));
        if (profile != null && profile.mode() != ServerProfile.Mode.STANDARD) {
            return profile.policy(builder.build());
        }
        return builder.build();
    }

    // Identity protection
    private boolean strictProfileActive() {
        ServerProfile.Mode mode = getServerProfile(currentServer).mode();
        return mode == ServerProfile.Mode.STRICT || mode == ServerProfile.Mode.MAXIMUM_PRIVACY;
    }

    /**
     * Whether the brand string should be overridden to "vanilla". A strict
     * server profile is allowed to strengthen the global setting, never weaken it.
     */
    public boolean shouldSpoofBrand() {
        return !EXPLOIT_PREVENTER_LOADED && (settings.isSpoofAsVanilla() || strictProfileActive());
    }

    /**
     * Whether channel spoofing/filtering should be active. Independent of brand —
     * channel filtering happens whenever NullTrace is in charge (i.e., EP not loaded).
     * The filter mode (block-all vs whitelist) is chosen by isVanillaMode/
     * isFabricMode in SpoofSettings, which read directly from spoofAsVanilla.
     */
    public boolean shouldSpoofChannels() {
        return !EXPLOIT_PREVENTER_LOADED && (settings.isSpoofAsVanilla() || strictProfileActive() || settings.isWhitelistEnabled());
    }

    /** Same EP gate as {@link #shouldSpoofChannels()} — kept distinct so the two can diverge without churn. */
    public boolean shouldSpoofKnownPacks() {
        return !EXPLOIT_PREVENTER_LOADED && (strictProfileActive() || settings.isSpoofAsVanilla() || settings.isWhitelistEnabled());
    }

    public String getEffectiveBrand() {
        return settings.getEffectiveBrand();
    }

    // Resource pack protection
    public boolean shouldIsolatePackCache() {
        return !EXPLOIT_PREVENTER_LOADED && settings.isIsolatePackCache();
    }

    public boolean shouldBlockLocalPackUrls() {
        return !EXPLOIT_PREVENTER_LOADED && (settings.isBlockLocalPackUrls() || strictProfileActive());
    }

    /** Per-mod shader strip. Not EP-gated — EP lacks this defense, so NullTrace keeps it active under EP. */
    public boolean shouldStripModShaders() {
        return settings.isStripModShaders();
    }

    /** True when any server-pack wrapping feature is active (whole-pack strip or per-mod shader strip). */
    public boolean shouldWrapServerPacks() {
        return shouldStripPack() || shouldStripModShaders();
    }

    // Key resolution protection
    public boolean isTranslationProtectionEnabled() {
        return (
            !EXPLOIT_PREVENTER_LOADED &&
            (settings.isTranslationProtectionEnabled() || strictProfileActive())
        );
    }

    public boolean isMeteorFix() {
        return settings.isMeteorFix();
    }

    // Bypass Server Pack Requirement
    public SpoofSettings.StripMode getPackStripMode() {
        return settings.getPackStripMode();
    }

    public boolean shouldStripPack() {
        // Wrapping remains active in MANUAL mode so a user can toggle an
        // individual pack off without disabling the protection layer. The mode
        // controls the initial pack state, not whether the wrapper exists.
        return !EXPLOIT_PREVENTER_LOADED;
    }

    public boolean shouldNormalizeClientInformation() {
        return !EXPLOIT_PREVENTER_LOADED && (settings.isNormalizeClientInformation() || strictProfileActive());
    }

    public boolean shouldBlockServerCookies() {
        ServerProfile profile = serverProfiles.get(normalizeAddress(currentServer));
        return settings.isBlockServerCookies()
                || (profile != null && (profile.mode() == ServerProfile.Mode.STRICT
                || profile.mode() == ServerProfile.Mode.MAXIMUM_PRIVACY
                || (profile.mode() == ServerProfile.Mode.CUSTOM && profile.blockServerCookies())));
    }

    public boolean shouldBlockServerTransfers() {
        ServerProfile profile = serverProfiles.get(normalizeAddress(currentServer));
        return settings.isBlockServerTransfers()
                || (profile != null && (profile.mode() == ServerProfile.Mode.STRICT
                || profile.mode() == ServerProfile.Mode.MAXIMUM_PRIVACY
                || (profile.mode() == ServerProfile.Mode.CUSTOM && profile.blockServerTransfers())));
    }

    /** The consent overlay prompt should show for an incoming push. */
    public boolean shouldShowPackOverlay() {
        return (
            !EXPLOIT_PREVENTER_LOADED &&
            settings.getPackStripMode() == SpoofSettings.StripMode.ASK
        );
    }

    // Alerts and logging
    public boolean shouldShowAlerts() {
        return settings.isShowAlerts();
    }

    public boolean shouldShowToasts() {
        return settings.isShowToasts();
    }

    public boolean isLogDetections() {
        return settings.isLogDetections();
    }

    public boolean isDebugAlerts() {
        return settings.isDebugAlerts();
    }

    // Chat signing
    public SpoofSettings.SigningMode getSigningMode() {
        return settings.getSigningMode();
    }

    public boolean shouldNotSign() {
        return !CHAT_SIGNING_MANAGED_EXTERNALLY && settings.shouldNotSign();
    }

    // Privacy
    public boolean shouldDisableTelemetry() {
        return !TELEMETRY_MANAGED_EXTERNALLY && settings.isDisableTelemetry();
    }

    /**
     * Normalizes a server address by converting to lowercase and removing default port.
     * @param address The server address to normalize
     * @return Normalized address or "unknown" if null
     */
    public static String normalizeAddress(String address) {
        if (address == null) return "unknown";
        String normalized = address.toLowerCase().trim();
        if (normalized.endsWith(":25565")) {
            normalized = normalized.substring(0, normalized.length() - 6);
        }
        return normalized;
    }
}
