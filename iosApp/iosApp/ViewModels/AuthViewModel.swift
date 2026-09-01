import Foundation
import SwiftUI

/// Auth-method tabs on the Login screen. Labels (exact): `Email` |
/// `Anonymous` | `SSO`. Hidden while the 2FA step is active.
/// Anonymous is FIRST and is the default: it is the only way into the app
/// that needs nothing from the user (5.1.1(v) — "the first and easiest
/// option"). It is deliberately NOT the only way in; the guest shell works
/// without tapping it at all, and `AnonymousCreateFailure` covers the case
/// where the server refuses to mint one.
enum AuthTab: String, CaseIterable, Identifiable, Sendable {
    case anonymous
    case email
    case sso

    var id: String { rawValue }

    var title: String {
        switch self {
        case .anonymous: return "Anonymous"
        case .email: return "Email"
        case .sso: return "SSO"
        }
    }
}

/// Authentication state machine: consent gate → login (Email | Anonymous |
/// SSO tabs, shared 2FA step) → home, plus session restore, hydration
/// (`GET /auth/me` + `GET /vpn/stats`), logout and GDPR erasure.
///
/// Routing contract (ContentView):
///   `!hasConsented && !consentDeferred` -> Consent
///   otherwise                           -> the tab shell, signed in OR GUEST
///
/// Sign-in is a SHEET raised at the point of use (`requestSignIn`), never a
/// route that owns the window — see GuestAccess.swift for why (5.1.1(v)).
@MainActor
final class AuthViewModel: ObservableObject {
    // MARK: - Published State

    /// Decided SYNCHRONOUSLY at cold start from stored-token JWT `exp` claims
    /// — routes straight to Home with no network wait. A background
    /// `GET /auth/me` then confirms; ONLY a definitive 401 (after one
    /// auto-refresh attempt) flips this back to false.
    @Published var isLoggedIn = false
    /// Consent is shown ONCE, on first launch, before anything else.
    @Published var hasConsented = false
    /// The user chose "Not now" on the privacy disclosure. They get the whole
    /// guest shell; consent is asked for again before an account is created or
    /// signed into — the only point at which personal data is processed.
    @Published private(set) var consentDeferred = false
    /// The sign-in sheet is up. Raised ONLY by `requestSignIn(_:)`, from an
    /// action that genuinely needs an account.
    @Published var isPresentingSignIn = false
    /// Why the sheet was raised — drives the headline/explanation on it.
    @Published private(set) var signInReason: SignInReason = .generic
    /// True while a Connect tap is minting an anonymous account with no user
    /// input. The sheet renders a progress step instead of the tabbed form, so
    /// the success path never shows a sign-in UI at all.
    @Published private(set) var isAutoProvisioning = false
    /// HTTP status of the last failed anonymous-account creation, or nil.
    /// Drives whether a "Try again" button is worth offering (a 429 inside the
    /// rate-limit hour is not — see AnonymousCreateFailure).
    @Published private(set) var anonymousCreateFailureStatus: Int?
    /// Single-flight flag for every auth submit (login / 2FA / anonymous /
    /// SSO). Double submits mint duplicate backend Session rows — guard on it.
    @Published var isLoading = false
    /// Login-screen error banner text. Views may set nil to dismiss; switching
    /// tabs or typing a 2FA code clears it automatically.
    @Published var error: String?
    /// Selected auth-method tab. Switching clears any error.
    @Published var selectedTab: AuthTab = .anonymous {
        didSet {
            if oldValue != selectedTab { error = nil }
        }
    }
    /// True while the 2FA step replaces the tabbed form (header subtitle
    /// becomes "Enter your authenticator code").
    @Published var requiresTwoFactor = false
    /// Bound to the 2FA code field. Accepts a 6-digit TOTP or a hex backup
    /// code (dashes optional); typing clears any error.
    @Published var twoFactorCode = "" {
        didSet {
            if oldValue != twoFactorCode && error != nil { error = nil }
        }
    }
    /// Set after a successful in-app anonymous registration: the server-minted
    /// 24-digit ID. The UI must display it ("save this — it's your only
    /// credential") and call `acknowledgeAnonymousId()` before the router
    /// proceeds to Home. Nil in every other state.
    @Published private(set) var createdAnonymousId: String?
    /// Identity from `GET /auth/me`. Nil until hydration succeeds — login is
    /// never blocked on the profile call, so treat nil as "unknown", not
    /// "logged out". Drives `hasPassword` (delete dialog) and the anonymous
    /// account-number card.
    @Published private(set) var user: UserProfile?
    /// Canonical subscription/plan/bandwidth truth from `GET /vpn/stats`.
    /// Plan gating (multi-hop, port-forward, custom DNS locks) reads this —
    /// never StoreKit.
    @Published private(set) var stats: VpnStats?
    /// Email shown in headers/Profile. Nil for anonymous accounts (show
    /// "Anonymous account" instead — never the synthetic email).
    @Published private(set) var userEmail: String?
    /// Deletion dialog state — separate from the login banner so errors render
    /// inside the dialog.
    @Published private(set) var isDeleting = false
    @Published var deleteError: String?

