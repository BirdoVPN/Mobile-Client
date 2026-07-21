import Foundation
import SwiftUI
import BirdoShared

/// Manages authentication state: login, logout, 2FA, anonymous login.
@MainActor
final class AuthViewModel: ObservableObject {
    // MARK: - Published State
    @Published var isLoggedIn = false
    @Published var isLoading = false
    @Published var error: String?
    @Published var requiresTwoFactor = false
    @Published var userEmail: String?
    @Published var hasConsented = false
    @Published var currentPlan: Plan?
    @Published var subscriptionExpiry: String?

    // MARK: - Private
    private let api: APIClient
    private let keychain: KeychainService

    @Published var twoFactorCode = ""
    private var pendingLoginEmail = ""
    private var pendingLoginPassword = ""

    init(api: APIClient = .shared, keychain: KeychainService = .shared) {
        self.api = api
        self.keychain = keychain

        // Restore session
        let storedConsent = UserDefaults.standard.bool(forKey: "gdpr_consented")
        if keychain.accessToken != nil {
            isLoggedIn = true
            userEmail = keychain.userEmail
            // A logged-in session implies prior consent; preserve a stored
            // consent flag too rather than letting one source clobber the other.
            hasConsented = true
            // Entitlements live with the App Store, not in our keychain, so the
            // plan badge has to be re-derived on every launch.
            refreshEntitlements()
        } else {
            hasConsented = storedConsent
        }
    }

    // MARK: - Actions

    func acceptConsent() {
        hasConsented = true
        UserDefaults.standard.set(true, forKey: "gdpr_consented")
    }

    func declineConsent() {
        hasConsented = false
        UserDefaults.standard.set(false, forKey: "gdpr_consented")
    }

    func login(email: String, password: String) {
        // Single-flight. SwiftUI applies `.disabled(authVM.isLoading)` on the
        // next render pass, so two fast taps both clear the enabled check and
        // fire two logins. Every backend login mints a Session row, which is
        // exactly what surfaced as duplicate "Active sessions" for one device on
        // Android (fixed there with the same guard).
        guard !isLoading else { return }
        isLoading = true
        error = nil
        pendingLoginEmail = email
        pendingLoginPassword = password

        Task {
            do {
                let result = try await api.login(email: email, password: password)
                switch result {
                case .success(let tokens):
                    // A failed keychain write is fatal to the session: every
                    // later request would go out unauthenticated and the login
                    // would be gone at next launch. Refuse to report success.
                    guard keychain.save(accessToken: tokens.accessToken,
                                        refreshToken: tokens.refreshToken,
                                        email: email) else {
                        error = "Could not securely store your session. Please try again."
                        pendingLoginEmail = ""
                        pendingLoginPassword = ""
                        isLoading = false
                        return
                    }
                    userEmail = email
                    isLoggedIn = true
                    // No 2FA step will consume these; clear them now.
                    pendingLoginEmail = ""
                    pendingLoginPassword = ""
                case .twoFactorRequired:
                    requiresTwoFactor = true
                case .failure(let message):
                    error = message
                    // No 2FA step will consume these either — don't leave the
                    // plaintext password sitting in memory after a rejection.
                    pendingLoginEmail = ""
                    pendingLoginPassword = ""
                }
                isLoading = false
            } catch {
                self.error = error.localizedDescription
                pendingLoginEmail = ""
                pendingLoginPassword = ""
                isLoading = false
            }
        }
    }

    func verifyTwoFactor(code: String) {
        // Use the code supplied by the caller (the user's typed input) rather
        // than relying on a separately-bound published property that the view
        // may never populate.
        twoFactorCode = code
        guard !twoFactorCode.isEmpty else { return }
        // Single-flight — see login().
        guard !isLoading else { return }
        isLoading = true
        error = nil

        Task {
            do {
                let tokens = try await api.verifyTwoFactor(
                    email: pendingLoginEmail,
                    password: pendingLoginPassword,
                    code: twoFactorCode
                )
                guard keychain.save(accessToken: tokens.accessToken,
                                    refreshToken: tokens.refreshToken,
                                    email: pendingLoginEmail) else {
                    // See login(): a lost keychain write means no session at all.
                    error = "Could not securely store your session. Please try again."
                    pendingLoginEmail = ""
                    pendingLoginPassword = ""
                    isLoading = false
                    return
                }
                userEmail = pendingLoginEmail
                requiresTwoFactor = false
                isLoggedIn = true
                twoFactorCode = ""
                // Drop the retained plaintext credentials as soon as they are
                // no longer needed to shrink the in-memory exposure window.
                pendingLoginEmail = ""
                pendingLoginPassword = ""
                isLoading = false
            } catch {
                self.error = error.localizedDescription
                // Clear the plaintext password on failure too, so it does not
                // linger in memory across failed verification attempts.
                pendingLoginEmail = ""
                pendingLoginPassword = ""
                isLoading = false
            }
        }
    }

