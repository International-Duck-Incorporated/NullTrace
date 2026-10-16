package net.nulltrace.mod.privacy;

import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.config.SpoofSettings;
import net.nulltrace.mod.tracking.ModRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Computes the pre-connection warning without opening a network connection.
 *
 * <p>Everything here is phrased as "what will this server actually receive?" and is
 * derived from the effective {@link PrivacyPolicy} for the target server instead of
 * from raw internal counters. The consent screen and the enforcement layer therefore
 * cannot disagree: if the policy blocks a surface, the screen reports it as hidden.
 *
 * <p>The screen itself owns all wording (via {@code NullTraceStrings}); this class
 * only answers yes/no questions and carries the counters for the details panel.
 */
public final class ConnectionWarning {
    private static final AtomicBoolean ALLOW_NEXT = new AtomicBoolean(false);

    private ConnectionWarning() {}

    /** How loud the warning has to be. */
    public enum Severity {
        /** Nothing reported reaches the server. */
        OK,
        /** Only tracking-shaped surfaces (pack requests, cookies, transfers) are visible. */
        NOTICE,
        /** At least one identity surface (brand, channels, packs, probes) is visible. */
        EXPOSED
    }

    /**
     * A user-facing grouping of one or more {@link PrivacySurface}s.
     *
     * <p>Declaration order is display order on the consent screen. Grouping keeps the
     * screen readable: a player understands "mod channels", not ten policy surfaces.
     */
    public enum Area {
        CLIENT_BRAND(PrivacySurface.CLIENT_BRAND),
        MOD_CHANNELS(PrivacySurface.MOD_CHANNEL),
        PACK_IDENTIFIERS(PrivacySurface.KNOWN_PACK),
        TEXT_PROBES(PrivacySurface.TRANSLATION_PROBE, PrivacySurface.KEYBIND_PROBE),
        CLIENT_INFO(PrivacySurface.CLIENT_INFORMATION),
        PACK_REQUESTS(PrivacySurface.RESOURCE_PACK, PrivacySurface.LOCAL_REQUEST),
        TRACKING(PrivacySurface.SERVER_COOKIE, PrivacySurface.SERVER_TRANSFER);

        private final PrivacySurface[] surfaces;

        Area(PrivacySurface... surfaces) {
            this.surfaces = surfaces;
        }

        public PrivacySurface[] surfaces() {
            return surfaces.clone();
        }

        /** Identity surfaces answer "which client is this?" — the ones players care about. */
        public boolean isIdentity() {
            return this == CLIENT_BRAND || this == MOD_CHANNELS || this == PACK_IDENTIFIERS
                    || this == TEXT_PROBES || this == CLIENT_INFO;
        }
    }

    public static void allowNextConnection() { ALLOW_NEXT.set(true); }

    public static boolean consumeBypass() { return ALLOW_NEXT.compareAndSet(true, false); }

    /**
     * Key used for server profiles and warning mutes: host only, no port, normalized.
     * Matches the key the join path stores, so a decision made here applies after connecting.
     */
    public static String serverKey(String server) {
        String key = server == null ? "" : server.trim();
        int colon = key.lastIndexOf(':');
        if (colon > 0) key = key.substring(0, colon);
        return NullTraceConfig.normalizeAddress(key);
    }

