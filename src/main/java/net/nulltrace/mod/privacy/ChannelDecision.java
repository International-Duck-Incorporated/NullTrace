package net.nulltrace.mod.privacy;

import java.util.function.Predicate;

/**
 * The single outbound custom-payload decision.
 *
 * <p>This exists because the rule used to be implemented twice inside
 * {@code ClientConnectionMixin} — once in a Netty outbound handler installed after the
 * encoder, once in a {@code Connection#send} inject — and the two copies had drifted. The
 * ViaVersion identity block lived only in the pipeline handler, so when the handler could not
 * be installed (another mod replaced the {@code encoder} entry), {@code vv:mod_details} was
 * sent again with no warning.
 *
 * <p>Pure JDK code, no Minecraft types: {@code ChannelDecisionTest} covers the matrix.
 */
public final class ChannelDecision {
    private ChannelDecision() {}

    public static final String MINECRAFT = "minecraft";
    public static final String REGISTER = "register";
    public static final String UNREGISTER = "unregister";
    public static final String MCO = "mco";

    /** What should happen to one outbound payload. */
    public enum Action {
        /** Send it unchanged. */
        PASS,
        /** Do not send it. */
        DROP,
        /** Rewrite {@code minecraft:register}/{@code minecraft:unregister} to allowed channels. */
        FILTER_REGISTRATION
    }

    /** Channels ViaFabricPlus/ViaVersion uses to describe the client; never allowed through. */
    public static boolean isViaIdentity(String namespace, String path) {
        if (!"vv".equals(namespace)) return false;
        return "mod_details".equals(path)
                || "app_details".equals(path)
                || "proxy_details".equals(path);
    }

    public static boolean isRegistration(String namespace, String path) {
        return MINECRAFT.equals(namespace) && (REGISTER.equals(path) || UNREGISTER.equals(path));
    }

    public static boolean isBrand(String namespace, String path) {
        return MINECRAFT.equals(namespace) && "brand".equals(path);
    }

    /**
     * Decide what to do with an outbound payload.
     *
     * @param channelSpoofing whether channel filtering is enabled at all
     * @param vanillaMode     {@code SpoofSettings.isVanillaMode()}
     * @param fabricMode      {@code SpoofSettings.isFabricMode()}
     * @param namespace       payload channel namespace
     * @param path            payload channel path
     * @param brandPayload    the payload is a {@code BrandPayload} (already spoofed upstream)
     * @param whitelisted     whitelist predicate for a {@code namespace:path} channel
     */
    public static Action decide(boolean channelSpoofing, boolean vanillaMode, boolean fabricMode,
                                String namespace, String path, boolean brandPayload,
                                Predicate<String> whitelisted) {
        if (!channelSpoofing) return Action.PASS;
        // Brand: the string is already replaced by ClientBrandRetrieverMixin.
        if (brandPayload) return Action.PASS;
        // Identity leak from another mod's protocol layer: blocked in every mode, including
        // AUTO, because whitelisting it is never what the user means.
        if (isViaIdentity(namespace, path)) return Action.DROP;
        if (vanillaMode) return Action.DROP;
        if (!fabricMode) return Action.PASS;
        if (isRegistration(namespace, path)) return Action.FILTER_REGISTRATION;
        if (MINECRAFT.equals(namespace) && MCO.equals(path)) return Action.DROP;
        // Includes minecraft:-namespace channels: a mod masquerading under minecraft:
        // (e.g. fancymenu_packet_bridge) must gate on the whitelist like any other.
        return whitelisted.test(namespace + ":" + path) ? Action.PASS : Action.DROP;
    }

    /** Human-readable reason stored in the audit ledger for a {@link Action#DROP}. */
    public static String dropReason(String namespace, String path, boolean vanillaMode) {
        if (isViaIdentity(namespace, path)) return "Known client-identity channel";
        if (vanillaMode) return "Vanilla profile blocks custom payloads";
        if (MINECRAFT.equals(namespace) && MCO.equals(path)) return "Blocked to match stock Fabric";
        return "Channel is not allowed by the active profile";
    }
}
