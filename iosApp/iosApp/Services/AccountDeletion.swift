import Foundation

/// Account deletion (GDPR Art. 17) — the pure decisions behind
/// `AuthViewModel.deleteAccount`, kept Foundation-only so the unit-test bundle
/// can pin them (AuthViewModel and APIClient cannot be compiled into it).
///
/// Owner item 43 (2026-10-01): the delete request goes FIRST. Only a server
/// that has CONFIRMED the erasure gets the tunnel torn down, the credentials
/// cleared and the user signed out; a refusal leaves the user connected and
/// signed in, with the reason on screen. (It used to disconnect the VPN before
/// sending, so a wrong password or a 5xx cost the user their tunnel for
/// nothing.)
enum AccountDeletion {
    /// Did the request possibly reach the server, with the answer lost?
    ///
    /// This is NOT an edge case while connected. The request rides the tunnel,
    /// and the erasure revokes every peer of the account on the nodes BEFORE
    /// the server answers (birdo-web `GdprService.deleteUserData`, B5), so the
    /// answer to a successful deletion is routed to a peer that no longer
    /// exists and never arrives: the request times out on a deleted account.
    /// Reading that timeout as "deletion failed" would leave the user signed
    /// in to nothing, behind a dead tunnel.
    ///
    /// So these errors mean UNKNOWN, and the caller checks: tunnel down, then
    /// `GET /auth/me` — a 401 there is a confirmed deletion.
    ///
    /// Transport errors that prove the request never left (no route, DNS, TLS
    /// or pin failure) are definitive failures instead: nothing was sent.
    static func outcomeIsUnknown(_ error: Error) -> Bool {
        guard let urlError = error as? URLError else { return false }
        switch urlError.code {
        case .timedOut, .networkConnectionLost, .badServerResponse,
             .cannotParseResponse, .zeroByteResource:
            return true
        default:
            return false
        }
    }

    /// Owner item 97: should deletion re-authenticate with Sign in with Apple
    /// first?
    ///
    /// `/auth/me` does not say whether an account is linked to Apple, so the
    /// app offers it when it KNOWS: this device's session was signed in with
    /// Apple, or the account's email is the backend's Apple alias
    /// (`apple_<hash>@appleid.local`, used when Apple shares no real address)
    /// or an Apple private-relay address. An Apple-linked account signed in
    /// some other way on this device is not detected; it deletes as before.
    static func offersAppleReauth(signedInWithAppleOnThisDevice: Bool, accountEmail: String?) -> Bool {
        if signedInWithAppleOnThisDevice { return true }
        guard let email = accountEmail?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
              !email.isEmpty else { return false }
        return email.hasSuffix("@appleid.local") || email.hasSuffix("@privaterelay.appleid.com")
    }

    /// MR-179 (owner 2026-11-13): the digits of what the user typed, so a
    /// grouped or pasted account number ("1234 5678 ...") or e-mailed code
    /// reaches the server as plain digits.
    static func digitsOnly(_ text: String) -> String {
        String(text.filter { $0.isASCII && $0.isNumber })
    }

    /// An anonymous account number is 24 digits.
    static func isCompleteAccountNumber(_ text: String) -> Bool {
        digitsOnly(text).count == 24
    }

    /// The code `POST /api/v1/gdpr/delete/code` e-mails is 6 digits.
    static func isCompleteEmailCode(_ text: String) -> Bool {
        digitsOnly(text).count == 6
    }

    /// Client-side completeness gate for the deletion dialog's code field —
    /// the same rule as the login 2FA step (the server is authoritative): a
    /// 6-digit TOTP, or a hex backup code in 4-character groups, dashes
    /// optional.
    static func isCompleteTwoFactorCode(_ code: String) -> Bool {
        let c = code.trimmingCharacters(in: .whitespaces)
        if c.range(of: #"^\d{6}$"#, options: .regularExpression) != nil { return true }
        return c.range(of: #"^[0-9A-Fa-f]{4}(-?[0-9A-Fa-f]{4})+$"#,
                       options: .regularExpression) != nil
    }
}

/// Body of `DELETE /api/v1/gdpr/delete`.
///
/// Blank values are dropped and nil fields are OMITTED (synthesized
/// `Encodable` skips them), so a password-less account sends `{}` exactly as
/// before, and the new fields only appear when there is something to say.
/// The backend's schema is a non-strict zod object: an older deploy strips
/// keys it does not know rather than rejecting them.
struct DeleteAccountBody: Encodable, Equatable {
    let password: String?
    /// Owner item 85: a 6-digit TOTP or a backup code, sent after the server
    /// answered `two_factor_required`.
    let twoFactorCode: String?
    /// Owner item 97: a fresh Sign in with Apple `authorizationCode`, so the
    /// backend can revoke the app's Apple tokens with the account.
    let appleAuthorizationCode: String?
    /// MR-179: the 24-digit number an anonymous account re-enters (digits only).
    let accountNumber: String?
    /// MR-179: the 6-digit code e-mailed to an SSO account with no 2FA.
    let emailCode: String?
    /// MR-179: a fresh Sign in with Apple identity token (and the raw nonce it
    /// was requested with) for an Apple account that has no e-mail address.
    let appleIdentityToken: String?
    let appleNonce: String?
    /// MR-179 hold fallback (birdo-web #883): `"hold"` asks the server to
    /// delete the account in 24 hours instead, for a user who cannot present
    /// the step-up factor (a lost account number). The password and 2FA code
    /// still ride along where the account has them. Not a secret.
    let stepUpMethod: String?

