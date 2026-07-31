import Foundation
import SwiftUI
import NetworkExtension

/// Manages VPN connection state, server list, subscription/plan state,
/// the 30 s heartbeat, speed tests and port forwarding.
///
/// Error surfaces are PER-SCREEN (S2): `error` is the connect-path slot the
/// Home banner renders; `serversError`, `subscriptionError` and
/// `portForwardError` belong to their own screens, so a failed refresh is
/// never invisible or misattributed to Home.
@MainActor
final class VpnViewModel: ObservableObject {
    // MARK: - Connection State
    @Published var isConnected = false
    @Published var isConnecting = false
    /// Connect-path error. Backend refusals arrive as the server's own words
    /// (`APIError.serverMessage`, incl. 2xx `{success:false}` envelopes and the
    /// 426 version-floor body) — render verbatim, never rephrase.
    @Published var error: String?
    /// True while a settings "blip" reconnect is in flight — Home shows
    /// "Applying settings — reconnecting…" off this.
    @Published private(set) var isReapplyingSettings = false

    // MARK: - Servers
    @Published var servers: [ServerInfo] = []
    @Published var selectedServer: ServerInfo?
    @Published var favoriteIds: Set<String> = []
    @Published var isLoadingServers = false
    /// S2 fix: the Servers screen renders THIS (with a retry affordance)
    /// instead of the shared connect slot — a failed refresh used to be
    /// indistinguishable from an empty fleet, with the error text surfacing
    /// later on the Home tab.
    @Published var serversError: String?

    // MARK: - Subscription (GET /vpn/stats — the canonical plan source)
    @Published private(set) var subscription: VpnStats?
    @Published private(set) var isLoadingSubscription = false
    @Published var subscriptionError: String?

    // MARK: - Stats
    @Published var bytesReceived: Int64 = 0
    @Published var bytesSent: Int64 = 0
    /// Wall-clock start of the current session. Drive the Duration readout
    /// with `TimelineView(.periodic(from:by:1))` off this date — the stats
    /// timer only republishes byte counters, and identical IPC values must
    /// not be what keeps the clock ticking.
    @Published var connectedSince: Date?
    /// Id of the node the tunnel is ACTUALLY on (captured at .connected), so the
    /// UI never paints the Connected dot on a server the user merely tapped. nil
    /// unless a live session exists. (Review #3 — the live-switch truth fix.)
    @Published private(set) var connectedServerId: String?
    /// True from the start of a live server switch until the new node reaches
    /// .connected (or the switch fails), so Home shows "Switching…" instead of a
    /// premature "Protected" over the new name.
    @Published private(set) var isSwitching = false

    // MARK: - Features
    /// Honest indicator: lit only when a true bilateral ML-KEM PSK was
    /// actually derived for this connection (BirdoPQManager mode).
    @Published var quantumActive = false

    // MARK: - Port Forwarding
    @Published var portForwards: [PortForwardEntry] = []
    @Published private(set) var isLoadingPortForwards = false
    /// Per-screen error surface for the Port Forwarding screen (S2 pattern).
    @Published var portForwardError: String?

    // MARK: - Hooks
    /// Fired when any call in this model dies with `APIError.unauthorized`
    /// (single-flight refresh exhausted). The app root wires this to
    /// `authVM.logout()` — without it a user with a stale refresh token stays
    /// "logged in" forever while every call fails into empty screens (S3).
    var onUnauthorized: (() -> Void)?

    // MARK: - Private
    private let api: APIClient
    private let vpnManager: VPNManager
    // `nonisolated(unsafe)` so the nonisolated `deinit` below can still invalidate
    // the Timers (Swift 6 forbids touching MainActor-isolated non-Sendable state
    // from deinit). Every read/write outside deinit is on the main actor.
    nonisolated(unsafe) private var statsTimer: Timer?
    nonisolated(unsafe) private var heartbeatTimer: Timer?

    /// Server-side handle of the live connection (`keyId` from the connect
    /// response). Persisted so a relaunch while the tunnel extension is still
    /// up can release the slot on the next user disconnect. Without this,
    /// every iOS disconnect leaked the user's connection slot until server
    /// eviction — fatal on the free tier's single slot, where the phantom
    /// session then refused the user's own reconnects.
    private(set) var activeKeyId: String?
    private static let lastKeyIdDefaultsKey = "last_key_id"

