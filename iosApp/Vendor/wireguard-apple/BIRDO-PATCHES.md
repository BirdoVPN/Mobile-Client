# Vendored WireGuardKit — provenance and patches

This is **tunnel code**. It is vendored, not fetched, so the exact bytes that
carry user traffic are in-tree, reviewable, and cannot change under us.

## Provenance

| | |
|---|---|
| Upstream | https://github.com/WireGuard/wireguard-apple (mirror of git.zx2c4.com/wireguard-apple) |
| Tag | `1.0.16-27` |
| Commit | `2fec12a6e1f6e3460b6ee483aa00ad29cddadab1` |
| Vendored | 2026-07-12 |
| Licence | MIT — see `COPYING` (unmodified) |

Only the three SwiftPM targets are vendored: `WireGuardKit`, `WireGuardKitC`,
`WireGuardKitGo`. Upstream's `Sources/WireGuardApp` and `Sources/Shared` (the
GUI app, ~2 MB) are **not** included — the package does not build them.

The `wireguard-go` tunnel itself is **not** vendored: it is fetched by the Go
module system at build time, pinned by `Sources/WireGuardKitGo/go.mod` +
`go.sum` (`golang.zx2c4.com/wireguard v0.0.0-20230209153558-1e2c3e5a3c14`).
`go.sum` gives it cryptographic integrity.

## Patch 1 — `Package.swift`: `swift-tools-version:5.3` → `5.9`