    init(password: String?,
         twoFactorCode: String? = nil,
         appleAuthorizationCode: String? = nil,
         accountNumber: String? = nil,
         emailCode: String? = nil,
         appleIdentityToken: String? = nil,
         appleNonce: String? = nil,
         stepUpMethod: String? = nil) {
        self.password = Self.nonBlank(password)
        self.twoFactorCode = Self.nonBlank(twoFactorCode)
        self.appleAuthorizationCode = Self.nonBlank(appleAuthorizationCode)
        self.accountNumber = Self.nonBlank(accountNumber.map(AccountDeletion.digitsOnly))
        self.emailCode = Self.nonBlank(emailCode.map(AccountDeletion.digitsOnly))
        self.appleIdentityToken = Self.nonBlank(appleIdentityToken)
        self.appleNonce = Self.nonBlank(appleNonce)
        self.stepUpMethod = Self.nonBlank(stepUpMethod)
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return nil }
        return trimmed
    }
}

/// The deletion refusals the app acts on by CODE (owner item 85).
///
/// For an account with two-factor authentication the backend answers
///   * 403 `{"error":"two_factor_required"}` — no code was sent;
///   * 403 `{"error":"two_factor_invalid"}`  — the code was wrong;
/// (and 429 for too many attempts, which the generic mapping already covers).
/// GlobalExceptionFilter puts a thrown exception's `error` on the wire
/// verbatim; `details.code`, `code` and a bare `message` are read too, so the
/// classification survives the backend choosing any of them. Matching is on
/// the exact code, never on prose, so nothing else can be mistaken for it.
enum DeletionRefusal: Error, Equatable, Sendable {
    case twoFactorRequired
    case twoFactorInvalid
    /// MR-179: the deletion step-up (birdo-web deletion-step-up.ts). Nothing
    /// was deleted; the dialog asks for, or says it was wrong, the proof.
    case accountNumberRequired
    case accountNumberInvalid
    case emailCodeRequired
    case emailCodeInvalid
    case appleReauthRequired
    case appleReauthInvalid

    static func classify(status: Int, body: Data) -> DeletionRefusal? {
        // Any 4xx: the contract says 403, but a 401 carrying the same code is
        // the same refusal (the retry after the token refresh reaches here).
        guard (400..<500).contains(status),
              let object = try? JSONSerialization.jsonObject(with: body),
              let json = object as? [String: Any] else { return nil }
        var candidates: [String] = []
        for key in ["error", "code", "message"] {
            if let value = json[key] as? String { candidates.append(value) }
        }
        if let details = json["details"] as? [String: Any],
           let code = details["code"] as? String {
            candidates.append(code)
        }
        for raw in candidates {
            switch raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "two_factor_required": return .twoFactorRequired
            case "two_factor_invalid": return .twoFactorInvalid
            case "account_number_required": return .accountNumberRequired
            case "account_number_invalid": return .accountNumberInvalid
            case "email_code_required": return .emailCodeRequired
            case "email_code_invalid": return .emailCodeInvalid
            case "apple_reauth_required": return .appleReauthRequired
            case "apple_reauth_invalid": return .appleReauthInvalid
            default: continue
            }
        }
        return nil
    }
}

/// `stepUp` of `GET /api/v1/gdpr/delete/preflight` (MR-179): the server's
/// factor tokens for this account (`password`, `account_number`, `email_code`,
/// `apple_reauth`, `two_factor`) and whether it refuses a deletion without
/// them. Every field optional: an older backend sends none.
struct DeletionStepUp: Decodable, Sendable, Equatable {
    let factors: [String]?
    let enforced: Bool?
    /// MR-179 hold fallback (birdo-web #883): the server takes
    /// `stepUpMethod: "hold"` from this account. Missing reads as false.
    let holdAvailable: Bool?

    var needsAccountNumber: Bool { factors?.contains("account_number") ?? false }
    var needsEmailCode: Bool { factors?.contains("email_code") ?? false }
    var needsAppleReauth: Bool { factors?.contains("apple_reauth") ?? false }
}

/// `POST /api/v1/gdpr/delete/code` reply: the masked address the code went to.
struct DeletionCodeSent: Decodable, Sendable, Equatable {
    let sentTo: String?
}

/// What a Sign in with Apple re-authentication for an account deletion yields
/// (MR-179): the `authorizationCode` for token revocation, and the identity
/// token plus the RAW nonce that prove a fresh Apple sign-in to the backend
/// (`apple_reauth`). Lives here, Foundation-only, so the test bundle compiles
/// it with its redaction (AppleSignInService.swift is not in that bundle).
struct DeletionAppleProof: Equatable, Sendable {
    let authorizationCode: String
    let identityToken: String?
    let nonce: String
}

// MARK: - MR-179 hold fallback (birdo-web #883)

/// An account deletion the server holds for 24 hours: "Delete in 24 hours
/// (I lost my account number)". Nothing is deleted until `applyAfter`; until
/// then the hold can be cancelled (`POST /auth/pending-changes/:id/cancel`).
struct PendingDeletion: Equatable, Sendable {
    let id: String
    let applyAfter: Date
}

/// The hold request's non-2xx answers that are not a refusal of the proof
/// (those stay `DeletionRefusal` / the generic mapping). Nothing was deleted.
enum DeletionHoldRefusal: Error, Equatable, Sendable {
    /// 409 `change_already_pending`: the hold that already waits. Nil when
    /// the body does not name it (the API's exception filter passes only
    /// `details` through): the caller reads `GET /auth/pending-changes`.
    case alreadyPending(PendingDeletion?)
    /// 409 `hold_unavailable`: the hold is switched off, or the request could
    /// not be tied to a signed-in session ("sign in to the app again"). The
    /// server's own sentence says which, if it sent one.
    case unavailable(message: String?)
    /// 429 `too_many_attempts`: past 3 holds a day ("try again tomorrow"), or
    /// the step-up's own limiter. The server's sentence says how long.
    case limited(message: String?)
    /// 503 `step_up_unavailable`: the server could not check. Nothing was filed.
    case notFiled
    /// A 2xx to a hold request that is not a readable hold (review of #616):
    /// the server never deletes at once for a hold, so nothing is assumed,
    /// least of all a deletion. The caller reads the list.
    case unexpectedAnswer
}

