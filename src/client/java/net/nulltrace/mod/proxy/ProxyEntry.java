package net.nulltrace.mod.proxy;

import com.google.gson.JsonObject;
import net.nulltrace.mod.lang.NullTraceStrings;

import java.net.InetSocketAddress;
import java.util.Locale;

/**
 * One saved proxy endpoint.
 *
 * <p>Immutable and serializable. Entries never carry credentials: Java's HTTP stack
 * cannot be given proxy credentials for connections Minecraft opens itself (the game
 * installs a no-op {@code Authenticator} on every connection it makes), and storing a
 * password that is silently ignored is worse than not accepting one. {@link #parse}
 * therefore rejects URLs containing user info with an explicit error instead of
 * dropping the credentials quietly.
 *
 * <p>Host names are deliberately kept unresolved ({@link InetSocketAddress#createUnresolved})
 * so a SOCKS proxy resolves the target name itself rather than the client resolving it
 * over plain DNS first.
 */
public final class ProxyEntry {
    public enum Type {
        /** An HTTP proxy; used as a CONNECT tunnel for https:// traffic. */
        HTTP,
        /** A SOCKS proxy (SOCKS5 in practice). */
        SOCKS
    }

    /** Scheme prefix shown in the UI, also used as the id prefix. */
    private static final String HTTP_SCHEME = "http://";
    private static final String SOCKS_SCHEME = "socks5://";

    private final Type type;
    private final String host;
    private final int port;

    public ProxyEntry(Type type, String host, int port) {
        this.type = type == null ? Type.SOCKS : type;
        this.host = host == null ? "" : host.trim();
        this.port = port;
    }

    public Type type() { return type; }
    public String host() { return host; }
    public int port() { return port; }

    /** Stable identity: also the text shown in the list. */
    public String id() {
        return (type == Type.HTTP ? HTTP_SCHEME : SOCKS_SCHEME) + host + ":" + port;
    }

    public String display() { return id(); }

    public boolean isValid() {
        return !host.isBlank() && port > 0 && port <= 65535;
    }

    /** The {@link java.net.Proxy} to hand to Minecraft's HTTP layers. */
    public java.net.Proxy toProxy() {
        return new java.net.Proxy(
                type == Type.HTTP ? java.net.Proxy.Type.HTTP : java.net.Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved(host, port));
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", type.name());
        json.addProperty("host", host);
        json.addProperty("port", port);
        return json;
    }

    public static ProxyEntry fromJson(JsonObject json) {
        if (json == null) return null;
        Type parsedType = Type.SOCKS;
        if (json.has("type")) {
            try {
                parsedType = Type.valueOf(json.get("type").getAsString().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Unknown type in an older/hand-edited file: fall back to SOCKS.
            }
        }
        String parsedHost = json.has("host") ? json.get("host").getAsString() : "";
        int parsedPort = json.has("port") ? json.get("port").getAsInt() : 0;
        ProxyEntry entry = new ProxyEntry(parsedType, parsedHost, parsedPort);
        return entry.isValid() ? entry : null;
    }

    /** Result of {@link #parse}: either an entry or a {@code nulltrace.proxy.error.*} key. */
    public record ParseResult(ProxyEntry entry, String errorKey) {
        public boolean ok() { return entry != null; }
    }

    /**
     * Parses user input. Accepted forms:
     *
     * <pre>
     *   host:port                  - SOCKS5 (the useful default for Minecraft)
     *   socks5://host:port         - SOCKS5
     *   socks://host:port          - SOCKS5
     *   http://host:port           - HTTP proxy
     *   https://host:port          - treated as an HTTP proxy
     *   [2001:db8::1]:1080         - IPv6 literal
     * </pre>
     *
     * @param raw the text from the input box
     * @return the entry, or an error key from {@code NullTraceStrings}
     */
    public static ParseResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParseResult(null, NullTraceStrings.PROXY_ERROR_EMPTY);
        }
        String text = raw.trim();

        // Credentials are not supported and must not be silently discarded.
        if (text.contains("@")) {
            return new ParseResult(null, NullTraceStrings.PROXY_ERROR_CREDENTIALS);
        }

        Type type = Type.SOCKS;
        int schemeEnd = text.indexOf("://");
        if (schemeEnd >= 0) {
            String scheme = text.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            text = text.substring(schemeEnd + 3);
            switch (scheme) {
                case "http", "https" -> type = Type.HTTP;
                case "socks", "socks5", "socks5h" -> type = Type.SOCKS;
                default -> {
                    return new ParseResult(null, NullTraceStrings.PROXY_ERROR_SCHEME);
                }
            }
        }

        // A path, query or fragment means the user pasted a full URL; say so precisely instead
        // of failing later on "the port looks wrong" for something like host:8080/path?token=x.
        if (text.contains("/") || text.contains("?") || text.contains("#")) {
            return new ParseResult(null, NullTraceStrings.PROXY_ERROR_PATH);
        }

        String host;
        String portText;
        if (text.startsWith("[")) {
            // IPv6 literal: [addr]:port
            int close = text.indexOf(']');
            if (close < 0) {
                return new ParseResult(null, NullTraceStrings.PROXY_ERROR_HOST);
            }
            host = text.substring(1, close);
            String remainder = text.substring(close + 1);
            if (!remainder.startsWith(":")) {
                return new ParseResult(null, NullTraceStrings.PROXY_ERROR_PORT);
            }
            portText = remainder.substring(1);
        } else {
            int colon = text.lastIndexOf(':');
            if (colon < 0) {
                return new ParseResult(null, NullTraceStrings.PROXY_ERROR_PORT);
            }
            if (colon == 0) {
                // ":1080" — the port is there, the host is missing. Saying "include a port"
                // would send the user looking in the wrong place.
                return new ParseResult(null, NullTraceStrings.PROXY_ERROR_HOST);
            }
            host = text.substring(0, colon);
            portText = text.substring(colon + 1);
        }

        host = host.trim();
        portText = portText.trim();
        if (host.isEmpty() || host.length() > 255
                || host.contains("/") || host.contains(" ") || host.contains("\t")) {
            return new ParseResult(null, NullTraceStrings.PROXY_ERROR_HOST);
        }
        if (portText.isEmpty()) {
            return new ParseResult(null, NullTraceStrings.PROXY_ERROR_PORT);
        }

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            return new ParseResult(null, NullTraceStrings.PROXY_ERROR_PORT);
        }
        if (port <= 0 || port > 65535) {
            return new ParseResult(null, NullTraceStrings.PROXY_ERROR_PORT);
        }

        return new ParseResult(new ProxyEntry(type, host, port), null);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ProxyEntry entry)) return false;
        return port == entry.port && type == entry.type && host.equals(entry.host);
    }

    @Override
    public int hashCode() {
        return id().hashCode();
    }

    @Override
    public String toString() { return id(); }
}
