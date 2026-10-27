package net.nulltrace.mod.privacy;

import net.nulltrace.mod.privacy.ChatSigningPolicy.Reason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat-signing rule, which used to be read straight out of the config in two separate
 * mixins ({@code ClientPacketListenerMixin} skipping the encoder, {@code ServerboundChatPacketMixin}
 * stripping the packet).
 */
class ChatSigningPolicyTest {

    @Test
    void signingModeSignAndNobodyElseInCharge_meansSign() {
        assertEquals(Reason.SIGNING, ChatSigningPolicy.reason(false, false));
        assertTrue(ChatSigningPolicy.shouldSign(false, false));
        assertFalse(ChatSigningPolicy.shouldStrip(false, false));
    }

    @Test
    void signingModeOff_meansStrip() {
        assertEquals(Reason.USER_OFF, ChatSigningPolicy.reason(true, false));
        assertFalse(ChatSigningPolicy.shouldSign(true, false));
        assertTrue(ChatSigningPolicy.shouldStrip(true, false));
    }

    @Test
    void externalManagerWins_overOurOwnSetting() {
        // No Chat Reports / No Prying Eyes installed: NullTrace must not touch signatures even
        // if our stored mode says OFF, or the two mods fight over the same packets.
        assertEquals(Reason.EXTERNAL_MANAGER, ChatSigningPolicy.reason(true, true));
        assertFalse(ChatSigningPolicy.shouldStrip(true, true));
        assertTrue(ChatSigningPolicy.shouldSign(true, true));
    }

    @Test
    void externalManagerWithSigningOn_isStillExternal() {
        assertEquals(Reason.EXTERNAL_MANAGER, ChatSigningPolicy.reason(false, true));
        assertTrue(ChatSigningPolicy.shouldSign(false, true));
    }

    @Test
    void strictServerHint_onlyWhenTheUserTurnedSigningOff() {
        assertTrue(ChatSigningPolicy.expectsRejectionOnStrictServers(true, false));
        assertFalse(ChatSigningPolicy.expectsRejectionOnStrictServers(false, false));
        // Deferring to another mod is not our doing, so we must not claim chat may be rejected.
        assertFalse(ChatSigningPolicy.expectsRejectionOnStrictServers(true, true));
    }

    @Test
    void signAndStripAreMutuallyExclusive() {
        for (boolean off : new boolean[]{false, true}) {
            for (boolean external : new boolean[]{false, true}) {
                boolean sign = ChatSigningPolicy.shouldSign(off, external);
                boolean strip = ChatSigningPolicy.shouldStrip(off, external);
                assertFalse(sign && strip, "sign and strip both true for off=" + off + " external=" + external);
            }
        }
    }

    @Test
    void everyReasonHasANonEmptyDescription() {
        for (Reason r : Reason.values()) {
            String text = ChatSigningPolicy.describe(r);
            assertTrue(text != null && !text.isBlank(), "no description for " + r);
        }
    }

    @Test
    void descriptionForUserOffMentionsTheConsequence() {
        // The audit ledger shows this string; it has to say what actually happens.
        String text = ChatSigningPolicy.describe(Reason.USER_OFF);
        assertTrue(text.contains("stripped"), text);
        assertTrue(text.contains("reject"), text);
    }
}
