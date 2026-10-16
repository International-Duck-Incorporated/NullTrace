package net.nulltrace.mod.util;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Range coverage for the resource-pack address guard.
 *
 * <p>The ranges below are the ones the guard used to miss: IPv6 unique-local, CGNAT, the
 * "this network" block and multicast/reserved space. Every case is a literal address, so the
 * test performs no DNS lookups and is deterministic in CI.
 */
class LocalAddressUtilTest {

    private static InetAddress literal(String address) throws UnknownHostException {
        return InetAddress.getByName(address);
    }

    // ------------------------------------------------------------------ blocked ranges

    @Test
    void ipv4RangesAreBlocked() throws Exception {
        String[] blocked = {
                "0.0.0.0",         // any-local
                "0.1.2.3",         // 0/8 "this network"
                "127.0.0.1",       // loopback
                "10.0.0.5",        // RFC 1918
                "172.16.0.1",      // RFC 1918
                "172.31.255.254",  // RFC 1918 upper bound
                "192.168.1.1",     // RFC 1918
                "169.254.1.1",     // link-local
                "100.64.0.1",      // CGNAT
                "100.127.255.254", // CGNAT upper bound
                "198.18.0.1",      // benchmarking
                "224.0.0.1",       // multicast
                "239.255.255.250", // SSDP multicast
                "240.0.0.1",       // reserved
                "255.255.255.255", // broadcast
        };
        for (String address : blocked) {
            assertTrue(LocalAddressUtil.isLocalAddress(literal(address)),
                    address + " must be treated as local/private");
        }
    }

    @Test
    void ipv6RangesAreBlocked() throws Exception {
        String[] blocked = {
                "::1",                    // loopback
                "fe80::1",                // link-local
                "fec0::1",                // deprecated site-local
                "fc00::1",                // unique-local lower half
                "fd12:3456:789a::1",      // unique-local upper half
                "ff02::1",                // multicast
        };
        for (String address : blocked) {
            assertTrue(LocalAddressUtil.isLocalAddress(literal(address)),
                    address + " must be treated as local/private");
        }
    }

    @Test
    void ipv4MappedIpv6IsRecheckedAgainstIpv4Ranges() throws Exception {
        // Constructed from raw bytes: InetAddress.getByName("::ffff:127.0.0.1") collapses to an
        // Inet4Address on dual-stack JVMs, which would test the wrong code path.
        byte[] mappedLoopback = new byte[16];
        mappedLoopback[10] = (byte) 0xFF;
        mappedLoopback[11] = (byte) 0xFF;
        mappedLoopback[12] = 127;
        mappedLoopback[15] = 1;
        assertTrue(LocalAddressUtil.isLocalAddress(InetAddress.getByAddress(mappedLoopback)),
                "::ffff:127.0.0.1 must not slip past the IPv4 checks");

        byte[] mappedCgnat = new byte[16];
        mappedCgnat[10] = (byte) 0xFF;
        mappedCgnat[11] = (byte) 0xFF;
        mappedCgnat[12] = 100;
        mappedCgnat[13] = 64;
        assertTrue(LocalAddressUtil.isLocalAddress(InetAddress.getByAddress(mappedCgnat)));

        byte[] mappedPublic = new byte[16];
        mappedPublic[10] = (byte) 0xFF;
        mappedPublic[11] = (byte) 0xFF;
        mappedPublic[12] = 8;
        mappedPublic[13] = 8;
        mappedPublic[14] = 8;
        mappedPublic[15] = 8;
        assertFalse(LocalAddressUtil.isLocalAddress(InetAddress.getByAddress(mappedPublic)),
                "a mapped public address is still public");
    }

    // ------------------------------------------------------------------ allowed targets

    @Test
    void publicAddressesAreAllowed() throws Exception {
        String[] allowed = {
                "8.8.8.8",
                "1.1.1.1",
                "93.184.216.34",
                "100.63.255.254",  // just below CGNAT
                "100.128.0.1",     // just above CGNAT
                "172.15.255.255",  // just below RFC 1918
                "172.32.0.1",      // just above RFC 1918
                "192.167.255.255", // just below RFC 1918
                "192.169.0.1",     // just above RFC 1918
                "223.255.255.255", // just below multicast
                "2001:4860:4860::8888",
                "2606:4700:4700::1111",
                "fb00::1",         // just below unique-local
        };
        for (String address : allowed) {
            assertFalse(LocalAddressUtil.isLocalAddress(literal(address)),
                    address + " must stay reachable (false positives break resource packs)");
        }
    }

    @Test
    void hostNameLookupFlagsLocalhostOnly() throws Exception {
        assertTrue(LocalAddressUtil.isLocalAddress("localhost"));
        assertFalse(LocalAddressUtil.isLocalAddress(""));
        assertFalse(LocalAddressUtil.isLocalAddress((String) null));
    }

    // ------------------------------------------------------------------ helpers

    @Test
    void nullAndUnresolvableInputIsSafe() {
        assertFalse(LocalAddressUtil.isLocalAddress((InetAddress) null));
        assertTrue(LocalAddressUtil.isLiteralAddress("192.168.1.1"));
        assertTrue(LocalAddressUtil.isLiteralAddress("[fd00::1]"));
        assertFalse(LocalAddressUtil.isLiteralAddress("example.com"));
        assertFalse(LocalAddressUtil.isLiteralAddress(""));
        assertFalse(LocalAddressUtil.isLiteralAddress(null));
    }

    @Test
    void hostNormalizationIsStable() {
        assertEquals("example.com", LocalAddressUtil.normalizeHost("  Example.COM.  "));
        assertEquals("", LocalAddressUtil.normalizeHost(null));
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
