package net.nulltrace.mod.util;

import net.minecraft.client.Minecraft;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.proxy.ProxySettings;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Builds the HTTP clients NullTrace uses for its own outbound requests (update check, jar
 * integrity check, account endpoints), honouring the same proxy configuration as the rest of
 * the game.
 *
 * <p>Before this existed, {@code UpdateChecker} and {@code JarIntegrityChecker} created bare
 * {@code HttpClient}s with no proxy at all: a player who routed the game through a proxy still
 * had the launcher's IP handed to GitLab and to the CurseForge metadata API on every start —
 * from a privacy mod. The proxy decision now has exactly one source of truth,
 * {@link ProxySettings#activeProxy()}:
 *
 * <ul>
 *   <li>{@code DIRECT} → {@link Proxy#NO_PROXY}</li>
 *   <li>{@code LAUNCHER} → whatever {@link Minecraft#getProxy()} reports (unchanged behaviour)</li>
 *   <li>{@code SELECTED} → the chosen endpoint, or {@code NO_PROXY} if it no longer exists</li>
 * </ul>
 */
public final class HttpClients {
    private HttpClients() {}

    /**
     * Shared client. Cached because building one is not free, and invalidated by
     * {@link #reset()} whenever the proxy configuration changes — a client that outlives a
     * proxy switch keeps leaking the player's address by the old route.
     */
    private static volatile HttpClient client;
    private static volatile Duration cachedTimeout;
    private static volatile boolean cachedFollowRedirects;

    /** An outbound client with the configured proxy, redirects left to the caller's policy. */
    public static HttpClient outbound(Duration connectTimeout, boolean followRedirects) {
        HttpClient existing = client;
        if (existing != null
                && connectTimeout.equals(cachedTimeout)
                && followRedirects == cachedFollowRedirects) {
            return existing;
        }
        synchronized (HttpClients.class) {
            if (client == null
                    || !connectTimeout.equals(cachedTimeout)
                    || followRedirects != cachedFollowRedirects) {
                HttpClient.Builder builder = HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(followRedirects
                                ? HttpClient.Redirect.NORMAL
                                : HttpClient.Redirect.NEVER);
                builder.proxy(new FixedProxySelector(resolveProxy()));
                client = builder.build();
                cachedTimeout = connectTimeout;
                cachedFollowRedirects = followRedirects;
            }
            return client;
        }
    }

    /**
     * Drop the cached client so the next request is built from the current proxy settings.
     * Called when the configuration changes the proxy mode, the selected endpoint or the list.
     */
    public static void reset() {
        synchronized (HttpClients.class) {
            client = null;
            cachedTimeout = null;
            cachedFollowRedirects = false;
        }
    }

    /** The proxy every outbound request should use; never null. */
    public static Proxy resolveProxy() {
        Proxy configured = NullTraceConfig.getInstance().proxySettings().activeProxy();
        if (configured != null) return configured;
        // LAUNCHER mode: leave the decision to the value Minecraft was started with.
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            try {
                Proxy launcher = client.getProxy();
                if (launcher != null) return launcher;
            } catch (RuntimeException e) {
                // Early startup or a broken game config: fall back to a direct connection
                // rather than failing an update check.
            }
        }
        return Proxy.NO_PROXY;
    }

    /** A one-entry selector: Java's HTTP stack only accepts a selector, not a single proxy. */
    public static final class FixedProxySelector extends ProxySelector {
        private final Proxy proxy;

        public FixedProxySelector(Proxy proxy) {
            this.proxy = proxy == null ? Proxy.NO_PROXY : proxy;
        }

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(proxy);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, java.io.IOException ioe) {
            // Nothing to do: the caller logs the failed request with its own context.
        }
    }
}
