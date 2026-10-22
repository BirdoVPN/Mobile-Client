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
/// who later breaks Curve25519 but does not hold this device's secret key.
///
/// It is NOT a complete "harvest now, decrypt later" defence:
///
/// - The ML-KEM secret key is LONG-LIVED: one keypair per install, kept
///   until it is replaced. Decapsulation is deterministic in (sk, ct), so
///   whoever obtains the key and has recorded the ciphertexts recovers every
///   PSK derived for it, past AND future. One keypair per connect (O0e) is
///   not built.
/// - The server sends the PSK on to the VPN server over TLS that is not yet
///   post-quantum (MR-1444), so a recording of that link plus a quantum
///   computer recovers it without touching ML-KEM.
/// - Multi-hop's entry-to-exit leg carries no PQ PSK at all.
///
/// Full analysis: birdo-web `docs/PQ-THREAT-MODEL.md` §4 and §6.2.
///
/// ## Where the secret key lives
///
/// On iOS we store it in the **app-only Keychain** with
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` and
/// `kSecAttrSynchronizable = false`, so it is bound to this device and
/// never syncs to iCloud Keychain. The PacketTunnel extension never reads
/// it — only the host app needs PSK derivation, and the derived 32-byte
/// PSK is what gets handed to the extension via the shared keychain.
///
/// In memory it is re-read from the Keychain per connect, as on Android, and
/// held only in a `PQSecretKey`, which is wiped once the decapsulation is done
/// (MR-2123). Nothing keeps it for the life of the process.
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

    // The keypair blob (pk||sk) is the host-only keychain item
    // `HostKeychainItem.pqKeypair` (account `birdo_pq_v1_keypair`, its own
    // service `app.birdo.vpn.pq` so a PQ-only purge doesn't nuke other items).
    // MR-1729: kept in the host-only access group through KeychainService, so
    // the PacketTunnel extension cannot read the ML-KEM secret key.

    private let queue = DispatchQueue(label: "app.birdo.vpn.pq", qos: .userInitiated)

    private typealias Keypair = (pk: Data, sk: PQSecretKey)

    /// The keypair whose public key `clientPublicKeyBase64()` last handed out
    /// for a /connect, kept for exactly ONE `tryDecapsulate`. Guarded by `queue`.
    ///
    /// MR-2123: it replaces `cachedKeypair`, which held the 3168-byte secret key
    /// as `Data` for the life of the process and never wiped it (resetting it to
    /// nil freed the storage as it was). Whoever ends a hand-off's life wipes
    /// its secret key: the decapsulation that consumes it, the next upload that
    /// replaces it, and `resetPersistedKeypair()`.
    ///
    /// MR-2117: it is also what keeps the decapsulation on the uploaded pair.
    /// Without a process-wide cache, a Keychain write that failed would make a
    /// second read generate a DIFFERENT pair: the server encapsulated to pk_A,
    /// the client decapsulated with sk_B, and implicit rejection gave a PSK the
    /// server does not have (the Android defect of the same row).
    private var handOff: Keypair?

    /// The public key `clientPublicKeyBase64()` last returned. Public, so unlike
    /// `handOff` it is kept: a decapsulation that has to fall back to the
    /// Keychain is still checked against it, and refuses instead of deriving a
    /// PSK the server cannot match. Guarded by `queue`.
    private var uploadedPublicKey: Data?

    private(set) var currentMode: Mode = .disabled

    private init() {}

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

    /// Returns the Base64 ML-KEM-1024 client public key, generating + persisting
    /// the keypair on first call. Returns `nil` only if both keychain
    /// persistence AND in-memory generation fail (extremely unlikely).
    func clientPublicKeyBase64() -> String? {
        return queue.sync { () -> String? in
            guard let kp = loadOrGenerateKeypair() else { return nil }
            // MR-2117: the decapsulation for this /connect uses exactly this
            // pair. An unconsumed earlier hand-off is wiped, not dropped.
            handOff?.sk.wipe()
            handOff = kp
            uploadedPublicKey = kp.pk
            return kp.pk.base64EncodedString()
        }
    }

    /// Try to derive a bilateral PQ PSK from the server response. Returns
    /// `nil` when the server didn't include a ciphertext (legacy path) or
    /// our local keypair is missing — caller should then fall back to the
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
        // The Keychain is read only when no hand-off was taken for this connect.
        guard let kp = handedOff ?? queue.sync(execute: { loadOrGenerateKeypair() }) else {
            debugLog("BirdoPQ: no client keypair available")
            return nil
        }
        // MR-2123: the secret key does not outlive this derivation.
        defer { kp.sk.wipe() }

        // MR-2117: the server encapsulated to the UPLOADED public key. Any
        // other pair can only derive a PSK the server does not have (implicit
        // rejection), seen as a handshake that never completes; refuse here,
        // where the cause is known.
        if let uploaded = queue.sync(execute: { uploadedPublicKey }), uploaded != kp.pk {
            debugLog("BirdoPQ: the keypair available to decapsulate is not the one whose public key was uploaded — bilateral PQ aborted")
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

    /// Permanently delete the persisted keypair. Use on user logout.
    func resetPersistedKeypair() {
        queue.sync {
            // MR-2123: an unconsumed hand-off still holds the secret key.
            handOff?.sk.wipe()
            handOff = nil
            uploadedPublicKey = nil
            currentMode = .disabled
            deleteStoredBlob()
        }
    }

    // MARK: - Private

    /// The Keychain's keypair, or a freshly generated (and persisted) one.
    /// Runs on `queue`. Every call returns a NEW `PQSecretKey`, which the
    /// caller owns and wipes.
    private func loadOrGenerateKeypair() -> Keypair? {
        dispatchPrecondition(condition: .onQueue(queue))

        // The KEM validates stored keys now. `ml-kem` enforces FIPS 203
        // §7.3 — the 3168-byte expanded decapsulation key embeds H(ek),
        // recomputed on load and compared — where the implementation that
        // shipped up to 1.4.29 checked only the length. A key that fails
        // that check is NOT recoverable, so keeping it would fail every
        // connect forever: derive_psk returns BIRDO_PQ_ERR_BAD_SECRET_KEY,
        // tryDecapsulate returns nil, and VPNManager throws
        // quantumHandshakeFailed. Discard and re-key instead; the server
        // re-pins the new public key on the next handshake, so the only
        // cost is one extra keygen. Parity with Android
        // BirdoPqManager.loadOrGenerateKeypair. (MR-2121: the triage also
        // discards a pair whose public key is not the one in its secret key.)
        let stored = readStoredBlob()
        let decision = StoredPQKeyTriage.decide(
            blob: stored,
            publicKeyLength: Int(BIRDO_PQ_PUBLIC_KEY_LEN),
            secretKeyLength: Int(BIRDO_PQ_SECRET_KEY_LEN),
            secretKeyIsLoadable: Self.secretKeyIsLoadable
        )
        switch decision {
        case .use:
            if let blob = stored {
                // MR-2123: straight from the Keychain's blob into the wipeable
                // buffer, with no `Data` copy of the secret key in between. The
                // blob itself is the buffer the Keychain API returned; Swift
                // cannot wipe that one in place.
                return (
                    pk: Data(blob.prefix(Int(BIRDO_PQ_PUBLIC_KEY_LEN))),
                    sk: PQSecretKey(copying: blob.suffix(Int(BIRDO_PQ_SECRET_KEY_LEN)))
                )
            }
        case .discardAndRekey(let reason):
            debugLog("BirdoPQ: persisted ML-KEM keypair unusable (\(reason)) — deleting it and re-keying")
            deleteStoredBlob()
        case .generateFresh:
            debugLog("BirdoPQ: no persisted ML-KEM keypair — generating fresh (~10–50 ms), impl=%@",
                     Self.implementationName)
        }

        guard let kp = generateKeypair() else { return nil }
        // Best-effort persist; if it fails we still return the in-memory
        // pair so the connect attempt isn't blocked. The hand-off carries it
        // to this connect's decapsulation (MR-2117).
        if !writeKeypairToKeychain(pk: kp.pk, sk: kp.sk) {
            debugLog("BirdoPQ: failed to persist keypair to Keychain — the next connect generates another")
        }
        return kp
    }

    /// Ask the native KEM whether this stored secret key still loads.
    ///
    /// `birdo_pq_stored_key_usable` runs the same FIPS 203 §7.3 check
    /// `birdo_pq_derive_psk` runs, without deriving anything, so the answer is
    /// available BEFORE a connect attempt turns it into a thrown error.
    private static func secretKeyIsLoadable(_ secretKey: Data) -> Bool {
        secretKey.withUnsafeBytes { skPtr -> Bool in
            birdo_pq_stored_key_usable(
                skPtr.baseAddress?.assumingMemoryBound(to: UInt8.self),
                secretKey.count
            ) == 1
        }
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

    /// The raw `pk || sk` Keychain value, or `nil` when there is no item.
    ///
    /// Deliberately does NOT validate: every rule about what a stored blob is
    /// worth lives in `StoredPQKeyTriage`, where CI can see it. This function
    /// used to hold the length check and its own self-heal delete, which is
    /// why the FIPS 203 check had nowhere obvious to go and ended up nowhere.
    private func readStoredBlob() -> Data? {
        KeychainService.shared.hostData(.pqKeypair)
    }

    /// Delete the persisted keypair item, every copy of it.
    private func deleteStoredBlob() {
        KeychainService.shared.deleteHostData(.pqKeypair)
    }

    private func writeKeypairToKeychain(pk: Data, sk: PQSecretKey) -> Bool {
        // MR-2123: the blob is another plaintext copy of the secret key. Built
        // in one buffer and wiped once the Keychain has it.
        var blob = Data(count: pk.count + sk.count)
        defer { blob.withUnsafeMutableBytes { _ = memset_s($0.baseAddress, $0.count, 0, $0.count) } }
        blob.withUnsafeMutableBytes { dst in
            dst.copyBytes(from: pk)
            sk.withUnsafeBytes { src in
                UnsafeMutableRawBufferPointer(rebasing: dst[pk.count...]).copyMemory(from: src)
            }
        }
        // Replaces any previous item. Bound to this device
        // (AfterFirstUnlockThisDeviceOnly, never synced) so a restored backup
        // on a new device has no PQ identity (fresh keypair is generated
        // there). Same posture as the existing WireGuard private key.
        return KeychainService.shared.setHostData(blob, for: .pqKeypair)
    }
}
