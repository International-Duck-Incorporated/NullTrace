package net.nulltrace.mod.mixin.client;

import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.PrivacyLogger;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.tracking.ModRegistry;
import net.nulltrace.mod.privacy.ChannelDecision;
import net.nulltrace.mod.privacy.PrivacyAction;
import net.nulltrace.mod.privacy.PrivacyAudit;
import net.nulltrace.mod.privacy.PrivacySurface;
import net.nulltrace.mod.util.LocalAddressUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
//? if >=1.20.5 {
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
//?}
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
//? if >=1.20.2 {
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?}
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;

//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.nulltrace.mod.config.NullTraceConstants.Channels.*;

/**
 * Intercepts and filters outgoing custom payloads for brand spoofing and channel filtering.
 * Also tracks server address for LAN detection.
 *
 * <p>Two injection points exist for the same policy: a Netty outbound handler installed after
 * the encoder (which sees packets from every code path, including other mods') and a
 * {@code Connection#send} inject (which can cancel). Both now call
 * {@link ChannelDecision#decide}, so they cannot drift apart again — previously the
 * ViaFabricPlus identity block existed only in the pipeline handler, and a failed handler
 * install silently restored that leak.
 */
@Mixin(Connection.class)
public class ClientConnectionMixin {

    @Shadow
    private Channel channel;

    @Inject(method = "channelActive", at = @At("HEAD"))
    private void nulltrace$onChannelActive(ChannelHandlerContext context, CallbackInfo ci) {
        try {
            if (context.channel() != null && context.channel().remoteAddress() != null) {
                java.net.SocketAddress addr = context.channel().remoteAddress();
                if (addr instanceof java.net.InetSocketAddress inetSocketAddress) {
                    LocalAddressUtil.serverAddress = inetSocketAddress.getAddress().getHostAddress();
                    NullTrace.LOGGER.debug("[NullTrace] Connected to server: {}", LocalAddressUtil.serverAddress);
                } else {
                    LocalAddressUtil.serverAddress = null;
                }
            }
        } catch (Exception e) {
            NullTrace.LOGGER.debug("[NullTrace] Failed to track server address on connect: {}", e.getMessage());
            LocalAddressUtil.serverAddress = null;
        }
    }

    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void nulltrace$onChannelInactive(ChannelHandlerContext context, CallbackInfo ci) {
        NullTrace.LOGGER.debug("[NullTrace] Disconnected from server");
        LocalAddressUtil.serverAddress = null;
    }

    @Unique
    private final AtomicBoolean nulltrace$logged = new AtomicBoolean(false);

    @Unique
    private volatile boolean nulltrace$pipelineHandlerInstalled = false;

    @Unique
    private static final ThreadLocal<Boolean> nulltrace$sending = ThreadLocal.withInitial(() -> false);