    func cancelTwoFactor() {
        requiresTwoFactor = false
        twoFactorCode = ""
        pendingLoginEmail = ""
        pendingLoginPassword = ""
    }

    func loginAnonymous() {
        // Single-flight — see login().
        guard !isLoading else { return }
        isLoading = true
        error = nil

        Task {
            do {
                let tokens = try await api.loginAnonymous()
                guard keychain.save(accessToken: tokens.accessToken,
                                    refreshToken: tokens.refreshToken,
                                    email: nil) else {
                    // See login(): a lost keychain write means no session at all.
                    error = "Could not securely store your session. Please try again."
                    isLoading = false
                    return
                }
                userEmail = nil
                isLoggedIn = true
                isLoading = false
            } catch {
                self.error = error.localizedDescription
                isLoading = false
            }
        }
    }

    func logout() {
        keychain.clear()
        // AUDIT-C1: drop the persisted ML-KEM-1024 client identity so the
        // next user gets a fresh PQ keypair instead of inheriting this one.
        BirdoPQManager.shared.resetPersistedKeypair()
        userEmail = nil
        isLoggedIn = false
        requiresTwoFactor = false
        error = nil
        // Clear the previous account's entitlement view, otherwise the next user
        // to sign in on this device inherits its plan badge. Also drop any
        // in-flight loading flag so the login form isn't left spinning/disabled.
        currentPlan = nil
        subscriptionExpiry = nil
        isLoading = false
        // Ensure no plaintext credentials linger after sign-out.
        pendingLoginEmail = ""
        pendingLoginPassword = ""
        twoFactorCode = ""
    }

    /// GDPR Art. 17 erasure. `password` is REQUIRED by the backend for accounts
    /// that have one (email signups) and ignored for SSO/anonymous accounts —
    /// see gdpr.controller.ts, which 401s a password account with no password.
    func deleteAccount(password: String?) {
        isLoading = true
        error = nil
        Task {
            do {
                try await api.deleteAccount(password: password)
                logout()
            } catch {
                self.error = error.localizedDescription
            }
            isLoading = false
        }
    }

    /// Begin a StoreKit 2 purchase for the given plan + billing period.
    /// On success the user's entitlement set is refreshed so the UI flips
    /// to the new plan immediately. Errors surface via `self.error`.
    func subscribe(plan: Plan, billing: BillingPeriod) {
        // RECON is the free tier and has no StoreKit product to purchase.
        guard !plan.isFree else { return }
        guard !isLoading else { return }
        let productID = StoreKitService.productID(
            planSlug: plan.rawValue,
            isYearly: billing == .yearly
        )
        isLoading = true
        error = nil
        Task {
            let store = StoreKitService.shared
            let ok = await store.purchase(productID: productID)
            if ok {
                // Honour the contract stated above. Nothing ever assigned
                // `currentPlan`, so the "CURRENT" badge never appeared and the
                // user could buy a plan they already owned.
                await store.refreshEntitlements()
                currentPlan = highestEntitledPlan(in: store.purchasedProductIDs)
            } else if let storeError = store.lastError {
                self.error = storeError
            }
            isLoading = false
        }
    }

    /// Refresh `currentPlan` from the App Store's active entitlements. Called on
    /// launch for an existing session so the plan badge survives a relaunch.
    func refreshEntitlements() {
        Task {
            let store = StoreKitService.shared
            await store.refreshEntitlements()
            currentPlan = highestEntitledPlan(in: store.purchasedProductIDs)
        }
    }

    /// Map an active StoreKit entitlement set to the highest plan it grants.
    /// Returns `nil` when nothing paid is active — RECON (free) has no product,
    /// so an absent entitlement is not evidence of a RECON subscription.
    private func highestEntitledPlan(in productIDs: Set<String>) -> Plan? {
        for plan in [Plan.sovereign, Plan.operative] {
            let monthly = StoreKitService.productID(planSlug: plan.rawValue, isYearly: false)
            let yearly = StoreKitService.productID(planSlug: plan.rawValue, isYearly: true)
            if productIDs.contains(monthly) || productIDs.contains(yearly) {
                return plan
            }
        }
        return nil
    }
}

// MARK: - Login result for ViewModel

enum LoginResultType {
    case success(TokenPairData)
    case twoFactorRequired
    case failure(String)
}

struct TokenPairData {
    let accessToken: String
    let refreshToken: String
}
