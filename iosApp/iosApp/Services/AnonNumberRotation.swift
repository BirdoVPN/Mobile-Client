import Foundation

// MR-1707 (iOS follow-up): the masked anonymous account number and the
// two-phase "replace number" flow, behind a client flag that ships OFF.
//
// Mirrors Desktop-Client #416 against birdo-web #850 (`ANON_NUMBER_MASKING`,
// `ANON_NUMBER_ROTATION`). Foundation-only and compiled into BirdoVPNTests:
// APIClient.swift cannot be, so everything the tests pin (the mask rules, the
// 401 and refusal classification, the step machine, the commit) lives here and
// APIClient only sends.
//
// Both numbers are credentials: nothing here logs one, every type that holds
// one prints `[REDACTED]` (CredentialRedactingModels.swift), and the flow
// clears them on `clear()`.

enum AnonNumberRotation {
    /// The client switch for the "Replace number" entry point. OFF until the
    /// server switches are on and the build is the one that should offer it:
    /// a number replaced on a build that does not know about it is a number the
    /// user never saw (the web server answers 403 while its own switch is off).
    static let isEnabled = false

    /// The Profile entry is shown only for an anonymous account, and only with
    /// the flag on.
    static func entryVisible(flag: Bool = isEnabled, isAnonymous: Bool) -> Bool {
        flag && isAnonymous
    }
}

// MARK: - The mask

/// What the server sends in `accountNumber` once `ANON_NUMBER_MASKING` is on:
/// 24 characters, stars then the last digits (`********************1234`).
/// Desktop's `AccountNumber::is_mask`. A mask is not a credential; the card
/// shows `•••• … 1234`, with no reveal and no copy, and a mask must never be
/// written over the number the keychain holds.
enum AnonNumberMask {
    static let length = 24

    /// Stars first, then ASCII digits only, 24 characters in all.
    static func isMasked(_ raw: String) -> Bool {
        guard raw.count == length else { return false }
        let stars = raw.prefix(while: { $0 == "*" }).count
        guard stars > 0 else { return false }
        return raw.dropFirst(stars).allSatisfy { $0.isASCII && $0.isNumber }
    }

    /// The last digits a mask discloses, nil for anything else.
    static func lastDigits(_ raw: String) -> String? {
        guard isMasked(raw) else { return nil }
        return String(raw.drop(while: { $0 == "*" }))
    }

    /// `•••• … 1234`.
    static func display(lastDigits tail: String) -> String {
        "•••• … \(tail)"
    }

    /// What the keychain may be given as the account number: never a mask, and
    /// never anything carrying the mask's stars or dots. A masked answer from
    /// `/auth/me` leaves the stored number alone.
    static func storable(_ raw: String?) -> String? {
        guard let raw else { return nil }
        if isMasked(raw) || raw.contains("*") || raw.contains("•") { return nil }
        return raw
    }

    /// The copy under the masked card (Desktop's wording).
    static let cardNote = "Shown once at sign-up or when you replace it. Replace it if you lost it."
}

// MARK: - Wire

/// Step 1 body. Optional proofs are OMITTED when nil (synthesized Encodable).
struct RotatePrepareBody: Encodable {
    let currentNumber: String
    let password: String?
    let twoFactorCode: String?
}

/// Step 1 answer: the NEW number, shown once.
struct RotatePrepareResponse: Decodable {
    let newNumber: String
    let expiresInSeconds: Int?
}

#if os(macOS)
private let kRotationDeviceType = "DESKTOP"
private let kRotationPlatform = "MACOS"
#else
private let kRotationDeviceType = "MOBILE"
private let kRotationPlatform = "IOS"
#endif

/// Step 2 body: the whole new number, re-typed, and the device fields every
/// sign-in carries (the confirm mints this device's fresh session).
struct RotateConfirmBody: Encodable {
    let newNumber: String
    let deviceId: String
    let deviceName: String
    let deviceType = kRotationDeviceType
    let platform = kRotationPlatform
    let platformVersion: String
    let appVersion: String
}

/// Step 2 answer: the caller's fresh session (every other one was ended).
struct RotateConfirmResponse: Decodable {
    let ok: Bool?
    let tokens: TokenPairDTO?
}

/// What the server refused, by its own words. Thrown by APIClient's two
/// rotation calls; never carries a number.
enum RotationRefusal: Error, Equatable {
    /// 403 `anon_number_rotation_disabled`.
    case disabled
    /// A 401 that is the handler's verdict (`rotation_proof_invalid`, or any
    /// message but passport's bare "Unauthorized"): sent once, never refreshed.
    case proofRejected
    /// A 401 from the JWT guard (bare "Unauthorized"): refresh once, retry once.
    /// Internal to APIClient; it surfaces as `APIError.unauthorized` if the
    /// retry is refused the same way.
    case sessionExpired
    /// 409 `rotation_not_pending`: expired or never prepared.
    case notPending
    /// 409 `rotation_account_changed`.
    case accountChanged
    /// 400 on confirm: not the pending number (counts toward the proof limit).
    case mismatch
    /// 400 on prepare (not an anonymous account, or a malformed body).
    case badRequest
    /// 429.
    case rateLimited
}

