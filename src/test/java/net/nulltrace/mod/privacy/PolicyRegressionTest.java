package net.nulltrace.mod.privacy;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Table-driven regression over the per-server profile modes.
 *
 * <p>This is the fixture the Python "adversarial probe server" never was: it asserts what the
 * policy <em>does</em> for every surface and every profile, so a protection that silently stops
 * blocking (or starts) fails here instead of in the wild. It runs in the normal Gradle test task
 * and needs no Minecraft runtime.
 */
class PolicyRegressionTest {

    private static PrivacyPolicy base() {
        // Mirrors NullTraceConfig#privacyPolicy() for default settings: brand allowed,
        // channels warned, protections ON where they ship enabled.
        return PrivacyPolicy.builder()
                .action(PrivacySurface.CLIENT_BRAND, PrivacyAction.ALLOW)
                .action(PrivacySurface.MOD_CHANNEL, PrivacyAction.WARN)
                .action(PrivacySurface.KNOWN_PACK, PrivacyAction.BLOCK)
                .action(PrivacySurface.TRANSLATION_PROBE, PrivacyAction.BLOCK)
                .action(PrivacySurface.KEYBIND_PROBE, PrivacyAction.BLOCK)
                .action(PrivacySurface.RESOURCE_PACK, PrivacyAction.BLOCK)
                .action(PrivacySurface.LOCAL_REQUEST, PrivacyAction.BLOCK)
                .action(PrivacySurface.SHADER_OVERRIDE, PrivacyAction.BLOCK)
                .action(PrivacySurface.CLIENT_INFORMATION, PrivacyAction.BLOCK)
                .action(PrivacySurface.SERVER_COOKIE, PrivacyAction.BLOCK)
                .action(PrivacySurface.SERVER_TRANSFER, PrivacyAction.BLOCK)
                .build();
    }

    @Test
    void standardProfilePassesTheBasePolicyThrough() {
        ServerProfile profile = new ServerProfile("example.com");
        assertEquals(ServerProfile.Mode.STANDARD, profile.mode());
        PrivacyPolicy fallback = base();
        assertSame(fallback, profile.policy(fallback),
                "STANDARD must not rebuild the policy; it is the fallback");
    }

    @Test
    void strictProfileBlocksEveryIdentitySurface() {
        ServerProfile profile = new ServerProfile("example.com");
        profile.setMode(ServerProfile.Mode.STRICT);
        PrivacyPolicy strict = profile.policy(base());

        assertEquals(PrivacyAction.BLOCK, strict.actionFor(PrivacySurface.CLIENT_BRAND));
        assertEquals(PrivacyAction.BLOCK, strict.actionFor(PrivacySurface.MOD_CHANNEL));
        assertEquals(PrivacyAction.BLOCK, strict.actionFor(PrivacySurface.KNOWN_PACK));
        assertEquals(PrivacyAction.BLOCK, strict.actionFor(PrivacySurface.TRANSLATION_PROBE));
        assertEquals(PrivacyAction.BLOCK, strict.actionFor(PrivacySurface.KEYBIND_PROBE));
        assertEquals(PrivacyAction.BLOCK, strict.actionFor(PrivacySurface.COMPONENT_PROBE));
        assertEquals(PrivacyAction.BLOCK, strict.actionFor(PrivacySurface.PROTOCOL_BEHAVIOR));
        // Pack requests stay usable but are recorded.
        assertEquals(PrivacyAction.WARN, strict.actionFor(PrivacySurface.RESOURCE_PACK));
        // STRICT keeps pack requests usable (WARN), so the loudest possible verdict is a notice:
        // no identity surface may remain visible.
        assertEquals(WarningAssessment.Severity.NOTICE, WarningAssessment.severity(strict),
                "strict must not leave an identity surface visible");
        for (WarningAssessment.Area area : WarningAssessment.Area.values()) {
            if (area.isIdentity()) {
                assertFalse(WarningAssessment.isVisible(strict, area), area + " must be hidden under STRICT");
            }
        }
    }

    @Test
    void maximumPrivacyBlocksEverySurfaceAndIsMarkedAsSuch() {
        ServerProfile profile = new ServerProfile("example.com");
        profile.setMode(ServerProfile.Mode.MAXIMUM_PRIVACY);
        PrivacyPolicy maximum = profile.policy(base());

        assertTrue(maximum.maximumPrivacy());
        for (PrivacySurface surface : PrivacySurface.values()) {
            assertEquals(PrivacyAction.BLOCK, maximum.actionFor(surface), surface + " must be blocked");
        }
        assertEquals(WarningAssessment.Severity.OK, WarningAssessment.severity(maximum));
    }

