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
use ml_kem::array::Array;
use ml_kem::kem::{Decapsulate, Encapsulate, Generate, KeyExport};
use ml_kem::ml_kem_1024::{DecapsulationKey, EncapsulationKey};
use ml_kem::{ExpandedDecapsulationKey, MlKem1024};
// The 3168-byte expanded encoding is what is already in every device's
// Keychain (service "app.birdo.vpn.pq", 1568 + 3168 = 4736 B). ml-kem 0.3
// deprecated it in favour of a 64-byte seed, but the seed cannot be recovered
// from an expanded key, so adopting the seed form would make every existing
// install unreadable. Deliberate, reviewed, pinned.
#[allow(deprecated)]
use ml_kem::ExpandedKeyEncoding;
use sha2::Sha256;
use zeroize::{Zeroize, Zeroizing};

/// The expanded (legacy, on-Keychain) decapsulation-key array type.
type StoredDk = ExpandedDecapsulationKey<MlKem1024>;

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

    // `try_generate()`, NOT `generate()`. `Generate::generate()` is
    // `generate_from_rng(&mut UnwrapErr(SysRng))` (crypto-common
    // `src/generate.rs`), documented "will panic in the event the system's
    // ambient RNG experiences an internal failure" -- and this crate sets
    // panic = "abort", so on iOS that panic is an abort inside a Swift call
    // with no status code to return. `pqcrypto-internals` had the identical
    // defect (`getrandom::fill(buf).expect("RNG Failed")`); migrating to
    // ml-kem moved that panic, it did not remove it. BIRDO_PQ_ERR_INTERNAL is
    // a status Swift already handles.
    let Ok(dk) = DecapsulationKey::try_generate() else {
        return BirdoPqStatus::Internal as i32;
    };
    let pk_bytes = dk.encapsulation_key().to_bytes();
    // NOT `KeyExport::to_bytes()`: that returns the 64-byte seed and PANICS for
    // keys loaded from the expanded form. The Keychain blob is the 3168-byte
    // expanded encoding, and the assertion below is what makes a wrong call
    // loud instead of silently changing every stored key.
    #[allow(deprecated)]
    let sk_bytes = Zeroizing::new(dk.to_expanded_bytes());
    if pk_bytes.len() != BIRDO_PQ_PUBLIC_KEY_LEN || sk_bytes.len() != BIRDO_PQ_SECRET_KEY_LEN {
        return BirdoPqStatus::Internal as i32;
    }

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

    // ml-kem enforces FIPS 203 §7.3 here (the expanded key embeds H(ek), which
    // is recomputed and compared) where pqcrypto's from_bytes was a length
    // check only. A stored key that fails this is not recoverable, so Swift
    // MUST treat BAD_SECRET_KEY as "discard the Keychain item and re-key"
    // rather than retrying it on every connect. See birdo_pq_stored_key_usable.
    let sk_obj = match StoredDk::try_from(sk_slice) {
        Ok(enc) =>
        {
            #[allow(deprecated)]
            match DecapsulationKey::from_expanded_bytes(&enc) {
                Ok(dk) => dk,
                Err(_) => return BirdoPqStatus::BadSecretKey as i32,
            }
        }
        Err(_) => return BirdoPqStatus::BadSecretKey as i32,
    };
    let ct_obj = match Array::try_from(ct_slice) {
        Ok(c) => c,
        Err(_) => return BirdoPqStatus::BadCiphertext as i32,
    };

    let mut ss = sk_obj.decapsulate(&ct_obj);
    // `SharedKey` is a plain `Array<u8, U32>` with no Drop impl of its own, so
    // BOTH copies have to be wiped by hand: the `Vec` below (via `Zeroizing`)
    // and `ss` itself, which would otherwise be left on the stack holding the
    // raw shared secret. `hybrid-array`'s `zeroize` feature is enabled in the
    // resolved graph (pulled in by `ml-kem/zeroize`), which is what makes
    // `Array: Zeroize` available.
    let mut ss_bytes = Zeroizing::new(ss.to_vec());

    let mut psk = Zeroizing::new([0u8; BIRDO_PQ_PSK_LEN]);
    let hk = Hkdf::<Sha256>::new(Some(HKDF_SALT), &ss_bytes);
    if hk.expand(nonce_slice, psk.as_mut_slice()).is_err() {
        ss_bytes.fill(0);
        ss.zeroize();
        return BirdoPqStatus::Internal as i32;
    }
    ss_bytes.fill(0);
    ss.zeroize();

    // SAFETY: out_psk validated above.
    unsafe {
        std::ptr::copy_nonoverlapping(psk.as_ptr(), out_psk, BIRDO_PQ_PSK_LEN);
    }
    BirdoPqStatus::Ok as i32
}

