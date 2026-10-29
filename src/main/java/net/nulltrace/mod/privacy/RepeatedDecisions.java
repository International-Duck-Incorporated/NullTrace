package net.nulltrace.mod.privacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Folds an audit-ledger window into distinct decision runs for display.
 *
 * <p>{@link AuditLedger} records one decision per <em>evaluation</em>, which is the right shape for
 * counting and for the exit summary, but a poor one for the tail of {@code /nulltrace audit}: the
 * client brand is evaluated several times per connection, so the last eight lines were often three
 * to eight copies of the same {@code CLIENT_BRAND} entry and every other decision was pushed out of
 * the window. Collapsing unbroken runs keeps the history readable without discarding information —
 * the repeat count is preserved and the newest runs are kept first.
 *
 * <p>Lives in the common source set (no Minecraft types) so it is unit-testable; see
 * {@code RepeatedDecisionsTest}.
 */
public final class RepeatedDecisions {
    private RepeatedDecisions() {}

    /**
     * One folded entry.
     *
     * @param decision the decision that was recorded (the newest occurrence of the run)
     * @param count    how many consecutive evaluations produced it, at least 1
     */
    public record Run(PrivacyDecision decision, int count) {}

    /**
     * Folds consecutive identical decisions and returns the newest {@code limit} runs.
     *
     * <p>Two decisions are the same run when surface, subject and reason match; the timestamp and
     * action are ignored so a re-evaluation that flipped the action still shows up as its own run.
     *
     * @param events ledger events in recording order (oldest first), may be null or empty
     * @param limit  maximum number of runs to return; {@code <= 0} yields an empty list
     * @return runs newest-first, at most {@code limit} of them
     */
    public static List<Run> fold(List<PrivacyDecision> events, int limit) {
        List<Run> runs = new ArrayList<>();
        if (events == null || events.isEmpty() || limit <= 0) return runs;

        PrivacyDecision current = null;
        int count = 0;
        for (int i = events.size() - 1; i >= 0; i--) {
            PrivacyDecision decision = events.get(i);
            if (decision == null) continue;
            if (current != null && sameRun(current, decision)) {
                count++;
                continue;
            }
            if (current != null) {
                runs.add(new Run(current, count));
                if (runs.size() >= limit) return runs;
            }
            current = decision;
            count = 1;
        }
        if (current != null && runs.size() < limit) runs.add(new Run(current, count));
        return runs;
    }

    private static boolean sameRun(PrivacyDecision a, PrivacyDecision b) {
        return a.surface() == b.surface()
                && a.action() == b.action()
                && Objects.equals(a.subject(), b.subject())
                && Objects.equals(a.reason(), b.reason());
    }
}
