package net.nulltrace.mod.mixin.client;

//? if >=1.20.2 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.nulltrace.mod.NullTrace;
import net.nulltrace.mod.PrivacyLogger;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.util.LocalAddressUtil;
import net.minecraft.util.HttpUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import net.nulltrace.mod.privacy.PrivacyAction;
import net.nulltrace.mod.privacy.PrivacyAudit;
import net.nulltrace.mod.privacy.PrivacySurface;
import java.util.Map;

/**
 * Vanilla-aligned redirect handling with per-hop local-address rejection.
 *
 * <p>Follows 300/301/302/303/307. 305 is re-issued through the named proxy via
 * {@link Proxy.Type#HTTP}. Cross-protocol redirects are rejected.
 *
 * <p>Each new redirect connection sets a unique {@link Authenticator}. JDK's
 * {@code HttpClient.New} compatibility check rejects any cached
 * {@code HttpClient} whose {@code AuthCache} does not match the new
 * connection's, closing the cached socket and creating a fresh one — so each
 * hop uses a fresh TCP socket, matching vanilla MC's
 * {@code setInstanceFollowRedirects(true)} behavior. The {@code Authenticator}
 * is never invoked unless the server responds with 401/407.
 */
@Mixin(HttpUtil.class)
public class HttpUtilMixin {

