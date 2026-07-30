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
/// `self`. The only cross-context mutable state is `heartbeatTask`, guarded by
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

    /// Dedicated URLSession for the heartbeat. Ephemeral + no cache so no auth
    /// header or body is ever written to disk, matching the host APIClient.
    private lazy var heartbeatSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 30
        config.httpCookieStorage = nil
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config)
    }()

    private lazy var adapter: WireGuardAdapter = {
        WireGuardAdapter(with: self) { [weak self] level, message in
            guard let self else { return }
            // Map WireGuardKit log levels onto os_log; NEVER include the
            // raw message at .info/.error in production builds because it
            // can include endpoints. Mark private.
            let type: OSLogType
            switch level {
            case .verbose: type = .debug
            case .error:   type = .error
            }
            os_log("wg: %{private}@", log: self.log, type: type, message)
        }
    }()

    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        os_log("Starting Birdo VPN tunnel", log: log, type: .info)

        guard let proto = protocolConfiguration as? NETunnelProviderProtocol,
              let configString = proto.providerConfiguration?["wg-config"] as? String else {
            os_log("Missing WireGuard config", log: log, type: .error)
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
        let heartbeatClientVersion = (proto.providerConfiguration?["hb-client-version"] as? String) ?? "1.0.0"

        // Read secrets out of the shared keychain. The IDs are passed via
        // providerConfiguration; the actual material never appears there.
        let privateKeyRef = (proto.providerConfiguration?["wg-private-key-ref"] as? String) ?? "wg_private_key"
        let presharedKeyRef = (proto.providerConfiguration?["wg-preshared-key-ref"] as? String) ?? ""

        guard let privateKey = readSharedKeychain(account: privateKeyRef), !privateKey.isEmpty else {
            os_log("WireGuard private key missing from shared keychain", log: log, type: .error)
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
                os_log("Expected preshared key missing from shared keychain — refusing to build a downgraded tunnel",
                       log: log, type: .error)
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
            os_log("Failed to parse tunnel config: %{public}@",
                   log: log, type: .error, error.localizedDescription)
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
        adapter.stop { [weak self] error in
            if let error {
                os_log("Adapter stop failed: %{public}@",
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
            os_log("Heartbeat reports connection revoked — tearing tunnel down",
                   log: log, type: .info)
            stopHeartbeat()
            // Tearing down here (rather than silently) is the whole point: a
            // revoked/evicted peer must stop routing so the device isn't left
            // blackholing traffic into a peer the server already deleted.
            cancelTunnelWithError(TunnelError.connectionRevoked)
        }
    }

    // MARK: - Helpers

    private func encodeStats(rx: Int64, tx: Int64) -> Data {
        let json = #"{"rx":\#(rx),"tx":\#(tx)}"#
        return json.data(using: .utf8) ?? Data()
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

    var errorDescription: String? {
        switch self {
        case .missingConfig: return "Missing tunnel configuration"
        case .invalidConfig: return "Invalid tunnel configuration"
        case .adapterFailed(let msg): return "Tunnel adapter failed: \(msg)"
        case .connectionRevoked: return "Connection has been revoked. Please reconnect."
        }
    }
}