    /// App-shell reset hook, fired at the END of every local sign-out
    /// (`completeLocalLogout`). The shell wires this to reset sibling
    /// view-models (notably VpnViewModel: plan/servers/subscription caches) so a
    /// second user on the device never inherits the previous account's state
    /// (finding #490 — the VpnViewModel reset itself is owned there; this is the
    /// signal that drives it). Idempotent; may be nil before the shell wires it.
    var onLogout: (() -> Void)?

    // MARK: - Derived State

    /// No account on this device. The app is fully usable in this state —
    /// settings, VPN settings, the policies, the about card and the location
    /// list — and only account-bound actions raise the sign-in sheet.
    var isGuest: Bool { !isLoggedIn }

    /// True for anonymous accounts (profile-derived; falls back to the stored
    /// anonymous ID before hydration lands).
    var isAnonymousAccount: Bool {
        if let user { return user.isAnonymous }
        return isLoggedIn && userEmail == nil && keychain.anonymousId != nil
    }

    /// The 24-digit account number to show on the Profile identity card.
    /// Only meaningful when `isAnonymousAccount`.
    var anonymousAccountNumber: String? {
        user?.anonymousAccountNumber ?? keychain.anonymousId
    }

    /// Uppercase plan id ("RECON" | "OPERATIVE" | "SOVEREIGN"); RECON until
    /// stats load — plan gating fails closed to the free tier.
    var planId: String { stats?.plan.uppercased() ?? "RECON" }

    /// Client-side completeness gate for the Verify button (server is
    /// authoritative): 6-digit TOTP, or hex backup code in 4-char groups
    /// (dashes optional — legacy 8-hex and current 16-hex both match).
    var isTwoFactorCodeComplete: Bool {
        Self.isCompleteTwoFactorCode(twoFactorCode)
    }

    // MARK: - Private

    private let api: APIClient
    private let keychain: KeychainService

    /// 2FA challenge token — ViewModel state ONLY, never the keychain. The
    /// backend consumes it on a SUCCESSFUL verify; a wrong code leaves it
    /// valid, so the same token is retried until the challenge expires.
    private var challengeToken: String?
    /// Which flow triggered the pending 2FA step (decides keychain handling
    /// after the verify completes). Email only — the password is NEVER
    /// retained past the first login response.
    private var pendingEmail: String?
    private var pendingAnonymousId: String?
    private var pendingContext: AuthContext = .emailLogin
    /// Sliding-window local rate limit: timestamps of FAILED email logins.
    /// 5 failures within 60 s block further submits.
    private var failedEmailAttempts: [Date] = []

    private enum AuthContext {
        case emailLogin
        case anonymousLogin
        case anonymousCreate
        case sso
    }

    // MARK: - Init / Session Restore

    init(api: APIClient = .shared, keychain: KeychainService = .shared) {
        self.api = api
        self.keychain = keychain

        let storedConsent = UserDefaults.standard.bool(forKey: "gdpr_consented")
        let storedDeferral = UserDefaults.standard.bool(forKey: Self.consentDeferredKey)
        // Cold-start routing from local tokens only: logged in iff either
        // stored JWT is unexpired. Unparseable = expired (fail-safe).
        let sessionLive = Self.isTokenLive(keychain.accessToken)
            || Self.isTokenLive(keychain.refreshToken)
        if sessionLive {
            isLoggedIn = true
            userEmail = keychain.userEmail
            // A logged-in session implies prior consent — grandfather it.
            hasConsented = true
            if !storedConsent {
                UserDefaults.standard.set(true, forKey: "gdpr_consented")
            }
            Task { [weak self] in
                await self?.hydrateOnColdStart()
            }
        } else {
            // Both tokens dead: purge the leftovers so no half-expired
            // session lingers in the keychain.
            if keychain.accessToken != nil || keychain.refreshToken != nil {
                keychain.clear()
            }
            hasConsented = storedConsent
            consentDeferred = storedDeferral
        }
    }

    // MARK: - Consent

    static let consentDeferredKey = "gdpr_consent_deferred"

    func acceptConsent() {
        hasConsented = true
        consentDeferred = false
        let defaults = UserDefaults.standard
        defaults.set(true, forKey: "gdpr_consented")
        defaults.removeObject(forKey: Self.consentDeferredKey)
        // Epoch millis, matching Android's `privacyConsentTimestamp`.
        defaults.set(Date().timeIntervalSince1970 * 1000, forKey: "privacyConsentTimestamp")
    }

    /// "Not now" on the privacy disclosure.
    ///
    /// This REPLACES the old `declineConsent()`, which set the flag false and
    /// left the user stuck on the Consent screen forever ("iOS cannot
    /// self-exit") — a dead end, and part of the same launch wall 5.1.1(v)
    /// names. Deferring drops the user into the guest shell instead: no
    /// account, no personal data processed, and consent asked for again on the
    /// sign-in sheet before either happens.
    func deferConsent() {
        hasConsented = false
        consentDeferred = true
        let defaults = UserDefaults.standard
        defaults.set(false, forKey: "gdpr_consented")
        defaults.set(true, forKey: Self.consentDeferredKey)
        defaults.removeObject(forKey: "privacyConsentTimestamp")
    }

    // MARK: - Sign-in sheet (point of use, never a launch wall)

