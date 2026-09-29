package net.nulltrace.mod.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.nulltrace.mod.detection.TrackPackDetector;
import net.nulltrace.mod.privacy.ConnectionWarning;
import net.nulltrace.mod.privacy.PrivacyAudit;
import net.nulltrace.mod.privacy.PrivacyWarningScreen;
import net.nulltrace.mod.protection.TranslationProtectionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Resets connection state and presents a privacy consent screen before joining. */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void nulltrace$warnBeforeConnecting(
            Screen parent, Minecraft client, ServerAddress address, ServerData serverData,
            boolean quickPlay, TransferState transferState, CallbackInfo ci) {
        TrackPackDetector.reset();
        TranslationProtectionHandler.clearCache();

        if (ConnectionWarning.consumeBypass()) return;

        ConnectionWarning.Summary summary = ConnectionWarning.summarize(address.toString());
        if (!summary.shouldWarn()) {
            PrivacyAudit.begin(address.toString(), "unknown");
            return;
        }

        PrivacyAudit.begin(address.toString(), "unknown");
        ci.cancel();
        //? if >=26.2 {
        client.setScreenAndShow(new PrivacyWarningScreen(parent, client, address, serverData,
                quickPlay, transferState, summary));
        //?} else {
        /*client.setScreen(new PrivacyWarningScreen(parent, client, address, serverData,
                quickPlay, transferState, summary));
        *///?}
    }
}
