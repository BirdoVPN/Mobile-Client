import Foundation
import Combine

/// MR-2152: whether the sign-up proof-of-work is running, so the sign-in
/// screen can show one status line ("Checking this device…") while it does.
/// A shared object and not a parameter so AuthViewModel.createAnonymousAccount
/// and APIClient.registerAnonymous keep their signatures. App-only: it needs
/// Combine and a main-actor home, which BirdoVPNTests does not compile.
@MainActor
final class SignupPowStatus: ObservableObject {
    static let shared = SignupPowStatus()

    @Published private(set) var isChecking = false

    func set(_ checking: Bool) {
        isChecking = checking
    }
}