enum AnonNumberRotationWire {
    enum Step { case prepare, confirm }

    /// The two 401 sources, as Desktop's `classify_proof_post_response_after`:
    /// a machine `error` code the handler set wins whatever the wording; failing
    /// that, a message that is anything but the guard's bare "Unauthorized" is
    /// the handler's too. Only a bare one (or an empty body) is an expired token.
    static func classify401(body: Data) -> RotationRefusal {
        let object = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
        let code = trimmed(object?["error"] as? String)
        let message = trimmed(messageText(object?["message"]))
        if let code, code.caseInsensitiveCompare("unauthorized") != .orderedSame {
            return .proofRejected
        }
        if let message, message.caseInsensitiveCompare("unauthorized") != .orderedSame {
            return .proofRejected
        }
        return .sessionExpired
    }

    /// The refusal for one non-2xx answer, or nil when it is none of these (a
    /// 5xx, a 426, anything else: the generic mapping then speaks).
    static func classify(status: Int, body: Data, step: Step) -> RotationRefusal? {
        switch status {
        case 401:
            return classify401(body: body)
        case 403:
            return code(in: body) == "anon_number_rotation_disabled" ? .disabled : nil
        case 409:
            switch code(in: body) {
            case "rotation_not_pending": return .notPending
            case "rotation_account_changed": return .accountChanged
            default: return nil
            }
        case 400:
            return step == .confirm ? .mismatch : .badRequest
        case 429:
            return .rateLimited
        default:
            return nil
        }
    }

    private static func code(in body: Data) -> String? {
        let object = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
        return trimmed(object?["error"] as? String)
    }

    private static func messageText(_ raw: Any?) -> String? {
        if let s = raw as? String { return s }
        if let list = raw as? [String] { return list.joined(separator: " ") }
        return nil
    }

    private static func trimmed(_ s: String?) -> String? {
        guard let t = s?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return nil }
        return t
    }
}

// MARK: - The step machine

/// How a failed step reads to the flow.
enum RotationFailure: Equatable {
    case refusal(RotationRefusal)
    /// No answer reached us (a lost response, offline, a timeout).
    case network
    /// The session itself is gone (the refresh was refused too).
    case sessionEnded
    case other

    static func from(_ error: Error) -> RotationFailure {
        if let refusal = error as? RotationRefusal { return .refusal(refusal) }
        if error is URLError { return .network }
        return .other
    }
}

/// The three steps of the replacement and the screen state between them.
/// Pure: the model calls the API and feeds the outcome in.
struct RotationFlow: Equatable {
    enum Step: Equatable {
        case prove, showNew, confirm, done
        /// A confirm was sent and a later answer says the session or the
        /// pending number is gone: the swap may already have happened.
        case maybeCommitted
    }

    enum Notice: Equatable {
        case disabled
        case proofRejected
        case rateLimited
        case offline
        case notAnAnonymousAccount
        case sessionEnded
        case failed
        case mismatch
        /// 409: start again.
        case expired
        case accountChanged
        /// A 401 or a 409 `rotation_not_pending` after a confirm was sent.
        case maybeActive
        /// Confirm succeeded on the server but this device could not keep the
        /// new session: the number is shown again, sign in with it.
        case storeFailed
    }

    var step: Step = .prove
    /// In memory only: set by `prepared`, dropped by `clear()` and on every
    /// restart.
    var newNumber: String?
    /// The unticked "I've saved it" toggle. Step 2 cannot advance without it.
    var saved = false
    var notice: Notice?
    /// After `done`: false when the new session could not be stored locally.
    var storedLocally = true
    /// Confirm requests sent in this flow. Once one was sent, the server may
    /// already have swapped the number (Desktop #417).
    private(set) var confirmsSent = 0

    var canContinueFromShowNew: Bool { step == .showNew && saved && newNumber != nil }

    mutating func prepared(newNumber: String) {
        self.newNumber = newNumber
        saved = false
        notice = nil
        step = .showNew
    }

    /// Step 2 -> 3, only once the number is marked saved.
    mutating func continueToConfirm() {
        guard canContinueFromShowNew else { return }
        notice = nil
        step = .confirm
    }

    /// Step 3 -> 2, to look at the number again.
    mutating func backToShowNew() {
        guard step == .confirm, newNumber != nil else { return }
        notice = nil
        step = .showNew
    }

    mutating func prepareFailed(_ failure: RotationFailure) {
        // Nothing was prepared: stay on step 1 whatever the cause.
        step = .prove
        newNumber = nil
        saved = false
        notice = Self.noticeFor(failure, atConfirm: false)
    }

