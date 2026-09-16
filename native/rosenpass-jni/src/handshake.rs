//! `BirdoPQ` v1 — ML-KEM-1024 KEM-only PSK derivation.
//!
//! This module is the only place that touches the PQ KEM. Every secret is
//! returned via `Zeroizing<Vec<u8>>` so it's wiped from native memory on drop.
//!
//! ## Why ML-KEM-1024 specifically
//!
//! - **NIST FIPS 203** standardised in August 2024. Algorithm name "ML-KEM"
//!   (formerly Kyber).
//! - **Security category 5** — the strongest of the three parameter sets,
//!   matching AES-256 against quantum attackers.
//! - Public key 1568 B, secret key 3168 B, ciphertext 1568 B — small enough
//!   to ship over the existing `/connect` API with negligible overhead.
//! - Shared secret 32 B — exactly the size `WireGuard`'s `PresharedKey` wants.
//!
//! ## Construction
//!
//! `psk = HKDF-SHA-256(IKM = ss, salt = "BirdoPQ-v1-PSK", info = nonce)[..32]`
//!
//! ## Implementation: `RustCrypto` `ml-kem`, not `PQClean`
//!
//! The KEM is `ml-kem` (RustCrypto/KEMs), pinned to an exact version. It
//! replaced `pqcrypto-mlkem` (`PQClean` CLEAN C) in 2026-09. What that changed
//! and what it did NOT change:
//!
//! - **Not changed:** every byte. The 1568-byte encapsulation key, the
//!   3168-byte expanded decapsulation key (the on-disk encoding), the
//!   1568-byte ciphertext, the 32-byte shared secret and the HKDF PSK are
//!   identical to `PQClean`'s and to `@noble/post-quantum`'s — the
//!   implementation the production server encapsulates with. `kat_vs_noble`
//!   and `stored_pqclean_dk_loads_and_derives_same_psk` below are the proof,
//!   and they run on committed vectors from both implementations.
//! - **Changed:** the stored key now goes through FIPS 203 §7.3 validation
//!   (`from_expanded_bytes` recomputes `H(ek)` and rejects a mismatch), where
//!   `PQClean`'s `from_bytes` was a length check only. A corrupted stored key
//!   that used to decapsulate to garbage — and fail as a `WireGuard` handshake
//!   that never completes — is now a clean error, which the Kotlin caller
//!   turns into "discard the stored key and re-key" via
//!   [`stored_secret_key_is_usable`].
//! - **Changed:** the expanded (3168-byte) key encoding is reachable only
//!   through `ml-kem`'s `#[deprecated]` `ExpandedKeyEncoding` trait. We use it
//!   deliberately: the seed form is NOT recoverable from an expanded key, so
//!   switching to it would brick every key already sealed on a device. The
//!   server (`@noble/post-quantum` 0.7.1) still uses 3168 bytes too.
//!
//! The ISA story is in `native/README.md` § ISA baseline. Short version:
//! `ml-kem`'s Keccak dependency *does* carry the same four ARMv8.2 `FEAT_SHA3`
//! mnemonics `PQClean`'s `feat.S` did, behind a `getauxval(AT_HWCAP)` runtime
//! check — and the Android build additionally pins the soft Keccak backend in
//! `.cargo/config.toml` so the opcodes are dropped at link time. Nothing here
//! is "structurally impossible" (PQ-DOCS-OK: quoted to refute); it is gated, and
//! the gate is asserted.

use crate::errors::JniErr;
use hkdf::Hkdf;
use ml_kem::array::Array;
use ml_kem::kem::{Decapsulate, Encapsulate, Generate, KeyExport};
use ml_kem::ml_kem_1024::{DecapsulationKey, EncapsulationKey};
use ml_kem::{ExpandedDecapsulationKey, MlKem1024};
// The 3168-byte expanded encoding is what is already sealed on every device.
// `ml-kem` 0.3 deprecated it in favour of the 64-byte seed form, but a seed
// cannot be recovered from an expanded key, so adopting the seed form would
// make every existing install unreadable. Deliberate, reviewed, pinned: see
// the module docs and native/README.md.
#[allow(deprecated)]
use ml_kem::ExpandedKeyEncoding;
use sha2::Sha256;
use zeroize::Zeroizing;

