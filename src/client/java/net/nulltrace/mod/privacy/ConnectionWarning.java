package net.nulltrace.mod.privacy;

import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.config.SpoofSettings;
import net.nulltrace.mod.tracking.ModRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

/** Computes the pre-connection warning without opening a network connection. */
public final class ConnectionWarning {
    private static final AtomicBoolean ALLOW_NEXT = new AtomicBoolean(false);

    private ConnectionWarning() {}

    public record Summary(String server, int installedMods, int channels,
                          int exposedChannels, int probes, int remainingExposure) {
        public boolean shouldWarn() {
            return remainingExposure > 0 || exposedChannels > 0 || probes > 0;
        }
    }

    public static void allowNextConnection() { ALLOW_NEXT.set(true); }

    public static boolean consumeBypass() { return ALLOW_NEXT.compareAndSet(true, false); }

    public static Summary summarize(String server) {
        NullTraceConfig config = NullTraceConfig.getInstance();
        SpoofSettings settings = config.getSettings();
        ServerProfile profile = config.getServerProfile(server);
        boolean strict = profile.mode() == ServerProfile.Mode.STRICT
                || profile.mode() == ServerProfile.Mode.MAXIMUM_PRIVACY;

        int channels = 0;
        for (ModRegistry.ModInfo info : ModRegistry.getAllMods()) channels += info.getChannels().size();
        int exposedChannels = (strict || settings.isSpoofAsVanilla()) ? 0 : channels;
        int probes = ModRegistry.getTranslationKeyCount()
                + (ModRegistry.isKnownPacksHookPresent() ? ModRegistry.getKnownPackCount() : 0);
        int remaining = (strict || settings.isSpoofAsVanilla() ? 0 : 1)
                + exposedChannels
                + (strict || settings.isTranslationProtectionEnabled() ? 0 : ModRegistry.getTranslationKeyCount())
                + (strict || settings.isBlockLocalPackUrls() ? 0 : 1);
        return new Summary(server, ModRegistry.getAllMods().size(), channels,
                exposedChannels, probes, remaining);
    }
}
