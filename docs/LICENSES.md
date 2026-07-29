# Dependency and redistribution notes

This is an engineering inventory, not legal advice.

## Current release blocker

The upstream checkout has no root `LICENSE` or `COPYING` file. A private test
repository does not grant permission to redistribute Solar, its APKs, or its ROM
images publicly. Obtain an explicit project license or permission from the
copyright holders before any public release.

## Direct Gradle dependencies

| Component | Use | License to verify/include |
| --- | --- | --- |
| OkHttp 3.12.13 / MockWebServer | API-17 HTTP and tests | Apache License 2.0 |
| Conscrypt Android 2.5.3 | modern TLS provider | Apache License 2.0 |
| jaudiotagger 3.0.1 | local audio metadata | LGPL; preserve notices and satisfy the applicable LGPL terms |
| JUnit 4 | host tests | Eclipse Public License 1.0 |
| AndroidX test libraries | instrumentation tests | Apache License 2.0 |
| `org.json` test artifact | host JSON behavior | review its upstream JSON license before redistribution |

Transitive dependencies must be captured from the final release graph, not
inferred from this short list:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

## Native and vendored assets

The repository includes or stages Android system files, Xposed components,
APKs, JARs, native libraries, fonts/themes, and ROM payloads. Every final binary
needs provenance, source/relink obligations where applicable, license text, and
redistribution permission.

In particular, the bundled IJK libraries expose FFmpeg/x264 strings, but this
checkout does not include the matching configure flags, source tree, or source
offer. They are used only as the existing playback fallback. They are **not**
an approved transcoder and must not be repurposed for conversion.

## Public-release checklist

- Add the Solar project license at the repository root.
- Generate an SBOM for Gradle, native, APK, Xposed, font/theme, and ROM inputs.
- Reproduce every native binary from documented source and toolchain revisions.
- Include all required notices and corresponding-source offers.
- Resolve FFmpeg/x264 license and patent implications for each target country.
- Confirm the Y1 base-ROM owner permits redistribution of modified images.

Until all items are complete, keep repositories and artifacts private.

