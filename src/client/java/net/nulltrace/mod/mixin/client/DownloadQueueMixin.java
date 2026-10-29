package net.nulltrace.mod.mixin.client;

//? if >=1.20.3 {
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.protection.PackCacheIsolation;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.DownloadQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Isolates resource pack cache per-account to prevent cross-account fingerprinting.
 *
 * <p>This prevents servers from tracking users across accounts by storing downloaded
 * resource packs in account-specific subdirectories instead of a shared cache.</p>
 *
 * <p>Adapted from <a href="https://github.com/CCBlueX/LiquidBounce">LiquidBounce</a></p>
 * Copyright (c) 2015 - 2025 CCBlueX
 *
 * @author Izuna
 * @see <a href="https://github.com/CCBlueX/LiquidBounce/blob/nextgen/src/main/java/net/ccbluex/liquidbounce/injection/mixins/minecraft/util/MixinDownloadQueue.java">MixinDownloadQueue.java</a>
 */
@Mixin(DownloadQueue.class)
public class DownloadQueueMixin {
    @Shadow
    @Final
    private Path cacheDir;

    @SuppressWarnings("UnresolvedMixinReference")
    @ModifyExpressionValue(
        method = {"lambda$runDownload$0", "method_55485"},
        at = @At(value = "INVOKE", target = "Ljava/nio/file/Path;resolve(Ljava/lang/String;)Ljava/nio/file/Path;"),
        require = 0)
    private Path nulltrace$isolatePackPath(Path original, @Local(argsOnly = true) UUID packId) {
        if (!NullTraceConfig.getInstance().shouldIsolatePackCache()) {
            return original;
        }

        // Check if path has already been modified by another mod (e.g., LiquidBounce, Meteor)
        if (!original.getParent().equals(cacheDir)) {
            return original;
        }

        UUID accountId = Minecraft.getInstance().getUser().getProfileId();
        if (accountId == null) {
            NullTrace.LOGGER.warn("[NullTrace] Failed to isolate resource pack cache - account UUID is null");
            return original;
        }

        // Liveness signal: PackCacheIsolation checks this counter 30 s after the first server
        // pack push and reports a WARN if the injection never bound (require = 0 hides a miss).
        PackCacheIsolation.recordIsolation();

        Path isolatedPath = cacheDir.resolve(accountId.toString()).resolve(packId.toString());
        NullTrace.LOGGER.debug("[NullTrace] Isolating resource pack cache for account {} -> {}", accountId, isolatedPath);
        return isolatedPath;
    }
}
//?} else {
/*
import net.minecraft.network.protocol.PacketUtils;
import org.spongepowered.asm.mixin.Mixin;

// 1.20.1/1.20.2: DownloadQueue class doesn't exist (introduced 1.20.3 with the
// multi-pack rewrite). Per-account isolated pack cache on this era is handled
// by HttpUtilMixin path redirection in the legacy server-resource-packs flow.
@Mixin(PacketUtils.class)
public class DownloadQueueMixin {
}
*///?}
