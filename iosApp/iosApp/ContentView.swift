import SwiftUI
import Network

/// macOS sizes a sheet to its content, and LoginView's root is a
/// `GeometryReader` — which has no intrinsic size, so an unconstrained sheet
/// can collapse to a sliver there. iOS page sheets take their own size, so
/// this is a no-op on that side.
private struct SignInSheetSizing: ViewModifier {
    func body(content: Content) -> some View {
        #if os(macOS)
        content.frame(minWidth: 460, idealWidth: 520, minHeight: 620, idealHeight: 720)
        #else
        content
        #endif
    }
}

/// App-wide reachability probe backing the offline banner (spec §0.4).
///
/// `NWPathMonitor` delivers updates on a private background queue; the update
/// handler hops to the main actor before touching `@Published isOffline`, so
/// the flag is only ever mutated on the main thread (no publish-during-render,
/// no data race under Swift 6 strict concurrency). Monitoring stops in
/// `deinit`.
@MainActor
final class NetworkMonitor: ObservableObject {
    @Published private(set) var isOffline = false

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "app.birdo.networkmonitor")

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let offline = path.status != .satisfied
            Task { @MainActor in
                guard let self else { return }
                if self.isOffline != offline { self.isOffline = offline }
            }
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }
}

/// Root router + tab shell.
///
/// Routing contract (`RootRoute.decide`, GuestAccess.swift):
///
///     !hasConsented && !consentDeferred  -> ConsentView
///     otherwise                          -> tab shell, SIGNED IN OR GUEST
///
/// 🔴 WHAT CHANGED AND WHY — App Store Guideline 5.1.1(v), macOS 1.4.22
/// rejected 10 Aug 2026: "The app requires users to register or log in to
/// access features that are not account based." The old contract was
/// `hasConsented && isLoggedIn -> shell, else -> LoginView`, so NOTHING was
/// reachable before login — including the entire Settings tab, VPN Settings,
/// the privacy policy, the terms and the about card, none of which touch an
/// account at all. `isLoggedIn` no longer appears in the routing decision;
/// sign-in is a SHEET raised at the point of use (`authVM.requestSignIn`).
/// Do not reintroduce a login route: `RootRoute.decide` is unit tested
/// precisely so this cannot regress silently.
///
/// What stays gated (and is honestly gated): connect, multi-hop, port
/// forwarding, data usage and the profile — the server mints a per-account
/// WireGuard peer and allocates a connection slot, so those genuinely cannot
/// work without an account.
///
/// `isLoggedIn` is decided synchronously at cold start from the stored JWTs'
/// `exp` claims (AuthViewModel.init); a background GET /auth/me then runs and
/// only a definitive 401 flips the user back to guest (network errors keep
/// the session). While `authVM.createdAnonymousId != nil` the sign-in sheet
/// deliberately stays up and undismissable — it presents the "save your
/// account number" acknowledgment step before `isLoggedIn` flips.
///
/// Back-stack semantics: each tab owns its own NavigationStack, so switching
/// tabs preserves per-tab push state; the login<->home flip replaces the whole
/// shell (SwiftUI tears the TabView down), which clears every stack — the
/// equivalent of Android's navigate-with-popUpTo-inclusive.
struct ContentView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @EnvironmentObject var vpnVM: VpnViewModel
    @EnvironmentObject var settingsVM: SettingsViewModel
    @EnvironmentObject var storeVM: StoreKitService

    /// Android bottom-nav tab set, in order: Profile · Connect · Limit ·
    /// Settings (spec-home-servers-consent §2). ServerList is NOT a tab — it
    /// is a route pushed from Home inside the Connect tab's stack.
    enum Tab: Hashable {
        case profile, home, limit, settings
    }

    @State private var selectedTab: Tab = .home
    @StateObject private var network = NetworkMonitor()
    @Environment(\.scenePhase) private var scenePhase

    /// Offline banner shows only when the device is truly offline AND the
    /// tunnel is not up/coming up (spec §0.4) — a live/connecting tunnel means
    /// traffic still flows, so "No internet connection" would be a lie.
    private var showOfflineBanner: Bool {
        network.isOffline && !vpnVM.isConnected && !vpnVM.isConnecting
    }

    var body: some View {
        ZStack {
            // Base coat so route swaps never flash the system background.
            BirdoTheme.black.ignoresSafeArea()

            VStack(spacing: 0) {
                if showOfflineBanner {
                    offlineBanner
                        .transition(.move(edge: .top).combined(with: .opacity))
                }

                ZStack {
                    switch RootRoute.decide(hasConsented: authVM.hasConsented,
                                            consentDeferred: authVM.consentDeferred) {
                    case .consent:
                        // Shown ONCE, on first launch. "Not now" defers into
                        // the guest shell instead of dead-ending the user
                        // (the old Decline left them stuck here forever).
                        ConsentView()
                    case .shell:
                        mainShell
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .animation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.standard),
                   value: showOfflineBanner)
        .onAppear {
            // Root view-model wiring — assignments are idempotent, so a
            // repeated onAppear is harmless.
            //
            // Settings toggle flips (quantum / LAN sharing / custom DNS)
            // debounce into one live tunnel "blip".
            settingsVM.onSettingsReapplyNeeded = { [weak vpnVM] in
                vpnVM?.reapplySettings()
            }
            // S3 FIX: a definitively dead session (refresh failed, retry
            // 401'd) used to strand the user on empty screens forever —
            // nothing mapped unauthorized back to Login. Tunnel down FIRST:
            // logout wipes the keychain the tunnel extension reads.
            vpnVM.onUnauthorized = { [weak vpnVM, weak authVM] in
                vpnVM?.disconnect()
                authVM?.logout()
            }
            // Review #490: clear cross-account cached plan/servers on sign-out so
            // the next user never inherits the previous account's state.
            authVM.onLogout = { [weak vpnVM] in
                vpnVM?.resetForLogout()
            }

            // App Store purchase rail (Guideline 3.1.1). Wired here for the
            // same reason as the hooks above: this is the one place that can
            // see every view model at once.
            //
            // `start()` is what puts the long-lived `Transaction.updates`
            // listener in place. It has to happen at LAUNCH, not when the
            // subscription screen opens: an Ask-to-Buy approval, a renewal, a
            // Family Sharing change or a purchase made on another device all
            // arrive there, and a purchase completed outside our own
            // `purchase()` call would otherwise never be seen by this process.
            let auth = authVM
            let vpn = vpnVM
            storeVM.isSignedIn = { [weak auth] in auth?.isLoggedIn ?? false }
            storeVM.requestSignIn = { [weak auth] in auth?.requestSignIn(.subscribe) }
            storeVM.onEntitlementChanged = { [weak vpn] in
                // The server has just changed what this account is entitled
                // to, and /vpn/stats is the app's single source of plan truth —
                // force past the 30 s cache so every plan gate opens now
                // rather than up to half a minute after the user paid.
                vpn?.refreshSubscription(force: true)
            }
            storeVM.start()
        }
        // Sign-in is a SHEET, raised only by an action that genuinely needs an
        // account (5.1.1(v)). Consent comes FIRST inside the same sheet when
        // the user deferred it on first launch: creating or signing into an
        // account is the point at which personal data is processed, so that is
        // where the disclosure has to be agreed to — not at app launch.
        .sheet(isPresented: $authVM.isPresentingSignIn) {
            Group {
                if authVM.hasConsented {
                    LoginView(isSheet: true)
                } else {
                    ConsentView(isSheet: true)
                }
            }
            // Sheets do not reliably inherit @EnvironmentObject on every
            // platform/OS combination this app ships to (iOS 17 + macOS 14),
            // and a missing one is a hard crash — inject explicitly.
            .environmentObject(authVM)
            .environmentObject(vpnVM)
            .environmentObject(settingsVM)
            .preferredColorScheme(.dark)
            // A freshly minted anonymous ID is shown EXACTLY once and is the
            // account's only credential: swiping the sheet away there would
            // lose it. Every other step is freely dismissable.
            .interactiveDismissDisabled(authVM.createdAnonymousId != nil)
            .modifier(SignInSheetSizing())
        }
        // S1 FIX: loadServers() was previously reachable ONLY from the manual
        // refresh button, so a fresh launch showed "0 servers" and Connect
        // yielded "Select a server first". Fire on the login flip AND on cold
        // start when already logged in — Android parity
        // (BirdoNavGraph.kt:149-151, :168-171).
        .task(id: authVM.isLoggedIn) {
            guard authVM.isLoggedIn else {
                // Guest shell: the per-account server list is unavailable, so
                // load the public (unauthenticated) location list instead —
                // browsing locations is not an account-based feature.
                vpnVM.loadPublicLocations()
                return
            }
            if vpnVM.servers.isEmpty && !vpnVM.isLoadingServers {
                vpnVM.loadServers()
            }
            vpnVM.refreshSubscription()   // plan gates + Profile/Limit hero (30s cache)
            vpnVM.autoConnectIfEnabled()  // no-op unless the "auto_connect" pref is ON
            // A purchase can complete while signed out — the App Store does not
            // care whether Birdo has a session — and /link needs one. Signing
            // in is therefore the moment those transactions can finally be
            // bound, so re-present everything StoreKit still considers current.
            await storeVM.linkExistingEntitlementsAfterSignIn()
        }
        .onChange(of: authVM.isLoggedIn) { _, loggedIn in
            // Signing in is the sheet's whole job — close it the moment it is
            // done, from ONE place rather than at each of the seven sites that
            // flip `isLoggedIn`.
            if loggedIn { authVM.isPresentingSignIn = false }
            // Either direction lands on Connect: a fresh session starts there
            // (Android's navigate-Home-clearing-backstack), and a sign-out
            // should not leave the user staring at a tab that just turned into
            // a sign-in prompt.
            selectedTab = .home
        }
        .onChange(of: scenePhase) { _, phase in
            // P1-ios-redial-loop-blackhole: the recovery hook the user actually
            // reaches. When the tunnel extension has trapped the device behind a
            // dead tunnel, the network is gone and the ONLY thing the user can
            // still do is open this app — so foregrounding must be what restores
            // traffic. `checkCircuitBreaker()` disarms the on-demand rule, clears
            // the kill-switch flags and stops the dead tunnel.
            //
            // Foregrounding does NOT clear the breaker — it acts on it. The
            // banner has to survive long enough for the user to read it.
            guard phase == .active else { return }
            vpnVM.checkCircuitBreaker()
        }
        .onOpenURL { url in
            // `birdo://` deep links (Android parity: connect / servers /
            // settings). `birdo://auth?…` is the SSO callback and is consumed
            // by ASWebAuthenticationSession in-session — if it ever arrives
            // here (plain-browser fallback) it is deliberately ignored.
            // No `isLoggedIn` gate any more: connect / servers / settings are
            // all reachable in the guest shell (Home and the location list
            // raise the sign-in sheet themselves when an account is actually
            // needed), and dropping a deep link on the floor because nobody is
            // signed in was part of the same launch-wall assumption.
            guard url.scheme?.lowercased() == "birdo" else { return }
            switch url.host?.lowercased() {
            case "connect":  selectedTab = .home
            case "servers":  selectedTab = .home   // server list is pushed from Home
            case "settings": selectedTab = .settings
            default: break
            }
        }
    }

    // MARK: - Offline banner (spec §0.4)

    /// Full-width red bar above the shell: wifi.slash icon + "No internet
    /// connection" (white 13sp). Sits above the safe-area inset so it reads as
    /// a system-level notice, and is announced to VoiceOver.
    private var offlineBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: "wifi.slash")
                .font(.system(size: 15, weight: .semibold))
            Text("No internet connection")
                .font(.system(size: 13, weight: .medium))
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .background(BirdoTheme.red.opacity(0.95).ignoresSafeArea(edges: .top))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("No internet connection")
    }

    // MARK: - Tab shell (signed in OR guest)

    private var mainShell: some View {
        TabView(selection: $selectedTab) {
            NavigationStack {
                tabRoot(.profile) { ProfileView() }
            }
            .tabItem { Label("Profile", systemImage: "person") }
            .tag(Tab.profile)

            NavigationStack {
                tabRoot(.home) { HomeView() }
            }
            .tabItem { Label("Connect", systemImage: "power") }
            .tag(Tab.home)

            NavigationStack {
                tabRoot(.limit) { LimitView() }
            }
            .tabItem { Label("Limit", systemImage: "speedometer") }
            .tag(Tab.limit)

            NavigationStack {
                tabRoot(.settings) { SettingsView() }
            }
            .tabItem { Label("Settings", systemImage: "gearshape") }
            .tag(Tab.settings)
        }
        .tint(BirdoTheme.accent)
    }

    /// Tab-root background per the PixelCanvas placement contract: the canvas
    /// lives at the shell level and tab-root screens stay transparent above
    /// it. Only the SELECTED tab mounts a live canvas so exactly one 20fps
    /// TimelineView exists at any moment; hidden tabs keep a static black
    /// base. Pushed sub-screens are opaque and bring their own canvas.
    @ViewBuilder
    private func tabRoot<Content: View>(_ tab: Tab, @ViewBuilder content: () -> Content) -> some View {
        ZStack {
            if selectedTab == tab {
                PixelCanvasView()
            } else {
                BirdoTheme.black.ignoresSafeArea()
            }
            content()
        }
    }
}
