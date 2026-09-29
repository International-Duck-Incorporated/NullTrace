package net.nulltrace.mod.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.config.SpoofSettings;
import net.nulltrace.mod.protection.NullTraceFromPacketAccess;
import net.nulltrace.mod.protection.TranslationProtectionHandler;
import net.nulltrace.mod.protection.TranslationProtectionHandler.InterceptionType;
import net.nulltrace.mod.tracking.ModRegistry;
import net.nulltrace.mod.privacy.PrivacyAction;
import net.nulltrace.mod.privacy.PrivacyAudit;
import net.nulltrace.mod.privacy.PrivacySurface;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

/**
 * Intercepts TranslatableContents to block mod translation resolution at the call site.
 *
 * Uses @WrapOperation on Language.getOrDefault() calls inside decompose() rather than
 * intercepting at the callee (ClientLanguage). This ensures protection fires regardless
 * of which Language implementation is active.
 *
 * Behavior by mode:
 * - VANILLA: Block all non-vanilla, non-resourcepack keys
 * - FABRIC: Block non-vanilla, non-resourcepack, non-whitelisted keys
 */
@Mixin(TranslatableContents.class)
public abstract class TranslatableContentsMixin implements NullTraceFromPacketAccess {

    @Shadow @Final private String key;
    @Shadow @Final private String fallback;

    @Unique
    private boolean nulltrace$fromPacket = false;

    @Unique
    private boolean nulltrace$reported = false;

    @Unique
    private boolean nulltrace$silent = false;

    @Override
    public void nulltrace$setFromPacket() {
        this.nulltrace$fromPacket = true;
    }

    @Override
    public void nulltrace$setSilent() {
        this.nulltrace$silent = true;
    }

    /** Sentinel value indicating the original call should proceed. */
    @Unique
    private static final String NULLTRACE_ALLOW_ORIGINAL = "\0__nulltrace_allow__";

    /**
     * Wrap the single-arg Language.getOrDefault(String) call inside decompose().
     * This is called when fallback is null (vanilla behavior falls back to the key itself).
     */
    @WrapOperation(
        method = "decompose",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/locale/Language;getOrDefault(Ljava/lang/String;)Ljava/lang/String;")
    )
    private String nulltrace$wrapGetOrDefault(Language instance, String id, Operation<String> original) {
        String result = nulltrace$handleTranslationLookup(id, id);
        if (result == NULLTRACE_ALLOW_ORIGINAL) {
            return original.call(instance, id);
        }
        return result;
    }

