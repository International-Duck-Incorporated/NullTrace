package net.nulltrace.mod.mixin.client;

import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.config.NullTraceConfig;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Core Minecraft client hooks for telemetry blocking.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "allowsTelemetry", at = @At("HEAD"), cancellable = true)
    private void nulltrace$disableTelemetry(CallbackInfoReturnable<Boolean> info) {
        if (NullTraceConfig.getInstance().shouldDisableTelemetry()) {
            NullTrace.LOGGER.debug("[NullTrace] Blocking telemetry");
            info.setReturnValue(false);
        }
    }
}
