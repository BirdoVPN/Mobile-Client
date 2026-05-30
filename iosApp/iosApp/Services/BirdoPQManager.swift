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

    /// Default per-connect nonce when the server omits one. Same bytes as
    /// Android `RosenpassManager.DEFAULT_NONCE_BYTES` and desktop
    /// `birdo_pq::DEFAULT_NONCE_BYTES` — change in lockstep or sessions
    /// fail to derive matching PSKs.
    private static let defaultNonceBytes: [UInt8] = Array("BirdoPQ-v1-default-nonce".utf8)

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
            NSLog("BirdoPQ: malformed/missing ciphertext")
            return nil
        }
        let nonce: Data = {
            if let n = rosenpassEndpointBase64, !n.isEmpty,
               let decoded = Data(base64Encoded: n) {
                return decoded
            }
            return Data(Self.defaultNonceBytes)
        }()
        guard let kp = loadOrGenerateKeypair() else {
            NSLog("BirdoPQ: no client keypair available")
            return nil
        }

        var psk = [UInt8](repeating: 0, count: BIRDO_PQ_PSK_LEN)
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
            NSLog("BirdoPQ: derive_psk failed rc=\(rc)")
            // Wipe partial output before bailing.
            psk.withUnsafeMutableBufferPointer { _ = memset_s($0.baseAddress, $0.count, 0, $0.count) }
            return nil
        }
        let pskData = Data(psk)
        // Wipe the local copy now that we've encoded it.
        psk.withUnsafeMutableBufferPointer { _ = memset_s($0.baseAddress, $0.count, 0, $0.count) }
        queue.sync { currentMode = .bilateral }
        NSLog("BirdoPQ v1 BILATERAL — quantum-resistant PSK derived (32 B, mode=bilateral)")
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
            let q: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: Self.keychainService,
                kSecAttrAccount as String: Self.keypairAccount,
                kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
            ]
            SecItemDelete(q as CFDictionary)
        }
    }

    // MARK: - Private

    private func loadOrGenerateKeypair() -> (pk: Data, sk: Data)? {
        return queue.sync {
            if let kp = cachedKeypair { return kp }
            if let kp = readKeypairFromKeychain() {
                cachedKeypair = kp
                return kp
            }
            NSLog("BirdoPQ: no persisted ML-KEM keypair — generating fresh (~10–50 ms)")
            guard let kp = generateKeypair() else { return nil }
            // Best-effort persist; if it fails we still return the in-memory
            // pair so the connect attempt isn't blocked.
            if !writeKeypairToKeychain(pk: kp.pk, sk: kp.sk) {
                NSLog("BirdoPQ: failed to persist keypair to Keychain — will regenerate next launch")
            }
            cachedKeypair = kp
            return kp
        }
    }

    private func generateKeypair() -> (pk: Data, sk: Data)? {
        var pk = [UInt8](repeating: 0, count: BIRDO_PQ_PUBLIC_KEY_LEN)
        var sk = [UInt8](repeating: 0, count: BIRDO_PQ_SECRET_KEY_LEN)
        let rc = birdo_pq_generate_keypair(&pk, pk.count, &sk, sk.count)
        if rc != BIRDO_PQ_OK {
            NSLog("BirdoPQ: generate_keypair failed rc=\(rc)")
            sk.withUnsafeMutableBufferPointer { _ = memset_s($0.baseAddress, $0.count, 0, $0.count) }
            return nil
        }
        let pkData = Data(pk)
        let skData = Data(sk)
        sk.withUnsafeMutableBufferPointer { _ = memset_s($0.baseAddress, $0.count, 0, $0.count) }
        return (pkData, skData)
    }

    private func readKeypairFromKeychain() -> (pk: Data, sk: Data)? {
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
        // Layout: pk (1568) || sk (3168).
        let expected = BIRDO_PQ_PUBLIC_KEY_LEN + BIRDO_PQ_SECRET_KEY_LEN
        guard data.count == expected else {
            NSLog("BirdoPQ: stored keypair has wrong size \(data.count); discarding")
            // Self-heal: drop the corrupt blob so the next call regenerates.
            let del: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: Self.keychainService,
                kSecAttrAccount as String: Self.keypairAccount,
                kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
            ]
            SecItemDelete(del as CFDictionary)
            return nil
        }
        let pk = data.prefix(BIRDO_PQ_PUBLIC_KEY_LEN)
        let sk = data.suffix(BIRDO_PQ_SECRET_KEY_LEN)
        return (Data(pk), Data(sk))
    }

    private func writeKeypairToKeychain(pk: Data, sk: Data) -> Bool {
        var blob = Data(capacity: pk.count + sk.count)
        blob.append(pk)
        blob.append(sk)

        // Delete first so we get clean upsert semantics.
        let del: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.keychainService,
            kSecAttrAccount as String: Self.keypairAccount,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        SecItemDelete(del as CFDictionary)

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
