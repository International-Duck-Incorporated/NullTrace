# Account vault harness

JDK-only test for `AccountTokenVault` — the class that encrypts session and refresh tokens at
rest. It runs without Minecraft and without Gradle, like `tests/proxy`.

```bash
mkdir -p /tmp/vault-harness
javac -encoding UTF-8 -d /tmp/vault-harness \
  tests/vault/stub/net/fabricmc/loader/api/FabricLoader.java \
  tests/vault/stub/net/nulltrace/mod/NullTrace.java \
  src/client/java/net/nulltrace/mod/accounts/AccountTokenVault.java \
  tests/vault/bridge/net/nulltrace/mod/accounts/AccountTokenVaultAccess.java \
  tests/vault/VaultHarness.java
java -cp /tmp/vault-harness VaultHarness
```

`stub/` supplies the two Fabric/loader references the vault has (a config directory and a
logger). `bridge/` lives in the vault's package so it can reach the package-private API, and
never enters the mod jar.

What it pins, in the order the failures matter:

| Check | Why |
| --- | --- |
| URL-safe base64 round-trip | Encryption used `getUrlEncoder()` while decryption used `getDecoder()`. ~98 % of ciphertexts contain `-` or `_`, so nearly every saved token failed to load with "re-authentication required". |
| AAD binding | A token blob moved onto another account entry must not decrypt. |
| Tampering | A flipped ciphertext byte, a truncated envelope and an unknown prefix are all rejected. |
| `v1:` migration | Values written before the binding existed still load. |
| Key handling | A 32-byte key is created owner-only, a corrupt key file is replaced, and envelopes sealed under the old key fail closed. |

Adding a case here is cheap; the harness exits non-zero on any failure.
