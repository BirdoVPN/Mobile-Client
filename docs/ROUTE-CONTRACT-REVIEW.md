# Route contract review: Android and iOS (MR-953)

Recorded 2026-10-15 against Mobile-Client `main` 4a760dd2 plus the PR that adds
this file. Line numbers are at that tree.

## The question

Desktop #200 (OPEN-WORK F8) fixed a silent failure: a route the OS refused was
logged and dropped, the tunnel still came up, and the app said Connected while
traffic left outside it. The mobile apps do not add routes the way the desktop
does. Their contract is different:

- **Android:** `VpnService.protect()` on the tunnel's own UDP sockets, and
  `VpnService.Builder.addRoute` for the captured prefixes.
- **iOS / macOS:** the AllowedIPs written into the tunnel profile, which
  WireGuardKit turns into `includedRoutes` and applies with
  `setTunnelNetworkSettings`.

The question for each step: when it fails, does the connect fail (or fail
closed), or does the app still report a working tunnel?

## Android

| Step | Where | On failure | Test |
| --- | --- | --- | --- |
| Protect wg-go's UDP sockets from the tunnel (D-6: the app is inside its own tunnel) | `BirdoVpnService.protectTunnelSockets` (`BirdoVpnService.kt:2480-2507`); the verdict is `TunnelSocketProtection.complete` (`TunnelRouting.kt:143-165`) | At least one socket must exist and every socket that exists must be protected. Otherwise `socket_protect_failed` is reported and the connect fails, block first (`:2099-2102`); a live rebuild fails closed (`:2817-2820`). | `TunnelRoutingTest` (`TunnelSocketProtection.complete`: both, one, none, either unprotected); `BirdoVpnServiceLifecycleTest:672` "a socket that cannot be protected fails the connect, block first" |
| Capture IPv4 | `buildVpnInterface` (`:2214`): each server prefix is added in the loop at `:2366-2376`; then the IPv4 default floor at `:2417-2432` | A prefix the Builder rejects (for example host bits, `10.0.0.1/8`) is logged and skipped. If no IPv4 default results, one is added; if that fails, `connect_v4_default_route_failed` is reported and the interface is not built (fails closed: kill switch plus an error). | Lifecycle and `WireGuardConfigBuilder` tests |
| Capture IPv6 | `:2378-2395`: `::/0` is added whenever the server omitted it; a node without IPv6 leaves the tunnel without an IPv6 address, so IPv6 is dropped inside it | A failed `::/0` is reported (`connect_v6_blackhole_route_failed`) and the connect proceeds. This is deliberate and is the one leak-shaped path: it is visible in every fault channel rather than silent. | (reported path) |
| Build the interface | `establish()` (`:2458`) | `null` (consent revoked, another VPN, a route the Builder rejected) is reported as `connect_establish_refused` and fails the connect (`:2036-2047`). | Lifecycle tests |
| API calls around the tunnel | The bypass client's sockets are protected before they connect (`ProtectingSocketFactory`, `ApiRouting.kt`) | A refused protect reports false to OkHttp, never a silent unprotected socket. | `ApiRoutingTest` |

**Verdict:** no silent failure of this class on Android. Every failure either
fails the connect or fails closed, except the IPv6 blackhole route, which is
reported. Nothing changed here.

## iOS and macOS

| Step | Where | On failure | Test |
| --- | --- | --- | --- |
| Validate the server's list | `VPNConnectionConfig.validate` (`ConnectWire.swift:243-251`) | An unparseable CIDR or more than 32 entries refuses the connect before any profile is written. | `TunnelRoutesTests.testValidationRefusesAnInvalidAllowedIP` |
| Build AllowedIPs | `TunnelRoutes.allowedIPs` (new in this PR), called from `VPNManager.buildRedactedWireGuardConfig` (`VPNManager.swift:1005`) | No entries become the full tunnel; `::/0` and `0.0.0.0/0` are always present, as on Android; every entry is written as its network address. | `TunnelRoutesTests` |
| AllowedIPs to `includedRoutes` | WireGuardKit `PacketTunnelSettingsGenerator.includedRoutes` (`PacketTunnelSettingsGenerator.swift:148-170`, vendored) | The interface addresses are masked (`maskedAddress()`, `:154`, `:158`), but each peer AllowedIP becomes a route exactly as written (`:164-170`). Until this PR, `10.0.0.1/8` reached `NEIPv4Route` with its host bits. Now `TunnelRoutes` clears them first. WireGuard masks AllowedIPs itself, so the tunnel carries the same prefix. | `TunnelRoutesTests.testHostBitsAreCleared`, `testEveryRouteIsANetworkAddress` |
| Apply the settings | `WireGuardAdapter.setNetworkSettings` (`WireGuardAdapter.swift:314-338`), on start (`:192`), update (`:258`) and resume (`:446`) | An error from `setTunnelNetworkSettings` throws `.setNetworkSettings`. `adapter.start` then completes with that error and `PacketTunnelProvider` fails `startTunnel` with `TunnelError.adapterFailed` (`PacketTunnelProvider.swift:257-274`), so the system reports the start failed, not Connected. | Guard: `MobileMiscRowsGuardTest` pins the provider's error completion |
| "Protected" | MR-974: only after a handshake is proven on the session (`TunnelProof`) | — | `TunnelProofTests` |
| Kill switch | `includeAllNetworks` + `enforceRoutes` (`KillSwitchProfile.swift`) | With the kill switch on, the system routes everything into the tunnel whatever `includedRoutes` says, and drops what cannot go through it. | `KillSwitchProfileTests` |

### Residual risk (recorded, not changed here)

`WireGuardAdapter.setNetworkSettings` waits 5 s for the system's callback. If
none arrives, it logs "setTunnelNetworkSettings timed out after 5 seconds;
proceeding anyway" (`WireGuardAdapter.swift:336`) and the start continues as if
the settings had applied. Upstream WireGuardKit does this on purpose: on some
networks the callback never fires, and failing there would break the connect.

The exposure is narrow but real. With the kill switch OFF, if the system has
not applied the routes, traffic can leave outside the tunnel. The MR-974
handshake proof does not catch it, because the handshake travels to the
endpoint over the physical interface either way. With the kill switch ON,
`includeAllNetworks` covers it.

Changing this means patching vendored WireGuardKit. The options are to fail the
start on a timeout, or to re-read the applied settings before reporting up.
That is a behaviour decision, so it is left to the lead as a follow-up rather
than made here.

### Excluded routes

iOS sets no `excludedRoutes`: there is no split tunnelling on iOS. The local
network follows `excludeLocalNetworks` (the user's Local Network Sharing
setting, `VPNManager.connect`), and the kill-switch exemptions are decided in
`KillSwitchProfile` (MR-1728).

**Verdict:** one silent shape was found and fixed: a host-bits AllowedIP went
into `includedRoutes` unmasked. One residual path is recorded above. The floors
that already existed (full tunnel, IPv6 blackhole, no split tunnel) now have
tests; before this PR they were inside `VPNManager`, which the unit-test bundle
cannot compile.
