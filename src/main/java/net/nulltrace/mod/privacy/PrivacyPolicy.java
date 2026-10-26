package net.nulltrace.mod.privacy;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Central, deterministic privacy policy.
 *
 * <p>Policies are immutable. A caller can therefore evaluate a packet on a
 * Netty thread without racing a config-screen mutation. Build a new policy
 * after changing settings.</p>
 */
public final class PrivacyPolicy {
    private final EnumMap<PrivacySurface, PrivacyAction> actions;
    private final Set<String> allowedChannels;
    private final boolean maximumPrivacy;

    private PrivacyPolicy(Builder builder) {
        this.actions = new EnumMap<>(PrivacySurface.class);
        this.actions.putAll(builder.actions);
        this.allowedChannels = Set.copyOf(builder.allowedChannels);
        this.maximumPrivacy = builder.maximumPrivacy;
    }

    public static Builder builder() { return new Builder(); }

    public static PrivacyPolicy maximumPrivacyPolicy() {
        Builder builder = builder().maximumPrivacy(true);
        for (PrivacySurface surface : PrivacySurface.values()) {
            builder.action(surface, PrivacyAction.BLOCK);
        }
        // Everything, including MOD_CHANNEL: the maximum-privacy preset is the "we will not
        // speak first" profile. Vanilla channels are handled outside this surface, so the
        // preset does not make the client unable to talk to a vanilla server.
        return builder.build();
    }

    public boolean maximumPrivacy() { return maximumPrivacy; }

    public PrivacyAction actionFor(PrivacySurface surface) {
        return actions.getOrDefault(surface, PrivacyAction.LOG);
    }

    public boolean allowsChannel(String channel) {
        return channel != null && allowedChannels.contains(channel);
    }

    public PrivacyDecision evaluate(PrivacySurface surface, String subject) {
        PrivacyAction action = actionFor(surface);
        if (surface == PrivacySurface.MOD_CHANNEL && allowsChannel(subject)) {
            action = PrivacyAction.ALLOW;
        }
        String reason = switch (action) {
            case ALLOW -> "Allowed by the active NullTrace policy";
            case WARN -> "Allowed, but recorded because the surface may identify the client";
            case BLOCK -> "Blocked by the active NullTrace policy";
            case LOG -> "Recorded for audit; no blocking rule is active";
        };
        return PrivacyDecision.of(surface, subject, action, reason);
    }

    public Map<PrivacySurface, PrivacyAction> actions() {
        return Map.copyOf(actions);
    }

    public static final class Builder {
        private final EnumMap<PrivacySurface, PrivacyAction> actions = new EnumMap<>(PrivacySurface.class);
        private final Set<String> allowedChannels = new java.util.LinkedHashSet<>();
        private boolean maximumPrivacy;

        public Builder action(PrivacySurface surface, PrivacyAction action) {
            if (surface != null && action != null) actions.put(surface, action);
            return this;
        }

        public Builder allowChannel(String channel) {
            if (channel != null && !channel.isBlank()) allowedChannels.add(channel);
            return this;
        }

        public Builder allowChannels(Iterable<String> channels) {
            if (channels != null) for (String channel : channels) allowChannel(channel);
            return this;
        }

        public Builder maximumPrivacy(boolean value) {
            maximumPrivacy = value;
            return this;
        }

        public PrivacyPolicy build() { return new PrivacyPolicy(this); }
    }
}
