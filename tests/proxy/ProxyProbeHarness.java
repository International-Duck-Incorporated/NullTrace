import net.nulltrace.mod.lang.NullTraceStrings;
import net.nulltrace.mod.proxy.ProxyEntry;
import net.nulltrace.mod.proxy.ProxySettings;
import net.nulltrace.mod.proxy.ProxyTester;

import java.net.Proxy;

/**
 * Standalone harness for the proxy feature: endpoint parsing, list/selection semantics, config
 * round-trip and the reachability probes, including the failure paths.
 *
 * <p>It starts its own fake endpoints ({@link FakeProxyServers}), needs no Minecraft runtime, and
 * exits non-zero on the first failing expectation group, so it can run as a CI step:
 *
 * <pre>
 *   javac -encoding UTF-8 -d /tmp/harness -cp gson.jar \
 *       tests/proxy/stub/net/nulltrace/mod/NullTrace.java \
 *       src/main/java/net/nulltrace/mod/lang/NullTraceStrings.java \
 *       src/client/java/net/nulltrace/mod/proxy/*.java \
 *       tests/proxy/FakeProxyServers.java tests/proxy/ProxyProbeHarness.java
 *   java -cp gson.jar:/tmp/harness ProxyProbeHarness
 * </pre>
 */
public final class ProxyProbeHarness {
    private static int passed;
    private static int failed;

    private ProxyProbeHarness() {}

