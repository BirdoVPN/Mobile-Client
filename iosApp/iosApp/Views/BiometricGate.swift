import SwiftUI
import LocalAuthentication

/// Wraps another view with a Face ID / Touch ID prompt that fires:
///   1. on first appearance (cold start)
///   2. every time the app returns from background
///
/// Activated when `enabled` is `true` (driven by `SettingsViewModel.biometricLockEnabled`).
/// While locked the wrapped content is replaced with an opaque cover so
/// snapshotted screens (multitasking switcher) don't leak any UI.
///
/// WHAT THIS IS NOT (P1-ios-biometric-gate-is-cosmetic — behaviour accepted by
/// the owner, so do not "fix" it here; fix the honesty instead):
///
/// This is a COVER, not a lock. It hides pixels and nothing else:
///   * it unlocks no keychain item and derives no key — tokens, the WireGuard
///     private key and every stored preference are readable by the running app
///     whether or not this gate has been satisfied;
///   * the app is fully alive behind it — view models keep polling, and
///     Auto-Connect will happily bring the tunnel up while the cover is
///     showing;
///   * it fails OPEN by design (no biometrics + no passcode → `unlocked = true`
///     below), so it is not an access control even on paper.
///
/// The Settings row is therefore worded as "Hide App Contents", out of the
/// Security section and off the green icon colour. If this ever DOES gate a
/// secret, change that copy back — until then, anything that reads as a
/// security guarantee is a lie the app tells the user.
struct BiometricGate<Content: View>: View {
    let enabled: Bool
    @ViewBuilder var content: () -> Content

    @State private var unlocked = false
    @State private var lastFailure: String?
    /// Single-flight: on cold start `.task` and `.onChange(.active)` both fire,
    /// which stacked two concurrent LAContext evaluations (two Face ID prompts;
    /// a dismissed one's late callback could flip `unlocked` unexpectedly).
    @State private var authInFlight = false
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
        guard !authInFlight else { return }
        authInFlight = true
        let ctx = LAContext()
        ctx.localizedFallbackTitle = "Use Passcode"
        var error: NSError?
        // Allow device passcode as fallback so a user without enrolled
        // biometrics (or after too many failures) can still get into the app.
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            // No biometrics + no passcode set → fail open so user can't be
            // permanently locked out of their VPN client.
            authInFlight = false
            unlocked = true
            return
        }
        ctx.evaluatePolicy(
            .deviceOwnerAuthentication,
            localizedReason: "Unlock BirdoVPN"
        ) { success, evalError in
            DispatchQueue.main.async {
                authInFlight = false
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
                Text("BirdoVPN")
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
