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
 *
 * <p>Values are bound to the account they belong to with {@code v2:} envelopes,
 * which feed the account identity in as AES-GCM additional authenticated data.
 * Without that, anyone able to edit the accounts file could move one account's
 * token blob onto another entry and the decryption would still succeed — the
 * client would silently log in as the wrong account. {@code v1:} values (no AAD)
 * are still readable so existing installations keep working; they are rewritten
 * as {@code v2:} on the next save.
 */
final class AccountTokenVault {
    private static final String PREFIX_V1 = "v1:";
    private static final String PREFIX_V2 = "v2:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Path KEY_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("nulltrace-account.key");
    private static volatile byte[] key;

    private AccountTokenVault() {}

    /**
     * @param binding stable identity of the owning account (its UUID, else its username);
     *                {@code null} or blank falls back to an unbound {@code v1:} envelope
     */
    static String encrypt(String value, String binding) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(loadKey(), "AES"),
                    new GCMParameterSpec(128, iv));
            boolean bound = binding != null && !binding.isBlank();
            if (bound) {
                cipher.updateAAD(binding.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            byte[] ciphertext = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return (bound ? PREFIX_V2 : PREFIX_V1) + encode(iv) + ":" + encode(ciphertext);
        } catch (Exception e) {
            NullTrace.LOGGER.error("[NullTrace] Could not encrypt account token; refusing plaintext persistence: {}", e.getMessage());
            return null;
        }
    }

    /**
     * @param candidates account identities to try as additional authenticated data, most
     *                   specific first (UUID, then username). Ignored for {@code v1:} values.
     */
    static String decrypt(String value, String... candidates) {
        if (value == null) return null;
        boolean bound = value.startsWith(PREFIX_V2);
        if (!bound && !value.startsWith(PREFIX_V1)) return null;
        String[] parts = value.split(":", 3);
        if (parts.length != 3) return null;

        byte[] iv;
        byte[] ciphertext;
        try {
            iv = decodeSegment(parts[1]);
            ciphertext = decodeSegment(parts[2]);
        } catch (IllegalArgumentException e) {
            NullTrace.LOGGER.warn("[NullTrace] Malformed saved account token; re-authentication is required");
            return null;
        }

        if (!bound) {
            return decryptWith(null, iv, ciphertext);
        }
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            String plaintext = decryptWith(candidate, iv, ciphertext);
            if (plaintext != null) return plaintext;
        }
        NullTrace.LOGGER.warn("[NullTrace] Saved account token is not bound to this account; re-authentication is required");
        return null;
    }

    private static String decryptWith(String binding, byte[] iv, byte[] ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(loadKey(), "AES"),
                    new GCMParameterSpec(128, iv));
            if (binding != null) {
                cipher.updateAAD(binding.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
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

    /**
     * Decode a base64 segment written by {@link #encode}.
     *
     * <p>{@code getUrlEncoder()} emits the URL-safe alphabet, where {@code -} and {@code _}
     * replace {@code +} and {@code /}. Decoding that with {@link Base64#getDecoder()} throws for
     * roughly 98% of ciphertexts (any envelope containing one of those two characters), which
     * made nearly every saved session token fail to load with "re-authentication required".
     * The URL decoder is tried first; the standard one stays as a fallback for values written
     * by a build that used it.
     */
    private static byte[] decodeSegment(String segment) {
        try {
            return Base64.getUrlDecoder().decode(segment);
        } catch (IllegalArgumentException urlSafeFailure) {
            return Base64.getDecoder().decode(segment);
        }
    }
}