    @SuppressWarnings("deprecation")
    @WrapOperation(
        //? if >=1.20.3 {
        method = "downloadFile",
        //?} else {
        /*// 1.20.1 / 1.20.2: HttpUtil.downloadFile doesn't exist yet — the actual HTTP work
        // lives in a private static synthetic method (the CompletableFuture.supplyAsync
        // lambda body of downloadTo). Mojang's official mappings don't cover this synthetic,
        // so Loom falls back to the Yarn intermediary name "method_15303". We list
        // "lambda$downloadTo$0" as a secondary candidate in case a future toolchain rev
        // picks the Java synthetic name instead — the @At INVOKE selector ensures only
        // one of them binds.
        method = {"method_15303", "lambda$downloadTo$0"},
        *///?}
        at = @At(value = "INVOKE", target = "Ljava/net/HttpURLConnection;getInputStream()Ljava/io/InputStream;"),
        require = 1
    )
    private static InputStream nulltrace$downloadFile(
            HttpURLConnection instance,
            Operation<InputStream> original,
            @Local(argsOnly = true) Proxy proxy,
            @Local(argsOnly = true) Map<String, String> requestProperties,
            @Local LocalRef<HttpURLConnection> httpURLConnection) throws IOException {

        // Resource-pack hosts/CDNs do not need the player's account identity.
        // Remove the two directly identifying headers before the first request and
        // from the mutable redirect-header map so they cannot be restored on hops.
        nulltrace$scrubIdentityHeaders(instance, requestProperties);

        if (!NullTraceConfig.getInstance().shouldBlockLocalPackUrls()) return original.call(instance);

        if (!LocalAddressUtil.isLocalAddress(LocalAddressUtil.serverAddress)
                && LocalAddressUtil.isLocalAddress(instance.getURL().getHost())) {
            NullTrace.LOGGER.warn("[NullTrace] Blocked connection to local address: {}", instance.getURL());
            PrivacyAudit.record(PrivacySurface.LOCAL_REQUEST, instance.getURL().toString(),
                    PrivacyAction.BLOCK, "Resource-pack URL resolves to a local or private address");
            PrivacyLogger.alertLocalPortScanDetected(instance.getURL().toString(), true);
            throw new IllegalStateException("Tried to connect to local address!");
        }

        instance.setInstanceFollowRedirects(false);

        int redirects = 0;
        String maxRedirectString = System.getProperty("http.maxRedirects") == null
            ? "20" : System.getProperty("http.maxRedirects");
        int maxRedirects = 20;
        try { maxRedirects = Math.max(Integer.parseInt(maxRedirectString), 1); }
        catch (NumberFormatException ignored) {}

        int status = instance.getResponseCode();

        while (instance.getHeaderField("Location") != null
                && (status == 300 || status == 301 || status == 302 || status == 303 || status == 305 || status == 307)) {
            if (redirects >= maxRedirects - 1) {
                // Never reproduce vanilla's raw Socket side effect here. The old
                // compatibility probe connected to the final Location before
                // throwing, which bypassed the local/private-address guard and
                // turned a redirect-limit check into an SSRF primitive.
                try {
                    URL limitUrl;
                    try {
                        limitUrl = new URL(instance.getHeaderField("Location"));
                    } catch (MalformedURLException exception) {
                        limitUrl = new URL(instance.getURL(), instance.getHeaderField("Location"));
                    }
                    if (!LocalAddressUtil.isLocalAddress(LocalAddressUtil.serverAddress)
                            && LocalAddressUtil.isLocalAddress(limitUrl.getHost())) {
                        PrivacyAudit.record(PrivacySurface.LOCAL_REQUEST, limitUrl.toString(),
                                PrivacyAction.BLOCK, "Redirect limit target is local or private");
                        PrivacyLogger.alertLocalPortScanDetected(limitUrl.toString(), true);
                    } else {
                        PrivacyAudit.record(PrivacySurface.RESOURCE_PACK, limitUrl.toString(),
                                PrivacyAction.BLOCK, "Redirect limit reached before opening the next hop");
                    }
                } catch (Exception ignored) {
                    PrivacyAudit.record(PrivacySurface.RESOURCE_PACK, "redirect-limit",
                            PrivacyAction.BLOCK, "Redirect limit reached before opening the next hop");
                }
                throw new ProtocolException("Server redirected too many times (" + maxRedirects + ")");
            }

            if (status == 305) {
                URL proxyUrl;
                try {
                    proxyUrl = new URL(instance.getHeaderField("Location"));
                } catch (MalformedURLException exception) {
                    try {
                        proxyUrl = new URL(instance.getURL(), instance.getHeaderField("Location"));
                    } catch (MalformedURLException ignored) {
                        break;
                    }
                }
                if (!proxyUrl.getProtocol().equalsIgnoreCase("http")
                        && !proxyUrl.getProtocol().equalsIgnoreCase("https")) break;

                if (!LocalAddressUtil.isLocalAddress(LocalAddressUtil.serverAddress)
                        && LocalAddressUtil.isLocalAddress(proxyUrl.getHost())) {
                    NullTrace.LOGGER.warn("[NullTrace] Blocked connection to local address: {}", proxyUrl);
                    PrivacyAudit.record(PrivacySurface.LOCAL_REQUEST, proxyUrl.toString(),
                            PrivacyAction.BLOCK, "HTTP proxy redirect resolves to a local or private address");
                    PrivacyLogger.alertLocalPortScanDetected(proxyUrl.toString(), true);
                    throw new IllegalStateException("Tried to connect to local address!");
                }

                int proxyPort = proxyUrl.getPort() == -1 ? proxyUrl.getDefaultPort() : proxyUrl.getPort();
                Proxy hopProxy = new Proxy(Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(proxyUrl.getHost(), proxyPort));
                URL originalUrl = instance.getURL();
                instance = (HttpURLConnection) originalUrl.openConnection(hopProxy);
                instance.setAuthenticator(new Authenticator() {});
            } else {
                URL url;
                try {
                    url = new URL(instance.getHeaderField("Location"));
                    if (!instance.getURL().getProtocol().equalsIgnoreCase(url.getProtocol())) break;
                } catch (MalformedURLException exception) {
                    url = new URL(instance.getURL(), instance.getHeaderField("Location"));
                }

                instance = (HttpURLConnection) url.openConnection(proxy);
                instance.setAuthenticator(new Authenticator() {});

                if (!LocalAddressUtil.isLocalAddress(LocalAddressUtil.serverAddress)
                        && LocalAddressUtil.isLocalAddress(instance.getURL().getHost())) {
                    NullTrace.LOGGER.warn("[NullTrace] Blocked connection to local address: {}", instance.getURL());
                    PrivacyAudit.record(PrivacySurface.LOCAL_REQUEST, instance.getURL().toString(),
                            PrivacyAction.BLOCK, "Redirect resolves to a local or private address");
                    PrivacyLogger.alertLocalPortScanDetected(instance.getURL().toString(), true);
                    throw new IllegalStateException("Tried to connect to local address!");
                }
            }

            instance.setInstanceFollowRedirects(false);
            requestProperties.forEach(instance::setRequestProperty);
            nulltrace$scrubIdentityHeaders(instance, requestProperties);

            status = instance.getResponseCode();
            redirects++;
        }

        httpURLConnection.set(instance);
        return original.call(instance);
    }

    private static void nulltrace$scrubIdentityHeaders(
            HttpURLConnection connection, Map<String, String> requestProperties) {
        requestProperties.remove("X-Minecraft-Username");
        requestProperties.remove("X-Minecraft-UUID");
        requestProperties.remove("x-minecraft-username");
        requestProperties.remove("x-minecraft-uuid");
        // HttpURLConnection may already have copied the map into its request
        // properties; replacing with an empty value is safer than leaving the
        // original value attached on redirect-created connections.
        connection.setRequestProperty("X-Minecraft-Username", "");
        connection.setRequestProperty("X-Minecraft-UUID", "");
    }
}
//?} else {
/*
import net.minecraft.network.protocol.PacketUtils;
import org.spongepowered.asm.mixin.Mixin;

// 1.20.1: Block Local URLs disabled (see MC_VERSION_HAS_BLOCK_LOCAL_URLS). Stub.
@Mixin(PacketUtils.class)
public class HttpUtilMixin {
}
*///?}
