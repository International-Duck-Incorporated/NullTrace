import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal in-process proxy endpoints used by {@link ProxyProbeHarness}.
 *
 * <p>Java rather than Python so the harness needs nothing but a JDK on the machine — that is what
 * lets it run in the existing GitLab CI test stage (see {@code .gitlab-ci.yml}). Each endpoint
 * implements exactly the handshake {@code ProxyTester} performs and nothing more, so the harness
 * tests the probe logic and the failure paths, not a proxy implementation.
 */
public final class FakeProxyServers implements Closeable {
    private final List<ServerSocket> sockets = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();

    /** Accepts a CONNECT and answers 200, like a working HTTP proxy. */
    public int httpConnectProxy() { return serve((socket, in, out) -> { socket.setSoTimeout(4000); readHeaders(in); out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)); out.flush(); }); }

    /** A SOCKS5 endpoint that accepts the no-authentication greeting. */
    public int socks5NoAuthProxy() { return serve((socket, in, out) -> { socket.setSoTimeout(4000); byte[] greeting = in.readNBytes(3); if (greeting.length == 3 && greeting[0] == 0x05 && greeting[1] == 0x01) { out.write(new byte[] {0x05, 0x00}); out.flush(); } }); }

    /** A SOCKS5 endpoint that demands authentication; NullTrace cannot supply credentials. */
    public int socks5AuthRequiredProxy() { return serve((socket, in, out) -> { socket.setSoTimeout(4000); byte[] greeting = in.readNBytes(3); if (greeting.length == 3 && greeting[0] == 0x05 && greeting[1] == 0x01) { out.write(new byte[] {0x05, (byte) 0xFF}); out.flush(); } }); }

    /** An endpoint that accepts the connection and then says nothing, testing the read timeout. */
    public int silentEndpoint() { return serve((socket, in, out) -> { socket.setSoTimeout(4000); try { Thread.sleep(6000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }); }

    /** An HTTP endpoint that answers with a non-200 CONNECT status. */
    public int refusingHttpProxy() { return serve((socket, in, out) -> { socket.setSoTimeout(4000); readHeaders(in); out.write("HTTP/1.1 403 Forbidden\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)); out.flush(); }); }

    /** A free port with nothing listening on it. */
    public static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("could not reserve a free port", e);
        }
    }

    private interface Handler {
        void handle(Socket socket, InputStream in, OutputStream out) throws Exception;
    }

    private int serve(Handler handler) {
        try {
            ServerSocket server = new ServerSocket(0);
            sockets.add(server);
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        handler.handle(socket, socket.getInputStream(), socket.getOutputStream());
                    } catch (Exception e) {
                        // A client that goes away mid-handshake is not a test failure.
                    }
                }
            }, "fake-proxy-" + server.getLocalPort());
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
            return server.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("could not start fake proxy", e);
        }
    }

    /** Reads until the end of the request headers so the client does not see a reset. */
    private static void readHeaders(InputStream in) throws IOException {
        StringBuilder seen = new StringBuilder();
        int read;
        while ((read = in.read()) != -1) {
            seen.append((char) read);
            if (seen.length() >= 4 && seen.substring(seen.length() - 4).equals("\r\n\r\n")) return;
        }
    }

    @Override
    public void close() {
        for (ServerSocket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing best-effort.
            }
        }
    }
}
