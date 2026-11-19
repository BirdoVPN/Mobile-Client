import Foundation

// MR-2287 (A-M37). Foundation-only BY DESIGN, so FailureCountsTests can pin
// the consent gate and the payload in the un-hosted test bundle; the call
// sites (VpnViewModel, VPNManager) cannot be compiled there.

/// Why a dial failed, as a closed set of literals: the ONLY thing a failure
/// count records.
///
/// Every case is a fixed string chosen here. Nothing is ever interpolated
/// into one, so a count cannot carry an account, a token, a key, an address,
/// a server or a time: the stored form has nowhere to put one.
enum DialFailure: String, CaseIterable, Codable, Sendable {
    // Post-quantum: one for one with `PQFailure`, same codes.
    case pqServerNotPostQuantum = "pq_server_not_pq"
    case pqCiphertextMissing = "pq_ciphertext_missing"
    case pqCiphertextMalformed = "pq_ciphertext_malformed"
    case pqCiphertextWrongSize = "pq_ciphertext_wrong_size"
    case pqNonceInvalid = "pq_nonce_invalid"
    case pqKeypairUnavailable = "pq_keypair_unavailable"
    case pqDeriveFailed = "pq_derive_failed"
    /// This device could not make the connect's ML-KEM keypair
    /// (`APIError.quantumKeyUnavailable`).
    case pqKeygenFailed = "pq_keygen_failed"
    /// The tunnel came up and no handshake answered within the handshake
    /// arm's window: the "tunnel that never handshakes" of A-M37.
    case tunnelNeverHandshook = "tunnel_never_handshook"
    /// NetworkExtension refused to save or start the VPN configuration
    /// (`VPNManagerError.setupRefused`), the system prompt declined included.
    case setupRefused = "setup_refused"
    /// The shared Keychain could not hold or return the tunnel's keys
    /// (`VPNManagerError.keychainUnavailable`).
    case keychainUnavailable = "keychain_unavailable"

    init(_ failure: PQFailure) {
        switch failure {
        case .serverNotPostQuantum: self = .pqServerNotPostQuantum
        case .ciphertextMissing: self = .pqCiphertextMissing
        case .ciphertextMalformed: self = .pqCiphertextMalformed
        case .ciphertextWrongSize: self = .pqCiphertextWrongSize
        case .nonceInvalid: self = .pqNonceInvalid
        case .keypairUnavailable: self = .pqKeypairUnavailable
        case .deriveFailed: self = .pqDeriveFailed
        }
    }
}

/// What a failure-count report would carry, and nothing else: the schema, the
/// platform, the app version and a count per `DialFailure` code. No account,
/// device or install id, no server, no address, no time.
struct FailureCountsReport: Codable, Equatable, Sendable {
    let schema: Int
    let platform: String
    let appVersion: String
    let counts: [String: Int]
}

/// Opt-in, counts-only failure diagnostics for the iOS and macOS app.
///
/// ## The consent model is Android's crash-report choice
///
/// `AppPreferences.crashReportsEnabled` and `CrashReporting` on Android:
///   - OFF by default, and nothing turns it on except the user.
///   - While off, nothing is captured. Here `record` is a no-op, so no count
///     can predate the opt-in (Android drops an event that predates consent,
///     `CrashReporting.predatesConsent`; here there is none to drop).
///   - Turning it off discards everything held but not yet sent (Android
///     deletes the SDK's outbox, `CrashReporting.discardUnsentReports`).
///   - While off there is nothing to send: `takeReport` is nil.
///
/// Where Android sends a scrubbed crash event to Sentry, this keeps a number
/// per reason code. That is all it can hold.
///
/// ## One app version at a time
///
/// Counts are held for the version that recorded them (`versionKey`). Counts
/// from another version are never reported under this one: an update discards
/// them at the next `record` or `takeReport`, and a report clears what it
/// took. A report's `platform` and `appVersion` are this build's own facts
/// (`platform`, `reportableVersion`), never a caller's text.
///
/// ## Not switched on in this build
///
/// There is no switch for it in the app yet and no destination: whether to
/// show the choice (consent screen and Settings, as on Android) and where a
/// report would go are owner decisions (MR-2287), because either one changes
/// the consent copy, /privacy and the App Store privacy answers. Until then
/// `isEnabled` is false on every device, every `record` is a no-op and
/// `takeReport` is always nil. The failure REASON is already visible without
/// it: it travels with the error and its code is in the message the user sees.
/// `IosFailureDiagnosticsGuardTest` (JVM) pins all of this, this file included.
final class FailureCounts: @unchecked Sendable {
    static let shared = FailureCounts(defaults: .standard)