    public static void main(String[] args) throws Exception {
        try (FakeProxyServers servers = new FakeProxyServers()) {
            int http = servers.httpConnectProxy();
            int socks = servers.socks5NoAuthProxy();
            int socksAuth = servers.socks5AuthRequiredProxy();
            int refusing = servers.refusingHttpProxy();
            int silent = servers.silentEndpoint();
            int dead = FakeProxyServers.closedPort();

            parseChecks();
            listChecks();
            jsonChecks();
            liveChecks(http, socks, socksAuth, refusing, silent, dead);
        }

        System.out.println();
        if (failed == 0) {
            System.out.println("ALL " + passed + " CHECKS PASSED");
        } else {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ parsing

    private static void parseChecks() {
        System.out.println("== parsing ==");
        ProxyEntry entry;
        entry = ProxyEntry.parse("play.example.com:1080").entry();
        check("host:port defaults to SOCKS5", entry != null && entry.type() == ProxyEntry.Type.SOCKS
                && entry.port() == 1080, entry);
        entry = ProxyEntry.parse("socks5://127.0.0.1:1080").entry();
        check("socks5:// accepted", entry != null && entry.id().equals("socks5://127.0.0.1:1080"), entry);
        entry = ProxyEntry.parse("http://proxy.local:8080").entry();
        check("http:// marked as HTTP", entry != null && entry.type() == ProxyEntry.Type.HTTP, entry);
        entry = ProxyEntry.parse("https://proxy.local:8443").entry();
        check("https:// treated as HTTP", entry != null && entry.type() == ProxyEntry.Type.HTTP, entry);
        entry = ProxyEntry.parse("[2001:db8::1]:1080").entry();
        check("IPv6 literal parsed", entry != null && entry.host().equals("2001:db8::1") && entry.port() == 1080, entry);
        check("surrounding whitespace tolerated", ProxyEntry.parse("   socks5://h:1  ").ok(), null);

        check("credentials rejected", NullTraceStrings.PROXY_ERROR_CREDENTIALS.equals(
                ProxyEntry.parse("user:pass@host:1080").errorKey()), null);
        check("missing port rejected", NullTraceStrings.PROXY_ERROR_PORT.equals(
                ProxyEntry.parse("host").errorKey()), null);
        check("scheme without port rejected", NullTraceStrings.PROXY_ERROR_PORT.equals(
                ProxyEntry.parse("socks5://host").errorKey()), null);
        check("unknown scheme rejected", NullTraceStrings.PROXY_ERROR_SCHEME.equals(
                ProxyEntry.parse("ftp://host:21").errorKey()), null);
        check("empty input rejected", NullTraceStrings.PROXY_ERROR_EMPTY.equals(
                ProxyEntry.parse("   ").errorKey()), null);
        check("null input rejected", NullTraceStrings.PROXY_ERROR_EMPTY.equals(
                ProxyEntry.parse(null).errorKey()), null);
        check("port 0 rejected", NullTraceStrings.PROXY_ERROR_PORT.equals(
                ProxyEntry.parse("host:0").errorKey()), null);
        check("port above 65535 rejected", NullTraceStrings.PROXY_ERROR_PORT.equals(
                ProxyEntry.parse("host:70000").errorKey()), null);
        check("non-numeric port rejected", NullTraceStrings.PROXY_ERROR_PORT.equals(
                ProxyEntry.parse("host:abc").errorKey()), null);
        check("URL with a path rejected precisely", NullTraceStrings.PROXY_ERROR_PATH.equals(
                ProxyEntry.parse("http://host:8080/path").errorKey()),
                ProxyEntry.parse("http://host:8080/path").errorKey());
        check("URL with a query rejected", NullTraceStrings.PROXY_ERROR_PATH.equals(
                ProxyEntry.parse("host:8080?x=1").errorKey()), null);
        check("blank host rejected", NullTraceStrings.PROXY_ERROR_HOST.equals(
                ProxyEntry.parse(":1080").errorKey()), null);
    }

    // ------------------------------------------------------------------ list semantics

    private static void listChecks() {
        System.out.println("== list and selection ==");
        ProxySettings settings = new ProxySettings();
        ProxyEntry a = ProxyEntry.parse("socks5://a.example:1080").entry();
        ProxyEntry b = ProxyEntry.parse("http://b.example:3128").entry();

        check("default mode is DIRECT", settings.mode() == ProxySettings.Mode.DIRECT, settings.mode());
        check("DIRECT resolves to NO_PROXY", settings.activeProxy() == Proxy.NO_PROXY, settings.activeProxy());
        check("add selects the new entry", settings.add(a) && a.id().equals(settings.selectedId()), settings.selectedId());
        check("duplicate add selects without duplicating",
                !settings.add(ProxyEntry.parse("socks5://a.example:1080").entry()) && settings.entries().size() == 1,
                settings.entries());
        check("invalid entry refused", !settings.add(new ProxyEntry(ProxyEntry.Type.SOCKS, "", 0)), null);
        check("null entry refused", !settings.add(null), null);

        settings.add(b);
        settings.setMode(ProxySettings.Mode.SELECTED);
        check("SELECTED resolves to the chosen entry", settings.activeProxy().address().toString().contains("b.example"),
                settings.activeProxy());
        check("HTTP type reaches java.net.Proxy", settings.activeProxy().type() == Proxy.Type.HTTP, settings.activeProxy());

        settings.move(b, -1);
        check("move up reorders", settings.entries().get(0).equals(b), settings.entries());
        settings.move(b, -5);
        check("move clamps at the top", settings.entries().get(0).equals(b), settings.entries());
        settings.move(b, 99);
        check("move clamps at the bottom", settings.entries().get(1).equals(b), settings.entries());

        settings.select(b);
        check("removing the selection falls back to the first entry",
                settings.remove(b) && a.id().equals(settings.selectedId()), settings.selectedId());
        settings.remove(a);
        check("removing the last entry clears the selection", settings.selectedId().isEmpty(), settings.selectedId());
        settings.setMode(ProxySettings.Mode.SELECTED);
        check("SELECTED with nothing selected fails closed",
                settings.activeProxy() == Proxy.NO_PROXY, settings.activeProxy());
        check("selectionMissing reports the problem", settings.selectionMissing(), null);
        settings.setMode(ProxySettings.Mode.LAUNCHER);
        check("LAUNCHER leaves Minecraft's own value alone", settings.activeProxy() == null, settings.activeProxy());
    }

    // ------------------------------------------------------------------ persistence

    private static void jsonChecks() {
        System.out.println("== configuration persistence ==");
        ProxySettings original = new ProxySettings();
        original.add(ProxyEntry.parse("socks5://one.example:1080").entry());
        original.add(ProxyEntry.parse("http://two.example:3128").entry());
        original.setMode(ProxySettings.Mode.SELECTED);

        ProxySettings reloaded = ProxySettings.fromJson(original.toJson());
        check("entries survive a round trip", reloaded.entries().equals(original.entries()), reloaded.entries());
        check("selection survives", reloaded.selectedId().equals(original.selectedId()), reloaded.selectedId());
        check("mode survives", reloaded.mode() == original.mode(), reloaded.mode());
        check("SOCKS type survives", reloaded.entries().get(0).type() == ProxyEntry.Type.SOCKS, null);
        check("HTTP type survives", reloaded.entries().get(1).type() == ProxyEntry.Type.HTTP, null);

        check("garbage entries are dropped", ProxySettings.fromJson(com.google.gson.JsonParser.parseString(
                "{\"mode\":\"SELECTED\",\"entries\":[{\"type\":\"SOCKS\",\"host\":\"\",\"port\":0}]}")
                .getAsJsonObject()).entries().isEmpty(), null);
        check("unknown mode falls back to DIRECT", ProxySettings.fromJson(com.google.gson.JsonParser.parseString(
                "{\"mode\":\"NONSENSE\"}").getAsJsonObject()).mode() == ProxySettings.Mode.DIRECT, null);
        check("null json yields defaults", ProxySettings.fromJson(null).entries().isEmpty(), null);
        check("duplicate entries in a hand-edited file collapse", ProxySettings.fromJson(com.google.gson.JsonParser.parseString(
                "{\"mode\":\"SELECTED\",\"entries\":[{\"type\":\"SOCKS\",\"host\":\"h\",\"port\":1},"
                + "{\"type\":\"SOCKS\",\"host\":\"h\",\"port\":1}]}").getAsJsonObject()).entries().size() == 1, null);
    }

    // ------------------------------------------------------------------ live probes

    private static void liveChecks(int http, int socks, int socksAuth, int refusing, int silent, int dead)
            throws Exception {
        System.out.println("== live probes ==");
        ProxyTester.Result result;

        result = ProxyTester.test(new ProxyEntry(ProxyEntry.Type.HTTP, "127.0.0.1", http)).get();
        check("HTTP CONNECT probe succeeds against a working proxy", result.ok(), result);
        check("successful probe reports a latency", result.latencyMs() >= 0, result);

        result = ProxyTester.test(new ProxyEntry(ProxyEntry.Type.SOCKS, "127.0.0.1", socks)).get();
        check("SOCKS5 probe succeeds when no auth is required", result.ok(), result);

        result = ProxyTester.test(new ProxyEntry(ProxyEntry.Type.SOCKS, "127.0.0.1", socksAuth)).get();
        check("SOCKS5 probe fails honestly when the proxy demands auth", !result.ok(), result);
        check("auth failure explains itself", result.detail() != null && result.detail().toLowerCase().contains("auth"),
                result.detail());

        result = ProxyTester.test(new ProxyEntry(ProxyEntry.Type.HTTP, "127.0.0.1", refusing)).get();
        check("HTTP probe fails on a non-200 CONNECT", !result.ok(), result);
        check("refusal detail carries the status line",
                result.detail() != null && result.detail().contains("403"), result.detail());

        result = ProxyTester.test(new ProxyEntry(ProxyEntry.Type.HTTP, "127.0.0.1", dead)).get();
        check("closed port fails cleanly", !result.ok(), result);

        result = ProxyTester.test(new ProxyEntry(ProxyEntry.Type.HTTP, "127.0.0.1", silent)).get();
        check("silent endpoint times out instead of hanging", !result.ok(), result);

        result = ProxyTester.test(null).get();
        check("null entry is handled", !result.ok(), result);
        result = ProxyTester.test(new ProxyEntry(ProxyEntry.Type.SOCKS, "", 0)).get();
        check("invalid entry is handled", !result.ok(), result);
    }

    // ------------------------------------------------------------------ reporting

    private static void check(String name, boolean ok, Object detail) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + (detail == null ? "" : "  -> " + detail));
        }
    }
}
