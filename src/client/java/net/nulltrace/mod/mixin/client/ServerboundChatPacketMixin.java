package net.nulltrace.mod.mixin.client;

import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.privacy.ChatSigningPolicy;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.time.Instant;
//? if >=26.3 {
/*import java.util.Optional;
*///?}

/**
 * Strips signatures from outbound chat packets when the user has signing
 * mode set to OFF.
 *
 * Minecraft 26.3 changed the record component from a nullable
 * {@code MessageSignature} to {@code Optional<MessageSignature>}, which changes
 * both the shadowed field descriptor and the constructor descriptor, so the
 * two eras need separate branches.
 */
@Mixin(ServerboundChatPacket.class)
public class ServerboundChatPacketMixin {

    //? if >=26.3 {
    /*@Final
    @Mutable
    @Shadow
    private Optional<MessageSignature> signature;

    @Inject(method = "<init>(Ljava/lang/String;Ljava/time/Instant;JLjava/util/Optional;Lnet/minecraft/network/chat/LastSeenMessages$Update;)V", at = @At("TAIL"))
    private void nulltrace$stripSignatureOnInit(String message, Instant timeStamp, long salt,
            Optional<MessageSignature> signature, LastSeenMessages.Update lastSeenMessages, CallbackInfo ci) {
        if (nulltrace$stripSignature()) {
            NullTrace.LOGGER.debug("[NullTrace] signing OFF —stripping chat signature");
            this.signature = Optional.empty();
        }
    }

    @Inject(method = "signature", at = @At("HEAD"), cancellable = true)
    private void nulltrace$stripSignatureOnGet(CallbackInfoReturnable<Optional<MessageSignature>> info) {
        if (nulltrace$stripSignature()) {
            NullTrace.LOGGER.debug("[NullTrace] signing OFF —returning empty signature");
            info.setReturnValue(Optional.empty());
        }
    }
    *///?} else {
    @Final
    @Nullable
    @Mutable
    @Shadow
    private MessageSignature signature;

    /**
     * Strip signature from packet on construction when signing is OFF.
     */
    @Inject(method = "<init>(Ljava/lang/String;Ljava/time/Instant;JLnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/network/chat/LastSeenMessages$Update;)V", at = @At("TAIL"))
    private void nulltrace$stripSignatureOnInit(String message, Instant timeStamp, long salt, 
            MessageSignature signature, LastSeenMessages.Update lastSeenMessages, CallbackInfo ci) {
        if (nulltrace$stripSignature()) {
            NullTrace.LOGGER.debug("[NullTrace] signing OFF —stripping chat signature");
            this.signature = null;
        }
    }
    
    /**
     * Return null from the signature accessor when signing is OFF.
     */
    @Inject(method = "signature", at = @At("HEAD"), cancellable = true)
    private void nulltrace$stripSignatureOnGet(CallbackInfoReturnable<MessageSignature> info) {
        if (nulltrace$stripSignature()) {
            NullTrace.LOGGER.debug("[NullTrace] signing OFF —returning null signature");
            info.setReturnValue(null);
        }
    }
    //?}
    /**
     * The same rule the encoder path uses; see {@link ChatSigningPolicy}.
     *
     * <p>Kept as one method so the 26.3 and legacy branches cannot disagree, and so this mixin
     * cannot drift away from {@code ClientPacketListenerMixin} again.
     */
    @Unique
    private static boolean nulltrace$stripSignature() {
        return ChatSigningPolicy.shouldStrip(NullTraceConfig.getInstance().isSigningModeOff(),
                NullTraceConfig.isChatSigningManagedExternally());
    }
}