    /// Call when a confirm request is about to be sent.
    mutating func confirmSent() { confirmsSent += 1 }

    /// After a SENT confirm, a 401 (verdict, expired guard, ended session)
    /// or a 409 `rotation_not_pending` may mean the server already switched to
    /// the new number and revoked the old session.
    private func mayHaveCommitted(_ failure: RotationFailure) -> Bool {
        guard confirmsSent >= 1 else { return false }
        switch failure {
        case .refusal(.notPending), .refusal(.proofRejected), .refusal(.sessionExpired), .sessionEnded:
            return true
        default:
            return false
        }
    }

    mutating func confirmFailed(_ failure: RotationFailure) {
        if mayHaveCommitted(failure) {
            // Not a restart: the user holds the new number and must sign in
            // with it. Nothing more is sent from this session.
            // The new number stays visible: it is the way back in.
            step = .maybeCommitted
            notice = .maybeActive
            return
        }
        switch failure {
        case .refusal(.notPending), .refusal(.accountChanged), .refusal(.disabled):
            // The pending number is gone (or the switch went off): the number in
            // memory is dead. Restart at step 1.
            step = .prove
            newNumber = nil
            saved = false
        default:
            // A 400 mismatch, a 429, a lost response, a 401 verdict: the number
            // stays in memory and the user retries on step 3.
            step = .confirm
        }
        notice = Self.noticeFor(failure, atConfirm: true)
    }

    /// The server swapped the number. `storedLocally == false` keeps the new
    /// number on screen (it is the only way back in).
    mutating func confirmed(storedLocally: Bool) {
        step = .done
        self.storedLocally = storedLocally
        notice = storedLocally ? nil : .storeFailed
        saved = false
        if storedLocally { newNumber = nil }
    }

    /// Dismiss: drop the number and the toggle.
    mutating func clear() {
        newNumber = nil
        saved = false
        notice = nil
        step = .prove
        storedLocally = true
        confirmsSent = 0
    }

    static func noticeFor(_ failure: RotationFailure, atConfirm: Bool) -> Notice {
        switch failure {
        case .refusal(.disabled): return .disabled
        case .refusal(.proofRejected): return .proofRejected
        case .refusal(.sessionExpired): return .sessionEnded
        case .refusal(.notPending): return .expired
        case .refusal(.accountChanged): return .accountChanged
        case .refusal(.mismatch): return .mismatch
        case .refusal(.badRequest): return atConfirm ? .mismatch : .notAnAnonymousAccount
        case .refusal(.rateLimited): return .rateLimited
        case .network: return .offline
        case .sessionEnded: return .sessionEnded
        case .other: return .failed
        }
    }
}

extension RotationFlow.Notice {
    /// Copy for the sheet. Never names a number.
    var message: String {
        switch self {
        case .disabled: return "Replacing the account number is not available yet."
        case .proofRejected: return "Those details were not accepted. Check them and try again."
        case .rateLimited: return "Too many attempts. Please try again later."
        case .offline: return "No answer from the server. Check your connection and try again."
        case .notAnAnonymousAccount: return "Only an anonymous account has a number to replace."
        case .sessionEnded: return "Your session ended. Sign in again."
        case .failed: return "Something went wrong. Please try again."
        case .mismatch: return "That is not the new number. Check it and try again."
        case .expired: return "The new number expired or was never prepared. Start again."
        case .accountChanged: return "The account changed since the number was prepared. Start again."
        case .maybeActive:
            return "Your new number may already be active. Sign in with the new number you saved."
        case .storeFailed:
            return "Your number was replaced, but this device could not save the new session. "
                + "Save the new number below and sign in with it."
        }
    }
}

// MARK: - The commit

/// The keychain writes a successful confirm needs. `KeychainService` conforms
/// (in ReplaceNumberSheet.swift); the tests use an in-memory store.
protocol RotationCommitStore: AnyObject {
    @discardableResult func saveRotationSession(accessToken: String, refreshToken: String) -> Bool
    @discardableResult func saveAnonymousId(_ id: String) -> Bool
}

enum AnonNumberRotationCommit {
    /// After the server swapped the number: the old tokens are dead, so the new
    /// pair goes in first (one retry, like every sign-in), then the NEW number
    /// for the Profile card. False when the tokens could not be kept.
    static func commit(newNumber: String,
                       accessToken: String,
                       refreshToken: String,
                       to store: any RotationCommitStore) -> Bool {
        guard SessionPersistence.saveWithRetry({
            store.saveRotationSession(accessToken: accessToken, refreshToken: refreshToken)
        }) else { return false }
        if let storable = AnonNumberMask.storable(newNumber) {
            store.saveAnonymousId(storable)
        }
        return true
    }
}