    /**
     * Wrap the two-arg Language.getOrDefault(String, String) call inside decompose().
     * This is called when fallback is non-null.
     */
    @WrapOperation(
        method = "decompose",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/locale/Language;getOrDefault(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
    )
    private String nulltrace$wrapGetOrDefaultWithFallback(Language instance, String keyArg, String defaultValue, Operation<String> original) {
        String result = nulltrace$handleTranslationLookup(keyArg, defaultValue);
        if (result == NULLTRACE_ALLOW_ORIGINAL) {
            return original.call(instance, keyArg, defaultValue);
        }
        return result;
    }

    /**
     * Shared handler for translation lookup interception.
     * Returns either a replacement value (blocked/fabricated) or the sentinel
     * {@link #NULLTRACE_ALLOW_ORIGINAL} to indicate the original call should proceed.
     *
     * @param translationKey the translation key being looked up
     * @param defaultValue   the fallback value if blocked (key itself for single-arg, fallback for two-arg)
     * @return replacement value, or {@link #NULLTRACE_ALLOW_ORIGINAL} to call through
     */
    @Unique
    private String nulltrace$handleTranslationLookup(String translationKey, String defaultValue) {
        // Not from a packet or in singleplayer — allow normal resolution
        if (!this.nulltrace$fromPacket || Minecraft.getInstance().hasSingleplayerServer()) {
            return NULLTRACE_ALLOW_ORIGINAL;
        }

        // In exploit context — always notify header (cooldown prevents spam)
        TranslationProtectionHandler.notifyExploitDetected();

        if (ModRegistry.isVanillaTranslationKey(translationKey)) {
            nulltrace$reportPassthrough(translationKey, defaultValue);
            return NULLTRACE_ALLOW_ORIGINAL;
        }

        NullTraceConfig config = NullTraceConfig.getInstance();
        SpoofSettings settings = config.getSettings();

        // If protection is disabled, still log but allow resolution
        if (!config.isTranslationProtectionEnabled()) {
            PrivacyAudit.record(PrivacySurface.TRANSLATION_PROBE, translationKey,
                    PrivacyAction.ALLOW, "Translation protection is disabled");
            String realValue = nulltrace$getRealTranslation(translationKey, defaultValue);
            TranslationProtectionHandler.sendDetailDebug(InterceptionType.TRANSLATION, translationKey, realValue, realValue);
            TranslationProtectionHandler.logDetection(InterceptionType.TRANSLATION, translationKey, realValue, realValue);
            return NULLTRACE_ALLOW_ORIGINAL;
        }

        // VANILLA MODE: Block all mod keys
        if (settings.isVanillaMode()) {
            String blockedValue = nulltrace$getBlockedValue(translationKey, defaultValue);
            nulltrace$logBlocked(translationKey, blockedValue);
            return blockedValue;
        }

        // FABRIC MODE: Allow whitelisted mod keys, block others
        if (settings.isFabricMode()) {
            if (ModRegistry.isWhitelistedTranslationKey(translationKey)) {
                nulltrace$reportPassthrough(translationKey, defaultValue);
                return NULLTRACE_ALLOW_ORIGINAL;
            }
            String blockedValue = nulltrace$getBlockedValue(translationKey, defaultValue);
            nulltrace$logBlocked(translationKey, blockedValue);
            return blockedValue;
        }

        // Fallback: Use whitelist behavior
        if (ModRegistry.isWhitelistedTranslationKey(translationKey)) {
            nulltrace$reportPassthrough(translationKey, defaultValue);
            return NULLTRACE_ALLOW_ORIGINAL;
        }
        String blockedValue = nulltrace$getBlockedValue(translationKey, defaultValue);
        nulltrace$logBlocked(translationKey, blockedValue);
        return blockedValue;
    }

    /**
     * Log detection when a mod translation key is blocked.
     * Gets the real translation value by directly accessing storage for accurate logging.
     */
    @Unique
    private void nulltrace$logBlocked(String translationKey, String defaultValue) {
        PrivacyAudit.record(PrivacySurface.TRANSLATION_PROBE, translationKey,
                PrivacyAction.BLOCK, "Non-vanilla translation lookup normalized");
        if (nulltrace$silent || nulltrace$reported) return;
        nulltrace$reported = true;
        String originalValue = nulltrace$getRealTranslation(translationKey, defaultValue);

        if (!originalValue.equals(defaultValue)) {
            TranslationProtectionHandler.sendDetail(InterceptionType.TRANSLATION, translationKey, originalValue, defaultValue);
        } else {
            TranslationProtectionHandler.sendDetailDebug(InterceptionType.TRANSLATION, translationKey, originalValue, defaultValue);
        }
        TranslationProtectionHandler.logDetection(InterceptionType.TRANSLATION, translationKey, originalValue, defaultValue);
    }

    /** Pack-defined value when present (independent of storage merge order), else {@code defaultValue}. */
    @Unique
    private String nulltrace$getBlockedValue(String translationKey, String defaultValue) {
        String packValue = ModRegistry.getServerPackTranslation(translationKey);
        return packValue != null ? packValue : defaultValue;
    }

    /** Report a vanilla/whitelisted passthrough — chat detail in debug mode, console log when enabled. Both handlers self-gate. */
    @Unique
    private void nulltrace$reportPassthrough(String translationKey, String defaultValue) {
        if (!NullTraceConfig.getInstance().isDebugAlerts() && !NullTraceConfig.getInstance().isLogDetections()) return;
        String realValue = nulltrace$getRealTranslation(translationKey, defaultValue);
        TranslationProtectionHandler.sendDetailDebug(InterceptionType.TRANSLATION, translationKey, realValue, realValue);
        TranslationProtectionHandler.logDetection(InterceptionType.TRANSLATION, translationKey, realValue, realValue);
    }

    /**
     * Get the real translation value by directly accessing ClientLanguage's storage map.
     * Uses {@link ClientLanguageAccessor} to bypass our interception.
     */
    @Unique
    private String nulltrace$getRealTranslation(String translationKey, String defaultValue) {
        try {
            Language lang = Language.getInstance();
            if (lang instanceof ClientLanguageAccessor accessor) {
                Map<String, String> storage = accessor.nulltrace$getStorage();
                String value = storage.get(translationKey);
                return value != null ? value : defaultValue;
            }
        } catch (Exception e) {
            NullTrace.LOGGER.debug("[NullTrace] Failed to get real translation for key '{}': {}",
                    translationKey, e.getMessage());
        }
        return defaultValue;
    }
}
