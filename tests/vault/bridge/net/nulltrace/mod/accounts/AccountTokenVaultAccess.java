package net.nulltrace.mod.accounts;

/**
 * Test bridge into the package-private vault.
 *
 * <p>Lives in the vault's own package so it can call the package-private API, and in
 * {@code tests/} so it never reaches the mod jar. {@link #resetKeyCache()} clears the
 * production key cache by reflection for the "key file was replaced" case; leaving that hook
 * out of {@code AccountTokenVault} keeps test-only code out of the shipped class.
 */
public final class AccountTokenVaultAccess {
    private AccountTokenVaultAccess() {}

    public static String encrypt(String value, String binding) {
        return AccountTokenVault.encrypt(value, binding);
    }

    public static String decrypt(String value, String... candidates) {
        return AccountTokenVault.decrypt(value, candidates);
    }

    public static void resetKeyCache() {
        try {
            java.lang.reflect.Field field = AccountTokenVault.class.getDeclaredField("key");
            field.setAccessible(true);
            field.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not reset the vault key cache", e);
        }
    }
}
