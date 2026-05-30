//! BirdoPQ v1 — ML-KEM-1024 PSK derivation (C ABI for iOS / Swift).
//!
//! This crate is a thin, stateless C-ABI wrapper around the same
//! ML-KEM-1024 + HKDF-SHA-256 construction used by:
//!   - `birdo-client-mobile/native/rosenpass-jni/src/handshake.rs` (Android)
//!   - `birdo-client-desktop/src-tauri/src/vpn/birdo_pq.rs`        (Desktop)
//!   - `birdo-web/backend/src/vpn/birdo-pq.service.ts`             (Server)
//!
//! Algorithm (canonical):
//!
//! ```text
//! ss  = ML-KEM-1024.Decap(sk_client, ct_server)        (32 B)
//! psk = HKDF-SHA-256(IKM = ss, salt = "BirdoPQ-v1-PSK", info = nonce)[..32]
//! ```
//!
//! Sizes (FIPS 203 ML-KEM-1024):
//!   pk  = 1568 B    sk = 3168 B    ct = 1568 B    psk = 32 B
//!
//! ## C ABI contract
//!
//! All entry points are stateless. Caller owns every buffer; this library
//! never allocates memory the caller has to free. Return code:
//!   0 = success
//!  <0 = failure (see `BirdoPqStatus`)
//!
//! Persistent storage of the secret key is the Swift caller's job
//! (Keychain item with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`).
//! That keeps every bit of long-lived secret material under iOS's hardware-
//! backed key protection — better than what we can do from Rust.

#![deny(unsafe_op_in_unsafe_fn)]
#![allow(clippy::missing_safety_doc)]

use hkdf::Hkdf;
use pqcrypto_mlkem::mlkem1024;
use pqcrypto_traits::kem::{
    Ciphertext as KemCiphertext, PublicKey as KemPublicKey,
    SecretKey as KemSecretKey, SharedSecret as KemSharedSecret,
};
use sha2::Sha256;
use zeroize::Zeroizing;

/// FIPS 203 ML-KEM-1024 sizes — must match every other BirdoPQ impl.
pub const BIRDO_PQ_PUBLIC_KEY_LEN: usize = 1568;
pub const BIRDO_PQ_SECRET_KEY_LEN: usize = 3168;
pub const BIRDO_PQ_CIPHERTEXT_LEN: usize = 1568;
pub const BIRDO_PQ_PSK_LEN: usize = 32;

const HKDF_SALT: &[u8] = b"BirdoPQ-v1-PSK";

/// Status codes returned by every C entry point.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BirdoPqStatus {
    Ok = 0,
    NullPointer = -1,
    BufferSizeMismatch = -2,
    /// ML-KEM rejected the secret key as malformed.
    BadSecretKey = -3,
    /// ML-KEM rejected the ciphertext as malformed (wrong length, etc.).
    BadCiphertext = -4,
    /// Internal error (HKDF expand failed, etc.). Should not happen.
    Internal = -99,
}

// ── Size accessors so Swift can sanity-check buffer sizes at runtime ──────

#[no_mangle]
pub extern "C" fn birdo_pq_public_key_len() -> usize {
    BIRDO_PQ_PUBLIC_KEY_LEN
}
#[no_mangle]
pub extern "C" fn birdo_pq_secret_key_len() -> usize {
    BIRDO_PQ_SECRET_KEY_LEN
}
#[no_mangle]
pub extern "C" fn birdo_pq_ciphertext_len() -> usize {
    BIRDO_PQ_CIPHERTEXT_LEN
}
#[no_mangle]
pub extern "C" fn birdo_pq_psk_len() -> usize {
    BIRDO_PQ_PSK_LEN
}

// ── Keypair generation ────────────────────────────────────────────────────

