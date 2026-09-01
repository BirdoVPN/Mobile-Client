import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// The auth screen — Email | Anonymous | SSO tabs, the shared 2FA step and the
/// minted-anonymous-ID acknowledgment. Rebuilt to the Android reference flow
/// (spec-auth-flow.md §1–5: structure, exact copy, entrance stagger) with the
/// desktop emerald visual language (spec-pixelcanvas-design.md §6).
///
/// Presentation: this is a SHEET raised at the point of use
/// (`authVM.requestSignIn(reason)`), never the app's launch route — see
/// ContentView / GuestAccess.swift for the 5.1.1(v) reasoning. `isSheet`
/// adds the header explaining WHY sign-in was asked for and the "Continue
/// without an account" escape; it is false only for previews.
///
/// While `authVM.createdAnonymousId != nil` the user is between "account
/// created" and "acknowledged" — `isLoggedIn` stays false, so this view
/// presents the save-your-ID step until `acknowledgeAnonymousId()` proceeds.
struct LoginView: View {
    @EnvironmentObject var authVM: AuthViewModel

    /// Explicit init: the private @State properties below would otherwise
    /// make the synthesized memberwise init private, so `LoginView(isSheet:)`
    /// would not compile from ContentView.
    private let isSheet: Bool

    init(isSheet: Bool = false) {
        self.isSheet = isSheet
    }

    @StateObject private var pixelModel = PixelGridModel()

    @State private var email = ""
    @State private var password = ""
    @State private var anonymousId = ""
    @State private var anonymousPassword = ""

    /// One-shot entrance-stagger flag (spec §1: 520ms decel fade + slide-up).
    @State private var entered = false
    /// Copy feedback on the minted-ID block (checkmark for ~2s).
    @State private var copiedId = false
    @State private var copyResetTask: Task<Void, Never>?

    @FocusState private var focusedField: Field?

    private enum Field: Hashable {
        case email, password, anonymousId, anonymousPassword, twoFactorCode
    }

    /// Characters a 2FA code may contain: TOTP digits plus hex backup codes
    /// with optional dashes (spec §5 item 3).
    private static let twoFactorAllowed = Set("0123456789abcdefABCDEF-")

    var body: some View {
        GeometryReader { geo in
            ZStack {
                // Opaque black base + own canvas — Login is a root route and
                // must fully occlude whatever sits behind it.
                BirdoTheme.black.ignoresSafeArea()
                PixelCanvasView(model: pixelModel)

                ScrollView {
                    VStack(spacing: 0) {
                        if isSheet { sheetChrome }
                        header
                        content
                    }
                    .frame(maxWidth: 480) // iPad parity (Android AdaptiveContainer)
                    .padding(.horizontal, 32)
                    .padding(.vertical, 24)
                    // Vertically centered when content fits; scrolls, never
                    // clips, on small screens (spec §1).
                    .frame(maxWidth: .infinity, minHeight: geo.size.height)
                }
                .scrollDismissesKeyboard(.interactively)
            }
            .pixelCanvasTouchTrail(pixelModel)
        }
        .onAppear {
            if !entered { entered = true }
        }
        .onDisappear { copyResetTask?.cancel() }
    }

    // MARK: - Sheet chrome (why we asked + the way out)

