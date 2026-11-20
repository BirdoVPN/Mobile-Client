import Foundation

// MR-2287 (A-M37). Foundation-only BY DESIGN, and in its own file for the
// same reason PQNonce.swift and PQSecretKey.swift are: `BirdoPQManager.swift`
// imports `BirdoPQNative` and cannot be compiled into the un-hosted unit-test
// bundle, so the decisions below are pinned by PQFailureTests here instead.

/// Why a post-quantum connect failed closed.
///
/// `BirdoPQManager.tryDecapsulate` used to return nil for every one of these,
/// the same nil it returns for a session that is simply not post-quantum, and
/// wrote the reason only to `debugLog`, which compiles to nothing in Release.
/// So on a user's device the cause of a refused connect existed nowhere: not
/// in the error the user saw, not in any log, not in any report. Now each
/// cause is a value that travels with the error (`VPNManagerError
/// .quantumHandshakeFailed(_:)`), is shown as a short code in the message, and
/// is the reason a failure count records (`DialFailure`).
///
/// The raw values are stable, low-cardinality literals: they are shown to the
/// user and may one day be counted off the device, so they must never carry a
/// key, an address, a server or a time. Android's twins are the
/// `FaultReporter` codes in `BirdoPqManager.kt` (`pq_ciphertext_malformed`,
/// `pq_nonce_malformed`, ...); the iOS set is coarser where `BirdoPQNonce`
/// already folds several causes into one nil.
enum PQFailure: String, Error, CaseIterable, Equatable, Sendable {
    /// Quantum Protection is on and the server's answer is not post-quantum
    /// (`quantumEnabled != true`): the requested-vs-granted guard refused a
    /// classical tunnel the UI would have called quantum-protected.
    case serverNotPostQuantum = "pq_server_not_pq"
    /// The server said post-quantum and sent no ciphertext.
    case ciphertextMissing = "pq_ciphertext_missing"
    /// The ciphertext is not Base64.
    case ciphertextMalformed = "pq_ciphertext_malformed"
    /// The ciphertext decodes, but not to ML-KEM-1024's length.
    case ciphertextWrongSize = "pq_ciphertext_wrong_size"
    /// The per-connect nonce is missing, not Base64, or outside 1...64 bytes
    /// (PFA-M5, MR-745: `BirdoPQNonce.decode`).
    case nonceInvalid = "pq_nonce_invalid"
    /// No keypair from THIS connect's upload is held: consumed, wiped by a
    /// reset, or the hand-off belongs to a newer dial (MR-2117, MR-2374).
    case keypairUnavailable = "pq_keypair_unavailable"
    /// The native decapsulation or HKDF returned an error.
    case deriveFailed = "pq_derive_failed"

    /// What the message shows and a failure count records.
    var code: String { rawValue }
}

/// What `BirdoPQManager.tryDecapsulate` does with one /connect answer,
/// decided before any native call. The checks run in the order the manager
/// has always made them, so the FIRST thing wrong is the reason reported.
enum PQDecapsulationPlan: Equatable, Sendable {
    /// The session is not post-quantum. No PQ PSK: the caller uses the
    /// server's classical PSK, or none. Not a failure.
    case classical
    /// Decapsulate `ciphertext` with this connect's secret key, with `nonce`
    /// as the HKDF info.
    case derive(ciphertext: Data, nonce: Data)

    /// - Parameters:
    ///   - quantumEnabled: the server's `quantumEnabled` (false when absent).
    ///   - ciphertextBase64: the server's `rosenpassPublicKey`.
    ///   - nonce: the server's `rosenpassEndpoint` through
    ///     `BirdoPQNonce.decode` (nil when missing, malformed or out of bounds).
    ///   - ciphertextLength: `BIRDO_PQ_CIPHERTEXT_LEN` (1568 for ML-KEM-1024).
    ///   - hasKeypair: whether the hand-off held THIS connect's pair.
    /// - Throws: `PQFailure` when the session is post-quantum and no PSK can
    ///   be derived from it. The caller fails closed with that reason.
    static func make(quantumEnabled: Bool,
                     ciphertextBase64: String?,
                     nonce: Data?,
                     ciphertextLength: Int,
                     hasKeypair: Bool) throws -> PQDecapsulationPlan {
        guard quantumEnabled else { return .classical }
        guard let ciphertextBase64 else { throw PQFailure.ciphertextMissing }
        guard let ciphertext = Data(base64Encoded: ciphertextBase64) else { throw PQFailure.ciphertextMalformed }
        guard ciphertext.count == ciphertextLength else { throw PQFailure.ciphertextWrongSize }
        guard let nonce else { throw PQFailure.nonceInvalid }
        guard hasKeypair else { throw PQFailure.keypairUnavailable }
        return .derive(ciphertext: ciphertext, nonce: nonce)
    }
}