const PSK_LEN: usize = 32;
const HKDF_SALT: &[u8] = b"BirdoPQ-v1-PSK";

/// FIPS 203 ML-KEM-1024 encapsulation-key (public key) length.
pub const EK_LEN: usize = 1568;
/// FIPS 203 ML-KEM-1024 expanded decapsulation-key (secret key) length — the
/// on-disk encoding. `ml-kem`'s `KeyExport::to_bytes()` returns a 64-byte
/// *seed* instead and panics for keys loaded from the expanded form, so every
/// store path asserts this length rather than trusting the type.
pub const DK_LEN: usize = 3168;
/// FIPS 203 ML-KEM-1024 ciphertext length.
pub const CT_LEN: usize = 1568;

/// The expanded (legacy, on-disk) decapsulation-key array type.
type StoredDk = ExpandedDecapsulationKey<MlKem1024>;

pub struct StaticKeypair {
    pub public_key: Vec<u8>,
    pub secret_key: Zeroizing<Vec<u8>>,
}

pub fn generate_keypair() -> Result<StaticKeypair, JniErr> {
    let dk = DecapsulationKey::generate();
    let public_key = dk.encapsulation_key().to_bytes().to_vec();
    // NOT `KeyExport::to_bytes()`: that returns the 64-byte seed and panics
    // for expanded-loaded keys. The stored encoding is the 3168-byte expanded
    // form, and the assertion below is what makes a wrong call loud.
    #[allow(deprecated)]
    let secret_key = Zeroizing::new(dk.to_expanded_bytes().to_vec());

    if public_key.len() != EK_LEN || secret_key.len() != DK_LEN {
        return Err(JniErr::Crypto(format!(
            "generated keypair has the wrong encoding ({} B pk, {} B sk; expected {EK_LEN}/{DK_LEN})",
            public_key.len(),
            secret_key.len()
        )));
    }

    Ok(StaticKeypair {
        public_key,
        secret_key,
    })
}

/// Can this stored secret key still be loaded by the current KEM?
///
/// `ml-kem` enforces FIPS 203 §7.3 (the expanded key carries `H(ek)`, which is
/// recomputed and compared), where `PQClean`'s binding checked only the length.
/// So a stored key that is intact decodes, and one that has been corrupted —
/// or was never a valid ML-KEM-1024 key — does not.
///
/// The Kotlin side calls this when `derive_psk` fails, and on `false` deletes
/// the sealed keypair and generates a fresh one. Without that branch an
/// install with a damaged key would retry the same unusable key on every
/// connect, forever.
#[must_use]
pub fn stored_secret_key_is_usable(secret_key: &[u8]) -> bool {
    load_secret_key(secret_key).is_ok()
}

fn load_secret_key(secret_key: &[u8]) -> Result<DecapsulationKey, JniErr> {
    let enc = StoredDk::try_from(secret_key).map_err(|_| {
        JniErr::Crypto(format!(
            "malformed client secret key: {} B, expected {DK_LEN}",
            secret_key.len()
        ))
    })?;
    #[allow(deprecated)]
    DecapsulationKey::from_expanded_bytes(&enc).map_err(|_| {
        JniErr::Crypto(
            "malformed client secret key: failed FIPS 203 validation (H(ek) mismatch or non-canonical encapsulation key) — the stored key must be discarded and re-keyed"
                .to_owned(),
        )
    })
}

