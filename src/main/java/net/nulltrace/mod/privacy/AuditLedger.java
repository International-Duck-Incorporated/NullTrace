package net.nulltrace.mod.privacy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Thread-safe, bounded connection audit ledger. */
public final class AuditLedger {
    private static final int MAX_EVENTS = 2048;
    private final Object lock = new Object();
    private final Deque<PrivacyDecision> events = new ArrayDeque<>();
    private ConnectionContext context = ConnectionContext.builder().build();

    public void begin(ConnectionContext context) {
        synchronized (lock) {
            this.context = context == null ? ConnectionContext.builder().build() : context;
            events.clear();
        }
    }

    public void record(PrivacyDecision decision) {
        if (decision == null) return;
        synchronized (lock) {
            if (events.size() >= MAX_EVENTS) events.pollFirst();
            events.addLast(decision);
        }
    }

    public ConnectionContext context() {
        synchronized (lock) { return context; }
    }

    public List<PrivacyDecision> events() {
        synchronized (lock) { return List.copyOf(events); }
    }

    public Map<PrivacySurface, Integer> counts(PrivacyAction action) {
        EnumMap<PrivacySurface, Integer> result = new EnumMap<>(PrivacySurface.class);
        synchronized (lock) {
            for (PrivacyDecision decision : events) {
                if (decision.action() == action) {
                    result.merge(decision.surface(), 1, Integer::sum);
                }
            }
        }
        return Map.copyOf(result);
    }

    public long count(PrivacyAction action) {
        synchronized (lock) {
            return events.stream().filter(event -> event.action() == action).count();
        }
    }

    public int remainingExposure() {
        synchronized (lock) {
            return (int) events.stream().filter(event -> event.action() == PrivacyAction.ALLOW
                    || event.action() == PrivacyAction.WARN
                    || event.action() == PrivacyAction.LOG).count();
        }
    }
}