    /// The user's choice. Absent means off.
    static let enabledKey = "failure_counts_enabled"
    /// `[code: count]`, held only while the choice is on.
    static let countsKey = "failure_counts"
    /// The app version the held counts were recorded under.
    static let versionKey = "failure_counts_version"
    /// Report format version.
    static let schema = 1
    /// A count stops here, on record and again on read. Past it the exact
    /// number tells a diagnosis nothing more, and a bounded number keeps every
    /// report the same shape.
    static let maxCount = 999
    /// The version a report names when the bundle's is not a plain `x.y.z`
    /// (the same sentinel the heartbeat sends, VPNManager.heartbeatClientVersion).
    static let unknownVersion = "0.0.0-unknown"

    /// The platform a report names: decided at build time.
    static var platform: String {
        #if os(macOS)
        return "macos"
        #else
        return "ios"
        #endif
    }

    /// `x.y.z` exactly, or `unknownVersion`: a build string with a suffix
    /// (a build number, an install or device tag) is never reported.
    static func reportableVersion(_ raw: String?) -> String {
        guard let raw, raw.range(of: #"^[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}$"#, options: .regularExpression) != nil else {
            return unknownVersion
        }
        return raw
    }

    private let defaults: UserDefaults
    private let appVersion: String
    private let lock = NSLock()

    /// `appVersion` is the bundle's own; only tests pass another.
    init(defaults: UserDefaults,
         appVersion: String? = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) {
        self.defaults = defaults
        self.appVersion = Self.reportableVersion(appVersion)
    }

    var isEnabled: Bool {
        lock.lock(); defer { lock.unlock() }
        return defaults.bool(forKey: Self.enabledKey)
    }

    /// The user's choice. Turning it off discards every count held.
    func setEnabled(_ enabled: Bool) {
        lock.lock(); defer { lock.unlock() }
        if enabled {
            defaults.set(true, forKey: Self.enabledKey)
        } else {
            defaults.removeObject(forKey: Self.enabledKey)
            clearHeld()
        }
    }

    /// Count one failure, only while the user has opted in. Counts another
    /// version left are discarded first.
    func record(_ failure: DialFailure) {
        lock.lock(); defer { lock.unlock() }
        guard defaults.bool(forKey: Self.enabledKey) else { return }
        var counts = heldCounts()
        counts[failure.rawValue] = min((counts[failure.rawValue] ?? 0) + 1, Self.maxCount)
        defaults.set(counts, forKey: Self.countsKey)
        defaults.set(appVersion, forKey: Self.versionKey)
    }

    /// What this version holds now, by reason. Empty while off.
    var counts: [DialFailure: Int] {
        lock.lock(); defer { lock.unlock() }
        guard defaults.bool(forKey: Self.enabledKey) else { return [:] }
        var out: [DialFailure: Int] = [:]
        for (code, count) in heldCounts() {
            if let failure = DialFailure(rawValue: code) { out[failure] = count }
        }
        return out
    }

    /// The report a sender would deliver; everything held is cleared in the
    /// same step. Nil while the user has not opted in (the gate: nothing is
    /// ever handed to a sender without consent) and when this version counted
    /// nothing. Codes this build does not know are dropped.
    func takeReport() -> FailureCountsReport? {
        lock.lock(); defer { lock.unlock() }
        guard defaults.bool(forKey: Self.enabledKey) else { return nil }
        let held = heldCounts()
        clearHeld()
        guard !held.isEmpty else { return nil }
        return FailureCountsReport(schema: Self.schema, platform: Self.platform,
                                   appVersion: appVersion, counts: held)
    }

    /// This version's counts: known codes only, each in 1...maxCount. Empty
    /// when what is stored was recorded under another version (or none).
    /// Caller holds `lock`.
    private func heldCounts() -> [String: Int] {
        guard defaults.string(forKey: Self.versionKey) == appVersion else { return [:] }
        let stored = defaults.dictionary(forKey: Self.countsKey) as? [String: Int] ?? [:]
        var out: [String: Int] = [:]
        for (code, count) in stored where count > 0 && DialFailure(rawValue: code) != nil {
            out[code] = min(count, Self.maxCount)
        }
        return out
    }

    /// Caller holds `lock`.
    private func clearHeld() {
        defaults.removeObject(forKey: Self.countsKey)
        defaults.removeObject(forKey: Self.versionKey)
    }
}
