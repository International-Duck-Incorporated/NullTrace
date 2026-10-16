package net.nulltrace.mod.accounts;

import com.google.gson.JsonObject;

/**
 * Common interface for authenticated Microsoft accounts stored by the account switcher.
 *
 * <p>Offline/cracked identities are deliberately not part of this model: the switcher
 * only ever swaps in a real authenticated session.
 */
public interface Account {

    /**
     * Logs into this account by switching the Minecraft session.
     * @return true if login was successful
     */
    boolean login();

    String getUsername();

    String getUuid();

    boolean isValid();

    String getLastError();

    boolean hasValidInfo();

    JsonObject toJson();

    /**
     * Deserializes an Account from JSON. Legacy entries written by older versions with
     * {@code "type": "cracked"} are dropped by returning {@code null}, so importing an old
     * account file cannot resurrect an unauthenticated identity.
     */
    static Account fromJson(JsonObject json) {
        if (json == null) return null;
        String type = json.has("type") ? json.get("type").getAsString() : "session";
        if ("cracked".equalsIgnoreCase(type) || "offline".equalsIgnoreCase(type)) {
            return null;
        }
        return SessionAccount.fromJson(json);
    }
}