// -- Implementation identity + stored-key triage --------------------------

/// Which ML-KEM implementation this library was built with, as a NUL-terminated
/// C string with static lifetime. Never null, never freed by the caller.
///
/// Android has had `nativeImplName` since the 1.4.25 SIGILL post-mortem and it
/// is the only field signal that attributes a native crash to an
/// implementation. iOS had no equivalent, so an Apple-side crash in the same
/// class was invisible in crash reports. It is `"mlkem1024-rustcrypto"` for
/// `RustCrypto` `ml-kem`; it was `"mlkem1024-clean"` when this crate linked
/// `PQClean`'s portable CLEAN C. Swift should attach it to crash metadata.
#[no_mangle]
pub extern "C" fn birdo_pq_impl_name() -> *const core::ffi::c_char {
    PQ_IMPL_NAME.as_ptr().cast::<core::ffi::c_char>()
}

/// Build-time implementation name, NUL-terminated for the C ABI.
const PQ_IMPL_NAME: &str = "mlkem1024-rustcrypto\0";

/// Is this Keychain-persisted secret key still loadable by the linked KEM?
///
/// Returns 1 for yes, 0 for no. `ml-kem` enforces FIPS 203 §7.3, where
/// `pqcrypto-mlkem` checked only the length, so a stored key that is corrupted
/// in a way that survives the length check is now a hard rejection rather than
/// a decapsulation to garbage. On 0 the Swift caller MUST delete the Keychain
/// item and generate a fresh keypair; otherwise the same unusable key is
/// retried on every connect forever.
#[no_mangle]
pub unsafe extern "C" fn birdo_pq_stored_key_usable(sk: *const u8, sk_len: usize) -> i32 {
    if sk.is_null() || sk_len != BIRDO_PQ_SECRET_KEY_LEN {
        return 0;
    }
    // SAFETY: caller asserts `sk` is valid for `sk_len` bytes; length checked.
    let sk_slice = unsafe { std::slice::from_raw_parts(sk, sk_len) };
    let Ok(enc) = StoredDk::try_from(sk_slice) else {
        return 0;
    };
    #[allow(deprecated)]
    let ok = DecapsulationKey::from_expanded_bytes(&enc).is_ok();
    i32::from(ok)
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

    let pk_obj = match Array::try_from(pk_slice) {
        Ok(enc) => match EncapsulationKey::new(&enc) {
            Ok(ek) => ek,
            // FIPS 203 §7.2: non-canonical coefficients are rejected, which
            // pqcrypto's length-only from_bytes accepted.
            Err(_) => return BirdoPqStatus::BadCiphertext as i32,
        },
        Err(_) => return BirdoPqStatus::BadCiphertext as i32,
    };
    // `encapsulate()` DOES still use the ambient-RNG unwrap: `kem` 0.3.0
    // declares no `TryEncapsulate`, and the only fallible door is
    // `encapsulate_deterministic`, behind the `hazmat` feature this crate
    // keeps out of its non-dev dependencies. This export is test-only (see the
    // doc comment above) and no shipped Swift path reaches it.
    let (ct, mut ss) = pk_obj.encapsulate();
    let mut ss_bytes = Zeroizing::new(ss.to_vec());
    let ct_bytes = ct;

    let mut psk = Zeroizing::new([0u8; BIRDO_PQ_PSK_LEN]);
    let hk = Hkdf::<Sha256>::new(Some(HKDF_SALT), &ss_bytes);
    if hk.expand(nonce_slice, psk.as_mut_slice()).is_err() {
        ss_bytes.fill(0);
        ss.zeroize();
        return BirdoPqStatus::Internal as i32;
    }
    ss_bytes.fill(0);
    ss.zeroize();

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

    include!("../../testdata/birdo_pq_kat_vectors.rs");

    fn unhex(s: &str) -> Vec<u8> {
        hex::decode(s).expect("fixture hex")
    }

    fn psk_via_c_abi(sk: &[u8], ct: &[u8], nonce: &[u8]) -> Result<Vec<u8>, i32> {
        let mut psk = vec![0u8; BIRDO_PQ_PSK_LEN];
        let r = unsafe {
            birdo_pq_derive_psk(
                sk.as_ptr(),
                sk.len(),
                ct.as_ptr(),
                ct.len(),
                nonce.as_ptr(),
                nonce.len(),
                psk.as_mut_ptr(),
                psk.len(),
            )
        };
        if r == 0 {
            Ok(psk)
        } else {
            Err(r)
        }
    }

    /// The only test in this crate that can detect byte-incompatibility with
    /// the PRODUCTION server, which encapsulates with `@noble/post-quantum` and
    /// not with Rust. Vectors: `native/testdata/birdo-pq-ml-kem-1024.kat.json`,
    /// a byte-for-byte copy of the backend's own fixture.
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

        // The production C-ABI path, on the server's own stored key.
        let psk = psk_via_c_abi(
            &unhex(NOBLE_DK_HEX),
            &unhex(NOBLE_CT_HEX),
            &unhex(NOBLE_NONCE_HEX),
        )
        .expect("derive_psk on the server fixture");
        assert_eq!(psk, unhex(NOBLE_PSK_HEX), "psk must equal presharedKeyB64");
    }

    /// The install-base guard: a real `PQClean`-produced 3168-byte key, which is
    /// what sits in the Keychain of every device that installed 1.4.29 or
    /// earlier. Never delete this test.
    #[test]
    fn stored_pqclean_dk_loads_and_derives_same_psk() {
        let sk = unhex(PQCLEAN_DK_HEX);
        assert_eq!(sk.len(), BIRDO_PQ_SECRET_KEY_LEN);
        assert_eq!(
            unsafe { birdo_pq_stored_key_usable(sk.as_ptr(), sk.len()) },
            1,
            "a PQClean-produced stored key must still be usable"
        );
        let psk = psk_via_c_abi(&sk, &unhex(PQCLEAN_CT_HEX), &unhex(PQCLEAN_NONCE_HEX))
            .expect("derive_psk on a PQClean-stored key");
        assert_eq!(psk, unhex(PQCLEAN_PSK_HEX), "the PSK must be unchanged");
    }

    /// `KeyExport::to_bytes()` returns a 64-byte Seed and panics for
    /// expanded-loaded keys. This is the assertion that makes either loud.
    #[test]
    fn stored_key_length_is_3168() {
        let mut pk = vec![0u8; BIRDO_PQ_PUBLIC_KEY_LEN];
        let mut sk = vec![0u8; BIRDO_PQ_SECRET_KEY_LEN];
        let r = unsafe {
            birdo_pq_generate_keypair(pk.as_mut_ptr(), pk.len(), sk.as_mut_ptr(), sk.len())
        };
        assert_eq!(r, 0);
        assert_eq!(sk.len(), 3168);
        // A 64-byte seed written into a 3168-byte buffer would leave the tail
        // zeroed; a real expanded key never is.
        assert_ne!(
            &sk[64..128],
            &[0u8; 64][..],
            "the store path wrote a seed, not an expanded key"
        );
        assert_eq!(
            unsafe { birdo_pq_stored_key_usable(sk.as_ptr(), sk.len()) },
            1
        );
    }

    /// FIPS 203 §7.3 is a NEW hard error on an existing install base, so it has
    /// to lead to a re-key rather than a connect-failure loop.
    #[test]
    fn corrupt_dk_returns_invalid_key_and_rekeys() {
        let mut corrupt = unhex(PQCLEAN_DK_HEX);
        corrupt[3100] ^= 0xff;
        assert_eq!(
            corrupt.len(),
            BIRDO_PQ_SECRET_KEY_LEN,
            "right length, wrong content"
        );
        assert_eq!(
            unsafe { birdo_pq_stored_key_usable(corrupt.as_ptr(), corrupt.len()) },
            0
        );
        assert_eq!(
            psk_via_c_abi(&corrupt, &unhex(PQCLEAN_CT_HEX), b"n"),
            Err(BirdoPqStatus::BadSecretKey as i32)
        );

        // Re-key: the caller discards and regenerates, and the fresh key works.
        let mut pk = vec![0u8; BIRDO_PQ_PUBLIC_KEY_LEN];
        let mut sk = vec![0u8; BIRDO_PQ_SECRET_KEY_LEN];
        assert_eq!(
            unsafe {
                birdo_pq_generate_keypair(pk.as_mut_ptr(), pk.len(), sk.as_mut_ptr(), sk.len())
            },
            0
        );
        assert_eq!(
            unsafe { birdo_pq_stored_key_usable(sk.as_ptr(), sk.len()) },
            1
        );
    }

    /// Implicit rejection is protocol behaviour: a wrong-but-valid key yields a
    /// stable, different PSK with no error and no panic.
    #[test]
    fn implicit_rejection_is_deterministic_and_silent() {
        let mut pk = vec![0u8; BIRDO_PQ_PUBLIC_KEY_LEN];
        let mut wrong = vec![0u8; BIRDO_PQ_SECRET_KEY_LEN];
        unsafe {
            birdo_pq_generate_keypair(pk.as_mut_ptr(), pk.len(), wrong.as_mut_ptr(), wrong.len())
        };
        let ct = unhex(PQCLEAN_CT_HEX);

        let a = psk_via_c_abi(&wrong, &ct, b"n").expect("no error on a wrong key");
        let b = psk_via_c_abi(&wrong, &ct, b"n").expect("no error on a wrong key");
        assert_eq!(a, b, "implicit rejection must be deterministic");

        let good = psk_via_c_abi(&unhex(PQCLEAN_DK_HEX), &ct, b"n").expect("real key");
        assert_ne!(a, good, "and must differ from the true PSK");
    }

    /// iOS had no equivalent of Android's `nativeImplName`, so an Apple-side
    /// crash in the `FEAT_SHA3` class could not name the implementation. It does
    /// now, and this asserts the name tracks the crate actually linked.
    #[test]
    fn pq_impl_name_matches_linked_crate() {
        let ptr = birdo_pq_impl_name();
        assert!(!ptr.is_null());
        let name = unsafe { std::ffi::CStr::from_ptr(ptr) }
            .to_str()
            .expect("impl name is UTF-8");
        assert_eq!(name, "mlkem1024-rustcrypto");
        assert_ne!(
            name, "mlkem1024-clean",
            "\"mlkem1024-clean\" means PQClean CLEAN C; this build links RustCrypto ml-kem"
        );

        // Exercised, not just named: only the linked KEM produces these.
        let mut pk = vec![0u8; BIRDO_PQ_PUBLIC_KEY_LEN];
        let mut sk = vec![0u8; BIRDO_PQ_SECRET_KEY_LEN];
        assert_eq!(
            unsafe {
                birdo_pq_generate_keypair(pk.as_mut_ptr(), pk.len(), sk.as_mut_ptr(), sk.len())
            },
            0
        );
    }

    /// `include/birdo_pq_ios.h` claimed to be "auto-checked against the Rust
    /// definitions by `cargo test`". It was not -- nothing read the header.
    /// Now something does: every `#[no_mangle] extern "C"` symbol in this file
    /// must be declared in the header the XCFramework ships, or Swift cannot
    /// call it and nobody finds out until link time on a Mac.
    #[test]
    fn c_header_declares_every_export() {
        let source = include_str!("lib.rs");
        let header = include_str!("../include/birdo_pq_ios.h");

        let mut exports: Vec<&str> = Vec::new();
        for line in source.lines() {
            let line = line.trim_start();
            let Some(rest) = line.strip_prefix("pub ") else {
                continue;
            };
            let rest = rest.strip_prefix("unsafe ").unwrap_or(rest);
            let Some(rest) = rest.strip_prefix("extern \"C\" fn ") else {
                continue;
            };
            let name = rest.split('(').next().expect("fn name");
            exports.push(name);
        }

        assert!(
            exports.len() >= 8,
            "expected the full C ABI surface, found {exports:?}"
        );
        for name in &exports {
            assert!(
                header.contains(name),
                "{name} is exported from lib.rs but not declared in birdo_pq_ios.h"
            );
        }
        assert!(exports.contains(&"birdo_pq_impl_name"));
        assert!(exports.contains(&"birdo_pq_stored_key_usable"));
    }

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
                pk.as_ptr(),
                pk.len(),
                ct.as_mut_ptr(),
                ct.len(),
                server_psk.as_mut_ptr(),
                server_psk.len(),
                nonce.as_ptr(),
                nonce.len(),
            )
        };
        assert_eq!(r, 0);

        let mut client_psk = vec![0u8; BIRDO_PQ_PSK_LEN];
        let r = unsafe {
            birdo_pq_derive_psk(
                sk.as_ptr(),
                sk.len(),
                ct.as_ptr(),
                ct.len(),
                nonce.as_ptr(),
                nonce.len(),
                client_psk.as_mut_ptr(),
                client_psk.len(),
            )
        };
        assert_eq!(r, 0);

        assert_eq!(client_psk, server_psk);
    }

    #[test]
    fn rejects_wrong_sk_size() {
        let bad_sk = [0u8; 32];
        let ct = vec![0u8; BIRDO_PQ_CIPHERTEXT_LEN];
        let mut psk = vec![0u8; BIRDO_PQ_PSK_LEN];
        let r = unsafe {
            birdo_pq_derive_psk(
                bad_sk.as_ptr(),
                bad_sk.len(),
                ct.as_ptr(),
                ct.len(),
                std::ptr::null(),
                0,
                psk.as_mut_ptr(),
                psk.len(),
            )
        };
        assert_eq!(r, BirdoPqStatus::BadSecretKey as i32);
    }

    #[test]
    fn rejects_wrong_ct_size() {
        let mut pk = vec![0u8; BIRDO_PQ_PUBLIC_KEY_LEN];
        let mut sk = vec![0u8; BIRDO_PQ_SECRET_KEY_LEN];
        unsafe { birdo_pq_generate_keypair(pk.as_mut_ptr(), pk.len(), sk.as_mut_ptr(), sk.len()) };
        let bad_ct = [0u8; 16];
        let mut psk = vec![0u8; BIRDO_PQ_PSK_LEN];
        let r = unsafe {
            birdo_pq_derive_psk(
                sk.as_ptr(),
                sk.len(),
                bad_ct.as_ptr(),
                bad_ct.len(),
                std::ptr::null(),
                0,
                psk.as_mut_ptr(),
                psk.len(),
            )
        };
        assert_eq!(r, BirdoPqStatus::BadCiphertext as i32);
    }

    #[test]
    fn null_pointer_rejected() {
        let r = unsafe {
            birdo_pq_generate_keypair(
                std::ptr::null_mut(),
                BIRDO_PQ_PUBLIC_KEY_LEN,
                std::ptr::null_mut(),
                BIRDO_PQ_SECRET_KEY_LEN,
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
