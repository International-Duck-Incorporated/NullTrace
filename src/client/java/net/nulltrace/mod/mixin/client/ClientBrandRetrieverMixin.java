package net.nulltrace.mod.mixin.client;

import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.privacy.PrivacyAction;
import net.nulltrace.mod.privacy.PrivacyAudit;
import net.nulltrace.mod.privacy.PrivacyDecision;
import net.nulltrace.mod.privacy.PrivacySurface;
import net.minecraft.client.ClientBrandRetriever;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spoofs the client brand at its source.
 */
@Mixin(ClientBrandRetriever.class)
public class ClientBrandRetrieverMixin {
    
    @Unique
    private static final AtomicBoolean nulltrace$logged = new AtomicBoolean(false);
    
    @Inject(method = "getClientModName", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGetClientModName(CallbackInfoReturnable<String> cir) {
        NullTraceConfig config = NullTraceConfig.getInstance();
        
        PrivacyDecision decision = PrivacyAudit.evaluate(PrivacySurface.CLIENT_BRAND, "client brand");
        if (decision.action() == PrivacyAction.BLOCK) {
            String spoofedBrand = config.getSettings().getEffectiveBrand();
            
            if (nulltrace$logged.compareAndSet(false, true)) {
                NullTrace.LOGGER.debug("[NullTrace] ClientBrandRetriever active - spoofing brand as: {}", spoofedBrand);
            }
            
            PrivacyAudit.record(PrivacySurface.CLIENT_BRAND, "client brand",
                    PrivacyAction.BLOCK, "Client brand normalized to " + spoofedBrand);
            cir.setReturnValue(spoofedBrand);
        }
    }
}

