package net.nulltrace.mod;

/**
 * Compile-time stand-in for the real {@code NullTrace} entry point.
 *
 * <p>This file exists <b>only</b> so {@code tests/proxy/ProxyProbeHarness.java} can compile and run
 * the proxy classes outside Minecraft: the real class pulls in FabricLoader and SLF4J, while
 * {@code ProxyTester} needs nothing from it but a logger and the version string.
 *
 * <p>It is never compiled into the mod jar — the mod build only includes {@code src/main},
 * {@code src/client} and the version targets, and this file lives under {@code tests/}.
 */
public final class NullTrace {
    public static final StubLogger LOGGER = new StubLogger();

    private NullTrace() {}

    public static String getVersion() { return "harness"; }

    /** Log lines are irrelevant to a probe harness; keep the output readable. */
    public static final class StubLogger {
        public void debug(String format, Object... args) {}
        public void info(String format, Object... args) {}
        public void warn(String format, Object... args) {}
        public void error(String format, Object... args) {}
    }
}
