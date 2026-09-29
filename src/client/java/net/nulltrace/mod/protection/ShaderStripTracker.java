package net.nulltrace.mod.protection;

import net.nulltrace.mod.PrivacyLogger;
import net.nulltrace.mod.config.NullTraceConstants;
import net.nulltrace.mod.lang.NullTraceLang;
import net.nulltrace.mod.lang.NullTraceStrings;
import net.minecraft.client.Minecraft;
import net.nulltrace.mod.privacy.PrivacyAction;
import net.nulltrace.mod.privacy.PrivacyAudit;
import net.nulltrace.mod.privacy.PrivacySurface;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accumulates the shader overrides NullTrace strips from a server pack and announces them once the
 * player is in-world — the strip runs during the config-phase reload, before {@code client.player}
 * exists. One pack's namespaces land in a single flush, merged into one chat line + one toast.
 */
public final class ShaderStripTracker {
    private ShaderStripTracker() {}

    /** namespace → stripped shader paths pending announcement. */
    private static final Map<String, Set<String>> pending = new ConcurrentHashMap<>();
    /** Namespaces already announced — no re-spam across reloads. */
    private static final Set<String> announced = ConcurrentHashMap.newKeySet();

    private static final int MAX_TRACKED = 64; // cap per namespace vs. a pathological pack

    public static void onStripped(String namespace, String path) {
        PrivacyAudit.record(PrivacySurface.SHADER_OVERRIDE, namespace + ":" + path,
                PrivacyAction.BLOCK, "Server-pack shader override stripped");
        if (announced.contains(namespace)) return;
        Set<String> paths = pending.computeIfAbsent(namespace, k -> ConcurrentHashMap.newKeySet());
        if (paths.size() < MAX_TRACKED) paths.add(path);
    }

    public static void flushPending() {
        if (pending.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        List<Map.Entry<String, Set<String>>> batch = new ArrayList<>();
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Set<String>> e = it.next();
            it.remove();
            if (announced.add(e.getKey())) {
                batch.add(e);
            }
        }
        if (!batch.isEmpty()) {
            emit(batch);
        }
    }

    private static void emit(List<Map.Entry<String, Set<String>>> batch) {
        List<String> mods = new ArrayList<>(batch.size());
        for (Map.Entry<String, Set<String>> e : batch) {
            mods.add(e.getKey());
            PrivacyLogger.logDetection("ShaderStrip",
                "Stripped " + e.getValue().size() + " shader override(s) for '" + e.getKey()
                    + "': " + new TreeSet<>(e.getValue()));
        }
        mods.sort(null);

        if (mods.size() == 1) {
            PrivacyLogger.alert(PrivacyLogger.AlertType.DANGER,
                NullTraceLang.tr(NullTraceStrings.ALERT_SHADER_STRIP, batch.get(0).getValue().size(), mods.get(0)));
        } else {
            PrivacyLogger.alert(PrivacyLogger.AlertType.DANGER,
                NullTraceLang.tr(NullTraceStrings.ALERT_SHADER_STRIP_MULTI, mods.size(), String.join(", ", mods)));
        }
        // Shared key → one toast even when several mods strip in one flush; also rate-limits re-strips.
        PrivacyLogger.toastWithCooldown(PrivacyLogger.AlertType.DANGER,
            NullTraceLang.tr(NullTraceStrings.TOAST_SHADER_STRIP),
            "shader_strip_toast", NullTraceConstants.Timeouts.EXPLOIT_TOAST_COOLDOWN_MS);
    }

    public static void clear() {
        pending.clear();
        announced.clear();
    }
}
