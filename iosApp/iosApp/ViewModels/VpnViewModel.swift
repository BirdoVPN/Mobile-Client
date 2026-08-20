import Foundation
import SwiftUI
import NetworkExtension

/// A live Multi-Hop session: the entry/exit pair the SERVER CONFIRMED it
/// installed (never merely the pair we asked for — `MultiHopRouteCheck`
/// enforces that before this is constructed), captured at connect time so
/// every rebuild path redials the same pair and the UI renders the route that
/// actually exists rather than the unrelated single-hop `selectedServer`.
struct MultiHopSession: Equatable, Sendable {
    let entry: ServerInfo
    let exit: ServerInfo
    /// Server-confirmed route string, e.g. "DE -> NL".
    let route: String
}

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
    /// The live Multi-Hop session, if any — nil for single-hop sessions.
    /// Drives Home's "Protected · Multi-Hop" surfaces (status pill, server
    /// card, globe focus) and forces the rebuild paths to preserve — or
    /// explicitly refuse to break — the pair instead of silently downgrading
    /// to single-hop. Like `activeKeyId`, it deliberately survives a transient
    /// OS `.disconnected` (the same session may resume); it is cleared by
    /// `resetSessionState()` (user disconnect / sign-out / dead tunnel), on
    /// any fresh single-hop `connect()`, and on a failed multi-hop dial.
    @Published private(set) var activeMultiHop: MultiHopSession?
    /// True from the start of a live server switch until the new node reaches
    /// .connected (or the switch fails), so Home shows "Switching…" instead of a
    /// premature "Protected" over the new name.
    @Published private(set) var isSwitching = false

    // MARK: - Features
    /// Honest indicator: lit only when a true bilateral ML-KEM PSK was
    /// actually derived for this connection (BirdoPQManager mode).
    @Published var quantumActive = false
    /// REAL kill-switch arming state (VPNManager.killSwitchArmed: on-demand
    /// rule armed + includeAllNetworks in the persisted profile). Snapshotted
    /// on every status transition. This — not the Settings preference — is
    /// what decides whether the UI may claim traffic is being blocked.
    @Published private(set) var killSwitchArmed = false

    /// P1-ios-redial-loop-blackhole: the tripped circuit-breaker record left by
    /// the tunnel extension, or nil.
    ///
    /// Non-nil means: automatic re-dials for that node have been stopped, the
    /// kill switch has been disarmed and traffic is flowing again. Home renders
    /// `TunnelCircuitBreaker.userMessage(for:)` off this — deliberately its own
    /// slot rather than the shared `error` string, because the OS `.disconnected`
    /// that `failOpenAndStop()` provokes lands milliseconds later and would
    /// overwrite a message written into `error`.
    @Published private(set) var breakerTrip: TunnelBreakerRecord?

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

    /// How long to wait for the first inbound byte before declaring the tunnel
    /// dead. 15 s (30 × 500 ms) covers a slow mobile handshake — WireGuard
    /// retries its handshake every 5 s — without leaving the user staring at a
    /// tunnel that is never coming up.
    private let HANDSHAKE_POLLS = 30
    private let HANDSHAKE_POLL_MS = 500
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

        // A trip recorded while the app was suspended (or not running at all)
        // is only actionable once we are here. Cold start is one of the four
        // places the fail-open fires — see `checkCircuitBreaker()`.
        checkCircuitBreaker()
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

    /// - Parameter userInitiated: `true` for anything the user asked for (the
    ///   Home CTA, a live server switch, a settings blip). Those CLEAR the
    ///   circuit breaker before dialling, which is the guarantee that a bug in
    ///   the breaker can never do worse than delay an automatic re-dial — a
    ///   manual tap always connects. `autoConnectIfEnabled()` passes `false`;
    ///   auto-connect re-dialling straight into a tripped node is the loop
    ///   wearing a different hat.
    func connect(userInitiated: Bool = true) {
        guard let server = selectedServer else {
            error = "Select a server first"
            return
        }
        // Re-entrancy guard, matching the Android VpnViewModel: a second tap
        // while a connect is already in flight mints a second tunnel session
        // (and a second server-side peer) for the same device.
        guard !isConnecting else { return }
        if userInitiated { clearCircuitBreaker() }
        isConnecting = true
        error = nil
        // A fresh single-hop dial replaces any multi-hop session state. This is
        // only reachable with the tunnel down (Home CTA, auto-connect, or a
        // single-hop rebuild — reapplySettings and selectServerLive branch to
        // the multi-hop redial / refusal before ever calling here), so a pair
        // left over from a transient OS drop must not relabel the new
        // single-hop session as Multi-Hop.
        activeMultiHop = nil

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

    /// Dial a Multi-Hop pair. Async (unlike `connect()`) so MultiHopView can
    /// hold its screen open on a progress state and only leave once the result
    /// is in: `true` means the server CONFIRMED the requested route and the
    /// tunnel start was accepted (the Connected state itself still lands via
    /// `handleStatusChange`, exactly as for single-hop).
    @discardableResult
    func connectMultiHop(entry: ServerInfo, exit: ServerInfo) async -> Bool {
        // Same re-entrancy guard as connect().
        guard !isConnecting else { return false }
        // Every caller of this is a user action (the Multi-Hop screen's dial, or
        // a settings blip rebuilding the pair the user chose), so it clears the
        // breaker unconditionally — same rule as `connect(userInitiated: true)`.
        clearCircuitBreaker()
        isConnecting = true
        error = nil

        do {
            let config = try await api.getMultiHopConfig(entryId: entry.id, exitId: exit.id)
            // The server-side peer now EXISTS — remember its handle so every
            // failure below (route refusal included) can release the slot.
            setActiveKeyId(config.keyId)
            do {
                // Refuse a route the server did not confirm (ports desktop
                // vpn_multi_hop.rs / Android VpnManager #268 — rationale in
                // MultiHopRoute.swift): `success: true` only means the request
                // was handled; without checking the response's `multiHop`
                // block, a working single-hop tunnel would be rendered as the
                // user's chosen two-hop route indefinitely.
                let confirmed = try MultiHopRouteCheck.validate(
                    config.multiHop,
                    requestedEntryId: entry.id,
                    requestedExitId: exit.id
                )
                try await vpnManager.connect(config: config)
                // Record the CONFIRMED pair as session state so reapply /
                // switch rebuild multi-hop (never a silent single-hop
                // downgrade) and Home renders the route that actually exists.
                activeMultiHop = MultiHopSession(entry: entry, exit: exit,
                                                 route: confirmed.route)
            } catch {
                // The peer exists but is unusable (unconfirmed/mismatched
                // route, or local tunnel setup failed): release it or it holds
                // the user's connection slot until eviction.
                releaseServerSlot()
                throw error
            }
            quantumActive = BirdoPQManager.shared.currentMode == .bilateral
            // Connected-state ownership: see connect().
            return true
        } catch {
            self.error = error.localizedDescription
            reportUnauthorized(error)
            isConnecting = false
            isReapplyingSettings = false
            // A failed (re)connect must not leave the UI stuck on "Switching…".
            isSwitching = false
            activeMultiHop = nil
            return false
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
        guard isConnected, !isConnecting else { return }
        guard activeMultiHop != nil || selectedServer != nil else { return }
        guard !isReapplyingSettings else { return }
        isReapplyingSettings = true
        error = nil
        vpnManager.disconnect()
        Task { [weak self] in
            await self?.awaitTeardown()
            guard let self, self.isReapplyingSettings else { return }
            if let mh = self.activeMultiHop {
                // A live Multi-Hop session rebuilds as the SAME confirmed pair.
                // The unconditional single-hop connect() here used to pass its
                // guard (fetchServers auto-picks selectedServer) and silently
                // rebuild any settings change as single-hop — the user kept
                // seeing the multi-hop UI over a one-hop tunnel.
                await self.connectMultiHop(entry: mh.entry, exit: mh.exit)
            } else {
                self.connect()
            }
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

    /// Arm the kill switch's on-demand rule only once traffic has actually
    /// flowed through the tunnel.
    ///
    /// `NEVPNStatus.connected` means the packet-tunnel extension STARTED. It says
    /// nothing about whether WireGuard completed a handshake — a tunnel pointed at
    /// a peer that never answers reports `.connected` just the same. Arming
    /// `NEOnDemandRuleConnect` at that moment created a trap: the dead tunnel
    /// drops, on-demand instantly re-dials it, it dies again, forever. With
    /// `includeAllNetworks` also set, every packet is blackholed throughout, and
    /// the user cannot break the cycle from inside the app — only by deleting the
    /// VPN profile in iOS Settings.
    ///
    /// `rx > 0` is the proof: WireGuard cannot deliver received bytes without a
    /// completed handshake, so any inbound traffic means the peer answered.
    ///
    /// On failure this tears the tunnel down and releases the server-side peer
    /// rather than leaving it to hold the user's connection slot — the free tier
    /// has exactly one, and a stranded peer makes the next attempt read as
    /// "device limit reached".
    private func armKillSwitchAfterHandshake() async {
        for _ in 0..<HANDSHAKE_POLLS {
            try? await Task.sleep(for: .milliseconds(HANDSHAKE_POLL_MS))
            // The user (or a settings blip) tore it down while we waited.
            guard isConnected else { return }
            let stats = await vpnManager.currentStats()
            if stats.rx > 0 {
                // PRIMARY BREAKER RESET: real inbound bytes prove this node's
                // data plane works right now, so whatever streak the extension
                // had recorded against it is spent history. Cleared BEFORE the
                // kill switch is armed, so the arm can never happen on top of a
                // stale trip.
                clearCircuitBreaker()
                let killSwitch = UserDefaults.standard.object(forKey: "kill_switch") as? Bool ?? true
                vpnManager.applyKillSwitchFlag(killSwitch)
                // applyKillSwitchFlag mutates the in-memory profile
                // synchronously; refresh the published snapshot so the UI
                // reflects the arm without waiting for a status transition.
                killSwitchArmed = vpnManager.killSwitchArmed
                return
            }
        }
        guard isConnected else { return }
        // Never handshook. Do NOT arm on-demand — that is the trap this exists to
        // avoid. Tear down explicitly so iOS has nothing to re-dial.
        //
        // Include the extension's own failure reason when iOS gave us one: the
        // appex is a separate process, so without this the user (and anyone
        // reading a bug report) sees only "it didn't work" for causes as
        // different as a rejected config and a crashed provider.
        if let reason = vpnManager.lastDisconnectReason {
            error = "Tunnel failed: \(reason)"
        } else {
            error = "Couldn't establish a secure tunnel to this server. Try another location."
        }
        vpnManager.disconnect()
        releaseServerSlot()
        resetSessionState()
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
        // A tripped breaker means the last few dials to this node produced a
        // tunnel that carried nothing. Auto-connecting straight back into it is
        // the re-dial loop with an app-level trigger instead of an on-demand
        // one. The user's own Connect tap still works (it clears the breaker),
        // and the trip lapses after `TunnelCircuitBreaker.tripCooldown` so
        // auto-connect resumes on its own without anyone doing anything.
        guard breakerTrip == nil else {
            NSLog("[VpnViewModel] auto-connect suppressed: circuit breaker is tripped")
            return
        }
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.5))
            guard let self else { return }
            guard !self.isConnected, !self.isConnecting else { return }
            guard self.breakerTrip == nil else { return }
            if self.selectedServer == nil {
                self.selectedServer = self.servers.first { $0.isOnline && $0.accessible }
            }
            guard self.selectedServer != nil else { return }
            self.connect(userInitiated: false)
        }
    }

    // MARK: - Circuit Breaker (P1-ios-redial-loop-blackhole)

    /// Act on a circuit-breaker trip recorded by the tunnel extension.
    ///
    /// THIS IS THE FAIL-OPEN. The extension can count failures and stop
    /// re-dialling, but it cannot disarm the on-demand rule or clear
    /// `includeAllNetworks` — those are host-app API. So when it trips with a
    /// rule armed it holds the dead tunnel in place and leaves the record here,
    /// and this method is what actually restores traffic:
    /// `VPNManager.failOpenAndStop()` clears the blocking flags, disarms
    /// on-demand (persisting BEFORE stopping, or iOS re-dials what we just
    /// stopped), then stops the tunnel.
    ///
    /// Called from FOUR places, so the window between the trip and the recovery
    /// is as short as iOS allows:
    ///   * `init` — cold start, covering a trip that happened while the app was
    ///     not running at all;
    ///   * the 30 s heartbeat timer — covers a trip while the app is already
    ///     open in the foreground, where no lifecycle event would fire;
    ///   * `ContentView`'s `scenePhase == .active` — the common case: the user
    ///     notices the network is dead and opens Birdo;
    ///   * every `handleStatusChange`, so a state transition never races ahead
    ///     of the recovery.
    ///
    /// ### Reset conditions (the complete list)
    /// | Event | Clears? |
    /// |---|---|
    /// | Inbound bytes on a new session (real handshake) | YES — `armKillSwitchAfterHandshake` |
    /// | User taps Connect / switches server / Multi-Hop dial / settings blip | YES — `connect(userInitiated: true)`, `connectMultiHop` |
    /// | `TunnelCircuitBreaker.tripCooldown` (15 min) elapses | YES — here, on the next check |
    /// | Dialling a DIFFERENT node | YES — the record is keyed by node, so the streak restarts at 1 |
    /// | Sign-out | YES — `resetForLogout` |
    /// | App foreground | NO — foreground is the trigger to ACT on a trip, not to forget it |
    /// | Network / interface change | NO — a new SSID does not revive a revoked peer; the cooldown covers the case where it would have helped |
    func checkCircuitBreaker() {
        let store = TunnelBreakerStore.shared
        guard let record = store.record else {
            breakerTrip = nil
            failOpenAppliedFor = nil
            return
        }
        guard TunnelCircuitBreaker.isTripped(record, now: Date()) else {
            // Either still under budget (leave the streak alone — it is how the
            // next failure knows it is the third, not the first) or a trip whose
            // cooldown has lapsed, which is spent state: drop it so the next
            // failure starts a clean streak.
            if record.trippedAt != nil { store.clear() }
            breakerTrip = nil
            failOpenAppliedFor = nil
            return
        }
        // The banner is published unconditionally — the user must be told even
        // on a pass where the teardown cannot run yet.
        breakerTrip = record
        // The TEARDOWN, though, is latched separately from the banner. Latching
        // on `breakerTrip` would mean a pass that failed to act (cold start:
        // `init` runs before `loadManager()`'s async completion assigns the
        // manager, so `failOpenAndStop()` has nothing to act on) could never try
        // again — a fail-open that silently does not happen is the whole bug.
        guard failOpenAppliedFor != record else { return }
        NSLog("[VpnViewModel] circuit breaker tripped (%@ x%ld) — failing open",
              record.kind.rawValue, record.consecutiveFailures)
        guard vpnManager.failOpenAndStop() else { return }   // retried next tick
        failOpenAppliedFor = record
        // Best-effort, exactly like every other call site: the DELETE may well
        // fail while the tunnel is still tearing down, and the next connect from
        // this deviceId reclaims the slot server-side anyway.
        releaseServerSlot()
        resetSessionState()
    }

    /// Forget the breaker entirely. See the reset table on
    /// `checkCircuitBreaker()` for who calls this and why.
    private func clearCircuitBreaker() {
        TunnelBreakerStore.shared.clear()
        breakerTrip = nil
        failOpenAppliedFor = nil
    }

    /// The trip whose fail-open teardown has ACTUALLY been performed. Separate
    /// from `breakerTrip` so a pass that could not act (no manager loaded yet)
    /// retries, while a pass that did act does not re-tear-down every 30 s.
    private var failOpenAppliedFor: TunnelBreakerRecord?

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
        // Every arm/disarm path either is, or coincides with, a status
        // transition (arming happens post-handshake, disarming in
        // disconnect()/stale-rule cleanup), so snapshotting here keeps the
        // published value honest without polling the system profile.
        killSwitchArmed = vpnManager.killSwitchArmed
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
            // stops lying about egress the instant the new peer is real. For a
            // Multi-Hop session the node this device talks to is the ENTRY node,
            // never the unrelated `selectedServer` the user last browsed to.
            connectedServerId = activeMultiHop?.entry.id ?? selectedServer?.id
            isSwitching = false
            // On-demand is armed only after a REAL handshake — see
            // armKillSwitchAfterHandshake(). NEVPNStatus.connected means the
            // extension started, NOT that WireGuard completed a handshake, and
            // arming on that alone is what turns a tunnel that can never
            // handshake into an endless re-dial the user cannot escape without
            // disabling the VPN in iOS Settings.
            Task { [weak self] in await self?.armKillSwitchAfterHandshake() }
            startStatsTimer()
            startHeartbeat()
        case .connecting, .reasserting:
            isConnecting = true
        case .disconnected, .invalid:
            // An OS-initiated drop carries the extension's failure reason. Show
            // it: a tunnel torn down by iOS because the provider failed to start
            // otherwise looks identical to a user disconnect, which is precisely
            // why a start-up failure could masquerade as an unexplained loop.
            // Prefer the extension's own account of what went wrong: it names
            // the exact failure (a keychain read, a config parse) where iOS's
            // lastDisconnectError is often nil or generic.
            if isConnected || isConnecting {
                if let appexReason = vpnManager.takeExtensionFailure() {
                    error = "Tunnel failed: \(appexReason)"
                } else if let reason = vpnManager.lastDisconnectReason {
                    error = "Tunnel failed: \(reason)"
                }
            }
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
        // LAST, deliberately. A trip can land on either edge — the extension
        // holds a dead tunnel at `.connected`, or cancels into `.disconnected` —
        // and the recovery must win over whatever the branch above just set.
        // Running it first would let `.connected` re-arm the timers the
        // fail-open had just torn down.
        //
        // No recursion risk: `failOpenAndStop()` provokes another status change,
        // but the second pass sees the SAME record already in
        // `failOpenAppliedFor` and returns before touching the tunnel again.
        checkCircuitBreaker()
    }

    private func resetSessionState() {
        isConnected = false
        isConnecting = false
        quantumActive = false
        connectedSince = nil
        connectedServerId = nil
        activeMultiHop = nil
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
        // REFUSE to downgrade a live Multi-Hop session (desktop S-5 semantics,
        // Dashboard.tsx): a single server cannot express the pair, so a switch
        // here would silently drop the second hop while the user kept paying
        // for — and believing in — a separation that no longer existed.
        if activeMultiHop != nil {
            error = "Switching servers would replace your Multi-Hop route with "
                + "a single hop. Disconnect first if you meant to switch."
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
        // The next account must not inherit a trip recorded against this one.
        clearCircuitBreaker()
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
                // Covers the app-already-in-the-foreground case: the extension
                // holds a dead tunnel at `.connected`, so NO status transition
                // and NO scenePhase change ever fires. Without this tick the
                // user would sit on a "Protected" UI with no network until they
                // backgrounded and reopened the app.
                self?.checkCircuitBreaker()
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
