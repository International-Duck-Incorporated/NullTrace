package net.nulltrace.mod.mixin.client;

import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.proxy.ProxySettings;
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
     * Minecraft receives its proxy from the launcher. NullTrace resolves it from the
     * proxy configuration instead:
     *
     * <ul>
     *   <li>{@link ProxySettings.Mode#DIRECT} - force a direct connection.</li>
     *   <li>{@link ProxySettings.Mode#LAUNCHER} - leave the launcher's value untouched.</li>
     *   <li>{@link ProxySettings.Mode#SELECTED} - use the entry the user picked, or a direct
     *       connection when that entry no longer exists (fail closed, never fail open).</li>
     * </ul>
     *
     * <p>Scope: this is Minecraft's auxiliary HTTP traffic (authentication, skins, resource
     * packs, telemetry, update checks). Vanilla never consults {@code getProxy()} for the
     * game-server connection, so this does not proxy gameplay.
     */
    @Inject(method = "getProxy", at = @At("HEAD"), cancellable = true)
    private void nulltrace$selectProxy(CallbackInfoReturnable<Proxy> info) {
        Proxy proxy = NullTraceConfig.getInstance().proxySettings().activeProxy();
        if (proxy != null) {
            info.setReturnValue(proxy);
        }
    }
}
