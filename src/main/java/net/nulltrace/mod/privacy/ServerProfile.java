package net.nulltrace.mod.privacy;

import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;

/** A per-server policy profile. */
public final class ServerProfile {
    public enum Mode { STANDARD, STRICT, MAXIMUM_PRIVACY, CUSTOM }

    private final String server;
    private Mode mode;
    private final Set<String> allowedChannels = new LinkedHashSet<>();
    private boolean normalizeClientInformation;
    private boolean blockLocalRequests;
    private boolean blockServerCookies;
    private boolean blockServerTransfers;

    public ServerProfile(String server) {
        this.server = server == null || server.isBlank() ? "default" : server;
        this.mode = Mode.STANDARD;
        this.normalizeClientInformation = false;
        this.blockLocalRequests = false;
        this.blockServerCookies = false;
        this.blockServerTransfers = false;
    }

    public String server() { return server; }
    public Mode mode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode == null ? Mode.STANDARD : mode; }
    public Set<String> allowedChannels() { return Set.copyOf(allowedChannels); }
    public void setAllowedChannels(Iterable<String> channels) {
        allowedChannels.clear();
        if (channels != null) for (String channel : channels) {
            if (channel != null && !channel.isBlank()) allowedChannels.add(channel);
        }
    }
    public boolean normalizeClientInformation() { return normalizeClientInformation; }
    public void setNormalizeClientInformation(boolean value) { normalizeClientInformation = value; }
    public boolean blockLocalRequests() { return blockLocalRequests; }
    public void setBlockLocalRequests(boolean value) { blockLocalRequests = value; }
    public boolean blockServerCookies() { return blockServerCookies; }
    public void setBlockServerCookies(boolean value) { blockServerCookies = value; }
    public boolean blockServerTransfers() { return blockServerTransfers; }
    public void setBlockServerTransfers(boolean value) { blockServerTransfers = value; }

    public PrivacyPolicy policy(PrivacyPolicy fallback) {
        if (mode == Mode.STANDARD) return fallback;
        PrivacyPolicy.Builder builder = PrivacyPolicy.builder();
        switch (mode) {
            case STRICT -> {
                builder.action(PrivacySurface.CLIENT_BRAND, PrivacyAction.BLOCK)
                        .action(PrivacySurface.MOD_CHANNEL, PrivacyAction.BLOCK)
                        .action(PrivacySurface.KNOWN_PACK, PrivacyAction.BLOCK)
                        .action(PrivacySurface.TRANSLATION_PROBE, PrivacyAction.BLOCK)
                        .action(PrivacySurface.KEYBIND_PROBE, PrivacyAction.BLOCK)
                        .action(PrivacySurface.RESOURCE_PACK, PrivacyAction.WARN)
                        .action(PrivacySurface.LOCAL_REQUEST, PrivacyAction.BLOCK)
                        .action(PrivacySurface.SHADER_OVERRIDE, PrivacyAction.BLOCK)
                        .action(PrivacySurface.COMPONENT_PROBE, PrivacyAction.BLOCK)
                        .action(PrivacySurface.CLIENT_INFORMATION, PrivacyAction.BLOCK)
                        .action(PrivacySurface.SERVER_COOKIE, PrivacyAction.BLOCK)
                        .action(PrivacySurface.SERVER_TRANSFER, PrivacyAction.BLOCK)
                        .action(PrivacySurface.PROTOCOL_BEHAVIOR, PrivacyAction.BLOCK);
            }
            case MAXIMUM_PRIVACY -> {
                builder.maximumPrivacy(true);
                for (PrivacySurface surface : PrivacySurface.values()) {
                    builder.action(surface, PrivacyAction.BLOCK);
                }
            }
            case CUSTOM -> {
                for (PrivacySurface surface : PrivacySurface.values()) {
                    builder.action(surface, PrivacyAction.LOG);
                }
                builder.action(PrivacySurface.CLIENT_INFORMATION,
                        normalizeClientInformation ? PrivacyAction.BLOCK : PrivacyAction.ALLOW);
                builder.action(PrivacySurface.LOCAL_REQUEST,
                        blockLocalRequests ? PrivacyAction.BLOCK : PrivacyAction.WARN);
                builder.action(PrivacySurface.SERVER_COOKIE,
                        blockServerCookies ? PrivacyAction.BLOCK : PrivacyAction.ALLOW);
                builder.action(PrivacySurface.SERVER_TRANSFER,
                        blockServerTransfers ? PrivacyAction.BLOCK : PrivacyAction.ALLOW);
                builder.allowChannels(allowedChannels);
            }
            default -> { return fallback; }
        }
        return builder.allowChannels(allowedChannels).build();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("mode", mode.name());
        json.addProperty("normalizeClientInformation", normalizeClientInformation);
        json.addProperty("blockLocalRequests", blockLocalRequests);
        json.addProperty("blockServerCookies", blockServerCookies);
        json.addProperty("blockServerTransfers", blockServerTransfers);
        var channels = new com.google.gson.JsonArray();
        allowedChannels.forEach(channels::add);
        json.add("allowedChannels", channels);
        return json;
    }

    public static ServerProfile fromJson(String server, JsonObject json) {
        ServerProfile profile = new ServerProfile(server);
        if (json == null) return profile;
        if (json.has("mode")) {
            try { profile.mode = Mode.valueOf(json.get("mode").getAsString()); }
            catch (IllegalArgumentException ignored) { profile.mode = Mode.STANDARD; }
        }
        if (json.has("normalizeClientInformation")) profile.normalizeClientInformation = json.get("normalizeClientInformation").getAsBoolean();
        if (json.has("blockLocalRequests")) profile.blockLocalRequests = json.get("blockLocalRequests").getAsBoolean();
        if (json.has("blockServerCookies")) profile.blockServerCookies = json.get("blockServerCookies").getAsBoolean();
        if (json.has("blockServerTransfers")) profile.blockServerTransfers = json.get("blockServerTransfers").getAsBoolean();
        if (json.has("allowedChannels") && json.get("allowedChannels").isJsonArray()) {
            for (var element : json.getAsJsonArray("allowedChannels")) {
                if (element.isJsonPrimitive()) profile.allowedChannels.add(element.getAsString());
            }
        }
        return profile;
    }
}
