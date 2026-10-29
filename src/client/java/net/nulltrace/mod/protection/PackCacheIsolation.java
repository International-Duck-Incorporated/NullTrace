package net.nulltrace.mod.protection;

import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.privacy.PrivacyAction;
import net.nulltrace.mod.privacy.PrivacyAudit;
import net.nulltrace.mod.privacy.PrivacySurface;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Liveness check for the per-account resource-pack cache isolation.
 *
 * <p>That feature hangs off the one injection in the mod that must tolerate a miss:
 * {@code DownloadQueueMixin} targets a lambda inside a class NullTrace does not own, so it is
 * declared {@code require = 0} (a Fabric or Minecraft bump renames the target and
 * {@code require = 1} would take the whole client down). The cost of that tolerance was mute
 * failure: the README advertises "Isolate server-pack caches by account", the option stays on,
 * the audit shows nothing, and the server quietly gets a shared cache path again — a fingerprinting
 * channel reopened with no signal at all.
 *
 * <p>So the mixin reports every time it actually runs ({@link #recordIsolation()}), and the first
 * server pack of a session starts a one-shot timer. If a pack was pushed, the option is on, and the
 * injection never fired, that is a loud, inspectable failure: a log line plus a
 * {@link PrivacyAction#WARN} entry in the connection ledger, the same shape as the
 * {@code RegistrationPayload} reflection finding.
 *
 * <p>The 30 s grace period exists because the pack download, the path resolution and a slow proxy
 * can all be spread out; checking synchronously at push time would report a false negative on
 * every session.
 */
public final class PackCacheIsolation {
    private PackCacheIsolation() {}

    /** How long after the first pushed pack to decide the injection is dead. */
    private static final long VERIFY_DELAY_SECONDS = 30;

    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicBoolean VERIFY_SCHEDULED = new AtomicBoolean();
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /** Called by {@code DownloadQueueMixin} every time it rewrites a pack cache path. */
    public static void recordIsolation() {
        HITS.incrementAndGet();
    }

    /** Number of paths the injection has rewritten this session; 0 means it never bound. */
    public static int isolationCount() {
        return HITS.get();
    }

    /**
     * A server pushed a pack. Starts the liveness timer once per session.
     *
     * <p>Called from the pack-push path, not from the mixin — the whole point is to notice when the
     * mixin is absent.
     */
    public static void noteServerPackPush() {
        if (!NullTraceConfig.getInstance().shouldIsolatePackCache()) return;
        if (!VERIFY_SCHEDULED.compareAndSet(false, true)) return;
        CompletableFuture.delayedExecutor(VERIFY_DELAY_SECONDS, TimeUnit.SECONDS)
                .execute(PackCacheIsolation::verifyBinding);
    }

    /** Test/verification entry point; also what the delayed task runs. */
    static void verifyBinding() {
        if (HITS.get() > 0) return;
        if (!NullTraceConfig.getInstance().shouldIsolatePackCache()) return;
        if (!WARNED.compareAndSet(false, true)) return;

        NullTrace.LOGGER.warn(
                "[NullTrace] Pack-cache isolation is enabled but its injection never ran - "
                + "server packs are sharing one cache path (no per-account isolation on this "
                + "Minecraft version). Disable 'Isolate Pack Cache' to silence this.");
        PrivacyAudit.record(PrivacySurface.RESOURCE_PACK, "pack-cache isolation",
                PrivacyAction.WARN,
                "injection did not bind; pack cache is shared between accounts");
    }

    /** Reset per-connection/per-session state. Called on disconnect and from tests. */
    public static void reset() {
        HITS.set(0);
        VERIFY_SCHEDULED.set(false);
        WARNED.set(false);
    }
}
