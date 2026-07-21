import SwiftUI

/// Root router + logged-in tab shell.
///
/// Routing contract (spec-auth-flow §0 — consent gates BEFORE login):
///
///     !authVM.hasConsented                       -> ConsentView
///     authVM.hasConsented && authVM.isLoggedIn   -> tab shell
///     else                                       -> LoginView
///
/// `isLoggedIn` is decided synchronously at cold start from the stored JWTs'
/// `exp` claims (AuthViewModel.init); a background GET /auth/me then runs and
/// only a definitive 401 flips the user back to Login (network errors keep
/// the session). While `authVM.createdAnonymousId != nil` the router
/// deliberately stays on the Login route — LoginView presents the "save your
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

    /// Android bottom-nav tab set, in order: Profile · Connect · Limit ·
    /// Settings (spec-home-servers-consent §2). ServerList is NOT a tab — it
    /// is a route pushed from Home inside the Connect tab's stack.
    enum Tab: Hashable {
        case profile, home, limit, settings
    }

    @State private var selectedTab: Tab = .home

    var body: some View {
        ZStack {
            // Base coat so route swaps never flash the system background.
            BirdoTheme.black.ignoresSafeArea()

            if !authVM.hasConsented {
                // Consent is shown ONCE, before Login ever appears. The screen
                // itself drives authVM.acceptConsent()/declineConsent();
                // decline keeps the user here (iOS cannot self-exit).
                ConsentView()
            } else if authVM.isLoggedIn {
                mainShell
            } else {
                LoginView()
            }
        }
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
        }
        // S1 FIX: loadServers() was previously reachable ONLY from the manual
        // refresh button, so a fresh launch showed "0 servers" and Connect
        // yielded "Select a server first". Fire on the login flip AND on cold
        // start when already logged in — Android parity
        // (BirdoNavGraph.kt:149-151, :168-171).
        .task(id: authVM.isLoggedIn) {
            guard authVM.isLoggedIn else { return }
            if vpnVM.servers.isEmpty && !vpnVM.isLoadingServers {
                vpnVM.loadServers()
            }
            vpnVM.refreshSubscription()   // plan gates + Profile/Limit hero (30s cache)
            vpnVM.autoConnectIfEnabled()  // no-op unless the "auto_connect" pref is ON
        }
        .onChange(of: authVM.isLoggedIn) { _, loggedIn in
            // A fresh session always starts on Connect, like Android's
            // navigate-Home-clearing-backstack after login.
            if !loggedIn { selectedTab = .home }
        }
        .onOpenURL { url in
            // `birdo://` deep links (Android parity: connect / servers /
            // settings). `birdo://auth?…` is the SSO callback and is consumed
            // by ASWebAuthenticationSession in-session — if it ever arrives
            // here (plain-browser fallback) it is deliberately ignored.
            guard url.scheme?.lowercased() == "birdo", authVM.isLoggedIn else { return }
            switch url.host?.lowercased() {
            case "connect":  selectedTab = .home
            case "servers":  selectedTab = .home   // server list is pushed from Home
            case "settings": selectedTab = .settings
            default: break
            }
        }
    }

    // MARK: - Logged-in shell

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
