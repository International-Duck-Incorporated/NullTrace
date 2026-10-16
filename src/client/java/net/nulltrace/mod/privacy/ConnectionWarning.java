package net.nulltrace.mod.privacy;

import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.config.SpoofSettings;
import net.nulltrace.mod.tracking.ModRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gathers the counters and settings for the pre-connection warning, without opening a network
 * connection.
 *
 * <p>The decision logic itself lives in {@link WarningAssessment} (Minecraft-free, unit-tested);
 * this class only collects inputs from {@link ModRegistry} and {@link NullTraceConfig} and hands
 * them back as an immutable {@link Summary}. The screen owns all wording via
 * {@code NullTraceStrings}, so this class never formats user-visible text.
 */
public final class ConnectionWarning {
    private static final AtomicBoolean ALLOW_NEXT = new AtomicBoolean(false);

    private ConnectionWarning() {}

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

        /** The effective policy this summary was derived from. */
        public PrivacyPolicy policy() { return policy; }

        /** True when the server will receive anything from this area. */
        public boolean isVisible(WarningAssessment.Area area) {
            return WarningAssessment.isVisible(policy, area);
        }

        public int visibleAreaCount() {
            return WarningAssessment.visibleAreaCount(policy);
        }

        public WarningAssessment.Severity severity() {
            return WarningAssessment.severity(policy);
        }

        /** See {@link WarningAssessment#shouldWarn}. */
        public boolean shouldWarn() {
            return WarningAssessment.shouldWarn(severity(), alwaysWarn, muted);
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
