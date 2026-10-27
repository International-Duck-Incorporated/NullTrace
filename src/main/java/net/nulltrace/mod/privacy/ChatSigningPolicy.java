package net.nulltrace.mod.privacy;

/**
 * The single chat-signing decision.
 *
 * <p>Chat signing used to be decided in two mixins that each read the config themselves:
 * {@code ClientPacketListenerMixin} skipped the {@code SignedMessageChain.Encoder.pack} call
 * inside {@code sendChat}, and {@code ServerboundChatPacketMixin} nulled the signature on the
 * packet after construction and again on the accessor. Two copies of one rule is how the
 * outbound-channel filter drifted, so the rule lives here now, with no Minecraft types, and
 * {@code ChatSigningPolicyTest} pins the matrix.
 *
 * <p>The two call sites are deliberately <em>not</em> redundant. Skipping the encoder stops the
 * local signing chain from advancing; nulling the packet stops a signature that was already
 * produced (a message queued before the toggle, a resend, another mod constructing the packet)
 * from reaching the wire. Either one alone leaves a gap, which is why both exist — but they now
 * share one source of truth.
 *
 * <p><b>Wire behaviour is unchanged by this class.</b> The reason both call sites exist, and what
 * happens on a server with {@code enforce-secure-profile=true} (the message is rejected, the
 * client shows "chat not sent"), is documented in {@code docs/reviews/src-review-2026-10-26-fixes.md}.
 * Changing that needs a decision and a real server; this class only makes the decision auditable.
 */
public final class ChatSigningPolicy {
    private ChatSigningPolicy() {}

    /** Why a message is being treated the way it is. Useful in debug logs and the audit ledger. */
    public enum Reason {
        /** The user asked for signing, and nothing external manages it. */
        SIGNING,
        /** The user turned signing off. */
        USER_OFF,
        /** Another mod (No Chat Reports, No Prying Eyes) manages signing; stay out of its way. */
        EXTERNAL_MANAGER
    }

    /**
     * @param signingModeOff     the user's stored {@code SigningMode == OFF}
     * @param managedExternally  a known mod owns chat signing (NullTrace defers)
     */
    public static Reason reason(boolean signingModeOff, boolean managedExternally) {
        if (managedExternally) return Reason.EXTERNAL_MANAGER;
        if (signingModeOff) return Reason.USER_OFF;
        return Reason.SIGNING;
    }

    /**
     * True when NullTrace must leave signing alone: the encoder runs and any signature already on
     * the packet stays there.
     *
     * <p>That is the case both when the user asked for signing and when another mod manages it —
     * deferring to No Chat Reports is not the same as actively signing, but it is the same as
     * "do not touch", which is what this predicate answers. The two are told apart by
     * {@link #reason}.
     */
    public static boolean shouldSign(boolean signingModeOff, boolean managedExternally) {
        return reason(signingModeOff, managedExternally) != Reason.USER_OFF;
    }

    /** True when the encoder should be skipped and any signature stripped from the packet. */
    public static boolean shouldStrip(boolean signingModeOff, boolean managedExternally) {
        return reason(signingModeOff, managedExternally) == Reason.USER_OFF;
    }

    /**
     * True when a stripped packet is expected to be rejected by servers that enforce signed chat.
     *
     * <p>Only a user-chosen OFF does that. When an external mod manages signing we strip
     * nothing, so this returns false and the "may not be delivered" hint is not shown.
     */
    public static boolean expectsRejectionOnStrictServers(boolean signingModeOff, boolean managedExternally) {
        return reason(signingModeOff, managedExternally) == Reason.USER_OFF;
    }

    /** Short human-readable explanation for logs and the audit ledger. */
    public static String describe(Reason reason) {
        switch (reason) {
            case USER_OFF:
                return "signing off by user choice; signatures are stripped and strict servers will reject chat";
            case EXTERNAL_MANAGER:
                return "signing deferred to an external mod";
            case SIGNING:
            default:
                return "signing messages";
        }
    }
}
