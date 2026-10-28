package net.nulltrace.mod.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.PrivacyLogger;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.privacy.ChatSigningPolicy;
import net.nulltrace.mod.detection.PacketContext;
import net.nulltrace.mod.protection.PackStripHandler;
import net.nulltrace.mod.protection.PackStripOverlay;
import net.nulltrace.mod.privacy.PrivacyAudit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.SignedMessageBody;
import net.minecraft.network.chat.SignedMessageChain;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
//? if <1.20.2 {
/*import net.nulltrace.mod.detection.TrackPackDetector;
import net.nulltrace.mod.lang.NullTraceLang;
import net.nulltrace.mod.lang.NullTraceStrings;
import net.minecraft.network.protocol.game.ClientboundResourcePackPacket;
*///?}
//? if <1.20.5 {
/*import net.minecraft.network.protocol.game.ClientboundServerDataPacket;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tracks server connection events for NullTrace.
 *
 * <p>Chat-signing scope here is limited to a single mechanism: the
 * {@code nulltrace$skipSigningEncoder} {@code @WrapOperation} on the
 * {@code SignedMessageChain.Encoder.pack(...)} call inside {@code sendChat},
 * which prevents signing-chain advancement whenever the user has signing OFF.
 * No server-state-triggered upgrades, no system-chat reactions, no auto-resends
 * — those were removed because they uniformly fingerprinted NullTrace users.</p>
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Unique
    private static volatile ScheduledExecutorService nulltrace$scheduler;

    @Unique
    private static volatile ScheduledFuture<?> nulltrace$pendingTask;

    @Unique
    private static synchronized ScheduledExecutorService nulltrace$getScheduler() {
        if (nulltrace$scheduler == null || nulltrace$scheduler.isShutdown()) {
            nulltrace$scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NullTrace-Scheduler");
            t.setDaemon(true);
            return t;
        });
        }
        return nulltrace$scheduler;
    }

    @Unique
    private static synchronized void nulltrace$shutdownScheduler() {
        if (nulltrace$pendingTask != null) {
            nulltrace$pendingTask.cancel(false);
            nulltrace$pendingTask = null;
        }
        if (nulltrace$scheduler != null && !nulltrace$scheduler.isShutdown()) {
            nulltrace$scheduler.shutdownNow();
            nulltrace$scheduler = null;
        }
    }

    /**
     * Wrap sub-packet handle() calls inside handleBundlePacket so each sub-packet
     * gets its own packet name context. Without this, all sub-packets inherit the
     * bundle's name since they bypass PacketProcessor$ListenerAndPacket.
     */
    @WrapOperation(
        method = "handleBundlePacket",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V")
    )
    private <T extends PacketListener> void nulltrace$wrapBundleSubPacketHandle(
            Packet<T> instance, T listener, Operation<Void> original) {
        Object previousPacket = PacketContext.getCurrentPacket();
        String previousPacketName = PacketContext.getRawPacketName();
        boolean previousProcessing = PacketContext.isProcessingPacket();
        PacketContext.setCurrentPacket(instance);
        PacketContext.setProcessingPacket(true);
        try {
            original.call(instance, listener);
        } finally {
            PacketContext.setProcessingPacket(previousProcessing);
            PacketContext.setCurrentPacket(previousPacket);
            PacketContext.setRawPacketName(previousPacketName);
        }
    }

    /**
     * Stable per-server key for privacy profiles and the audit ledger.
     *
     * <p>{@code InetSocketAddress#toString()} is {@code hostname/resolvedIp:port}, so the old
     * parse (strip a leading {@code /}, then cut at the last {@code :}) produced
     * {@code example.com/172.66.147.243}. Two consequences, both silent: a profile saved against
     * that key stops matching as soon as the server's DNS answer changes (proxies, SRV targets,
     * any CDN front-end), and joining the same server by hostname and by IP produced two
     * different profiles. A user who pinned {@code MAXIMUM_PRIVACY} for a server could end up on
     * {@code STANDARD} without ever being told.
     *
     * <p>{@link InetSocketAddress#getHostString()} returns the host name the client connected
     * with and never the resolved address, which is exactly the identity a profile should have.
     */
    @Unique
    private static String nulltrace$serverKey(java.net.SocketAddress address) {
        if (address instanceof java.net.InetSocketAddress inet) {
            String host = inet.getHostString();
            return host == null ? "" : host;
        }
        String fallback = address == null ? "" : address.toString();
        if (fallback.startsWith("/")) fallback = fallback.substring(1);
        int colonIndex = fallback.lastIndexOf(':');
        return colonIndex > 0 ? fallback.substring(0, colonIndex) : fallback;
    }

    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void onLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        ClientPacketListener listener = (ClientPacketListener)(Object)this;
        String serverAddress = nulltrace$serverKey(listener.getConnection().getRemoteAddress());

        NullTraceConfig.getInstance().setCurrentServer(serverAddress);
        PrivacyAudit.begin(serverAddress, "unknown");

        // Schedule port scan summary after 2 seconds
        nulltrace$pendingTask = nulltrace$getScheduler().schedule(() -> {
            Minecraft.getInstance().execute(PrivacyLogger::showPortScanSummary);
        }, 2, TimeUnit.SECONDS);
    }

    //? if <1.20.2 {
    /*// <1.20.2: single-pack server packs come through ClientboundResourcePackPacket on
    // the game listener; on 1.20.2+ this is ClientCommonPacketListenerImplMixin instead.
    // Drives TrackPack detection and pack-strip state (keyed by LEGACY_SERVER_PACK_UUID).
    @Inject(method = "handleResourcePack", at = @At("HEAD"))
    private void nulltrace$onLegacyResourcePack(ClientboundResourcePackPacket packet, CallbackInfo ci) {
        String url = packet.getUrl();
        String hash = packet.getHash();

        TrackPackDetector.recordRequest(url, hash);

        if (TrackPackDetector.isFingerprinting() && TrackPackDetector.consumeNotifyPatternOnce()) {
            PrivacyLogger.alert(PrivacyLogger.AlertType.DANGER,
                NullTraceLang.tr(NullTraceStrings.ALERT_TRACKPACK_PATTERN));
            PrivacyLogger.toast(PrivacyLogger.AlertType.DANGER,
                NullTraceLang.tr(NullTraceStrings.TOAST_TRACKPACK));
        }

        PackStripHandler.onPackPush(PackStripHandler.LEGACY_SERVER_PACK_UUID, url, packet.isRequired());
    }
    *///?}

    /**
     * Wrap the signing encoder call in sendChat() to prevent chain advancement when not signing.
     * Returns null instead of calling the encoder, keeping the chain clean.
     */
    @WrapOperation(
            method = "sendChat",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/SignedMessageChain$Encoder;pack(Lnet/minecraft/network/chat/SignedMessageBody;)Lnet/minecraft/network/chat/MessageSignature;")
    )
    private MessageSignature nulltrace$skipSigningEncoder(SignedMessageChain.Encoder encoder, SignedMessageBody body, Operation<MessageSignature> original) {
        // One rule for both signing call sites; see ChatSigningPolicy.
        if (nulltrace$stripSignature()) {
            NullTrace.LOGGER.debug("[NullTrace] Skipping message signing (chain preserved): {}",
                    ChatSigningPolicy.describe(nulltrace$signingReason()));
            return null;
        }
        return original.call(encoder, body);
    }

    @Unique
    private static ChatSigningPolicy.Reason nulltrace$signingReason() {
        return ChatSigningPolicy.reason(NullTraceConfig.getInstance().isSigningModeOff(),
                NullTraceConfig.isChatSigningManagedExternally());
    }

    @Unique
    private static boolean nulltrace$stripSignature() {
        return ChatSigningPolicy.shouldStrip(NullTraceConfig.getInstance().isSigningModeOff(),
                NullTraceConfig.isChatSigningManagedExternally());
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        nulltrace$shutdownScheduler();
        PrivacyLogger.resetPortScanTracking();
        PrivacyLogger.clearCooldowns();
        NullTraceConfig.getInstance().setCurrentServer(null);
        PackStripHandler.clearAll();
        PackStripOverlay.clearQueue();
    }
}
