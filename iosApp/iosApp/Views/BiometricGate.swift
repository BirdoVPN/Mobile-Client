import SwiftUI
import LocalAuthentication

/// Wraps another view with a Face ID / Touch ID prompt that fires:
///   1. on first appearance (cold start)
///   2. every time the app returns from background
///
/// Activated when `enabled` is `true` (driven by `SettingsViewModel.biometricLockEnabled`).
/// While locked the wrapped content is replaced with an opaque cover so
/// snapshotted screens (multitasking switcher) don't leak any UI.
struct BiometricGate<Content: View>: View {
    let enabled: Bool
    @ViewBuilder var content: () -> Content

    @State private var unlocked = false
    @State private var lastFailure: String?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            content()
                .allowsHitTesting(unlocked || !enabled)
                .opacity(unlocked || !enabled ? 1 : 0)

            if enabled && !unlocked {
                LockOverlay(failure: lastFailure, onRetry: authenticate)
            }
        }
        .task { authenticateIfNeeded() }
        .onChange(of: scenePhase) { _, newPhase in
            switch newPhase {
            case .background, .inactive:
                if enabled { unlocked = false }
            case .active:
                authenticateIfNeeded()
            @unknown default:
                break
            }
        }
        .onChange(of: enabled) { _, isOn in
            // Toggling biometrics off in Settings should immediately unlock.
            if !isOn { unlocked = true }
        }
    }

    private func authenticateIfNeeded() {
        guard enabled, !unlocked else { return }
        authenticate()
    }

    private func authenticate() {
        let ctx = LAContext()
        ctx.localizedFallbackTitle = "Use Passcode"
        var error: NSError?
        // Allow device passcode as fallback so a user without enrolled
        // biometrics (or after too many failures) can still get into the app.
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            // No biometrics + no passcode set → fail open so user can't be
            // permanently locked out of their VPN client.
            unlocked = true
            return
        }
        ctx.evaluatePolicy(
            .deviceOwnerAuthentication,
            localizedReason: "Unlock Birdo VPN"
        ) { success, evalError in
            DispatchQueue.main.async {
                if success {
                    unlocked = true
                    lastFailure = nil
                } else {
                    lastFailure = (evalError as NSError?)?.localizedDescription
                        ?? "Authentication failed"
                }
            }
        }
    }
}

private struct LockOverlay: View {
    let failure: String?
    let onRetry: () -> Void

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 24) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 64, weight: .light))
                    .foregroundStyle(.white)
                Text("Birdo VPN")
                    .font(.title2.bold())
                    .foregroundStyle(.white)
                if let failure {
                    Text(failure)
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.7))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 40)
                }
                Button(action: onRetry) {
                    Label("Unlock", systemImage: "faceid")
                        .font(.headline)
                        .padding(.horizontal, 24)
                        .padding(.vertical, 12)
                        .background(Color.white.opacity(0.15))
                        .clipShape(Capsule())
                        .foregroundStyle(.white)
                }
            }
        }
    }
}
