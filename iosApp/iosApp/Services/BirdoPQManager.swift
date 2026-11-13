import Foundation
import BirdoPQNative

/// BirdoPQ v1 — ML-KEM-1024 PSK derivation for iOS.
///
/// Wire-format twin of:
///   - Android `BirdoPqManager` (`native/rosenpass-jni/src/handshake.rs`)
///   - Desktop `birdo_pq.rs`
///   - Server `birdo-pq.service.ts`
///
/// Algorithm (canonical):
///
/// ```text
/// ss  = ML-KEM-1024.Decap(sk_client, ct_server)        (32 B)
/// psk = HKDF-SHA-256(IKM = ss, salt = "BirdoPQ-v1-PSK", info = nonce)[..32]
/// ```
///
/// ## Threat model: what BirdoPQ v1 protects, and what it does not
///
/// Each connect derives a fresh PSK from a fresh server encapsulation, and
/// the server withholds that PSK from the connect response. So one session's
/// PSK says nothing about another's, and a recording of this app's API
/// traffic and WireGuard handshakes stays confidential against an adversary
/// who later breaks Curve25519.
///
/// Since MR-703 / MR-1742 the ML-KEM keypair is made PER CONNECT and lives in
/// memory only, so there is no long-lived key for a stolen backup or device
/// file to yield, and the client public key is no longer a stable per-install
/// identifier. It is still NOT a complete "harvest now, decrypt later"
/// defence:
///
/// - Someone who can read this process's memory DURING a connect (between the
///   upload and the decapsulation) recovers that one session's PSK.
/// - The server sends the PSK on to the VPN server over TLS that is not yet
///   post-quantum (MR-1444), so a recording of that link plus a quantum
///   computer recovers it without touching ML-KEM.
/// - Multi-hop's entry-to-exit leg carries no PQ PSK at all.
///
/// Full analysis: birdo-web `docs/PQ-THREAT-MODEL.md` §4 and §6.2.
///
/// ## Where the secret key lives
///
/// Nowhere on disk and not in the Keychain. `clientPublicKeyBase64()` makes a
/// NEW keypair for each `/connect` request and keeps the secret in a
/// `PQSecretKey` for exactly one `tryDecapsulate`, which wipes it (MR-2123);
/// `resetPersistedKeypair()` and a later upload wipe an unconsumed one. The
/// host-only Keychain item (`HostKeychainItem.pqKeypair`) older builds kept is
/// deleted, once per process (`purgeLegacyKeypair()`).
final class BirdoPQManager: @unchecked Sendable {
    static let shared = BirdoPQManager()

    /// What the most recent connect attempt actually used. Drives the
    /// "Quantum Protection: Bilateral / Server / Off" UI badge.
    enum Mode: String, Sendable {
        /// No PSK at all.
        case disabled
        /// Server-provided classical PSK (TLS-delivered random; not HNDL-safe).
        case serverProvided
        /// Bilateral ML-KEM-1024: post-quantum on the app-to-API leg only, not a
        /// complete HNDL defence (see the threat model above).
        case bilateral
    }

    // PFA-M5: the legacy constant `defaultNonceBytes` fallback was removed —
    // `tryDecapsulate` now refuses to derive a PSK against a missing/malformed
    // per-connect nonce (parity with Android BirdoPqManager).

    private let queue = DispatchQueue(label: "app.birdo.vpn.pq", qos: .userInitiated)

    private typealias Keypair = (pk: Data, sk: PQSecretKey)

    /// The keypair whose public key `clientPublicKeyBase64()` last handed out
    /// for a /connect, kept for exactly ONE `tryDecapsulate`, and the ONLY
    /// holder of an ML-KEM secret key (MR-703 / MR-1742). Guarded by `queue`.
    ///
    /// MR-2123: whoever ends a hand-off's life wipes its secret key: the
    /// decapsulation that consumes it, the next upload that replaces it, and
    /// `resetPersistedKeypair()`.
    ///
    /// MR-2117: with no stored copy to fall back to, a decapsulation without it
    /// fails closed. The server encapsulated to this pair's public key, and any
    /// other pair can only derive a PSK the server does not have.
    private var handOff: Keypair?

    /// The once-per-process legacy purge has run. Guarded by `queue`.
    private var legacyPurged = false

    private(set) var currentMode: Mode = .disabled

