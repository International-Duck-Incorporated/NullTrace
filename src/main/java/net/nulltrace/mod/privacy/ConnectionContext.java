package net.nulltrace.mod.privacy;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Snapshot of the information relevant to a single server connection.
 *
 * <p>The context intentionally contains observations, not policy decisions.
 * This prevents a protection implementation from silently becoming the source
 * of truth for the audit system.</p>
 */
public final class ConnectionContext {
    private final String server;
    private final String protocol;
    private final Set<String> detectedMods;
    private final Set<String> exposedChannels;
    private final Set<String> knownPacks;
    private final Set<String> translations;
    private final Set<String> resourcePacks;
    private final Set<String> probes;

    private ConnectionContext(Builder builder) {
        this.server = normalize(builder.server);
        this.protocol = normalize(builder.protocol);
        this.detectedMods = immutable(builder.detectedMods);
        this.exposedChannels = immutable(builder.exposedChannels);
        this.knownPacks = immutable(builder.knownPacks);
        this.translations = immutable(builder.translations);
        this.resourcePacks = immutable(builder.resourcePacks);
        this.probes = immutable(builder.probes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(String server) {
        return new Builder().server(server);
    }

    public String server() { return server; }
    public String protocol() { return protocol; }
    public Set<String> detectedMods() { return detectedMods; }
    public Set<String> exposedChannels() { return exposedChannels; }
    public Set<String> knownPacks() { return knownPacks; }
    public Set<String> translations() { return translations; }
    public Set<String> resourcePacks() { return resourcePacks; }
    public Set<String> probes() { return probes; }

    public static final class Builder {
        private String server = "unknown";
        private String protocol = "unknown";
        private final Set<String> detectedMods = new LinkedHashSet<>();
        private final Set<String> exposedChannels = new LinkedHashSet<>();
        private final Set<String> knownPacks = new LinkedHashSet<>();
        private final Set<String> translations = new LinkedHashSet<>();
        private final Set<String> resourcePacks = new LinkedHashSet<>();
        private final Set<String> probes = new LinkedHashSet<>();

        public Builder server(String value) { this.server = value; return this; }
        public Builder protocol(String value) { this.protocol = value; return this; }
        public Builder detectedMods(Iterable<String> values) { add(this.detectedMods, values); return this; }
        public Builder exposedChannels(Iterable<String> values) { add(this.exposedChannels, values); return this; }
        public Builder knownPacks(Iterable<String> values) { add(this.knownPacks, values); return this; }
        public Builder translations(Iterable<String> values) { add(this.translations, values); return this; }
        public Builder resourcePacks(Iterable<String> values) { add(this.resourcePacks, values); return this; }
        public Builder probes(Iterable<String> values) { add(this.probes, values); return this; }
        public ConnectionContext build() { return new ConnectionContext(this); }

        private static void add(Set<String> destination, Iterable<String> values) {
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.isBlank()) destination.add(value);
                }
            }
        }
    }

    private static Set<String> immutable(Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
