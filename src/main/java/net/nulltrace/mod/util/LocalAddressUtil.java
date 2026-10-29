package net.nulltrace.mod.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Local/private address detection for resource-pack URLs, and the current server address.
 *
 * <p>Lives in the common source set because it is pure JDK code: that keeps it unit-testable
 * (see {@code LocalAddressUtilTest}) instead of only reachable from a running client.
 *
 * <p>Checks an explicit list of ranges rather than relying on
 * {@link InetAddress#isSiteLocalAddress()} alone, which only covers the three RFC 1918 blocks and
 * the deprecated IPv6 site-local prefix. The following are treated as local/private:
 *
 * <table>
 *   <caption>Ranges rejected</caption>
 *   <tr><td>{@code 0.0.0.0/8}</td><td>"this network" — only the single address 0.0.0.0 is matched by {@code isAnyLocalAddress()}</td></tr>
 *   <tr><td>{@code 127.0.0.0/8}, {@code ::1}</td><td>loopback</td></tr>
 *   <tr><td>{@code 10/8}, {@code 172.16/12}, {@code 192.168/16}</td><td>RFC 1918 (LAN)</td></tr>
 *   <tr><td>{@code 169.254/16}, {@code fe80::/10}</td><td>link-local</td></tr>
 *   <tr><td>{@code 100.64.0.0/10}</td><td>CGNAT / carrier NAT — common on VPS and cloud networks</td></tr>
 *   <tr><td>{@code 224/4}, {@code ff00::/8}</td><td>multicast</td></tr>
 *   <tr><td>{@code fc00::/7}</td><td>IPv6 unique-local (UA); {@code isSiteLocalAddress()} misses it</td></tr>
 *   <tr><td>{@code ::ffff:a.b.c.d}</td><td>IPv4-mapped IPv6 — re-checked against the IPv4 ranges</td></tr>
 * </table>
 *
 * <p><b>Known limitation.</b> This resolves a host name and returns; the HTTP stack resolves it
 * again when it connects, so a DNS answer with a very short TTL can in principle pass this check
 * as public and connect as loopback (DNS rebinding). Over {@code https://} the connection fails
 * certificate validation anyway, so the realistic vector is a plain-{@code http://} redirect hop.
 * Pinning the connection to the checked address is the fix and is not implemented here.
 */
public class LocalAddressUtil {

    /**
     * Current server address, set on connect, cleared on disconnect.
     * Used to skip local address blocking when already connected to a local server.
     */
    public static volatile String serverAddress = null;

    /** Convenience: any resolution of {@code host} being local/private makes the host local. */
    public static boolean isLocalAddress(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) return false;
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (isLocalAddress(address)) return true;
        }
        return false;
    }

    /**
     * True for ranges a remote server must never be able to point a client at, regardless of
     * where that server itself lives: loopback, unspecified, link-local, multicast and reserved
     * space. Unlike {@link #isLocalAddress(InetAddress)} this excludes RFC 1918 / ULA / CGNAT,
     * which are reachable on a LAN where the server legitimately lives too.
     */
    public static boolean isNeverServerReachable(InetAddress address) {
        if (address == null) return false;
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        int offset = bytes.length == 16 && isIpv4Mapped(bytes) ? 12 : 0;
        if (bytes.length == 4 || offset == 12) {
            int a = bytes[offset] & 0xFF;
            int b = bytes[offset + 1] & 0xFF;
            if (a == 0) return true;              // 0.0.0.0/8 "this network"
            if (a == 127) return true;            // loopback
            if (a == 169 && b == 254) return true; // link-local
            if (a >= 224) return true;            // multicast + reserved + broadcast
            return false;
        }
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        if (first == 0xFF) return true;                        // ff00::/8 multicast
        if (first == 0xFE && (second & 0xC0) == 0x80) return true; // fe80::/10 link-local
        // ::1 loopback is covered by isLoopbackAddress above.
        return false;
    }

    /**
     * Whether a server-directed URL target should be blocked.
     *
     * <p>This is the policy the resource-pack guard uses, and it exists because the previous
     * check short-circuited on "the game server is itself on a private address". That turned
     * every local-address guard off for anyone playing on a LAN world or a home server, so a
     * server on {@code 192.168.x.x} could point the client at {@code http://127.0.0.1:<port>/}
     * and read the result. Loopback, link-local and multicast are never legitimate pack hosts;
     * RFC 1918 / ULA / CGNAT targets are only tolerated when the server shares that network.
     *
     * <p>An unresolvable or empty host is not blocked here: the connection will fail on its own,
     * and DNS answers are re-checked for each redirect hop. One spelling of a resolvable host is
     * normalised first: a trailing dot (as in {@code 127.0.0.1.}) is stripped, because the JDK's
     * resolver rejects it outright — which used to make this predicate fail <em>open</em> rather
     * than blocking a loopback literal spelled with an FQDN root dot. Nothing here depends on the
     * connect path failing after the check, so the dot must not be able to reach it.
     *
     * <p>Deliberate asymmetry, so it does not get "fixed" by accident: {@link #isNeverServerReachable}
     * rejects loopback/link-local/multicast/reserved but not CGNAT ({@code 100.64/10}) or the
     * benchmarking block ({@code 198.18/15}), while {@link #isLocalAddress(InetAddress)} rejects both.
     * So a server on {@code 192.168.x.x} may point the client at {@code 100.64.x.x}: some CGNAT
     * ranges are legitimately reachable from a LAN, and the target is not a third party there.
     *
     * @param serverAddress address of the game server (an IP literal, may be null)
     * @param targetHost    host of the URL the server asked the client to fetch
     */
    public static boolean isBlockedServerPackTarget(String serverAddress, String targetHost) {
        if (targetHost == null || targetHost.isBlank()) return false;

        InetAddress[] targets;
        try {
            targets = InetAddress.getAllByName(stripBrackets(stripRootDot(targetHost.trim())));
        } catch (UnknownHostException e) {
            return false;
        }

        boolean serverIsLocal = isLocalServer(serverAddress);
        for (InetAddress target : targets) {
            if (isNeverServerReachable(target)) return true;
            if (!serverIsLocal && isLocalAddress(target)) return true;
        }
        return false;
    }

    private static boolean isLocalServer(String serverAddress) {
        if (serverAddress == null || serverAddress.isBlank()) return false;
        try {
            return isLocalAddress(serverAddress);
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /**
     * Drops trailing dots from an FQDN-root spelling ({@code example.com.}, {@code 127.0.0.1.}).
     * Only used on the resolution path — {@link #normalizeHost(String)} keeps doing the
     * lowercasing used for profile keys.
     */
    private static String stripRootDot(String host) {
        int end = host.length();
        while (end > 0 && host.charAt(end - 1) == '.') {
            end--;
        }
        return host.substring(0, end);
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        if (bytes.length != 16) return false;
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return false;
        }
        return (bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF)
                || (bytes[10] == 0 && bytes[11] == 0);
    }

    /**
     * True when a single resolved address belongs to a local, private, carrier-NAT, loopback,
     * link-local or multicast range.
     */
    public static boolean isLocalAddress(InetAddress address) {
        if (address == null) return false;
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isLocalIpv4(bytes, 0);
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xFF;
            // IPv6 unique-local fc00::/7
            if ((first & 0xFE) == 0xFC) return true;
            // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d) forms.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped
                    && ((bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF)
                        || (bytes[10] == 0 && bytes[11] == 0))) {
                return isLocalIpv4(bytes, 12);
            }
        }
        return false;
    }

    /** {@code a.b.c.d} starting at {@code offset} in {@code bytes}. */
    private static boolean isLocalIpv4(byte[] bytes, int offset) {
        int a = bytes[offset] & 0xFF;
        int b = bytes[offset + 1] & 0xFF;

        // 0.0.0.0/8 "this network"
        if (a == 0) return true;
        // 10/8
        if (a == 10) return true;
        // 100.64.0.0/10 CGNAT
        if (a == 100 && b >= 64 && b <= 127) return true;
        // 127/8 loopback (also covered by isLoopbackAddress when parsed, not when raw)
        if (a == 127) return true;
        // 169.254/16 link-local
        if (a == 169 && b == 254) return true;
        // 172.16/12
        if (a == 172 && b >= 16 && b <= 31) return true;
        // 192.168/16
        if (a == 192 && b == 168) return true;
        // 198.18.0.0/15 benchmarking
        if (a == 198 && (b == 18 || b == 19)) return true;
        // 224/4 multicast, 240/4 reserved, and 255.255.255.255 broadcast
        return a >= 224;
    }

    /** Normalizes a host string for logging and profile keys (lowercase, no trailing dot). */
    public static String normalizeHost(String host) {
        if (host == null) return "";
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /** True when the string parses as a literal IPv4/IPv6 address (no DNS involved). */
    public static boolean isLiteralAddress(String host) {
        if (host == null || host.isBlank()) return false;
        String text = host.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        if (text.isEmpty()) return false;
        // InetAddress.getByName resolves names, so only accept inputs that cannot be host names.
        boolean looksV4 = text.chars().allMatch(ch -> (ch >= '0' && ch <= '9') || ch == '.');
        boolean looksV6 = text.indexOf(':') >= 0
                && text.chars().allMatch(ch -> Character.digit(ch, 16) >= 0 || ch == ':' || ch == '.');
        if (!looksV4 && !looksV6) return false;
        try {
            InetAddress address = InetAddress.getByName(text);
            return address instanceof Inet4Address || address instanceof Inet6Address;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
