package net.nulltrace.mod.proxy;

import net.nulltrace.mod.NullTrace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Reachability probe for a saved proxy, run off the render thread.
 *
 * <p>What it proves:
 * <ul>
 *   <li>{@link ProxyEntry.Type#HTTP}: the endpoint accepted a TCP connection and answered a
 *       {@code CONNECT} tunnel request with {@code 200}. That is the exact operation the
 *       game needs for {@code https://} traffic, so a pass means the proxy works for
 *       Mojang/Microsoft and pack-download HTTPS requests.</li>
 *   <li>{@link ProxyEntry.Type#SOCKS}: the endpoint completed a SOCKS5 greeting
 *       (no-authentication). A proxy that requires authentication fails here, which is
 *       intentional — NullTrace cannot supply SOCKS credentials.</li>
 * </ul>
 *
 * <p>What it does not prove: that the proxy is trustworthy, fast, or that it can reach a
 * specific host. Nothing is sent except the probe handshake, and no credentials are involved.
 */
public final class ProxyTester {
    private static final int TIMEOUT_MS = 4000;
    /** Host used in the HTTP CONNECT probe; only the tunnel establishment is checked. */
    private static final String PROBE_HOST = "www.minecraft.net";
    private static final int PROBE_PORT = 443;

    private ProxyTester() {}

    /** Outcome of one probe. {@code detail} is a short technical string for logs/tooltips. */
    public record Result(boolean ok, long latencyMs, String detail) {}

    public static CompletableFuture<Result> test(ProxyEntry entry) {
        return CompletableFuture.supplyAsync(() -> {
            if (entry == null || !entry.isValid()) {
                return new Result(false, -1, "no proxy selected");
            }
            long started = System.nanoTime();
            try {
                return entry.type() == ProxyEntry.Type.HTTP
                        ? probeHttp(entry, started)
                        : probeSocks(entry, started);
            } catch (Exception e) {
                NullTrace.LOGGER.debug("[NullTrace] Proxy probe failed for {}: {}", entry.id(), e.getMessage());
                return new Result(false, elapsed(started), e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private static Result probeHttp(ProxyEntry entry, long started) throws IOException {
        try (Socket socket = connect(entry)) {
            OutputStream out = socket.getOutputStream();
            String request = "CONNECT " + PROBE_HOST + ":" + PROBE_PORT + " HTTP/1.1\r\n"
                    + "Host: " + PROBE_HOST + ":" + PROBE_PORT + "\r\n"
                    + "User-Agent: NullTrace/" + NullTrace.getVersion() + "\r\n"
                    + "\r\n";
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            long latency = elapsed(started);
            if (statusLine == null) {
                return new Result(false, latency, "no response to CONNECT");
            }
            boolean ok = statusLine.contains(" 200");
            return new Result(ok, latency, statusLine.trim());
        }
    }

    private static Result probeSocks(ProxyEntry entry, long started) throws IOException {
        try (Socket socket = connect(entry)) {
            OutputStream out = socket.getOutputStream();
            // SOCKS5 greeting: version 5, one method offered, method 0x00 (no auth).
            out.write(new byte[] {0x05, 0x01, 0x00});
            out.flush();

            byte[] reply = new byte[2];
            int read = socket.getInputStream().read(reply);
            long latency = elapsed(started);
            if (read < 2) {
                return new Result(false, latency, "truncated SOCKS5 greeting");
            }
            if (reply[0] != 0x05) {
                return new Result(false, latency, "not a SOCKS5 endpoint");
            }
            if (reply[1] == 0x00) {
                return new Result(true, latency, "SOCKS5, no authentication required");
            }
            if (reply[1] == (byte) 0xFF) {
                return new Result(false, latency, "SOCKS5 proxy rejected the offered method (authentication required?)");
            }
            return new Result(false, latency, "SOCKS5 proxy chose unsupported method 0x"
                    + Integer.toHexString(reply[1] & 0xFF));
        }
    }

    private static Socket connect(ProxyEntry entry) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(entry.host(), entry.port()), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            return socket;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing a failed socket is best-effort.
            }
            throw e;
        }
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
