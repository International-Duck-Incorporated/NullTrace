package net.nulltrace.mod.protection;

public interface NullTraceFromPacketAccess {
    void nulltrace$setFromPacket();
    default void nulltrace$setSilent() {}
}
