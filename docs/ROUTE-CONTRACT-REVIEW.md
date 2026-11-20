# Route contract review: Android and iOS (MR-953)

Recorded 2026-10-15 against Mobile-Client `main` 4a760dd2 plus the PR that adds
this file. Line numbers are at that tree, and were re-checked unchanged after
`main` moved to a3a33991 (#489 changed build files, notices and one test, none
of the files cited here).

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
| Apply the settings | `WireGuardAdapter.setNetworkSettings` (vendored, local patch 5), on start, update and resume | An error from `setTunnelNetworkSettings` throws `.setNetworkSettings`. `adapter.start` then completes with that error and `PacketTunnelProvider` fails `startTunnel` with `TunnelError.adapterFailed`, so the system reports the start failed, not Connected. No answer at all is asked once more, then fails the same way (MR-953 F4, `TunnelSettingsApply`). | `TunnelSettingsApplyTests`; guard: `MobileMiscRowsGuardTest` pins the provider's error completion |
| "Protected" | MR-974: only after a handshake is proven on the session (`TunnelProof`) | — | `TunnelProofTests` |
| Kill switch | `includeAllNetworks` (`KillSwitchProfile.swift`) | With the kill switch on, the system routes everything into the tunnel whatever `includedRoutes` says, and drops what cannot go through it. | `KillSwitchProfileTests` |
| Route precedence | `enforceRoutes` always on, kill switch or not (`KillSwitchProfile.swift`, MR-953 F2) | The tunnel's routes beat a more specific local or DHCP option 121 route (TunnelVision, CVE-2024-3661). It routes, it does not block. | `KillSwitchProfileTests` (every kill switch x Local Network Sharing combination) |
| IPv6 on a v4-only node | WireGuardKit `PacketTunnelSettingsGenerator.generateNetworkSettings` (vendored, local patch 6) | With no IPv6 address from the node, the tunnel gets a placeholder ULA (`TunnelIPv6Blackhole`) so `::/0` goes into an addressed interface and IPv6 dies inside the tunnel (MR-953 F3). | `TunnelIPv6BlackholeTests` |
| Extension heartbeat | `TunnelHeartbeatTransport` (MR-953 F1) | Sent THROUGH the tunnel: Network.framework on `virtualInterface` (iOS 18 / macOS 15), `createTCPConnectionThroughTunnel` before that, TLS pinned. No route into the tunnel skips the beat; it is never sent around it. | `HeartbeatHTTPTests`, `TunnelRouteContractGuardTests` |

### The MR-953 follow-ups (F1-F4), fixed

The 2026-10-23 audit of this contract found four iOS/macOS gaps; the owner
made them required on 2026-11-13.

- **F1, extension heartbeat outside the tunnel.** NetworkExtension routes the
  provider's own connections outside its own tunnel, so the URLSession
  heartbeat sent the bearer token and key id from the real IP every 30 s. It
  now uses `TunnelHeartbeatTransport` (see the table). Device check still
  required: a capture on the physical interface shows no TLS to
  api.birdo.app during a session, and the server's lastSeen keeps moving with
  the app in the background.
  The cost: the `{valid:false}` answer that told the extension its key was
  revoked or evicted now has to come through the tunnel, and a node that has
  dropped the peer carries nothing. Such a key is noticed as a dead data plane
  (handshake age), not as a revocation, so it takes minutes rather than one
  30 s beat. Faster dead-tunnel detection is MR-1622/MR-1623 (Mobile #605);
  nothing here asks the server from outside the tunnel.
- **F2, routes not enforced with the kill switch off.** `enforceRoutes` was
  tied to the kill switch, so a DHCP option 121 route won over the tunnel, and
  Local Network Sharing OFF was never delivered (`excludeLocalNetworks` only
  works next to `enforceRoutes` or `includeAllNetworks`). Now always on.
- **F3, IPv6 on a v4-only node.** The tunnel had no IPv6 address while `::/0`
  was routed into it, and nothing showed iOS installs that route. Now a
  placeholder ULA makes the blackhole explicit. Device check still required.
- **F4, "proceeding anyway".** The 5 s settings timeout used to carry on as if
  the routes were applied. Now it asks once more and then fails the start or
  the update. A resume after a path change stays paused (traffic held) while
  a timer retries it (`TunnelResumeWatchdog`, 5 s and 10 s later); if it still
  cannot resume, the provider treats the tunnel as dead and sends it through
  the circuit breaker, instead of leaving the system saying connected with no
  connectivity. Upstream signalled its condition without the lock, which
  could turn an early answer into a timeout; that is fixed with it. A success
  from any request is final, an error only from the latest one.

### Excluded routes

iOS sets no `excludedRoutes`: there is no split tunnelling on iOS. The local
network follows `excludeLocalNetworks` (the user's Local Network Sharing
setting, `VPNManager.connect`), and the kill-switch exemptions are decided in
`KillSwitchProfile` (MR-1728).

**Verdict:** one silent shape was found and fixed: a host-bits AllowedIP went
into `includedRoutes` unmasked. The residual path once recorded here (the
settings timeout) is F4 above, fixed with F1-F3. The floors
that already existed (full tunnel, IPv6 blackhole, no split tunnel) now have
tests; before this PR they were inside `VPNManager`, which the unit-test bundle
cannot compile.