/// Client side: decap server ciphertext, derive PSK.
///
/// ML-KEM is implicit-rejection — a malformed ciphertext won't error, it'll
/// return a deterministically-derived random shared secret. The resulting
/// PSK then won't match the server's, and the `WireGuard` handshake fails
/// later. This is the desired behaviour: it prevents an attacker from
/// learning whether decapsulation succeeded by observing client behaviour.
pub fn derive_psk(
    client_secret_key: &Zeroizing<Vec<u8>>,
    server_ciphertext: &[u8],
    server_nonce: &[u8],
) -> Result<Zeroizing<Vec<u8>>, JniErr> {
    let dk = load_secret_key(client_secret_key.as_slice())?;
    let ct = Array::try_from(server_ciphertext).map_err(|_| {
        JniErr::Crypto(format!(
            "malformed server ciphertext: {} B, expected {CT_LEN}",
            server_ciphertext.len()
        ))
    })?;

    // Infallible by construction (FIPS 203 implicit rejection); see above.
    let ss = dk.decapsulate(&ct);
    // `SharedKey` is a plain `Array<u8, U32>` with no Drop impl of its own, so
    // the copy we keep is the one that has to be wiped.
    let mut ss_bytes = Zeroizing::new(ss.to_vec());

    let psk = hkdf_to_psk(&ss_bytes, server_nonce);
    ss_bytes.fill(0);
    Ok(psk)
}

/// Server side: encap against `client_pk`, return `(ciphertext, psk)`.
pub fn encapsulate(
    client_public_key: &[u8],
    server_nonce: &[u8],
) -> Result<(Vec<u8>, Zeroizing<Vec<u8>>), JniErr> {
    let ek_arr = Array::try_from(client_public_key).map_err(|_| {
        JniErr::Crypto(format!(
            "malformed client public key: {} B, expected {EK_LEN}",
            client_public_key.len()
        ))
    })?;
    // FIPS 203 §7.2: rejects non-canonical coefficients, which PQClean's
    // length-only `from_bytes` accepted.
    let ek = EncapsulationKey::new(&ek_arr).map_err(|_| {
        JniErr::Crypto("malformed client public key: failed FIPS 203 validation".to_owned())
    })?;

    let (ct, ss) = ek.encapsulate();
    let mut ss_bytes = Zeroizing::new(ss.to_vec());
    let ct_bytes = ct.to_vec();

    let psk = hkdf_to_psk(&ss_bytes, server_nonce);
    ss_bytes.fill(0);
    Ok((ct_bytes, psk))
}

fn hkdf_to_psk(shared_secret: &[u8], nonce: &[u8]) -> Zeroizing<Vec<u8>> {
    let hk = Hkdf::<Sha256>::new(Some(HKDF_SALT), shared_secret);
    let mut psk = Zeroizing::new(vec![0u8; PSK_LEN]);
    hk.expand(nonce, psk.as_mut_slice())
        .expect("HKDF length OK (RFC 5869)");
    psk
}

#[cfg(test)]
mod tests {
    use super::*;

    include!("../../testdata/birdo_pq_kat_vectors.rs");

    fn unhex(s: &str) -> Vec<u8> {
        hex::decode(s).expect("fixture hex")
    }

    #[test]
    fn client_server_roundtrip_derives_identical_psk() {
        let kp = generate_keypair().expect("generate_keypair");
        let nonce = b"connect-2026-05-08T00:00:00Z";

        let (ct, server_psk) = encapsulate(&kp.public_key, nonce).expect("encapsulate");
        assert_eq!(server_psk.len(), PSK_LEN);

        let client_psk = derive_psk(&kp.secret_key, &ct, nonce).expect("derive_psk");
        assert_eq!(client_psk.len(), PSK_LEN);

        assert_eq!(
            client_psk.as_slice(),
            server_psk.as_slice(),
            "client and server MUST derive identical PSK from the same (sk, ct, nonce)"
        );
    }

    #[test]
    fn different_nonces_produce_different_psks() {
        let kp = generate_keypair().expect("kp");
        let (ct, psk_a) = encapsulate(&kp.public_key, b"nonce-A").expect("enc A");
        let (_, psk_b) = encapsulate(&kp.public_key, b"nonce-B").expect("enc B");
        assert_ne!(psk_a.as_slice(), psk_b.as_slice());
        let client_a = derive_psk(&kp.secret_key, &ct, b"nonce-A").unwrap();
        assert_eq!(client_a.as_slice(), psk_a.as_slice());
    }

