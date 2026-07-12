import Foundation
import SwiftUI
import NetworkExtension
import CryptoKit

/// Manages VPN connection state, the server list, and port forwarding.
///
/// Connection lifecycle (parity with Android's VpnManager):
///   1. Generate a FRESH WireGuard keypair per connect — the private key never
///      leaves the device and the server never learns it (contract rule 1).
///   2. POST vpn/connect (or vpn/multi-hop/connect) with our public key.
///   3. Build the tunnel config from the response + our private key.
///   4. Hold `keyId`; heartbeat every 30 s; on `valid: false` tear down with a
///      user-visible error (the server has revoked the slot).
///   5. On user disconnect, DELETE vpn/connections/{keyId} best-effort — the
///      local tunnel comes down regardless.
@MainActor
final class VpnViewModel: ObservableObject {
    // MARK: - Connection State
    @Published var isConnected = false
    @Published var isConnecting = false
    @Published var error: String?

    // MARK: - Servers
    @Published var servers: [ServerInfo] = []
    @Published var selectedServer: ServerInfo?
    @Published var favoriteIds: Set<String> = []
    @Published var isLoadingServers = false

    // MARK: - Stats
    @Published var bytesReceived: Int64 = 0
    @Published var bytesSent: Int64 = 0
    @Published var connectedSince: Date?

    // MARK: - Features (real state, not decoration)
    /// True when the tunnel dropped UNEXPECTEDLY while the kill switch was
    /// armed — i.e. traffic is currently being blocked on purpose.
    @Published var killSwitchActive = false
    /// Always false on iOS today: there is no Xray engine, so stealth is
    /// never requested (the Settings toggle is shown disabled).
    @Published var stealthActive = false
    /// True when this connection's PSK came from the bilateral ML-KEM
    /// exchange (or the server confirmed quantum mode).
    @Published var quantumActive = false

    // MARK: - Port Forwarding
    @Published var portForwards: [PortForwardEntry] = []

    // MARK: - Private
    private let api: APIClient
    private let vpnManager: VPNManager
    private var statsTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    /// Server-side connection slot for the ACTIVE tunnel.
    private var activeKeyId: String?
    /// Set while a deliberate user disconnect is in flight so the status
    /// observer can tell it apart from an unexpected drop.
    private var userInitiatedDisconnect = false
    private var wasConnected = false

    private static let heartbeatInterval: TimeInterval = 30

    init(api: APIClient = .shared, vpnManager: VPNManager = .shared) {
        self.api = api
        self.vpnManager = vpnManager

        if let ids = UserDefaults.standard.stringArray(forKey: "favorite_servers") {
            favoriteIds = Set(ids)
        }

        vpnManager.onStatusChange = { [weak self] status in
            Task { @MainActor in
                self?.handleStatusChange(status)
            }
        }
    }

    // MARK: - Server Management

    /// Last-fetched server list cache. Mirrors the Android 60-second TTL
    /// in `BirdoRepository` so rapid screen revisits don't hammer the API
    /// or churn the radio. Pass `forceRefresh: true` from a pull-to-refresh
    /// gesture to bypass the TTL.
    private var serverCacheTimestamp: Date?
    private static let serverCacheTTL: TimeInterval = 60

