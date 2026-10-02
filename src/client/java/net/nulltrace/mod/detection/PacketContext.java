package net.nulltrace.mod.detection;

import net.minecraft.network.protocol.Packet;
import net.nulltrace.mod.protection.NullTraceFromPacketAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * ThreadLocal tracking for packet processing context.
 * Set true during packet decode and handle, read by content constructors
 * to tag instances that originated from network packets.
 *
 * Two injection points use this:
 * - PacketDecoderMixin wraps StreamCodec.decode() (eager deserialization)
 * - PacketProcessorMixin wraps Packet.handle() (lazy deserialization)
 *
 * {@link #isDecodingPayload()} is the narrower of the two: it is true ONLY inside
 * StreamCodec.decode (PacketDecoderMixin), never during handle(). The inbound
 * channel-fingerprint gate needs that distinction — Fabric calls the same
 * PayloadTypeRegistryImpl.get(id) both while decoding an inbound payload (which we
 * want to redirect to vanilla's DiscardedPayload) and while registering mod
 * receivers during the config->play transition, which runs inside finish_configuration's
 * handle() (where suppressing it would crash registration).
 */
public class PacketContext {
    private static final ThreadLocal<Boolean> PROCESSING_PACKET =
        ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<Boolean> DECODING_PAYLOAD =
        ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<String> PACKET_NAME = new ThreadLocal<>();

    private static final ThreadLocal<Object> CURRENT_PACKET = new ThreadLocal<>();

    private static final ThreadLocal<List<NullTraceFromPacketAccess>> DECODED_COMPONENTS =
        new ThreadLocal<>();

    private PacketContext() {}

    public static boolean isProcessingPacket() {
        return PROCESSING_PACKET.get();
    }

    public static void setProcessingPacket(boolean value) {
        PROCESSING_PACKET.set(value);
    }

    /** True only inside StreamCodec.decode (PacketDecoderMixin), not during handle(). */
    public static boolean isDecodingPayload() {
        return DECODING_PAYLOAD.get();
    }

    public static void setDecodingPayload(boolean value) {
        DECODING_PAYLOAD.set(value);
    }

    /** Start tracking components decoded during a single PacketDecoder.decode() call. */
    public static void beginPacketDecode() {
        PROCESSING_PACKET.set(true);
        DECODING_PAYLOAD.set(true);
        List<NullTraceFromPacketAccess> list = DECODED_COMPONENTS.get();
        if (list != null) {
            list.clear();
        }
    }

    /** Assign the resolved packet name to all components decoded during the current decode() call. */
    public static void finishPacketDecode(Object packet) {
        List<NullTraceFromPacketAccess> decoded = DECODED_COMPONENTS.get();
        if (decoded != null && !decoded.isEmpty() && packet != null) {
            String resolved = resolvePacketName(packet);
            for (NullTraceFromPacketAccess component : decoded) {
                if (component.nulltrace$getPacketName() == null) {
                    component.nulltrace$setPacketName(resolved);
                }
            }
            decoded.clear();
        }
    }

    /** Clean up decode tracking state in a finally block. */
    public static void endPacketDecode() {
        List<NullTraceFromPacketAccess> decoded = DECODED_COMPONENTS.get();
        if (decoded != null) {
            decoded.clear();
        }
        DECODING_PAYLOAD.set(false);
        PROCESSING_PACKET.set(false);
    }

    /** Record a packet-tagged component so it receives the packet's name once decode completes. */
    public static void registerDecodedComponent(NullTraceFromPacketAccess component) {
        if (component == null) return;
        String currentName = getRawPacketName();
        if (currentName != null) {
            component.nulltrace$setPacketName(currentName);
        }
        if (DECODING_PAYLOAD.get()) {
            List<NullTraceFromPacketAccess> decoded = DECODED_COMPONENTS.get();
            if (decoded == null) {
                decoded = new ArrayList<>(4);
                DECODED_COMPONENTS.set(decoded);
            }
            decoded.add(component);
        }
    }

    public static String getPacketName() {
        String name = getRawPacketName();
        return name != null ? name : "unknown";
    }

    public static String getRawPacketName() {
        String explicit = PACKET_NAME.get();
        if (explicit != null) {
            return explicit;
        }
        Object currentPacket = CURRENT_PACKET.get();
        if (currentPacket != null) {
            String resolved = resolvePacketName(currentPacket);
            PACKET_NAME.set(resolved);
            return resolved;
        }
        return null;
    }

    public static void setRawPacketName(String packetName) {
        if (packetName == null) {
            PACKET_NAME.remove();
        } else {
            PACKET_NAME.set(packetName);
        }
    }

    public static Object getCurrentPacket() {
        return CURRENT_PACKET.get();
    }

    public static void setCurrentPacket(Object packet) {
        PACKET_NAME.remove();
        if (packet == null) {
            CURRENT_PACKET.remove();
        } else {
            CURRENT_PACKET.set(packet);
        }
    }

    /**
     * Resolve and store the packet name from its PacketType ResourceLocation.
     * This gives stable, human-readable IDs like "minecraft:system_chat"
     * regardless of obfuscation.
     */
    public static void setPacketName(Object packet) {
        setCurrentPacket(packet);
    }

    public static String resolvePacketName(Object packet) {
        if (packet instanceof Packet<?> p) {
            try {
                //? if >=1.20.5 {
                return p.type().id().toString();
                //?} else {
                /*// 1.20.4 and below: Packet doesn't have type() — fall back to class name
                return p.getClass().getSimpleName();
                *///?}
            } catch (Exception e) {
                return "unknown";
            }
        }
        return "unknown";
    }
}
