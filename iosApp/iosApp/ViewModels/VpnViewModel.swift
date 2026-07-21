import Foundation
import SwiftUI
import NetworkExtension
import BirdoShared

/// Manages VPN connection state, server list, speed tests, port forwarding.
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

    // MARK: - Features
    @Published var killSwitchActive = false
    @Published var stealthActive = false
    @Published var quantumActive = false

    // MARK: - Port Forwarding
    @Published var portForwards: [PortForwardEntry] = []

    // MARK: - Private
    private let api: APIClient
    private let vpnManager: VPNManager
    // `nonisolated(unsafe)` so the nonisolated `deinit` below can still invalidate
    // the Timer (Swift 6 forbids touching MainActor-isolated non-Sendable state
    // from deinit). Every read/write outside deinit is on the main actor.
    nonisolated(unsafe) private var statsTimer: Timer?

    init(api: APIClient = .shared, vpnManager: VPNManager = .shared) {
        self.api = api
        self.vpnManager = vpnManager

        // Load persisted favorites
        if let ids = UserDefaults.standard.stringArray(forKey: "favorite_servers") {
            favoriteIds = Set(ids)
        }

        // Observe VPN status
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
        error = nil
        Task {
            defer { isLoadingServers = false }
            do {
                let list = try await api.fetchServers()
                servers = list
                serverCacheTimestamp = Date()
                // Mirror the Android `loadServers()`: pre-select the best usable
                // node. Without this nothing ever assigns `selectedServer`, so a
                // fresh install's first Connect tap always failed with
                // "Select a server first". Never auto-select an out-of-plan or
                // offline node — the backend would refuse the connect anyway.
                if selectedServer == nil {
                    selectedServer = list.first { $0.isOnline && $0.accessible }
                }
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    func selectServer(_ server: ServerInfo) {
        // Defence in depth: the list renders out-of-plan nodes locked and inert,
        // so reaching here means a caller bypassed that. The backend would
        // refuse the connect anyway.
        guard server.accessible else { return }
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
        // Re-entrancy guard, matching the Android VpnViewModel: a second tap
        // while a connect is already in flight mints a second tunnel session
        // (and a second server-side peer) for the same device.
        guard !isConnecting else { return }
        isConnecting = true
        error = nil

        Task {
            do {
                let config = try await api.getConnectConfig(serverId: server.id)
                try await vpnManager.connect(config: config)
                // Honest indicator: light the "Quantum" badge only when a true
                // bilateral ML-KEM PSK was actually derived for this connection.
                quantumActive = BirdoPQManager.shared.currentMode == .bilateral
                // `startVPNTunnel()` only REQUESTS the tunnel — it is still
                // handshaking at this point. `isConnected`, `connectedSince` and
                // the stats timer are owned by handleStatusChange(.connected) so
                // the UI never shows "Protected" before the tunnel actually is.
            } catch {
                self.error = error.localizedDescription
                isConnecting = false
            }
        }
    }

    func disconnect() {
        // Runs synchronously: callers rely on the tunnel being torn down BEFORE
        // the next statement (HomeView's sign-out does `disconnect()` then
        // `logout()`, which wipes the keychain the extension reads its secrets
        // from). The previous `Task { … }` wrapper deferred the whole body by a
        // main-actor hop, so the keychain was cleared first.
        vpnManager.disconnect()
        isConnected = false
        isConnecting = false
        quantumActive = false
        connectedSince = nil
        bytesReceived = 0
        bytesSent = 0
        stopStatsTimer()
    }

    func connectMultiHop(entryId: String, exitId: String) {
        // Same re-entrancy guard as connect(): the multi-hop sheet dismisses on
        // tap but does not disable its button on `isConnecting`.
        guard !isConnecting else { return }
        isConnecting = true
        error = nil

        Task {
            do {
                let config = try await api.getMultiHopConfig(entryId: entryId, exitId: exitId)
                try await vpnManager.connect(config: config)
                quantumActive = BirdoPQManager.shared.currentMode == .bilateral
                // Connected-state ownership: see connect().
            } catch {
                self.error = error.localizedDescription
                isConnecting = false
            }
        }
    }

    // MARK: - Port Forwarding

    func createPortForward(internalPort: Int, proto: String) {
        Task {
            do {
                let entry = try await api.createPortForward(port: internalPort, proto: proto)
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

    // MARK: - Speed Test

    func runSpeedTest(
        onComplete: @escaping (SpeedTestResult) -> Void,
        onProgress: @escaping (TestPhase, Double) -> Void
    ) {
        Task {
            do {
                // Latency phase
                onProgress(.latency, 0.1)
                let latency = try await api.measureLatency()
                onProgress(.latency, 0.33)

                // Download phase
                onProgress(.download, 0.4)
                let download = try await api.measureDownload()
                onProgress(.download, 0.66)

                // Upload phase
                onProgress(.upload, 0.7)
                let upload = try await api.measureUpload()
                onProgress(.upload, 0.95)

                let result = SpeedTestResult(
                    latencyMs: latency.latencyMs,
                    jitterMs: latency.jitterMs,
                    downloadMbps: download,
                    uploadMbps: upload
                )
                onComplete(result)
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
            // The connected session is owned here rather than at request time so
            // it also covers tunnels the system brings up on its own (on-demand
            // rules, or a reassert after a network change). Previously those
            // paths left the duration pinned at 00:00 with the byte counters
            // frozen at zero, because only connect() ever started the timer.
            if connectedSince == nil { connectedSince = Date() }
            startStatsTimer()
        case .connecting, .reasserting:
            isConnecting = true
        case .disconnected, .invalid:
            isConnected = false
            isConnecting = false
            quantumActive = false
            connectedSince = nil
            // Match disconnect(): an OS-initiated drop must not leave the last
            // session's byte counts on screen for the next connection.
            bytesReceived = 0
            bytesSent = 0
            stopStatsTimer()
        case .disconnecting:
            isConnecting = false
        @unknown default:
            break
        }
    }

    private func startStatsTimer() {
        // Idempotent: `.connected` can be delivered more than once for a single
        // session (e.g. after a reassert). Overwriting `statsTimer` without
        // invalidating the old one orphaned a repeating Timer on the run loop —
        // it kept firing and kept its closure alive for the process lifetime.
        stopStatsTimer()
        statsTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                let stats = await self.vpnManager.currentStats()
                self.bytesReceived = stats.rx
                self.bytesSent = stats.tx
            }
        }
    }

    private func stopStatsTimer() {
        statsTimer?.invalidate()
        statsTimer = nil
    }

    // AUDIT-H: guarantee the repeating stats Timer is torn down if this
    // ViewModel is deallocated while a connection is still active (e.g. before
    // a disconnect/status-change notification arrives). Without this the Timer
    // retains its closure target via the run loop and leaks. `invalidate()` is
    // safe to call from deinit and removes the timer from the run loop.
    deinit {
        statsTimer?.invalidate()
    }
}
