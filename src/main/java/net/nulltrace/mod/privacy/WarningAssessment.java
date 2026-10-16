package net.nulltrace.mod.privacy;

/**
 * Pure, Minecraft-free evaluation of "what will this server actually receive?", used by the
 * pre-connection warning screen.
 *
 * <p>This exists so the highest-stakes screen in the mod can be unit-tested. The logic used to
 * live inside the client-side {@code ConnectionWarning} together with counter gathering, which
 * meant the rule that decides whether a player gets interrupted could only be exercised by
 * launching the game — and the bug that started the warning redesign (interrupting a join with
 * zero exposure) went unnoticed because of exactly that.
 *
 * <p>Everything here is derived from the effective {@link PrivacyPolicy} for the target server,
 * so the screen and the enforcement layer cannot disagree.
 */
public final class WarningAssessment {
    private WarningAssessment() {}

    /** How loud the warning needs to be. */
    public enum Severity {
        /** Nothing reported reaches the server. */
        OK,
        /** Only tracking-shaped surfaces (pack requests, cookies, transfers) are visible. */
        NOTICE,
        /** At least one identity surface (brand, channels, packs, probes, client info) is visible. */
        EXPOSED
    }

    /**
     * A user-facing grouping of one or more {@link PrivacySurface}s.
     *
     * <p>Declaration order is display order. Grouping keeps the screen readable: a player
     * understands "mod channels", not ten policy surfaces.
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

    /** True when anything from this area reaches the server (i.e. is not BLOCKed). */
    public static boolean isVisible(PrivacyPolicy policy, Area area) {
        if (policy == null || area == null) return false;
        for (PrivacySurface surface : area.surfaces()) {
            if (policy.actionFor(surface) != PrivacyAction.BLOCK) return true;
        }
        return false;
    }

    public static int visibleAreaCount(PrivacyPolicy policy) {
        int count = 0;
        for (Area area : Area.values()) {
            if (isVisible(policy, area)) count++;
        }
        return count;
    }

    /** Any visible identity area is loud; only tracking-shaped areas is a notice; nothing is OK. */
    public static Severity severity(PrivacyPolicy policy) {
        Severity severity = Severity.OK;
        for (Area area : Area.values()) {
            if (!isVisible(policy, area)) continue;
            if (area.isIdentity()) return Severity.EXPOSED;
            severity = Severity.NOTICE;
        }
        return severity;
    }

    /**
     * Whether the pre-connection screen must be shown.
     *
     * <p>An all-clear connection is not interrupted by default: interrupting players with a
     * "0 exposure" screen is what teaches them to click through the warnings that do matter.
     *
     * @param severity    outcome of {@link #severity(PrivacyPolicy)}
     * @param alwaysWarn  user opted into a receipt for every connection ({@code WarningMode.ALWAYS})
     * @param muted       the player muted the prompt for this server
     */
    public static boolean shouldWarn(Severity severity, boolean alwaysWarn, boolean muted) {
        if (muted || severity == null) return false;
        return alwaysWarn || severity != Severity.OK;
    }
}
