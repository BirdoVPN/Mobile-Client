//! BirdoPQ v1 — ML-KEM-1024 KEM-only PSK derivation.
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
//! - Shared secret 32 B — exactly the size WireGuard's PresharedKey wants.
//!
//! ## Construction
//!
//! `psk = HKDF-SHA-256(IKM = ss, salt = "BirdoPQ-v1-PSK", info = nonce)[..32]`

use crate::errors::JniErr;
use hkdf::Hkdf;
use pqcrypto_mlkem::mlkem1024;
use pqcrypto_traits::kem::{
    Ciphertext as KemCiphertext, PublicKey as KemPublicKey,
    SecretKey as KemSecretKey, SharedSecret as KemSharedSecret,
};
use sha2::Sha256;
use zeroize::Zeroizing;

const PSK_LEN: usize = 32;
const HKDF_SALT: &[u8] = b"BirdoPQ-v1-PSK";

pub struct StaticKeypair {
    pub public_key: Vec<u8>,
    pub secret_key: Zeroizing<Vec<u8>>,
}

pub fn generate_keypair() -> Result<StaticKeypair, JniErr> {
    let (pk, sk) = mlkem1024::keypair();
    Ok(StaticKeypair {
        public_key: pk.as_bytes().to_vec(),
        secret_key: Zeroizing::new(sk.as_bytes().to_vec()),
    })
}

/// Client side: decap server ciphertext, derive PSK.
///
/// ML-KEM is implicit-rejection — a malformed ciphertext won't error, it'll
/// return a deterministically-derived random shared secret. The resulting
/// PSK then won't match the server's, and the WireGuard handshake fails
/// later. This is the desired behaviour: it prevents an attacker from
/// learning whether decapsulation succeeded by observing client behaviour.
pub fn derive_psk(
    client_secret_key: &Zeroizing<Vec<u8>>,
    server_ciphertext: &[u8],
    server_nonce: &[u8],
) -> Result<Zeroizing<Vec<u8>>, JniErr> {
    let sk = mlkem1024::SecretKey::from_bytes(client_secret_key.as_slice())
        .map_err(|e| JniErr::Crypto(format!("malformed client secret key: {e}")))?;
    let ct = mlkem1024::Ciphertext::from_bytes(server_ciphertext)
        .map_err(|e| JniErr::Crypto(format!("malformed server ciphertext: {e}")))?;

    let ss = mlkem1024::decapsulate(&ct, &sk);
    let mut ss_bytes = Zeroizing::new(ss.as_bytes().to_vec());

    let psk = hkdf_to_psk(&ss_bytes, server_nonce);
    ss_bytes.fill(0);
    Ok(psk)
}

/// Server side: encap against client_pk, return `(ciphertext, psk)`.
pub fn encapsulate(
    client_public_key: &[u8],
    server_nonce: &[u8],
) -> Result<(Vec<u8>, Zeroizing<Vec<u8>>), JniErr> {
    let pk = mlkem1024::PublicKey::from_bytes(client_public_key)
        .map_err(|e| JniErr::Crypto(format!("malformed client public key: {e}")))?;

    let (ss, ct) = mlkem1024::encapsulate(&pk);
    let mut ss_bytes = Zeroizing::new(ss.as_bytes().to_vec());
    let ct_bytes = ct.as_bytes().to_vec();

    let psk = hkdf_to_psk(&ss_bytes, server_nonce);
    ss_bytes.fill(0);
    Ok((ct_bytes, psk))
}

fn hkdf_to_psk(shared_secret: &[u8], nonce: &[u8]) -> Zeroizing<Vec<u8>> {
    let hk = Hkdf::<Sha256>::new(Some(HKDF_SALT), shared_secret);
    let mut psk = Zeroizing::new(vec![0u8; PSK_LEN]);
    hk.expand(nonce, psk.as_mut_slice()).expect("HKDF length OK (RFC 5869)");
    psk
}

#[cfg(test)]
mod tests {
    use super::*;

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
        assert_eq!(kp.public_key.len(), 1568, "ML-KEM-1024 pk size per FIPS 203");
        assert_eq!(kp.secret_key.len(), 3168, "ML-KEM-1024 sk size per FIPS 203");
    }
}