    //? if >=1.20.2 {
    @Unique
    private void nulltrace$ensurePipelineHandler() {
        if (nulltrace$pipelineHandlerInstalled || channel == null) {
            return;
        }

        try {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get("nulltrace_filter") == null) {
                pipeline.addAfter("encoder", "nulltrace_filter", new NullTracePacketFilter());
                nulltrace$pipelineHandlerInstalled = true;
                NullTrace.LOGGER.debug("[NullTrace] Installed Netty pipeline filter (after encoder)");
            } else {
                nulltrace$pipelineHandlerInstalled = true;
            }
        } catch (Exception e) {
            // The Connection#send inject still enforces the same policy, so this is degraded,
            // not broken - but it must not be a debug-only line: payloads written by other mods
            // directly to the channel are no longer filtered.
            NullTrace.LOGGER.error("[NullTrace] Could not install the outbound pipeline filter; "
                    + "channels written directly to the pipeline are unfiltered: {}", e.getMessage());
            PrivacyAudit.record(PrivacySurface.MOD_CHANNEL, "pipeline", PrivacyAction.WARN,
                    "Outbound filter handler could not be installed; relying on the send() path");
            PrivacyLogger.toastWithCooldown(PrivacyLogger.AlertType.WARNING,
                    "NullTrace: outbound filter degraded", "pipeline-filter", 300_000L);
        }
    }

    @Unique
    private static class NullTracePacketFilter extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (!(msg instanceof Packet<?> packet)) {
                ctx.write(msg, promise);
                return;
            }

            if (!(packet instanceof ServerboundCustomPayloadPacket customPayloadPacket)) {
                ctx.write(msg, promise);
                return;
            }

            CustomPacketPayload payload = customPayloadPacket.payload();
            //? if >=1.21.11 {
            Identifier payloadId = payload.type().id();
            //?} else if >=1.20.5 {
            /*ResourceLocation payloadId = payload.type().id();
            *///?} else {
            /*ResourceLocation payloadId = payload.id();
            *///?}

            nulltrace$trackRegistrationChannels(payload, payloadId);

            ChannelDecision.Action action = nulltrace$decide(payload, payloadId);
            if (action == ChannelDecision.Action.PASS) {
                ctx.write(msg, promise);
                return;
            }

            if (action == ChannelDecision.Action.DROP) {
                nulltrace$recordDropped(payloadId);
                promise.setSuccess();
                return;
            }

            // FILTER_REGISTRATION
            //? if >=1.20.5 {
            if (!(payload instanceof RegistrationPayload registrationPayload)) {
                // Unknown class on the register path: block it, as before.
                nulltrace$recordDropped(payloadId);
                promise.setSuccess();
                return;
            }

            List<?> filtered = nulltrace$filteredChannels(registrationPayload);
            if (filtered.isEmpty()) {
                PrivacyAudit.record(PrivacySurface.MOD_CHANNEL, "minecraft:register",
                        PrivacyAction.BLOCK, "No registered channels are allowed by policy");
                promise.setSuccess();
                return;
            }

            ServerboundCustomPayloadPacket rewritten = nulltrace$rewriteRegistration(registrationPayload, filtered);
            if (rewritten == null) {
                // Fail open: forwarding the unfiltered packet is visible in the audit ledger,
                // whereas dropping it silently breaks every mod channel the player uses.
                PrivacyAudit.record(PrivacySurface.MOD_CHANNEL, "minecraft:register",
                        PrivacyAction.WARN, "Could not rebuild the registration payload; sent unfiltered");
                ctx.write(msg, promise);
                return;
            }
            ctx.write(rewritten, promise);
            return;
            //?} else {
            /*nulltrace$recordDropped(payloadId);
            promise.setSuccess();
            return;
            *///?}
        }
    }

    @Inject(method = "configurePacketHandler", at = @At("TAIL"))
    private void onConfigurePacketHandler(CallbackInfo ci) {
        nulltrace$ensurePipelineHandler();
    }
    //?}

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void onSend(Packet<?> packet, CallbackInfo ci) {
        nulltrace$ensurePipelineHandler();

        if (nulltrace$logged.compareAndSet(false, true)) {
            NullTrace.LOGGER.debug("[NullTrace] Connection.send mixin active!");
        }
        handleOutgoingPacket(packet, ci, (Connection)(Object)this);
    }

    @Unique
    private void handleOutgoingPacket(Packet<?> packet, CallbackInfo ci, Connection connection) {
        //? if <1.20.2 {
        /*if (nulltrace$sending.get()) {
            return;
        }

        // 1.20.1 lives in net.minecraft.network.protocol.game, not .common,
        // and wraps a raw FriendlyByteBuf instead of a typed CustomPacketPayload.
        if (!(packet instanceof net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket customPayloadPacket)) {
            return;
        }

        ResourceLocation channelId = customPayloadPacket.getIdentifier();
        String namespace = channelId.getNamespace();
        String path = channelId.getPath();

        // Always track channels from minecraft:register packets so /nulltrace channels
        // and whitelist UI know what the server would otherwise see.
        if (MINECRAFT.equals(namespace) && REGISTER.equals(path)) {
            nulltrace$trackChannelsFromPayload(nulltrace$readRegisterPayload(customPayloadPacket.getData()));
        }

        NullTraceConfig config = NullTraceConfig.getInstance();
        ChannelDecision.Action action = ChannelDecision.decide(
                config.shouldSpoofChannels(),
                config.getSettings().isVanillaMode(),
                config.getSettings().isFabricMode(),
                namespace, path,
                ChannelDecision.isBrand(namespace, path),
                key -> ModRegistry.isWhitelistedChannel(channelId));

        if (action == ChannelDecision.Action.PASS) {
            return;
        }
        if (action == ChannelDecision.Action.DROP) {
            NullTrace.LOGGER.debug("[NullTrace] Blocking: {} ({})", channelId, action);
            ci.cancel();
            return;
        }

        // FILTER_REGISTRATION
        java.util.List<ResourceLocation> originalChannels =
            nulltrace$readRegisterPayload(customPayloadPacket.getData());
        java.util.List<ResourceLocation> filteredChannels = originalChannels.stream()
            .filter(ModRegistry::isWhitelistedChannel)
            .toList();

        NullTrace.LOGGER.debug("[NullTrace] FABRIC MODE - Filtered channels: {} -> {}",
            originalChannels.size(), filteredChannels.size());

        ci.cancel();

        if (filteredChannels.isEmpty()) {
            return;
        }

        net.minecraft.network.FriendlyByteBuf newData = nulltrace$writeRegisterPayload(filteredChannels);
        nulltrace$sending.set(true);
        try {
            connection.send(new net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket(channelId, newData));
        } finally {
            nulltrace$sending.set(false);
        }
        *///?}
        //? if >=1.20.2 {
        if (nulltrace$sending.get()) {
            return;
        }

        // Keybind protection is handled globally by KeybindContentsMixin
        // This handler only processes brand/channel spoofing

        if (!(packet instanceof ServerboundCustomPayloadPacket customPayloadPacket)) {
            return;
        }

        CustomPacketPayload payload = customPayloadPacket.payload();
        //? if >=1.21.11 {
        Identifier payloadId = payload.type().id();
        //?} else if >=1.20.5 {
        /*ResourceLocation payloadId = payload.type().id();
        *///?} else {
        /*ResourceLocation payloadId = payload.id();
        *///?}

        nulltrace$trackRegistrationChannels(payload, payloadId);

        ChannelDecision.Action action = nulltrace$decide(payload, payloadId);
        if (action == ChannelDecision.Action.PASS) {
            return;
        }

        if (action == ChannelDecision.Action.DROP) {
            nulltrace$recordDropped(payloadId);
            ci.cancel();
            return;
        }

        // FILTER_REGISTRATION
        //? if >=1.20.5 {
        if (!(payload instanceof RegistrationPayload registrationPayload)) {
            nulltrace$recordDropped(payloadId);
            ci.cancel();
            return;
        }

        List<?> filtered = nulltrace$filteredChannels(registrationPayload);
        ci.cancel();
        if (filtered.isEmpty()) {
            PrivacyAudit.record(PrivacySurface.MOD_CHANNEL, "minecraft:register",
                    PrivacyAction.BLOCK, "No registered channels are allowed by policy");
            return;
        }

        ServerboundCustomPayloadPacket rewritten = nulltrace$rewriteRegistration(registrationPayload, filtered);
        if (rewritten == null) {
            // Fail open with an audit record rather than dropping every mod channel.
            PrivacyAudit.record(PrivacySurface.MOD_CHANNEL, "minecraft:register",
                    PrivacyAction.WARN, "Could not rebuild the registration payload; sent unfiltered");
            nulltrace$sending.set(true);
            try {
                connection.send(packet);
            } finally {
                nulltrace$sending.set(false);
            }
            return;
        }

        nulltrace$sending.set(true);
        try {
            connection.send(rewritten);
        } finally {
            nulltrace$sending.set(false);
        }
        //?} else {
        /*ci.cancel();
        *///?}
        //?}
    }

    // ---------------------------------------------------------------- shared helpers

    /**
     * Track every channel advertised in a {@code minecraft:register} packet, regardless of
     * spoofing mode, so {@code /nulltrace channels} and the whitelist UI reflect what a server
     * would otherwise see. The pipeline and send paths both used to do this at different times;
     * this is now the single entry point.
     */
    @Unique
    private static void nulltrace$trackRegistrationChannels(Object payload, Object payloadIdRaw) {
        //? if >=1.20.5 {
        if (payload instanceof RegistrationPayload registrationPayload) {
            //? if >=1.21.11 {
            Identifier payloadId = (Identifier) payloadIdRaw;
            //?} else {
            /*ResourceLocation payloadId = (ResourceLocation) payloadIdRaw;
            *///?}
            if (ChannelDecision.isRegistration(payloadId.getNamespace(), payloadId.getPath())) {
                nulltrace$trackChannelsFromPayload(registrationPayload.channels());
            }
        }
        //?}
    }

    //? if >=1.20.2 {
    /** The one place the outbound policy is evaluated. */
    @Unique
    private static ChannelDecision.Action nulltrace$decide(Object payload, Object payloadIdRaw) {
        //? if >=1.21.11 {
        Identifier payloadId = (Identifier) payloadIdRaw;
        //?} else {
        /*ResourceLocation payloadId = (ResourceLocation) payloadIdRaw;
        *///?}
        NullTraceConfig config = NullTraceConfig.getInstance();
        return ChannelDecision.decide(
                config.shouldSpoofChannels(),
                config.getSettings().isVanillaMode(),
                config.getSettings().isFabricMode(),
                payloadId.getNamespace(),
                payloadId.getPath(),
                payload instanceof BrandPayload,
                key -> ModRegistry.isWhitelistedChannel(payloadId));
    }

    @Unique
    private static void nulltrace$recordDropped(Object payloadIdRaw) {
        //? if >=1.21.11 {
        Identifier payloadId = (Identifier) payloadIdRaw;
        //?} else {
        /*ResourceLocation payloadId = (ResourceLocation) payloadIdRaw;
        *///?}
        NullTraceConfig config = NullTraceConfig.getInstance();
        NullTrace.LOGGER.debug("[NullTrace] Blocking custom payload: {}", payloadId);
        PrivacyAudit.record(PrivacySurface.MOD_CHANNEL, payloadId.toString(), PrivacyAction.BLOCK,
                ChannelDecision.dropReason(payloadId.getNamespace(), payloadId.getPath(),
                        config.getSettings().isVanillaMode()));
    }

    //? if >=1.20.5 {
    @Unique
    private static List<?> nulltrace$filteredChannels(RegistrationPayload registrationPayload) {
        //? if >=1.21.11 {
        List<Identifier> originalChannels = registrationPayload.channels();
        List<Identifier> filtered = originalChannels.stream()
                .filter(ModRegistry::isWhitelistedChannel)
                .toList();
        //?} else {
        /*List<ResourceLocation> originalChannels = registrationPayload.channels();
        List<ResourceLocation> filtered = originalChannels.stream()
                .filter(ModRegistry::isWhitelistedChannel)
                .toList();
        *///?}
        NullTrace.LOGGER.debug("[NullTrace] FABRIC MODE - Filtered channels: {} -> {}",
                originalChannels.size(), filtered.size());
        if (filtered.size() < originalChannels.size()) {
            PrivacyAudit.record(PrivacySurface.MOD_CHANNEL, "minecraft:register",
                    PrivacyAction.BLOCK, "Registration payload contained blocked channels");
        }
        return filtered;
    }

    /** Build a replacement packet, or {@code null} when the payload cannot be reconstructed. */
    @Unique
    private static ServerboundCustomPayloadPacket nulltrace$rewriteRegistration(
            RegistrationPayload original, List<?> channels) {
        //? if >=1.21.11 {
        @SuppressWarnings("unchecked")
        List<Identifier> typed = (List<Identifier>) channels;
        //?} else {
        /*@SuppressWarnings("unchecked")
        List<ResourceLocation> typed = (List<ResourceLocation>) channels;
        *///?}
        RegistrationPayload rebuilt = nulltrace$createRegistrationPayload(original, new ArrayList<>(typed));
        return rebuilt == null ? null : new ServerboundCustomPayloadPacket(rebuilt);
    }
    //?}
    //?}

    /**
     * Track ALL channels from the minecraft:register packet.
     * This is the authoritative source - exactly what the server would see.
     */
    @Unique
    //? if >=1.21.11 {
    private void nulltrace$trackAllChannels(List<Identifier> channels) {
    //?} else {
    /*private void nulltrace$trackAllChannels(List<ResourceLocation> channels) {
    *///?}
        nulltrace$trackChannelsFromPayload(channels);
    }

    //? if <1.20.2 {
    /*// Parse a 1.20.1 minecraft:register/unregister payload — null-byte-separated
    // raw UTF-8 channel IDs. Read non-destructively so the source packet still
    // serializes correctly if we let it through.
    @Unique
    private static List<ResourceLocation> nulltrace$readRegisterPayload(net.minecraft.network.FriendlyByteBuf data) {
        int readable = data.readableBytes();
        if (readable <= 0) return java.util.Collections.emptyList();
        byte[] bytes = new byte[readable];
        data.getBytes(data.readerIndex(), bytes);
        String joined = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        List<ResourceLocation> result = new ArrayList<>();
        for (String token : joined.split("\0")) {
            if (token.isEmpty()) continue;
            ResourceLocation parsed = ResourceLocation.tryParse(token);
            if (parsed != null) result.add(parsed);
        }
        return result;
    }

    @Unique
    private static net.minecraft.network.FriendlyByteBuf nulltrace$writeRegisterPayload(List<ResourceLocation> channels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < channels.size(); i++) {
            if (i > 0) sb.append('\0');
            sb.append(channels.get(i).toString());
        }
        byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        net.minecraft.network.FriendlyByteBuf buf =
            new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer(bytes.length));
        buf.writeBytes(bytes);
        return buf;
    }
    *///?}

    /**
     * Static method to track channels - can be called from both instance and static contexts.
     */
    @Unique
    //? if >=1.21.11 {
    private static void nulltrace$trackChannelsFromPayload(List<Identifier> channels) {
    //?} else {
    /*private static void nulltrace$trackChannelsFromPayload(List<ResourceLocation> channels) {
    *///?}
        if (channels == null || channels.isEmpty()) return;
        NullTrace.LOGGER.debug("[NullTrace] Tracking {} channels from minecraft:register packet", channels.size());
        //? if >=1.21.11 {
        for (Identifier channel : channels) {
        //?} else {
        /*for (ResourceLocation channel : channels) {
        *///?}
            String namespace = channel.getNamespace();

            // Forge "common" alias is never a fingerprint.
            if ("c".equals(namespace)) {
                continue;
            }

            // Attribute mods masquerading under minecraft: (e.g. fancymenu_packet_bridge) by path.
            if ("minecraft".equals(namespace)) {
                String owner = ModRegistry.resolveOwningModForChannel(namespace, channel.getPath());
                if (owner != null) {
                    ModRegistry.recordChannel(owner, channel);
                }
                continue;
            }

            // Resolve namespace to actual mod ID(s).
            // NOTE: Do NOT use ModIdResolver.getModIdFromStacktrace() here -- this runs during
            // packet processing, not mod registration, so the stack trace would show Netty/MC/NullTrace
            // frames, not the registering mod's frames.
            Set<String> resolvedModIds = ModRegistry.resolveModIdsForNamespace(namespace);
            if (!resolvedModIds.isEmpty()) {
                for (String modId : resolvedModIds) {
                    ModRegistry.recordChannel(modId, channel);
                }
            } else {
                // Fall back to namespace as mod ID (backwards compat)
                ModRegistry.recordChannel(namespace, channel);
            }
        }
    }

    //? if >=1.20.5 {
    /**
     * The {@code RegistrationPayload} constructor, resolved once.
     *
     * <p>Reflection is unavoidable (the class is a Fabric implementation detail), but this used
     * to walk the constructor list and try both parameter orders on every rewrite. Resolving
     * once and caching the result keeps a Fabric API bump to one log line instead of a per-packet
     * cost, and lets the caller fail open deliberately instead of silently dropping every mod
     * channel.
     */
    @Unique
    private static volatile Constructor<?> nulltrace$registrationCtor;
    @Unique
    private static volatile boolean nulltrace$registrationCtorChannelsFirst;
    @Unique
    private static volatile boolean nulltrace$registrationCtorResolved;

    @Unique
    private static Constructor<?> nulltrace$resolveRegistrationCtor() {
        if (nulltrace$registrationCtorResolved) return nulltrace$registrationCtor;
        synchronized (ClientConnectionMixin.class) {
            if (nulltrace$registrationCtorResolved) return nulltrace$registrationCtor;
            try {
                for (Constructor<?> candidate : RegistrationPayload.class.getDeclaredConstructors()) {
                    if (candidate.getParameterCount() != 2) continue;
                    Class<?>[] parameters = candidate.getParameterTypes();
                    boolean firstIsList = List.class.isAssignableFrom(parameters[0]);
                    boolean secondIsList = List.class.isAssignableFrom(parameters[1]);
                    if (firstIsList == secondIsList) continue; // cannot tell the order apart
                    candidate.setAccessible(true);
                    nulltrace$registrationCtorChannelsFirst = firstIsList;
                    nulltrace$registrationCtor = candidate;
                    break;
                }
            } catch (RuntimeException e) {
                NullTrace.LOGGER.warn("[NullTrace] Could not access RegistrationPayload constructors: {}", e.getMessage());
            }
            nulltrace$registrationCtorResolved = true;
            if (nulltrace$registrationCtor == null) {
                NullTrace.LOGGER.warn("[NullTrace] No usable RegistrationPayload(List, Type) constructor; "
                        + "channel filtering will forward registration payloads unfiltered");
            }
            return nulltrace$registrationCtor;
        }
    }

    @Unique
    //? if >=1.21.11 {
    private static RegistrationPayload nulltrace$createRegistrationPayload(RegistrationPayload original, List<Identifier> channels) {
    //?} else {
    /*private static RegistrationPayload nulltrace$createRegistrationPayload(RegistrationPayload original, List<ResourceLocation> channels) {
    *///?}
        Constructor<?> constructor = nulltrace$resolveRegistrationCtor();
        if (constructor == null) return null;
        try {
            //? if >=26.1 {
            Object type = original.type();
            //?} else {
            /*Object type = original.id();
            *///?}
            return (RegistrationPayload) (nulltrace$registrationCtorChannelsFirst
                    ? constructor.newInstance(channels, type)
                    : constructor.newInstance(type, channels));
        } catch (ReflectiveOperationException | RuntimeException e) {
            NullTrace.LOGGER.warn("[NullTrace] Could not rebuild the registration payload: {}", e.getMessage());
            return null;
        }
    }
    //?}
}
