//! `birdo-pq-server` — server-side BirdoPQ v1 helper binary.
//!
//! ## Purpose
//!
//! Each VPN backend node runs this binary (or links the same logic into the
//! application server) on every `/connect` request that includes a
//! `pq_client_public_key` field. It performs the ML-KEM-1024 encapsulation
//! against the client's public key, derives the WireGuard PresharedKey with
//! the same HKDF construction the Android client uses, and prints the
//! resulting `(ciphertext, psk)` pair so the calling backend can:
//!
//!   - return the ciphertext to the client in `ConnectResponse.rosenpassPublicKey`;
//!   - inject the PSK into the WireGuard peer config via
//!     `wg set <iface> peer <client_wg_pubkey> preshared-key <psk_file>`.
//!
//! ## Why a separate binary?
//!
//! - **Process isolation** — backend code (Node, Python, Go, etc.) doesn't
//!   need to link Rust crates or cross-compile pqcrypto.
//! - **Distro-agnostic** — single static binary, deploy via package or
//!   `scp + chmod +x`.
//! - **Auditable** — same Rust crate as the client, no parallel re-implementation.
//! - **Replaceable** — when libsodium cross-compile is solved upstream and
//!   you want to switch to spec-compatible Rosenpass, swap this binary out
//!   for `rosenpass exchange-config` and update the API contract.
//!
//! ## CLI
//!
//! ```text
//! birdo-pq-server encap <client_pk_b64>
//! ```
//!
//! Reads the client's Base64 ML-KEM-1024 public key from argv[2]. Generates
//! a fresh 32-byte random nonce. Encapsulates against the public key.
//! Derives the PSK as `HKDF-SHA-256(salt = "BirdoPQ-v1-PSK", IKM = ss, info = nonce)`
//! truncated to 32 bytes.
//!
//! Prints a single JSON object on stdout:
//!
//! ```json
//! { "ciphertext_b64": "...", "nonce_b64": "...", "psk_b64": "..." }
//! ```
//!
//! Exits 0 on success, non-zero on any input/crypto error (with a one-line
//! human-readable error message on stderr).
//!
//! ```text
//! birdo-pq-server version
//! ```
//!
//! Prints `birdo-pq-server X.Y.Z (BirdoPQ v1, ML-KEM-1024)`.
//!
//! ## Wire-compatibility with the Android client
//!
//! The HKDF salt and (default) construction MUST match the client side
//! (`native/rosenpass-jni/src/handshake.rs`). If you change one, change both
//! AND bump the protocol version string ("BirdoPQ-v1-PSK" → "v2", etc.).

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use hkdf::Hkdf;
use ml_kem::array::Array;
use ml_kem::kem::Encapsulate;
use ml_kem::ml_kem_1024::EncapsulationKey;
use sha2::Sha256;
use std::process::ExitCode;
use zeroize::{Zeroize, Zeroizing};

const HKDF_SALT: &[u8] = b"BirdoPQ-v1-PSK";
const PSK_LEN: usize = 32;
const NONCE_LEN: usize = 32;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let cmd = args.get(1).map(String::as_str).unwrap_or("");

    match cmd {
        "version" => {
            println!(
                "birdo-pq-server {} (BirdoPQ v1, ML-KEM-1024)",
                env!("CARGO_PKG_VERSION")
            );
            ExitCode::SUCCESS
        }
        "encap" => {
            let pk_b64 = match args.get(2) {
                Some(s) => s,
                None => {
                    eprintln!("usage: birdo-pq-server encap <client_pk_b64>");
                    return ExitCode::from(2);
                }
            };
            match encap(pk_b64) {
                Ok(json) => {
                    println!("{json}");
                    ExitCode::SUCCESS
                }
                Err(e) => {
                    eprintln!("error: {e}");
                    ExitCode::from(1)
                }
            }
        }
        _ => {
            eprintln!("usage:\n  birdo-pq-server encap <client_pk_b64>\n  birdo-pq-server version");
            ExitCode::from(2)
        }
    }
}

