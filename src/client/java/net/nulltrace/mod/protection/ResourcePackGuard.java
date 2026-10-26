package net.nulltrace.mod.protection;

import net.nulltrace.mod.NullTrace;
//? if >=1.20.5 {
import net.nulltrace.mod.mixin.client.DownloadedPackSourceAccessor;
import net.nulltrace.mod.mixin.client.MinecraftAccessor;
//?}
import net.minecraft.client.Minecraft;
//? if >=1.20.3 {
import net.minecraft.client.resources.server.DownloadedPackSource;
//?} else {
/*import net.minecraft.client.resources.DownloadedPackSource;
*///?}

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Manages resource pack cache and provides utilities for cache clearing.
 */
public class ResourcePackGuard {

    /**
     * Clear all resource pack caches for all accounts.
     * Clears both 'downloads' and 'server-resource-packs' folders,
     * and resets the in-memory download state.
     */
    public static void clearAllCaches() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
        int totalDeleted = 0;
        
        // Clear downloads folder (new cache location)
        totalDeleted += clearCacheFolder(gameDir, "downloads");
        
        // Clear server-resource-packs folder (legacy/alternative cache location)
        totalDeleted += clearCacheFolder(gameDir, "server-resource-packs");
        
        // Try to reset the in-memory download state
        boolean memoryCleared = clearDownloadQueueState();

        NullTrace.LOGGER.info("[NullTrace] Clear pack cache: {} files removed, memoryCleared={}", totalDeleted, memoryCleared);
    }
    
    /**
     * Attempts to clear the in-memory download queue state.
     * This simulates what happens when disconnecting from a server.
     * @return true if successfully cleared, false otherwise
     */
    private static boolean clearDownloadQueueState() {
        //? if >=1.20.5 {
        try {
            Minecraft mc = Minecraft.getInstance();
            DownloadedPackSource packSource = ((MinecraftAccessor) mc).nulltrace$getDownloadedPackSource();

            if (packSource != null) {
                ((DownloadedPackSourceAccessor) packSource).nulltrace$invokeCleanupAfterDisconnect();
                NullTrace.LOGGER.debug("[NullTrace] Cleared download queue state via cleanupAfterDisconnect");
                return true;
            }
        } catch (Exception e) {
            NullTrace.LOGGER.debug("[NullTrace] Could not clear download queue state: {}", e.getMessage());
        }
        return false;
        //?} else if >=1.20.3 {
        /*// 1.20.3-1.20.4: cleanupAfterDisconnect is public — call it directly.
        try {
            DownloadedPackSource packSource = Minecraft.getInstance().getDownloadedPackSource();
            if (packSource != null) {
                packSource.cleanupAfterDisconnect();
                NullTrace.LOGGER.debug("[NullTrace] Cleared download queue state via cleanupAfterDisconnect");
                return true;
            }
        } catch (Exception e) {
            NullTrace.LOGGER.debug("[NullTrace] Could not clear download queue state: {}", e.getMessage());
        }
        return false;
        *///?} else {
        /*// 1.20.1 / 1.20.2: legacy DownloadedPackSource exposes public clearServerPack().
        try {
            DownloadedPackSource packSource = Minecraft.getInstance().getDownloadedPackSource();
            if (packSource != null) {
                packSource.clearServerPack();
                NullTrace.LOGGER.debug("[NullTrace] Cleared download queue state via clearServerPack");
                return true;
            }
        } catch (Exception e) {
            NullTrace.LOGGER.debug("[NullTrace] Could not clear download queue state: {}", e.getMessage());
        }
        return false;
        *///?}
    }
    
    /**
     * Helper to clear a specific cache folder.
     * @return number of entries deleted
     */
    private static int clearCacheFolder(Path gameDir, String folderName) {
        Path cachePath = gameDir.resolve(folderName).normalize();

        if (!cachePath.startsWith(gameDir)) {
            NullTrace.LOGGER.error("[NullTrace] Security: {} path outside game directory", folderName);
            return 0;
        }

        if (!Files.exists(cachePath)) {
            return 0;
        }

        int deleted = 0;
        // Files.walk holds directory handles and must be closed; leaking them left the very
        // files this method deletes locked on Windows.
        try (Stream<Path> walk = Files.walk(cachePath)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (p.equals(cachePath)) continue;
                try {
                    if (Files.deleteIfExists(p)) {
                        deleted++;
                    }
                } catch (IOException e) {
                    NullTrace.LOGGER.warn("[NullTrace] Failed to delete cache entry {}: {}", p.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            NullTrace.LOGGER.error("[NullTrace] Failed to walk cache folder {}: {}", folderName, e.getMessage());
        }
        
        return deleted;
    }
}