    /// The escape hatch that keeps this a prompt rather than a wall: the user
    /// can always go back to the app with no account. Hidden during the
    /// save-your-ID step, where leaving would lose the only credential.
    @ViewBuilder
    private var sheetChrome: some View {
        // Visible during provisioning too. Hiding it was justified by the
        // claim that dismissing mid-mint loses the only credential -- and that
        // claim is false in this repo's own terms: completeAuthentication
        // persists the id (`keychain.saveAnonymousId`) and ProfileView renders
        // it afterwards. What hiding it actually bought was an uncancellable
        // modal for as long as two 30-second requests can take, on the first
        // tap of the app's primary button, which on macOS reads as a hang.
        if !isCreatedStep {
            VStack(spacing: 10) {
                HStack {
                    Spacer(minLength: 0)
                    Button {
                        authVM.dismissSignIn()
                    } label: {
                        Text("Not now")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(BirdoTheme.white60)
                            .frame(minWidth: BirdoTheme.Spacing.minTouch,
                                   minHeight: BirdoTheme.Spacing.minTouch)
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .accessibilityIdentifier("signin_dismiss")
                    .accessibilityLabel("Continue without an account")
                }

                // WHY this particular action needed an account, in the app's
                // own words — and the standing promise that the rest of it
                // does not (SignInReason, GuestAccess.swift).
                Text(authVM.signInReason.message)
                    .font(BirdoTheme.Fonts.bodySmall)
                    .foregroundStyle(BirdoTheme.white40)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("signin_reason")
            }
            .padding(.bottom, 4)
        }
    }

    // MARK: - Header (badge + gradient title + subtitle)

    private var isCreatedStep: Bool { authVM.createdAnonymousId != nil }

    /// Shown while a Connect tap mints an anonymous account. No fields, no
    /// tabs, nothing to fill in -- the point is that the user supplies nothing.
    private var provisioningStep: some View {
        VStack(spacing: BirdoTheme.Spacing.md) {
            // Every sibling branch renders this; without it a failure that
            // clears the spinner but not the step is invisible.
            errorBanner
            ProgressView()
                .controlSize(.large)
            Text("Setting up your anonymous account")
                .font(BirdoTheme.Fonts.bodyMedium)
                .foregroundStyle(BirdoTheme.onSurface)
            Text("No email, no password, nothing to fill in. You'll get a 24-digit number to save.")
                .font(BirdoTheme.Fonts.bodySmall)
                .foregroundStyle(BirdoTheme.onSurfaceMuted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, BirdoTheme.Spacing.xl)
        .accessibilityIdentifier("login_provisioning_step")
    }

    private var header: some View {
        VStack(spacing: 0) {
            statusBadge
                .loginEntrance(entered, delay: 0.08)

            Text(headlineText)
                .font(BirdoTheme.Fonts.displayMedium)
                .foregroundStyle(BirdoTheme.Gradients.headlineText)
                .multilineTextAlignment(.center)
                .padding(.top, 28)
                .loginEntrance(entered, delay: 0.15)

            Text(subtitleText)
                .font(BirdoTheme.Fonts.bodyMedium)
                .foregroundStyle(BirdoTheme.white40)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
                .loginEntrance(entered, delay: 0.20)
        }
    }

    private var headlineText: String {
        if isCreatedStep { return "Account created" }
        // NOT signInReason.title here: that is literally "Sign in to connect",
        // the sentence guideline 5.1.1(v) was rejected over. Gating only the
        // form would have left the rejected copy sitting above the spinner.
        if authVM.isAutoProvisioning { return "Setting you up" }
        // On the sheet the headline names the action that asked for sign-in
        // ("Sign in to connect"), so the prompt reads as an answer to what the
        // user just tapped rather than a generic wall.
        if isSheet && !authVM.requiresTwoFactor { return authVM.signInReason.title }
        return "Welcome Back"
    }

    private var subtitleText: String {
        if isCreatedStep { return "Save your recovery ID" }
        if authVM.isAutoProvisioning { return "No account needed" }
        if authVM.requiresTwoFactor { return "Enter your authenticator code" }
        return "Sign in to access the sovereign network"
    }

    private var statusBadge: some View {
        HStack(spacing: 10) {
            BirdoLogo.badge
            Text("Secure Connection")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(BirdoTheme.white80)
        }
        .padding(.leading, 8)
        .padding(.trailing, 16)
        .padding(.vertical, 6)
        .background(Capsule().fill(BirdoTheme.white05))
    }

    // MARK: - Step routing (tabbed form / 2FA / minted ID)

    private var stepTransition: AnyTransition {
        .opacity.combined(with: .scale(scale: 0.98))
    }

    @ViewBuilder
    private var content: some View {
        Group {
            if let id = authVM.createdAnonymousId {
                createdIdStep(id)
                    .transition(stepTransition)
            } else if authVM.isAutoProvisioning {
                // Connect was tapped with no account. An anonymous one is
                // being minted with no input from the user, so the tabbed
                // sign-in form must not appear -- showing it is precisely what
                // guideline 5.1.1(v) rejected. On failure the flag clears and
                // the form returns, carrying the error.
                provisioningStep
                    .transition(stepTransition)
            } else if authVM.requiresTwoFactor {
                // 2FA replaces the whole tabbed form; tabs stay hidden and
                // the header subtitle carries the instruction (spec §5).
                VStack(spacing: 0) {
                    errorBanner
                    twoFactorStep
                }
                .transition(stepTransition)
            } else {
                VStack(spacing: 0) {
                    errorBanner

                    SegmentedTabs(items: AuthTab.allCases.map(\.title),
                                  selection: tabSelection)
                        .loginEntrance(entered, delay: 0.235)

                    tabWell
                        .padding(.top, 20)

                    // Hidden on Anonymous — those accounts are created
                    // in-app; the web signup prompt would mislead (spec §1).
                    if authVM.selectedTab != .anonymous {
                        signUpRow
                            .padding(.top, 20)
                            .transition(.opacity)
                    }
                }
                .transition(stepTransition)
            }
        }
        .padding(.top, 36)
        .animation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.standard), value: authVM.requiresTwoFactor)
        .animation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.standard), value: authVM.createdAnonymousId)
        .animation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.standard), value: authVM.error)
        .animation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.standard), value: authVM.selectedTab)
    }

    @ViewBuilder
    private var errorBanner: some View {
        if let error = authVM.error {
            ErrorBanner(error)
                .accessibilityIdentifier("login_error")
                .padding(.bottom, 16)
        }
    }

    /// Bridges `authVM.selectedTab` (AuthTab) to SegmentedTabs' Int selection.
    private var tabSelection: Binding<Int> {
        Binding(
            get: { AuthTab.allCases.firstIndex(of: authVM.selectedTab) ?? 0 },
            set: { authVM.selectedTab = AuthTab.allCases[$0] }
        )
    }

    /// Fixed-min-height content well so switching tabs never jolts the layout
    /// (shorter tabs top-align inside it — spec §1 item 6).
    private var tabWell: some View {
        ZStack(alignment: .top) {
            switch authVM.selectedTab {
            case .email:
                emailTab.transition(stepTransition)
            case .anonymous:
                anonymousTab.transition(stepTransition)
            case .sso:
                ssoTab.transition(stepTransition)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 300, alignment: .top)
    }

    // MARK: - Email tab

    private var emailTab: some View {
        VStack(spacing: 0) {
            BirdoTextField("Email",
                           placeholder: "you@example.com",
                           text: $email,
                           keyboardType: .emailAddress,
                           textContentType: .emailAddress,
                           submitLabel: .next,
                           onSubmit: { focusedField = .password })
                .focused($focusedField, equals: .email)
                .accessibilityIdentifier("login_email_field")
                .loginEntrance(entered, delay: 0.25)

            BirdoTextField("Password",
                           placeholder: "••••••••",
                           text: $password,
                           isSecure: true,
                           textContentType: .password,
                           submitLabel: .done,
                           onSubmit: submitEmailLogin)
                .focused($focusedField, equals: .password)
                .padding(.top, 14)
                .accessibilityIdentifier("login_password_field")
                .loginEntrance(entered, delay: 0.28)

            PrimaryButton("Initialize Uplink",
                          loadingTitle: "Connecting…",
                          isLoading: authVM.isLoading,
                          isEnabled: isEmailFormValid,
                          action: submitEmailLogin)
                .padding(.top, 28)
                .accessibilityIdentifier("login_button")
                .loginEntrance(entered, delay: 0.30)
        }
    }

    private var isEmailFormValid: Bool {
        !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !password.isEmpty
    }

    private func submitEmailLogin() {
        guard isEmailFormValid, !authVM.isLoading else { return }
        authVM.login(email: email, password: password)
    }

    // MARK: - Anonymous tab (one-tap create FIRST, then existing-ID sign-in)

    /// Order is deliberate (5.1.1(v)): creating an anonymous account is the
    /// easiest way in and therefore comes first, as a full-width primary
    /// button rather than the underlined afterthought it used to be. It is
    /// NOT the only way in — the sheet is dismissable, the guest shell works
    /// without it, and `POST /auth/register/anonymous` is rate limited to 3
    /// per IP per hour, so on a shared address (App Review's, for one) it can
    /// fail through no fault of the user. The failure copy and the retry
    /// affordance below exist for exactly that case.
    private var anonymousTab: some View {
        VStack(spacing: 0) {
            PrimaryButton("Create a new anonymous account",
                          loadingTitle: "Creating…",
                          variant: .brand,
                          isLoading: authVM.isLoading,
                          isEnabled: !authVM.isLoading,
                          action: {
                              authVM.error = nil
                              authVM.createAnonymousAccount()
                          })
                .accessibilityIdentifier("login_anonymous_create")
                .loginEntrance(entered, delay: 0.25)

            Text("No email, no personal details — the server mints a 24-digit ID.")
                .font(BirdoTheme.Fonts.bodySmall)
                .foregroundStyle(BirdoTheme.white40)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity)
                .padding(.top, 8)
                .loginEntrance(entered, delay: 0.27)

            // Only offered when retrying can actually work. A 429 inside the
            // rate-limit hour would just burn another attempt and fail again,
            // which is how a reviewer concludes the app is broken.
            if authVM.error != nil, authVM.anonymousCreateFailureStatus != nil,
               authVM.canRetryAnonymousCreate {
                Button {
                    authVM.createAnonymousAccount()
                } label: {
                    Text("Try again")
                        .font(.system(size: 13, weight: .medium))
                        .underline()
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(PressScaleButtonStyle())
                .disabled(authVM.isLoading)
                .padding(.top, 4)
                .accessibilityIdentifier("login_anonymous_retry")
            }

            anonymousDivider
                .padding(.top, 20)

            BirdoTextField("Account ID",
                           placeholder: "XXXX XXXX XXXX XXXX XXXX XXXX",
                           text: $anonymousId,
                           supportingText: "Enter the 24-digit ID from your anonymous account",
                           keyboardType: .numberPad,
                           monospaced: true)
                .focused($focusedField, equals: .anonymousId)
                .accessibilityIdentifier("login_anonymous_id_field")
                .onChange(of: anonymousId) { _, newValue in
                    // Digits only, hard cap 24 (spec §3a).
                    let filtered = String(newValue.filter(\.isNumber).prefix(24))
                    if filtered != newValue { anonymousId = filtered }
                }
                .padding(.top, 16)
                .loginEntrance(entered, delay: 0.30)

            BirdoTextField("Password (optional)",
                           placeholder: "••••••••",
                           text: $anonymousPassword,
                           isSecure: true,
                           submitLabel: .done,
                           onSubmit: submitAnonymousLogin)
                .focused($focusedField, equals: .anonymousPassword)
                .padding(.top, 14)
                .accessibilityIdentifier("login_anonymous_password_field")
                .loginEntrance(entered, delay: 0.32)

            PrimaryButton("Sign In",
                          loadingTitle: "Connecting…",
                          isLoading: authVM.isLoading,
                          isEnabled: anonymousId.count == 24,
                          action: submitAnonymousLogin)
                .padding(.top, 24)
                .accessibilityIdentifier("login_anonymous_submit")
                .loginEntrance(entered, delay: 0.35)
        }
    }

    /// "or sign in with an existing ID" rule between the two anonymous paths.
    private var anonymousDivider: some View {
        HStack(spacing: 12) {
            Rectangle().fill(BirdoTheme.hairlineSoft).frame(height: 1)
            Text("or use an existing ID")
                .font(.system(size: 12))
                .foregroundStyle(BirdoTheme.white40)
                .fixedSize()
            Rectangle().fill(BirdoTheme.hairlineSoft).frame(height: 1)
        }
        .accessibilityHidden(true)
        .loginEntrance(entered, delay: 0.28)
    }

    private func submitAnonymousLogin() {
        guard anonymousId.count == 24, !authVM.isLoading else { return }
        authVM.loginAnonymous(anonymousId: anonymousId,
                              password: anonymousPassword.isEmpty ? nil : anonymousPassword)
    }

    // MARK: - SSO tab

    private var ssoTab: some View {
        VStack(spacing: 0) {
            Text("Sign in with your Google or GitHub account")
                .font(BirdoTheme.Fonts.bodySmall)
                .foregroundStyle(BirdoTheme.white40)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity)
                .loginEntrance(entered, delay: 0.25)

            ssoButton("Continue with Google") {
                authVM.loginWithSSO(provider: .google)
            }
            .padding(.top, 12)
            .accessibilityIdentifier("login_sso_google")
            .loginEntrance(entered, delay: 0.28)

            ssoButton("Continue with GitHub") {
                authVM.loginWithSSO(provider: .github)
            }
            .padding(.top, 10)
            .accessibilityIdentifier("login_sso_github")
            .loginEntrance(entered, delay: 0.30)
        }
    }

    /// Plain-text provider button: 48pt, r12, 12%-white fill, 24%-white border
    /// (spec §4 — no provider logos, matching Android).
    private func ssoButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                        .fill(Color.white.opacity(0.12))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.24), lineWidth: 1)
                )
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(authVM.isLoading)
        .opacity(authVM.isLoading ? 0.5 : 1)
    }

    // MARK: - Sign-up row

    private var signUpRow: some View {
        HStack(spacing: 0) {
            Text("Don't have an account? ")
                .font(.system(size: 13))
                .foregroundStyle(BirdoTheme.white40)
            Button {
                // In-app account creation only — no external-purchase steering
                // (guideline 3.1.1). Switching to the Anonymous tab surfaces the
                // one-tap "Create a new anonymous account" path; that tab also
                // hides this row (see content: selectedTab != .anonymous).
                authVM.error = nil
                authVM.selectedTab = .anonymous
            } label: {
                Text("Sign up")
                    .font(.system(size: 13, weight: .medium))
                    .underline()
                    .foregroundStyle(.white)
                    .frame(minHeight: 44) // touch target
            }
            .buttonStyle(PressScaleButtonStyle())
            .accessibilityIdentifier("login_sign_up")
        }
        .frame(maxWidth: .infinity)
        .loginEntrance(entered, delay: 0.35)
    }

    // MARK: - 2FA step (shared by email / anonymous / SSO)

    private var twoFactorStep: some View {
        VStack(spacing: 0) {
            ZStack {
                Circle()
                    .fill(BirdoTheme.white05)
                    .frame(width: 48, height: 48)
                Image(systemName: "shield.fill")
                    .font(.system(size: 24))
                    .foregroundStyle(BirdoTheme.white60)
            }
            .accessibilityHidden(true)
            .padding(.bottom, 16)

            Text("Verification Code")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(BirdoTheme.white60)
                .padding(.bottom, 6)

            twoFactorCodeField

            Text("Enter the 6-digit code from your authenticator, or a backup code")
                .font(BirdoTheme.Fonts.bodySmall)
                .foregroundStyle(BirdoTheme.white40)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            // Failure keeps the 2FA form + challenge — the user may retry the
            // same challengeToken until it expires (spec §5).
            PrimaryButton("Verify",
                          loadingTitle: "Verifying…",
                          isLoading: authVM.isLoading,
                          isEnabled: authVM.isTwoFactorCodeComplete,
                          action: { authVM.verifyTwoFactor() })
                .padding(.top, 24)
                .accessibilityIdentifier("login_2fa_verify_button")

            Button {
                authVM.cancelTwoFactor()
            } label: {
                Text("Back to login")
                    .font(.system(size: 13))
                    .underline()
                    .foregroundStyle(BirdoTheme.white40)
                    .frame(maxWidth: .infinity, minHeight: 44) // touch target
            }
            .buttonStyle(PressScaleButtonStyle())
            .padding(.top, 16)
            .accessibilityIdentifier("login_2fa_back_button")
        }
    }

    /// Bespoke centered 24pt code field (spec §5 item 3) — deliberately NOT
    /// BirdoTextField, whose layout is label-above/left-aligned. Plain-text
    /// keyboard: hex backup codes need letters. Typing clears any error (the
    /// view model handles that on the bound property).
    private var twoFactorCodeField: some View {
        TextField("", text: $authVM.twoFactorCode,
                  prompt: Text("000000 or backup code")
                      .font(.system(size: 14))
                      .foregroundStyle(BirdoTheme.white40))
            .font(.system(size: 24, weight: .medium, design: .monospaced))
            .multilineTextAlignment(.center)
            .foregroundStyle(.white)
            .tint(BirdoTheme.accentSoft)
            .autocorrectionDisabled(true)
            .modifier(OneTimeCodeInput())
            .onSubmit {
                if authVM.isTwoFactorCodeComplete && !authVM.isLoading {
                    authVM.verifyTwoFactor()
                }
            }
            .focused($focusedField, equals: .twoFactorCode)
            .padding(.horizontal, 16)
            .frame(minHeight: 56)
            .background(
                RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                    .fill(Color.white.opacity(focusedField == .twoFactorCode ? 0.12 : 0.09))
            )
            .overlay(
                RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                    .strokeBorder(focusedField == .twoFactorCode
                                    ? BirdoTheme.accentSoft.opacity(0.6)
                                    : BirdoTheme.white10,
                                  lineWidth: 1)
            )
            .animation(.easeInOut(duration: 0.2), value: focusedField == .twoFactorCode)
            .accessibilityIdentifier("login_2fa_code_field")
            .accessibilityLabel("Verification code")
            .onChange(of: authVM.twoFactorCode) { _, newValue in
                // Digits, hex a–f, dashes; hard cap 19 chars (spec §5 item 3).
                let filtered = String(newValue.filter { Self.twoFactorAllowed.contains($0) }.prefix(19))
                if filtered != newValue { authVM.twoFactorCode = filtered }
            }
    }

    // MARK: - Minted anonymous ID (save-your-ID acknowledgment)

    /// The register response returns the 24-digit ID exactly once — the user
    /// must explicitly confirm they saved it before proceeding (it is the
    /// account's only credential). Copy mirrors the desktop Login screen.
    private func createdIdStep(_ id: String) -> some View {
        VStack(spacing: 16) {
            VStack(spacing: 8) {
                ZStack {
                    Circle()
                        .fill(BirdoTheme.white05)
                        .frame(width: 48, height: 48)
                    Image(systemName: "key.fill")
                        .font(.system(size: 22))
                        .foregroundStyle(BirdoTheme.green)
                }
                .accessibilityHidden(true)

                (Text("Your anonymous account is ready. This ID is the ")
                    + Text("only").fontWeight(.semibold).foregroundStyle(.white)
                    + Text(" way back in."))
                    .font(.system(size: 14))
                    .foregroundStyle(BirdoTheme.white60)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Button {
                copyCreatedId(id)
            } label: {
                HStack(spacing: 12) {
                    Text(id)
                        .font(.system(size: 14, design: .monospaced))
                        .tracking(1)
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: copiedId ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 16))
                        .foregroundStyle(copiedId ? BirdoTheme.green : BirdoTheme.white60)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                        .fill(BirdoTheme.white04)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                        .strokeBorder(BirdoTheme.hairlineSoft, lineWidth: 1)
                )
            }
            .buttonStyle(PressScaleButtonStyle())
            .accessibilityLabel("Copy account ID")

            HStack(alignment: .top, spacing: 6) {
                Image(systemName: "exclamationmark.shield.fill")
                    .font(.system(size: 14))
                    .foregroundStyle(BirdoTheme.yellow)
                    .accessibilityHidden(true)
                Text("Save it somewhere safe — we can't reset it if you lose it.")
                    .font(BirdoTheme.Fonts.bodySmall)
                    .foregroundStyle(BirdoTheme.white40)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            PrimaryButton("I've saved it — continue",
                          variant: .brand,
                          action: { authVM.acknowledgeAnonymousId() })
                .accessibilityIdentifier("login_anonymous_ack")
        }
    }

    private func copyCreatedId(_ id: String) {
        Clipboard.copySensitive(id)
        Haptics.notify(.success)
        AccessibilityNotification.Announcement("Account ID copied").post()
        copyResetTask?.cancel()
        withAnimation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick)) {
            copiedId = true
        }
        copyResetTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            withAnimation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick)) {
                copiedId = false
            }
        }
    }
}

// MARK: - Entrance stagger

/// One-shot login entrance: fade in over 520ms (decel curve) + slide up from
/// +20pt, with per-element stagger delays from spec-auth-flow.md §1. Honors
/// Reduce Motion (elements appear instantly).
private struct LoginEntrance: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let entered: Bool
    let delay: TimeInterval

    func body(content: Content) -> some View {
        content
            .opacity(entered ? 1 : 0)
            .offset(y: entered ? 0 : 20)
            .animation(reduceMotion ? nil
                        : BirdoTheme.Motion.decel(BirdoTheme.Motion.slow).delay(delay),
                       value: entered)
    }
}

private extension View {
    func loginEntrance(_ entered: Bool, delay: TimeInterval) -> some View {
        modifier(LoginEntrance(entered: entered, delay: delay))
    }
}