    private init() {
        // An install that never turns Quantum Protection on is cleaned too:
        // the first touch of the singleton (any connect reads `currentMode`)
        // deletes what an older build kept.
        queue.async { [self] in purgeLegacyKeypairOnQueue() }
    }

    // MARK: - Public API

    /// The ML-KEM implementation the linked `BirdoPQNative` was built with:
    /// `"mlkem1024-rustcrypto"` since 1.4.30, `"mlkem1024-clean"` (PQClean
    /// CLEAN C) before it. A build-time fact, not a probe.
    ///
    /// Android's twin is `RosenpassNative.nativeImplName()`, tagged on Sentry
    /// as `birdo.pq.impl` (`CpuFeatures.TAG_PQ_IMPL`) since the 1.4.25 SIGILL
    /// post-mortem, so a native crash can be attributed to one implementation
    /// or the other. iOS has NO crash reporter today — `debugLog` compiles to
    /// nothing in Release — so this is not yet attached to crash metadata; it
    /// is read at first use, and is the value a reporter would attach when one
    /// is added. Said plainly rather than described as crash attribution it is
    /// not yet.
    static var implementationName: String {
        guard let name = birdo_pq_impl_name() else { return "unknown" }
        return String(cString: name)
    }

    /// Generates a NEW ML-KEM-1024 keypair for one `/connect` request and returns
    /// its Base64 public key. The secret half is held only in the one-shot
    /// hand-off for the `tryDecapsulate` that answers this request. Returns
    /// `nil` if the native generator fails; a connect that asked for quantum
    /// protection then fails closed.
    ///
    /// Call it once per request: a retry of the SAME request re-sends the key it
    /// already has (the server's ciphertext still fits it), while a new connect,
    /// rebuild or reconnect calls this again. Calling it replaces and wipes any
    /// pair still in the hand-off.
    func clientPublicKeyBase64() -> String? {
        return queue.sync { () -> String? in
            purgeLegacyKeypairOnQueue()
            guard let kp = generateKeypair() else { return nil }
            // An unconsumed earlier hand-off is wiped, not dropped.
            handOff?.sk.wipe()
            handOff = kp
            return kp.pk.base64EncodedString()
        }
    }

    /// Try to derive a bilateral PQ PSK from the server response. Returns
    /// `nil` when the server didn't include a ciphertext (legacy path) or
    /// this connect made no keypair — caller should then fall back to the
    /// server-provided classical PSK and call `recordServerProvided()`.
    ///
    /// On success, latches `currentMode == .bilateral`.
    func tryDecapsulate(
        quantumEnabled: Bool,
        rosenpassPublicKeyBase64: String?,
        rosenpassEndpointBase64: String?
    ) -> String? {
        // MR-2117 / MR-2123: take the hand-off before anything can return, so
        // every path out (the early nils included) wipes its secret key.
        let handedOff = queue.sync { () -> Keypair? in
            defer { handOff = nil }
            return handOff
        }
        defer { handedOff?.sk.wipe() }

        guard quantumEnabled, let ctB64 = rosenpassPublicKeyBase64 else {
            return nil
        }
        guard let ct = Data(base64Encoded: ctB64), ct.count == BIRDO_PQ_CIPHERTEXT_LEN else {
            debugLog("BirdoPQ: malformed/missing ciphertext")
            return nil
        }
        // PFA-M5 parity with Android BirdoPqManager: refuse to derive a PSK
        // against a missing/malformed per-connect nonce instead of falling back
        // to a constant. ML-KEM gives a fresh shared secret per encapsulation so
        // the old constant caused no cryptographic nonce reuse, but it dropped
        // per-connect domain separation and let a misconfigured/tampered server
        // silently weaken PQ. Returning nil fails closed — VPNManager.connect
        // throws quantumHandshakeFailed when quantum protection is enabled.
        // MR-745: and bounded to 1...64 bytes like Android, so the server can
        // never hand the native HKDF an empty or unbounded `info`.
        guard let nonce = BirdoPQNonce.decode(rosenpassEndpointBase64) else {
            debugLog("BirdoPQ: server omitted/malformed/out-of-bounds per-connect nonce — bilateral PQ aborted (PFA-M5)")
            return nil
        }
        // MR-703: the hand-off is the only holder. The server encapsulated to
        // the public key THIS connect uploaded; with no pair from that upload
        // (consumed, or wiped by a reset) there is nothing to decapsulate with,
        // and any other key would derive a PSK the server does not have
        // (implicit rejection): refuse here, where the cause is known.
        guard let kp = handedOff else {
            debugLog("BirdoPQ: no keypair from this connect's upload is available — bilateral PQ aborted")
            return nil
        }

        var psk = [UInt8](repeating: 0, count: Int(BIRDO_PQ_PSK_LEN))
        let rc = kp.sk.withUnsafeBytes { skPtr -> Int32 in
            ct.withUnsafeBytes { ctPtr -> Int32 in
                nonce.withUnsafeBytes { noncePtr -> Int32 in
                    birdo_pq_derive_psk(
                        skPtr.baseAddress?.assumingMemoryBound(to: UInt8.self),
                        skPtr.count,
                        ctPtr.baseAddress?.assumingMemoryBound(to: UInt8.self),
                        ct.count,
                        noncePtr.baseAddress?.assumingMemoryBound(to: UInt8.self),
                        nonce.count,
                        &psk,
                        psk.count
                    )
                }
            }
        }
        if rc != BIRDO_PQ_OK {
            debugLog("BirdoPQ: derive_psk failed rc=\(rc)")
            // Wipe partial output before bailing.
            psk.withUnsafeMutableBufferPointer { _ = memset_s($0.baseAddress, $0.count, 0, $0.count) }
            return nil
        }
        let pskData = Data(psk)
        // Wipe the local copy now that we've encoded it.
        psk.withUnsafeMutableBufferPointer { _ = memset_s($0.baseAddress, $0.count, 0, $0.count) }
        queue.sync { currentMode = .bilateral }
        debugLog("BirdoPQ v1 BILATERAL — quantum-resistant PSK derived (32 B, mode=bilateral)")
        return pskData.base64EncodedString()
    }

