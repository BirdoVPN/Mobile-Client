import SwiftUI

// MR-1707 (iOS follow-up): the masked account-number card and the three-step
// "Replace number" sheet. Pure decisions live in AnonNumberRotation.swift
// (pinned by AnonNumberRotationTests); this file is the SwiftUI around them.
//
// Both numbers are credentials: nothing here logs one, the sheet adds no
// analytics, and the model drops both from memory when the sheet goes away.

// MARK: - Keychain

extension KeychainService: RotationCommitStore {
    @discardableResult
    func saveRotationSession(accessToken: String, refreshToken: String) -> Bool {
        // An anonymous account has no e-mail; the keychain's own is kept.
        save(accessToken: accessToken, refreshToken: refreshToken, email: userEmail)
    }
}

// MARK: - Masked card

/// What the Profile card shows once the server masks the number: `•••• … 1234`,
/// no reveal, no copy. The raw anonymous e-mail is never shown.
struct MaskedAccountNumberRow: View {
    let lastDigits: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("ACCOUNT NUMBER")
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(BirdoTheme.onSurfaceFaint)
            Text(AnonNumberMask.display(lastDigits: lastDigits))
                .font(BirdoTheme.Fonts.mono)
                .foregroundStyle(BirdoTheme.onBackground)
                .accountNumberSnapshotCover()
                .accessibilityLabel("Account number, hidden, ending \(lastDigits)")
            Text(AnonNumberMask.cardNote)
                .font(BirdoTheme.Fonts.bodySmall)
                .foregroundStyle(BirdoTheme.onSurfaceMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.leading, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Entry

/// The Profile entry point. Renders nothing while the client flag is OFF.
struct ReplaceNumberEntry: View {
    @EnvironmentObject var authVM: AuthViewModel
    @EnvironmentObject var vpnVM: VpnViewModel
    @State private var showSheet = false

    var body: some View {
        if AnonNumberRotation.entryVisible(isAnonymous: authVM.isAnonymousAccount) {
            Button("Replace number") { showSheet = true }
                .font(BirdoTheme.Fonts.labelLarge)
                .foregroundStyle(BirdoTheme.accent)
                .padding(.leading, 14)
                .padding(.vertical, 6)
                .sheet(isPresented: $showSheet) {
                    ReplaceNumberSheet().environmentObject(authVM).environmentObject(vpnVM)
                }
        }
    }
}

// MARK: - Model

@MainActor
final class ReplaceNumberModel: ObservableObject {
    @Published private(set) var flow = RotationFlow()
    @Published var currentNumber = ""
    @Published var password = ""
    @Published var twoFactorCode = ""
    @Published var retyped = ""
    @Published private(set) var isBusy = false

    private let api: APIClient
    private let keychain: KeychainService

    init(api: APIClient = .shared, keychain: KeychainService = .shared) {
        self.api = api
        self.keychain = keychain
    }

    private static func digits(_ raw: String) -> String {
        raw.filter { $0.isASCII && $0.isNumber }
    }

    var canPrepare: Bool { !isBusy && Self.digits(currentNumber).count == AnonNumberMask.length }
    var canConfirm: Bool { !isBusy && Self.digits(retyped).count == AnonNumberMask.length }

    func setSaved(_ value: Bool) { flow.saved = value }
    func continueToConfirm() { flow.continueToConfirm() }
    func backToShowNew() { flow.backToShowNew() }

    /// Step 1.
    func prepare() async {
        guard canPrepare else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            let answer = try await api.rotateNumberPrepare(
                currentNumber: Self.digits(currentNumber),
                password: password.isEmpty ? nil : password,
                twoFactorCode: twoFactorCode.isEmpty ? nil : twoFactorCode)
            flow.prepared(newNumber: answer.newNumber)
            // The proofs have done their job.
            currentNumber = ""
            password = ""
            twoFactorCode = ""
        } catch {
            flow.prepareFailed(Self.failure(of: error))
        }
    }

    /// Step 3. A lost response leaves the flow on step 3 with the number still
    /// in memory, so the same confirm can be sent again.
    func confirm(authVM: AuthViewModel) async {
        guard canConfirm, let expected = flow.newNumber else { return }
        let typed = Self.digits(retyped)
        // The server counts a wrong re-type toward the proof limit: check it here.
        guard typed == expected else {
            flow.confirmFailed(.refusal(.mismatch))
            return
        }
        isBusy = true
        defer { isBusy = false }
        flow.confirmSent()
        do {
            let tokens = try await api.rotateNumberConfirm(newNumber: typed)
            let stored = AnonNumberRotationCommit.commit(newNumber: typed,
                                                         accessToken: tokens.accessToken,
                                                         refreshToken: tokens.refreshToken,
                                                         to: keychain)
            flow.confirmed(storedLocally: stored)
            retyped = ""
            if stored { await authVM.refreshProfile() }
        } catch {
            flow.confirmFailed(Self.failure(of: error))
        }
    }

    /// Dismiss: both numbers and the toggle leave memory.
    func clear() {
        flow.clear()
        currentNumber = ""
        password = ""
        twoFactorCode = ""
        retyped = ""
    }

    private static func failure(of error: Error) -> RotationFailure {
        if case APIError.unauthorized = error { return .sessionEnded }
        return RotationFailure.from(error)
    }
}

// MARK: - Sheet

struct ReplaceNumberSheet: View {
    @EnvironmentObject var authVM: AuthViewModel
    @EnvironmentObject var vpnVM: VpnViewModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = ReplaceNumberModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Replace account number")
                    .font(BirdoTheme.Fonts.headlineMedium)
                    .foregroundStyle(BirdoTheme.onBackground)

