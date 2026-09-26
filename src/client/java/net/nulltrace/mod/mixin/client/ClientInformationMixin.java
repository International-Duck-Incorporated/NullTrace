package net.nulltrace.mod.mixin.client;

import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Normalizes the client-information packet so ordinary local preferences do not
 * become a server-side fingerprint. Appearance-affecting skin-part and hand
 * settings are preserved.
 */
@Mixin(ServerboundClientInformationPacket.class)
public abstract class ClientInformationMixin {
    @Mutable
    @Final
    @Shadow
    private ClientInformation information;

    @Inject(method = "<init>(Lnet/minecraft/server/level/ClientInformation;)V", at = @At("TAIL"))
    private void nulltrace$normalizeClientInformation(ClientInformation original, CallbackInfo ci) {
        // The official mappings expose these record components using the
        // legacy-compatible names on all supported targets.
        this.information = new ClientInformation(
                "en_us",
                8,
                ChatVisiblity.FULL,
                true,
                original.modelCustomisation(),
                original.mainHand(),
                false,
                false,
                ParticleStatus.ALL
        );
    }
}
