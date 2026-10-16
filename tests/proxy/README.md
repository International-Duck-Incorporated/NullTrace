# Proxy harness

Standalone checks for the proxy feature, runnable on any machine with a JDK — no Minecraft, no
Gradle, no Python.

```bash
# 1. compile the proxy classes plus the harness
javac -encoding UTF-8 -d /tmp/harness -cp gson.jar \
    tests/proxy/stub/net/nulltrace/mod/NullTrace.java \
    src/client/java/net/nulltrace/mod/lang/NullTraceStrings.java \
    src/client/java/net/nulltrace/mod/proxy/*.java \
    tests/proxy/FakeProxyServers.java tests/proxy/ProxyProbeHarness.java

# 2. run it (it starts its own fake endpoints and exits non-zero on failure)
java -cp gson.jar:/tmp/harness ProxyProbeHarness
```

`gson.jar` can be fetched from Maven Central
(`com.google.code.gson:gson:2.11.0`); CI does exactly that in the `proxy_probe_harness` job.

## Files

| File | Role |
| --- | --- |
| `ProxyProbeHarness.java` | 54 expectations across parsing, list/selection semantics, config round-trip and live probes |
| `FakeProxyServers.java` | In-process endpoints: HTTP CONNECT (200 and 403), SOCKS5 (no-auth and auth-required), a silent server, and a closed port |
| `stub/net/nulltrace/mod/NullTrace.java` | Compile-only stand-in for the mod entry point (logger + version). Never shipped: the mod build only compiles `src/main`, `src/client` and `versions/`. |

## Why this exists

The repository's other "adversarial fixture" (`tests/adversarial_server.py` plus its test) only
asserts that a hardcoded list of probe *names* is stable; it never touches NullTrace code, so it
stays green no matter what the mod does. This harness runs real code against real sockets, which is
how it found three genuine defects while being written:

1. a pasted URL containing a path/query reported "include a port" instead of "remove the path";
2. `:1080` (missing host) reported the same misleading port error;
3. the SOCKS5 auth-required path needed to fail *honestly* rather than reporting a timeout.

The proxy classes live in `src/client`, which the Gradle test source set cannot see, so this harness
is their only automated coverage. The pure-logic tests that *do* run under Gradle cover the privacy
policy, audit ledger, warning decision and address guard in
`src/test/java/net/nulltrace/mod/{privacy,util}`.
