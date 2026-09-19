package net.nulltrace.mod.mixin.client;

import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.tracking.ModIdResolver;
import net.nulltrace.mod.tracking.ModRegistry;
//? if >=26.1 {
import net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl;

//?} else {
/*import net.fabricmc.fabric.impl.client.keybinding.KeyBindingRegistryImpl;
*///?}
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to track keybinds registered by mods via Fabric API.
 * Records which mod registered each keybind for whitelist support.
 */
//? if >=26.1 {
@Mixin(value = KeyMappingRegistryImpl.class, remap = false)
//?} else {
/*@Mixin(value = KeyBindingRegistryImpl.class, remap = false)
*///?}
public class KeyBindingRegistryImplMixin {
    
    @Unique
    private static boolean nulltrace$loggedOnce = false;
    
    /**
     * Track mod keybind registration with mod ID.
     */
    //? if >=26.1 {
    @Inject(method = "registerKeyMapping", at = @At("RETURN"))
    //?} else {
    /*@SuppressWarnings("UnresolvedMixinReference")
    @Inject(method = "registerKeyBinding", at = @At("RETURN"))
    *///?}
    private static void nulltrace$onKeybindRegister(KeyMapping keyBinding, CallbackInfoReturnable<KeyMapping> cir) {
        if (keyBinding == null) return;
        
        // Determine which mod is registering this keybind via stack trace
        String modId = ModIdResolver.getModIdFromStacktrace();
        
        if (modId != null) {
            ModRegistry.recordKeybind(modId, keyBinding.getName());
            
            if (!nulltrace$loggedOnce) {
                nulltrace$loggedOnce = true;
                NullTrace.LOGGER.debug("[NullTrace] Mod keybind tracking active");
            }
        } else {
            NullTrace.LOGGER.debug("[NullTrace] Could not determine mod for keybind: {}", keyBinding.getName());
        }
    }
}