    /// Latch mode for telemetry when we end up using the server's classical
    /// PSK (still useful, but not HNDL-safe).
    func recordServerProvided() {
        queue.sync { currentMode = .serverProvided }
    }

    /// Latch DISABLED mode (no PSK at all).
    func recordDisabled() {
        queue.sync { currentMode = .disabled }
    }

    /// Delete the legacy host-only Keychain item now (idempotent), on `queue`.
    /// Called at launch BEFORE `migrateHostOnlyItems()` so the migration never
    /// re-writes the old secret key into the host group.
    func purgeLegacyKeypair() {
        queue.sync { purgeLegacyKeypairOnQueue() }
    }

    /// Sign-out and account deletion: wipe an in-flight pair and make sure the
    /// legacy Keychain item is gone (the purge runs again here, not only once
    /// per process, because this is the hygiene the user asked for). The name
    /// is kept for the callers and the parity guards.
    func resetPersistedKeypair() {
        queue.sync {
            // MR-2123: an unconsumed hand-off still holds the secret key.
            handOff?.sk.wipe()
            handOff = nil
            currentMode = .disabled
            deleteStoredBlob()
        }
    }

    // MARK: - Private

    /// Deletes the host-only Keychain item builds before per-connect keys kept
    /// (`pk || sk`, ~4.7 KB). Once per process; runs on `queue`.
    private func purgeLegacyKeypairOnQueue() {
        dispatchPrecondition(condition: .onQueue(queue))
        guard !legacyPurged else { return }
        legacyPurged = true
        deleteStoredBlob()
    }

    private func generateKeypair() -> Keypair? {
        var pk = [UInt8](repeating: 0, count: Int(BIRDO_PQ_PUBLIC_KEY_LEN))
        var sk = [UInt8](repeating: 0, count: Int(BIRDO_PQ_SECRET_KEY_LEN))
        // MR-2123: the native call writes the secret key into `sk`; it is
        // copied into the wipeable buffer, and `sk` is wiped on both outcomes.
        defer { sk.withUnsafeMutableBufferPointer { _ = memset_s($0.baseAddress, $0.count, 0, $0.count) } }
        let rc = birdo_pq_generate_keypair(&pk, pk.count, &sk, sk.count)
        if rc != BIRDO_PQ_OK {
            debugLog("BirdoPQ: generate_keypair failed rc=\(rc)")
            return nil
        }
        return (pk: Data(pk), sk: PQSecretKey(copying: sk))
    }

    /// Delete the legacy keypair item, every copy of it. Deleting an item that
    /// is not there is a no-op.
    private func deleteStoredBlob() {
        KeychainService.shared.deleteHostData(.pqKeypair)
    }
}
