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

/// #485 review — what the app shows as "anonymous account" and its number
/// (`AuthViewModel.isAnonymousAccount` / `anonymousAccountNumber`).
///
/// The profile decides once it is known. Before it lands (a cold start
/// offline), a number stored on the device stands in — but only while SIGNED
/// IN, when it is the number recorded for this session
/// (`AnonymousNumberRecord`). A stored number is never shown while signed out,
/// nor once the profile says the account is not anonymous: it can then only be
/// a previous account's.
enum AnonymousAccountDisplay {
    static func isAnonymous(profile: AccountIdentity.Resolved?,
                            signedIn: Bool,
                            email: String?,
                            storedNumber: String?) -> Bool {
        if let profile { return profile.isAnonymous }
        return signedIn && email == nil && storedNumber != nil
    }

    static func number(profile: AccountIdentity.Resolved?,
                       signedIn: Bool,
                       storedNumber: String?) -> String? {
        if let profile {
            guard profile.isAnonymous else { return nil }
            if let number = profile.accountNumber { return number }
        }
        return signedIn ? storedNumber : nil
    }
}

/// How the account number reads on screen (MR-1362): Android's rule
/// (`AnonymousId.kt` `formatAnonymousId` / `maskAnonymousId`), so both apps
/// show the same thing and both match the account API contract's "mask it by
/// default". Profile shows `masked` until the user asks to see the number, then
/// `grouped`; Copy always takes the full number.
///
/// Foundation-only, pinned by AccountIdentityTests against Android's own test
/// values.
enum AccountNumberText {
    /// Digits only, in groups of four: `1234 5678 9012 3456 7890 1234`, the
    /// shape the sign-in field reads back. Display formatting, never
    /// validation: a partial number is grouped too.
    static func grouped(_ raw: String) -> String {
        groups(raw).joined(separator: " ")
    }

    /// Every group masked but the last — `•••• •••• •••• •••• •••• 1234` — so
    /// the user can tell which account this is without showing the credential
    /// to anyone beside them. Same length as `grouped`.
    static func masked(_ raw: String) -> String {
        let all = groups(raw)
        return all.enumerated().map { index, group in
            index == all.count - 1 ? group : String(repeating: "•", count: group.count)
        }.joined(separator: " ")
    }

    /// The last group, for VoiceOver while the number is masked.
    static func lastGroup(_ raw: String) -> String {
        groups(raw).last ?? ""
    }

    private static func groups(_ raw: String) -> [String] {
        let digits = Array(raw.filter { $0.isASCII && $0.isWholeNumber })
        return stride(from: 0, to: digits.count, by: 4).map {
            String(digits[$0..<min($0 + 4, digits.count)])
        }
    }
}
