package net.nulltrace.mod.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The proxy configuration: which proxy path Minecraft's HTTP layers should take, and
 * the saved list the user picks from.
 *
 * <p>Persisted as a top-level {@code "proxy"} object in the NullTrace config file, next to
 * {@code "settings"} and {@code "serverProfiles"} — deliberately outside {@code SpoofSettings}
 * so the settings reset button cannot wipe a list of endpoints the user pasted in by hand.
 *
 * <p>What this affects, precisely: {@link net.minecraft.client.Minecraft#getProxy()}, which
 * Minecraft consults for its auxiliary HTTP traffic (authentication, skins, resource-pack
 * downloads, telemetry, NullTrace's own update and integrity checks). It does <b>not</b>
 * change the multiplayer TCP connection — vanilla never consults the proxy for the
 * game-server connection, so no client-side setting can hide a player's IP from a server.
 * See {@code docs/design/proxy-management.md}.
 */
public final class ProxySettings {

    /** Where the proxy comes from. */
    public enum Mode {
        /** Ignore any launcher-supplied proxy and connect directly. Default. */
        DIRECT,
        /** Use whatever proxy the launcher configured (previous behaviour of "Play through proxy"). */
        LAUNCHER,
        /** Use the entry selected in the saved list. */
        SELECTED
    }

    private Mode mode = Mode.DIRECT;
    private String selectedId = "";
    private final List<ProxyEntry> entries = new ArrayList<>();

    public Mode mode() { return mode; }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.DIRECT : mode;
    }

    /** Saved entries, in display order. */
    public List<ProxyEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public String selectedId() { return selectedId; }

    public ProxyEntry selected() {
        for (ProxyEntry entry : entries) {
            if (entry.id().equals(selectedId)) return entry;
        }
        return null;
    }

    public void select(ProxyEntry entry) {
        this.selectedId = entry == null ? "" : entry.id();
    }

    /**
     * Adds an entry and selects it. Returns false when an identical endpoint is already
     * in the list (the existing entry is selected instead, so adding twice is harmless).
     */
    public boolean add(ProxyEntry entry) {
        if (entry == null || !entry.isValid()) return false;
        for (ProxyEntry existing : entries) {
            if (existing.equals(entry)) {
                select(existing);
                return false;
            }
        }
        entries.add(entry);
        select(entry);
        return true;
    }

    public boolean remove(ProxyEntry entry) {
        if (entry == null) return false;
        boolean removed = entries.remove(entry);
        if (removed && entry.id().equals(selectedId)) {
            selectedId = entries.isEmpty() ? "" : entries.get(0).id();
        }
        return removed;
    }

    /** Moves an entry by {@code delta} positions, clamped to the list. */
    public void move(ProxyEntry entry, int delta) {
        int index = entries.indexOf(entry);
        if (index < 0 || delta == 0) return;
        int target = Math.max(0, Math.min(entries.size() - 1, index + delta));
        if (target == index) return;
        entries.remove(index);
        entries.add(target, entry);
    }

    public boolean hasEntry(ProxyEntry entry) {
        return entries.contains(entry);
    }

    /**
     * The proxy Minecraft should use, or {@code null} when Minecraft's own value
     * (launcher-supplied) should be left alone.
     */
    public Proxy activeProxy() {
        return switch (mode) {
            case LAUNCHER -> null;
            case DIRECT -> Proxy.NO_PROXY;
            case SELECTED -> {
                ProxyEntry entry = selected();
                // Selection points at nothing (deleted entry, hand-edited config):
                // fail closed to a direct connection rather than sending traffic nowhere.
                yield entry == null ? Proxy.NO_PROXY : entry.toProxy();
            }
        };
    }

    /** True when the mode expects a selected entry but there is none. */
    public boolean selectionMissing() {
        return mode == Mode.SELECTED && selected() == null;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("mode", mode.name());
        json.addProperty("selectedId", selectedId);
        JsonArray array = new JsonArray();
        for (ProxyEntry entry : entries) {
            array.add(entry.toJson());
        }
        json.add("entries", array);
        return json;
    }

    public static ProxySettings fromJson(JsonObject json) {
        ProxySettings settings = new ProxySettings();
        if (json == null) return settings;

        if (json.has("mode")) {
            try {
                settings.mode = Mode.valueOf(json.get("mode").getAsString().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                settings.mode = Mode.DIRECT;
            }
        }
        if (json.has("entries") && json.get("entries").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("entries")) {
                if (!element.isJsonObject()) continue;
                ProxyEntry entry = ProxyEntry.fromJson(element.getAsJsonObject());
                if (entry != null && !settings.entries.contains(entry)) {
                    settings.entries.add(entry);
                }
            }
        }
        if (json.has("selectedId")) {
            settings.selectedId = json.get("selectedId").getAsString();
        }
        return settings;
    }
}