                if let notice = model.flow.notice {
                    ErrorBanner(notice.message)
                }

                switch model.flow.step {
                case .prove: proveStep
                case .showNew: showNewStep
                case .confirm: confirmStep
                case .done: doneStep
                case .maybeCommitted: maybeCommittedStep
                }
            }
            .padding(20)
        }
        .background(BirdoTheme.background.ignoresSafeArea())
        .onDisappear { model.clear() }
    }

    // Step 1
    private var proveStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Your current number stays valid until you finish. Enter it to start.")
                .font(BirdoTheme.Fonts.bodyMedium)
                .foregroundStyle(BirdoTheme.onSurfaceMuted)
            BirdoTextField("Current account number",
                           placeholder: "24 digits",
                           text: $model.currentNumber,
                           keyboardType: .numberPad,
                           monospaced: true)
                .accountNumberSnapshotCover(isInput: true)
            BirdoTextField("Password (if you set one)",
                           placeholder: "Password",
                           text: $model.password,
                           isSecure: true)
            BirdoTextField("2FA code (if turned on)",
                           placeholder: "Code",
                           text: $model.twoFactorCode,
                           keyboardType: .numberPad)
            PrimaryButton("Continue",
                          loadingTitle: "Checking",
                          isLoading: model.isBusy,
                          isEnabled: model.canPrepare) {
                Task { await model.prepare() }
            }
            PrimaryButton("Cancel", variant: .ghost) { dismiss() }
        }
    }

    // Step 2
    private var showNewStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Your new account number. It is shown only now. Write it down or store it in a password manager.")
                .font(BirdoTheme.Fonts.bodyMedium)
                .foregroundStyle(BirdoTheme.onSurfaceMuted)
            Text(AccountNumberText.grouped(model.flow.newNumber ?? ""))
                .font(BirdoTheme.Fonts.mono)
                .foregroundStyle(BirdoTheme.onBackground)
                .textSelection(.enabled)
                .accountNumberSnapshotCover()
            Toggle("I've saved it", isOn: Binding(get: { model.flow.saved },
                                                  set: { model.setSaved($0) }))
                .tint(BirdoTheme.accent)
                .foregroundStyle(BirdoTheme.onBackground)
            PrimaryButton("Continue", isEnabled: model.flow.canContinueFromShowNew) {
                model.continueToConfirm()
            }
            PrimaryButton("Cancel", variant: .ghost) { dismiss() }
        }
    }

    // Step 3
    private var confirmStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Type the new number again to confirm. Your old number stops working and your other devices are signed out.")
                .font(BirdoTheme.Fonts.bodyMedium)
                .foregroundStyle(BirdoTheme.onSurfaceMuted)
            BirdoTextField("New account number",
                           placeholder: "24 digits",
                           text: $model.retyped,
                           keyboardType: .numberPad,
                           monospaced: true)
                .accountNumberSnapshotCover(isInput: true)
            PrimaryButton("Replace number",
                          loadingTitle: "Replacing",
                          isLoading: model.isBusy,
                          isEnabled: model.canConfirm) {
                Task { await model.confirm(authVM: authVM) }
            }
            PrimaryButton("Show the number again", variant: .secondary) { model.backToShowNew() }
            PrimaryButton("Cancel", variant: .ghost) { dismiss() }
        }
    }

    // A confirm was sent and the answers since say the session is gone.
    private var maybeCommittedStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let number = model.flow.newNumber {
                Text(AccountNumberText.grouped(number))
                    .font(BirdoTheme.Fonts.mono)
                    .foregroundStyle(BirdoTheme.onBackground)
                    .textSelection(.enabled)
                    .accountNumberSnapshotCover()
            }
            PrimaryButton("Close", variant: .ghost) { dismiss() }
            PrimaryButton("Sign in again") {
                Task {
                    await vpnVM.disconnectForSignOut()
                    authVM.logout()
                    model.clear()
                    dismiss()
                }
            }
        }
    }

    // Done
    private var doneStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            if model.flow.storedLocally {
                Text("Your account number was replaced. The old number no longer works.")
                    .font(BirdoTheme.Fonts.bodyMedium)
                    .foregroundStyle(BirdoTheme.onSurfaceMuted)
            } else if let number = model.flow.newNumber {
                Text(AccountNumberText.grouped(number))
                    .font(BirdoTheme.Fonts.mono)
                    .foregroundStyle(BirdoTheme.onBackground)
                    .textSelection(.enabled)
                    .accountNumberSnapshotCover()
            }
            PrimaryButton("Done") { dismiss() }
        }
    }
}