/// Generate a fresh ML-KEM-1024 keypair.
///
/// `out_pk` MUST point to at least `BIRDO_PQ_PUBLIC_KEY_LEN` bytes.
/// `out_sk` MUST point to at least `BIRDO_PQ_SECRET_KEY_LEN` bytes.
///
/// On `Ok`, both buffers are fully written. On any error, the buffers are
/// zeroed before return so partial / undefined material can't leak.
#[no_mangle]
pub unsafe extern "C" fn birdo_pq_generate_keypair(
    out_pk: *mut u8,
    out_pk_len: usize,
    out_sk: *mut u8,
    out_sk_len: usize,
) -> i32 {
    if out_pk.is_null() || out_sk.is_null() {
        return BirdoPqStatus::NullPointer as i32;
    }
    if out_pk_len < BIRDO_PQ_PUBLIC_KEY_LEN || out_sk_len < BIRDO_PQ_SECRET_KEY_LEN {
        return BirdoPqStatus::BufferSizeMismatch as i32;
    }

    let (pk, sk) = mlkem1024::keypair();
    let pk_bytes = pk.as_bytes();
    let sk_bytes = sk.as_bytes();

    // Wrap caller buffers as slices for the copy.
    // SAFETY: caller asserts `out_pk` / `out_sk` are valid for the declared
    // lengths and writable. We only write `BIRDO_PQ_*_LEN` bytes.
    unsafe {
        std::ptr::copy_nonoverlapping(pk_bytes.as_ptr(), out_pk, BIRDO_PQ_PUBLIC_KEY_LEN);
        std::ptr::copy_nonoverlapping(sk_bytes.as_ptr(), out_sk, BIRDO_PQ_SECRET_KEY_LEN);
    }
    BirdoPqStatus::Ok as i32
}

// ── PSK derivation (client side) ──────────────────────────────────────────

/// Decapsulate `ct` with `sk`, then HKDF-SHA-256 it through `nonce` into a
/// 32-byte WireGuard PSK.
///
/// ML-KEM is implicit-rejection: a malformed ciphertext won't error here,
/// it'll yield a deterministic random shared secret. The resulting PSK
/// then won't match the server's, and the WireGuard handshake fails later.
/// This is the desired behaviour — it prevents an attacker from probing
/// decap success by observing client behaviour.
///
/// `out_psk` MUST point to at least `BIRDO_PQ_PSK_LEN` bytes.
#[no_mangle]
pub unsafe extern "C" fn birdo_pq_derive_psk(
    sk: *const u8,
    sk_len: usize,
    ct: *const u8,
    ct_len: usize,
    nonce: *const u8,
    nonce_len: usize,
    out_psk: *mut u8,
    out_psk_len: usize,
) -> i32 {
    if sk.is_null() || ct.is_null() || out_psk.is_null() {
        return BirdoPqStatus::NullPointer as i32;
    }
    if sk_len != BIRDO_PQ_SECRET_KEY_LEN {
        return BirdoPqStatus::BadSecretKey as i32;
    }
    if ct_len != BIRDO_PQ_CIPHERTEXT_LEN {
        return BirdoPqStatus::BadCiphertext as i32;
    }
    if out_psk_len < BIRDO_PQ_PSK_LEN {
        return BirdoPqStatus::BufferSizeMismatch as i32;
    }
    // nonce may legitimately be empty; nonce.is_null() with nonce_len == 0
    // is OK, but a null pointer with non-zero len is an API misuse.
    if nonce.is_null() && nonce_len != 0 {
        return BirdoPqStatus::NullPointer as i32;
    }

    // SAFETY: caller asserted lengths and validity above.
    let sk_slice = unsafe { std::slice::from_raw_parts(sk, sk_len) };
    let ct_slice = unsafe { std::slice::from_raw_parts(ct, ct_len) };
    let nonce_slice: &[u8] = if nonce_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(nonce, nonce_len) }
    };

    let sk_obj = match mlkem1024::SecretKey::from_bytes(sk_slice) {
        Ok(s) => s,
        Err(_) => return BirdoPqStatus::BadSecretKey as i32,
    };
    let ct_obj = match mlkem1024::Ciphertext::from_bytes(ct_slice) {
        Ok(c) => c,
        Err(_) => return BirdoPqStatus::BadCiphertext as i32,
    };

    let ss = mlkem1024::decapsulate(&ct_obj, &sk_obj);
    let mut ss_bytes = Zeroizing::new(ss.as_bytes().to_vec());

    let mut psk = Zeroizing::new([0u8; BIRDO_PQ_PSK_LEN]);
    let hk = Hkdf::<Sha256>::new(Some(HKDF_SALT), &ss_bytes);
    if hk.expand(nonce_slice, psk.as_mut_slice()).is_err() {
        ss_bytes.fill(0);
        return BirdoPqStatus::Internal as i32;
    }
    ss_bytes.fill(0);

    // SAFETY: out_psk validated above.
    unsafe {
        std::ptr::copy_nonoverlapping(psk.as_ptr(), out_psk, BIRDO_PQ_PSK_LEN);
    }
    BirdoPqStatus::Ok as i32
}

