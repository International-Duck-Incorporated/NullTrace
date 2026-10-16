package net.nulltrace.mod.privacy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thread-safe, bounded connection audit ledger.
 *
 * <p>Two things are tracked per connection, deliberately separately:
 *
 * <ul>
 *   <li>{@link #events()} — the most recent decisions, bounded so a hostile server cannot grow
 *       memory without limit. This is what the audit screen lists.</li>
 *   <li>cumulative counters — {@link #count(PrivacyAction)}, {@link #counts(PrivacyAction)} and
 *       {@link #allowedSurfaceCount()}, which do <b>not</b> depend on the event window. Reading
 *       totals out of the bounded deque made every number freeze at 2048 on a flooding server.</li>
 * </ul>
 */
public final class AuditLedger {
    private static final int MAX_EVENTS = 2048;
    private final Object lock = new Object();
    private final Deque<PrivacyDecision> events = new ArrayDeque<>();
    private final EnumMap<PrivacySurface, Integer> blockCounts = new EnumMap<>(PrivacySurface.class);
    private final EnumMap<PrivacySurface, Integer> allowCounts = new EnumMap<>(PrivacySurface.class);
    private final EnumMap<PrivacySurface, Integer> warnCounts = new EnumMap<>(PrivacySurface.class);
    private final EnumMap<PrivacySurface, Integer> logCounts = new EnumMap<>(PrivacySurface.class);
    private final EnumSet<PrivacySurface> allowedSurfaces = EnumSet.noneOf(PrivacySurface.class);
    private long allowedEvents;
    private ConnectionContext context = ConnectionContext.builder().build();

    public void begin(ConnectionContext context) {
        synchronized (lock) {
            this.context = context == null ? ConnectionContext.builder().build() : context;
            events.clear();
            blockCounts.clear();
            allowCounts.clear();
            warnCounts.clear();
            logCounts.clear();
            allowedSurfaces.clear();
            allowedEvents = 0;
        }
    }

    public void record(PrivacyDecision decision) {
        if (decision == null) return;
        synchronized (lock) {
            if (events.size() >= MAX_EVENTS) events.pollFirst();
            events.addLast(decision);

            // Cumulative tallies, independent of the window above.
            countsFor(decision.action()).merge(decision.surface(), 1, Integer::sum);
            if (decision.action() != PrivacyAction.BLOCK) {
                allowedSurfaces.add(decision.surface());
                allowedEvents++;
            }
        }
    }

    private EnumMap<PrivacySurface, Integer> countsFor(PrivacyAction action) {
        return switch (action) {
            case BLOCK -> blockCounts;
            case ALLOW -> allowCounts;
            case WARN -> warnCounts;
            case LOG -> logCounts;
        };
    }

    public ConnectionContext context() {
        synchronized (lock) { return context; }
    }

    public List<PrivacyDecision> events() {
        synchronized (lock) { return List.copyOf(events); }
    }

    /** Cumulative per-surface counts for one action across the current connection. */
    public Map<PrivacySurface, Integer> counts(PrivacyAction action) {
        synchronized (lock) {
            return Map.copyOf(countsFor(action));
        }
    }

    /** Cumulative number of decisions with this action across the current connection. */
    public long count(PrivacyAction action) {
        synchronized (lock) {
            long total = 0;
            for (int value : countsFor(action).values()) total += value;
            return total;
        }
    }

    /**
     * Distinct surfaces the current connection is allowed to expose.
     *
     * <p>This intentionally counts <b>surfaces, not events</b>. The old
     * {@code remainingExposure()} counted every ALLOW/WARN/LOG entry in a bounded deque, so a
     * server that probed repeatedly walked the number up to the ledger's 2048-event ceiling while
     * nothing new was exposed — an alarming figure that meant nothing.
     */
    public int allowedSurfaceCount() {
        synchronized (lock) {
            return allowedSurfaces.size();
        }
    }

    /** Distinct surfaces recorded as ALLOW/WARN/LOG, for diagnostics. */
    public Set<PrivacySurface> allowedSurfaces() {
        synchronized (lock) {
            return Set.copyOf(allowedSurfaces);
        }
    }

    /**
     * @deprecated a count of allowed <em>events</em>, not exposure. Use {@link #allowedSurfaceCount()}.
     */
    @Deprecated
    public int remainingExposure() {
        synchronized (lock) {
            return (int) allowedEvents;
        }
    }
}