    /// Raise the sign-in sheet for an action that genuinely needs an account.
    /// No-op when already signed in — callers must not have asked.
    func requestSignIn(_ reason: SignInReason = .generic) {
        guard !isLoggedIn else { return }
        signInReason = reason
        error = nil
        anonymousCreateFailureStatus = nil
        isAutoProvisioning = false
        isPresentingSignIn = true
    }

    /// Connect with no account and no typing (App Store guideline 5.1.1(v)).
    ///
    /// macOS 1.4.23 was rejected because tapping Connect opened the sign-in
    /// sheet: "the app requires users to register before accessing non
    /// account-based features (connect to VPN)". 5.1.1 prohibits requiring a
    /// user to ENTER PERSONAL INFORMATION. An anonymous account satisfies that
    /// literally -- it is a server-minted 24-digit number, there is no email,
    /// no password and no form -- so the fix is to mint one on the tap instead
    /// of asking for credentials.
    ///
    /// The sheet is still presented, for one reason: the minted ID is the
    /// account's ONLY credential and is displayed exactly once, and that step
    /// (with its dismissal guard) already lives there. Minting silently with
    /// nothing shown would hand the user an account they can never recover
    /// after a reinstall -- trading Apple's objection for a worse one of ours.
    ///
    /// Two deliberate fall-backs to the ordinary sheet:
    ///   * no consent yet -- creating an account for someone who deferred the
    ///     GDPR consent is not ours to do, so ask first;
    ///   * creation failed -- `isAutoProvisioning` clears, the tabbed form
    ///     appears carrying `AnonymousCreateFailure`'s copy, and the user can
    ///     retry or pick another method. The wall only ever appears when the
    ///     no-input path could not be delivered.
    /// Deliberately does NOT dial the tunnel afterwards.
    ///
    /// Auto-connecting was attempted twice and was wrong twice. Both attempts
    /// hung a reactive trigger on state that does not behave as assumed: a
    /// guest has no authenticated server list at all, so firing on the login
    /// flip called connect() against a nil selection ("Select a server
    /// first"), and firing on the selection instead went SILENT whenever the
    /// post-login fetch failed or returned nothing usable -- no tunnel, no
    /// error, nothing -- while leaving the intent latched to dial later from a
    /// pull-to-refresh the user never connected to a Connect tap.
    ///
    /// Guideline 5.1.1(v) requires that connecting not demand registration. It
    /// does not require the app to connect on the user's behalf. The extra tap
    /// costs the user one gesture, after a screen that already asks them to
    /// save a recovery number; the reactive trigger cost three review rounds
    /// and was never once correct. Removing it deletes that whole class.
    ///
    /// - Parameter reason: what the user was trying to do. Drives the sheet
    ///   copy if the mint FAILS and the ordinary form has to come back;
    ///   hardcoding `.connect` told a user who tapped a location row
    ///   "Sign in to connect", which is both wrong and the rejected sentence.
    func provisionAnonymously(reason: SignInReason = .connect) {
        guard !isLoggedIn else { return }
        // An un-acknowledged ID is still on screen. Minting again would show
        // account A's number while account B's tokens land in the keychain --
        // the user saves a credential for an account they no longer hold, and
        // account A is orphaned forever.
        guard createdAnonymousId == nil else {
            isPresentingSignIn = true
            return
        }
        // Already minting. Re-present rather than returning silently: the
        // second tap of a double-tap must not look like a dead button.
        guard !isLoading else {
            isPresentingSignIn = true
            return
        }
        // Consent first: creating a server-side account for someone who chose
        // "Not now" on the privacy screen is not ours to do.
        //
        // requestSignIn is the right destination, and an earlier comment here
        // claiming otherwise ("the sheet has no way to consent") was simply
        // false -- ContentView.swift:187 renders ConsentView inside this very
        // sheet whenever hasConsented is false. The alternative built on that
        // false belief swapped the ROOT ROUTE to the consent screen, which
        // destroys the tab shell. Checked before restoring it.
        guard hasConsented else {
            requestSignIn(reason)
            return
        }
        signInReason = reason
        error = nil
        anonymousCreateFailureStatus = nil
        isAutoProvisioning = true
        isPresentingSignIn = true
        createAnonymousAccount()
    }

    /// Close the sheet and drop back into the guest shell.
    ///
    /// Deliberately does NOT run while a freshly minted anonymous ID is
    /// un-acknowledged: that ID is the account's only credential and is shown
    /// exactly once (the sheet is also `interactiveDismissDisabled` in that
    /// step). Any half-finished 2FA challenge is dropped so the next open
    /// starts clean.
    func dismissSignIn() {
        guard createdAnonymousId == nil else { return }
        if requiresTwoFactor { cancelTwoFactor() }
        error = nil
        anonymousCreateFailureStatus = nil
        isAutoProvisioning = false
        isPresentingSignIn = false
    }

    // MARK: - Email Login

    func login(email: String, password: String) {
        // Single-flight: SwiftUI applies `.disabled(isLoading)` on the NEXT
        // render pass, so two fast taps both clear the enabled check. Every
        // backend login mints a Session row (historic duplicate-sessions bug).
        guard !isLoading else { return }
        let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)

        // Client-side validation before any network traffic.
        guard Self.isValidEmail(trimmedEmail) else {
            error = "Please enter a valid email address"
            return
        }
        guard (6...256).contains(password.count) else {
            error = "Password must be 6-256 characters"
            return
        }
        // Local sliding-window rate limit — only FAILED attempts count.
        if let wait = secondsUntilLoginAllowed() {
            error = "Too many login attempts. Please wait \(wait)s."
            return
        }