    @Test
    void customProfileOnlyAllowsWhatTheProfileSays() {
        ServerProfile profile = new ServerProfile("example.com");
        profile.setMode(ServerProfile.Mode.CUSTOM);
        profile.setNormalizeClientInformation(true);
        profile.setBlockServerCookies(false);
        profile.setBlockServerTransfers(true);
        profile.setBlockLocalRequests(false);

        PrivacyPolicy custom = profile.policy(base());
        assertEquals(PrivacyAction.BLOCK, custom.actionFor(PrivacySurface.CLIENT_INFORMATION));
        assertEquals(PrivacyAction.ALLOW, custom.actionFor(PrivacySurface.SERVER_COOKIE));
        assertEquals(PrivacyAction.BLOCK, custom.actionFor(PrivacySurface.SERVER_TRANSFER));
        assertEquals(PrivacyAction.WARN, custom.actionFor(PrivacySurface.LOCAL_REQUEST));
        // CUSTOM defaults to LOG for everything the profile does not name explicitly.
        assertEquals(PrivacyAction.LOG, custom.actionFor(PrivacySurface.CLIENT_BRAND));
    }

    @Test
    void customProfileChannelsAreAllowedThroughWhileBlockedOnesAreNot() {
        ServerProfile profile = new ServerProfile("example.com");
        profile.setMode(ServerProfile.Mode.CUSTOM);
        profile.setAllowedChannels(Set.of("nulltrace:whitelisted", "other:ok"));
        PrivacyPolicy custom = profile.policy(base());

        assertTrue(custom.allowsChannel("nulltrace:whitelisted"));
        assertTrue(custom.allowsChannel("other:ok"));
        assertFalse(custom.allowsChannel("not:listed"));
        assertEquals(PrivacyAction.ALLOW,
                custom.evaluate(PrivacySurface.MOD_CHANNEL, "nulltrace:whitelisted").action());
        assertEquals(PrivacyAction.LOG,
                custom.evaluate(PrivacySurface.MOD_CHANNEL, "not:listed").action());
    }

    @Test
    void allowedChannelsNeverOverrideANonChannelSurface() {
        PrivacyPolicy policy = PrivacyPolicy.builder()
                .action(PrivacySurface.CLIENT_BRAND, PrivacyAction.BLOCK)
                .allowChannel("nulltrace:test")
                .build();
        // A channel name that happens to equal a subject must not unlock an unrelated surface.
        assertEquals(PrivacyAction.BLOCK,
                policy.evaluate(PrivacySurface.CLIENT_BRAND, "nulltrace:test").action());
    }

    @Test
    void whitelistSurvivesProfileRebuilds() {
        ServerProfile profile = new ServerProfile("example.com");
        profile.setMode(ServerProfile.Mode.STRICT);
        profile.setAllowedChannels(Set.of("kept:channel"));
        PrivacyPolicy strict = profile.policy(base());
        assertTrue(strict.allowsChannel("kept:channel"),
                "switching to STRICT must keep the user's channel decisions");
    }

    @Test
    void everyDecisionCarriesAnActionAndAReason() {
        PrivacyPolicy policy = base();
        for (PrivacySurface surface : PrivacySurface.values()) {
            PrivacyDecision decision = policy.evaluate(surface, "subject");
            assertEquals(surface, decision.surface());
            assertEquals("subject", decision.subject());
            assertFalse(decision.reason().isBlank(), surface + " must explain itself");
            assertTrue(EnumSet.allOf(PrivacyAction.class).contains(decision.action()));
        }
    }

    @Test
    void missingSurfaceFallsBackToLogRatherThanAllow() {
        // A surface with no configured action must be recorded, never silently permitted.
        PrivacyPolicy empty = PrivacyPolicy.builder().build();
        assertEquals(PrivacyAction.LOG, empty.actionFor(PrivacySurface.CLIENT_BRAND));
    }

    @Test
    void policyIsImmutableAfterBuild() {
        PrivacyPolicy.Builder builder = PrivacyPolicy.builder()
                .action(PrivacySurface.CLIENT_BRAND, PrivacyAction.BLOCK);
        PrivacyPolicy built = builder.build();
        builder.action(PrivacySurface.CLIENT_BRAND, PrivacyAction.ALLOW);
        assertEquals(PrivacyAction.BLOCK, built.actionFor(PrivacySurface.CLIENT_BRAND),
                "a policy handed to a network thread must not change under it");
    }
}
