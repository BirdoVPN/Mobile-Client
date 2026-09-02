import CommonCrypto
import NetworkExtension
import Security
import WireGuardKit
import os.log

/// WireGuard packet tunnel provider.
///
/// Production wiring:
/// - Uses `WireGuardAdapter` from WireGuardKit to drive the Go tunnel runtime.
/// - PrivateKey & PresharedKey are read from the **shared App Group keychain**
///   (the host app's `VPNManager` writes them there on connect) so they
///   never appear in the NEVPN preferences plaintext blob.
/// - All `os_log` calls use `%{private}@` for any string derived from the
///   config so secrets stay marked-private in sysdiagnose.
/// - Forwards `NEPacketTunnelProvider` → adapter network-settings calls,
///   honouring per-address CIDR for both IPv4 and IPv6.
/// - Implements live transfer-stats IPC for the host app (`stats` message).
/// `@unchecked Sendable` (finding #2): the liveness heartbeat runs in a
/// `Task`, whose closure is `@Sendable` and therefore requires a Sendable
/// `self`. The cross-context mutable state is `heartbeatTask` and
/// `tunnelStartedAt` (via its locked accessor), both guarded by
/// `heartbeatLock`; the lazily-initialised `adapter`/`heartbeatSession` are set
/// once and treated as effectively immutable thereafter (the existing stats /
/// wake callbacks already read them off-thread). Mirrors the codebase's
/// `VPNManager: @unchecked Sendable` posture on the host side.
final class PacketTunnelProvider: NEPacketTunnelProvider, @unchecked Sendable {
    private let log = OSLog(subsystem: "app.birdo.vpn.tunnel", category: "tunnel")

    /// Fully-qualified keychain access group shared with the host app.
    /// The entitlement grants `$(AppIdentifierPrefix)app.birdo.vpn`, and on a
    /// PHYSICAL device an explicit `kSecAttrAccessGroup` must name the
    /// team-prefixed group exactly — the bare `"app.birdo.vpn"` literal fails
    /// with errSecMissingEntitlement (-34018), so every real-device tunnel
    /// start died with missingConfig. The simulator does not enforce this,
    /// which is how the unprefixed value ever appeared to work.
    /// MUST stay in lockstep with `VPNManager.sharedKeychainAccessGroup` on
    /// the host side — the two targets do not share source files, so the
    /// constant is declared once per side (team id is pinned in project.yml).
    private static let sharedAccessGroup = "KPUFGR98A5.app.birdo.vpn"

    /// Liveness heartbeat (finding #2). The 30 s keepalive MUST run here, not
    /// in the host app: iOS suspends the host seconds after the device locks,
    /// so a host-app timer stops and the backend reaps the peer after 5 min —
    /// with the kill switch ON (includeAllNetworks) that blackholes ALL device
    /// traffic. The extension lives exactly as long as the tunnel and its
    /// URLSession rides the live tunnel, so it can keep the peer alive as long
    /// as the tunnel is up.
    private static let heartbeatInterval: TimeInterval = 30
    /// Base URL of the API — same host the host app's APIClient targets.
    private static let apiBaseURL = URL(string: "https://api.birdo.app")!

    /// Serialises access to `heartbeatTask` between `startTunnel`'s completion
    /// and `stopTunnel`, both delivered on NetworkExtension's callback context.
    private let heartbeatLock = NSLock()
    private var heartbeatTask: Task<Void, Never>?

    /// When the tunnel came up, so liveness checks can allow a grace period for
    /// the first handshake instead of tearing down a tunnel that is still
    /// establishing on a slow network. Written on the NetworkExtension callback
    /// context and read from the heartbeat Task — access ONLY via
    /// `tunnelStartedAt` (guarded by `heartbeatLock`, same as `heartbeatTask`).
    private var _tunnelStartedAt: Date?
    private var tunnelStartedAt: Date? {
        get { heartbeatLock.lock(); defer { heartbeatLock.unlock() }; return _tunnelStartedAt }
        set { heartbeatLock.lock(); defer { heartbeatLock.unlock() }; _tunnelStartedAt = newValue }
    }

    /// Identity the circuit breaker attributes failures to: the endpoint host
    /// this tunnel dials (`NEVPNProtocol.serverAddress` — the ENTRY node for a
    /// Multi-Hop session, the only peer this device handshakes with). Keying by
    /// node is what makes "switch to another server" a breaker reset.
    /// Written once in `startTunnel`, read from the heartbeat Task — same lock
    /// as `tunnelStartedAt`.
    private var _breakerNodeId: String = "unknown"
    private var breakerNodeId: String {
        get { heartbeatLock.lock(); defer { heartbeatLock.unlock() }; return _breakerNodeId }
        set { heartbeatLock.lock(); defer { heartbeatLock.unlock() }; _breakerNodeId = newValue }
    }