/// Review of #616: how a failed `deleteAccount` is handled, decided in ONE
/// place and in a pinned order, so AccountDeletionTests can check it. A hold
/// is matched before the lost-answer check, so a held deletion can never be
/// mistaken for an unknown outcome (which tears the tunnel down).
enum DeletionFailureRoute: Equatable, Sendable {
    /// The proof was asked for or wrong (2FA, the step-up): the dialog asks.
    case refusal(DeletionRefusal)
    /// The server held the deletion instead: show the hold (nil: read the list).
    case held(PendingDeletion?)
    /// The answer was lost: check whether the account still exists.
    case outcomeUnknown
    /// Anything else: the mapped message. Nothing was deleted.
    case failed
}

extension AccountDeletion {
    static func route(_ error: Error) -> DeletionFailureRoute {
        if let refusal = error as? DeletionRefusal { return .refusal(refusal) }
        if let hold = error as? DeletionHoldRefusal {
            if case .alreadyPending(let pending) = hold { return .held(pending) }
            return .held(nil)
        }
        if outcomeIsUnknown(error) { return .outcomeUnknown }
        return .failed
    }
}

/// The wire rules of the hold, Foundation-only so AccountDeletionTests pins them.
///
/// `stepUp` is polymorphic on `DELETE /api/v1/gdpr/delete` (#883 final): an
/// OBJECT `{ methods, pendingChangeId, applyAfter }` on 202 and on 409
/// `change_already_pending`, an ARRAY of factor names on 401 / 403 / 429 /
/// 503 and on 409 `hold_unavailable`. It is read by status and `error`, never
/// by its shape: only those two answers are ever read for a hold. Everything
/// here reads with `JSONSerialization`, so no shape can throw.
enum DeletionHold {
    static let method = "hold"
    static let accountDeleteAction = "account_delete"

    /// What a 2xx of the deletion route is.
    enum SuccessKind: Equatable, Sendable {
        /// 202 `change_pending`: a hold, NOT a deletion.
        case held(PendingDeletion)
        /// A hold whose `stepUp` cannot be read: never a success, never a deletion.
        case unreadableHold
        /// A completed deletion (any other 2xx, as before).
        case deletion
    }

    /// A 2xx answering a HOLD request: the hold, or nil for anything else.
    /// Never a deletion (review of #616): `{}`, an empty or a non-JSON 200
    /// assumes nothing.
    static func holdAnswer(status: Int, body: Data) -> PendingDeletion? {
        if case .held(let pending) = classifySuccess(status: status, body: body) { return pending }
        return nil
    }

    static func classifySuccess(status: Int, body: Data) -> SuccessKind {
        let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
        let isHold = status == 202
            || (json?["pending"] as? Bool) == true
            || (json?["code"] as? String) == "change_pending"
        guard isHold else { return .deletion }
        return pending(fromStepUp: json?["stepUp"]).map(SuccessKind.held) ?? .unreadableHold
    }

