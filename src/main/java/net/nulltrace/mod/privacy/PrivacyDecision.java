package net.nulltrace.mod.privacy;

import java.time.Instant;
import java.util.Objects;

/** A single explainable policy decision. */
public record PrivacyDecision(
        PrivacySurface surface,
        String subject,
        PrivacyAction action,
        String reason,
        Instant time
) {
    public PrivacyDecision {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(action, "action");
        subject = subject == null || subject.isBlank() ? "*" : subject;
        reason = reason == null || reason.isBlank() ? "No reason supplied" : reason;
        time = time == null ? Instant.now() : time;
    }

    public static PrivacyDecision of(PrivacySurface surface, String subject,
                                     PrivacyAction action, String reason) {
        return new PrivacyDecision(surface, subject, action, reason, Instant.now());
    }
}
