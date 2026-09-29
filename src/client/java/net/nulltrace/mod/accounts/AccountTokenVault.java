package net.nulltrace.mod.accounts;

import net.nulltrace.mod.NullTrace;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * Small local encrypted vault for session tokens.
 *
 * <p>The account JSON never receives a plaintext bearer token. The key is
 * generated once, stored separately with owner-only permissions where the
 * platform supports them, and used with AES-GCM. This is protection at rest,
 * not a claim that a local administrator or malware cannot read a live client.
 * Export deliberately strips tokens instead of exporting an unusable secret.
 */
final class AccountTokenVault {
    private static final String PREFIX = "v1:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Path KEY_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("nulltrace-account.key");
    private static volatile byte[] key;

    private AccountTokenVault() {}

    static String encrypt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(loadKey(), "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return PREFIX + encode(iv) + ":" + encode(ciphertext);
        } catch (Exception e) {
            NullTrace.LOGGER.error("[NullTrace] Could not encrypt account token; refusing plaintext persistence: {}", e.getMessage());
            return null;
        }
    }

    static String decrypt(String value) {
        if (value == null || !value.startsWith(PREFIX)) return null;
        try {
            String[] parts = value.split(":", 3);
            if (parts.length != 3) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(loadKey(), "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            NullTrace.LOGGER.warn("[NullTrace] Could not decrypt a saved account token; re-authentication is required");
            return null;
        }
    }

    private static byte[] loadKey() throws Exception {
        byte[] existing = key;
        if (existing != null) return existing.clone();
        synchronized (AccountTokenVault.class) {
            if (key != null) return key.clone();
            Files.createDirectories(KEY_PATH.getParent());
            if (Files.exists(KEY_PATH)) {
                byte[] loaded = Files.readAllBytes(KEY_PATH);
                if (loaded.length == KEY_BYTES) {
                    key = loaded;
                    restrict(KEY_PATH);
                    return key.clone();
                }
                NullTrace.LOGGER.warn("[NullTrace] Invalid account vault key; generating a new key and requiring re-authentication");
            }
            byte[] generated = new byte[KEY_BYTES];
            RANDOM.nextBytes(generated);
            Files.write(KEY_PATH, generated, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            restrict(KEY_PATH);
            key = generated;
            return generated.clone();
        }
    }

    private static void restrict(Path path) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            path.toFile().setReadable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(false, false);
            path.toFile().setWritable(true, true);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
