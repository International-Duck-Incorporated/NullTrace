package net.fabricmc.loader.api;

import java.nio.file.Path;

/**
 * Compile-time stand-in so the vault can be exercised outside Minecraft.
 *
 * <p>{@code AccountTokenVault} resolves its key file from
 * {@code FabricLoader.getInstance().getConfigDir()} in a static initialiser, which is the only
 * Fabric dependency it has. The harness points that at a temporary directory.
 */
public final class FabricLoader {
    private static final FabricLoader INSTANCE = new FabricLoader();

    private Path configDir = Path.of(System.getProperty("java.io.tmpdir"), "nulltrace-vault-harness");

    private FabricLoader() {}

    public static FabricLoader getInstance() {
        return INSTANCE;
    }

    public Path getConfigDir() {
        return configDir;
    }

    public void setConfigDir(Path dir) {
        this.configDir = dir;
    }
}
