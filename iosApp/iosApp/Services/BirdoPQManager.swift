import Foundation
import BirdoPQNative

/// BirdoPQ v1 — ML-KEM-1024 PSK derivation for iOS.
///
/// Wire-format twin of:
///   - Android `RosenpassManager` (`rosenpass-jni/src/handshake.rs`)
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
/// ## Threat model for the persisted client secret key
///
/// The ML-KEM secret key is the LONG-LIVED client identity for BirdoPQ.
/// Whoever holds it can decrypt any *future* server-encapsulated PSK they
/// observe but CANNOT derive PSKs from sessions that happened before the
/// theft (the server uses fresh randomness in every encapsulation).
///
/// On iOS we store it in the **app-only Keychain** with
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` and
/// `kSecAttrSynchronizable = false`, so it is bound to this device and
/// never syncs to iCloud Keychain. The PacketTunnel extension never reads
/// it — only the host app needs PSK derivation, and the derived 32-byte
/// PSK is what gets handed to the extension via the shared keychain.
final class BirdoPQManager: @unchecked Sendable {
    static let shared = BirdoPQManager()

    /// What the most recent connect attempt actually used. Drives the
    /// "Quantum Protection: Bilateral / Server / Off" UI badge.
    enum Mode: String, Sendable {
        /// No PSK at all.
        case disabled
        /// Server-provided classical PSK (TLS-delivered random; not HNDL-safe).
        case serverProvided
        /// True bilateral ML-KEM-1024 — HNDL-safe.
        case bilateral
    }

    // PFA-M5: the legacy constant `defaultNonceBytes` fallback was removed —
    // `tryDecapsulate` now refuses to derive a PSK against a missing/malformed
    // per-connect nonce (parity with Android RosenpassManager).

    /// Keychain account name used to persist the keypair blob (pk||sk).
    private static let keypairAccount = "birdo_pq_v1_keypair"
    /// Service identifier for the PQ keychain item (separate from the
    /// generic `app.birdo.vpn` service so audit + clear ops can target it
    /// distinctly, and so a PQ-only purge doesn't nuke other items).
    private static let keychainService = "app.birdo.vpn.pq"

    private let queue = DispatchQueue(label: "app.birdo.vpn.pq", qos: .userInitiated)
    private var cachedKeypair: (pk: Data, sk: Data)?
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
        guard let kp = loadOrGenerateKeypair() else { return nil }
        return kp.pk.base64EncodedString()
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
        guard quantumEnabled, let ctB64 = rosenpassPublicKeyBase64 else {
            return nil
        }
        guard let ct = Data(base64Encoded: ctB64), ct.count == BIRDO_PQ_CIPHERTEXT_LEN else {
            debugLog("BirdoPQ: malformed/missing ciphertext")
            return nil
        }
        // PFA-M5 parity with Android RosenpassManager: refuse to derive a PSK
        // against a missing/malformed per-connect nonce instead of falling back
        // to a constant. ML-KEM gives a fresh shared secret per encapsulation so
        // the old constant caused no cryptographic nonce reuse, but it dropped
        // per-connect domain separation and let a misconfigured/tampered server
        // silently weaken PQ. Returning nil fails closed — VPNManager.connect
        // throws quantumHandshakeFailed when quantum protection is enabled.
        guard let nonceB64 = rosenpassEndpointBase64, !nonceB64.isEmpty,
              let nonce = Data(base64Encoded: nonceB64) else {
            debugLog("BirdoPQ: server omitted/malformed per-connect nonce — bilateral PQ aborted (PFA-M5)")
            return nil
        }
        guard let kp = loadOrGenerateKeypair() else {
            debugLog("BirdoPQ: no client keypair available")
            return nil
        }

        var psk = [UInt8](repeating: 0, count: Int(BIRDO_PQ_PSK_LEN))
        let rc = kp.sk.withUnsafeBytes { skPtr -> Int32 in
            ct.withUnsafeBytes { ctPtr -> Int32 in
                nonce.withUnsafeBytes { noncePtr -> Int32 in
                    birdo_pq_derive_psk(
                        skPtr.baseAddress?.assumingMemoryBound(to: UInt8.self),
                        kp.sk.count,
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
            cachedKeypair = nil
            currentMode = .disabled
            deleteStoredBlob()
        }
    }

    // MARK: - Private

    private func loadOrGenerateKeypair() -> (pk: Data, sk: Data)? {
        return queue.sync {
            if let kp = cachedKeypair { return kp }

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
            // RosenpassManager.loadOrGenerateKeypair.
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
                    let kp = (
                        pk: Data(blob.prefix(Int(BIRDO_PQ_PUBLIC_KEY_LEN))),
                        sk: Data(blob.suffix(Int(BIRDO_PQ_SECRET_KEY_LEN)))
                    )
                    cachedKeypair = kp
                    return kp
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
            // pair so the connect attempt isn't blocked.
            if !writeKeypairToKeychain(pk: kp.pk, sk: kp.sk) {
                debugLog("BirdoPQ: failed to persist keypair to Keychain — will regenerate next launch")
            }
            cachedKeypair = kp
            return kp
        }
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

    private func generateKeypair() -> (pk: Data, sk: Data)? {
        var pk = [UInt8](repeating: 0, count: Int(BIRDO_PQ_PUBLIC_KEY_LEN))
        var sk = [UInt8](repeating: 0, count: Int(BIRDO_PQ_SECRET_KEY_LEN))
        let rc = birdo_pq_generate_keypair(&pk, pk.count, &sk, sk.count)
        if rc != BIRDO_PQ_OK {
            debugLog("BirdoPQ: generate_keypair failed rc=\(rc)")
            sk.withUnsafeMutableBufferPointer { _ = memset_s($0.baseAddress, $0.count, 0, $0.count) }
            return nil
        }
        let pkData = Data(pk)
        let skData = Data(sk)
        sk.withUnsafeMutableBufferPointer { _ = memset_s($0.baseAddress, $0.count, 0, $0.count) }
        return (pkData, skData)
    }

    /// The raw `pk || sk` Keychain value, or `nil` when there is no item.
    ///
    /// Deliberately does NOT validate: every rule about what a stored blob is
    /// worth lives in `StoredPQKeyTriage`, where CI can see it. This function
    /// used to hold the length check and its own self-heal delete, which is
    /// why the FIPS 203 check had nowhere obvious to go and ended up nowhere.
    private func readStoredBlob() -> Data? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.keychainService,
            kSecAttrAccount as String: Self.keypairAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return data
    }

    /// Delete the persisted keypair item. Also the upsert half of a write.
    private func deleteStoredBlob() {
        let del: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.keychainService,
            kSecAttrAccount as String: Self.keypairAccount,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        SecItemDelete(del as CFDictionary)
    }

    private func writeKeypairToKeychain(pk: Data, sk: Data) -> Bool {
        var blob = Data(capacity: pk.count + sk.count)
        blob.append(pk)
        blob.append(sk)

        // Delete first so we get clean upsert semantics.
        deleteStoredBlob()

        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.keychainService,
            kSecAttrAccount as String: Self.keypairAccount,
            kSecValueData as String: blob,
            // Bound to this device so a restored backup on a new device has
            // no PQ identity (fresh keypair is generated there). Same posture
            // as the existing WireGuard private key.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }
}