    #[test]
    fn fresh_encap_each_call() {
        let kp = generate_keypair().expect("kp");
        let (ct1, _) = encapsulate(&kp.public_key, b"n").expect("enc 1");
        let (ct2, _) = encapsulate(&kp.public_key, b"n").expect("enc 2");
        assert_ne!(ct1, ct2, "encap MUST use fresh randomness per call");
    }

    #[test]
    fn malformed_inputs_error_cleanly() {
        let _kp = generate_keypair().expect("kp");
        let r1 = derive_psk(&Zeroizing::new(vec![0u8; 16]), b"x", b"n");
        assert!(matches!(r1, Err(JniErr::Crypto(_))));
        let r2 = encapsulate(&[0u8; 16], b"n");
        assert!(matches!(r2, Err(JniErr::Crypto(_))));
    }

    #[test]
    fn kem_sizes_match_fips_203() {
        let kp = generate_keypair().expect("kp");
        assert_eq!(
            kp.public_key.len(),
            1568,
            "ML-KEM-1024 pk size per FIPS 203"
        );
        assert_eq!(
            kp.secret_key.len(),
            3168,
            "ML-KEM-1024 sk size per FIPS 203"
        );
    }

    /// The only test in this repo that can detect byte-incompatibility with the
    /// PRODUCTION server.
    ///
    /// The server does not run Rust: `birdo-web backend/src/vpn/birdo-pq.service.ts`
    /// encapsulates with `@noble/post-quantum`. Every other test here is a
    /// self-consistent round trip, and a KEM that is internally correct but
    /// byte-incompatible with the server passes all of them — FIPS 203 implicit
    /// rejection means the only field symptom would be a `WireGuard` handshake
    /// that silently never completes.
    ///
    /// The vectors are `native/testdata/birdo-pq-ml-kem-1024.kat.json`, a
    /// byte-for-byte copy of the fixture the backend's own tests use.
    #[test]
    fn kat_vs_noble() {
        let seed = unhex(NOBLE_KEYGEN_SEED_HEX);
        let seed_arr = Array::try_from(&seed[..]).expect("64-byte seed");
        let dk = DecapsulationKey::from_seed(seed_arr);

        // (1) FIPS 203 keygen is deterministic in (d, z): the same 64-byte seed
        //     must give the server's exact encapsulation key ...
        assert_eq!(
            dk.encapsulation_key().to_bytes().as_slice(),
            &unhex(NOBLE_EK_HEX)[..],
            "ek from the KAT seed must equal the server fixture's publicKeyB64"
        );
        // (2) ... and the server's exact 3168-byte stored encoding.
        #[allow(deprecated)]
        let dk_bytes = dk.to_expanded_bytes();
        assert_eq!(
            dk_bytes.as_slice(),
            &unhex(NOBLE_DK_HEX)[..],
            "expanded dk must equal the server fixture's secretKeyB64"
        );

        // (3) Deterministic encapsulation with the fixture's randomness must
        //     reproduce the server's ciphertext and shared secret.
        let m = Array::try_from(&unhex(NOBLE_ENCAPS_RANDOMNESS_HEX)[..]).expect("32-byte m");
        let (ct, ss) = dk.encapsulation_key().encapsulate_deterministic(&m);
        assert_eq!(
            ct.as_slice(),
            &unhex(NOBLE_CT_HEX)[..],
            "ct must equal cipherTextB64"
        );
        assert_eq!(
            ss.as_slice(),
            &unhex(NOBLE_SS_HEX)[..],
            "ss must equal sharedSecretB64"
        );

        // (4) The production path: load the SERVER-produced 3168-byte key from
        //     its serialized form and decapsulate the SERVER's ciphertext.
        let stored = Zeroizing::new(unhex(NOBLE_DK_HEX));
        let loaded = load_secret_key(&stored).expect("server-produced dk must load");
        let ct_arr = Array::try_from(&unhex(NOBLE_CT_HEX)[..]).expect("ct");
        assert_eq!(
            loaded.decapsulate(&ct_arr).as_slice(),
            &unhex(NOBLE_SS_HEX)[..],
            "decapsulate(ct, dk) must equal sharedSecretB64"
        );

        // (5) And the whole BirdoPQ v1 construction end to end.
        let psk =
            derive_psk(&stored, &unhex(NOBLE_CT_HEX), &unhex(NOBLE_NONCE_HEX)).expect("derive_psk");
        assert_eq!(
            psk.as_slice(),
            &unhex(NOBLE_PSK_HEX)[..],
            "hkdf_to_psk(ss, nonce) must equal presharedKeyB64"
        );
    }

