import CommonCrypto
import Foundation
import Security

/// MR-953 F1 — the heartbeat connection's trust DECISION, apart from the pin
/// set it is made with.
///
/// The pins stay in PacketTunnelProvider.swift (`HeartbeatPins.pins`, where
/// scripts/check-cert-pins.sh reads them). The decision lives here so the
/// unit-test bundle, which cannot compile the provider, runs it for real
/// against certificate fixtures (HeartbeatTrustTests): on both ways into the
/// tunnel the answer to "may this connection carry the bearer token" is this
/// code's.
///
/// Fails CLOSED on every uncertain path: standard validation for the host
/// first, then at least one certificate in the chain must match a pinned SPKI
/// hash; an unreadable chain, key or key type refuses rather than falling
/// through.
enum PinnedTrust {
    /// True only when `trust` passes standard validation as `host` AND at least
    /// one certificate in its chain matches a pin.
    ///
    /// The SSL policy is set here, not assumed: a TLS verify block (or a trust
    /// built from a raw chain) carries whatever policy its maker chose, and
    /// the URLSession challenge this replaced always carried the hostname.
    static func isTrusted(_ trust: SecTrust, host: String, pins: Set<String>) -> Bool {
        guard SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, host as CFString)) == errSecSuccess else {
            return false
        }
        var trustError: CFError?
        guard SecTrustEvaluateWithError(trust, &trustError) else { return false }

        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate] else { return false }
        for certificate in chain {
            if let hash = spkiHash(of: certificate), pins.contains(hash) {
                return true
            }
        }
        return false
    }

    /// Base64 SHA-256 of the certificate's SubjectPublicKeyInfo, the form the
    /// pins are written in. nil for a key the pin set cannot contain (anything
    /// but RSA 2048/4096 and EC P-256/P-384): such a certificate matches no pin.
    static func spkiHash(of certificate: SecCertificate) -> String? {
        guard let key = SecCertificateCopyKey(certificate),
              let keyData = SecKeyCopyExternalRepresentation(key, nil) as Data?,
              let header = spkiHeader(for: key) else {
            return nil
        }
        var spki = header
        spki.append(keyData)
        return sha256(spki).base64EncodedString()
    }

    // MARK: - The iOS 17 / macOS 14 path (NWTCPConnection's delegate)

    /// The trust for the raw chain NetworkExtension hands the delegate, to be
    /// judged by `isTrusted`. nil when the chain holds no certificate at all.
    static func trust(forPeerChain chain: [Any], host: String) -> SecTrust? {
        let certificates = certificates(in: chain)
        guard !certificates.isEmpty else { return nil }
        var trust: SecTrust?
        guard SecTrustCreateWithCertificates(certificates as CFArray,
                                             SecPolicyCreateSSL(true, host as CFString),
                                             &trust) == errSecSuccess else {
            return nil
        }
        return trust
    }

    /// A trust for the same chain that can never pass the system's own
    /// evaluation: it has no anchors, and only those count. It is what the
    /// delegate hands back for a chain the pin check refused, since handing
    /// back nothing leaves the decision to the system's default.
    static func unpassableTrust(forPeerChain chain: [Any], host: String) -> SecTrust? {
        guard let trust = trust(forPeerChain: chain, host: host) else { return nil }
        guard SecTrustSetAnchorCertificates(trust, [] as CFArray) == errSecSuccess,
              SecTrustSetAnchorCertificatesOnly(trust, true) == errSecSuccess else {
            return nil
        }
        return trust
    }

    // MARK: - Private

    private static func certificates(in chain: [Any]) -> [SecCertificate] {
        chain.compactMap { item in
            let ref = item as CFTypeRef
            guard CFGetTypeID(ref) == SecCertificateGetTypeID() else { return nil }
            return (ref as! SecCertificate)
        }
    }

    /// ASN.1 SubjectPublicKeyInfo header bytes that prefix the raw key data
    /// produced by `SecKeyCopyExternalRepresentation`. RSA 2048/4096 +
    /// EC P-256 / P-384 cover every CA in the pin set.
    private static func spkiHeader(for key: SecKey) -> Data? {
        let attrs = SecKeyCopyAttributes(key) as? [CFString: Any] ?? [:]
        let type = (attrs[kSecAttrKeyType] as? String) ?? ""
        let size = (attrs[kSecAttrKeySizeInBits] as? Int) ?? 0
        let rsaType = kSecAttrKeyTypeRSA as String
        let ecType = kSecAttrKeyTypeECSECPrimeRandom as String
        switch (type, size) {
        case (rsaType, 2048):
            return Data([
                0x30, 0x82, 0x01, 0x22, 0x30, 0x0d, 0x06, 0x09,
                0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
                0x01, 0x05, 0x00, 0x03, 0x82, 0x01, 0x0f, 0x00,
            ])
        case (rsaType, 4096):
            return Data([
                0x30, 0x82, 0x02, 0x22, 0x30, 0x0d, 0x06, 0x09,
                0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
                0x01, 0x05, 0x00, 0x03, 0x82, 0x02, 0x0f, 0x00,
            ])
        case (ecType, 256):
            return Data([
                0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86,
                0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
                0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03,
                0x42, 0x00,
            ])
        case (ecType, 384):
            return Data([
                0x30, 0x76, 0x30, 0x10, 0x06, 0x07, 0x2a, 0x86,
                0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x05, 0x2b,
                0x81, 0x04, 0x00, 0x22, 0x03, 0x62, 0x00,
            ])
        default:
            return nil // Unknown key type: no pin can match it.
        }
    }

    private static func sha256(_ data: Data) -> Data {
        var hash = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes {
            _ = CC_SHA256($0.baseAddress, CC_LONG(data.count), &hash)
        }
        return Data(hash)
    }
}

/// The iOS 17 / macOS 14 path's rule that NO BYTE is written before the pin
/// check has approved the connection.
///
/// On that path the pin check runs in NWTCPConnection's authentication
/// delegate, and the request is written when the connection reports itself
/// connected. Those are two callbacks on two threads, and the write must not
/// depend on the system having consulted the delegate at all: a connection
/// that reaches `connected` without an approval on record is refused, whatever
/// evaluation the system did or did not run. The first `connected` decides,
/// once; an approval that arrives after a refusal changes nothing.
final class PinnedWriteGate: @unchecked Sendable {
    enum Decision: Equatable {
        /// Approved: write the request.
        case write
        /// Connected with no approval on record: end the exchange, write nothing.
        case refuse
        /// Not the first `connected`: nothing to do.
        case ignore
    }

    private let lock = NSLock()
    private var approved = false
    private var decided = false

    /// The pin check passed for this connection.
    func approve() {
        lock.lock()
        approved = true
        lock.unlock()
    }

    /// The connection reports itself connected.
    func connected() -> Decision {
        lock.lock()
        defer { lock.unlock() }
        guard !decided else { return .ignore }
        decided = true
        return approved ? .write : .refuse
    }
}