fn encap(client_pk_b64: &str) -> Result<String, String> {
    let pk_bytes = B64
        .decode(client_pk_b64.trim())
        .map_err(|e| format!("base64 decode of pk: {e}"))?;
    // Two checks, not one: the length, then FIPS 203 §7.2 (the coefficients of
    // the encapsulation key must be canonical). pqcrypto's from_bytes was a
    // length check only and would have encapsulated against a malformed key.
    let pk_arr = Array::try_from(&pk_bytes[..]).map_err(|_| {
        format!(
            "malformed ML-KEM-1024 pk ({} B, expected 1568)",
            pk_bytes.len()
        )
    })?;
    let pk = EncapsulationKey::new(&pk_arr)
        .map_err(|_| "malformed ML-KEM-1024 pk: failed FIPS 203 validation".to_owned())?;

    // Per-connect random nonce (32 B from the OS CSPRNG).
    let mut nonce = [0u8; NONCE_LEN];
    getrandom::fill(&mut nonce).map_err(|e| format!("CSPRNG: {e}"))?;

    // `encapsulate()` uses `kem` 0.3.0's ambient-RNG unwrap, which panics if
    // the OS CSPRNG fails; there is no `TryEncapsulate` to call instead and
    // `encapsulate_deterministic` is behind `hazmat`. This helper is a CLI, so
    // an abort is a non-zero exit rather than a silent failure.
    let (ct, mut ss) = pk.encapsulate();
    // `SharedKey` is a plain `Array<u8, U32>` with no Drop impl of its own, so
    // `ss` is wiped explicitly alongside the `Zeroizing` copy.
    let mut ss_bytes = Zeroizing::new(ss.to_vec());

    let mut psk = Zeroizing::new(vec![0u8; PSK_LEN]);
    Hkdf::<Sha256>::new(Some(HKDF_SALT), &ss_bytes)
        .expand(&nonce, psk.as_mut_slice())
        .map_err(|e| format!("HKDF expand: {e}"))?;
    ss_bytes.fill(0);
    ss.zeroize();

    let json = format!(
        r#"{{"ciphertext_b64":"{}","nonce_b64":"{}","psk_b64":"{}"}}"#,
        B64.encode(ct.as_slice()),
        B64.encode(nonce),
        B64.encode(psk.as_slice()),
    );
    Ok(json)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ml_kem::kem::{Decapsulate, Generate, KeyExport};
    use ml_kem::ml_kem_1024::DecapsulationKey;
    #[allow(deprecated)]
    use ml_kem::ExpandedKeyEncoding;
    use ml_kem::{ExpandedDecapsulationKey, MlKem1024};

    include!("../../testdata/birdo_pq_kat_vectors.rs");

    type StoredDk = ExpandedDecapsulationKey<MlKem1024>;

    fn unhex(s: &str) -> Vec<u8> {
        hex::decode(s).expect("fixture hex")
    }

    fn load_dk(bytes: &[u8]) -> DecapsulationKey {
        let enc = StoredDk::try_from(bytes).expect("3168-byte expanded dk");
        #[allow(deprecated)]
        let dk = DecapsulationKey::from_expanded_bytes(&enc).expect("valid dk");
        dk
    }

    fn psk_of(ss: &[u8], nonce: &[u8]) -> Vec<u8> {
        let mut psk = vec![0u8; PSK_LEN];
        Hkdf::<Sha256>::new(Some(HKDF_SALT), ss)
            .expand(nonce, psk.as_mut_slice())
            .expect("HKDF length OK (RFC 5869)");
        psk
    }

    /// The only test in this crate that can detect byte-incompatibility with
    /// the PRODUCTION server, which encapsulates with `@noble/post-quantum` and
    /// not with Rust. Vectors: `native/testdata/birdo-pq-ml-kem-1024.kat.json`,
    /// a byte-for-byte copy of the backend's own fixture.
    ///
    /// This crate is the one that would be *replaced by* that server, so
    /// byte-equality with it is the whole point of the binary existing.
    #[test]
    fn kat_vs_noble() {
        let seed = Array::try_from(&unhex(NOBLE_KEYGEN_SEED_HEX)[..]).expect("64-byte seed");
        let dk = DecapsulationKey::from_seed(seed);

        assert_eq!(
            dk.encapsulation_key().to_bytes().as_slice(),
            &unhex(NOBLE_EK_HEX)[..],
            "ek from the KAT seed must equal publicKeyB64"
        );
        #[allow(deprecated)]
        let dk_bytes = dk.to_expanded_bytes();
        assert_eq!(
            dk_bytes.as_slice(),
            &unhex(NOBLE_DK_HEX)[..],
            "expanded dk must equal secretKeyB64"
        );

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

        let ct_arr = Array::try_from(&unhex(NOBLE_CT_HEX)[..]).expect("ct");
        let decapsulated = load_dk(&unhex(NOBLE_DK_HEX)).decapsulate(&ct_arr);
        assert_eq!(
            decapsulated.as_slice(),
            &unhex(NOBLE_SS_HEX)[..],
            "decapsulate(ct, dk) must equal sharedSecretB64"
        );
        assert_eq!(
            psk_of(&decapsulated, &unhex(NOBLE_NONCE_HEX)),
            unhex(NOBLE_PSK_HEX),
            "hkdf_to_psk(ss, nonce) must equal presharedKeyB64"
        );
    }

    /// The install-base guard: a real `PQClean`-produced 3168-byte key, which is
    /// what is sealed on every device that installed 1.4.29 or earlier. This
    /// binary never reads a stored client key, but it is a twin of the two that
    /// do, and a twin that is not tested is how drift survives. Never delete.
    #[test]
    fn stored_pqclean_dk_loads_and_derives_same_psk() {
        let stored = unhex(PQCLEAN_DK_HEX);
        assert_eq!(stored.len(), 3168);
        let dk = load_dk(&stored);
        assert_eq!(
            dk.encapsulation_key().to_bytes().as_slice(),
            &unhex(PQCLEAN_EK_HEX)[..]
        );
        let ct = Array::try_from(&unhex(PQCLEAN_CT_HEX)[..]).expect("ct");
        let ss = dk.decapsulate(&ct);
        assert_eq!(ss.as_slice(), &unhex(PQCLEAN_SS_HEX)[..]);
        assert_eq!(
            psk_of(&ss, &unhex(PQCLEAN_NONCE_HEX)),
            unhex(PQCLEAN_PSK_HEX),
            "the PSK from a PQClean-stored key must be unchanged"
        );
    }

    /// `KeyExport::to_bytes()` returns a 64-byte Seed, not the 3168-byte stored
    /// encoding, and panics for expanded-loaded keys.
    #[test]
    fn stored_key_length_is_3168() {
        let dk = DecapsulationKey::generate();
        #[allow(deprecated)]
        let stored = dk.to_expanded_bytes();
        assert_eq!(stored.len(), 3168);
        assert_eq!(dk.encapsulation_key().to_bytes().len(), 1568);
    }

    /// FIPS 203 §7.3 is a new hard error: a corrupted stored key is rejected
    /// rather than silently decapsulating to garbage, and the answer is to
    /// re-key.
    #[test]
    fn corrupt_dk_returns_invalid_key_and_rekeys() {
        let mut corrupt = unhex(PQCLEAN_DK_HEX);
        corrupt[3100] ^= 0xff;
        let enc = StoredDk::try_from(&corrupt[..]).expect("still 3168 bytes");
        #[allow(deprecated)]
        let rejected = DecapsulationKey::from_expanded_bytes(&enc);
        assert!(rejected.is_err(), "a corrupted stored key must be rejected");

        // Re-key: a fresh key works immediately, end to end through encap().
        let fresh = DecapsulationKey::generate();
        let json = encap(&B64.encode(fresh.encapsulation_key().to_bytes().as_slice()))
            .expect("encap against a freshly generated key");
        assert!(json.contains("psk_b64"));
    }

    /// Implicit rejection is protocol behaviour: a wrong-but-valid key yields a
    /// stable, different shared secret with no error and no panic.
    #[test]
    fn implicit_rejection_is_deterministic_and_silent() {
        let wrong = DecapsulationKey::generate();
        let ct = Array::try_from(&unhex(PQCLEAN_CT_HEX)[..]).expect("ct");
        let a = wrong.decapsulate(&ct);
        let b = wrong.decapsulate(&ct);
        assert_eq!(a, b, "implicit rejection must be deterministic");
        assert_ne!(
            a.as_slice(),
            &unhex(PQCLEAN_SS_HEX)[..],
            "and must differ from the true shared secret"
        );
    }

    /// Server-side encap roundtrips through the same KEM the client uses.
    /// (We can't import the JNI crate here without its jni dep, so we just
    /// re-run keygen locally and verify the math holds.)
    #[test]
    fn encap_produces_well_formed_output() {
        let dk = DecapsulationKey::generate();
        let pk_b64 = B64.encode(dk.encapsulation_key().to_bytes().as_slice());
        let json = encap(&pk_b64).expect("encap");

        // Output is parseable JSON-ish and contains all three fields.
        assert!(json.contains("ciphertext_b64"));
        assert!(json.contains("nonce_b64"));
        assert!(json.contains("psk_b64"));
    }

    #[test]
    fn encap_rejects_malformed_pk() {
        let r = encap("not-base64-!@#$");
        assert!(r.is_err());
    }

    #[test]
    fn encap_rejects_wrong_size_pk() {
        let r = encap(&B64.encode([0u8; 16]));
        assert!(r.is_err());
        assert!(r.unwrap_err().contains("malformed"));
    }

    /// FIPS 203 §7.2: a right-sized but non-canonical encapsulation key is
    /// rejected. pqcrypto accepted it (length check only) and encapsulated
    /// against it.
    #[test]
    fn encap_rejects_non_canonical_pk() {
        let r = encap(&B64.encode([0xffu8; 1568]));
        assert!(
            r.is_err(),
            "an all-0xff encapsulation key must not encapsulate"
        );
    }

    /// The encapsulation this binary performs must produce a ciphertext the
    /// CLIENT can decapsulate to the same PSK. Nothing else here proves the two
    /// halves of the protocol agree.
    #[test]
    fn encap_output_decapsulates_to_the_same_psk() {
        let dk = load_dk(&unhex(PQCLEAN_DK_HEX));
        let json = encap(&B64.encode(dk.encapsulation_key().to_bytes().as_slice())).expect("encap");

        let field = |key: &str| -> Vec<u8> {
            let needle = format!("\"{key}\":\"");
            let rest = &json[json.find(&needle).expect("field present") + needle.len()..];
            let end = rest.find('"').expect("field closes");
            B64.decode(&rest[..end]).expect("base64")
        };

        let ct = Array::try_from(&field("ciphertext_b64")[..]).expect("1568-byte ct");
        let ss = dk.decapsulate(&ct);
        assert_eq!(
            psk_of(&ss, &field("nonce_b64")),
            field("psk_b64"),
            "the client must derive the PSK this binary printed"
        );
    }
}
