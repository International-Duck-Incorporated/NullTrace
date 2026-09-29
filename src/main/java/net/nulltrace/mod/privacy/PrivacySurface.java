package net.nulltrace.mod.privacy;

/**
 * A surface through which a remote server can learn something about the client.
 * Keeping this list finite makes audit output stable and gives every protection
 * a common vocabulary.
 */
public enum PrivacySurface {
    CLIENT_BRAND,
    MOD_CHANNEL,
    KNOWN_PACK,
    TRANSLATION_PROBE,
    KEYBIND_PROBE,
    RESOURCE_PACK,
    LOCAL_REQUEST,
    SHADER_OVERRIDE,
    COMPONENT_PROBE,
    CLIENT_INFORMATION,
    SERVER_COOKIE,
    SERVER_TRANSFER,
    PROTOCOL_BEHAVIOR
}