    /// The install-base guard.
    ///
    /// `PQCLEAN_DK_HEX` is a real 3168-byte expanded decapsulation key produced
    /// by `pqcrypto-mlkem` 0.1.1 (`PQClean` `MLKEM1024_CLEAN`) — bit-for-bit what
    /// is sealed in `RosenpassKeyStore` on every device that installed 1.4.29
    /// or earlier. If a future KEM change stops reading this, every existing
    /// install loses its key silently. Never delete this test.
    #[test]
    fn stored_pqclean_dk_loads_and_derives_same_psk() {
        let stored = Zeroizing::new(unhex(PQCLEAN_DK_HEX));
        assert_eq!(stored.len(), DK_LEN);

        let loaded = load_secret_key(&stored).expect("PQClean-produced dk must still load");
        assert_eq!(
            loaded.encapsulation_key().to_bytes().as_slice(),
            &unhex(PQCLEAN_EK_HEX)[..],
            "the ek recovered from the stored PQClean dk must match the stored PQClean ek"
        );

        let psk = derive_psk(&stored, &unhex(PQCLEAN_CT_HEX), &unhex(PQCLEAN_NONCE_HEX))
            .expect("derive_psk on a PQClean-stored key");
        assert_eq!(
            psk.as_slice(),
            &unhex(PQCLEAN_PSK_HEX)[..],
            "the PSK from a PQClean-stored key must be unchanged"
        );
    }

    /// `KeyExport::to_bytes()` returns a 64-byte Seed and panics for keys loaded
    /// from the expanded form. Nothing in the Android or iOS test suite would
    /// have caught either before this assertion existed.
    #[test]
    fn stored_key_length_is_3168() {
        let kp = generate_keypair().expect("kp");
        assert_eq!(
            kp.secret_key.len(),
            3168,
            "the stored secret-key encoding must be the 3168-byte expanded form, not a 64-byte seed"
        );
        assert_eq!(kp.public_key.len(), 1568);
    }

    /// FIPS 203 §7.3 is a NEW hard error on an existing install base. It must
    /// lead to a re-key, not to a connect-failure loop.
    #[test]
    fn corrupt_dk_returns_invalid_key_and_rekeys() {
        let mut corrupt = unhex(PQCLEAN_DK_HEX);
        // Byte 3100 sits inside the encapsulation key embedded at dk[1536..3104],
        // so the FIPS 203 §7.3 comparison against the stored H(ek) at
        // dk[3104..3136] must reject it.
        corrupt[3100] ^= 0xff;
        assert_eq!(
            corrupt.len(),
            DK_LEN,
            "still the right LENGTH — only the content is wrong"
        );

        assert!(
            !stored_secret_key_is_usable(&corrupt),
            "a corrupted stored key must be reported unusable so the caller re-keys"
        );
        let err = derive_psk(&Zeroizing::new(corrupt), &unhex(PQCLEAN_CT_HEX), b"n");
        assert!(matches!(err, Err(JniErr::Crypto(_))));

        // The re-key branch: the caller discards and regenerates, and the fresh
        // key works immediately.
        let fresh = generate_keypair().expect("re-key");
        assert!(stored_secret_key_is_usable(&fresh.secret_key));
        let (ct, server_psk) = encapsulate(&fresh.public_key, b"n").expect("encap");
        let client_psk = derive_psk(&fresh.secret_key, &ct, b"n").expect("derive");
        assert_eq!(client_psk.as_slice(), server_psk.as_slice());
    }

