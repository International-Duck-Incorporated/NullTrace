package net.nulltrace.mod.mixin.client;

import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.tracking.ModRegistry;
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
    private static final AtomicBoolean nulltrace$logged = new AtomicBoolean(false);
    
    @Unique
    private volatile boolean nulltrace$pipelineHandlerInstalled = false;
    
    @Unique
    private static final ThreadLocal<Boolean> nulltrace$sending = ThreadLocal.withInitial(() -> false);
    
    @Unique
    private void nulltrace$ensurePipelineHandler() {
        //? if >=1.20.2 {
        if (nulltrace$pipelineHandlerInstalled || channel == null) {
            return;
        }

        try {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get("nulltrace_filter") == null) {
                pipeline.addAfter("encoder", "nulltrace_filter", new NullTracePacketFilter());
                nulltrace$pipelineHandlerInstalled = true;
                NullTrace.LOGGER.debug("[NullTrace] Installed Netty pipeline filter (after encoder)");
            }
        } catch (Exception e) {
            NullTrace.LOGGER.error("[NullTrace] Failed to install pipeline handler: {}", e.getMessage(), e);
        }
        //?}
    }

    //? if >=1.20.2 {
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

            // ALWAYS track channels from minecraft:register packets for whitelist support
            // This runs regardless of spoofing settings so /nulltrace channels is accurate
            //? if >=1.20.5 {
            if (payload instanceof RegistrationPayload registrationPayload) {
                String namespace = payloadId.getNamespace();
                String path = payloadId.getPath();
                if (MINECRAFT.equals(namespace) && REGISTER.equals(path)) {
                    nulltrace$trackChannelsFromPayload(registrationPayload.channels());
                }
            }
            //?}

            NullTraceConfig config = NullTraceConfig.getInstance();
            if (!config.shouldSpoofChannels()) {
                ctx.write(msg, promise);
                return;
            }

            if (payload instanceof BrandPayload) {
                ctx.write(msg, promise);
                return;
            }

            // ViaFabricPlus/ViaVersion explicitly identifies the client through
            // vv:mod_details. Do not let AUTO whitelist mode re-enable this
            // identity payload. The selected translated protocol can still be
            // observable by a server, but the direct Via identity leak is avoidable.
            if ("vv".equals(payloadId.getNamespace())
                    && ("mod_details".equals(payloadId.getPath())
                    || "app_details".equals(payloadId.getPath())
                    || "proxy_details".equals(payloadId.getPath()))) {
                NullTrace.LOGGER.debug("[NullTrace] Blocking Via identity payload: {}", payloadId);
                PrivacyAudit.record(PrivacySurface.MOD_CHANNEL, payloadId.toString(),
                        PrivacyAction.BLOCK, "Known client-identity channel");
                promise.setSuccess();
                return;
            }

            if (config.getSettings().isVanillaMode()) {
                NullTrace.LOGGER.debug("[NullTrace] VANILLA MODE (pipeline) - Blocking: {}", payloadId);
                PrivacyAudit.record(PrivacySurface.MOD_CHANNEL, payloadId.toString(),
                        PrivacyAction.BLOCK, "Vanilla profile blocks custom payloads");
                promise.setSuccess();
                return;
            }

            if (config.getSettings().isFabricMode()) {
                String namespace = payloadId.getNamespace();
                String path = payloadId.getPath();

                if (MINECRAFT.equals(namespace) && (REGISTER.equals(path) || UNREGISTER.equals(path))) {
                    //? if >=1.20.5 {
                    if (payload instanceof RegistrationPayload registrationPayload) {
                        // Use ModRegistry.isWhitelistedChannel which handles:
                        // - minecraft:* channels
                        // - Whitelisted mod channels (including fabric API modules via DEFAULT_FABRIC_MODS)
                        //? if >=1.21.11 {
                        List<Identifier> filtered = registrationPayload.channels().stream()
                        //?} else {
                        /*List<ResourceLocation> filtered = registrationPayload.channels().stream()
                        *///?}
                            .filter(ModRegistry::isWhitelistedChannel)
                            .toList();
                        
                        NullTrace.LOGGER.debug("[NullTrace] FABRIC MODE (pipeline) - Filtered channels: {} -> {}", 
                            registrationPayload.channels().size(), filtered.size());
                        if (filtered.size() < registrationPayload.channels().size()) {
                            PrivacyAudit.record(PrivacySurface.MOD_CHANNEL, "minecraft:register",
                                    PrivacyAction.BLOCK, "Registration payload contained blocked channels");
                        }
                        
                        if (filtered.isEmpty()) {
                            PrivacyAudit.record(PrivacySurface.MOD_CHANNEL, "minecraft:register",
                                    PrivacyAction.BLOCK, "No registered channels are allowed by policy");
                            promise.setSuccess();
                            return;
                        }
                        
                        RegistrationPayload newPayload = nulltrace$createRegistrationPayload(registrationPayload, new ArrayList<>(filtered));
                        if (newPayload != null) {
                            ctx.write(new ServerboundCustomPayloadPacket(newPayload), promise);
                            return;
                        }
                    }
                    //?}
                    promise.setSuccess();
                    return;
                }

                // Everything else gates on isWhitelistedChannel, incl. minecraft: masquerading mods
                // (e.g. fancymenu_packet_bridge) — never short-circuit minecraft: to allow.
                if (ModRegistry.isWhitelistedChannel(payloadId)) {
                    ctx.write(msg, promise);
                    return;
                }

                NullTrace.LOGGER.debug("[NullTrace] FABRIC MODE (pipeline) - Blocking mod channel: {}", payloadId);
                PrivacyAudit.record(PrivacySurface.MOD_CHANNEL, payloadId.toString(),
                        PrivacyAction.BLOCK, "Channel is not allowed by the active profile");
                promise.setSuccess();
                return;
            }
            
            ctx.write(msg, promise);
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
        if (!config.shouldSpoofChannels()) {
            return;
        }

        // Brand: let through — ClientBrandRetrieverMixin already spoofs the string.
        if (MINECRAFT.equals(namespace) && "brand".equals(path)) {
            return;
        }

        if (config.getSettings().isVanillaMode()) {
            NullTrace.LOGGER.debug("[NullTrace] VANILLA MODE - Blocking: {}", channelId);
            ci.cancel();
            return;
        }

        if (config.getSettings().isFabricMode()) {
            if (MINECRAFT.equals(namespace) && (REGISTER.equals(path) || UNREGISTER.equals(path))) {
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
                return;
            }

            if (MINECRAFT.equals(namespace) && MCO.equals(path)) {
                NullTrace.LOGGER.debug("[NullTrace] Blocking minecraft:mco to match stock Fabric");
                ci.cancel();
                return;
            }

            // minecraft: masquerading mods gate on isWhitelistedChannel too — no short-circuit.
            if (ModRegistry.isWhitelistedChannel(channelId)) {
                return;
            }

            NullTrace.LOGGER.debug("[NullTrace] FABRIC MODE - Blocking mod channel: {}", channelId);
            ci.cancel();
        }
        return;
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

        // ALWAYS track channels from minecraft:register packets for whitelist support
        // This runs regardless of spoofing settings so /nulltrace channels is accurate
        //? if >=1.20.5 {
        if (payload instanceof RegistrationPayload registrationPayload) {
            String namespace = payloadId.getNamespace();
            String path = payloadId.getPath();
            if (MINECRAFT.equals(namespace) && REGISTER.equals(path)) {
                nulltrace$trackChannelsFromPayload(registrationPayload.channels());
            }
        }
        //?}

        NullTraceConfig config = NullTraceConfig.getInstance();
        if (!config.shouldSpoofChannels()) {
            return;
        }

        if (payload instanceof BrandPayload) {
            return;
        }
        
        if (config.getSettings().isVanillaMode()) {
            NullTrace.LOGGER.debug("[NullTrace] VANILLA MODE - Blocking: {}", payloadId);
            ci.cancel();
            return;
        }
        
        if (config.getSettings().isFabricMode()) {
            String namespace = payloadId.getNamespace();
            String path = payloadId.getPath();
            
            if (MINECRAFT.equals(namespace) && (REGISTER.equals(path) || UNREGISTER.equals(path))) {
                //? if >=1.20.5 {
                if (payload instanceof RegistrationPayload registrationPayload) {
                    nulltrace$handleMinecraftRegisterForFabric(registrationPayload, ci, connection);
                } else {
                    NullTrace.LOGGER.warn("[NullTrace] Blocking unknown {}: {}",
                        payloadId, payload.getClass().getName());
                    ci.cancel();
                }
                //?} else {
                /*// 1.20.4 and below: fabric-networking-api lacks RegistrationPayload — block
                // any minecraft:register/unregister payload outright in fabric mode (matches
                // the post-1.20.5 fabric-blocks-non-whitelisted-channels intent).
                NullTrace.LOGGER.debug("[NullTrace] FABRIC MODE - Blocking {} payload (pre-1.20.5 path): {}",
                    path, payload.getClass().getName());
                ci.cancel();
                *///?}
                return;
            }

            if (MINECRAFT.equals(namespace) && MCO.equals(path)) {
                NullTrace.LOGGER.debug("[NullTrace] Blocking minecraft:mco to match stock Fabric");
                ci.cancel();
                return;
            }
            
            // Everything else gates on isWhitelistedChannel, incl. minecraft: masquerading mods
            // (e.g. fancymenu_packet_bridge) — never short-circuit minecraft: to allow.
            if (ModRegistry.isWhitelistedChannel(payloadId)) {
                return;
            }

            NullTrace.LOGGER.debug("[NullTrace] FABRIC MODE - Blocking mod channel: {}", payloadId);
            ci.cancel();
            return;
        }
        //?}
    }


    //? if >=1.20.5 {
    @Unique
    private void nulltrace$handleMinecraftRegisterForFabric(RegistrationPayload original, CallbackInfo ci, Connection connection) {
        //? if >=1.21.11 {
        List<Identifier> originalChannels = original.channels();
        //?} else {
        /*List<ResourceLocation> originalChannels = original.channels();
        *///?}
        
        // Track ALL channels before filtering - this is what the server would see
        nulltrace$trackAllChannels(originalChannels);
        
        // Use ModRegistry.isWhitelistedChannel which handles:
        // - minecraft:* channels
        // - Whitelisted mod channels (including fabric API modules via DEFAULT_FABRIC_MODS)
        //? if >=1.21.11 {
        List<Identifier> filteredChannels = originalChannels.stream()
        //?} else {
        /*List<ResourceLocation> filteredChannels = originalChannels.stream()
        *///?}
            .filter(ModRegistry::isWhitelistedChannel)
            .toList();
        
        NullTrace.LOGGER.debug("[NullTrace] FABRIC MODE - Filtered channels: {} -> {}", 
            originalChannels.size(), filteredChannels.size());
        
        ci.cancel();
        
        if (filteredChannels.isEmpty()) {
            return;
        }
        
        RegistrationPayload filteredPayload = nulltrace$createRegistrationPayload(original, new ArrayList<>(filteredChannels));
        if (filteredPayload == null) {
            NullTrace.LOGGER.error("[NullTrace] Could not create filtered RegistrationPayload");
            return;
        }
        
        nulltrace$sending.set(true);
        try {
            connection.send(new ServerboundCustomPayloadPacket(filteredPayload));
        } finally {
            nulltrace$sending.set(false);
        }
    }
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
    @Unique
    //? if >=1.21.11 {
    private static RegistrationPayload nulltrace$createRegistrationPayload(RegistrationPayload original, List<Identifier> channels) {
    //?} else {
    /*private static RegistrationPayload nulltrace$createRegistrationPayload(RegistrationPayload original, List<ResourceLocation> channels) {
    *///?}
        try {
            for (Constructor<?> constructor : RegistrationPayload.class.getDeclaredConstructors()) {
                if (constructor.getParameterCount() == 2) {
                    constructor.setAccessible(true);
                    try {
                        //? if >=26.1 {
                        return (RegistrationPayload) constructor.newInstance(original.type(), channels);
                        //?} else {
                        /*return (RegistrationPayload) constructor.newInstance(original.id(), channels);
                        *///?}
                    } catch (Exception e1) {
                        try {
                            //? if >=26.1 {
                            return (RegistrationPayload) constructor.newInstance(channels, original.type());
                            //?} else {
                            /*return (RegistrationPayload) constructor.newInstance(channels, original.id());
                            *///?}
                        } catch (Exception e2) {
                            NullTrace.LOGGER.debug("[NullTrace] Failed parameter order attempt: {}", e2.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            NullTrace.LOGGER.error("[NullTrace] Failed to create RegistrationPayload: {}", e.getMessage(), e);
        }
        NullTrace.LOGGER.warn("[NullTrace] Unable to create RegistrationPayload - no compatible constructor found");
        return null;
    }
    //?}
}
