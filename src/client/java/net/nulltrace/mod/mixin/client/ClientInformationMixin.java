package net.nulltrace.mod.mixin.client;

import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.config.SpoofSettings;
import net.nulltrace.mod.privacy.PrivacyAction;
import net.nulltrace.mod.privacy.PrivacyAudit;
import net.nulltrace.mod.privacy.PrivacyDecision;
import net.nulltrace.mod.privacy.PrivacySurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Optionally normalizes the client-information packet so ordinary local
 * preferences do not become a server-side fingerprint. The setting is
 * deliberately granular: users can preserve compatibility-sensitive values.
 */
@Mixin(ServerboundClientInformationPacket.class)
public abstract class ClientInformationMixin {
    @Mutable
    @Final
    @Shadow
    private ClientInformation information;

    @Inject(method = "<init>(Lnet/minecraft/server/level/ClientInformation;)V", at = @At("TAIL"))
    private void nulltrace$normalizeClientInformation(ClientInformation original, CallbackInfo ci) {
        NullTraceConfig config = NullTraceConfig.getInstance();
        PrivacyDecision decision = PrivacyAudit.evaluate(PrivacySurface.CLIENT_INFORMATION, "client information");
        if (decision.action() != PrivacyAction.BLOCK) return;

        SpoofSettings settings = config.getSettings();
        String language = switch (settings.getNormalizedLanguage()) {
            case DEFAULT -> original.language();
            case EN_US -> "en_us";
            case RANDOM -> RANDOM_LANGUAGES[ThreadLocalRandom.current().nextInt(RANDOM_LANGUAGES.length)];
        };
        int viewDistance = settings.getNormalizedViewDistance() == SpoofSettings.NormalizedViewDistance.DEFAULT
                ? original.viewDistance() : 8;
        ChatVisiblity chatVisibility = settings.getNormalizedChatVisibility() == SpoofSettings.NormalizedChatVisibility.DEFAULT
                ? original.chatVisibility() : ChatVisiblity.FULL;
        ParticleStatus particles = settings.isNormalizeParticles() ? ParticleStatus.ALL : original.particleStatus();

        this.information = new ClientInformation(
                language,
                viewDistance,
                chatVisibility,
                true,
                original.modelCustomisation(),
                original.mainHand(),
                false,
                false,
                particles
        );
    }

    private static final String[] RANDOM_LANGUAGES = {
            "en_us", "de_de", "es_es", "fr_fr", "it_it", "ja_jp", "ko_kr", "pt_br"
    };
}
