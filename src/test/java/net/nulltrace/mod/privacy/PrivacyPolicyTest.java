package net.nulltrace.mod.privacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrivacyPolicyTest {
    @Test
    void decisionsAreExplainableAndDeterministic() {
        PrivacyPolicy policy = PrivacyPolicy.builder()
                .action(PrivacySurface.CLIENT_BRAND, PrivacyAction.BLOCK)
                .action(PrivacySurface.MOD_CHANNEL, PrivacyAction.BLOCK)
                .allowChannel("example:allowed")
                .build();

        PrivacyDecision blocked = policy.evaluate(PrivacySurface.CLIENT_BRAND, "brand");
        PrivacyDecision allowed = policy.evaluate(PrivacySurface.MOD_CHANNEL, "example:allowed");

        assertEquals(PrivacyAction.BLOCK, blocked.action());
        assertEquals(PrivacyAction.ALLOW, allowed.action());
        assertFalse(blocked.reason().isBlank());
        assertFalse(allowed.reason().isBlank());
    }

    @Test
    void ledgerKeepsConnectionStateAndCounts() {
        AuditLedger ledger = new AuditLedger();
        ledger.begin(ConnectionContext.builder("example.net")
                .protocol("unknown")
                .exposedChannels(java.util.List.of("a:one", "b:two"))
                .build());
        ledger.record(PrivacyDecision.of(PrivacySurface.MOD_CHANNEL, "a:one",
                PrivacyAction.BLOCK, "test rule"));
        ledger.record(PrivacyDecision.of(PrivacySurface.CLIENT_BRAND, "brand",
                PrivacyAction.ALLOW, "test exposure"));

        assertEquals("example.net", ledger.context().server());
        assertEquals(1, ledger.count(PrivacyAction.BLOCK));
        assertEquals(1, ledger.remainingExposure());
        assertEquals(1, ledger.counts(PrivacyAction.BLOCK).get(PrivacySurface.MOD_CHANNEL));
    }

    @Test
    void strictProfileStrengthensPolicy() {
        ServerProfile profile = new ServerProfile("example.net");
        profile.setMode(ServerProfile.Mode.STRICT);
        PrivacyPolicy fallback = PrivacyPolicy.builder()
                .action(PrivacySurface.CLIENT_BRAND, PrivacyAction.ALLOW)
                .build();

        PrivacyPolicy strict = profile.policy(fallback);
        assertEquals(PrivacyAction.BLOCK, strict.actionFor(PrivacySurface.CLIENT_BRAND));
        assertEquals(PrivacyAction.BLOCK,
                strict.evaluate(PrivacySurface.MOD_CHANNEL, "mod:channel").action());
    }
}