    private static let heartbeatInterval: TimeInterval = 30

    init(api: APIClient = .shared, vpnManager: VPNManager = .shared) {
        self.api = api
        self.vpnManager = vpnManager

        // Load persisted favorites
        if let ids = UserDefaults.standard.stringArray(forKey: "favorite_servers") {
            favoriteIds = Set(ids)
        }

        // Reclaim the connection handle across a process restart: the tunnel
        // extension outlives the app, so the session this handle names may
        // still be up right now.
        activeKeyId = UserDefaults.standard.string(forKey: Self.lastKeyIdDefaultsKey)

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
        guard !isLoadingServers else { return }
        isLoadingServers = true
        serversError = nil
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
                serversError = error.localizedDescription
                reportUnauthorized(error)
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

    // MARK: - Subscription / Plan Gating

    private var subscriptionFetchedAt: Date?
    private static let subscriptionCacheTTL: TimeInterval = 30

    /// Refresh the canonical plan/usage snapshot. 30 s client cache mirrors
    /// Android's repository: tab-focus refreshes ride the cache; the Limit
    /// screen's "Refresh usage" and voucher-success paths pass `force: true`.
    func refreshSubscription(force: Bool = false) {
        if !force,
           subscription != nil,
           let ts = subscriptionFetchedAt,
           Date().timeIntervalSince(ts) < Self.subscriptionCacheTTL {
            return
        }
        guard !isLoadingSubscription else { return }
        isLoadingSubscription = true
        subscriptionError = nil
        Task {
            defer { isLoadingSubscription = false }
            do {
                subscription = try await api.fetchVpnStats()
                subscriptionFetchedAt = Date()
            } catch {
                // Keep any cached snapshot — a refresh failure must never
                // downgrade a paid user's UI to RECON.
                subscriptionError = error.localizedDescription
                reportUnauthorized(error)
            }
        }
    }

    /// Server-truth plan slug, uppercased. "RECON" until /vpn/stats answers —
    /// locks render closed rather than flashing premium features open.
    var currentPlan: String { (subscription?.plan ?? "RECON").uppercased() }
    var isSovereign: Bool { subscription?.isSovereign ?? false }
    var isOperativeOrHigher: Bool { subscription?.isOperativeOrHigher ?? false }

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
                // The server-side peer now EXISTS — remember its handle so both
                // the disconnect path and the failure path below can release
                // the connection slot (the free tier has exactly one).
                setActiveKeyId(config.keyId)
                do {
                    try await vpnManager.connect(config: config)
                } catch {
                    // Local tunnel setup failed AFTER the server minted a peer:
                    // release it or the dead peer holds the user's slot until
                    // eviction and their own retry reads as "device limit".
                    releaseServerSlot()
                    throw error
                }
                // Honest indicator: light the "Quantum" badge only when a true
                // bilateral ML-KEM PSK was actually derived for this connection.
                quantumActive = BirdoPQManager.shared.currentMode == .bilateral
                // `startVPNTunnel()` only REQUESTS the tunnel — it is still
                // handshaking at this point. `isConnected`, `connectedSince` and
                // the stats timer are owned by handleStatusChange(.connected) so
                // the UI never shows "Protected" before the tunnel actually is.
            } catch {
                self.error = error.localizedDescription
                reportUnauthorized(error)
                isConnecting = false
                isReapplyingSettings = false
                // A failed (re)connect must not leave the UI stuck on "Switching…".
                isSwitching = false
            }
        }
    }

    func disconnect() {
        // A user-initiated teardown wins over any pending settings blip.
        isReapplyingSettings = false
        // Release the server-side peer (fire-and-forget DELETE): tearing down
        // only the local tunnel leaves the peer holding the user's connection
        // slot until server eviction.
        releaseServerSlot()
        // Runs synchronously: callers rely on the tunnel being torn down BEFORE
        // the next statement (sign-out does `disconnect()` then `logout()`,
        // which wipes the keychain the extension reads its secrets from).
        vpnManager.disconnect()
        resetSessionState()
    }

    /// Sign-out variant: tears the tunnel down synchronously (the keychain the
    /// extension reads must still exist at that point) and AWAITS the
    /// server-side slot release before returning, so the caller can wipe
    /// credentials immediately after without the DELETE racing token removal.
    /// Order stays load-bearing: `await disconnectForSignOut()` BEFORE
    /// `authVM.logout()`.
    func disconnectForSignOut() async {
        isReapplyingSettings = false
        let keyId = activeKeyId
        setActiveKeyId(nil)
        vpnManager.disconnect()
        resetSessionState()
        if let keyId {
            try? await api.disconnect(keyId: keyId)
        }
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
                setActiveKeyId(config.keyId)
                do {
                    try await vpnManager.connect(config: config)
                } catch {
                    releaseServerSlot()
                    throw error
                }
                quantumActive = BirdoPQManager.shared.currentMode == .bilateral
                // Connected-state ownership: see connect().
            } catch {
                self.error = error.localizedDescription
                reportUnauthorized(error)
                isConnecting = false
                isReapplyingSettings = false
                // A failed (re)connect must not leave the UI stuck on "Switching…".
                isSwitching = false
            }
        }
    }

    /// Settings "blip" (Android apply-on-change, path 2): rebuild the live
    /// tunnel so changed tunnel-shape settings (quantum, DNS, local network
    /// sharing, port, MTU) take effect. Wired to
    /// `SettingsViewModel.onSettingsReapplyNeeded` at the app root; no-op
    /// unless connected. The immediate reconnect replaces this device's
    /// server-side slot atomically (`evictForConnect` reclaims by deviceId),
    /// so the old peer needs no DELETE round-trip and a free-tier slot is
    /// never double-consumed. A user disconnect during the blip WINS:
    /// `disconnect()` clears `isReapplyingSettings`, which gates the deferred
    /// reconnect below.
    func reapplySettings() {
        guard isConnected, !isConnecting, selectedServer != nil else { return }
        guard !isReapplyingSettings else { return }
        isReapplyingSettings = true
        error = nil
        vpnManager.disconnect()
        Task { [weak self] in
            await self?.awaitTeardown()
            guard let self, self.isReapplyingSettings else { return }
            self.connect()
            await self.clearRebuildFlagsIfStalled()
        }
    }

    /// Wait for the teardown we just requested to ACTUALLY land.
    ///
    /// Both rebuild paths (settings reapply, live server switch) used to sleep a
    /// fixed 600 ms and hope the teardown had finished. On a slower device it had
    /// not: the old session's `stopVPNTunnel()` arrived AFTER the new
    /// `startVPNTunnel()` and killed the rebuilt tunnel. The tunnel stayed down,
    /// and because the in-flight flag is only cleared on `.connected`, it LATCHED
    /// — `reapplySettings()`'s own re-entrancy guard then silently swallowed
    /// every later settings change, and the switch path pinned the UI on
    /// "Switching…", until the user reconnected by hand.
    ///
    /// `isConnected` is cleared by `handleStatusChange(.disconnected)`, so it is
    /// the real signal that the teardown landed. Bounded: after the cap we
    /// rebuild anyway rather than strand the user on a wedged NE state.
    private func awaitTeardown() async {
        for _ in 0..<50 {                                   // up to ~5 s
            if !isConnected { break }
            try? await Task.sleep(for: .milliseconds(100))
        }
        // Let the teardown's preference save settle before connect() writes the
        // new configuration over it.
        try? await Task.sleep(for: .milliseconds(150))
    }

    /// Watchdog: a rebuild that never reaches `.connected` must not leave the
    /// in-flight flags set. `isReapplyingSettings` gates the reapply re-entrancy
    /// guard, so a latched flag makes the app silently ignore every subsequent
    /// settings change — the symptom this pairs with `awaitTeardown()` to kill.
    private func clearRebuildFlagsIfStalled() async {
        try? await Task.sleep(for: .seconds(40))
        guard !isConnected else { return }
        isReapplyingSettings = false
        isSwitching = false
    }

    /// Android parity ("Auto-Connect — connect to VPN on app startup", 1.5 s
    /// delay). Call once from the app root when the logged-in shell appears.
    /// No-op unless the preference is ON and nothing is connected/connecting.
    func autoConnectIfEnabled() {
        guard UserDefaults.standard.bool(forKey: "auto_connect") else { return }
        guard !isConnected, !isConnecting else { return }
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.5))
            guard let self else { return }
            guard !self.isConnected, !self.isConnecting else { return }
            if self.selectedServer == nil {
                self.selectedServer = self.servers.first { $0.isOnline && $0.accessible }
            }
            guard self.selectedServer != nil else { return }
            self.connect()
        }
    }

    // MARK: - Port Forwarding

    func loadPortForwards() {
        guard !isLoadingPortForwards else { return }
        isLoadingPortForwards = true
        portForwardError = nil
        Task {
            defer { isLoadingPortForwards = false }
            do {
                portForwards = try await api.fetchPortForwards()
            } catch {
                portForwardError = error.localizedDescription
                reportUnauthorized(error)
            }
        }
    }

    func createPortForward(internalPort: Int, proto: String) {
        Task {
            do {
                let entry = try await api.createPortForward(port: internalPort, proto: proto)
                portForwards.append(entry)
                portForwardError = nil
            } catch {
                // "Port forwarding requires a Sovereign subscription", "No
                // active VPN connection…" — backend refusals, shown verbatim
                // on the Port Forwarding screen itself.
                portForwardError = error.localizedDescription
                reportUnauthorized(error)
            }
        }
    }

    func deletePortForward(id: String) {
        Task {
            do {
                try await api.deletePortForward(id: id)
                portForwards.removeAll { $0.id == id }
            } catch {
                portForwardError = error.localizedDescription
                reportUnauthorized(error)
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
                reportUnauthorized(error)
            }
        }
    }

    // MARK: - Private

    private func handleStatusChange(_ status: NEVPNStatus) {
        switch status {
        case .connected:
            isConnected = true
            isConnecting = false
            isReapplyingSettings = false
            // The connected session is owned here rather than at request time so
            // it also covers tunnels the system brings up on its own (on-demand
            // rules, or a reassert after a network change). Previously those
            // paths left the duration pinned at 00:00 with the byte counters
            // frozen at zero, because only connect() ever started the timer.
            if connectedSince == nil { connectedSince = Date() }
            // The tunnel is up on the server we just dialled — record which node
            // it actually is and clear any in-flight live-switch flag, so the UI
            // stops lying about egress the instant the new peer is real.
            connectedServerId = selectedServer?.id
            isSwitching = false
            // finding #5: on-demand (the kill-switch re-dial rule) is armed
            // ONLY now that the tunnel is actually up — never before
            // startVPNTunnel, where a failure could leave iOS auto-dialing a
            // server-side peer the failure path already deleted. connect()
            // saved the config with on-demand disabled; applyKillSwitchFlag
            // arms it session-gated (it only arms while the session is live).
            // Read the raw stored value so a fresh install (absent key)
            // defaults ON, matching VPNManager.connect().
            let killSwitch = UserDefaults.standard.object(forKey: "kill_switch") as? Bool ?? true
            vpnManager.applyKillSwitchFlag(killSwitch)
            startStatsTimer()
            startHeartbeat()
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
            stopHeartbeat()
            // `activeKeyId` deliberately survives here: a transient OS drop
            // (network change with the kill switch re-dialling) resumes the
            // SAME server-side session, and a settings blip passes through
            // .disconnected on its way back up. Only a user disconnect or a
            // server-side revocation clears the handle.
        case .disconnecting:
            isConnecting = false
        @unknown default:
            break
        }
    }

    private func resetSessionState() {
        isConnected = false
        isConnecting = false
        quantumActive = false
        connectedSince = nil
        connectedServerId = nil
        isSwitching = false
        bytesReceived = 0
        bytesSent = 0
        stopStatsTimer()
        stopHeartbeat()
    }

    /// Live server switch (Review #3). Tapping a different node while connected
    /// must reconnect the tunnel — not just relabel the UI. Same-node taps and
    /// taps while disconnected fall back to a plain selection.
    func selectServerLive(_ server: ServerInfo) {
        guard server.accessible else { return }
        guard isConnected || isConnecting else {
            selectServer(server)
            return
        }
        if server.id == connectedServerId { return }
        selectedServer = server
        error = nil
        isSwitching = true
        // Reuse the settings-blip sequencing: stop, WAIT for the teardown to
        // actually land (not a fixed sleep — see `awaitTeardown()`), then
        // reconnect. evictForConnect reclaims the device slot atomically, so no
        // DELETE round-trip is needed and the free-tier slot is never doubled.
        vpnManager.disconnect()
        Task { [weak self] in
            await self?.awaitTeardown()
            guard let self, self.isSwitching else { return }
            self.connect()
            await self.clearRebuildFlagsIfStalled()
        }
    }

    /// Clears cross-account cached state on sign-out (Review #490). The shell
    /// wires this to AuthViewModel.onLogout so the next user never inherits the
    /// previous account's plan/servers.
    func resetForLogout() {
        disconnect()
        servers = []
        selectedServer = nil
        serversError = nil
        subscription = nil
        subscriptionError = nil
        subscriptionFetchedAt = nil
        portForwards = []
        portForwardError = nil
    }

    // MARK: - Connection Slot (keyId) Bookkeeping

    private func setActiveKeyId(_ keyId: String?) {
        activeKeyId = keyId
        let defaults = UserDefaults.standard
        if let keyId {
            defaults.set(keyId, forKey: Self.lastKeyIdDefaultsKey)
        } else {
            defaults.removeObject(forKey: Self.lastKeyIdDefaultsKey)
        }
    }

    /// Fire-and-forget `DELETE /vpn/connections/{keyId}`. Best effort: if the
    /// request fails, the next connect from this deviceId reclaims the slot
    /// server-side and eviction covers the rest. NEVER routed through the
    /// unauthorized hook — this can legitimately run mid-logout.
    private func releaseServerSlot() {
        guard let keyId = activeKeyId else { return }
        setActiveKeyId(nil)
        Task { [api] in
            do {
                try await api.disconnect(keyId: keyId)
            } catch {
                NSLog("[VpnViewModel] connection slot release failed: %@",
                      error.localizedDescription)
            }
        }
    }

    // MARK: - Heartbeat (30 s, Android VpnManager.startHeartbeat parity)

    private func startHeartbeat() {
        // Idempotent for the same reason as startStatsTimer().
        stopHeartbeat()
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: Self.heartbeatInterval,
                                              repeats: true) { [weak self] _ in
            Task { @MainActor in
                await self?.sendHeartbeat()
            }
        }
    }

    private func stopHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
    }

    private func sendHeartbeat() async {
        guard isConnected, let keyId = activeKeyId else { return }
        let result: HeartbeatResult
        do {
            result = try await api.heartbeat(keyId: keyId)
        } catch {
            // Transient network/API failure — NEVER tear down a live tunnel
            // over a missed heartbeat (Android logs and moves on too).
            NSLog("[VpnViewModel] heartbeat failed: %@", error.localizedDescription)
            return
        }
        if !result.valid {
            // Revoked server-side (silent free-tier eviction, admin revoke…).
            // This heartbeat is the ONLY way the client learns about it. The
            // peer is already gone, so drop the handle rather than DELETE it,
            // then tear down locally and tell the user why in the server's
            // own words.
            let message = result.message ?? "Connection has been revoked. Please reconnect."
            setActiveKeyId(nil)
            disconnect()
            error = message
        } else if result.serverOnline == false {
            // Android parity: log only. The node may flap during maintenance;
            // the session stays valid and the message repeats if it persists.
            NSLog("[VpnViewModel] heartbeat: current server reports offline")
        }
    }

    // MARK: - Stats Timer

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

    private func reportUnauthorized(_ error: Error) {
        if case APIError.unauthorized = error {
            onUnauthorized?()
        }
    }

    // AUDIT-H: guarantee the repeating Timers are torn down if this ViewModel
    // is deallocated while a connection is still active (e.g. before a
    // disconnect/status-change notification arrives). Without this a Timer
    // retains its closure target via the run loop and leaks. `invalidate()` is
    // safe to call from deinit and removes the timer from the run loop.
    deinit {
        statsTimer?.invalidate()
        heartbeatTimer?.invalidate()
    }
}
