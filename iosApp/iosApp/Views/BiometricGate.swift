import SwiftUI

/// The App Lock (MR-1094, owner decision 2026-10-28): opt-in, but real when on.
///
/// Turned on in Settings ("App Lock", `SettingsViewModel.biometricLockEnabled`),
/// the device owner must unlock before the app shows anything. That means Face
/// ID or Touch ID, with the device passcode as the fallback (`DeviceOwnerAuth`).
/// The decisions are `AppLockPolicy`'s, pinned by AppLockPolicyTests:
///
/// - **Nothing is shown until unlocked.** The content stays mounted underneath,
///   so navigation, deep links (`onOpenURL`) and the app's own work survive a
///   re-lock. While covered it is invisible, untouchable, disabled for a
///   hardware keyboard and hidden from VoiceOver, under an opaque lock screen.
/// - **No bypass.** If nothing can be asked (LocalAuthentication refuses), the
///   app stays locked with a Retry. The one exception is a device passcode
///   removed after the lock was turned on: the lock then switches itself off
///   and says so, because the owner who removed the passcode has nothing left
///   to unlock with. The setting cannot be turned on without a passcode.
/// - **Re-locks when the app goes to the background.** `.inactive` (Control
///   Center, the app switcher, a StoreKit sheet, a Face ID prompt for the
///   account number) only covers the screen, so the app-switcher snapshot
///   shows the lock screen without asking again on return.
/// - **The VPN keeps running underneath.** The tunnel lives in the packet
///   tunnel extension, and on-demand and Auto-Connect work while the app is
///   locked. The tokens are not tied to Face ID (owner decision), since
///   background reconnects need them.
///
/// The account number has its own app-switcher cover whether the lock is on or
/// off (`AccountNumberSnapshotCover`, MR-1727), and asks for the owner before
/// it is revealed or copied (`ProfileView`).
struct BiometricGate<Content: View>: View {
    /// A binding, not a value: the gate switches the setting off itself when
    /// the device passcode has been removed.
    @Binding var enabled: Bool
    @ViewBuilder var content: () -> Content

    /// Must the owner unlock before anything is shown? Set at launch from the
    /// setting; set again whenever the app reaches the background.
    @State private var locked: Bool
    @State private var lastFailure: String?
    /// Single-flight: on cold start `.task` and `.onChange(.active)` can both
    /// ask, which stacked two LocalAuthentication prompts.
    @State private var authInFlight = false
    @State private var showPasscodeRemovedNotice = false
    @Environment(\.scenePhase) private var scenePhase

    init(enabled: Binding<Bool>, @ViewBuilder content: @escaping () -> Content) {
        _enabled = enabled
        self.content = content
        _locked = State(initialValue: enabled.wrappedValue)
    }

    private static func phase(_ scenePhase: ScenePhase) -> AppLockPolicy.Phase {
        switch scenePhase {
        case .active: return .active
        case .background: return .background
        default: return .inactive
        }
    }

    private var covered: Bool {
        AppLockPolicy.covered(lockOn: enabled, locked: locked, phase: Self.phase(scenePhase))
    }

    var body: some View {
        ZStack {
            content()
                .opacity(covered ? 0 : 1)
                .allowsHitTesting(!covered)
                .disabled(covered)
                .accessibilityHidden(covered)

            if covered {
                LockOverlay(failure: locked ? lastFailure : nil,
                            showsUnlock: locked,
                            onRetry: authenticate)
            }
        }
        .task {
            reconcileAndUnlock()
        }
        .onChange(of: scenePhase) { _, newPhase in
            locked = AppLockPolicy.locked(after: Self.phase(newPhase), wasLocked: locked, lockOn: enabled)
            if newPhase == .active { reconcileAndUnlock() }
        }
        .onChange(of: enabled) { _, _ in
            // Turned on in Settings: the owner has just authenticated to do it
            // (SettingsView), so the app stays open until it next reaches the
            // background. Turned off, by the user or by the passcode check:
            // there is nothing to lock.
            locked = false
            lastFailure = nil
        }
        .alert("App Lock is off", isPresented: $showPasscodeRemovedNotice) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("This device no longer has a passcode, so BirdoVPN cannot ask for Face ID, Touch ID "
                 + "or the passcode. Set a device passcode, then turn App Lock on again in Settings.")
        }
    }

    /// At launch and on every return to the foreground: a removed passcode
    /// switches the lock off with a notice; otherwise, if locked, ask.
    private func reconcileAndUnlock() {
        guard enabled else { return }
        let result = AppLockPolicy.reconcile(lockOn: enabled, DeviceOwnerAuth.availability())
        if !result.lockOn {
            switchOffForMissingPasscode(notify: result.notify)
            return
        }
        if locked && scenePhase == .active { authenticate() }
    }

    private func switchOffForMissingPasscode(notify: Bool) {
        enabled = false
        locked = false
        lastFailure = nil
        if notify { showPasscodeRemovedNotice = true }
    }

    private func authenticate() {
        guard enabled, locked, !authInFlight else { return }
        authInFlight = true
        Task {
            let outcome = await DeviceOwnerAuth.authenticate(reason: "Unlock BirdoVPN")
            authInFlight = false
            switch outcome {
            case .success:
                locked = false
                lastFailure = nil
            case .passcodeNotSet:
                // The passcode went between the check and the prompt.
                switchOffForMissingPasscode(notify: true)
            case .failed(let message):
                // Stays locked: no bypass. The lock screen offers Retry.
                lastFailure = message
            }
        }
    }
}

private struct LockOverlay: View {
    let failure: String?
    /// False while merely covered (out of the foreground, not locked): the
    /// lock screen without the button, for the app-switcher snapshot.
    let showsUnlock: Bool
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
                if showsUnlock {
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
}