Upstream declares tools-version **5.3** but uses `.iOS(.v15)` and
`.macOS(.v12)`, which `PackageDescription` only introduced in **5.5**. The
manifest therefore fails to compile and SwiftPM reports a bare
`Invalid manifest` with no diagnostic (swift-package-manager#5886).

This has been broken since
[`901fe1c` "App: bump minimum OS versions"](https://github.com/WireGuard/wireguard-apple/commit/901fe1c)
(Feb 2023) — the last commit to touch `Package.swift`. **Every tag since,
including `1.0.16-27`, carries it.** Upstream never noticed because the official
WireGuard app compiles these sources as Xcode targets and does not consume its
own SwiftPM package.

Reported and diagnosed publicly, never fixed:
- https://lists.zx2c4.com/pipermail/wireguard/2023-October/008205.html
- https://forums.swift.org/t/wireguard-for-ios-macos-invalid-manifest-error/67008

## Patch 2 — `Sources/WireGuardKitGo/Makefile`: iOS-simulator support

Upstream defines `GOOS_macosx` and `GOOS_iphoneos` but **no
`GOOS_iphonesimulator`**. Any simulator build (our unit-test job builds the app,
which embeds the PacketTunnel extension, which links `-lwg-go`) therefore fails
to link.

Added, mirroring
[mullvad/wireguard-apple](https://github.com/mullvad/wireguard-apple), which
ships this in production:

```make
UNAME := $(shell uname -m)
ifeq ($(UNAME), $(GOARCH_arm64))
	GOOS_iphonesimulator := ios
endif
```

Guarded on an arm64 host because Go can only cross-build the arm64 simulator
slice there. CI runs `macos-14` (Apple Silicon).

## Patch 3 — `Sources/WireGuardKitC/WireGuardKitC.h`: `#include <sys/types.h>`

The umbrella header uses `u_int32_t`, `u_char` and `u_int16_t` without ever
declaring them. Upstream builds `WireGuardKitC` as a plain Xcode target, where a
transitively-included `sys/types.h` happens to cover it. As a **SwiftPM Clang
module** the umbrella header is parsed standalone and the compiler hard-errors:

```
declaration of 'u_int32_t' must be imported from module
'Darwin.POSIX.sys._types' before it is required
```

Added the missing `#include <sys/types.h>`. Nothing else in the header changes.

This is the third symptom of the same root cause as Patch 1: **upstream does not
consume its own SwiftPM package**, so the package has quietly rotted.

## Patch 4 — two files MOVED into the `WireGuardKit` target

Added to `Sources/WireGuardKit/`, byte-identical to upstream apart from a header
comment:

- `TunnelConfiguration+WgQuickConfig.swift`
- `String+ArrayConversion.swift` (its only dependency — `splitToArray`)

Upstream keeps both in `Sources/Shared/`, which **the SwiftPM package does not
build**: they are compiled directly into the WireGuard GUI app's Xcode target,
alongside WireGuardKit's own sources. That is why they carry no
`import WireGuardKit`.

`PacketTunnelProvider` needs `TunnelConfiguration(fromWgQuickConfig:called:)` to
parse the tunnel config it receives. Moving these into the `WireGuardKit` target
makes them same-module, so no import is needed.

**One further change was required:** upstream declares the parser's entry points
as `internal`, which is fine when everything is compiled into a single app
target — but `PacketTunnel` is a *separate module*, and `internal` does not cross
a package boundary. So three declarations gain `public`:

- `convenience init(fromWgQuickConfig:called:)`
- `func asWgQuickConfig()`
- `enum ParseError`

Bodies are untouched. This is the canonical WireGuard wg-quick parser — the
alternative, hand-rolling a config parser for a VPN tunnel, would be strictly
worse.

## Nothing else is modified

No Go source is touched. The only C change is the one `#include` (Patch 3); the
only Swift change is the two moved files (Patch 4), which are otherwise
unaltered. To verify:

```sh
git clone --depth 1 --branch 1.0.16-27 https://github.com/WireGuard/wireguard-apple.git /tmp/wg
diff -ru /tmp/wg/Sources/WireGuardKit  iosApp/Vendor/wireguard-apple/Sources/WireGuardKit  # + the 2 moved files
diff -ru /tmp/wg/Sources/WireGuardKitC iosApp/Vendor/wireguard-apple/Sources/WireGuardKitC  # WireGuardKitC.h only
diff -ru /tmp/wg/Sources/WireGuardKitGo iosApp/Vendor/wireguard-apple/Sources/WireGuardKitGo  # Makefile only
```

(Expect line-ending noise only if your clone has `core.autocrlf` on — the
vendored tree is pinned to LF by `.gitattributes`, because a CRLF `Makefile`
yields `GOOS := ios\r` and a CRLF `.diff` will not apply.)

## Why SwiftPM cannot build this alone

`WireGuardKitGo` declares `linkerSettings: [.linkedLibrary("wg-go")]` — it emits
`-lwg-go` but cannot produce the archive. Upstream's README states this outright.

Upstream's prescription is an Xcode **External Build System** ("legacy") target
that runs this Makefile. We tried that first; it failed with Xcode's opaque

```
Internal inconsistency error: never received target ended message
for target ID '5' (in target 'WireGuardGoBridge')
```

…and, worse, it **swallows `make`'s output**, so there is nothing to debug. iOS
only ever builds in CI here (the dev machine is Windows), so an undebuggable
build step is not survivable.

Instead, **`scripts/build-wireguard-go.sh`** invokes the same Makefile directly
with the same environment variables Xcode would have passed (`ARCHS`,
`PLATFORM_NAME`, `SDKROOT`, `CONFIGURATION_BUILD_DIR`,
`DEPLOYMENT_TARGET_CLANG_*`). `ios.yml` runs it before `xcodebuild` — once for
`iphoneos` (the shipped slice) and once for `iphonesimulator` (the test job) —
and passes the output directory as `LIBWG_GO_DIR`, which `project.yml` puts on
`PacketTunnel`'s `LIBRARY_SEARCH_PATHS`. `make`'s output is now fully visible in
the CI log.

That build needs the **Go toolchain**, installed by `ios.yml`: macOS runners
cache Go but do **not** put it on `PATH`
([actions/runner-images#13675](https://github.com/actions/runner-images/issues/13675)).