    func loadServers(forceRefresh: Bool = false) {
        if !forceRefresh,
           !servers.isEmpty,
           let ts = serverCacheTimestamp,
           Date().timeIntervalSince(ts) < Self.serverCacheTTL {
            return
        }
        isLoadingServers = true
        Task {
            defer { isLoadingServers = false }
            do {
                let list = try await api.fetchServers()
                servers = list
                serverCacheTimestamp = Date()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    func selectServer(_ server: ServerInfo) {
        selectedServer = server
    }

    func toggleFavorite(_ serverId: String) {
        if favoriteIds.contains(serverId) {
            favoriteIds.remove(serverId)
        } else {
            favoriteIds.insert(serverId)
        }
        UserDefaults.standard.set(Array(favoriteIds), forKey: "favorite_servers")
    }

    // MARK: - Connection

    func connect() {
        guard let server = selectedServer else {
            error = "Select a server first"
            return
        }
        isConnecting = true
        error = nil

        Task {
            do {
                let prefs = TunnelPreferences.load()
                let keyPair = WireGuardKeyPair()
                let pqPk = prefs.quantumProtection
                    ? BirdoPQManager.shared.clientPublicKeyBase64()
                    : nil

                let response = try await api.connect(ConnectRequest(
                    serverNodeId: server.id,
                    deviceName: nil,
                    preferredRegion: nil,
                    clientPublicKey: keyPair.publicKeyBase64,
                    stealthMode: false,   // no Xray engine on iOS — never request stealth
                    quantumProtection: pqPk != nil,
                    pqClientPublicKey: pqPk,
                    integrityToken: nil   // App Attest is future work; policy accepts absent
                ))
                try await startTunnel(with: response,
                                      clientPrivateKey: keyPair.base64Key,
                                      preferences: prefs)
            } catch {
                self.error = error.localizedDescription
                isConnecting = false
            }
        }
    }

    func connectMultiHop(entryId: String, exitId: String) {
        isConnecting = true
        error = nil

        Task {
            do {
                let prefs = TunnelPreferences.load()
                let keyPair = WireGuardKeyPair()
                let pqPk = prefs.quantumProtection
                    ? BirdoPQManager.shared.clientPublicKeyBase64()
                    : nil

                let response = try await api.connectMultiHop(MultiHopConnectRequest(
                    entryNodeId: entryId,
                    exitNodeId: exitId,
                    deviceName: nil,
                    clientPublicKey: keyPair.publicKeyBase64,
                    stealthMode: false,
                    quantumProtection: pqPk != nil,
                    pqClientPublicKey: pqPk,
                    integrityToken: nil   // same field as single-hop, same rule
                ))
                try await startTunnel(with: response.asConnectResponse,
                                      clientPrivateKey: keyPair.base64Key,
                                      preferences: prefs)
            } catch {
                self.error = error.localizedDescription
                isConnecting = false
            }
        }
    }

    private func startTunnel(with response: ConnectResponse,
                             clientPrivateKey: String,
                             preferences: TunnelPreferences) async throws {
        var config = try VPNConnectionConfig(response: response,
                                             clientPrivateKey: clientPrivateKey)
        config = Self.applyOverrides(config, preferences: preferences)

        try await vpnManager.connect(config: config, preferences: preferences)

        activeKeyId = response.keyId
        connectedSince = Date()
        isConnected = true
        isConnecting = false
        wasConnected = true
        killSwitchActive = false
        stealthActive = response.stealthEnabled ?? false
        quantumActive = (response.quantumEnabled ?? false)
            || (config.quantumEnabled ?? false)
        startStatsTimer()
        startHeartbeat()
    }

    /// User settings that legitimately override server-provided values:
    /// custom DNS, MTU, and the WireGuard port (auto / 51820 / 53 / custom).
    private static func applyOverrides(_ config: VPNConnectionConfig,
                                       preferences p: TunnelPreferences) -> VPNConnectionConfig {
        var dns = config.dns
        if p.customDnsEnabled {
            let custom = [p.customDnsPrimary, p.customDnsSecondary]
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
            if !custom.isEmpty { dns = custom }
        }
        var port = config.serverPort
        if p.wireGuardPort != "auto", let chosen = Int(p.wireGuardPort),
           (1...65535).contains(chosen) {
            port = chosen
        }
        return VPNConnectionConfig(
            serverAddress: config.serverAddress,
            serverPort: port,
            privateKey: config.privateKey,
            publicKey: config.publicKey,
            presharedKey: config.presharedKey,
            addresses: config.addresses,
            dns: dns,
            allowedIPs: config.allowedIPs,
            mtu: p.mtuOverride ?? config.mtu,
            persistentKeepalive: config.persistentKeepalive,
            quantumEnabled: config.quantumEnabled,
            rosenpassPublicKey: config.rosenpassPublicKey,
            rosenpassEndpoint: config.rosenpassEndpoint
        )
    }

    func disconnect() {
        userInitiatedDisconnect = true
        let keyId = activeKeyId
        activeKeyId = nil
        stopHeartbeat()

        Task {
            // Best-effort server-side teardown: never gate the local
            // disconnect on network success.
            if let keyId {
                await api.disconnectConnection(keyId: keyId)
            }
            vpnManager.disconnect()
            isConnected = false
            isConnecting = false
            connectedSince = nil
            bytesReceived = 0
            bytesSent = 0
            killSwitchActive = false
            stealthActive = false
            quantumActive = false
            wasConnected = false
            stopStatsTimer()
            userInitiatedDisconnect = false
        }
    }

    // MARK: - Heartbeat

    /// 30-second liveness check against the server-side connection slot —
    /// the same cadence as Android's TunnelMonitor. `valid: false` means the
    /// server revoked the key (subscription lapsed, device evicted, node
    /// drained): tear down and tell the user why.
    private func startHeartbeat() {
        stopHeartbeat()
        guard let keyId = activeKeyId else { return }
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(Self.heartbeatInterval * 1_000_000_000))
                guard !Task.isCancelled, let self else { return }
                guard await self.activeKeyId == keyId else { return }
                do {
                    let beat = try await self.api.heartbeat(keyId: keyId)
                    if !beat.valid {
                        await MainActor.run {
                            self.error = beat.message ?? "This session was ended by the server."
                            self.disconnect()
                        }
                        return
                    }
                } catch {
                    // Transient network failures are expected mid-tunnel
                    // (path changes, sleep). The next beat retries; only the
                    // server explicitly invalidating the key disconnects.
                }
            }
        }
    }

