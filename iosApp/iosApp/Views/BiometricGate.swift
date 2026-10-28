import SwiftUI
#if os(iOS)
import UIKit
#endif

/// The App Lock (MR-1094, owner decision 2026-10-28): opt-in, but real when on.
///
/// Turned on in Settings ("App Lock", `SettingsViewModel.biometricLockEnabled`),
/// the device owner must unlock before the app shows anything. That means Face
/// ID or Touch ID, with the device passcode as the fallback (`DeviceOwnerAuth`).
/// The decisions are `AppLockPolicy`'s, pinned by AppLockPolicyTests:
///
/// - **Nothing is shown until unlocked.** On iOS the lock screen is a window
///   of its own (`LockCoverWindow`) at `UIWindow.Level.alert + 1`. That puts
///   it above everything SwiftUI presents in the app's window: the sign-in
///   sheet with a new account number, alerts, confirmation dialogs and
///   full-screen covers. A ZStack sibling of the content could not reach
///   those (#522 review). It is shown and hidden with the lock state, so the
///   app-switcher snapshot shows it too. Underneath, the content stays mounted,
///   so navigation, deep links (`onOpenURL`) and the app's own work survive a
///   re-lock. It is also invisible, untouchable, disabled for a hardware
///   keyboard and hidden from VoiceOver, under the same lock screen in the
///   ZStack, which is the only cover on the Mac.
/// - **No bypass.** If nothing can be asked (LocalAuthentication refuses), the
///   app stays locked with a Retry. The one exception is a device passcode
///   removed after the lock was turned on: the lock then switches itself off
///   and says so ("App Lock is off"), because the owner who removed the
///   passcode has nothing left to unlock with. The setting cannot be turned on
///   without a passcode.
/// - **Re-locks when the app goes to the background.** `.inactive` (Control
///   Center, the app switcher, a StoreKit sheet, a Face ID prompt for the
///   account number) only covers the screen, so the app-switcher snapshot
///   shows the lock screen without asking again on return. A Face ID success
///   that lands after the app has reached the background belongs to the lock
///   period that ended, and is dropped (`AppLockPolicy.Epoch`).
/// - **The VPN keeps running underneath.** The tunnel lives in the packet
///   tunnel extension, and on-demand and Auto-Connect work while the app is
///   locked. The tokens are not tied to Face ID (owner decision), since
///   background reconnects need them.
///
/// The account number has its own app-switcher cover whether the lock is on or
/// off (`AccountNumberSnapshotCover`, MR-1727), and asks for the owner before
/// it is revealed or copied (`ProfileView`). The one-time "save your number"
/// screen asks nothing, but it sits under this cover once the app re-locks.
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
    /// Bumped each time the app reaches the background: a prompt's success
    /// counts only in the lock period it was asked in.
    @State private var epoch = AppLockPolicy.Epoch()
    #if os(iOS)
    @State private var coverWindow = LockCoverWindow()
    #endif
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

    /// What the lock screen shows; the cover window follows it.
    private struct CoverState: Equatable {
        let covered: Bool
        let locked: Bool
        let failure: String?
    }

    private var coverState: CoverState {
        CoverState(covered: covered, locked: locked, failure: lastFailure)
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
        .onChange(of: coverState, initial: true) { _, state in
            syncCoverWindow(state)
        }
        .task {
            reconcileAndUnlock()
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .background { epoch.enterBackground() }
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

    /// Shows the lock screen in its own window above every presentation while
    /// covered, and takes keyboard focus only while locked (a merely inactive
    /// app keeps its keyboard). Nothing to do on the Mac: there the ZStack
    /// cover is the lock screen.
    private func syncCoverWindow(_ state: CoverState) {
        #if os(iOS)
        if state.covered {
            coverWindow.show(AnyView(LockOverlay(failure: state.locked ? state.failure : nil,
                                                 showsUnlock: state.locked,
                                                 onRetry: authenticate)),
                             takeFocus: state.locked)
        } else {
            coverWindow.hide()
        }
        #endif
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
        let asked = epoch
        Task {
            let outcome = await DeviceOwnerAuth.authenticate(reason: "Unlock BirdoVPN")
            authInFlight = false
            // The app reached the background while the prompt was up: this
            // answer unlocks a lock period that has ended. Stay locked.
            guard epoch.stillCurrent(asked) else { return }
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

#if os(iOS)
/// The App Lock's lock screen in a window of its own (#522 review).
///
/// SwiftUI presents sheets, alerts, confirmation dialogs and full-screen
/// covers from the app's own window, at `.normal`, above anything in that
/// window's view hierarchy. A cover that is a sibling of the content is
/// therefore BELOW them: the sign-in sheet showing a new 24-digit account
/// number stayed on top, usable, after the app had re-locked, and in the
/// app-switcher snapshot. A window at `.alert + 1` is above all of them.
///
/// Two things a window level alone does not settle (#522 re-review):
/// - The KEYBOARD's windows sit above `.alert + 1`. A keyboard left up in the
///   sign-in sheet could show its QuickType / AutoFill bar over the lock, so
///   locking first resigns every first responder, which takes the keyboard
///   down.
/// - VoiceOver: `accessibilityViewIsModal` hides siblings inside this window
///   only and is not documented to reach other windows. A sheet or alert in
///   the app's window could still be swiped into, and the sign-in sheet reads
///   the 24 digits. So while the cover is up, every OTHER window of the scene
///   is `accessibilityElementsHidden`, and each one's previous value comes
///   back when it hides.
@MainActor
final class LockCoverWindow {
    /// Above `.alert`, the highest level the app's own presentations reach.
    static let level = UIWindow.Level.alert + 1

    private var window: UIWindow?
    private weak var previousKeyWindow: UIWindow?
    /// The windows hidden from VoiceOver while covered, with the value each
    /// had before, to restore.
    private var silencedWindows: [(window: UIWindow, wasHidden: Bool)] = []

    nonisolated init() {}

    func show(_ root: AnyView, takeFocus: Bool) {
        let window: UIWindow
        if let existing = self.window {
            window = existing
            (window.rootViewController as? UIHostingController<AnyView>)?.rootView = root
        } else {
            guard let scene = Self.scene() else { return }
            window = UIWindow(windowScene: scene)
            window.windowLevel = Self.level
            let host = UIHostingController(rootView: root)
            host.view.backgroundColor = .black
            // VoiceOver stays in the lock screen; nothing underneath is reachable.
            host.view.accessibilityViewIsModal = true
            window.rootViewController = host
            self.window = window
        }
        let revealed = window.isHidden
        if revealed {
            window.isHidden = false
        }
        silenceOtherWindows(besides: window)
        if revealed {
            // VoiceOver moves to the lock screen.
            UIAccessibility.post(notification: .screenChanged, argument: window.rootViewController?.view)
        }
        if takeFocus {
            // Takes the keyboard down: its windows (and their QuickType /
            // AutoFill bar) sit above this one.
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
            if !window.isKeyWindow {
                previousKeyWindow = window.windowScene?.windows.first { $0.isKeyWindow && $0 !== window }
                window.makeKey()
            }
        }
    }

    func hide() {
        guard let window, !window.isHidden else { return }
        let wasKey = window.isKeyWindow
        window.isHidden = true
        restoreOtherWindows()
        if wasKey || previousKeyWindow != nil {
            // Nothing was key at the first show (a locked cold launch): fall
            // back to the scene's app window, at .normal.
            let target = previousKeyWindow
                ?? window.windowScene?.windows.first { $0 !== window && !$0.isHidden && $0.windowLevel == .normal }
            target?.makeKey()
            previousKeyWindow = nil
        }
    }

    /// Hides every other window of the scene from VoiceOver, including ones
    /// that appeared since the cover went up; each keeps its earlier value.
    private func silenceOtherWindows(besides cover: UIWindow) {
        for other in cover.windowScene?.windows ?? [] where other !== cover {
            if !silencedWindows.contains(where: { $0.window === other }) {
                silencedWindows.append((other, other.accessibilityElementsHidden))
            }
            other.accessibilityElementsHidden = true
        }
    }

    private func restoreOtherWindows() {
        for (other, wasHidden) in silencedWindows {
            other.accessibilityElementsHidden = wasHidden
        }
        silencedWindows.removeAll()
    }

    private static func scene() -> UIWindowScene? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first { $0.activationState == .foregroundActive }
            ?? scenes.first { $0.activationState == .foregroundInactive }
            ?? scenes.first
    }
}
#endif

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
