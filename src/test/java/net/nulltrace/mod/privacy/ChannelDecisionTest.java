package net.nulltrace.mod.privacy;

import net.nulltrace.mod.privacy.ChannelDecision.Action;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbound payload rule, which used to be duplicated in two places in
 * {@code ClientConnectionMixin} with different behaviour.
 */
class ChannelDecisionTest {

    private static final Set<String> WHITELISTED = Set.of("voicechat:packet", "minecraft:brand");

    private static Action decide(boolean spoofing, boolean vanilla, boolean fabric,
                                 String channel, boolean brand) {
        int colon = channel.indexOf(':');
        String namespace = channel.substring(0, colon);
        String path = channel.substring(colon + 1);
        return ChannelDecision.decide(spoofing, vanilla, fabric, namespace, path, brand,
                WHITELISTED::contains);
    }

    @Test
    void spoofingDisabledLetsEverythingThrough() {
        assertEquals(Action.PASS, decide(false, false, false, "somemod:payload", false));
        assertEquals(Action.PASS, decide(false, true, false, "somemod:payload", false));
        assertEquals(Action.PASS, decide(false, false, true, "minecraft:register", false));
    }

    @Test
    void brandIsAlwaysForwardedBecauseTheStringIsSpoofedAlready() {
        assertEquals(Action.PASS, decide(true, true, false, "minecraft:brand", true));
        assertEquals(Action.PASS, decide(true, false, true, "minecraft:brand", true));
    }

    @Test
    void viaIdentityIsBlockedInEveryMode() {
        for (String channel : new String[]{
                "vv:mod_details", "vv:app_details", "vv:proxy_details"}) {
            assertEquals(Action.DROP, decide(true, false, true, channel, false),
                    channel + " must be blocked in fabric mode");
            assertEquals(Action.DROP, decide(true, true, false, channel, false),
                    channel + " must be blocked in vanilla mode");
        }
    }

    @Test
    void viaIdentityCannotBeReEnabledByWhitelisting() {
        // The regression the shared policy exists for: the block used to live only in the
        // Netty handler, so a failed install silently restored the leak.
        assertTrue(ChannelDecision.isViaIdentity("vv", "mod_details"));
        assertEquals(Action.DROP, ChannelDecision.decide(true, false, true,
                "vv", "mod_details", false, channel -> true));
    }

    @Test
    void vanillaModeDropsAllCustomPayloads() {
        assertEquals(Action.DROP, decide(true, true, false, "voicechat:packet", false));
        assertEquals(Action.DROP, decide(true, true, false, "minecraft:register", false));
        assertEquals(Action.DROP, decide(true, true, false, "minecraft:custom_query", false));
    }

    @Test
    void fabricModeFiltersRegistrations() {
        assertEquals(Action.FILTER_REGISTRATION, decide(true, false, true, "minecraft:register", false));
        assertEquals(Action.FILTER_REGISTRATION, decide(true, false, true, "minecraft:unregister", false));
    }

    @Test
    void fabricModeGatesEverythingElseOnTheWhitelist() {
        assertEquals(Action.PASS, decide(true, false, true, "voicechat:packet", false));
        assertEquals(Action.DROP, decide(true, false, true, "othermod:payload", false));
        // A mod masquerading under minecraft: must not be short-circuited to allow.
        assertEquals(Action.DROP, decide(true, false, true, "minecraft:custom_query", false));
        // Realms-only channel, blocked to match stock Fabric behaviour.
        assertEquals(Action.DROP, decide(true, false, true, "minecraft:mco", false));
    }

    @Test
    void registrationHelpersRecogniseTheVanillaChannels() {
        assertTrue(ChannelDecision.isRegistration("minecraft", "register"));
        assertTrue(ChannelDecision.isRegistration("minecraft", "unregister"));
        assertFalse(ChannelDecision.isRegistration("minecraft", "brand"));
        assertFalse(ChannelDecision.isRegistration("someMod", "register"));
        assertTrue(ChannelDecision.isBrand("minecraft", "brand"));
        assertFalse(ChannelDecision.isBrand("minecraft", "mco"));
    }

    @Test
    void dropReasonsAreSpecific() {
        assertTrue(ChannelDecision.dropReason("vv", "mod_details", false).contains("identity"));
        assertTrue(ChannelDecision.dropReason("someMod", "x", true).contains("Vanilla profile"));
        assertTrue(ChannelDecision.dropReason("minecraft", "mco", false).contains("stock Fabric"));
        assertTrue(ChannelDecision.dropReason("someMod", "x", false).contains("active profile"));
    }
}
