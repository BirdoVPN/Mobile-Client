import Foundation

/// MR-745: the per-connect BirdoPQ nonce, decoded and BOUNDED before it reaches
/// the native HKDF.
///
/// The server mints 32 random bytes per connect and sends them Base64 in
/// `rosenpassEndpoint` (the re-used field name). They become the HKDF `info`
/// for the PSK. iOS took any length, so a misconfigured or tampered server
/// could hand the native library an empty or arbitrarily long `info`. Android
/// (`BirdoPqManager.MAX_NONCE_BYTES`) and desktop (`MAX_NONCE_BYTES`) refuse
/// anything outside 1...64 bytes; this is the same bound. Refusing fails the PQ
/// derivation closed, exactly like an absent nonce (PFA-M5).
///
/// Foundation-only, so `PQNonceTests` can pin it: `BirdoPQManager.swift`, its
/// only caller, imports `BirdoPQNative` and cannot be compiled into the test
/// bundle.
enum BirdoPQNonce {
    /// Upper bound in bytes, as on Android and desktop. The server mints 32.
    static let maxBytes = 64

    /// The longest Base64 text that can decode to `maxBytes`. Checked before
    /// decoding, so an oversized value is never decoded at all.
    static let maxBase64Length = 4 * ((maxBytes + 2) / 3)

    /// The nonce bytes, or nil when the value is absent, empty, not Base64, or
    /// decodes to more than `maxBytes`.
    static func decode(_ base64: String?) -> Data? {
        guard let base64, !base64.isEmpty, base64.utf8.count <= maxBase64Length,
              let nonce = Data(base64Encoded: base64),
              (1...maxBytes).contains(nonce.count) else {
            return nil
        }
        return nonce
    }
}
