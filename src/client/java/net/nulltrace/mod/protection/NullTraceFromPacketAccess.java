package net.nulltrace.mod.protection;

public interface NullTraceFromPacketAccess {
    void nulltrace$setFromPacket();
    default void nulltrace$setSilent() {}
    default void nulltrace$setPacketName(String packetName) {}
    default String nulltrace$getPacketName() { return null; }
}
