import Foundation
import SwiftUI

/// Auth-method tabs on the Login screen. Labels (exact): `Email` |
/// `Anonymous` | `SSO`. Hidden while the 2FA step is active.
enum AuthTab: String, CaseIterable, Identifiable, Sendable {
    case email
    case anonymous
    case sso

    var id: String { rawValue }

    var title: String {
        switch self {
        case .email: return "Email"
        case .anonymous: return "Anonymous"
        case .sso: return "SSO"
        }
    }
}

/// Authentication state machine: consent gate → login (Email | Anonymous |
/// SSO tabs, shared 2FA step) → home, plus session restore, hydration
/// (`GET /auth/me` + `GET /vpn/stats`), logout and GDPR erasure.
///
/// Routing contract (ContentView):
///   `!hasConsented`            -> Consent
///   `hasConsented && loggedIn` -> Home (tabbed app)
///   else                       -> Login
@MainActor
final class AuthViewModel: ObservableObject {
    // MARK: - Published State

    /// Decided SYNCHRONOUSLY at cold start from stored-token JWT `exp` claims
    /// — routes straight to Home with no network wait. A background
    /// `GET /auth/me` then confirms; ONLY a definitive 401 (after one
    /// auto-refresh attempt) flips this back to false.
    @Published var isLoggedIn = false
    /// Consent is shown ONCE, before the user ever sees Login.
    @Published var hasConsented = false
    /// Single-flight flag for every auth submit (login / 2FA / anonymous /
    /// SSO). Double submits mint duplicate backend Session rows — guard on it.
    @Published var isLoading = false
    /// Login-screen error banner text. Views may set nil to dismiss; switching
    /// tabs or typing a 2FA code clears it automatically.
    @Published var error: String?
    /// Selected auth-method tab. Switching clears any error.
    @Published var selectedTab: AuthTab = .email {
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

    // MARK: - Derived State

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
        }
    }

    // MARK: - Consent

    func acceptConsent() {
        hasConsented = true
        let defaults = UserDefaults.standard
        defaults.set(true, forKey: "gdpr_consented")
        // Epoch millis, matching Android's `privacyConsentTimestamp`.
        defaults.set(Date().timeIntervalSince1970 * 1000, forKey: "privacyConsentTimestamp")
    }

    /// iOS cannot exit the app (Android calls `finishAffinity()`), so
    /// declining simply keeps the user on the Consent screen.
    func declineConsent() {
        hasConsented = false
        let defaults = UserDefaults.standard
        defaults.set(false, forKey: "gdpr_consented")
        defaults.removeObject(forKey: "privacyConsentTimestamp")
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
                recordFailedEmailLogin()
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
                // ANY failure (wrong code, expired/consumed challenge, 429):
                // stay on the 2FA form. The challenge is consumed server-side
                // only on success, so the SAME token is retried until it
                // expires — then the user goes "Back to login". No client-side
                // retry counter.
                self.error = "Invalid verification code. Please try again."
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
                    } else {
                        // Server didn't surface the ID anywhere (should never
                        // happen) — nothing to acknowledge; proceed.
                        isLoggedIn = true
                        refreshStatsInBackground()
                    }
                }
            } catch {
                self.error = mapAuthError(
                    error,
                    fallback: "Could not create an anonymous account. Please try again."
                )
            }
            self.isLoading = false
        }
    }

    /// The user confirmed they saved the freshly-minted anonymous ID —
    /// proceed to Home.
    func acknowledgeAnonymousId() {
        guard createdAnonymousId != nil else { return }
        createdAnonymousId = nil
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
            case .serverMessage(let raw):
                let msg = Self.sanitizedServerMessage(raw, fallback: fallback)
                if msg == fallback { return fallback }
                // Client-minted sentinel (blank tokens in an ok response) —
                // show as-is, never with the "Login failed:" prefix.
                if msg == "Unexpected server response (no tokens)" { return msg }
                return Self.mapKnownMessage(msg)
            case .unauthorized:
                return "Invalid email or password"
            case .httpError(let code):
                if code == 401 { return "Invalid email or password" }
                if code == 429 { return "Too many attempts. Please wait a moment." }
                return "Login failed: Server error (\(code))"
            case .invalidURL, .invalidResponse:
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
            case .serverMessage(let msg):
                if msg.lowercased().contains("password") { return "Incorrect password" }
                if msg.contains("429") || msg.lowercased().contains("too many") {
                    return "Too many attempts. Please wait a moment."
                }
                return "Account deletion failed. Please try again."
            case .unauthorized:
                return "Incorrect password"
            case .httpError(let code):
                if code == 401 { return "Incorrect password" }
                if code == 429 { return "Too many attempts. Please wait a moment." }
                return "Account deletion failed. Please try again."
            case .invalidURL, .invalidResponse:
                return "Account deletion failed. Please try again."
            }
        }
        if error is URLError {
            return "Unable to reach server. Check your connection."
        }
        return "Account deletion failed. Please try again."
    }
}