    /** Snapshot of what the target server will see, plus the counters the details panel shows. */
    public static Summary summarize(String server) {
        NullTraceConfig config = NullTraceConfig.getInstance();
        SpoofSettings settings = config.getSettings();
        String serverKey = serverKey(server);

        ServerProfile profile = config.getServerProfile(serverKey);
        PrivacyPolicy policy = profile.policy(config.privacyPolicy());

        int channelCount = 0;
        int exposedChannels = 0;
        boolean channelsBlocked = policy.actionFor(PrivacySurface.MOD_CHANNEL) == PrivacyAction.BLOCK;
        for (ModRegistry.ModInfo info : ModRegistry.getAllMods()) {
            for (Object channel : info.getChannels()) {
                channelCount++;
                if (!channelsBlocked || policy.allowsChannel(String.valueOf(channel))) exposedChannels++;
            }
        }

        return new Summary(
                server,
                serverKey,
                policy,
                // The brand is replaced, not suppressed: the server still receives
                // "vanilla" (or "fabric"), so the screen must not report "not sent".
                policy.actionFor(PrivacySurface.CLIENT_BRAND) == PrivacyAction.BLOCK
                        ? settings.getEffectiveBrand() : null,
                settings.getWarningMode() == SpoofSettings.WarningMode.ALWAYS,
                settings.isWarningMuted(serverKey),
                ModRegistry.getAllMods().size(),
                channelCount,
                exposedChannels,
                ModRegistry.getTranslationKeyCount(),
                ModRegistry.getKeybindCount(),
                ModRegistry.isKnownPacksHookPresent() ? ModRegistry.getKnownPackCount() : 0,
                ModRegistry.getShaderCount());
    }

    /** Immutable answer to "should we interrupt this connection, and with what?". */
    public static final class Summary {
        private final String server;
        private final String serverKey;
        private final PrivacyPolicy policy;
        private final String spoofedBrand;
        private final boolean alwaysWarn;
        private final boolean muted;
        private final int installedMods;
        private final int channelCount;
        private final int exposedChannels;
        private final int translationKeys;
        private final int keybindCount;
        private final int knownPackCount;
        private final int shaderCount;

        private Summary(String server, String serverKey, PrivacyPolicy policy, String spoofedBrand,
                        boolean alwaysWarn, boolean muted, int installedMods, int channelCount,
                        int exposedChannels, int translationKeys, int keybindCount, int knownPackCount,
                        int shaderCount) {
            this.server = server == null || server.isBlank() ? "unknown" : server;
            this.serverKey = serverKey;
            this.policy = policy;
            this.spoofedBrand = spoofedBrand;
            this.alwaysWarn = alwaysWarn;
            this.muted = muted;
            this.installedMods = installedMods;
            this.channelCount = channelCount;
            this.exposedChannels = exposedChannels;
            this.translationKeys = translationKeys;
            this.keybindCount = keybindCount;
            this.knownPackCount = knownPackCount;
            this.shaderCount = shaderCount;
        }

        /** Display form, may include the port. */
        public String server() { return server; }

        /** Host-only key used by server profiles. */
        public String serverKey() { return serverKey; }

        /** True when the server will receive anything from this area. */
        public boolean isVisible(Area area) {
            for (PrivacySurface surface : area.surfaces()) {
                if (policy.actionFor(surface) != PrivacyAction.BLOCK) return true;
            }
            return false;
        }

        public int visibleAreaCount() {
            int count = 0;
            for (Area area : Area.values()) {
                if (isVisible(area)) count++;
            }
            return count;
        }

        private Severity computeSeverity() {
            Severity severity = Severity.OK;
            for (Area area : Area.values()) {
                if (!isVisible(area)) continue;
                if (area.isIdentity()) return Severity.EXPOSED;
                severity = Severity.NOTICE;
            }
            return severity;
        }

        public Severity severity() { return computeSeverity(); }

        /**
         * Whether the consent screen must be shown. An all-clear connection is not
         * interrupted — interrupting players with "0 exposure" screens is what teaches
         * them to click through the warnings that do matter.
         */
        public boolean shouldWarn() {
            return !muted && (alwaysWarn || severity() != Severity.OK);
        }

        /**
         * The brand reported to the server when spoofing is active ("vanilla" or "fabric"),
         * or {@code null} when the real brand is sent. Used so the warning screen does not
         * claim the brand is hidden when it is in fact replaced.
         */
        public String spoofedBrand() { return spoofedBrand; }

        public int installedMods() { return installedMods; }
        public int channelCount() { return channelCount; }
        public int exposedChannels() { return exposedChannels; }
        public int translationKeys() { return translationKeys; }
        public int keybindCount() { return keybindCount; }
        public int knownPackCount() { return knownPackCount; }
        public int shaderCount() { return shaderCount; }
    }
}
