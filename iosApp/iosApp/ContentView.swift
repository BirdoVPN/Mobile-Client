import SwiftUI

/// Root navigation container with tab bar matching Android bottom nav.
struct ContentView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @EnvironmentObject var vpnVM: VpnViewModel

    enum Tab {
        case home, servers, settings
    }

    @State private var selectedTab: Tab = .home

    var body: some View {
        Group {
            // AUDIT-C: consent is a single source of truth (`authVM.hasConsented`,
            // backed by the `gdpr_consented` UserDefaults key). ContentView no
            // longer keeps a divergent @State / "hasAcceptedPrivacy" key, which
            // previously caused split-brain consent and a non-atomic update race.
            if !authVM.hasConsented {
                ConsentView(onAccept: {
                    UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "privacyConsentTimestamp")
                    authVM.acceptConsent()
                }, onDecline: {
                    // Zero-consent-zero-access: withdrawing consent must purge any
                    // credentials/keys so the PacketTunnel extension cannot tunnel.
                    // logout() clears the keychain + resets the PQ keypair.
                    UserDefaults.standard.removeObject(forKey: "privacyConsentTimestamp")
                    authVM.declineConsent()
                    vpnVM.disconnect()
                    authVM.logout()
                })
            } else if !authVM.isLoggedIn {
                LoginView()
            } else {
                TabView(selection: $selectedTab) {
                    NavigationStack {
                        HomeView()
                    }
                    .tabItem {
                        Label("Connect", systemImage: "power")
                    }
                    .tag(Tab.home)

                    NavigationStack {
                        ServerListView()
                    }
                    .tabItem {
                        Label("Servers", systemImage: "server.rack")
                    }
                    .tag(Tab.servers)

                    NavigationStack {
                        SettingsView()
                    }
                    .tabItem {
                        Label("Settings", systemImage: "gearshape")
                    }
                    .tag(Tab.settings)
                }
                .tint(.white)
            }
        }
    }
}
