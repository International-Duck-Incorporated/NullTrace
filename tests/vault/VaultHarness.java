import net.fabricmc.loader.api.FabricLoader;
import net.nulltrace.mod.accounts.AccountTokenVaultAccess;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JDK-only harness for the account token vault (no Minecraft, no Gradle).
 *
 * <p>Exercises the shipped {@code AccountTokenVault} through the thin
 * {@link AccountTokenVaultAccess} bridge so the package-private class can be tested from the
 * default package, exactly like {@code tests/proxy/ProxyProbeHarness}.
 *
 * <p>Covers the properties the vault claims: round-trip, AES-GCM additional authenticated data
 * binding (a token moved onto another account must not decrypt), rejection of tampering, and
 * read compatibility with {@code v1:} values written before the binding existed.
 */
public final class VaultHarness {

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("nulltrace-vault");
        FabricLoader.getInstance().setConfigDir(dir);
        System.out.println("vault dir: " + dir);

        String token = "eyJhbGciOiJIUzI1NiJ9.example-access-token-value";
        String uuidA = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
        String uuidB = "853c80ef-3c37-49fd-aa49-938b674adae6";

        // --- round trip, bound to account A
        String sealed = AccountTokenVaultAccess.encrypt(token, uuidA);
        check("encrypt returns a v2 envelope", sealed != null && sealed.startsWith("v2:"));
        check("no plaintext token inside the envelope", sealed != null && !sealed.contains(token));
        check("decrypts for the owning account",
                token.equals(AccountTokenVaultAccess.decrypt(sealed, uuidA)));

        // --- the swap this binding exists to stop
        check("a token moved to another account does not decrypt",
                AccountTokenVaultAccess.decrypt(sealed, uuidB) == null);
        check("a token moved with no candidates does not decrypt",
                AccountTokenVaultAccess.decrypt(sealed) == null);

        // --- every encryption is independently randomised
        String sealedAgain = AccountTokenVaultAccess.encrypt(token, uuidA);
        check("IV is fresh per encryption", !sealed.equals(sealedAgain));
        check("both envelopes decrypt", token.equals(AccountTokenVaultAccess.decrypt(sealedAgain, uuidA)));

        // --- usernames work as the binding for entries that have no UUID yet
        String sealedByUsername = AccountTokenVaultAccess.encrypt(token, "Notch");
        check("username binding round-trips",
                token.equals(AccountTokenVaultAccess.decrypt(sealedByUsername, "Notch")));
        check("username binding rejects the wrong name",
                AccountTokenVaultAccess.decrypt(sealedByUsername, "Herobrine") == null);

        // --- tampering
        char[] tampered = sealed.toCharArray();
        int lastPayload = sealed.lastIndexOf(':') + 2;
        tampered[lastPayload] = tampered[lastPayload] == 'A' ? 'B' : 'A';
        check("flipped ciphertext byte is rejected",
                AccountTokenVaultAccess.decrypt(new String(tampered), uuidA) == null);
        check("truncated envelope is rejected",
                AccountTokenVaultAccess.decrypt("v2:deadbeef", uuidA) == null);
        check("unknown prefix is rejected",
                AccountTokenVaultAccess.decrypt("v9:AAAA:BBBB", uuidA) == null);
        check("null is rejected", AccountTokenVaultAccess.decrypt(null, uuidA) == null);

        // --- migration: a v1 value (written before AAD existed) still loads
        String legacy = AccountTokenVaultAccess.encrypt(token, null);
        check("legacy envelope is v1", legacy != null && legacy.startsWith("v1:"));
        check("legacy envelope still decrypts without a binding",
                token.equals(AccountTokenVaultAccess.decrypt(legacy, uuidA)));
        check("legacy envelope does not claim a binding",
                AccountTokenVaultAccess.decrypt(legacy, uuidB) != null);

        // --- alphabet regression: URL-safe base64 output must decode
        // Encryption emits the URL-safe alphabet ('-' and '_'); decoding it with the standard
        // decoder throws for ~98% of ciphertexts. This pins an envelope that actually contains
        // one of those characters.
        String withUrlSafeChar = null;
        for (int i = 0; i < 200 && withUrlSafeChar == null; i++) {
            String candidate = AccountTokenVaultAccess.encrypt(token, uuidA);
            if (candidate != null && (candidate.contains("-") || candidate.contains("_"))) {
                withUrlSafeChar = candidate;
            }
        }
        check("an envelope using the URL-safe alphabet is produced", withUrlSafeChar != null);
        if (withUrlSafeChar != null) {
            check("such an envelope decrypts (regression: URL vs standard base64)",
                    token.equals(AccountTokenVaultAccess.decrypt(withUrlSafeChar, uuidA)));
        }
        // A standard-alphabet segment is still accepted as a fallback.
        String standardIv = java.util.Base64.getEncoder().encodeToString(new byte[12]);
        String standardCt = java.util.Base64.getEncoder().encodeToString(new byte[16]);
        check("a standard-alphabet envelope is rejected cleanly, not with a crash",
                AccountTokenVaultAccess.decrypt("v2:" + standardIv + ":" + standardCt, uuidA) == null);

        // --- key file handling
        Path keyFile = dir.resolve("nulltrace-account.key");
        check("key file was created", Files.exists(keyFile));
        byte[] key = Files.readAllBytes(keyFile);
        check("key is 32 bytes", key.length == 32);
        check("key file does not contain the token",
                !new String(key, java.nio.charset.StandardCharsets.ISO_8859_1).contains(token));

        // --- blank input is refused rather than stored
        check("blank token returns null", AccountTokenVaultAccess.encrypt("   ", uuidA) == null);

        // --- an unreadable key file is regenerated, and old envelopes then fail closed
        Files.write(keyFile, new byte[]{1, 2, 3});
        AccountTokenVaultAccess.resetKeyCache();
        String resealed = AccountTokenVaultAccess.encrypt(token, uuidA);
        check("a corrupt key file is regenerated", resealed != null && resealed.startsWith("v2:"));
        check("a corrupt key file is replaced with 32 fresh bytes",
                Files.readAllBytes(keyFile).length == 32);
        check("values sealed under the old key do not decrypt under the new one",
                AccountTokenVaultAccess.decrypt(sealed, uuidA) == null);

        System.out.printf("%n%d checks, %d failures%n", checks, failures);
        if (failures > 0) System.exit(1);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) failures++;
        System.out.printf("  %s %s%n", ok ? "PASS" : "FAIL", what);
    }
}
