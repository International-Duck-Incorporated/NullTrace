package net.nulltrace.mod.privacy;

import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.tracking.ModRegistry;

import java.util.LinkedHashSet;
import java.util.Set;

/** Runtime bridge used by mixins and the /nulltrace audit command. */
public final class PrivacyAudit {
    private static final AuditLedger LEDGER = new AuditLedger();

    private PrivacyAudit() {}

    public static AuditLedger ledger() { return LEDGER; }

    public static void begin(String server, String protocol) {
        Set<String> mods = new LinkedHashSet<>();
        Set<String> channels = new LinkedHashSet<>();
        for (ModRegistry.ModInfo info : ModRegistry.getAllMods()) {
            mods.add(info.getModId());
            info.getChannels().forEach(channel -> channels.add(channel.toString()));
        }
        LEDGER.begin(ConnectionContext.builder(server)
                .protocol(protocol)
                .detectedMods(mods)
                .exposedChannels(channels)
                .currentExposure(channels.size())
                .build());
        NullTrace.LOGGER.debug("[NullTrace] Started privacy audit for {}", server);
    }

    public static void begin(String server, String protocol, Set<String> channels,
                             Set<String> knownPacks, Set<String> translations) {
        LEDGER.begin(ConnectionContext.builder(server)
                .protocol(protocol)
                .exposedChannels(channels)
                .knownPacks(knownPacks)
                .translations(translations)
                .build());
    }

    public static PrivacyDecision record(PrivacySurface surface, String subject,
                                         PrivacyAction action, String reason) {
        PrivacyDecision decision = PrivacyDecision.of(surface, subject, action, reason);
        LEDGER.record(decision);
        if (action == PrivacyAction.BLOCK) {
            NullTrace.LOGGER.debug("[NullTrace] {} {}: {}", action, surface, subject);
        }
        return decision;
    }

    public static PrivacyDecision evaluate(PrivacySurface surface, String subject) {
        PrivacyDecision decision = NullTraceConfig.getInstance().privacyPolicy().evaluate(surface, subject);
        LEDGER.record(decision);
        return decision;
    }

    public static void clear() {
        LEDGER.begin(ConnectionContext.builder().build());
    }
}