        isLoading = true
        error = nil
        pendingEmail = trimmedEmail
        pendingAnonymousId = nil
        pendingContext = .emailLogin

        Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await api.login(email: trimmedEmail, password: password)
                switch result {
                case .success(let tokens):
                    if await completeAuthentication(tokens: tokens,
                                                    knownEmail: trimmedEmail,
                                                    knownAnonymousId: nil,
                                                    context: .emailLogin) {
                        isLoggedIn = true
                        refreshStatsInBackground()
                    }
                case .twoFactorRequired(let challenge):
                    // Do NOT store anything — tokens are withheld until the
                    // challenge is answered. Password is already gone.
                    challengeToken = challenge
                    requiresTwoFactor = true
                }
            } catch {
                // Count the attempt ONLY if the server actually weighed these
                // credentials and said no. This used to be a blanket
                // recordFailedEmailLogin() on every thrown error, so five taps
                // in a tunnel, on hotel wifi, during a backend deploy, or on a
                // TLS-pinning failure locked the user out of their OWN login
                // button for 60 s — punishment for a network they don't control,
                // on credentials that were never tested. Worse, the lockout
                // banner says "Too many login attempts", which reads as "I'm
                // typing my password wrong" and sends people to password reset.
                // Transport failures now leave the counter untouched; the user
                // reads the real reason ("No internet connection.") and can
                // retry the instant they have signal.
                if Self.isCredentialRejection(error) {
                    recordFailedEmailLogin()
                }
                self.error = mapAuthError(error, fallback: "Login failed")
            }
            self.isLoading = false
        }
    }

    // MARK: - Two-Factor (shared by Email, Anonymous and SSO paths)

    func verifyTwoFactor() {
        guard !isLoading else { return }
        guard let challengeToken else {
            // No pending challenge (process recreated mid-step) — the only
            // recovery is a fresh login.
            cancelTwoFactor()
            return
        }
        guard isTwoFactorCodeComplete else {
            error = "Enter a 6-digit code or a backup code"
            return
        }
        let code = twoFactorCode.trimmingCharacters(in: .whitespaces)
        isLoading = true
        error = nil

        Task { [weak self] in
            guard let self else { return }
            do {
                let tokens = try await api.verifyTwoFactor(challengeToken: challengeToken, code: code)
                if await completeAuthentication(tokens: tokens,
                                                knownEmail: pendingEmail,
                                                knownAnonymousId: pendingAnonymousId,
                                                context: pendingContext) {
                    isLoggedIn = true
                    refreshStatsInBackground()
                }
            } catch {
                // Stay on the 2FA form on any failure. The challenge is
                // consumed server-side only on success, so the SAME token is
                // retried until it expires — then the user goes "Back to
                // login". No client-side retry counter.
                //
                // Only a definitive 401 means the CODE was wrong. Offline, 5xx
                // and 429 must NOT read as "invalid code" — that sends a user
                // on bad signal re-typing a correct code into the server's
                // 10/min limit, and it would mask a pinning failure (the one
                // error they genuinely need to see).
                if Self.isCredentialRejection(error) {
                    self.error = "Invalid verification code. Please try again."
                } else {
                    self.error = mapAuthError(
                        error, fallback: "Invalid verification code. Please try again.")
                }
            }
            self.isLoading = false
        }
    }

    /// "Back to login" — returns to the tabbed form, dropping the challenge.
    func cancelTwoFactor() {
        requiresTwoFactor = false
        challengeToken = nil
        twoFactorCode = ""
        pendingEmail = nil
        pendingAnonymousId = nil
        error = nil
    }

    // MARK: - Anonymous (login to EXISTING vs CREATE new — distinct paths)

    /// Sign in with an existing 24-digit anonymous account ID. `password` is
    /// optional — sent only when non-blank (accounts without one must omit
    /// it; the backend deliberately rejects a supplied password there).
    func loginAnonymous(anonymousId: String, password: String?) {
        guard !isLoading else { return }
        let digits = anonymousId.filter { $0.isASCII && $0.isNumber }
        guard digits.count == 24 else {
            error = "Anonymous ID must be 24 digits"
            return
        }
        let trimmed = password?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let sendPassword: String? = trimmed.isEmpty ? nil : password

        isLoading = true
        error = nil
        pendingEmail = nil
        pendingAnonymousId = digits
        pendingContext = .anonymousLogin

        Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await api.loginAnonymous(anonymousId: digits, password: sendPassword)
                switch result {
                case .success(let tokens):
                    if await completeAuthentication(tokens: tokens,
                                                    knownEmail: nil,
                                                    knownAnonymousId: digits,
                                                    context: .anonymousLogin) {
                        isLoggedIn = true
                        refreshStatsInBackground()
                    }
                case .twoFactorRequired(let challenge):
                    // Yes — anonymous accounts can have 2FA (set on the web).
                    challengeToken = challenge
                    requiresTwoFactor = true
                }
            } catch {
                self.error = mapAuthError(error, fallback: "Anonymous login failed")
            }
            self.isLoading = false
        }
    }

    /// One-tap in-app registration — no confirmation dialog, no form. On
    /// success `createdAnonymousId` is set and the router HOLDS before Home
    /// until `acknowledgeAnonymousId()` — the minted ID is the account's only
    /// credential and is shown exactly once here (it also lives on the
    /// Profile tab afterwards, derived from the synthetic /auth/me email).
    func createAnonymousAccount() {
        guard !isLoading else { return }
        isLoading = true
        error = nil
        anonymousCreateFailureStatus = nil
        pendingEmail = nil
        pendingContext = .anonymousCreate

        Task { [weak self] in
            guard let self else { return }
            do {
                let registration = try await api.registerAnonymous()
                if await completeAuthentication(tokens: registration.tokens,
                                                knownEmail: nil,
                                                knownAnonymousId: registration.anonymousId,
                                                context: .anonymousCreate) {
                    let minted = registration.anonymousId
                        ?? user?.anonymousAccountNumber
                        ?? keychain.anonymousId
                    if let minted {
                        createdAnonymousId = minted
                        isAutoProvisioning = false
                    } else {
                        // Server didn't surface the ID anywhere (should never
                        // happen) — nothing to acknowledge; proceed.
                        isAutoProvisioning = false
                        isLoggedIn = true
                        refreshStatsInBackground()
                    }
                } else {
                    // completeAuthentication returned FALSE without throwing --
                    // a keychain write failure. The account exists server-side,
                    // its ID was never persisted, and without clearing the flag
                    // the sheet stays on a spinner that cannot render the error
                    // completeAuthentication set. One of three mints per IP per
                    // hour has already been spent, so say so rather than
                    // spinning.
                    isAutoProvisioning = false
                }
            } catch {
                // NOT mapAuthError: its 429 branch says "Too many attempts.
                // Please wait a moment.", which is wrong twice over here — the
                // limit is 3 NEW ACCOUNTS per IP per hour (nothing to do with
                // this user's attempts), and the wait is an hour, not a moment.
                // On a shared App Review address that copy reads as a broken
                // app. AnonymousCreateFailure says whose limit it is, how long
                // it lasts, and that the app still works without an account.
                let status = Self.httpStatus(of: error)
                // Hand the user back the ordinary sheet: the no-input path
                // could not be delivered, and AnonymousCreateFailure's copy
                // lives on the tabbed form.
                self.isAutoProvisioning = false
                self.anonymousCreateFailureStatus = status
                self.error = AnonymousCreateFailure.message(
                    status: status,
                    serverText: Self.serverText(of: error),
                    isOffline: Self.isOffline(error)
                )
            }
            self.isLoading = false
        }
    }

    /// True when the last anonymous-create failure is worth a "Try again"
    /// button. A 429 inside the rate-limit hour is not: retrying burns another
    /// attempt and fails again.
    var canRetryAnonymousCreate: Bool {
        AnonymousCreateFailure.isImmediatelyRetryable(status: anonymousCreateFailureStatus)
    }

    /// The user confirmed they saved the freshly-minted anonymous ID —
    /// proceed to Home.
    func acknowledgeAnonymousId() {
        guard createdAnonymousId != nil else { return }
        createdAnonymousId = nil
        isAutoProvisioning = false
        isLoggedIn = true
        refreshStatsInBackground()
    }

    // MARK: - SSO

    /// Full PKCE broker flow via ASWebAuthenticationSession (SSOService).
    /// A cancelled sheet or broker `auth_failed` redirect surfaces nothing,
    /// matching Android.
    func loginWithSSO(provider: SSOProvider) {
        guard !isLoading else { return }
        isLoading = true
        error = nil
        pendingEmail = nil
        pendingAnonymousId = nil
        pendingContext = .sso

        Task { [weak self] in
            guard let self else { return }
            do {
                let outcome = try await SSOService.shared.signIn(provider: provider.rawValue)
                switch outcome {
                case .cancelled:
                    break
                case .completed(.success(let tokens)):
                    if await completeAuthentication(tokens: tokens,
                                                    knownEmail: nil,
                                                    knownAnonymousId: nil,
                                                    context: .sso) {
                        isLoggedIn = true
                        refreshStatsInBackground()
                    }
                case .completed(.twoFactorRequired(let challenge)):
                    // SSO does NOT bypass a user's 2FA.
                    challengeToken = challenge
                    requiresTwoFactor = true
                }
            } catch {
                self.error = mapAuthError(error, fallback: "Sign-in failed")
            }
            self.isLoading = false
        }
    }

    // MARK: - Hydration

    /// Re-fetch `GET /auth/me` (Profile tab refetches on focus). Tolerant:
    /// only a definitive 401 changes auth state.
    func refreshProfile() async {
        guard isLoggedIn else { return }
        do {
            applyProfile(try await api.fetchProfile())
        } catch {
            logoutIfUnauthorized(error)
        }
    }

    /// Re-fetch `GET /vpn/stats` (plan gating, Limit tab "Refresh usage",
    /// Subscription hero card). Always hits the network — no client cache.
    func refreshStats() async {
        guard isLoggedIn || createdAnonymousId != nil else { return }
        do {
            stats = try await api.fetchVpnStats()
        } catch {
            // Keep stale stats on transient failures — gating degrades
            // gracefully rather than flapping.
            logoutIfUnauthorized(error)
        }
    }

    // MARK: - Logout / Session Expiry

    /// Sign out. ORDER IS LOAD-BEARING for callers: run
    /// `VpnViewModel.disconnect()` BEFORE this — logout wipes the shared
    /// keychain the tunnel extension reads. Server-side revocation is
    /// best-effort and never blocks the local wipe.
    func logout() {
        let token = keychain.accessToken
        if token != nil {
            let api = self.api
            Task.detached {
                await api.logout(bearerToken: token)
            }
        }
        completeLocalLogout()
    }

    /// S3 hook: any ViewModel that catches an error from an authenticated
    /// call should pass it here. A definitive `APIError.unauthorized` (the
    /// refresh already failed inside APIClient) forces a local sign-out so
    /// the user is never trapped "logged in" with every call failing.
    /// Returns true when the error was consumed as a session expiry.
    @discardableResult
    func logoutIfUnauthorized(_ error: Error) -> Bool {
        guard case APIError.unauthorized = error else { return false }
        if isLoggedIn || createdAnonymousId != nil {
            completeLocalLogout()
        }
        return true
    }

    private func completeLocalLogout() {
        // Keychain keeps `device_id` (stable device identity) and
        // `anonymous_id` (sole recovery credential) — everything else goes.
        keychain.clear()
        // Fence the API session (finding #1): bump the generation AFTER the
        // keychain wipe so any refresh still in flight drops its rotated pair
        // instead of resurrecting the signed-out session. Ordering matters —
        // clear() nils the refresh token (the CAS check) and this bump is the
        // belt-and-braces generation guard for writes that already read it.
        let api = self.api
        Task { await api.invalidateSession() }
        // AUDIT-C1: drop the persisted ML-KEM-1024 client identity so the
        // next user gets a fresh PQ keypair instead of inheriting this one.
        BirdoPQManager.shared.resetPersistedKeypair()
        isLoggedIn = false
        user = nil
        stats = nil
        userEmail = nil
        requiresTwoFactor = false
        challengeToken = nil
        twoFactorCode = ""
        createdAnonymousId = nil
        pendingEmail = nil
        pendingAnonymousId = nil
        pendingContext = .emailLogin
        error = nil
        deleteError = nil
        isLoading = false
        isDeleting = false
        selectedTab = .email
        failedEmailAttempts.removeAll()
        // Signal the shell to reset sibling state (VpnViewModel plan/servers/
        // subscription caches) so the next user can't inherit this account's
        // state (finding #490). Fired last, after our own state is clean.
        onLogout?()
    }

    // MARK: - Account Deletion (GDPR Art. 17)

    /// Erase the account. Callers must disconnect the VPN first (same
    /// ordering rule as `logout()`). No client-side password validation —
    /// password-less accounts (SSO, anonymous without password) legitimately
    /// send nothing, and the backend is authoritative for the rest (the
    /// Android 6-256 pre-check stranded password-less deletes; do not
    /// replicate). Results surface via `deleteError` / `isDeleting` so they
    /// render inside the dialog.
    func deleteAccount(password: String?) {
        guard !isDeleting else { return }
        isDeleting = true
        deleteError = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                try await api.deleteAccount(password: password)
                // The account is gone — so is its recovery credential.
                keychain.clearAnonymousId()
                completeLocalLogout()
            } catch {
                self.deleteError = Self.mapDeleteError(error)
            }
            self.isDeleting = false
        }
    }

    // MARK: - Shared Completion

    /// Store tokens (durable BEFORE the profile fetch), hydrate identity and
    /// reset the 2FA step. Returns false — with the error already surfaced —
    /// when the keychain write failed; NEVER report success then.
    private func completeAuthentication(
        tokens: TokenPairData,
        knownEmail: String?,
        knownAnonymousId: String?,
        context: AuthContext
    ) async -> Bool {
        guard keychain.save(accessToken: tokens.accessToken,
                            refreshToken: tokens.refreshToken,
                            email: knownEmail) else {
            error = "Could not securely store your session. Please try again."
            return false
        }
        switch context {
        case .anonymousLogin, .anonymousCreate:
            if let knownAnonymousId {
                keychain.saveAnonymousId(knownAnonymousId)
            }
        case .emailLogin, .sso:
            // A previous anonymous account's ID must not survive into a
            // different identity on this device.
            keychain.clearAnonymousId()
        }
        // Hydration — login NEVER blocks on the profile call: a failure still
        // lands on Home with `user == nil`.
        if let profile = try? await api.fetchProfile() {
            applyProfile(profile)
        } else {
            user = nil
            userEmail = knownEmail
        }
        requiresTwoFactor = false
        challengeToken = nil
        twoFactorCode = ""
        pendingEmail = nil
        pendingAnonymousId = nil
        error = nil
        failedEmailAttempts.removeAll()
        return true
    }

    private func applyProfile(_ profile: UserProfile) {
        user = profile
        // Never surface the synthetic anon_<id>@anonymous.local email.
        userEmail = profile.isAnonymous ? nil : profile.email
        if let number = profile.anonymousAccountNumber {
            keychain.saveAnonymousId(number)
        }
    }

    private func hydrateOnColdStart() async {
        do {
            applyProfile(try await api.fetchProfile())
        } catch {
            // ONLY a definitive 401 (after APIClient's auto-refresh attempt)
            // ends the session; network errors / 5xx / timeouts keep the user
            // logged in.
            if logoutIfUnauthorized(error) { return }
        }
        await refreshStats()
    }

    private func refreshStatsInBackground() {
        Task { [weak self] in
            await self?.refreshStats()
        }
    }

    // MARK: - Local Rate Limit (email tab)

    private func secondsUntilLoginAllowed() -> Int? {
        let now = Date()
        failedEmailAttempts.removeAll { now.timeIntervalSince($0) >= 60 }
        guard failedEmailAttempts.count >= 5,
              let oldest = failedEmailAttempts.first else { return nil }
        return max(1, Int((60 - now.timeIntervalSince(oldest)).rounded(.up)))
    }

    private func recordFailedEmailLogin() {
        failedEmailAttempts.append(Date())
    }

    /// Did the SERVER receive these credentials, evaluate them, and refuse?
    ///
    /// Only that may feed the local lockout counter. The counter's whole purpose
    /// is to stop someone guessing a password at the login form; an attempt that
    /// never reached the backend guessed nothing, so counting it just penalises
    /// a user with bad signal.
    ///
    /// TRUE only for a definitive 401. Everything else is deliberately excluded:
    ///   * URLError (offline, DNS, timeout, pin-cancel) — never reached the server.
    ///   * `.invalidURL` / `.invalidResponse` — our own request/parse gave out.
    ///   * `.httpError(5xx)` and a 5xx `.serverMessage` — the backend fell over
    ///     mid-deploy; the password may well be correct.
    ///   * 429 — the SERVER's own brute-force limiter already refused, so it
    ///     never checked the password either, and it is the authoritative limit
    ///     regardless. Double-counting it here would stack a second, invisible
    ///     60 s lockout on top of the one the user is already serving.
    ///   * `.quantumKeyUnavailable` — connect-path only, never login.
    ///
    /// Note the check is on the HTTP STATUS, not on the message text. The backend
    /// answers a wrong password as 401 *with* a JSON body, so it arrives as
    /// `.serverMessage`, not `.httpError(401)` — classifying on `.httpError` alone
    /// would silently stop counting real guesses and leave the limiter dead. The
    /// message string is backend copy and must never be load-bearing.
    private static func isCredentialRejection(_ error: Error) -> Bool {
        guard let api = error as? APIError else { return false }
        switch api {
        case .unauthorized:
            return true
        case .httpError(let code):
            return code == 401
        case .serverMessage(_, let status):
            return status == 401
        case .serverRefusal(_, let status, _, _):
            return status == 401
        case .invalidURL, .invalidResponse, .quantumKeyUnavailable:
            return false
        }
    }

    // MARK: - Validation

    private static func isValidEmail(_ email: String) -> Bool {
        guard !email.isEmpty, email.count <= 254 else { return false }
        let pattern = #"^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,64}$"#
        return email.range(of: pattern, options: .regularExpression) != nil
    }

    private static func isCompleteTwoFactorCode(_ code: String) -> Bool {
        let c = code.trimmingCharacters(in: .whitespaces)
        if c.range(of: #"^\d{6}$"#, options: .regularExpression) != nil { return true }
        return c.range(of: #"^[0-9A-Fa-f]{4}(-?[0-9A-Fa-f]{4})+$"#,
                       options: .regularExpression) != nil
    }

    // MARK: - Token Expiry (cold-start routing)

    /// True iff the token parses as a JWT whose `exp` claim is in the future.
    /// Unparseable = expired — fail-safe: never route to Home on garbage.
    private static func isTokenLive(_ token: String?) -> Bool {
        guard let token, let expiry = jwtExpiry(token) else { return false }
        return expiry > Date()
    }

    private static func jwtExpiry(_ token: String) -> Date? {
        let segments = token.split(separator: ".")
        guard segments.count >= 2 else { return nil }
        var payload = String(segments[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while payload.count % 4 != 0 { payload += "=" }
        guard let data = Data(base64Encoded: payload),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let exp = object["exp"] as? TimeInterval else {
            return nil
        }
        return Date(timeIntervalSince1970: exp)
    }

    // MARK: - Error Mapping (spec §8)

    /// Map a login-path failure to user copy. Server text is sanitized first
    /// (blank / >200 chars / HTML / stack-trace fragments → fallback), then
    /// matched against the known-condition table.
    private func mapAuthError(_ error: Error, fallback: String) -> String {
        // SSOService errors already carry their exact user-facing copy.
        if let sso = error as? SSOService.SSOError {
            return sso.errorDescription ?? fallback
        }
        if let api = error as? APIError {
            switch api {
            case .serverMessage(let raw, _):
                let msg = Self.sanitizedServerMessage(raw, fallback: fallback)
                if msg == fallback { return fallback }
                // Client-minted sentinel (blank tokens in an ok response) —
                // show as-is, never with the "Login failed:" prefix.
                if msg == "Unexpected server response (no tokens)" { return msg }
                return Self.mapKnownMessage(msg)
            case .serverRefusal(let raw, let status, _, _):
                // Same shape as .serverMessage, but the server also named a
                // machine code. Status decides; the text is only a fallback.
                if status == 401 { return "Invalid email or password" }
                if status == 429 { return "Too many attempts. Please wait a moment." }
                let msg = Self.sanitizedServerMessage(raw ?? "", fallback: fallback)
                return msg == fallback ? fallback : Self.mapKnownMessage(msg)
            case .unauthorized:
                return "Invalid email or password"
            case .httpError(let code):
                if code == 401 { return "Invalid email or password" }
                if code == 429 { return "Too many attempts. Please wait a moment." }
                return "Login failed: Server error (\(code))"
            case .invalidURL, .invalidResponse, .quantumKeyUnavailable:
                // quantumKeyUnavailable is raised only on the VPN connect path,
                // never during login — handled explicitly because the switch is
                // exhaustive, rather than adding a `default` that would silently
                // swallow future cases.
                return fallback
            }
        }
        if let urlError = error as? URLError {
            switch urlError.code {
            case .notConnectedToInternet, .cannotFindHost, .dnsLookupFailed:
                return "No internet connection."
            case .cancelled, .secureConnectionFailed, .serverCertificateUntrusted,
                 .serverCertificateHasBadDate, .serverCertificateNotYetValid,
                 .serverCertificateHasUnknownRoot, .clientCertificateRejected:
                // .cancelled included: the pinning delegate cancels the
                // challenge on a pin mismatch, which surfaces as cancelled.
                return "Secure connection failed. Please update the app."
            default:
                return "Unable to reach server. Check your connection."
            }
        }
        return fallback
    }

    /// HTTP status an error arrived on, or nil when the client minted it
    /// (transport failure, unusable 2xx body). Classification for user copy is
    /// on THIS — never on the backend's message text, which changes without
    /// notice.
    static func httpStatus(of error: Error) -> Int? {
        guard let api = error as? APIError else { return nil }
        switch api {
        case .serverMessage(_, let status): return status
        case .serverRefusal(_, let status, _, _): return status
        case .httpError(let code): return code
        case .unauthorized: return 401
        case .invalidURL, .invalidResponse, .quantumKeyUnavailable: return nil
        }
    }

    /// The backend's own words, when it explained itself and the text survives
    /// sanitisation (blank / >200 chars / HTML / stack-trace fragments -> nil).
    static func serverText(of error: Error) -> String? {
        guard let api = error as? APIError, case .serverMessage(let raw, _) = api else { return nil }
        let sentinel = "__birdo_unusable_server_message__"
        let cleaned = sanitizedServerMessage(raw, fallback: sentinel)
        return cleaned == sentinel ? nil : cleaned
    }

    /// The transport says there is no connection at all.
    static func isOffline(_ error: Error) -> Bool {
        guard let urlError = error as? URLError else { return false }
        switch urlError.code {
        case .notConnectedToInternet, .cannotFindHost, .dnsLookupFailed:
            return true
        default:
            return false
        }
    }

    private static func sanitizedServerMessage(_ raw: String, fallback: String) -> String {
        let msg = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !msg.isEmpty, msg.count <= 200 else { return fallback }
        if msg.lowercased().contains("<html")
            || msg.contains("Exception")
            || msg.contains("at ")
            || msg.contains("stackTrace") {
            return fallback
        }
        return msg
    }

    private static func mapKnownMessage(_ msg: String) -> String {
        let lower = msg.lowercased()
        if msg.contains("Invalid credentials") || msg.contains("401") {
            return "Invalid email or password"
        }
        if msg.contains("429") || lower.contains("too many") {
            return "Too many attempts. Please wait a moment."
        }
        if lower.contains("certificate") || lower.contains("ssl") || lower.contains("pinning") {
            return "Secure connection failed. Please update the app."
        }
        if lower.contains("unable to resolve host") || lower.contains("unknownhost") {
            return "No internet connection."
        }
        if lower.contains("network") || lower.contains("timeout") || lower.contains("failed to connect") {
            return "Unable to reach server. Check your connection."
        }
        return "Login failed: \(msg)"
    }

    /// Deletion-dialog error mapping (spec §7).
    private static func mapDeleteError(_ error: Error) -> String {
        if let api = error as? APIError {
            switch api {
            case .serverMessage(let msg, _):
                if msg.lowercased().contains("password") { return "Incorrect password" }
                if msg.contains("429") || msg.lowercased().contains("too many") {
                    return "Too many attempts. Please wait a moment."
                }
                return "Account deletion failed. Please try again."
            case .serverRefusal(let msg, let status, _, _):
                if status == 401 { return "Incorrect password" }
                if status == 429 { return "Too many attempts. Please wait a moment." }
                if (msg ?? "").lowercased().contains("password") { return "Incorrect password" }
                return "Account deletion failed. Please try again."
            case .unauthorized:
                return "Incorrect password"
            case .httpError(let code):
                if code == 401 { return "Incorrect password" }
                if code == 429 { return "Too many attempts. Please wait a moment." }
                return "Account deletion failed. Please try again."
            case .invalidURL, .invalidResponse, .quantumKeyUnavailable:
                // quantumKeyUnavailable cannot arise from account deletion — it is
                // raised only on the VPN connect path — but the switch is
                // exhaustive, so it is handled explicitly rather than via a
                // `default` that would silently swallow future cases here too.
                return "Account deletion failed. Please try again."
            }
        }
        if error is URLError {
            return "Unable to reach server. Check your connection."
        }
        return "Account deletion failed. Please try again."
    }
}
