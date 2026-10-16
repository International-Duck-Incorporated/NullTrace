package net.nulltrace.mod.privacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the pre-connection warning decision.
 *
 * <p>These exist because the screen this logic drives had a real bug: it interrupted joins
 * whose {@code remainingExposure} was 0, and because a user-visible counter it displayed
 * ("8250 fingerprint surfaces are tracked") was an internals sum. Both are asserted below so
 * they cannot come back.
 */
class WarningAssessmentTest {

    private static PrivacyPolicy policy(PrivacyAction action) {
        PrivacyPolicy.Builder builder = PrivacyPolicy.builder();
        for (PrivacySurface surface : PrivacySurface.values()) {
            builder.action(surface, action);
        }
        return builder.build();
    }

    private static PrivacyPolicy blockingAll() {
        return policy(PrivacyAction.BLOCK);
    }

    /** Everything blocked except the tracking-shaped surfaces. */
    private static PrivacyPolicy identityBlockedOnly() {
        PrivacyPolicy.Builder builder = PrivacyPolicy.builder();
        for (PrivacySurface surface : PrivacySurface.values()) {
            builder.action(surface, PrivacyAction.BLOCK);
        }
        builder.action(PrivacySurface.RESOURCE_PACK, PrivacyAction.WARN)
               .action(PrivacySurface.SERVER_COOKIE, PrivacyAction.ALLOW);
        return builder.build();
    }

    @Test
    void fullyBlockedPolicyIsOkAndDoesNotInterrupt() {
        PrivacyPolicy blocked = blockingAll();
        assertEquals(WarningAssessment.Severity.OK, WarningAssessment.severity(blocked));
        assertEquals(0, WarningAssessment.visibleAreaCount(blocked));
        for (WarningAssessment.Area area : WarningAssessment.Area.values()) {
            assertFalse(WarningAssessment.isVisible(blocked, area), area + " should be hidden");
        }
        assertFalse(WarningAssessment.shouldWarn(WarningAssessment.Severity.OK, false, false),
                "a connection with nothing exposed must not be interrupted");
    }

    @Test
    void trackingOnlyIsANoticeNotAnAlarm() {
        PrivacyPolicy policy = identityBlockedOnly();
        assertEquals(WarningAssessment.Severity.NOTICE, WarningAssessment.severity(policy));
        assertTrue(WarningAssessment.isVisible(policy, WarningAssessment.Area.PACK_REQUESTS));
        assertTrue(WarningAssessment.isVisible(policy, WarningAssessment.Area.TRACKING));
        assertFalse(WarningAssessment.isVisible(policy, WarningAssessment.Area.CLIENT_BRAND));
        assertTrue(WarningAssessment.shouldWarn(WarningAssessment.severity(policy), false, false));
    }

    @Test
    void anyVisibleIdentitySurfaceIsExposed() {
        PrivacyPolicy.Builder builder = PrivacyPolicy.builder();
        for (PrivacySurface surface : PrivacySurface.values()) {
            builder.action(surface, PrivacyAction.BLOCK);
        }
        builder.action(PrivacySurface.MOD_CHANNEL, PrivacyAction.WARN);

        assertEquals(WarningAssessment.Severity.EXPOSED, WarningAssessment.severity(builder.build()));
        assertTrue(WarningAssessment.isVisible(builder.build(), WarningAssessment.Area.MOD_CHANNELS));
    }

    @Test
    void logIsTreatedAsVisible() {
        // LOG means "recorded, not blocked": the server still receives it.
        PrivacyPolicy.Builder builder = PrivacyPolicy.builder();
        for (PrivacySurface surface : PrivacySurface.values()) {
            builder.action(surface, PrivacyAction.BLOCK);
        }
        builder.action(PrivacySurface.CLIENT_INFORMATION, PrivacyAction.LOG);
        assertEquals(WarningAssessment.Severity.EXPOSED, WarningAssessment.severity(builder.build()));
    }

    @Test
    void alwaysWarnShowsTheReceiptEvenWhenClear() {
        assertTrue(WarningAssessment.shouldWarn(WarningAssessment.Severity.OK, true, false),
                "WarningMode.ALWAYS must still show an all-clear summary");
    }

    @Test
    void muteWinsOverEverything() {
        assertFalse(WarningAssessment.shouldWarn(WarningAssessment.Severity.EXPOSED, true, true),
                "a muted server must never be interrupted");
        assertFalse(WarningAssessment.shouldWarn(WarningAssessment.Severity.OK, false, true));
    }

    @Test
    void nullSeverityAndNullPolicyAreSafe() {
        assertFalse(WarningAssessment.shouldWarn(null, false, false));
        assertFalse(WarningAssessment.shouldWarn(null, true, false));
        assertEquals(WarningAssessment.Severity.OK, WarningAssessment.severity(null));
        assertEquals(0, WarningAssessment.visibleAreaCount(null));
        assertFalse(WarningAssessment.isVisible(null, WarningAssessment.Area.CLIENT_BRAND));
        assertFalse(WarningAssessment.isVisible(blockingAll(), null));
    }

    @Test
    void areasMapToDistinctSurfaces() {
        // Each surface group must be non-empty and the identity flag must not drift: the screen
        // colours rows from it, so a wrong flag shows a reassuring green row for a real leak.
        for (WarningAssessment.Area area : WarningAssessment.Area.values()) {
            assertNotEquals(0, area.surfaces().length, area + " has no surfaces");
        }
        assertTrue(WarningAssessment.Area.CLIENT_BRAND.isIdentity());
        assertTrue(WarningAssessment.Area.MOD_CHANNELS.isIdentity());
        assertTrue(WarningAssessment.Area.PACK_IDENTIFIERS.isIdentity());
        assertTrue(WarningAssessment.Area.TEXT_PROBES.isIdentity());
        assertTrue(WarningAssessment.Area.CLIENT_INFO.isIdentity());
        assertFalse(WarningAssessment.Area.PACK_REQUESTS.isIdentity());
        assertFalse(WarningAssessment.Area.TRACKING.isIdentity());
    }

    @Test
    void maximumPrivacyPolicyIsSilent() {
        assertEquals(WarningAssessment.Severity.OK,
                WarningAssessment.severity(PrivacyPolicy.maximumPrivacyPolicy()));
        assertFalse(WarningAssessment.shouldWarn(
                WarningAssessment.severity(PrivacyPolicy.maximumPrivacyPolicy()), false, false));
    }
}
