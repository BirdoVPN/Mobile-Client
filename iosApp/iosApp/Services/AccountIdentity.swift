import Foundation

/// "Is this an anonymous account, and what is its account number?" — decided
/// from `GET /auth/me`.
///
/// Owner item 86 (2026-10-01). `/auth/me` used to answer this only through the
/// synthetic email `anon_<24 digits>@anonymous.local`, which carries the
/// account's sole credential in a field every client and log treats as an
/// email address. The backend now also sends:
///   * `accountType`   — "anonymous" | "standard"
///   * `isAnonymous`   — Bool
///   * `accountNumber` — the 24-digit number, or null
/// and will stop putting the number in `email` once every client reads these.
///
/// So the explicit fields WIN when present, and the email parsing below is the
/// fallback for a backend that predates them. Keep the fallback until the
/// backend drops the synthetic email; removing it early would blank the
/// account-number card for anyone on an older server.
///
/// Foundation-only and compiled into the unit-test bundle (`UserProfile`
/// itself lives in APIClient.swift, which cannot be).
enum AccountIdentity {
    struct Resolved: Equatable, Sendable {
        let isAnonymous: Bool
        /// Only ever non-nil for an anonymous account.
        let accountNumber: String?
    }

    static func resolve(accountType: String?,
                        isAnonymous: Bool?,
                        accountNumber: String?,
                        email: String?) -> Resolved {
        let anonymous = isAnonymous
            ?? explicitType(accountType)
            ?? isSyntheticAnonymousEmail(email)
        guard anonymous else { return Resolved(isAnonymous: false, accountNumber: nil) }
        let number = digits(accountNumber) ?? numberFromSyntheticEmail(email)
        return Resolved(isAnonymous: true, accountNumber: number)
    }

    /// `accountType` → Bool, or nil for absent / a value this build does not
    /// know (which then falls through to the next source rather than guessing).
    private static func explicitType(_ raw: String?) -> Bool? {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "anonymous": return true
        case "standard": return false
        default: return nil
        }
    }

    /// The pre-item-86 signal: `anon_<digits>@anonymous.local`.
    static func isSyntheticAnonymousEmail(_ email: String?) -> Bool {
        guard let email else { return false }
        return email.hasPrefix("anon_") && email.hasSuffix("@anonymous.local")
    }

    /// The account number inside the synthetic email, or nil.
    static func numberFromSyntheticEmail(_ email: String?) -> String? {
        guard isSyntheticAnonymousEmail(email), let email,
              let atIndex = email.firstIndex(of: "@") else { return nil }
        let start = email.index(email.startIndex, offsetBy: "anon_".count)
        guard start < atIndex else { return nil }
        let candidate = String(email[start..<atIndex])
        guard !candidate.isEmpty, candidate.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return candidate
    }

    /// The server's `accountNumber`, reduced to its digits (tolerating a
    /// grouped "1234 5678 …" rendering); nil when nothing usable is left.
    private static func digits(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let only = raw.filter { $0.isASCII && $0.isNumber }
        return only.isEmpty ? nil : only
    }
}