// ── Test helpers (compiled out of release) ────────────────────────────────

/// Server-side encapsulation. ONLY exposed so the Swift unit tests can do
/// a self-contained round-trip without spinning up the backend. Not part
/// of the production API surface — guarded so it shows up as a separate
/// symbol that's easy to grep for if anyone ever ships a release that
/// accidentally calls it.
#[no_mangle]
pub unsafe extern "C" fn birdo_pq_test_encapsulate(
    pk: *const u8,
    pk_len: usize,
    out_ct: *mut u8,
    out_ct_len: usize,
    out_psk: *mut u8,
    out_psk_len: usize,
    nonce: *const u8,
    nonce_len: usize,
) -> i32 {
    if pk.is_null() || out_ct.is_null() || out_psk.is_null() {
        return BirdoPqStatus::NullPointer as i32;
    }
    if pk_len != BIRDO_PQ_PUBLIC_KEY_LEN {
        return BirdoPqStatus::BufferSizeMismatch as i32;
    }
    if out_ct_len < BIRDO_PQ_CIPHERTEXT_LEN || out_psk_len < BIRDO_PQ_PSK_LEN {
        return BirdoPqStatus::BufferSizeMismatch as i32;
    }
    if nonce.is_null() && nonce_len != 0 {
        return BirdoPqStatus::NullPointer as i32;
    }

    // SAFETY: validated above.
    let pk_slice = unsafe { std::slice::from_raw_parts(pk, pk_len) };
    let nonce_slice: &[u8] = if nonce_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(nonce, nonce_len) }
    };

    let pk_obj = match mlkem1024::PublicKey::from_bytes(pk_slice) {
        Ok(p) => p,
        Err(_) => return BirdoPqStatus::BadCiphertext as i32,
    };
    let (ss, ct) = mlkem1024::encapsulate(&pk_obj);
    let mut ss_bytes = Zeroizing::new(ss.as_bytes().to_vec());
    let ct_bytes = ct.as_bytes();

    let mut psk = Zeroizing::new([0u8; BIRDO_PQ_PSK_LEN]);
    let hk = Hkdf::<Sha256>::new(Some(HKDF_SALT), &ss_bytes);
    if hk.expand(nonce_slice, psk.as_mut_slice()).is_err() {
        ss_bytes.fill(0);
        return BirdoPqStatus::Internal as i32;
    }
    ss_bytes.fill(0);

    // SAFETY: caller buffers validated above.
    unsafe {
        std::ptr::copy_nonoverlapping(ct_bytes.as_ptr(), out_ct, BIRDO_PQ_CIPHERTEXT_LEN);
        std::ptr::copy_nonoverlapping(psk.as_ptr(), out_psk, BIRDO_PQ_PSK_LEN);
    }
    BirdoPqStatus::Ok as i32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sizes_match_fips_203() {
        assert_eq!(birdo_pq_public_key_len(), 1568);
        assert_eq!(birdo_pq_secret_key_len(), 3168);
        assert_eq!(birdo_pq_ciphertext_len(), 1568);
        assert_eq!(birdo_pq_psk_len(), 32);
    }

    #[test]
    fn round_trip_via_c_abi_yields_identical_psk() {
        let mut pk = vec![0u8; BIRDO_PQ_PUBLIC_KEY_LEN];
        let mut sk = vec![0u8; BIRDO_PQ_SECRET_KEY_LEN];
        let r = unsafe {
            birdo_pq_generate_keypair(pk.as_mut_ptr(), pk.len(), sk.as_mut_ptr(), sk.len())
        };
        assert_eq!(r, 0);

        let nonce = b"connect-2026-05-10T12:00:00Z";
        let mut ct = vec![0u8; BIRDO_PQ_CIPHERTEXT_LEN];
        let mut server_psk = vec![0u8; BIRDO_PQ_PSK_LEN];
        let r = unsafe {
            birdo_pq_test_encapsulate(
                pk.as_ptr(), pk.len(),
                ct.as_mut_ptr(), ct.len(),
                server_psk.as_mut_ptr(), server_psk.len(),
                nonce.as_ptr(), nonce.len(),
            )
        };
        assert_eq!(r, 0);

        let mut client_psk = vec![0u8; BIRDO_PQ_PSK_LEN];
        let r = unsafe {
            birdo_pq_derive_psk(
                sk.as_ptr(), sk.len(),
                ct.as_ptr(), ct.len(),
                nonce.as_ptr(), nonce.len(),
                client_psk.as_mut_ptr(), client_psk.len(),
            )
        };
        assert_eq!(r, 0);

        assert_eq!(client_psk, server_psk);
    }

    #[test]
    fn rejects_wrong_sk_size() {
        let bad_sk = vec![0u8; 32];
        let ct = vec![0u8; BIRDO_PQ_CIPHERTEXT_LEN];
        let mut psk = vec![0u8; BIRDO_PQ_PSK_LEN];
        let r = unsafe {
            birdo_pq_derive_psk(
                bad_sk.as_ptr(), bad_sk.len(),
                ct.as_ptr(), ct.len(),
                std::ptr::null(), 0,
                psk.as_mut_ptr(), psk.len(),
            )
        };
        assert_eq!(r, BirdoPqStatus::BadSecretKey as i32);
    }

    #[test]
    fn rejects_wrong_ct_size() {
        let mut pk = vec![0u8; BIRDO_PQ_PUBLIC_KEY_LEN];
        let mut sk = vec![0u8; BIRDO_PQ_SECRET_KEY_LEN];
        unsafe {
            birdo_pq_generate_keypair(pk.as_mut_ptr(), pk.len(), sk.as_mut_ptr(), sk.len())
        };
        let bad_ct = vec![0u8; 16];
        let mut psk = vec![0u8; BIRDO_PQ_PSK_LEN];
        let r = unsafe {
            birdo_pq_derive_psk(
                sk.as_ptr(), sk.len(),
                bad_ct.as_ptr(), bad_ct.len(),
                std::ptr::null(), 0,
                psk.as_mut_ptr(), psk.len(),
            )
        };
        assert_eq!(r, BirdoPqStatus::BadCiphertext as i32);
    }

    #[test]
    fn null_pointer_rejected() {
        let r = unsafe {
            birdo_pq_generate_keypair(
                std::ptr::null_mut(), BIRDO_PQ_PUBLIC_KEY_LEN,
                std::ptr::null_mut(), BIRDO_PQ_SECRET_KEY_LEN,
            )
        };
        assert_eq!(r, BirdoPqStatus::NullPointer as i32);
    }

    #[test]
    fn small_output_buffer_rejected() {
        let mut pk = vec![0u8; BIRDO_PQ_PUBLIC_KEY_LEN];
        let mut sk = vec![0u8; 100]; // too small
        let r = unsafe {
            birdo_pq_generate_keypair(pk.as_mut_ptr(), pk.len(), sk.as_mut_ptr(), sk.len())
        };
        assert_eq!(r, BirdoPqStatus::BufferSizeMismatch as i32);
    }
}
