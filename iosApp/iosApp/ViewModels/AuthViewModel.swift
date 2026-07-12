import Foundation
import SwiftUI

/// Manages authentication state: login, logout, 2FA, anonymous login, and the
/// current subscription (read-only — plans are changed on the website).
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
    /// The 2FA challenge is a server-issued token, NOT re-sent credentials.
    /// The old flow kept the user's plaintext password in memory across the
    /// whole 2FA hop; the challenge-token protocol removes that entirely.
    private var pendingChallengeToken: String?
    private var pendingLoginEmail = ""

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
            refreshSubscription()
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
        isLoading = true
        error = nil

        Task {
            do {
                let response = try await api.login(email: email, password: password)
                if response.requiresTwoFactor == true {
                    pendingChallengeToken = response.challengeToken
                    pendingLoginEmail = email
                    requiresTwoFactor = true
                } else if let tokens = response.tokens {
                    completeLogin(tokens: tokens, email: email)
                } else {
                    error = "Sign-in failed. Check your email and password."
                }
                isLoading = false
            } catch {
                self.error = error.localizedDescription
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
        guard let challengeToken = pendingChallengeToken else {
            error = "The sign-in challenge expired. Please sign in again."
            requiresTwoFactor = false
            return
        }
        isLoading = true
        error = nil

        Task {
            do {
                let response = try await api.verifyTwoFactor(
                    challengeToken: challengeToken,
                    code: twoFactorCode
                )
                if response.ok, let tokens = response.tokens {
                    completeLogin(tokens: tokens, email: pendingLoginEmail)
                    requiresTwoFactor = false
                    pendingChallengeToken = nil
                    pendingLoginEmail = ""
                    twoFactorCode = ""
                } else {
                    error = "That code didn't work. Try again."
                }
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
        pendingChallengeToken = nil
        pendingLoginEmail = ""
    }

    func loginAnonymous() {
        isLoading = true
        error = nil

        Task {
            do {
                let response = try await api.loginAnonymous(anonymousId: Self.anonymousId())
                guard let tokens = response.tokens else {
                    error = "Anonymous sign-in failed. Try again."
                    isLoading = false
                    return
                }
                completeLogin(tokens: tokens, email: nil)
                isLoading = false
            } catch {
                self.error = error.localizedDescription
                isLoading = false
            }
        }
    }

    func logout() {
        Task { await api.logout() }
        keychain.clear()
        // AUDIT-C1: drop the persisted ML-KEM-1024 client identity so the
        // next user gets a fresh PQ keypair instead of inheriting this one.
        BirdoPQManager.shared.resetPersistedKeypair()
        userEmail = nil
        isLoggedIn = false
        requiresTwoFactor = false
        error = nil
        currentPlan = nil
        subscriptionExpiry = nil
        pendingChallengeToken = nil
        pendingLoginEmail = ""
        twoFactorCode = ""
    }

    /// GDPR deletion. The password goes IN THE REQUEST — the backend requires
    /// it (DELETE v1/gdpr/delete with body). The old code collected the
    /// password in the UI and then never sent it, so nothing was deleted.
    func deleteAccount(password: String) {
        isLoading = true
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

    // MARK: - Subscription (read-only; managed on birdo.app)

    func refreshSubscription() {
        Task {
            do {
                let status = try await api.fetchSubscription()
                currentPlan = Plan(rawValue: status.plan) ?? .recon
                subscriptionExpiry = status.subscriptionEndsAt
            } catch {
                // Non-fatal: plan display degrades to RECON; next refresh retries.
            }
        }
    }

    // MARK: - Helpers

    private func completeLogin(tokens: TokenPair, email: String?) {
        keychain.save(accessToken: tokens.accessToken,
                      refreshToken: tokens.refreshToken,
                      email: email)
        userEmail = email
        isLoggedIn = true
        refreshSubscription()
    }

    /// Stable per-install anonymous identity. An identifier, not a secret —
    /// UserDefaults is the right home (Android keeps its equivalent in prefs).
    private static func anonymousId() -> String {
        let key = "anonymous_id"
        if let existing = UserDefaults.standard.string(forKey: key) {
            return existing
        }
        let fresh = UUID().uuidString
        UserDefaults.standard.set(fresh, forKey: key)
        return fresh
    }
}
