import net.nulltrace.mod.lang.NullTraceStrings;
import net.nulltrace.mod.proxy.*;

public class ProxyProbeHarness {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, Object detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + "  -> " + detail); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== parse ==");
        ProxyEntry e;
        e = ProxyEntry.parse("play.example.com:1080").entry();
        check("host:port defaults to SOCKS5", e != null && e.type() == ProxyEntry.Type.SOCKS && e.port() == 1080, e);
        e = ProxyEntry.parse("socks5://127.0.0.1:1080").entry();
        check("socks5:// accepted", e != null && e.id().equals("socks5://127.0.0.1:1080"), e);
        e = ProxyEntry.parse("http://proxy.local:8080").entry();
        check("http:// marked as HTTP", e != null && e.type() == ProxyEntry.Type.HTTP, e);
        e = ProxyEntry.parse("https://proxy.local:8443").entry();
        check("https:// treated as HTTP", e != null && e.type() == ProxyEntry.Type.HTTP, e);
        e = ProxyEntry.parse("[2001:db8::1]:1080").entry();
        check("IPv6 literal parsed", e != null && e.host().equals("2001:db8::1") && e.port() == 1080, e);
        check("credentials rejected", NullTraceStrings.PROXY_ERROR_CREDENTIALS.equals(
                ProxyEntry.parse("user:pass@host:1080").errorKey()), null);
        check("missing port rejected", NullTraceStrings.PROXY_ERROR_PORT.equals(
                ProxyEntry.parse("host").errorKey()), null);
        check("blank scheme host+no port rejected", NullTraceStrings.PROXY_ERROR_PORT.equals(
                ProxyEntry.parse("socks5://host").errorKey()), null);
        check("bad scheme rejected", NullTraceStrings.PROXY_ERROR_SCHEME.equals(
                ProxyEntry.parse("ftp://host:21").errorKey()), null);
        check("empty input rejected", NullTraceStrings.PROXY_ERROR_EMPTY.equals(
                ProxyEntry.parse("   ").errorKey()), null);
        check("port 0 rejected", NullTraceStrings.PROXY_ERROR_PORT.equals(
                ProxyEntry.parse("host:0").errorKey()), null);
        check("port 70000 rejected", NullTraceStrings.PROXY_ERROR_PORT.equals(
                ProxyEntry.parse("host:70000").errorKey()), null);
        check("non-numeric port rejected", NullTraceStrings.PROXY_ERROR_PORT.equals(
                ProxyEntry.parse("host:abc").errorKey()), null);
        check("URL with path rejected", NullTraceStrings.PROXY_ERROR_PATH.equals(
                ProxyEntry.parse("http://host:8080/path").errorKey()), ProxyEntry.parse("http://host:8080/path").errorKey());

        System.out.println("== list state ==");
        ProxySettings s = new ProxySettings();
        ProxyEntry a = ProxyEntry.parse("socks5://a.example:1080").entry();
        ProxyEntry b = ProxyEntry.parse("http://b.example:3128").entry();
        check("default mode is DIRECT", s.mode() == ProxySettings.Mode.DIRECT, s.mode());
        check("default resolves to NO_PROXY", s.activeProxy() == java.net.Proxy.NO_PROXY, s.activeProxy());
        check("add selects new entry", s.add(a) && a.id().equals(s.selectedId()), s.selectedId());
        check("duplicate add does not duplicate", !s.add(ProxyEntry.parse("socks5://a.example:1080").entry())
                && s.entries().size() == 1, s.entries());
        s.add(b);
        s.setMode(ProxySettings.Mode.SELECTED);
        check("SELECTED resolves to the entry", s.activeProxy().address().toString().equals("b.example:3128")
                || s.activeProxy().address().toString().contains("b.example"), s.activeProxy());
        s.move(b, -1);
        check("move up reorders", s.entries().get(0).equals(b), s.entries());
        s.move(b, -5);
        check("move clamps at top", s.entries().get(0).equals(b), s.entries());
        check("type is HTTP for b", s.activeProxy().type() == java.net.Proxy.Type.HTTP, s.activeProxy());
        s.select(b);
        check("remove selected falls back to remaining", s.remove(b) && a.id().equals(s.selectedId()), s.selectedId());
        s.remove(a);
        check("remove last clears selection", s.selectedId().isEmpty(), s.selectedId());
        s.setMode(ProxySettings.Mode.SELECTED);
        check("SELECTED with empty list fails closed", s.activeProxy() == java.net.Proxy.NO_PROXY, s.activeProxy());
        check("selectionMissing true", s.selectionMissing(), s.selectionMissing());
        s.setMode(ProxySettings.Mode.LAUNCHER);
        check("LAUNCHER leaves Minecraft's value alone", s.activeProxy() == null, s.activeProxy());

        System.out.println("== json round-trip ==");
        ProxySettings original = new ProxySettings();
        original.add(ProxyEntry.parse("socks5://one.example:1080").entry());
        original.add(ProxyEntry.parse("http://two.example:3128").entry());
        original.setMode(ProxySettings.Mode.SELECTED);
        ProxySettings reloaded = ProxySettings.fromJson(original.toJson());
        check("entries survive round-trip", reloaded.entries().equals(original.entries()), reloaded.entries());
        check("selected id survives", reloaded.selectedId().equals(original.selectedId()), reloaded.selectedId());
        check("mode survives", reloaded.mode() == original.mode(), reloaded.mode());
        check("socks type survives", reloaded.entries().get(0).type() == ProxyEntry.Type.SOCKS, null);
        check("http type survives", reloaded.entries().get(1).type() == ProxyEntry.Type.HTTP, null);
        check("garbage entry dropped", ProxySettings.fromJson(com.google.gson.JsonParser.parseString(
                "{\"mode\":\"SELECTED\",\"entries\":[{\"type\":\"SOCKS\",\"host\":\"\",\"port\":0}]}").getAsJsonObject())
                .entries().isEmpty(), null);
        check("unknown mode falls back to DIRECT", ProxySettings.fromJson(com.google.gson.JsonParser.parseString(
                "{\"mode\":\"NONSENSE\"}").getAsJsonObject()).mode() == ProxySettings.Mode.DIRECT, null);

        System.out.println("== live probe ==");
        ProxyEntry http = new ProxyEntry(ProxyEntry.Type.HTTP, "127.0.0.1", Integer.parseInt(args[0]));
        ProxyEntry socks = new ProxyEntry(ProxyEntry.Type.SOCKS, "127.0.0.1", Integer.parseInt(args[1]));
        ProxyEntry dead = new ProxyEntry(ProxyEntry.Type.HTTP, "127.0.0.1", Integer.parseInt(args[2]));
        ProxyTester.Result httpResult = ProxyTester.test(http).get();
        check("HTTP CONNECT probe succeeds against a working proxy", httpResult.ok(), httpResult);
        ProxyTester.Result socksResult = ProxyTester.test(socks).get();
        check("SOCKS5 probe succeeds against a SOCKS5 server", socksResult.ok(), socksResult);
        ProxyTester.Result deadResult = ProxyTester.test(dead).get();
        check("closed port fails cleanly", !deadResult.ok(), deadResult);
        ProxyTester.Result nullResult = ProxyTester.test(null).get();
        check("null entry handled", !nullResult.ok(), nullResult);

        System.out.println();
        System.out.println(fail == 0 ? ("ALL " + pass + " CHECKS PASSED") : (fail + " FAILED, " + pass + " passed"));
        if (fail > 0) System.exit(1);
    }
}
