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

    // MARK: - Public locations (guest shell — GET /vpn/locations, no auth)
    /// The browsable, per-account-free location list shown when signed out.
    /// Separate from `servers` on purpose: these carry no `accessible` flag,
    /// no ids the connect path could use, and must never be mistaken for
    /// connectable nodes.
    @Published private(set) var publicLocations: [PublicLocation] = []
    @Published private(set) var isLoadingPublicLocations = false
    @Published var publicLocationsError: String?

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
    private let keychain: KeychainService

    /// Is there a session to authenticate WITH?
    ///
    /// Guest-shell guard (5.1.1(v)): with no account the app still shows Home,
    /// the location list and every setting, so these screens keep calling
    /// `loadServers()` / `refreshSubscription()` on appear. Firing those
    /// without a token would spend requests to earn a 401 and paint a "Session
    /// expired" error over a user who never had a session. Read from the
    /// keychain rather than a copy of `isLoggedIn` so there is exactly one
    /// truth and no ordering bug between the two view models.
    var hasSession: Bool {
        keychain.accessToken != nil || keychain.refreshToken != nil
    }

    // How long to wait for the first inbound byte before declaring the tunnel
    // dead lives in `HandshakeArm` (15 s, fresh dials only), beside the live
    // rebuild's probe-then-revert window in `LiveRebuildProbe` (18 s). Two
    // windows, one file: they were a single value until bug (B) of #350's
    // review, and this file is not one of BirdoVPNTests' sources, so only
    // there can the difference — or the fail-open decision either of them
    // ends in — be asserted at all.
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

    /// REBUILD (Mobile-Client #159): an in-place live rebuild is in flight.
    /// Re-entrancy guard for `rebuildLive`; also suppresses the generic
    /// post-`.connected` handshake arm, whose no-handshake teardown would
    /// otherwise race the rebuild's own revert.
    private var isRebuildingLive = false

    /// REBUILD (#351): a disconnect-first path has stopped the live tunnel and
    /// is waiting for that teardown to land before it re-dials.
    ///
    /// `isConnecting` cannot express this state — `handleStatusChange` CLEARS
    /// it on `.disconnecting` and `.disconnected`, the exact transitions
    /// `awaitTeardown()` waits for — so for the whole ~5 s wait every entry
    /// point read "idle" and a second dial minted a second server-side peer for
    /// the same device. Owned by the two sites in `RedialTeardownSite`, read
    /// only through `TunnelDialGate`, and deliberately NOT cleared by
    /// `resetSessionState()`: a user disconnect landing inside the window would
    /// otherwise re-open the very gap this closes.
    private var isTearingDownForRedial = false

    /// REBUILD (#351): a live rebuild's revert has put the OLD peer back on the
    /// RUNNING session and the `.connected` that restore raises has not been
    /// consumed yet.
    ///
    /// Consumed by `handleStatusChange(.connected)` — the transition itself, so
    /// exactly one arm can inherit it — and dropped on `.disconnected` /
    /// `.invalid` and in `resetSessionState()`, because a marker that outlived
    /// its session would misdescribe the NEXT one. Without it the post-connect
    /// arm treated a restored peer as a fresh dial and could tear down — and
    /// disarm — a session that had been protected the whole time. The decision
    /// itself is `HandshakeArm.noHandshakeOutcome`.
    private var revertRestoredPeerAwaitingConnected = false

    private static let heartbeatInterval: TimeInterval = 30

    init(api: APIClient = .shared,
         vpnManager: VPNManager = .shared,
         keychain: KeychainService = .shared) {
        self.api = api
        self.vpnManager = vpnManager
        self.keychain = keychain

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
        // Guest shell: /vpn/servers is per-account (JwtAuthGuard + a plan-derived
        // `accessible` flag), so signed out there is nothing to fetch. The
        // browsable list a guest gets is `loadPublicLocations()`.
        guard hasSession else { return }
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

    /// Last-fetched public-location cache (same 60 s TTL as `servers`).
    private var publicLocationsTimestamp: Date?

    /// Fetch the unauthenticated location list for the guest shell.
    ///
    /// Deliberately survives sign-out (`resetForLogout` leaves it alone): it is
    /// public data with nothing of the previous account in it, and dropping it
    /// would blank the list the signed-out user is looking at.
    func loadPublicLocations(forceRefresh: Bool = false) {
        if !forceRefresh,
           !publicLocations.isEmpty,
           let ts = publicLocationsTimestamp,
           Date().timeIntervalSince(ts) < Self.serverCacheTTL {
            return
        }
        guard !isLoadingPublicLocations else { return }
        isLoadingPublicLocations = true
        publicLocationsError = nil
        Task {
            defer { isLoadingPublicLocations = false }
            do {
                publicLocations = try await api.fetchPublicLocations()
                publicLocationsTimestamp = Date()
            } catch {
                // No `reportUnauthorized` here: this call sends no credentials,
                // so a failure can never mean "your session died" — mapping it
                // to a logout would sign a user out over a flaky network.
                publicLocationsError = error.localizedDescription
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
        guard hasSession else { return }
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
        // Defence in depth: Home routes a signed-out tap to the sign-in sheet
        // and never reaches here. Connecting genuinely needs an account — the
        // server mints a per-account WireGuard peer and holds a connection
        // slot — so refuse rather than dial into a guaranteed 401.
        guard hasSession else {
            error = "Sign in to connect."
            return
        }
        guard let server = selectedServer else {
            error = "Select a server first"
            return
        }
        // Re-entrancy guard, matching the Android VpnViewModel: a second tap
        // while a connect is already in flight mints a second tunnel session
        // (and a second server-side peer) for the same device. Asked of
        // `TunnelDialGate` rather than of `isConnecting`, which goes FALSE for
        // the several seconds a disconnect-first teardown takes to land (#351)
        // — one decision, read the same way by every entry point (#336).
        guard TunnelDialGate.mayStartDial(isConnecting: isConnecting,
                                          isRebuildingLive: isRebuildingLive,
                                          isTearingDownForRedial: isTearingDownForRedial) else { return }
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
        // Same re-entrancy guard as connect(), through the same gate: a live
        // in-place rebuild counts, and so does the teardown this very function
        // performs below when it is dialled over a live session (#351).
        guard TunnelDialGate.mayStartDial(isConnecting: isConnecting,
                                          isRebuildingLive: isRebuildingLive,
                                          isTearingDownForRedial: isTearingDownForRedial) else { return false }
        // Every caller of this is a user action (the Multi-Hop screen's dial, or
        // a settings blip rebuilding the pair the user chose), so it clears the
        // breaker unconditionally — same rule as `connect(userInitiated: true)`.
        clearCircuitBreaker()
        if isConnected {
            // MULTI-HOP CHANGE-EXIT (#159 review item (d)) — the ONE live
            // rebuild that is deliberately NOT done in place. The Multi-Hop
            // screen can dial a new pair over a live session, and the node-agent
            // one-exit-per-entry guard (birdo-node-agent
            // src/handlers/multihop.rs, `set_exit_default_peer`) 409s a
            // "same entry, different exit" install while a live client — this
            // device's own deferred previous session — still routes via the
            // old exit. Today's eviction-first order is what avoids that, so
            // this path keeps it: stop, wait for the teardown to land, redial.
            // It leaks the gap exactly as before; only the same confirmed pair
            // (a settings blip) is rebuilt in place, via reapplySettings.
            // Also fixes the pre-existing shape here, where `vpnManager.connect`
            // overwrote the profile (on-demand OFF) under a still-running
            // session that the server had just evicted mid-request.
            NSLog("[VpnViewModel] multi-hop dial over a live session — using the disconnect-first path (one-exit-per-entry guard)")
            isSwitching = true
            // #351: hold the gate across the wait. Nothing else can. The
            // teardown below drives the session through `.disconnecting` and
            // `.disconnected`, and the status handler clears `isConnecting` on
            // both, so a second dial arriving inside this window passed every
            // re-entrancy guard and minted a second peer while this one was
            // still tearing the tunnel down under it. `MultiHopView`'s
            // `isSubmitting` covers one door of four. Twin: `legacyRebuild`,
            // which waits on the same `awaitTeardown()`.
            isTearingDownForRedial = true
            vpnManager.disconnect()
            await awaitTeardown()
            // Dropped here and NOT in a `defer`: there is no suspension point
            // between this line and `isConnecting = true` below, so the gate
            // hands straight over to the flag the status handler maintains,
            // with no instant in between where a second dial reads "idle".
            isTearingDownForRedial = false
        }
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
    /// tunnel so changed tunnel-shape settings (quantum, DNS, port, MTU) take
    /// effect. Wired to `SettingsViewModel.onSettingsReapplyNeeded` at the app
    /// root; no-op unless connected.
    ///
    /// REBUILD (Mobile-Client #159): this never calls `vpnManager.disconnect()`
    /// any more — that disarmed on-demand and dropped the session, and with no
    /// session `includeAllNetworks` blocks nothing, so the gap leaked. The peer
    /// is swapped on the LIVE session instead (`rebuildLive`); the server
    /// defers this device's old key until the new peer handshakes. The one
    /// exception is a PROTOCOL-level flag (Local Network Sharing →
    /// `excludeLocalNetworks`), which iOS applies only at tunnel start: that
    /// setting alone still takes the restart path. A user disconnect during
    /// the blip WINS: it clears `isReapplyingSettings` and drops the session
    /// the swap would probe, so the rebuild ends without touching anything.
    func reapplySettings() {
        guard isConnected else { return }
        // `isConnecting` and `isRebuildingLive` are read through the gate, which
        // also sees the disconnect-first teardown window neither of them covers
        // (#351). `isReapplyingSettings` stays separate: it is this path's own
        // in-flight flag, not a property of the tunnel.
        guard TunnelDialGate.mayStartDial(isConnecting: isConnecting,
                                          isRebuildingLive: isRebuildingLive,
                                          isTearingDownForRedial: isTearingDownForRedial) else { return }
        guard activeMultiHop != nil || selectedServer != nil else { return }
        guard !isReapplyingSettings else { return }
        let target: RebuildTarget
        if let mh = activeMultiHop {
            // A live Multi-Hop session rebuilds as the SAME confirmed pair.
            // The unconditional single-hop connect() here used to pass its
            // guard (fetchServers auto-picks selectedServer) and silently
            // rebuild any settings change as single-hop — the user kept
            // seeing the multi-hop UI over a one-hop tunnel.
            target = .multiHop(entry: mh.entry, exit: mh.exit)
        } else if let server = selectedServer {
            target = .single(server)
        } else {
            return
        }
        isReapplyingSettings = true
        error = nil
        if vpnManager.liveProfileNeedsRestart {
            NSLog("[VpnViewModel] reapply: a tunnel-start-only flag changed — using the disconnect-first path")
            legacyRebuild(target)
            return
        }
        Task { [weak self] in await self?.rebuildLive(target, revertSelectionTo: nil) }
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
    /// "device limit reached". That teardown is a fail-OPEN
    /// (`VPNManager.disconnect()` persists on-demand OFF *before* stopping) and
    /// it is the RIGHT answer for exactly one input: a fresh dial, whose rule
    /// was never armed. `HandshakeArm.noHandshakeOutcome` is where that is
    /// decided, and it is decided there — in BirdoVPNTests' one Foundation-only
    /// source — rather than here, because this file is not in that bundle.
    ///
    /// - Parameter peerWasRestoredInPlace: this `.connected` is the OLD peer a
    ///   live rebuild's revert put back on the RUNNING session (#351), not a
    ///   fresh dial. Its on-demand rule has been armed throughout and its key is
    ///   the one the user is still riding, so the no-handshake exit must fail
    ///   CLOSED: the extension's liveness check, the armed rule's re-dial and
    ///   then the circuit breaker are the recovery, exactly as for
    ///   `LiveRebuildFollowUp.stayFailedClosed`. Deliberately has no default —
    ///   a future caller has to answer the question rather than inherit an
    ///   answer, which is how a guard lands on some of N paths (#336).
    private func armKillSwitchAfterHandshake(peerWasRestoredInPlace: Bool) async {
        // A live rebuild owns the handshake wait for its swap (and the revert
        // when it fails); this generic arm must not run its own no-handshake
        // teardown on top of it. The rule it would arm is already armed — the
        // rebuild never disarmed it.
        guard !isRebuildingLive else { return }
        // Measured, not counted. `try? await Task.sleep` on a CANCELLED task
        // returns instantly and swallows the error, so a pass count alone can
        // reach the exit below in microseconds having proved nothing — the
        // shape that once ran 7.7 M iterations in 2 s in the rebuild probe,
        // except that here the cost is not a spin but a teardown. ContinuousClock
        // rather than Date, so an NTP step cannot shorten the window either.
        let startedAt = ContinuousClock.now
        for _ in 0..<HandshakeArm.polls {
            try? await Task.sleep(for: .milliseconds(HandshakeArm.pollMs))
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
        switch HandshakeArm.noHandshakeOutcome(
            peerWasRestoredInPlace: peerWasRestoredInPlace,
            elapsed: startedAt.duration(to: ContinuousClock.now),
            window: .milliseconds(HandshakeArm.windowMs)
        ) {
        case .inconclusive:
            // The loop came back without spending its window, so it has proved
            // nothing about this peer. Decide nothing: no teardown, and no
            // message claiming a verdict. Whatever cancelled it owns the state.
            NSLog("[VpnViewModel] handshake arm ended before its window — no verdict, session left alone")

        case .keepSessionFailClosed:
            // #351. This is the OLD peer a revert put back on a session that
            // never dropped and whose on-demand rule is armed right now. The
            // teardown below would disarm that rule, stop the tunnel and DELETE
            // the key the user is still riding — a fail-OPEN at the end of the
            // one path that exists to fail closed. Keep everything and say so:
            // the extension's liveness check cancels a peer whose handshake has
            // gone stale, the armed rule re-dials the persisted OLD profile,
            // and if that never comes up the breaker trips and
            // `checkCircuitBreaker` performs the counted, user-visible
            // fail-open. Same recovery `.stayFailedClosed` relies on.
            NSLog("[VpnViewModel] restored peer has not re-handshaked — staying fail-closed under the armed rule")
            error = "Your previous location hasn't answered yet. Traffic stays blocked "
                + "until Birdo recovers the connection — or tap Disconnect."

        case .tearDownFailOpen:
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
        guard !isConnected else { return }
        // The fourth door a disconnect-first teardown window used to look idle
        // to (#351). `connect()` reads the same gate, but a dial refused here is
        // also a 1.5 s timer never started and a breaker check never run.
        guard TunnelDialGate.mayStartDial(isConnecting: isConnecting,
                                          isRebuildingLive: isRebuildingLive,
                                          isTearingDownForRedial: isTearingDownForRedial) else { return }
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
            guard !self.isConnected else { return }
            // Re-checked after the sleep against the same gate: 1.5 s is long
            // enough for a user tap, a settings blip or a teardown to start.
            guard TunnelDialGate.mayStartDial(isConnecting: self.isConnecting,
                                              isRebuildingLive: self.isRebuildingLive,
                                              isTearingDownForRedial: self.isTearingDownForRedial) else { return }
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
        guard hasSession else { return }
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
            // #351: consume the revert marker on the TRANSITION, not inside the
            // arm — the arm runs in its own Task and a second `.connected` for
            // the same session would otherwise inherit the same marker. Read
            // and cleared in one main-actor step, so exactly one arm can see it.
            let peerWasRestoredInPlace = revertRestoredPeerAwaitingConnected
            revertRestoredPeerAwaitingConnected = false
            Task { [weak self] in
                await self?.armKillSwitchAfterHandshake(peerWasRestoredInPlace: peerWasRestoredInPlace)
            }
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
            // #351: the session a revert restored is over, so its marker is
            // spent. Bounding it here is what stops a revert whose restore
            // never raised `.connected` at all from describing the NEXT
            // session's peer. A latched marker could not leak traffic — it only
            // ever suppresses a fail-OPEN — but it would leave a genuinely dead
            // fresh tunnel blocking until the breaker trips, and a flag that
            // outlives the thing it describes is how these fixes rot.
            revertRestoredPeerAwaitingConnected = false
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
        // #351: the session is gone, so the revert marker describes nothing.
        // `isTearingDownForRedial` is deliberately NOT cleared here — this runs
        // from `disconnect()`, which a user can tap INSIDE the teardown window,
        // and clearing it there would re-open the double-dial gap it closes.
        revertRestoredPeerAwaitingConnected = false
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
        // The same gate every other entry point reads (#336: one decision, not
        // five), switched on rather than collapsed to a Bool because this door
        // treats one of its reasons differently — and an exception that is
        // written down is one a later reader can check.
        switch TunnelDialGate.block(isConnecting: isConnecting,
                                    isRebuildingLive: isRebuildingLive,
                                    isTearingDownForRedial: isTearingDownForRedial) {
        case .some(.liveRebuildInFlight), .some(.tearingDownForRedial):
            // A switch is already being swapped in place, or a teardown is
            // still landing: never stack a second rebuild on either — the first
            // commits or reverts on its own. (`.tearingDownForRedial` is not
            // reachable today: the `isConnected || isConnecting` guard above
            // already sends a tap during a teardown to a plain selection. It is
            // refused rather than assumed impossible.)
            return
        case .some(.dialInFlight), .none:
            // `.dialInFlight` is NOT a refusal here: a tap while the first dial
            // is still in flight is the documented switch-while-connecting
            // case, and the `guard isConnected, !isConnecting` below routes it
            // to the legacy stop-wait-redial path on purpose.
            break
        }
        let previous = selectedServer
        selectedServer = server
        error = nil
        isSwitching = true
        guard isConnected, !isConnecting else {
            // Still dialling — no settled session to swap under. Today's
            // stop, wait-for-teardown, redial sequence, unchanged.
            legacyRebuild(.single(server))
            return
        }
        // REBUILD (Mobile-Client #159): swap the peer on the live session; the
        // kill switch stays armed throughout. See `rebuildLive`.
        Task { [weak self] in await self?.rebuildLive(.single(server), revertSelectionTo: previous) }
    }

    // MARK: - Live rebuild (Mobile-Client #159)

    private enum RebuildTarget {
        case single(ServerInfo)
        case multiHop(entry: ServerInfo, exit: ServerInfo)
    }

    /// Everything one rebuild attempt needs to finish, whichever way it ends.
    private struct RebuildContext {
        let target: RebuildTarget
        /// Selection to put back if the switch does not take (server switch only).
        let previous: ServerInfo?
        /// The key the live tunnel is riding — the one the server defers.
        let oldKeyId: String
        /// The live profile + secrets, for the swap-back.
        let snapshot: VPNManager.TunnelProfileSnapshot
        /// The minted config, once /connect answered.
        var newConfig: VPNConnectionConfig?
        var confirmedRoute: String?
    }

    /// In-place live rebuild. The `/connect` goes THROUGH the live tunnel with
    /// `rebuild: true` + the key it is riding (`currentKeyId`); the server
    /// defers that ONE key's eviction until the new peer handshakes (its
    /// sweeper retires it — birdo-web `VpnService.sweepSupersededPeers`); the
    /// peer is swapped on the running utun (`WireGuardAdapter.update`, no
    /// interface drop); and only once the NEW peer has delivered bytes does this
    /// client consider the old key released. The session never drops, so the
    /// kill switch never disarms. If the new peer never answers, the snapshot is
    /// swapped back — its peer is still on its node, and the server keeps it
    /// because it is the live one.
    ///
    /// Every outcome goes through `LiveRebuildPolicy` (LiveRebuild.swift) and
    /// `finishRebuild` is its only interpreter. The ONE directive that may stop
    /// the tunnel is `legacyDisconnectFirst`, reached solely when the SERVER
    /// cannot or will not defer our key: it does not know the fields, it did
    /// not echo the key, or it refused the rebuild for a key it holds no live
    /// session under / a route change it will not make in place
    /// (`RebuildRefusal`). No error path stops the tunnel or disarms on-demand.
    private func rebuildLive(_ target: RebuildTarget, revertSelectionTo previous: ServerInfo?) async {
        guard !isRebuildingLive else { return }
        guard let oldKeyId = activeKeyId else {
            // No server handle for the live session (an install upgraded under a
            // live tunnel from a build that never stored one): nothing the server
            // could defer against — today's path.
            NSLog("[VpnViewModel] rebuild: live session has no connection handle — using the disconnect-first path")
            legacyRebuild(target)
            return
        }
        let snapshot: VPNManager.TunnelProfileSnapshot
        do {
            snapshot = try vpnManager.snapshotRunningProfile()
        } catch {
            NSLog("[VpnViewModel] rebuild: could not snapshot the live profile — using the disconnect-first path")
            legacyRebuild(target)
            return
        }
        isRebuildingLive = true
        var flagsOwnedElsewhere = false
        defer {
            isRebuildingLive = false
            if !flagsOwnedElsewhere {
                isReapplyingSettings = false
                isSwitching = false
            }
        }
        var ctx = RebuildContext(target: target, previous: previous, oldKeyId: oldKeyId,
                                 snapshot: snapshot, newConfig: nil, confirmedRoute: nil)

        // 1. /connect THROUGH the live tunnel, telling the server which key it rides.
        let config: VPNConnectionConfig
        do {
            switch target {
            case .single(let server):
                config = try await api.getConnectConfig(serverId: server.id, rebuildOf: oldKeyId)
            case .multiHop(let entry, let exit):
                config = try await api.getMultiHopConfig(entryId: entry.id, exitId: exit.id, rebuildOf: oldKeyId)
            }
        } catch let apiError as APIError where apiError.isRebuildFieldRejection {
            // The server does not know `rebuild` — read from the validation
            // error's own words, never from a bare 400. It would evict our own
            // peer mid-request, so use the pre-#159 path (leaks the gap as
            // today; never a blackhole).
            NSLog("[VpnViewModel] rebuild: server does not know the rebuild fields — using the disconnect-first path")
            flagsOwnedElsewhere = await finishRebuild(.serverDoesNotKnowRebuild, ctx)
            return
        } catch let refusal as RebuildRefusedError {
            // The server REFUSED the rebuild before touching anything (2xx
            // {success:false, rebuildRefused}): nothing evicted, nothing minted.
            // The policy decides by code — `unknown-current-key` (no live
            // session under the handle we ride: nothing to defer, nothing to
            // blackhole, and every later rebuild refused the same way) and
            // `same-entry-exit-change` take the disconnect-first path; the rest
            // keep the session and show the server's words. The log names the
            // classified event, never the server's string.
            let event = RebuildRefusal.event(forCode: refusal.code)
            NSLog("[VpnViewModel] rebuild: refused by the server — %@", String(describing: event))
            if !LiveRebuildPolicy.directive(for: event).stopsTheTunnel {
                self.error = refusal.message
            }
            flagsOwnedElsewhere = await finishRebuild(event, ctx)
            return
        } catch {
            // Old tunnel untouched, still protected.
            self.error = error.localizedDescription
            reportUnauthorized(error)
            flagsOwnedElsewhere = await finishRebuild(.configRequestFailed, ctx)
            return
        }
        // Server-side the NEW peer now exists (and, if honoured, the OLD one is
        // deferred rather than evicted).
        ctx.newConfig = config

        // 2. Multi-Hop: refuse a route the server did not confirm (same rule as
        //    connectMultiHop — rationale in MultiHopRoute.swift).
        if case .multiHop(let entry, let exit) = target {
            do {
                ctx.confirmedRoute = try MultiHopRouteCheck.validate(
                    config.multiHop, requestedEntryId: entry.id, requestedExitId: exit.id
                ).route
            } catch {
                self.error = error.localizedDescription
                flagsOwnedElsewhere = await finishRebuild(.routeNotConfirmed, ctx)
                return
            }
        }

        // 3. Was OUR key deferred? Only an echo of the exact key counts.
        guard let newKeyId = config.keyId,
              RebuildDeferral.honoured(currentKeyId: oldKeyId, deferredKeyId: config.deferredKeyId) else {
            // The server accepted the flag but did not echo the key we ride: it
            // did not defer it (a backend that knows the field but not the echo,
            // or a handle it no longer holds), so the old peer may already be
            // gone. Today's path, minus the key this attempt minted.
            NSLog("[VpnViewModel] rebuild: server did not defer this device's current key — using the disconnect-first path")
            flagsOwnedElsewhere = await finishRebuild(.deferralNotHonoured, ctx)
            return
        }

        // 4. Swap the peer on the RUNNING session. On-demand stays armed.
        setActiveKeyId(newKeyId)
        do {
            try await vpnManager.swapConfig(config: config)
        } catch VPNManagerError.noLiveSession {
            // The session went away under the swap (user disconnect, OS drop,
            // a heartbeat revoke). `applyLiveProfile` throws this BEFORE any
            // write, so nothing was swapped: keychain and persisted profile are
            // still the OLD ones. Not `.swapFailed`: its revert would fail the
            // same way into `.stayFailedClosed(false)`, pinning `activeKeyId`
            // on a NEW key nothing rides (the host heartbeat then tears the
            // re-dialled OLD tunnel down once the sweeper retires that key) and
            // telling a user who just disconnected that traffic is blocked.
            NSLog("[VpnViewModel] rebuild: session gone before the swap — releasing the minted key")
            flagsOwnedElsewhere = await finishRebuild(.sessionGoneBeforeSwap, ctx)
            return
        } catch let swapError {
            NSLog("[VpnViewModel] rebuild: in-place swap refused — reverting to the previous profile: %@",
                  swapError.localizedDescription)
            // Deliberately NO restore here. `.swapFailed` → `.revertToOld` runs
            // the ONE revert path in `finishRebuild`: restore the snapshot so
            // the keychain, the persisted profile, the running tunnel and both
            // heartbeats name the OLD key again, and release the NEW key only
            // once that restore has succeeded. A best-effort restore here
            // followed by `.releaseNewKey` deleted the new key even when the
            // restore had failed — leaving the persisted profile, and so the
            // armed on-demand rule's re-dial, on a peer this client had just
            // deleted.
            self.error = swapError.localizedDescription
            flagsOwnedElsewhere = await finishRebuild(.swapFailed, ctx)
            return
        }

        // 5. Only the NEW peer's bytes prove the switch.
        flagsOwnedElsewhere = await finishRebuild(await awaitNewPeerHandshake(), ctx)
    }

    /// The single interpreter of `LiveRebuildPolicy`. Returns true when the
    /// in-flight flags (`isSwitching` / `isReapplyingSettings`) are now owned by
    /// a later `.connected` or the stalled-flags watchdog rather than by
    /// `rebuildLive`'s exit.
    private func finishRebuild(_ event: LiveRebuildEvent, _ ctx: RebuildContext) async -> Bool {
        let directive = LiveRebuildPolicy.directive(for: event)
        // No identifiers in this line: no key ids, no hosts (node-agent privacy
        // guard convention, applied to client logs too).
        NSLog("[VpnViewModel] rebuild outcome: %@ -> %@", String(describing: event), String(describing: directive))
        switch directive {
        case .legacyDisconnectFirst(let releaseNewKeyFirst):
            if releaseNewKeyFirst, let newId = ctx.newConfig?.keyId { release(keyId: newId) }
            legacyRebuild(ctx.target)
            return true

        case .keepSession(let followUp):
            // #351 - DERIVED from the policy, never hand-placed. This is the
            // ONE assignment of the marker, and it is what makes
            // `HandshakeArm.restoresPeerInPlace` a function the APP calls
            // rather than a table only the tests read. Written out by hand
            // inside `.revertToOld`, the marker could be deleted - reverting
            // the whole fail-open fix - with all 30 BirdoVPNTests cases still
            // green: `VpnViewModel` is not one of that bundle's sources
            // (project.yml), so no test could ever see it. Reading it from the
            // tested function is what makes those tests load-bearing on the
            // app (#336: a truth stated twice is the defect shape).
            //
            // Raised HERE, before the switch body, because `.revertToOld`'s
            // `restoreProfile` can have the `.reasserting -> .connected` it
            // provokes delivered while it is still awaiting: handleStatusChange
            // has to already know that peer was PUT BACK, not freshly dialled.
            // Monotonic (`if`, never `=`): `.revertToOld` recurses into
            // `.revertFailed` -> `.stayFailedClosed`, and a failed restore must
            // not clear a marker the attempt earned. Only the end of the
            // session clears it (handleStatusChange, resetSessionState).
            if HandshakeArm.restoresPeerInPlace(followUp) {
                revertRestoredPeerAwaitingConnected = true
            }
            switch followUp {
            case .nothing:
                if let previous = ctx.previous { selectedServer = previous }
                return false

            case .releaseNewKey:
                if let newId = ctx.newConfig?.keyId { release(keyId: newId) }
                // The persisted profile is the OLD one on both events that land
                // here, so the OLD key is what any armed re-dial rides — put the
                // handle back unconditionally (the re-dial may not have reached
                // `.connecting` yet). After a user disconnect it is a stale
                // handle, never a live one this device is not on: the next
                // connect overwrites it and a repeated DELETE answers
                // "Connection not found".
                setActiveKeyId(ctx.oldKeyId)
                if let previous = ctx.previous { selectedServer = previous }
                return false

            case .commitNew:
                switch ctx.target {
                case .single(let server):
                    activeMultiHop = nil
                    connectedServerId = server.id
                case .multiHop(let entry, let exit):
                    activeMultiHop = MultiHopSession(entry: entry, exit: exit, route: ctx.confirmedRoute ?? "")
                    connectedServerId = entry.id
                }
                quantumActive = BirdoPQManager.shared.currentMode == .bilateral
                // Real inbound bytes on the new peer: the breaker's streak is
                // spent history (same rule as armKillSwitchAfterHandshake).
                clearCircuitBreaker()
                killSwitchArmed = vpnManager.killSwitchArmed
                error = nil
                // Belt-and-braces: the server retires the old key itself once
                // the new peer handshakes (its supersede sweeper); this only
                // shortens the double-tenancy window. A row it has already
                // retired answers "Connection not found", which is harmless.
                release(keyId: ctx.oldKeyId)
                return false

            case .revertToOld:
                // The ONE revert path — a refused swap and a silent new peer
                // alike. Order is load-bearing: restore FIRST (the snapshot
                // puts the OLD secrets back under the same keychain refs,
                // persists the OLD profile — what the armed on-demand rule
                // re-dials — and reconfigures the running tunnel, whose
                // `reconfigure` restarts the extension heartbeat on the OLD
                // `hb-key-id`); then the host heartbeat target; then the DELETE
                // of the NEW key. A restore that fails is `.revertFailed`,
                // which releases nothing.
                //
                // The extension answers a refused `adapter.update` while the
                // adapter still has `reasserting` raised, so for a few ms the
                // host can see `.reasserting` (`isConnecting`) and the restore
                // would refuse with `noLiveSession` on exactly the path that
                // needs it. Let it settle — bounded to 1 s.
                var settleTicks = 0
                while isConnected, isConnecting, settleTicks < 10 {
                    try? await Task.sleep(for: .milliseconds(100))
                    settleTicks += 1
                }
                // #351 — raise the marker BEFORE the restore, never after. The
                // `.reasserting → .connected` the extension's `adapter.update`
                // raises can be delivered while `restoreProfile` is still
                // awaiting, and `handleStatusChange` has to already know that
                // the peer under that `.connected` is one this client PUT BACK
                // on a session whose rule has been armed throughout — not a
                // fresh dial. Without it the generic arm probed the restored
                // OLD peer for 15 s and, if it had not re-handshaked (old node
                // down, or the server sweeper's retire-old race), called
                // `vpnManager.disconnect()` + `releaseServerSlot()`: on-demand
                // persisted OFF, the tunnel stopped, the OLD key DELETEd. The
                // arm's own `isRebuildingLive` guard does not cover it —
                // there is no suspension point between `restoreProfile`
                // returning and `rebuildLive`'s `defer`, so that `.connected`
                // is handled after the flag is already down.
                //
                // It stays raised through a FAILED restore too: `.revertFailed`
                // → `.stayFailedClosed` keeps the session and the armed rule for
                // the breaker to resolve, and a teardown there is the same
                // fail-open by another route.
                // The marker for this follow-up is raised by the single
                // `HandshakeArm.restoresPeerInPlace` gate above, which runs
                // before this body and therefore before the restore.
                do {
                    try await vpnManager.restoreProfile(ctx.snapshot)
                } catch VPNManagerError.reconfigureRefused {
                    return await finishRebuild(.revertFailed(persistedProfileIsOld: true), ctx)
                } catch {
                    return await finishRebuild(.revertFailed(persistedProfileIsOld: false), ctx)
                }
                setActiveKeyId(ctx.oldKeyId)
                if let previous = ctx.previous {
                    selectedServer = previous
                    connectedServerId = previous.id
                }
                if let newId = ctx.newConfig?.keyId { release(keyId: newId) }
                // A refused swap already put its own reason on screen (rebuildLive).
                if event != .swapFailed {
                    error = "Couldn't reach that server. You're still connected to your previous location."
                }
                return false

            case .handOffToOnDemandRedial:
                // The OS is re-dialling the persisted NEW profile under the
                // still-armed rule; `.connected` (or the watchdog) owns the flags.
                Task { [weak self] in await self?.clearRebuildFlagsIfStalled() }
                return true

            case .stayFailedClosed(let persistedProfileIsOld):
                // FAIL CLOSED. Nothing here stops the tunnel or disarms
                // on-demand. The extension's liveness check / heartbeat will
                // cancel the dead peer, the armed rule re-dials the persisted
                // profile, and if that never comes up the breaker trips and
                // `checkCircuitBreaker` performs the user-visible fail-open —
                // the same recovery every dead tunnel already has.
                if persistedProfileIsOld {
                    setActiveKeyId(ctx.oldKeyId)
                    if let previous = ctx.previous {
                        selectedServer = previous
                        connectedServerId = previous.id
                    }
                } else if let newId = ctx.newConfig?.keyId {
                    setActiveKeyId(newId)
                }
                error = "That server didn't answer and the tunnel couldn't be switched back. "
                    + "Traffic stays blocked until Birdo recovers the connection — or tap Disconnect."
                return false
            }
        }
    }

    /// After `replace_peers` the old peer's counters left with it, so any rx on
    /// the interface came from the NEW peer — the same proof
    /// `armKillSwitchAfterHandshake` uses. `currentStats` reports (0,0) while
    /// the session is `.reasserting`, which simply keeps polling.
    ///
    /// The window is `LiveRebuildProbe` (18 s), NOT the fresh dial's 15 s:
    /// it must not end on a WireGuard REKEY_TIMEOUT boundary, and with the
    /// round trip, the swap and the revert's re-handshake added it must still
    /// end inside the server's 30 s deferral grace, which runs from the
    /// server mint. The reasoning lives beside the constants.
    ///
    /// Bounded by the CLOCK as well as by the pass count. `polls × pollMs` is a
    /// FLOOR on the elapsed time, not the window: each pass also pays a
    /// `sendProviderMessage` round trip, so counting iterations alone lets a
    /// loaded extension carry the probe past 18 s and spend the grace the
    /// revert still needs — on the one case where overrunning it costs the
    /// user the peer they are reverting TO. Both caps live in
    /// `LiveRebuildProbe.shouldProbeAgain`, which is where they are tested;
    /// keeping the pass count is what makes a `Task.sleep` that consumes no
    /// time (a cancelled task) exit instead of spin.
    private func awaitNewPeerHandshake() async -> LiveRebuildEvent {
        // ContinuousClock, not Date: a clock step (NTP, a manual date change)
        // must not shorten or extend the one window the server's grace is
        // being measured against.
        let deadline = ContinuousClock.now.advanced(by: .milliseconds(LiveRebuildProbe.windowMs))
        var passesDone = 0
        while true {
            let remaining = ContinuousClock.now.duration(to: deadline)
            guard LiveRebuildProbe.shouldProbeAgain(passesDone: passesDone, remaining: remaining) else { break }
            passesDone += 1
            try? await Task.sleep(for: LiveRebuildProbe.nextSleep(remaining: remaining))
            guard isConnected || isConnecting else { return .sessionDroppedWhileProbing }
            if await vpnManager.currentStats().rx > 0 { return .newPeerHandshaked }
        }
        guard isConnected || isConnecting else { return .sessionDroppedWhileProbing }
        return .newPeerSilent
    }

    /// Fire-and-forget `DELETE /vpn/connections/{keyId}` for a key this device
    /// no longer rides. Best effort, like `releaseServerSlot`; never logs the id.
    private func release(keyId: String) {
        Task { [api] in
            do {
                try await api.disconnect(keyId: keyId)
            } catch {
                NSLog("[VpnViewModel] rebuild: key release failed: %@", error.localizedDescription)
            }
        }
    }

    /// The pre-#159 rebuild: disconnect, WAIT for the teardown to land, redial.
    /// It disarms on-demand and leaks the gap — kept ONLY as the fallback for a
    /// server that cannot defer our key, where the old peer is (or may be) gone
    /// and there is nothing left to keep up.
    private func legacyRebuild(_ target: RebuildTarget) {
        // #351, and the twin of `connectMultiHop`'s disconnect-first block: the
        // redial below waits for a teardown that CLEARS `isConnecting`, so
        // without a flag that survives those transitions every entry point read
        // "idle" for the whole wait. Guarding one of the two teardown sites and
        // not the other is the #336 shape exactly.
        isTearingDownForRedial = true
        vpnManager.disconnect()
        Task { [weak self] in
            await self?.awaitTeardown()
            guard let self else { return }
            // Dropped before the redial, because this teardown and that redial
            // are ONE attempt: `connect()` / `connectMultiHop()` read the flag
            // through `TunnelDialGate` and would otherwise refuse the very dial
            // this teardown was performed for. `isRebuildingLive` is already
            // false by now even when `finishRebuild` called us mid-rebuild —
            // nothing suspends between `legacyRebuild` returning and
            // `rebuildLive`'s `defer`, and `awaitTeardown()` always sleeps.
            self.isTearingDownForRedial = false
            guard self.isReapplyingSettings || self.isSwitching else { return }
            switch target {
            case .single(let server):
                self.selectedServer = server
                self.connect()
            case .multiHop(let entry, let exit):
                await self.connectMultiHop(entry: entry, exit: exit)
            }
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
