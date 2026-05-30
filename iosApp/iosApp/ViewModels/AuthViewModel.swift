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
        if keychain.accessToken != nil {
            isLoggedIn = true
            userEmail = keychain.userEmail
            hasConsented = true
        }
        hasConsented = UserDefaults.standard.bool(forKey: "gdpr_consented")
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
        isLoading = true
        error = nil
        pendingLoginEmail = email
        pendingLoginPassword = password

        Task {
            do {
                let result = try await api.login(email: email, password: password)
                switch result {
                case .success(let tokens):
                    keychain.save(accessToken: tokens.accessToken,
                                  refreshToken: tokens.refreshToken,
                                  email: email)
                    userEmail = email
                    isLoggedIn = true
                case .twoFactorRequired:
                    requiresTwoFactor = true
                case .failure(let message):
                    error = message
                }
                isLoading = false
            } catch {
                self.error = error.localizedDescription
                isLoading = false
            }
        }
    }

    func verifyTwoFactor() {
        guard !twoFactorCode.isEmpty else { return }
        isLoading = true
        error = nil

        Task {
            do {
                let tokens = try await api.verifyTwoFactor(
                    email: pendingLoginEmail,
                    password: pendingLoginPassword,
                    code: twoFactorCode
                )
                keychain.save(accessToken: tokens.accessToken,
                              refreshToken: tokens.refreshToken,
                              email: pendingLoginEmail)
                userEmail = pendingLoginEmail
                requiresTwoFactor = false
                isLoggedIn = true
                twoFactorCode = ""
                isLoading = false
            } catch {
                self.error = error.localizedDescription
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
        isLoading = true
        error = nil

        Task {
            do {
                let tokens = try await api.loginAnonymous()
                keychain.save(accessToken: tokens.accessToken,
                              refreshToken: tokens.refreshToken,
                              email: nil)
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
    }

    func deleteAccount() {
        isLoading = true
        Task {
            do {
                try await api.deleteAccount()
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
        let productID = StoreKitService.productID(
            planSlug: plan.rawValue,
            isYearly: billing == .yearly
        )
        isLoading = true
        Task {
            let store = StoreKitService.shared
            let ok = await store.purchase(productID: productID)
            if !ok, let storeError = store.lastError {
                self.error = storeError
            }
            isLoading = false
        }
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