    /// Implicit rejection is protocol behaviour, not a bug: a wrong-but-valid
    /// decapsulation key yields a stable, different shared secret with no error
    /// and no panic. Locked in so nobody "fixes" it into a `Result`.
    #[test]
    fn implicit_rejection_is_deterministic_and_silent() {
        let real = Zeroizing::new(unhex(PQCLEAN_DK_HEX));
        let wrong = generate_keypair().expect("kp").secret_key;
        let ct = unhex(PQCLEAN_CT_HEX);

        let a = derive_psk(&wrong, &ct, b"n").expect("no error on a wrong key");
        let b = derive_psk(&wrong, &ct, b"n").expect("no error on a wrong key");
        assert_eq!(
            a.as_slice(),
            b.as_slice(),
            "implicit rejection must be deterministic"
        );

        let good = derive_psk(&real, &ct, b"n").expect("real key");
        assert_ne!(
            a.as_slice(),
            good.as_slice(),
            "and must differ from the true PSK"
        );
    }

    /// The generated constants must not drift from the JSON fixtures they came
    /// from. This is the only crate that re-parses them (it already has base64);
    /// the iOS and server crates consume the constants.
    #[test]
    fn kat_hex_constants_match_the_committed_fixtures() {
        use base64::engine::general_purpose::STANDARD as B64;
        use base64::Engine;

        // Deliberately dependency-free: pull the quoted value that follows a
        // `"key":` in the fixture rather than adding a JSON parser to a crate
        // that ships in the APK.
        fn field<'a>(json: &'a str, key: &str) -> &'a str {
            let needle = format!("\"{key}\":");
            let rest = &json[json.find(&needle).expect("fixture key present") + needle.len()..];
            let start = rest.find('"').expect("value opens") + 1;
            let end = start + rest[start..].find('"').expect("value closes");
            &rest[start..end]
        }

        let noble = include_str!("../../testdata/birdo-pq-ml-kem-1024.kat.json");
        let pqclean = include_str!("../../testdata/birdo-pq-pqclean-stored-key.json");

        for (key, expected) in [
            ("keygenSeedB64", NOBLE_KEYGEN_SEED_HEX),
            ("encapsRandomnessB64", NOBLE_ENCAPS_RANDOMNESS_HEX),
            ("publicKeyB64", NOBLE_EK_HEX),
            ("secretKeyB64", NOBLE_DK_HEX),
            ("cipherTextB64", NOBLE_CT_HEX),
            ("sharedSecretB64", NOBLE_SS_HEX),
            ("nonceB64", NOBLE_NONCE_HEX),
            ("presharedKeyB64", NOBLE_PSK_HEX),
        ] {
            let raw = B64.decode(field(noble, key)).expect("fixture base64");
            assert_eq!(
                hex::encode(raw),
                expected,
                "{key} drifted from the @noble fixture"
            );
        }

        for (key, expected) in [
            ("encapsulationKeyHex", PQCLEAN_EK_HEX),
            ("expandedDecapsulationKeyHex", PQCLEAN_DK_HEX),
            ("cipherTextHex", PQCLEAN_CT_HEX),
            ("sharedSecretHex", PQCLEAN_SS_HEX),
            ("nonceHex", PQCLEAN_NONCE_HEX),
            ("presharedKeyHex", PQCLEAN_PSK_HEX),
        ] {
            assert_eq!(
                field(pqclean, key),
                expected,
                "{key} drifted from the PQClean fixture"
            );
        }
    }
}
