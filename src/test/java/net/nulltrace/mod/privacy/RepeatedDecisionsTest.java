package net.nulltrace.mod.privacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the folding used by the tail of {@code /nulltrace audit}: repeated CLIENT_BRAND
 * evaluations must not crowd out the rest of the window, and the counts must stay exact.
 */
class RepeatedDecisionsTest {

    private static PrivacyDecision decision(PrivacySurface surface, String subject, String reason) {
        return PrivacyDecision.of(surface, subject, PrivacyAction.BLOCK, reason);
    }

    @Test
    void foldsUnbrokenRunsAndCountsThem() {
        PrivacyDecision brand = decision(PrivacySurface.CLIENT_BRAND, "client brand", "Blocked by policy");
        PrivacyDecision probe = decision(PrivacySurface.TRANSLATION_PROBE, "foo.bar", "Unknown key");

        List<RepeatedDecisions.Run> runs = RepeatedDecisions.fold(
                List.of(brand, brand, brand, probe, brand), 8);

        assertEquals(3, runs.size());
        // Newest first.
        assertEquals(PrivacySurface.CLIENT_BRAND, runs.get(0).decision().surface());
        assertEquals(1, runs.get(0).count(), "a run is broken by the decision in between");
        assertEquals(1, runs.get(1).count());
        assertEquals(3, runs.get(2).count(), "three consecutive brand evaluations fold into one");
    }

    @Test
    void honoursTheLimitAndKeepsTheNewestRuns() {
        PrivacyDecision a = decision(PrivacySurface.CLIENT_BRAND, "client brand", "a");
        PrivacyDecision b = decision(PrivacySurface.CLIENT_BRAND, "client brand", "b");
        List<RepeatedDecisions.Run> runs = RepeatedDecisions.fold(List.of(a, b, a, b), 2);
        assertEquals(2, runs.size());
        assertEquals("b", runs.get(0).decision().reason());
        assertEquals("a", runs.get(1).decision().reason());
    }

    @Test
    void differentReasonIsADifferentRun() {
        // Same surface and subject, different explanation: two decisions, not one.
        List<RepeatedDecisions.Run> runs = RepeatedDecisions.fold(List.of(
                decision(PrivacySurface.CLIENT_BRAND, "client brand", "Blocked"),
                decision(PrivacySurface.CLIENT_BRAND, "client brand", "Normalized to vanilla")), 8);
        assertEquals(2, runs.size());
    }

    @Test
    void actionChangeBreaksTheRun() {
        PrivacyDecision blocked = PrivacyDecision.of(PrivacySurface.MOD_CHANNEL, "c:version", PrivacyAction.BLOCK, "r");
        PrivacyDecision allowed = PrivacyDecision.of(PrivacySurface.MOD_CHANNEL, "c:version", PrivacyAction.ALLOW, "r");
        assertEquals(2, RepeatedDecisions.fold(List.of(blocked, allowed), 8).size());
    }

    @Test
    void emptyAndDegenerateInputsAreSafe() {
        assertTrue(RepeatedDecisions.fold(null, 8).isEmpty());
        assertTrue(RepeatedDecisions.fold(List.of(), 8).isEmpty());
        PrivacyDecision one = decision(PrivacySurface.CLIENT_BRAND, "client brand", "r");
        assertTrue(RepeatedDecisions.fold(List.of(one), 0).isEmpty());
        assertEquals(1, RepeatedDecisions.fold(List.of(one), 1).size());
    }

    @Test
    void timestampsDoNotSplitARun() {
        PrivacyDecision first = new PrivacyDecision(PrivacySurface.CLIENT_BRAND, "client brand",
                PrivacyAction.BLOCK, "r", Instant.parse("2026-10-28T11:51:16Z"));
        PrivacyDecision second = new PrivacyDecision(PrivacySurface.CLIENT_BRAND, "client brand",
                PrivacyAction.BLOCK, "r", Instant.parse("2026-10-28T11:51:17Z"));
        assertEquals(1, RepeatedDecisions.fold(List.of(first, second), 8).size());
    }
}
