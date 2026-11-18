import SwiftUI

/// MR-179 (hold origin): the "Sign out?" confirmation of Home and Profile, and
/// what it says about an account deletion waiting out its 24-hour hold.
///
/// What signing out of this app does to a hold depends on where it came from
/// (birdo-web #883, `heldDeletionVoided`): the server voids a hold when the
/// session that FILED it signs out, and never one filed on the website (no
/// session) or by another device. So the line comes from
/// `HeldDeletionRecord.signOutNotice`: "cancels" only for a hold this
/// session's own 202 filed; otherwise "may not cancel ... cancel the
/// deletion". Either way "Cancel deletion" is offered right here. The texts
/// and the decisions are `SignOutHold`'s (pinned by HeldDeletionOriginTests).
///
/// With no hold known (a tap right after a cold start, before the list read
/// answered), the list is read first, for at most `SignOutHold.readWait`, and
/// Sign out waits for it, so a fast tap cannot skip the warning. A read that
/// fails says so and still lets the user sign out.
///
/// A hold filed here is cancelled by the app FIRST: the server's sign-out is
/// best effort, so offline it might never void the hold the user was told
/// signing out cancels. If that cancel fails, nobody is signed out and the
/// dialog says the deletion is still scheduled. Once it went through, the
/// sign-out goes on with "The deletion is cancelled." unless the re-read
/// names a hold (MR-2389 R1). While signing out the dialog cannot be
/// dismissed, so a cancel never lands unseen; that state is bounded: the
/// cancel at `HeldDeletionController.cancelCap` (10 s, R2), the re-read at
/// `SignOutHold.readWait` (3 s). Android #623 at e2337eb1 does the same.
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
    /// The server answered the last read (this opening's, or the caller's).
    @State private var listAnswered: Bool
    /// Signing out (with its cancel-first) is under way.
    @State private var busy = false
    /// "Cancel deletion" is under way. Set in the button's action, before any
    /// task starts, so Sign out and Cancel deletion cannot both start one.
    @State private var cancelInFlight = false
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
        let text = SignOutHold.dialogText(opening: message,
                                          signOutLabel: confirmLabel,
                                          notice: notice,
                                          checking: checking,
                                          blocked: blocked)
        BirdoConfirmDialog(
            title: title,
            message: text.message,
            confirmLabel: text.confirmLabel,
            cancelLabel: text.dismissLabel,
            isWorking: busy,
            onConfirm: { confirm() },
            onCancel: { onClose() }
        ) {
            holdActions(text, notice: notice)
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

    @ViewBuilder
    private func holdActions(_ text: SignOutHold.DialogText, notice: SignOutHoldNotice) -> some View {
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
            if text.offersSignOutAnyway {
                Button("Sign out anyway") { signOutAnyway() }
                    .birdoFont(14, weight: .semibold)
                    .foregroundStyle(BirdoTheme.red)
                    .buttonStyle(.plain)
                    .frame(minHeight: 44) // touch target
                    .disabled(busy)
                    .accessibilityIdentifier("sign_out_anyway_button")
            }
            if text.offersCancelDeletion, let id = notice.cancellableId {
                Button(cancelInFlight ? "Cancelling\u{2026}" : "Cancel deletion") {
                    cancelDeletion(id)
                }
                .birdoFont(14, weight: .semibold)
                .foregroundStyle(BirdoTheme.accent)
                .buttonStyle(.plain)
                .frame(minHeight: 44) // touch target
                .disabled(busy || cancelInFlight || authVM.isCancellingPendingDeletion)
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
        guard !checking, !busy, !cancelInFlight else { return }
        let notice = self.notice
        busy = true
        blocked = nil
        cancelError = nil
        cancelNote = nil
        Task { @MainActor in
            let step = await SignOutHold.prepareSignOut(
                notice,
                current: { authVM.heldDeletion },
                cancel: { id in await authVM.cancelHeldDeletion(id: id) },
                read: { [authVM] in await authVM.readHeldDeletionForSignOut() })
            switch step {
            case .signOut(let cancelled):
                // MR-2389 (R1): a cancel-first that went through is said here
                // while the sign-out runs, never the generic caution: nothing
                // is known to wait (`CancelResult.listKnown`).
                if let cancelled {
                    listAnswered = true
                    cancelNote = SignOutHold.cancelNote(cancelled)
                }
                await signOut()
                busy = false
                onClose()
            case .blocked(let refused):
                // Nobody is signed out: the deletion is still scheduled.
                busy = false
                blocked = refused
            case .reconsider(let answered, let cancelled):
                // The cancel went through, but what is known after it is not
                // "nothing waits": show it, and what the cancel did.
                listAnswered = answered
                cancelNote = SignOutHold.cancelNote(cancelled)
                busy = false
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

    /// "Cancel deletion": the app's cancel, then the list is read again (at
    /// most `SignOutHold.readWait`). After a 404 only a read that answered may
    /// say that nothing waits any more; after a 200 nothing is known to wait
    /// unless that read names a hold (MR-2389 R1, `CancelResult.listKnown`).
    private func cancelDeletion(_ id: String) {
        guard !busy, !cancelInFlight else { return }
        cancelInFlight = true
        cancelError = nil
        cancelNote = nil
        Task { @MainActor in
            let result = await SignOutHold.cancelThenRecheck(
                id,
                cancel: { id in await authVM.cancelHeldDeletion(id: id) },
                read: { [authVM] in await authVM.readHeldDeletionForSignOut() })
            cancelInFlight = false
            switch result.outcome {
            case .cancelled, .alreadyGone:
                // The server confirmed it: only now is anything said.
                listAnswered = result.listKnown
                cancelNote = SignOutHold.cancelNote(result.outcome)
            case .failed(let reason):
                cancelError = reason
            case .sessionEnded:
                onClose()
            }
        }
    }
}