    /// Dedicated URLSession for the heartbeat. Ephemeral + no cache so no auth
    /// header or body is ever written to disk, matching the host APIClient —
    /// and SPKI-PINNED like the host APIClient. This session carries the
    /// account's bearer token every 30 s for the tunnel's whole life, a
    /// strictly higher-volume exposure than the host app's occasional calls,
    /// so an unpinned session here was a hole in the app's pinning guarantee.
    private lazy var heartbeatSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 30
        config.httpCookieStorage = nil
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config,
                          delegate: HeartbeatPinningDelegate(),
                          delegateQueue: nil)
    }()

    private lazy var adapter: WireGuardAdapter = {
        WireGuardAdapter(with: self) { [weak self] level, message in
            guard let self else { return }
            let type: OSLogType
            switch level {
            case .verbose: type = .debug
            case .error:   type = .error
            }
            // ERRORS are logged PUBLICLY; verbose stays private.
            //
            // These messages were entirely %{private}@, which cost hours on the
            // macOS bring-up: the tunnel failed with a single redacted line
            // reading `wg: <private>`, and recovering it needed a logging
            // configuration profile installed by hand on the test Mac. In the
            // field that is simply undiagnosable.
            //
            // Errors are safe to publish. wireguard-go's error strings describe
            // socket and interface state ("Unable to update bind: listen udp4
            // :0: bind: operation not permitted"), and peer references are
            // already abbreviated to a public-key prefix — a PUBLIC key, by
            // definition. No private key, preshared key or token can reach this
            // closure. Verbose output, which does carry endpoints and per-packet
            // detail, stays redacted.
            //
            // CORRECTION: the reasoning above holds for the `peer` argument but
            // NOT for the wrapped error beside it. wireguard-go logs
            //   device/send.go:135
            //   Errorf("%v - Failed to send handshake initiation: %v", peer, err)
            // and on Apple platforms that `err` originates in
            // `conn.WriteMsgUDP(..., msg.Addr)` (conn/bind_std.go:428, the
            // non-Linux branch), which returns a `*net.OpError` rendering as
            //   write udp 0.0.0.0:0->203.0.113.7:51820: <syscall error>
            // i.e. the VPN server's address and port. Publishing that writes the
            // chosen node into the unified log and into every sysdiagnose taken
            // afterwards, which is exactly what the adapter-start log below
            // already marks %{private}@ to avoid. It also fires precisely when
            // handshakes fail, i.e. on networks already interfering with us.
            //
            // Keep the DISCRIMINANT public and the payload private, the same
            // split `errorDiscriminant` applies to adapter errors. The macOS
            // bring-up failure that motivated public error logging still reads
            // as "Unable to update bind" with no logging profile installed, so
            // field diagnosability is preserved.
            if type == .error {
                os_log("wg: %{public}@ | %{private}@",
                       log: self.log, type: type, Self.logSummary(message), message)
            } else {
                os_log("wg: %{private}@", log: self.log, type: type, message)
            }
        }
    }()

    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        os_log("Starting Birdo VPN tunnel", log: log, type: .info)

        guard let proto = protocolConfiguration as? NETunnelProviderProtocol,
              let configString = proto.providerConfiguration?["wg-config"] as? String else {
            recordFailure("missing wg-config in providerConfiguration")
            completionHandler(TunnelError.missingConfig)
            return
        }

        // Heartbeat credentials (finding #2). Passed via providerConfiguration
        // each connect so the token is fresh; readable only by this app's own
        // appex. Absent keyId/token simply means no extension heartbeat (older
        // host build) — the tunnel still works, it just can't self-report
        // liveness. NEVER log the token.
        let heartbeatKeyId = (proto.providerConfiguration?["hb-key-id"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let heartbeatToken = (proto.providerConfiguration?["hb-access-token"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        // "0.0.0-unknown", never "1.0.0": a fabricated plausible version
        // defeats any server-side version floor and corrupts attribution (the
        // exact regression the codebase eliminated once). MUST stay in
        // lockstep with VPNManager's fallback.
        let rawHbVersion = proto.providerConfiguration?["hb-client-version"] as? String
        let heartbeatClientVersion = (rawHbVersion?.isEmpty == false) ? rawHbVersion! : "0.0.0-unknown"

        // Circuit-breaker identity (P1-ios-redial-loop-blackhole). `serverAddress`
        // is the endpoint host, already set by the host app on every connect —
        // no new providerConfiguration key, and it is exactly the granularity
        // the breaker wants: failures are per-node, so picking another location
        // starts with a clean budget.
        let breakerNode = (proto.serverAddress?.isEmpty == false) ? proto.serverAddress! : "unknown"

        // Read secrets out of the shared keychain. The IDs are passed via
        // providerConfiguration; the actual material never appears there.
        let privateKeyRef = (proto.providerConfiguration?["wg-private-key-ref"] as? String) ?? "wg_private_key"
        let presharedKeyRef = (proto.providerConfiguration?["wg-preshared-key-ref"] as? String) ?? ""

        guard let privateKey = readSharedKeychain(account: privateKeyRef), !privateKey.isEmpty else {
            recordFailure("private key missing from shared keychain")
            completionHandler(TunnelError.missingConfig)
            return
        }
        // The host app sets `wg-preshared-key-ref` to a non-empty account name
        // ONLY when it actually wrote a PSK. If we cannot read it back, the
        // server still has that PSK configured for this peer, so a tunnel built
        // without it can never complete a handshake — and silently omitting it
        // would be a crypto downgrade if the server ever tolerated it. Fail
        // closed and loudly, matching Android's `WireGuardConfigBuilder`, which
        // throws rather than proceed without an expected PSK.
        var presharedKey: String?
        if !presharedKeyRef.isEmpty {
            guard let psk = readSharedKeychain(account: presharedKeyRef), !psk.isEmpty else {
                recordFailure("preshared key missing from shared keychain")
                completionHandler(TunnelError.missingConfig)
                return
            }
            presharedKey = psk
        }

        // Reconstruct a complete `wg-quick` config (with the keys) and parse
        // it into a WireGuardKit `TunnelConfiguration`. The reconstructed
        // string is NEVER persisted — it lives only in this stack frame
        // until WireGuardAdapter copies it into the Go runtime.
        //
        // SEC NOTE: Swift `String` is immutable + value-typed, so we cannot
        // truly zero its backing buffer. Mitigation: keep the variable in
        // the tightest possible scope (an immediately-invoked closure) so
        // ARC drops the only strong reference the moment the parser is done.
        let tunnelConfiguration: TunnelConfiguration
        do {
            tunnelConfiguration = try {
                let fullConfigString = injectSecrets(
                    into: configString,
                    privateKey: privateKey,
                    presharedKey: presharedKey
                )
                return try TunnelConfiguration(
                    fromWgQuickConfig: fullConfigString,
                    called: "birdo"
                )
            }()
        } catch {
            // Type/case discriminant only — TunnelConfiguration parse errors can
            // carry the offending config LINE, i.e. the private key or PSK.
            recordFailure("config parse failed: \(Self.errorDiscriminant(error))")
            completionHandler(TunnelError.invalidConfig)
            return
        }

        adapter.start(tunnelConfiguration: tunnelConfiguration) { [weak self] adapterError in
            // `self` is deliberately NOT guard-let'd first: NetworkExtension
            // waits on `completionHandler` indefinitely, so any path that
            // returns without calling it wedges the extension in "Connecting"
            // until the system kills it. Every branch below completes.
            if let adapterError {
                // %{private}@, NOT %{public}@: `WireGuardAdapterError.dnsResolution`
                // carries `DNSResolutionError.address` — the VPN server endpoint
                // the user connected to. Logging that publicly writes the chosen
                // exit node into the unified log / sysdiagnose, which is exactly
                // the connection metadata the no-logs policy says does not exist.
                os_log("Adapter start failed: %{private}@",
                       log: self?.log ?? .default, type: .error,
                       String(describing: adapterError))
                // Case name only — `.dnsResolution` carries the server endpoint.
                self?.recordFailure("adapter start failed: \(Self.errorDiscriminant(adapterError))")
                completionHandler(TunnelError.adapterFailed(String(describing: adapterError)))
                return
            }
            guard let self else {
                // Provider deallocated between start and callback — fail closed
                // rather than report a tunnel nothing is left to manage.
                completionHandler(TunnelError.adapterFailed("provider deallocated"))
                return
            }
            os_log("Tunnel up", log: self.log, type: .info)
            // finding #2: start the in-extension liveness heartbeat now that
            // the tunnel is up. Only when the host passed a keyId + token.
            // Stamp the start time before the heartbeat begins, so the liveness
            // grace window is measured from when the tunnel actually came up.
            self.tunnelStartedAt = Date()
            self.breakerNodeId = breakerNode
            if let keyId = heartbeatKeyId, !keyId.isEmpty,
               let token = heartbeatToken, !token.isEmpty {
                self.startHeartbeat(keyId: keyId, token: token, clientVersion: heartbeatClientVersion)
            } else {
                os_log("No heartbeat credentials in provider config — extension heartbeat disabled",
                       log: self.log, type: .info)
            }
            completionHandler(nil)
        }
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        // %ld, not %d: `reason.rawValue` is a 64-bit Int and os_log's Swift
        // overlay encodes it as 8 bytes — %d would print a garbage value.
        os_log("Stopping tunnel, reason: %{public}ld", log: log, type: .info, reason.rawValue)
        // finding #2: tear the heartbeat down first so no request outlives the
        // tunnel (and so a cancelTunnelWithError re-entry can't double-start).
        stopHeartbeat()
        tunnelStartedAt = nil
        adapter.stop { [weak self] error in
            if let error {
                // %{private}@ — same WireGuardAdapterError family as the start
                // path; its cases can carry the endpoint.
                os_log("Adapter stop failed: %{private}@",
                       log: self?.log ?? .default, type: .error,
                       String(describing: error))
            }
            completionHandler()
        }
    }

    /// Called by NetworkExtension when the device wakes from sleep.
    /// WireGuardKit's adapter has its own internal `NWPathMonitor` so it
    /// will already have noticed the path change — we just nudge it to
    /// re-resolve the endpoint DNS in case the upstream IP rotated while
    /// we were asleep (e.g. roaming Wi-Fi → 5G across CGNAT boundaries).
    override func wake() {
        os_log("wake() — prompting adapter to re-validate path",
               log: log, type: .info)
        adapter.getRuntimeConfiguration { [weak self] _ in
            // The act of pulling runtime config triggers wg-go's path
            // re-evaluation; nothing else needed.
            _ = self
        }
    }

    override func sleep(completionHandler: @escaping () -> Void) {
        // Keep the tunnel alive while the device is idle; wg-go is
        // configured with PersistentKeepalive=25 to keep the NAT pinhole
        // open. We just acknowledge the call.
        completionHandler()
    }

    /// IPC from the host app. Supported commands:
    ///   "stats" → JSON `{ "rx": <bytes>, "tx": <bytes> }`
    ///   "ping"  → JSON `{ "ok": true }`
    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        guard let command = String(data: messageData, encoding: .utf8) else {
            completionHandler?(nil)
            return
        }
        // REBUILD (Mobile-Client #159): JSON commands. `reconfigure` is the only
        // one today; the plain-string commands below are unchanged.
        if command.hasPrefix("{") {
            reconfigure(messageData, completionHandler: completionHandler)
            return
        }
        switch command {
        case "stats":
            adapter.getRuntimeConfiguration { [weak self] config in
                guard let config else {
                    completionHandler?(self?.encodeStats(rx: 0, tx: 0))
                    return
                }
                let (rx, tx) = self?.parseTransferStats(uapiConfig: config) ?? (0, 0)
                completionHandler?(self?.encodeStats(rx: rx, tx: tx))
            }
        case "ping":
            completionHandler?(#"{"ok":true}"#.data(using: .utf8))
        default:
            completionHandler?(nil)
        }
    }

    // MARK: - Live rebuild (Mobile-Client #159)

    /// Swap the peer on the RUNNING tunnel.
    ///
    /// `WireGuardAdapter.update` (vendored WireGuardAdapter.swift:241) re-applies
    /// the network settings and `wgSetConfig`s the new peer on the existing utun
    /// with `replace_peers=true` (PacketTunnelSettingsGenerator.swift:51),
    /// flipping `reasserting` true→false so the host sees .reasserting →
    /// .connected. The interface — and therefore `includeAllNetworks` — never
    /// drops. Secrets are re-read from the shared keychain by ref exactly as in
    /// `startTunnel`; the material never rides the IPC message. On success the
    /// heartbeat is re-targeted at the new keyId, the breaker identity at the
    /// new node, and the liveness grace restarts for the new peer. Replies
    /// `{"ok":Bool,"error"?:String}`; on failure the previous configuration is
    /// still what the adapter runs and the host restores its snapshot.
    private func reconfigure(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        func reply(_ ok: Bool, _ error: String? = nil) {
            var body: [String: Any] = ["ok": ok]
            if let error { body["error"] = error }
            completionHandler?(try? JSONSerialization.data(withJSONObject: body))
        }
        guard let message = (try? JSONSerialization.jsonObject(with: messageData)) as? [String: Any],
              message["cmd"] as? String == "reconfigure",
              let configString = message["wg-config"] as? String, !configString.isEmpty else {
            reply(false, "bad reconfigure message")
            return
        }
        let privateKeyRef = (message["wg-private-key-ref"] as? String) ?? "wg_private_key"
        let presharedKeyRef = (message["wg-preshared-key-ref"] as? String) ?? ""
        guard let privateKey = readSharedKeychain(account: privateKeyRef), !privateKey.isEmpty else {
            reply(false, "private key missing from shared keychain")
            return
        }
        // Same fail-closed rule as startTunnel: a non-empty ref means the host
        // wrote a PSK and the server expects it — never build without it.
        var presharedKey: String?
        if !presharedKeyRef.isEmpty {
            guard let psk = readSharedKeychain(account: presharedKeyRef), !psk.isEmpty else {
                reply(false, "preshared key missing from shared keychain")
                return
            }
            presharedKey = psk
        }
        let tunnelConfiguration: TunnelConfiguration
        do {
            tunnelConfiguration = try TunnelConfiguration(
                fromWgQuickConfig: injectSecrets(into: configString, privateKey: privateKey, presharedKey: presharedKey),
                called: "birdo"
            )
        } catch {
            // Discriminant only — a parse error can carry the offending line.
            reply(false, "config parse failed: \(Self.errorDiscriminant(error))")
            return
        }
        let keyId = ((message["hb-key-id"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let token = ((message["hb-access-token"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let rawVersion = message["hb-client-version"] as? String
        let clientVersion = (rawVersion?.isEmpty == false) ? rawVersion! : "0.0.0-unknown"
        let rawServer = message["server"] as? String
        let breakerNode = (rawServer?.isEmpty == false) ? rawServer! : "unknown"

        os_log("Reconfiguring tunnel in place", log: log, type: .info)
        adapter.update(tunnelConfiguration: tunnelConfiguration) { [weak self] error in
            guard let self else {
                reply(false, "provider deallocated")
                return
            }
            if let error {
                // %{private}@ — the adapter error family can carry the endpoint.
                os_log("Adapter update failed: %{private}@", log: self.log, type: .error,
                       String(describing: error))
                reply(false, "adapter update failed: \(Self.errorDiscriminant(error))")
                return
            }
            // The new peer starts its own liveness clock and its own heartbeat.
            self.stopHeartbeat()
            self.tunnelStartedAt = Date()
            self.breakerNodeId = breakerNode
            if !keyId.isEmpty, !token.isEmpty {
                self.startHeartbeat(keyId: keyId, token: token, clientVersion: clientVersion)
            } else {
                os_log("No heartbeat credentials in reconfigure — extension heartbeat disabled",
                       log: self.log, type: .info)
            }
            os_log("Tunnel reconfigured in place", log: self.log, type: .info)
            reply(true)
        }
    }

    // MARK: - Heartbeat (finding #2)

    /// Start the 30 s liveness loop. Mirrors the host `APIClient.heartbeat`
    /// request exactly: `POST https://api.birdo.app/vpn/heartbeat/{keyId}` with
    /// `Authorization: Bearer <token>` + `X-Desktop-Client: birdo-ios`. The
    /// backend answers `{ valid, serverOnline, message? }`. On `valid == false`
    /// (revoked / evicted) we tear the tunnel down with `cancelTunnelWithError`
    /// so a dead peer stops blackholing traffic even while the host app is
    /// suspended. Transport failures and a stale-token 401 are IGNORED — a
    /// missed heartbeat or an expired token must never kill a live tunnel.
    private func startHeartbeat(keyId: String, token: String, clientVersion: String) {
        heartbeatLock.lock()
        heartbeatTask?.cancel()
        let interval = Self.heartbeatInterval
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
                if Task.isCancelled { return }
                guard let self else { return }
                // Data-plane liveness FIRST, and independently of the network
                // heartbeat below. The heartbeat cannot detect a dead tunnel: its
                // URLSession rides the live tunnel, so the one condition worth
                // detecting is exactly the condition that disables the detector.
                await self.checkDataPlaneLiveness()
                await self.sendHeartbeat(keyId: keyId, token: token, clientVersion: clientVersion)
            }
        }
        heartbeatLock.unlock()
    }

    private func stopHeartbeat() {
        heartbeatLock.lock()
        heartbeatTask?.cancel()
        heartbeatTask = nil
        heartbeatLock.unlock()
    }

    /// One heartbeat round-trip. `valid == false` → cancel the tunnel. Anything
    /// else (network error, 401 from a stale token, 5xx, decode surprise) is
    /// logged and swallowed so the tunnel survives.
    private func sendHeartbeat(keyId: String, token: String, clientVersion: String) async {
        guard let encodedKeyId = keyId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
              let url = URL(string: "/vpn/heartbeat/\(encodedKeyId)", relativeTo: Self.apiBaseURL),
              url.scheme?.lowercased() == "https" else {
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("birdo-ios", forHTTPHeaderField: "X-Desktop-Client")
        request.setValue("Birdo-iOS/\(clientVersion) (iOS)", forHTTPHeaderField: "User-Agent")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await heartbeatSession.data(for: request)
        } catch {
            // Transient transport failure — NEVER tear down over a missed beat.
            os_log("Heartbeat transport failure (ignored)", log: log, type: .info)
            return
        }
        guard let http = response as? HTTPURLResponse else { return }
        // A stale/expired token surfaces as 401 here (the extension cannot
        // refresh tokens — it has no refresh token). Do NOT tear down: the host
        // app passes a fresh token on the next connect, and the kernel tunnel's
        // PersistentKeepalive keeps handshaking so the backend's handshake-age
        // check keeps the peer alive. Only an explicit {valid:false} tears down.
        guard (200...299).contains(http.statusCode) else {
            os_log("Heartbeat HTTP %{public}ld (ignored)", log: log, type: .info, http.statusCode)
            return
        }
        // Fail OPEN on any decode surprise: an absent/garbled `valid` must never
        // kill a live tunnel (matches the host HeartbeatResult default).
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return
        }
        let valid = (json["valid"] as? Bool) ?? true
        if !valid {
            os_log("Heartbeat reports connection revoked", log: log, type: .info)
            // Tearing down here (rather than silently) is the whole point: a
            // revoked/evicted peer must stop routing so the device isn't left
            // blackholing traffic into a peer the server already deleted.
            // Routed through the breaker so it can never become an unbounded
            // teardown ↔ on-demand-re-dial loop — see `handleDataPlaneFailure`.
            handleDataPlaneFailure(.revoked, error: TunnelError.connectionRevoked)
        }
    }

    // MARK: - Circuit breaker (P1-ios-redial-loop-blackhole)

    /// Single exit for every EXTENSION-INITIATED teardown, so the re-dial budget
    /// is enforced in one place.
    ///
    /// ## The interaction this is built around
    ///
    /// The extension cannot disarm the on-demand rule — `NETunnelProviderManager`
    /// preferences are host-app API and the appex has no access to them. So
    /// `cancelTunnelWithError` from here is not "stop"; with a rule armed it is
    /// "stop, and be restarted immediately". That is the loop: liveness kills the
    /// tunnel, on-demand revives it, WireGuardKit brings the interface up against
    /// a peer that will never answer, liveness kills it again — with
    /// `includeAllNetworks` blackholing every packet in between, and the host app
    /// suspended so nothing in-app is counting.
    ///
    /// ## What this does instead
    ///
    /// Under budget → cancel exactly as before. A bounded re-dial is a genuine
    /// recovery for a NAT rebind or a node restart, and removing it would trade
    /// this bug for a worse one.
    ///
    /// Over budget → the breaker trips, and the response depends on whether the
    /// host has an on-demand rule armed (mirrored into the shared keychain by
    /// `VPNManager`, because the appex cannot read the real flag):
    ///
    ///   * **not armed** → `cancelTunnelWithError`. Nothing re-dials, the tunnel
    ///     stays down, and traffic immediately flows on the physical interface.
    ///     A complete, unattended fail-open with no user action at all.
    ///   * **armed** → do NOT cancel. Cancelling is precisely what feeds the
    ///     loop. Hold the (dead) tunnel in place, stop the heartbeat/liveness
    ///     loop, and leave the tripped record for the host. Holding is not
    ///     "worse than cancelling": under `includeAllNetworks` both states drop
    ///     every packet, but holding stops the thrash, stops the server-side
    ///     peer churn, and — crucially — leaves `NEVPNStatus == .connected` so
    ///     the host app can tear it down cleanly the moment it runs.
    ///     `VpnViewModel.checkCircuitBreaker()` then disarms on-demand, clears
    ///     `includeAllNetworks` and stops the tunnel: THAT is the fail-open, and
    ///     it fires on app foreground, on the 30 s host heartbeat and on launch.
    ///
    /// The escape hatch never depends on this code being right: the host clears
    /// the breaker on every user-initiated connect, and the trip lapses after
    /// `TunnelCircuitBreaker.tripCooldown` on its own.
    private func handleDataPlaneFailure(_ kind: TunnelFailureKind, error: Error) {
        let store = TunnelBreakerStore.shared
        let record = store.recordFailure(kind, nodeId: breakerNodeId)
        let tripped = TunnelCircuitBreaker.isTripped(record, now: Date())

        os_log("Data-plane failure (%{public}@) #%{public}ld — tripped=%{public}@ onDemandArmed=%{public}@",
               log: log, type: .error,
               kind.rawValue, record.consecutiveFailures,
               tripped ? "yes" : "no", store.onDemandArmed ? "yes" : "no")

        guard tripped else {
            cancelTunnelWithError(error)
            return
        }

        // Stop probing: the verdict is in, and further polls would only append
        // failures to a record that is already tripped.
        stopHeartbeat()

        if store.onDemandArmed {
            os_log("Circuit breaker tripped with on-demand armed — holding the tunnel instead of re-dialling. Open Birdo to restore traffic.",
                   log: log, type: .error)
            // Deliberately NO cancelTunnelWithError here. See the doc comment.
        } else {
            os_log("Circuit breaker tripped; no on-demand rule armed — stopping for good, traffic falls back to the physical interface",
                   log: log, type: .error)
            cancelTunnelWithError(TunnelError.circuitBreakerTripped)
        }
    }

    // MARK: - Helpers

    private func encodeStats(rx: Int64, tx: Int64) -> Data {
        let json = #"{"rx":\#(rx),"tx":\#(tx)}"#
        return json.data(using: .utf8) ?? Data()
    }

    /// How stale a WireGuard handshake may get before we call the tunnel dead.
    ///
    /// WireGuard rekeys well inside two minutes on any live session, so a
    /// handshake older than this means the peer is unreachable — not idle.
    /// Matches Android's TunnelMonitor threshold so the platforms agree.
    private static let maxHandshakeAge: TimeInterval = 180

    /// Grace period after connecting before liveness is enforced, so the very
    /// first handshake has time to complete on a slow network.
    private static let livenessGrace: TimeInterval = 60

    /// Detect a tunnel that is up but carrying nothing, and tear it down so the
    /// on-demand rule can re-dial.
    ///
    /// On a server crash, a NAT rebind or blocked UDP, WireGuardKit keeps the
    /// utun interface up and `NEVPNStatus` stays `.connected`. With
    /// `AllowedIPs 0.0.0.0/0` the tunnel swallows every packet, so the device has
    /// no connectivity at all — and because the tunnel never disconnects, the
    /// on-demand rule never fires. The user sees "Connected" and a dead network,
    /// indefinitely, with no path to recovery except toggling the VPN by hand.
    ///
    /// Windows requires two independent death signals and Android watches
    /// handshake age; iOS had neither. Handshake age is the signal available here
    /// without new API, entitlements or background modes.
    private func checkDataPlaneLiveness() async {
        guard let connectedAt = tunnelStartedAt,
              Date().timeIntervalSince(connectedAt) > Self.livenessGrace else { return }

        let uapi: String? = await withCheckedContinuation { continuation in
            adapter.getRuntimeConfiguration { config in
                continuation.resume(returning: config)
            }
        }
        guard let uapi else { return }

        // `last_handshake_time_sec` is absolute unix seconds, per peer. Take the
        // most recent across peers: any live peer means the data plane works.
        var newest: Int64 = 0
        for line in uapi.split(separator: "\n") where line.hasPrefix("last_handshake_time_sec=") {
            let value = Int64(line.dropFirst("last_handshake_time_sec=".count)) ?? 0
            newest = max(newest, value)
        }

        // Zero means "never handshaked". Past the grace window that is itself a
        // failure — the tunnel came up and never established.
        let age = newest == 0
            ? Date().timeIntervalSince(connectedAt)
            : Date().timeIntervalSince1970 - Double(newest)

        if age > Self.maxHandshakeAge {
            // CLASSIFY, don't just count (P1 requirement 2). `newest == 0` means
            // the tunnel came up and never handshaked at all — re-dialling the
            // same endpoint is nearly worthless, so it gets a small budget and
            // the user is steered to a different location. A handshake that
            // existed and went stale is the NAT-rebind / node-restart shape,
            // where re-dialling usually works, so it gets the largest budget.
            let kind: TunnelFailureKind = (newest == 0) ? .neverEstablished : .diedAfterHandshake
            os_log("Data plane dead: last handshake %{public}.0fs ago (%{public}@)",
                   log: log, type: .error, age, kind.rawValue)
            handleDataPlaneFailure(kind, error: NSError(
                domain: "app.birdo.vpn.tunnel",
                code: -1001,
                userInfo: [NSLocalizedDescriptionKey: "The VPN connection stopped responding."]
            ))
        }
    }

    /// Sum `rx_bytes=` / `tx_bytes=` lines from the wg-go UAPI dump.
    private func parseTransferStats(uapiConfig: String) -> (rx: Int64, tx: Int64) {
        var rx: Int64 = 0
        var tx: Int64 = 0
        // Saturating accumulation: clamp to Int64.max on overflow rather than
        // silently wrapping to a negative value (wraparound would corrupt the
        // monotonically-increasing stats reported to the host app).
        func saturatingAdd(_ a: Int64, _ b: Int64) -> Int64 {
            let (sum, overflow) = a.addingReportingOverflow(b)
            return overflow ? Int64.max : sum
        }
        for line in uapiConfig.split(separator: "\n") {
            if line.hasPrefix("rx_bytes=") {
                rx = saturatingAdd(rx, Int64(line.dropFirst("rx_bytes=".count)) ?? 0)
            } else if line.hasPrefix("tx_bytes=") {
                tx = saturatingAdd(tx, Int64(line.dropFirst("tx_bytes=".count)) ?? 0)
            }
        }
        return (rx, tx)
    }

    /// Build a wg-quick string by re-inserting the secrets pulled from the
    /// shared keychain. Idempotent: if the input already contains a
    /// `PrivateKey =` line we do not duplicate it.
    private func injectSecrets(into config: String, privateKey: String, presharedKey: String?) -> String {
        var lines = config.components(separatedBy: "\n")
        // Insert PrivateKey after the [Interface] header if not present.
        if !lines.contains(where: { $0.trimmingCharacters(in: .whitespaces).hasPrefix("PrivateKey") }) {
            if let idx = lines.firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == "[Interface]" }) {
                lines.insert("PrivateKey = \(privateKey)", at: idx + 1)
            }
        }
        // Insert PresharedKey after the [Peer] header if we have one and it isn't already present.
        if let psk = presharedKey, !psk.isEmpty,
           !lines.contains(where: { $0.trimmingCharacters(in: .whitespaces).hasPrefix("PresharedKey") }) {
            if let idx = lines.firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == "[Peer]" }) {
                lines.insert("PresharedKey = \(psk)", at: idx + 1)
            }
        }
        return lines.joined(separator: "\n")
    }

    /// Read a shared-keychain string by account, scoped to the
    /// `app.birdo.vpn.shared` service that the host app writes to.
    /// Record why the tunnel refused to start, where the host app can read it.
    ///
    /// The extension is a separate process with no UI. Its `os_log` output is
    /// invisible to a TestFlight user, and `completionHandler(error)` reaches
    /// NetworkExtension rather than the app — iOS does not reliably forward the
    /// reason to `lastDisconnectError`. So a provider that fails at startup
    /// presents in the app as a tunnel that simply never comes up, with nothing
    /// to act on.
    ///
    /// The shared keychain is reused as the channel deliberately: it is already
    /// entitled on both sides and already works, whereas an App Group would mean
    /// a new entitlement and regenerating both provisioning profiles.
    ///
    /// Stable, payload-free discriminant for an error: the enum case name (or
    /// type name) WITHOUT associated values — so `.dnsResolution(endpoint)`
    /// logs as "dnsResolution", never the endpoint, and a parse error never
    /// logs the config line (which can be the private key).
    private static func errorDiscriminant(_ error: Error) -> String {
        let mirror = Mirror(reflecting: error)
        if mirror.displayStyle == .enum, let caseName = mirror.children.first?.label {
            return "\(type(of: error)).\(caseName)"
        }
        return String(describing: type(of: error))
    }

    /// Payload-free summary of a tunnel log line, for the PUBLIC half of the
    /// split log in `wgLogger`.
    ///
    /// Take the text before the first ": " -- Go wraps causes as
    /// `"<context>: <cause>"`, and the cause is what carries addresses
    /// (`*net.OpError` renders as `"write udp <laddr>-><raddr>: ..."`) -- then
    /// scrub what remains.
    ///
    /// The scrub is NOT belt-and-braces. The first draft published the head
    /// unconditionally on the theory that an address always follows the
    /// separator; that is false for a site in this very repo.
    /// `WireGuardAdapter.swift:409` logs, at error level,
    ///   "Failed to resolve endpoint \(resolutionError.address): ..."
    /// and `DNSResolutionError.address` is the bare hostname with no port
    /// (`DNSResolver.swift:58`), so the endpoint sits BEFORE the first ": ".
    /// That line fires on `startTunnel` and on every network change, i.e.
    /// exactly when a user is roaming.
    ///
    /// A second draft caught it only because production node names contain a
    /// digit (`de1.birdo.app`): the test required `isNumber` before treating a
    /// token as an address, so `frankfurt.birdo.app` would have published
    /// cleanly. A naming convention enforced nowhere is not a privacy control.
    ///
    /// So: redact per TOKEN and on STRUCTURE alone. Any whitespace-separated
    /// token containing "." or ":" is replaced, which covers hostnames, IPv4,
    /// IPv6 and host:port with no digit test. wireguard-go's abbreviated peer
    /// id is `peer(____<U+2026>____)` over the base64 alphabet, which contains
    /// neither character, so the useful context survives -- as does the macOS
    /// bring-up failure that motivated public error logging ("Unable to update
    /// bind"). Android redacts hostnames structurally too (`BirdoApp.kt`);
    /// this brings the twins back into line.
    static func logSummary(_ message: String) -> String {
        let head = message.components(separatedBy: ": ").first ?? message
        let scrubbed = head
            .split(separator: " ", omittingEmptySubsequences: false)
            .map { token -> String in
                token.contains(".") || token.contains(":") ? "<redacted>" : String(token)
            }
            .joined(separator: " ")
        return String(scrubbed.prefix(120))
    }

    /// Diagnostic strings only — never key material, never the endpoint.
    /// Callers must pass pre-sanitised discriminants (see `errorDiscriminant`);
    /// the os_log below stays %{private}@ as defence in depth so a future call
    /// site that slips an interpolated error in cannot reach sysdiagnose.
    private func recordFailure(_ reason: String) {
        os_log("Tunnel start failed: %{private}@", log: log, type: .error, reason)
        guard let data = reason.data(using: .utf8) else { return }
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "app.birdo.vpn.shared",
            kSecAttrAccount as String: "last_tunnel_error",
            kSecAttrAccessGroup as String: Self.sharedAccessGroup,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    private func readSharedKeychain(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "app.birdo.vpn.shared",
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: Self.sharedAccessGroup,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

// MARK: - Errors

enum TunnelError: Error, LocalizedError {
    case missingConfig
    case invalidConfig
    case adapterFailed(String)
    /// The backend heartbeat reported the peer is no longer valid (revoked or
    /// free-tier evicted). Tearing down on this is what stops a dead peer from
    /// blackholing all traffic under the kill switch.
    case connectionRevoked
    /// The re-dial circuit breaker ran out of budget for this node
    /// (P1-ios-redial-loop-blackhole). Only used on the path where no on-demand
    /// rule is armed, i.e. where stopping actually restores traffic.
    case circuitBreakerTripped

    var errorDescription: String? {
        switch self {
        case .missingConfig: return "Missing tunnel configuration"
        case .invalidConfig: return "Invalid tunnel configuration"
        case .adapterFailed(let msg): return "Tunnel adapter failed: \(msg)"
        case .connectionRevoked: return "Connection has been revoked. Please reconnect."
        case .circuitBreakerTripped:
            return "Birdo stopped reconnecting to this server after repeated failures."
        }
    }
}

// MARK: - Certificate Pinning (extension copy)

/// SPKI-pinning URLSession delegate for the heartbeat session.
///
/// The appex and the host app do not share source files, so — exactly like
/// `sharedAccessGroup` above — this is a deliberate per-target copy of the
/// host's `APIClient.PinningDelegate`, and the PIN SET below MUST stay in
/// lockstep with it (and with Android's `NetworkModule.kt` /
/// `network_security_config.xml`). Pin-set expiration: 2027-06-01.
///
/// Fails CLOSED on every uncertain path: standard CA validation first, then at
/// least one certificate in the chain must match a pinned SPKI hash; an
/// unreadable chain or key cancels the challenge rather than falling through.
private final class HeartbeatPinningDelegate: NSObject, URLSessionDelegate {
    /// Base64-encoded SHA-256 hashes of the SubjectPublicKeyInfo (SPKI) of
    /// every certificate we accept, anywhere in the chain.
    ///
    /// SOURCE OF TRUTH: `third_party/cert-pins.json` (vendored from
    /// `birdo-shared/cert-pins.json`); `scripts/check-cert-pins.sh` enforces
    /// that this set matches it, and matches the three other copies, in CI.
    ///
    /// This delegate fails CLOSED, which makes a stale pin set here worse than
    /// anywhere else in the app: the heartbeat simply stops. Live chain
    /// measured 2026-08-22: leaf CN=birdo.app -> WE1 -> GTS Root R4.
    private static let pins: Set<String> = [
        // --- live in the presented chain ---
        // WE1 — Google Trust Services intermediate
        "kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",
        // GTS Root R4 — the actual trust anchor api.birdo.app chains to.
        // ADDED 2026-08-22. Until now WE1 was the ONLY pin here that could ever
        // match, and this delegate cancels the challenge when nothing matches,
        // so a leaf moving off WE1 would have killed the tunnel heartbeat
        // outright with no remote recovery.
        "mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=",

        // --- dormant: cross-CA insurance, not in today's chain ---
        // GlobalSign ECC Root CA - R4 (alternate Google cross-sign anchor)
        "CLOmM1/OXvSPjw5UOYbAf9GKOxImEp9hhku9W90fHMk=",
        // ISRG Root X1 — Let's Encrypt root. A default Let's Encrypt server
        // sends the leaf + ONE issuing intermediate and never this root, so the
        // four issuing intermediates below are what make the backup real.
        "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",
        // Let's Encrypt R10 (RSA issuing intermediate)
        "K7rZOrXHknnsEhUH8nLL4MZkejquUuIvOIr6tCa0rbo=",
        // Let's Encrypt R11 (RSA issuing intermediate)
        "bdrBhpj38ffhxpubzkINl0rG+UyossdhcBYj+Zx2fcc=",
        // Let's Encrypt E5 (ECDSA issuing intermediate)
        "NYbU7PBwV4y9J67c4guWTki8FJ+uudrXL0a4V4aRcrg=",
        // Let's Encrypt E6 (ECDSA issuing intermediate)
        "0Bbh/jEZSKymTy3kTOhsmlHKBB32EDu1KojrP3YfV9c=",
    ]

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        var trustError: CFError?
        guard SecTrustEvaluateWithError(trust, &trustError) else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate] else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        for cert in chain {
            guard let pubKey = SecCertificateCopyKey(cert),
                  let pubKeyData = SecKeyCopyExternalRepresentation(pubKey, nil) as Data? else {
                continue
            }
            let spkiHeader = Self.spkiHeader(for: pubKey)
            var hashable = spkiHeader
            hashable.append(pubKeyData)
            let hash = Self.sha256(hashable).base64EncodedString()
            if Self.pins.contains(hash) {
                completionHandler(.useCredential, URLCredential(trust: trust))
                return
            }
        }
        completionHandler(.cancelAuthenticationChallenge, nil)
    }

    /// ASN.1 SubjectPublicKeyInfo header bytes that prefix the raw key data
    /// produced by `SecKeyCopyExternalRepresentation`. RSA 2048/4096 +
    /// EC P-256 / P-384 cover every CA in the pin set.
    private static func spkiHeader(for key: SecKey) -> Data {
        let attrs = SecKeyCopyAttributes(key) as? [CFString: Any] ?? [:]
        let type = (attrs[kSecAttrKeyType] as? String) ?? ""
        let size = (attrs[kSecAttrKeySizeInBits] as? Int) ?? 0
        let rsaType = kSecAttrKeyTypeRSA as String
        let ecType = kSecAttrKeyTypeECSECPrimeRandom as String
        switch (type, size) {
        case (rsaType, 2048):
            return Data([
                0x30, 0x82, 0x01, 0x22, 0x30, 0x0d, 0x06, 0x09,
                0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
                0x01, 0x05, 0x00, 0x03, 0x82, 0x01, 0x0f, 0x00,
            ])
        case (rsaType, 4096):
            return Data([
                0x30, 0x82, 0x02, 0x22, 0x30, 0x0d, 0x06, 0x09,
                0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
                0x01, 0x05, 0x00, 0x03, 0x82, 0x02, 0x0f, 0x00,
            ])
        case (ecType, 256):
            return Data([
                0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86,
                0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
                0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03,
                0x42, 0x00,
            ])
        case (ecType, 384):
            return Data([
                0x30, 0x76, 0x30, 0x10, 0x06, 0x07, 0x2a, 0x86,
                0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x05, 0x2b,
                0x81, 0x04, 0x00, 0x22, 0x03, 0x62, 0x00,
            ])
        default:
            return Data() // Unknown key type — hash will not match any pin.
        }
    }

    private static func sha256(_ data: Data) -> Data {
        var hash = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes {
            _ = CC_SHA256($0.baseAddress, CC_LONG(data.count), &hash)
        }
        return Data(hash)
    }
}
