package net.nulltrace.mod.mixin.client;

import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.config.NullTraceConfig;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.Proxy;

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

    /**
     * Minecraft receives its proxy from the launcher. Keep direct connections as
     * the default, but expose an opt-in setting for users who have configured a
     * proxy in the launcher/client. Returning NO_PROXY here also covers the
     * multiplayer connection and services that use Minecraft#getProxy().
     */
    @Inject(method = "getProxy", at = @At("HEAD"), cancellable = true)
    private void nulltrace$selectProxy(CallbackInfoReturnable<Proxy> info) {
        if (!NullTraceConfig.getInstance().getSettings().isUseProxy()) {
            info.setReturnValue(Proxy.NO_PROXY);
        }
    }
}
