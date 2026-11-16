import SwiftUI

/// MR-179 (hold origin): the "Sign out?" confirmation of Home and Profile, and
/// what it says about an account deletion waiting out its 24-hour hold.
///
/// What signing out of this app does to a hold depends on where it came from
/// (birdo-web #883, `heldDeletionVoided`): the server voids a hold when the
/// session that FILED it signs out, and never one filed on the website (no
/// session) or by another device. So the line comes from
/// `HeldDeletionRecord.signOutNotice`: "cancels" only for a hold this
/// session's own 202 filed; otherwise "does not cancel ... cancel the
/// deletion". Either way "Cancel deletion" is offered right here.
///
/// With no hold known (a tap right after a cold start, before the list read
/// answered), the list is read first, for at most `SignOutHold.readWait`, and
/// Sign out waits for it, so a fast tap cannot skip the warning. A read that
/// fails says so and still lets the user sign out.
///
/// A hold filed here is cancelled by the app FIRST: the server's sign-out is
/// best effort, so offline it might never void the hold the user was told
/// signing out cancels. If that cancel fails, nobody is signed out and the
/// dialog says the deletion is still scheduled.
struct SignOutConfirmation: View {
    @EnvironmentObject var authVM: AuthViewModel

    /// The canonical words (MR-1617), set by the caller: "Sign out?" and
    /// "Sign out" (IosRequiredRowsGuardTest pins them in HomeView).
    let title: String
    let confirmLabel: String
    /// The opening line (still connected, the anonymous number reminder).
    let message: String
    /// Disconnect, then sign out. The caller owns VpnViewModel.
    let signOut: @MainActor () async -> Void
    let onClose: () -> Void

    /// The list read for this opening is still out.
    @State private var checking: Bool
    /// The server answered that read (or one the caller made).
    @State private var listAnswered: Bool
    @State private var busy = false
    @State private var blocked: SignOutHold.Blocked?
    @State private var cancelError: String?
    @State private var cancelNote: String?

    /// - Parameter listAnswered: the caller already knows what waits (a hold
    ///   is known, or it read the list itself): true or false for that read's
    ///   answer. Nil: read it here first.
    init(title: String,
         message: String,
         confirmLabel: String,
         listAnswered: Bool?,
         signOut: @escaping @MainActor () async -> Void,
         onClose: @escaping () -> Void) {
        self.title = title
        self.confirmLabel = confirmLabel
        self.message = message
        self.signOut = signOut
        self.onClose = onClose
        _checking = State(initialValue: listAnswered == nil)
        _listAnswered = State(initialValue: listAnswered ?? false)
    }

    private var notice: SignOutHoldNotice {
        authVM.heldDeletion.signOutNotice(listAnswered: listAnswered)
    }

    var body: some View {
        let notice = self.notice
        BirdoConfirmDialog(
            title: title,
            message: fullMessage(notice),
            confirmLabel: confirmButtonLabel,
            cancelLabel: notice.offersCancelDeletion ? "Stay signed in" : "Cancel",
            isWorking: busy,
            onConfirm: { confirm() },
            onCancel: { onClose() }
        ) {
            holdActions(notice)
        }
        .task {
            guard checking else { return }
            let answered = await SignOutHold.check(current: { authVM.heldDeletion },
                                                   read: { [authVM] in await authVM.readHeldDeletionForSignOut() })
            listAnswered = answered
            checking = false
        }
        .onChange(of: authVM.isLoggedIn) { _, loggedIn in
            // The session ended under the dialog (a 401): nothing to confirm.
            if !loggedIn { onClose() }
        }
    }

    private var confirmButtonLabel: String {
        if checking { return "Checking\u{2026}" }
        return blocked == nil ? confirmLabel : "Try again"
    }

    private func fullMessage(_ notice: SignOutHoldNotice) -> String {
        if checking { return message }
        guard let line = notice.message() else { return message }
        return message + "\n\n" + line
    }

    @ViewBuilder
    private func holdActions(_ notice: SignOutHoldNotice) -> some View {
        VStack(spacing: 8) {
            if checking {
                HStack(spacing: 8) {
                    ProgressView()
                        .controlSize(.small)
                    Text(DeletionHold.signOutChecking)
                        .font(BirdoTheme.Fonts.bodySmall)
                        .foregroundStyle(BirdoTheme.onSurfaceMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if let blocked {
                Text(DeletionHold.signOutCancelFailed(reason: blocked.reason,
                                                      applyAfter: blocked.hold.applyAfter))
                    .font(BirdoTheme.Fonts.bodySmall)
                    .foregroundStyle(BirdoTheme.red)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Button("Sign out anyway") { signOutAnyway() }
                    .birdoFont(14, weight: .semibold)
                    .foregroundStyle(BirdoTheme.red)
                    .buttonStyle(.plain)
                    .frame(minHeight: 44) // touch target
                    .disabled(busy)
                    .accessibilityIdentifier("sign_out_anyway_button")
            } else if notice.offersCancelDeletion {
                Button(authVM.isCancellingPendingDeletion ? "Cancelling\u{2026}" : "Cancel deletion") {
                    cancelDeletion()
                }
                .birdoFont(14, weight: .semibold)
                .foregroundStyle(BirdoTheme.accent)
                .buttonStyle(.plain)
                .frame(minHeight: 44) // touch target
                .disabled(busy || authVM.isCancellingPendingDeletion)
                .accessibilityIdentifier("sign_out_cancel_deletion_button")
            }
            if let cancelError {
                Text(cancelError)
                    .font(BirdoTheme.Fonts.bodySmall)
                    .foregroundStyle(BirdoTheme.red)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let cancelNote, notice.hold == nil {
                Text(cancelNote)
                    .font(BirdoTheme.Fonts.bodySmall)
                    .foregroundStyle(BirdoTheme.onSurface)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: - Actions

    /// Sign out (or, after a failed cancel, try the cancel and sign-out again).
    private func confirm() {
        guard !checking, !busy, !authVM.isCancellingPendingDeletion else { return }
        let notice = self.notice
        busy = true
        blocked = nil
        cancelError = nil
        Task { @MainActor in
            let step = await SignOutHold.prepareSignOut(notice) { hold in
                await authVM.cancelHeldDeletion(hold)
            }
            switch step {
            case .signOut:
                await signOut()
                busy = false
                onClose()
            case .blocked(let refused):
                // Nobody is signed out: the deletion is still scheduled.
                busy = false
                blocked = refused
            case .sessionEnded:
                busy = false
                onClose()
            }
        }
    }

    /// After a failed cancel: sign out without it. The text above said the
    /// deletion may then still go ahead.
    private func signOutAnyway() {
        guard !busy else { return }
        busy = true
        Task { @MainActor in
            await signOut()
            busy = false
            onClose()
        }
    }

    /// "Cancel deletion": the app's cancel, then the list is read again.
    private func cancelDeletion() {
        guard let hold = authVM.pendingDeletion, !busy, !authVM.isCancellingPendingDeletion else { return }
        cancelError = nil
        cancelNote = nil
        Task { @MainActor in
            switch await authVM.cancelHeldDeletion(hold) {
            case .cancelled:
                // The server confirmed it: only now is anything said.
                listAnswered = true
                cancelNote = "The deletion is cancelled."
            case .alreadyGone:
                listAnswered = true
                cancelNote = "That deletion was no longer waiting."
            case .failed(let reason):
                cancelError = reason
            case .sessionEnded:
                onClose()
            }
        }
    }
}