    /// The hold request's own refusals, by status AND code; nil for anything
    /// else (the proof refusals and the generic mapping keep those).
    static func classifyRefusal(status: Int, body: Data) -> DeletionHoldRefusal? {
        guard let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else { return nil }
        let details = json["details"] as? [String: Any]
        let codes = [json["error"], json["code"], details?["code"]]
            .compactMap { ($0 as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
        let message = (json["message"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let sentence = (message?.isEmpty == false) ? message : nil
        switch status {
        case 409 where codes.contains("change_already_pending"):
            return .alreadyPending(pending(fromStepUp: json["stepUp"])
                ?? pending(fromStepUp: details?["stepUp"])
                ?? pending(fromStepUp: details))
        case 409 where codes.contains("hold_unavailable"):
            return .unavailable(message: sentence)
        case 429 where codes.contains("too_many_attempts"):
            return .limited(message: sentence)
        case 503 where codes.contains("step_up_unavailable"):
            return .notFiled
        default:
            return nil
        }
    }

    /// The hold in a `stepUp` value: an object with a non-blank id and an
    /// ISO-8601 instant. An array (the refusals' factor list) or anything else is nil.
    static func pending(fromStepUp value: Any?) -> PendingDeletion? {
        guard let object = value as? [String: Any],
              let id = (object["pendingChangeId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !id.isEmpty,
              let raw = object["applyAfter"] as? String,
              let at = parseInstant(raw) else { return nil }
        return PendingDeletion(id: id, applyAfter: at)
    }

    /// `GET /auth/pending-changes`: `.some(nil)` when no deletion is on hold,
    /// nil when the body is not the list at all (the caller keeps what it shows).
    static func accountDeletion(inPendingChanges body: Data) -> PendingDeletion?? {
        guard let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              let list = json["pendingChanges"] as? [Any] else { return nil }
        let held = list.compactMap { item -> PendingDeletion? in
            guard let entry = item as? [String: Any],
                  (entry["action"] as? String) == accountDeleteAction,
                  let id = (entry["id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !id.isEmpty,
                  let raw = entry["applyAfter"] as? String,
                  let at = parseInstant(raw) else { return nil }
            return PendingDeletion(id: id, applyAfter: at)
        }
        return .some(held.min { $0.applyAfter < $1.applyAfter })
    }

    /// ISO-8601 with an offset, with or without fractional seconds.
    static func parseInstant(_ raw: String) -> Date? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: text) { return date }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: text)
    }

    /// Review of #616: the server cancels a hold when the app SESSION that
    /// filed it signs out. Said only about a hold this session files or filed:
    /// the deletion dialog's hold step while no hold is known
    /// (`holdStepSignOutNote`), and the banner of a hold filed here
    /// (`bannerSignOutNote`).
    static let signOutCancels = "Stay signed in on this device until then; signing out cancels the deletion."

    // MR-179 hold origin. The sign-out texts below are the COMMON wording,
    // shared with Desktop-Client #439 and Mobile-Client #623 (Android).

    /// The sign-out confirmation's line for a hold THIS session filed
    /// (`SignOutHoldNotice.filedHere`).
    static func signOutWarning(applyAfter: Date, timeZone: TimeZone = .current, locale: Locale = .current) -> String {
        "Signing out cancels the deletion of your account that is scheduled for "
            + formatted(applyAfter, timeZone: timeZone, locale: locale) + "."
    }

    /// A hold not known to be filed by this session. The server voids a hold
    /// on sign-out only when the session that filed it signs out (birdo-web
    /// #883, `heldDeletionVoided`), so this one may survive. No origin is
    /// named: the app does not know it.
    static let signOutKeeps = "Signing out of this app may not cancel this deletion. To keep your account, cancel the deletion."

    /// The sign-out confirmation's line for a hold not known to be filed here
    /// (a list read or a 409 named it).
    static func signOutKeepsWarning(applyAfter: Date, timeZone: TimeZone = .current, locale: Locale = .current) -> String {
        bannerText(applyAfter: applyAfter, timeZone: timeZone, locale: locale) + " " + signOutKeeps
    }

    /// The list could not be read before the sign-out confirmation decided
    /// (a failure, or no answer within `SignOutHold.readWait`). Sign-out
    /// stays allowed.
    static let signOutUnchecked = "We couldn't check whether a deletion is waiting. Signing out may not cancel it. "
        + "To keep your account, stay signed in and cancel it once you're online."

    /// Shown while the sign-out confirmation waits for the list read.
    static let signOutChecking = "Checking whether a deletion is waiting for this account\u{2026}"

    /// Sign-out cancels a hold filed here first, and that cancel failed:
    /// nobody was signed out, and nothing claims the account is safe. With
    /// no date known (a cold start that could not read the list), the hold
    /// is named by where it was asked for.
    static func signOutCancelFailed(reason: String, applyAfter: Date?,
                                    timeZone: TimeZone = .current, locale: Locale = .current) -> String {
        let scheduled = applyAfter.map {
            "your account is still scheduled to be deleted on " + formatted($0, timeZone: timeZone, locale: locale)
        } ?? "the deletion you asked for on this device may still be scheduled"
        return reason + " You are still signed in, and " + scheduled
            + ". Try again, or sign out anyway: the deletion may then still go ahead."
    }

    /// The Profile banner's sentence about signing out, by where the hold came from.
    static func bannerSignOutNote(filedHere: Bool) -> String {
        filedHere ? signOutCancels : signOutKeeps
    }

    /// The deletion dialog's hold step: "signing out cancels the deletion"
    /// only once the list has ANSWERED that no hold waits (MR-2389, as
    /// Android #623's `holdStepSaysSignOutCancels`). With one waiting, a new
    /// hold request is answered 409 `change_already_pending`, for a hold this
    /// session may not have filed; before the list answered, that is not
    /// known yet.
    static func holdStepSignOutNote(holdKnown: Bool, listAnswered: Bool) -> String? {
        listAnswered && !holdKnown ? signOutCancels : nil
    }

    private static func formatted(_ date: Date, timeZone: TimeZone, locale: Locale) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        formatter.timeZone = timeZone
        formatter.locale = locale
        return formatter.string(from: date)
    }

    /// The Profile banner's sentence: `applyAfter` in the device's zone and locale.
    static func bannerText(applyAfter: Date, timeZone: TimeZone = .current, locale: Locale = .current) -> String {
        "Your account will be deleted on " + formatted(applyAfter, timeZone: timeZone, locale: locale) + "."
    }
}

// MARK: - MR-179: where a held deletion came from

/// What a hold request (`APIClient.requestDeletionHold`) was answered with.
/// Which of the two decides what signing out of this app does to the hold.
enum DeletionHoldAnswer: Equatable, Sendable {
    /// 202 `change_pending`: THIS request filed it, tied to this session.
    case filed(PendingDeletion)
    /// 409 `change_already_pending` naming the hold that already waits. Filed
    /// earlier, perhaps on the website or another device: not known to be
    /// this session's.
    case alreadyPending(PendingDeletion)

    var pending: PendingDeletion {
        switch self {
        case .filed(let pending), .alreadyPending(let pending): return pending
        }
    }

    /// This request filed it.
    var filedHere: Bool {
        if case .filed = self { return true }
        return false
    }
}

extension DeletionHold {
    /// A hold request's answer, from its status and body (#883 final):
    ///   * a 2xx naming a hold (`202 { pending, code: "change_pending",
    ///     stepUp: { pendingChangeId, applyAfter } }`) is `.filed`: this request
    ///     filed it, for this session;
    ///   * a 409 `change_already_pending` naming one (in `stepUp`,
    ///     `details.stepUp` or `details`) is `.alreadyPending`.
    /// Nil for anything else: the refusals keep their own classification, and
    /// a 2xx that names no hold is never one.
    static func holdRequestAnswer(status: Int, body: Data) -> DeletionHoldAnswer? {
        if (200..<300).contains(status) {
            return holdAnswer(status: status, body: body).map(DeletionHoldAnswer.filed)
        }
        if case .alreadyPending(let pending?) = classifyRefusal(status: status, body: body) {
            return .alreadyPending(pending)
        }
        return nil
    }

    /// `APIClient.requestDeletionHold`'s refusal hook, for every non-2xx
    /// (MR-2389: here, so the tests run the very hook the request uses). The
    /// proof refusals first (`DeletionRefusal`, 2FA), then a 409
    /// `change_already_pending` that NAMES the hold, which is an answer
    /// (`DeletionHoldNamed`, `.alreadyPending`), not a failure, then the
    /// hold's own 409 / 429 / 503 (#883 final), each told apart by status and
    /// code.
    static func holdRequestRefusal(status: Int, body: Data) -> Error? {
        if let refusal = DeletionRefusal.classify(status: status, body: body) { return refusal }
        if let named = holdRequestAnswer(status: status, body: body) { return DeletionHoldNamed(answer: named) }
        return classifyRefusal(status: status, body: body)
    }
}

/// A 409 `change_already_pending` that names the hold: an answer to the hold
/// request, not a failure (`APIClient.requestDeletionHold`'s refusal hook).
struct DeletionHoldNamed: Error, Sendable {
    let answer: DeletionHoldAnswer
}

/// The account's held deletion as this app knows it, and whether THIS app
/// session filed it.
///
/// The server voids a held deletion on sign-out ONLY when the session that
/// signs out is the one that filed it (birdo-web #883,
/// `pending-change.service.ts` `heldDeletionVoided`): a hold filed on the
/// website has no session and is never voided by a sign-out, and one filed on
/// another device survives this device's sign-out. (A sign-out everywhere, a
/// number rotation, a password change and turning 2FA on or off cancel every
/// hold at once.) So:
///   * `filedHereId` is set ONLY by this session's own 202 (`filed`), never by
///     a 409 for a hold already waiting or by a list read: neither says which
///     session filed it;
///   * a list read or a 409 keeps it only while it names the SAME hold;
///   * a confirmed cancel of it, the end of the session and a list without
///     the hold drop it.
/// `HeldDeletionController` keeps `filedHereId` in the keychain with the
/// session (`HeldDeletionIdStore`), so a cold start still knows it.
struct HeldDeletionRecord: Equatable, Sendable {
    /// The deletion on hold, nil for none known.
    private(set) var pending: PendingDeletion?
    /// The id this session's own 202 named, while that hold may still wait.
    private(set) var filedHereId: String?

    init() {}

    /// A cold start: the id this session filed, read back from the keychain.
    init(restoredFiledHereId: String?) {
        let id = restoredFiledHereId?.trimmingCharacters(in: .whitespacesAndNewlines)
        filedHereId = (id?.isEmpty == false) ? id : nil
    }

    /// The hold that waits was filed by THIS session: signing out of this app
    /// voids it.
    var filedHere: Bool {
        guard let pending, let filedHereId else { return false }
        return pending.id == filedHereId
    }

    /// This session's own 202 `change_pending`.
    mutating func filed(_ hold: PendingDeletion) {
        pending = hold
        filedHereId = hold.id
    }

    /// A hold named by anything but this session's 202 (a 409, a list read):
    /// filed here only if it is the very hold this session filed.
    mutating func named(_ hold: PendingDeletion) {
        if filedHereId != hold.id { filedHereId = nil }
        pending = hold
    }

    mutating func apply(_ answer: DeletionHoldAnswer) {
        switch answer {
        case .filed(let hold): filed(hold)
        case .alreadyPending(let hold): named(hold)
        }
    }

    /// `GET /auth/pending-changes` answered; nil: no deletion waits.
    mutating func listed(_ hold: PendingDeletion?) {
        if let hold {
            named(hold)
        } else {
            pending = nil
            filedHereId = nil
        }
    }

    /// The server confirmed the cancel of `id` (or that it was gone already).
    /// Anything else known stays until the list says otherwise.
    mutating func cancelled(id: String) {
        if pending?.id == id { pending = nil }
        if filedHereId == id { filedHereId = nil }
    }

    /// The session ended (sign-out, expiry, deletion): the next one inherits nothing.
    mutating func sessionEnded() {
        pending = nil
        filedHereId = nil
    }

    /// What the sign-out confirmation says. `listAnswered`: the server
    /// answered a list read for this sign-out, so no hold known means none.
    func signOutNotice(listAnswered: Bool) -> SignOutHoldNotice {
        if let pending { return filedHere ? .filedHere(pending) : .notFiledHere(pending) }
        if listAnswered { return .nothingWaiting }
        // A cold start that could not read the list still knows the hold this
        // session filed: a sign-out cancels it first.
        if let filedHereId { return .filedHereUnchecked(id: filedHereId) }
        return .unchecked
    }
}

/// What the sign-out confirmations (Home and Profile) say about a held deletion.
enum SignOutHoldNotice: Equatable, Sendable {
    /// The server says no deletion waits.
    case nothingWaiting
    /// This session filed it: signing out of this app cancels it (and the app
    /// cancels it itself first, `SignOutHold.prepareSignOut`).
    case filedHere(PendingDeletion)
    /// Waiting, and not known to be this session's (a list read, a 409):
    /// signing out of this app may leave it.
    case notFiledHere(PendingDeletion)
    /// The list could not be read, but this session filed a hold (its id came
    /// back from the keychain at a cold start). Its date is not known; a
    /// sign-out cancels that id first.
    case filedHereUnchecked(id: String)
    /// The list could not be read in time and nothing is known. Sign-out
    /// stays allowed.
    case unchecked

    var hold: PendingDeletion? {
        switch self {
        case .filedHere(let hold), .notFiledHere(let hold): return hold
        case .nothingWaiting, .filedHereUnchecked, .unchecked: return nil
        }
    }

    /// The id "Cancel deletion" cancels, nil when there is none to cancel.
    var cancellableId: String? {
        switch self {
        case .filedHere(let hold), .notFiledHere(let hold): return hold.id
        case .filedHereUnchecked(let id): return id
        case .nothingWaiting, .unchecked: return nil
        }
    }

    /// Both confirmations offer "Cancel deletion" whenever there is a hold to cancel.
    var offersCancelDeletion: Bool { cancellableId != nil }

    /// The id the app cancels before signing out: only a hold this session filed.
    var cancelsBeforeSignOut: String? {
        switch self {
        case .filedHere(let hold): return hold.id
        case .filedHereUnchecked(let id): return id
        case .nothingWaiting, .notFiledHere, .unchecked: return nil
        }
    }

    /// The confirmation's line about the hold, nil when none waits.
    func message(timeZone: TimeZone = .current, locale: Locale = .current) -> String? {
        switch self {
        case .nothingWaiting:
            return nil
        case .filedHere(let hold):
            return DeletionHold.signOutWarning(applyAfter: hold.applyAfter, timeZone: timeZone, locale: locale)
        case .notFiledHere(let hold):
            return DeletionHold.signOutKeepsWarning(applyAfter: hold.applyAfter, timeZone: timeZone, locale: locale)
        case .filedHereUnchecked, .unchecked:
            return DeletionHold.signOutUnchecked
        }
    }
}

/// How `POST /auth/pending-changes/:id/cancel` ended.
enum HeldDeletionCancelOutcome: Equatable, Sendable {
    /// 200: the server cancelled it.
    case cancelled
    /// 404 `pending_change_not_found`: gone already.
    case alreadyGone
    /// Not cancelled; the sentence says why.
    case failed(String)
    /// A definitive 401: the session is over and the app signed out.
    case sessionEnded
}

/// List reads may answer out of order (Profile's opening read, the app-active
/// refresh, a number rotation, a sign-out's own read). Each read takes a
/// number when it starts, and an answer older than the last state applied is
/// dropped. A write (filed, cancelled, session end) is newer than every read
/// started before it.
struct HeldDeletionReadOrder: Equatable, Sendable {
    enum Admission: Equatable, Sendable {
        /// The newest answer yet: apply it.
        case apply
        /// Something newer is already applied: what is known is at least as fresh.
        case superseded
    }

    private(set) var issued: UInt64 = 0
    private(set) var applied: UInt64 = 0

    mutating func begin() -> UInt64 {
        issued += 1
        return issued
    }

    mutating func wrote() {
        issued += 1
        applied = issued
    }

    mutating func admit(_ ticket: UInt64) -> Admission {
        guard ticket > applied else { return .superseded }
        applied = ticket
        return .apply
    }
}

/// Where the id this session filed is kept, with the session. `KeychainService`
/// conforms (host-only item `held_deletion_filed_here`, dropped by `clear()`).
protocol HeldDeletionIdStore: AnyObject {
    var heldDeletionFiledHereId: String? { get }
    func setHeldDeletionFiledHere(_ id: String?)
}

/// The held deletion of the signed-in session. Every read, write and cancel
/// goes through here: AuthViewModel forwards to it and publishes `record` and
/// `isCancelling` for the views. Foundation-only, so the tests drive it with a
/// fake server and an in-memory store.
@MainActor
final class HeldDeletionController {
    /// The two routes: `APIClient.pendingDeletion` and `cancelPendingChange(id:)`.
    struct Server {
        /// `GET /auth/pending-changes`: the held deletion, nil for none; throws on failure.
        var list: @MainActor () async throws -> PendingDeletion?
        /// `POST /auth/pending-changes/:id/cancel`: false for 404 (gone already).
        var cancel: @MainActor (String) async throws -> Bool
    }

    private let server: Server
    private let store: any HeldDeletionIdStore
    /// AuthViewModel.logoutIfUnauthorized: true when the error ended the session.
    private let endsSession: @MainActor (Error) -> Bool
    /// A session is signed in now (an answer that lands after a sign-out is dropped).
    private let isSignedIn: @MainActor () -> Bool
    /// After every change of `record`, `isCancelling` or `listAnswered`.
    var onChange: (@MainActor () -> Void)?

    private(set) var record = HeldDeletionRecord()
    private(set) var isCancelling = false
    /// MR-2389: a list read has answered (and was applied) in this session,
    /// so "no hold" is known rather than assumed. The deletion dialog's hold
    /// step says "signing out cancels the deletion" only then
    /// (`DeletionHold.holdStepSignOutNote`), as Android #623's
    /// `pendingDeletionListAnswered`.
    private(set) var listAnswered = false
    private var order = HeldDeletionReadOrder()
    private var inFlight: (id: String, task: Task<HeldDeletionCancelOutcome, Never>)?
    private let cancelTimeout: Duration

    static let unreachableMessage = "Unable to reach server. Check your connection."
    static let cancelFailedMessage = "The deletion could not be cancelled. Please try again."

    /// MR-2389 (R2): the longest one cancel may take, a token refresh inside
    /// it included. The request alone could take 30 s (60 s for the resource)
    /// plus a refresh, with the sign-out confirmation waiting on it. Past the
    /// cap the cancel FAILED (`unreachableMessage`): a sign-out waiting on it
    /// stops with Try again / Sign out anyway. Android #623's
    /// `HOLD_CANCEL_TIMEOUT_MS`.
    nonisolated static let cancelCap: Duration = .seconds(10)

    init(server: Server,
         store: any HeldDeletionIdStore,
         endsSession: @escaping @MainActor (Error) -> Bool,
         isSignedIn: @escaping @MainActor () -> Bool,
         cancelTimeout: Duration = HeldDeletionController.cancelCap) {
        self.server = server
        self.store = store
        self.endsSession = endsSession
        self.isSignedIn = isSignedIn
        self.cancelTimeout = cancelTimeout
    }

    /// A cold start with a live session: the id this session filed comes back
    /// from the store. Nothing is shown until a list read names that hold.
    func restore() {
        record = HeldDeletionRecord(restoredFiledHereId: store.heldDeletionFiledHereId)
        listAnswered = false
        onChange?()
    }

    /// A sign-in: a new session has filed nothing, whatever an earlier one left.
    func startSession() {
        order.wrote()
        record = HeldDeletionRecord()
        listAnswered = false
        store.setHeldDeletionFiledHere(nil)
        onChange?()
    }

    /// A local sign-out or expiry.
    func endSession() {
        order.wrote()
        inFlight = nil
        isCancelling = false
        listAnswered = false
        update { $0.sessionEnded() }
    }

    /// A hold request named a hold. Filed here only for this session's own 202.
    func show(_ answer: DeletionHoldAnswer) {
        order.wrote()
        update { $0.apply(answer) }
    }

    /// Read the list. True when what is known is now at least as fresh as its
    /// answer; false when it failed, was cancelled or was refused (signed out,
    /// a cancel in flight when it would start).
    ///
    /// MR-2389 (R3): a read that STARTED before a cancel still applies when
    /// it answers while that cancel is out, or after it failed: only a cancel
    /// that succeeds is a write (`performCancel`), and only it makes the
    /// reads started before it stale. As Android #623's `applyRead`.
    @discardableResult
    func read() async -> Bool {
        guard isSignedIn(), !isCancelling else { return false }
        let ticket = order.begin()
        do {
            let listed = try await server.list()
            guard !Task.isCancelled, isSignedIn() else { return false }
            switch order.admit(ticket) {
            case .apply:
                listAnswered = true
                update { $0.listed(listed) }
                return true
            case .superseded:
                return true
            }
        } catch {
            _ = endsSession(error)
            return false
        }
    }

    /// Cancel the hold `id`. One cancel at a time: a second call for the same
    /// id waits for the one in flight and gets its outcome. Capped at
    /// `cancelTimeout` (R2). The read order moves only when it succeeds (R3).
    func cancel(id: String) async -> HeldDeletionCancelOutcome {
        if let running = inFlight {
            let outcome = await running.task.value
            if running.id == id { return outcome }
        }
        isCancelling = true
        onChange?()
        let task = Task<HeldDeletionCancelOutcome, Never> { await self.performCancel(id: id) }
        inFlight = (id: id, task: task)
        let outcome = await task.value
        if inFlight?.task == task { inFlight = nil }
        return outcome
    }

    private func performCancel(id: String) async -> HeldDeletionCancelOutcome {
        do {
            let cancelled = try await CappedCall.run(within: cancelTimeout) { [self] in
                try await server.cancel(id)
            }
            // The server's word: every read started before now is stale.
            order.wrote()
            isCancelling = false
            update { $0.cancelled(id: id) }
            return cancelled ? .cancelled : .alreadyGone
        } catch {
            isCancelling = false
            onChange?()
            if endsSession(error) { return .sessionEnded }
            // Past the cap the hold may or may not be cancelled; the next
            // list read says which. Until then it is NOT said to be.
            let unreachable = error is URLError || error is CappedCall.TimedOut
            return .failed(unreachable ? Self.unreachableMessage : Self.cancelFailedMessage)
        }
    }

    /// Change the record, keeping the store's copy of the filed id in step.
    private func update(_ change: (inout HeldDeletionRecord) -> Void) {
        var next = record
        change(&next)
        if next.filedHereId != record.filedHereId {
            store.setHeldDeletionFiledHere(next.filedHereId)
        }
        record = next
        onChange?()
    }
}

/// MR-179 (hold origin): the decisions of the sign-out confirmations, with the
/// server behind closures so the tests drive them.
enum SignOutHold {
    /// The longest a sign-out waits for a list read.
    static let readWait: Duration = .seconds(3)

    /// The confirmation's dismiss button: "Cancel" would read as cancelling
    /// the deletion.
    static let dismissLabel = "Stay signed in"

    /// Before a sign-out confirmation decides what to say: with no hold known
    /// (a tap right after a cold start, before the list read answered), read
    /// the list first, waiting at most `wait`. True when a hold is already
    /// known or the list answered; false when the read failed or took longer
    /// (the confirmation then says it could not check, and still signs out).
    @MainActor
    static func check(current: @MainActor () -> HeldDeletionRecord,
                      read: @escaping @MainActor @Sendable () async -> Bool,
                      wait: Duration = readWait) async -> Bool {
        if current().pending != nil { return true }
        return await answered(within: wait, read)
    }

    /// `read`'s answer, or false once `wait` has passed first. A read that
    /// answers later still updates what the app knows; only this wait ends.
    static func answered(within wait: Duration,
                         _ read: @escaping @MainActor @Sendable () async -> Bool) async -> Bool {
        await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            let once = ResumeOnce(continuation)
            let timer = Task {
                try? await Task.sleep(for: wait)
                once.resume(false)
            }
            Task {
                let answered = await read()
                once.resume(answered)
                timer.cancel()
            }
        }
    }

    /// A cancel, and whether the list read after it answered.
    struct CancelResult: Equatable, Sendable {
        let outcome: HeldDeletionCancelOutcome
        let listAnswered: Bool

        /// MR-2389 (R1): what the confirmation may take as known after this
        /// cancel. A 200 (`.cancelled`) is the server's word that the hold is
        /// gone, so a re-read that failed or ran past its wait is no reason
        /// to fall back on "we couldn't check": nothing is known to wait
        /// unless a list read names a hold (as Android #623 clears
        /// `pendingDeletionUnknown` on a 200). A 404 says only that THAT id
        /// is gone: only an answered read says nothing else waits.
        var listKnown: Bool {
            listAnswered || outcome == .cancelled
        }
    }

    /// The line under "Cancel deletion" for what it did (and for what a
    /// sign-out's cancel-first did), nil when it did not go through.
    static func cancelNote(_ outcome: HeldDeletionCancelOutcome) -> String? {
        switch outcome {
        case .cancelled: return "The deletion is cancelled."
        case .alreadyGone: return "That deletion was no longer waiting."
        case .failed, .sessionEnded: return nil
        }
    }

    /// "Cancel deletion" (and a sign-out's cancel-first): cancel `id`, then
    /// read the list again, waiting at most `wait`. Only a read that answered
    /// may say that nothing waits any more.
    @MainActor
    static func cancelThenRecheck(_ id: String,
                                  cancel: @MainActor (String) async -> HeldDeletionCancelOutcome,
                                  read: @escaping @MainActor @Sendable () async -> Bool,
                                  wait: Duration = readWait) async -> CancelResult {
        let outcome = await cancel(id)
        switch outcome {
        case .cancelled, .alreadyGone:
            return CancelResult(outcome: outcome, listAnswered: await answered(within: wait, read))
        case .failed, .sessionEnded:
            return CancelResult(outcome: outcome, listAnswered: false)
        }
    }

    /// Sign-out was refused because the cancel before it failed.
    struct Blocked: Equatable, Sendable {
        let reason: String
        /// The hold's date, nil when the list could not be read.
        let applyAfter: Date?
    }

    enum Step: Equatable, Sendable {
        /// Go on and sign out. `cancelled`: what the cancel-first did
        /// (`.cancelled` or `.alreadyGone`, said under the dialog while the
        /// sign-out runs), nil when there was none.
        case signOut(cancelled: HeldDeletionCancelOutcome?)
        /// Nobody is signed out: the deletion is still scheduled.
        case blocked(Blocked)
        /// The cancel went through, but what is known after it is not
        /// "nothing waits" (another hold listed; a 404 whose re-read did not
        /// answer): show what is known now, sign nobody out.
        case reconsider(listAnswered: Bool, cancelled: HeldDeletionCancelOutcome)
        /// The session ended on its own (401).
        case sessionEnded
    }

    /// Sign-out confirmed. A hold THIS session filed (or, after a cold start
    /// that could not read the list, the id it filed) is cancelled first: the
    /// user was told signing out cancels it, and the server's sign-out is best
    /// effort. If that cancel fails (or passes `HeldDeletionController.cancelCap`),
    /// nobody is signed out. After it, the list is read again (at most
    /// `wait`), and the sign-out goes on when nothing is known to wait
    /// (`CancelResult.listKnown`, MR-2389 R1): after a 200 that holds unless
    /// the re-read NAMES a hold, so a slow or failed re-read neither stops
    /// the sign-out nor asks for a second tap; after a 404 only an answered,
    /// empty list lets it go on (Android #623 `afterCancelFirst`). A hold not
    /// known to be filed here is left alone: its line said signing out may
    /// not cancel it, and offered Cancel deletion.
    @MainActor
    static func prepareSignOut(_ notice: SignOutHoldNotice,
                               current: @MainActor () -> HeldDeletionRecord,
                               cancel: @MainActor (String) async -> HeldDeletionCancelOutcome,
                               read: @escaping @MainActor @Sendable () async -> Bool,
                               wait: Duration = readWait) async -> Step {
        guard let id = notice.cancelsBeforeSignOut else { return .signOut(cancelled: nil) }
        let result = await cancelThenRecheck(id, cancel: cancel, read: read, wait: wait)
        switch result.outcome {
        case .failed(let reason):
            return .blocked(Blocked(reason: reason, applyAfter: notice.hold?.applyAfter))
        case .sessionEnded:
            return .sessionEnded
        case .cancelled, .alreadyGone:
            let known = result.listKnown
            let after = current().signOutNotice(listAnswered: known)
            return after == .nothingWaiting
                ? .signOut(cancelled: result.outcome)
                : .reconsider(listAnswered: known, cancelled: result.outcome)
        }
    }

    /// What the confirmation shows.
    struct DialogText: Equatable, Sendable {
        let message: String
        let confirmLabel: String
        let dismissLabel: String
        let offersCancelDeletion: Bool
        let offersSignOutAnyway: Bool
    }

    /// The confirmation's text for its state. While a cancel-first has failed
    /// (`blocked`), its sentence REPLACES the hold's line: "signing out
    /// cancels the deletion" above "the deletion may then still go ahead"
    /// would contradict itself.
    static func dialogText(opening: String,
                           signOutLabel: String,
                           notice: SignOutHoldNotice,
                           checking: Bool,
                           blocked: Blocked?,
                           timeZone: TimeZone = .current,
                           locale: Locale = .current) -> DialogText {
        if checking {
            return DialogText(message: opening, confirmLabel: "Checking\u{2026}", dismissLabel: dismissLabel,
                              offersCancelDeletion: false, offersSignOutAnyway: false)
        }
        if let blocked {
            let refusal = DeletionHold.signOutCancelFailed(reason: blocked.reason, applyAfter: blocked.applyAfter,
                                                           timeZone: timeZone, locale: locale)
            return DialogText(message: opening + "\n\n" + refusal, confirmLabel: "Try again",
                              dismissLabel: dismissLabel, offersCancelDeletion: false, offersSignOutAnyway: true)
        }
        let line = notice.message(timeZone: timeZone, locale: locale)
        return DialogText(message: line.map { opening + "\n\n" + $0 } ?? opening, confirmLabel: signOutLabel,
                          dismissLabel: dismissLabel, offersCancelDeletion: notice.offersCancelDeletion,
                          offersSignOutAnyway: false)
    }
}

/// MR-2389 (R2): an async call with a hard cap. The call runs in its own
/// task, so one that does not stop when cancelled (a coalesced token refresh)
/// cannot hold the cap open; past the cap that task is cancelled and its late
/// answer dropped.
enum CappedCall {
    /// The cap passed before `operation` answered.
    struct TimedOut: Error, Equatable, Sendable {}

    static func run<T: Sendable>(within limit: Duration,
                                 _ operation: @escaping @MainActor @Sendable () async throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<T, Error>) in
            let race = FirstAnswer(continuation)
            race.add(Task {
                do {
                    let value = try await operation()
                    race.resolve(.success(value))
                } catch {
                    race.resolve(.failure(error))
                }
            })
            race.add(Task {
                try? await Task.sleep(for: limit)
                race.resolve(.failure(TimedOut()))
            })
        }
    }
}

/// Resumes a throwing continuation once, with whichever task answers first,
/// and cancels the others.
private final class FirstAnswer<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Error>?
    private var tasks: [Task<Void, Never>] = []

    init(_ continuation: CheckedContinuation<T, Error>) {
        self.continuation = continuation
    }

    func add(_ task: Task<Void, Never>) {
        lock.lock()
        let answered = continuation == nil
        if !answered { tasks.append(task) }
        lock.unlock()
        if answered { task.cancel() }
    }

    func resolve(_ result: Result<T, Error>) {
        lock.lock()
        let pending = continuation
        continuation = nil
        let others = tasks
        tasks = []
        lock.unlock()
        guard let pending else { return }
        others.forEach { $0.cancel() }
        pending.resume(with: result)
    }
}

/// Resumes a continuation once, whichever of two tasks gets there first.
private final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Bool, Never>?

    init(_ continuation: CheckedContinuation<Bool, Never>) {
        self.continuation = continuation
    }

    func resume(_ value: Bool) {
        lock.lock()
        let pending = continuation
        continuation = nil
        lock.unlock()
        pending?.resume(returning: value)
    }
}
