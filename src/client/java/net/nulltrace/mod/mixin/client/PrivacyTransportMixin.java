package net.nulltrace.mod.mixin.client;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.nulltrace.mod.privacy.PrivacyAction;
import net.nulltrace.mod.privacy.PrivacyAudit;
import net.nulltrace.mod.privacy.PrivacyDecision;
import net.nulltrace.mod.privacy.PrivacySurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blocks server state-transfer mechanisms that can persist identifiers across hosts. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class PrivacyTransportMixin {
    @Inject(method = "handleStoreCookie", at = @At("HEAD"), cancellable = true)
    private void nulltrace$blockStoreCookie(ClientboundStoreCookiePacket packet, CallbackInfo ci) {
        PrivacyDecision decision = PrivacyAudit.evaluate(PrivacySurface.SERVER_COOKIE, "server cookie");
        if (decision.action() == PrivacyAction.BLOCK) {
            ci.cancel();
        }
    }

    @Inject(method = "handleTransfer", at = @At("HEAD"), cancellable = true)
    private void nulltrace$blockTransfer(ClientboundTransferPacket packet, CallbackInfo ci) {
        PrivacyDecision decision = PrivacyAudit.evaluate(PrivacySurface.SERVER_TRANSFER, "server transfer");
        if (decision.action() == PrivacyAction.BLOCK) {
            ci.cancel();
        }
    }
}
