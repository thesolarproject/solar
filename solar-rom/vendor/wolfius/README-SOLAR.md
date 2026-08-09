# Wolfius core — vendored into Solar

This directory contains the Wolfius TLS proxy core (https://github.com/gohoski/Wolfius,
`gohoski/Wolfius`, commit as fetched) embedded into Solar so the Y1 (API 17) / Y2 (API 19)
can speak **TLS 1.3** on the wire.

## What it does

- `jni/wolfssl/` — wolfSSL source (submodule `wolfSSL/wolfssl` @ `ac01707f552c611fbd135cc723b2682b3e7f80f2`).
- `jni/wolfssl_bridge.c` — JNI bridge: SNI peek/parse, per-domain MITM cert generation,
  native TLS handshakes. Java class renamed to `com.solar.launcher.tlsproxy.WolfClient`.
- `java-src/` — upstream Wolfius Java (reference only; the compiled copies live in
  `app/src/main/java/com/solar/launcher/tlsproxy/` with the package renamed).
- `LICENSE-Wolfius` / `LICENSE-lwIP` / `app/src/main/assets/gpl-3.0.txt` — upstream licenses.

## Licensing — GPLv3

Wolfius is GPLv3 and uses wolfSSL in its GPLv3 configuration. By embedding this code,
**Solar redistributes GPLv3-covered code** (wolfSSL + the Wolfius proxy core). This applies
to the whole Solar APK. Do not remove the license files or the attribution. A future option
is a commercial wolfSSL license, which would only clear wolfSSL — the Wolfius-derived Java
bridge and proxy remain GPLv3.

## Build

Native library (one command):

```bash
scripts/build-wolfssl-ndk.sh   # needs NDK (NDK= or ANDROID_NDK_HOME)
```

Output: `app/src/main/jniLibs/armeabi-v7a/libwolfssl.so` (armeabi-v7a only; the Y devices
are ARMv7 MTK SoCs). The APK packages it automatically via `jniLibs`.

Java port (already in tree): `app/src/main/java/com/solar/launcher/tlsproxy/`

- `WolfClient` — JNI surface (must match `wolfssl_bridge.c` registration).
- `TlsProxy` — MITM listener on port 7998 (iptables DNAT / HTTP CONNECT).
- `WolfSSLSocket` / `WolfSSLSocketFactory` — wolfSSL-backed SSLSocket.
- `MitmKeyStoreManager` — CA + per-domain leaf certs + system-trust-store install (root).
- `DnsForwarder` — DNS on 5353 with IP→hostname cache (SNI restore for raw-IP clients).
- `ShellUtils` — root shell.
- `WolfiusProxy` — Solar facade (current method + active IP) replacing upstream `ProxyService` statics.
- `VpnCompatHelper` — no-op until the lwIP VpnService stage lands.

Service: `app/src/main/java/com/solar/launcher/WolfiusTlsService.java`

## Wiring (automatic — no user switch)

There is **no Settings toggle**: on the rooted Y1/Y2 targets the proxy runs automatically
and performs all system-level setup itself.

- `BootReceiver` calls `WolfiusTlsService.ensureStarted()` ~8 s after boot; `MainActivity`
  also calls it on app start, and the service is `START_STICKY`.
- On start the service: (1) installs the CA via `MitmKeyStoreManager.installRootCa()`
  (root remount → `/system/etc/security/cacerts/{subject_hash}.0`), (2) starts the proxy +
  DNS forwarder, (3) applies the iptables DNAT rules. If the CA install races a slow boot,
  it retries on connectivity changes and on a 30 s timer (up to 3 attempts) until it
  converges.
- Traffic capture is only applied once the CA is verifiably in the system trust store —
  until then the proxy stays listener-only (usable as a manual HTTP proxy), so existing
  TLS is never broken.
- ROM builds can pre-install the CA with `solar-rom/scripts/inject-wolfius-ca.sh` so no
  runtime `/system` remount is needed at all.
- Reversal (opt-in again): gate `ensureStarted` on a pref and re-add the Connections row.

## Deferred stages (upstream Wolfius methods not yet ported)

- **VpnService (lwIP)** — non-root system-wide capture. Needs `jni/lwip` + `lwip_bridge.c`
  + `WolfiusVpnService`/`LwipBridge`; the sources are already vendored here.
- **PPTP** — root VPN for ROMs without netfilter.
- HTTP-proxy mode works already (the listener answers CONNECT/absolute-URI) — point any app
  at `127.0.0.1:7998` as an HTTP proxy and TLS 1.3 applies.

## Security note (by design)

The proxy terminates TLS locally with a bundled root CA, so the device's own TLS traffic is
decrypted at the proxy and re-encrypted with TLS 1.3 upstream. That is the Wolfius trade-off:
modern cipher connectivity in exchange for trusting the bundled CA. Keep the toggle off for
sensitive traffic unless TLS 1.3 connectivity is required.

Additional inherited-from-upstream caveats:

- **Upstream certificate verification is disabled** (`WOLFSSL_VERIFY_NONE` on the client
  context in `wolfssl_bridge.c`). The proxy never validates the remote server's certificate,
  matching Wolfius. A future stage could enable a pinned/anchor set without breaking the MITM
  design.
- **The CA + leaf keys are static, shipped in APK assets.** Anyone who extracts the APK can
  impersonate the proxy CA on a device trusting it. Wolfius ships the same way; a per-device
  CA generated at first run would be stronger.
- **Capture is gated on CA trust**: `WolfiusTlsService` only applies the iptables DNAT rules
  after confirming the CA is in the system trust store (runtime root install or ROM
  pre-install). If the CA is missing, the proxy stays listener-only so Solar's own
  OkHttp/Conscrypt TLS keeps working.
