import Foundation

/// What to do with the ML-KEM-1024 keypair blob found in the Keychain.
///
/// ## Why this is its own file
///
/// `ml-kem` enforces FIPS 203 §7.3 on load: the 3168-byte expanded
/// decapsulation key embeds `H(ek)`, which is recomputed and compared. The
/// implementation that shipped up to 1.4.29 (`pqcrypto-mlkem`, PQClean CLEAN C)
/// checked only the length. So a stored key damaged in a way the old binding
/// accepted is, from 1.4.30 on, a hard rejection — `birdo_pq_derive_psk`
/// returns `BIRDO_PQ_ERR_BAD_SECRET_KEY`, `BirdoPQManager.tryDecapsulate`
/// returns `nil`, and `VPNManager` throws `quantumHandshakeFailed`. Nothing
/// recovers a key that fails that check, so retrying it is retrying it
/// **forever**: such an install could never connect with quantum protection
/// on, on any attempt, until the user logged out.
///
/// The Android twin (`BirdoPqManager.loadOrGenerateKeypair` ->
/// `RosenpassNative.storedKeyUsable`) makes exactly this decision. Keeping the
/// rule here — Foundation-only, pure, no Keychain and no native library — is
/// what lets `StoredPQKeyTriageTests` pin it on CI. `BirdoPQManager.swift`
/// itself cannot be compiled into the unit-test bundle: it imports
/// `BirdoPQNative` and talks to the data-protection Keychain. The first cut of
/// this mitigation shipped `birdo_pq_stored_key_usable` exported and never
/// called precisely because nothing on CI could see the call was missing;
/// `scripts/check_pq_ios_wiring.sh` now sees it, and these tests pin what the
/// call is supposed to decide.
enum StoredPQKeyDecision: Equatable {
    /// The blob is well formed, the KEM can still load its secret key, and its
    /// public key is the one embedded in that secret key.
    case use
    /// The blob exists but is unusable. Delete it, then generate a fresh
    /// keypair; the server re-pins the new public key on the next handshake,
    /// so the only cost is one extra keygen.
    case discardAndRekey(reason: String)
    /// Nothing is stored yet — first launch, or a logout cleared it.
    case generateFresh
}

enum StoredPQKeyTriage {

    /// Decide what to do with the persisted `pk || sk` blob.
    ///
    /// - Parameters:
    ///   - blob: the raw Keychain value, or `nil` when the item is absent.
    ///   - publicKeyLength: `BIRDO_PQ_PUBLIC_KEY_LEN`, passed in rather than
    ///     hardcoded so this file cannot drift from `birdo_pq_ios.h`.
    ///   - secretKeyLength: `BIRDO_PQ_SECRET_KEY_LEN`, same reason.
    ///   - secretKeyIsLoadable: `birdo_pq_stored_key_usable` — the FIPS 203
    ///     §7.3 check, which only the native KEM can answer.
    static func decide(
        blob: Data?,
        publicKeyLength: Int,
        secretKeyLength: Int,
        secretKeyIsLoadable: (Data) -> Bool
    ) -> StoredPQKeyDecision {
        guard let blob else { return .generateFresh }

        let expected = publicKeyLength + secretKeyLength
        guard blob.count == expected else {
            return .discardAndRekey(
                reason: "wrong size \(blob.count) B, expected \(expected) B")
        }

        // `Data(_:)` re-bases the slice. `suffix` keeps the parent's indices,
        // and handing a non-zero-based slice to `withUnsafeBytes` reads from
        // the wrong offset — the defect this whole path exists to avoid.
        var secretKey = Data(blob.suffix(secretKeyLength))
        // MR-2123: that is a plaintext copy of the secret key. Wipe it on every
        // path out (memset_s, which the optimiser may not remove).
        defer { secretKey.withUnsafeMutableBytes { _ = memset_s($0.baseAddress, $0.count, 0, $0.count) } }
        guard secretKeyIsLoadable(secretKey) else {
            return .discardAndRekey(
                reason: "failed FIPS 203 §7.3 validation (H(ek) mismatch) — not recoverable")
        }

        // MR-2121: §7.3 binds the encapsulation key EMBEDDED in the secret key
        // to H(ek); nothing binds the public key stored beside it. A pair whose
        // halves disagree passes the check above, uploads a public key this
        // client cannot decapsulate for, and fails every BirdoPQ connect until
        // sign-out. Desktop (birdo_pq.rs read_keypair) and Android reject it.
        guard publicKeyMatchesSecretKey(blob: blob,
                                        publicKeyLength: publicKeyLength,
                                        secretKeyLength: secretKeyLength) else {
            return .discardAndRekey(
                reason: "stored public key differs from the one embedded in the secret key")
        }

        return .use
    }

    /// Is the stored public key the encapsulation key embedded in the secret
    /// key? FIPS 203 lays the expanded ML-KEM decapsulation key out as
    /// `dk_pke || ek || H(ek) || z`, with `H(ek)` and `z` 32 bytes each, so `ek`
    /// starts at `secretKeyLength - publicKeyLength - 64` (1536 for
    /// ML-KEM-1024; the crate's `embedded_ek_sits_at_1536_in_the_expanded_dk`
    /// test pins it). Compared in place on the blob: both halves are public
    /// key material, and no further copy of the secret key is made.
    static func publicKeyMatchesSecretKey(blob: Data,
                                          publicKeyLength: Int,
                                          secretKeyLength: Int) -> Bool {
        let embeddedOffset = secretKeyLength - publicKeyLength - 64
        guard publicKeyLength > 0, embeddedOffset >= 0,
              blob.count == publicKeyLength + secretKeyLength else { return false }
        let start = blob.startIndex
        let stored = blob[start ..< start + publicKeyLength]
        let embeddedStart = start + publicKeyLength + embeddedOffset
        let embedded = blob[embeddedStart ..< embeddedStart + publicKeyLength]
        return stored.elementsEqual(embedded)
    }
}