    private func stopHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
    }

    // MARK: - Port Forwarding

    func loadPortForwards() {
        Task {
            do {
                portForwards = try await api.listPortForwards()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    func createPortForward(internalPort: Int, proto: String) {
        Task {
            do {
                let entry = try await api.createPortForward(internalPort: internalPort, proto: proto)
                portForwards.append(entry)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    func deletePortForward(id: String) {
        Task {
            do {
                try await api.deletePortForward(id: id)
                portForwards.removeAll { $0.id == id }
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    // MARK: - Private

    private func handleStatusChange(_ status: NEVPNStatus) {
        switch status {
        case .connected:
            isConnected = true
            isConnecting = false
            killSwitchActive = false
        case .connecting, .reasserting:
            isConnecting = true
        case .disconnected, .invalid:
            // An UNEXPECTED drop while the kill switch is armed means iOS is
            // now blocking traffic (includeAllNetworks) — surface that state
            // honestly instead of pretending we cleanly disconnected.
            if wasConnected && !userInitiatedDisconnect {
                killSwitchActive = TunnelPreferences.load().killSwitch
                if killSwitchActive {
                    error = "Connection dropped — kill switch is blocking traffic."
                }
            }
            isConnected = false
            isConnecting = false
            connectedSince = nil
            wasConnected = false
            stopStatsTimer()
            stopHeartbeat()
        case .disconnecting:
            isConnecting = false
        @unknown default:
            break
        }
    }

    /// 1 Hz transfer-stats poll.
    ///
    /// A Task, not a Timer: `Timer` is non-Sendable, and a @MainActor class's
    /// `deinit` is nonisolated — so Swift 6 will not let deinit touch it to
    /// invalidate it. `Task` IS Sendable, so cancellation from deinit is legal,
    /// and this matches the heartbeat loop above.
    private func startStatsTimer() {
        stopStatsTimer()
        statsTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard !Task.isCancelled, let self else { return }
                let stats = await self.vpnManager.currentStats()
                self.bytesReceived = stats.rx
                self.bytesSent = stats.tx
            }
        }
    }

    private func stopStatsTimer() {
        statsTask?.cancel()
        statsTask = nil
    }

    // AUDIT-H: tear down both loops if this ViewModel is deallocated while a
    // connection is still active (e.g. before a disconnect/status-change
    // notification arrives), otherwise they keep running against a dead object.
    // Both are Tasks, which are Sendable — so a nonisolated deinit may cancel
    // them. (The old Timer could not be touched here at all under Swift 6.)
    deinit {
        statsTask?.cancel()
        heartbeatTask?.cancel()
    }
}

/// A fresh WireGuard keypair, generated on-device.
///
/// WireGuard keys are X25519 — exactly `Curve25519.KeyAgreement`, and the
/// wire format is the raw 32 bytes, base64. Using CryptoKit here (instead of
/// WireGuardKit's `PrivateKey`) keeps the ~10 MB wireguard-go archive out of
/// the MAIN APP binary: only the PacketTunnel extension needs the tunnel
/// engine, and this was the app target's sole use of it.
struct WireGuardKeyPair {
    let base64Key: String
    let publicKeyBase64: String

    init() {
        let priv = Curve25519.KeyAgreement.PrivateKey()
        base64Key = priv.rawRepresentation.base64EncodedString()
        publicKeyBase64 = priv.publicKey.rawRepresentation.base64EncodedString()
    }
}
