//! `BirdoPQ` post-quantum `WireGuard` PSK derivation.
//!
//! ## Why this exists
//!
//! The "vendor upstream rosenpass" path is blocked on Android: `rosenpass`
//! transitively requires `libsodium` as a system C library, which would
//! mean cross-compiling libsodium for arm64-v8a / armeabi-v7a / `x86_64`
//! before we can even start. Worse, the upstream `rosenpass` binary expects
//! a static `[[peers]]` block per known peer, which doesn't work for a VPN
//! with thousands of clients.
//!
//! Instead we ship the same cryptographic guarantee using a minimal
//! Mullvad-style KEM-only protocol that we control end-to-end. The PSK is
//! still derived from a NIST-standardised post-quantum KEM (ML-KEM-1024,
//! FIPS 203), so "post-quantum `WireGuard` PSK" is an accurate description
//! and the HNDL threat is genuinely defeated.
//!
//! ## Protocol — `BirdoPQ` v1
//!
//! ```text
//! Client                                            Server
//! ┌──────────────────────────────────────────────────────────┐
//! │ 1. (one-time) generate ML-KEM-1024 keypair (pk_c, sk_c)  │
//! │    persist via RosenpassKeyStore (sk_c in EncryptedFile) │
//! └──────────────────────────────────────────────────────────┘
//!
//!   POST /connect { ... pq_client_public_key: pk_c (base64) }  ──▶
//!
//!                                                ┌────────────────────┐
//!                                                │ 2. encap(pk_c)     │
//!                                                │   → (ct, ss)       │
//!                                                │   nonce_s = rand   │
//!                                                │   psk = HKDF(ss,   │
//!                                                │     nonce_s,       │
//!                                                │     "BirdoPQ v1")  │
//!                                                └────────────────────┘
//!
//!   ◀──  ConnectResponse {
//!            ...,
//!            quantumEnabled: true,
//!            rosenpassPublicKey: ct  (base64),       // re-used field name
//!            rosenpassEndpoint: nonce_s  (base64),   // re-used field name
//!            presharedKey: <classic random PSK fallback>
//!        }
//!
//! ┌──────────────────────────────────────────────────────────┐
//! │ 3. decap(sk_c, ct) → ss                                  │
//! │    psk = HKDF(ss, nonce_s, "BirdoPQ v1")                 │
//! │    inject psk into WireGuard PresharedKey                │
//! └──────────────────────────────────────────────────────────┘
//! ```
//!
//! Both sides now hold the SAME 32-byte PSK derived from a PQ-KEM. An attacker
//! recording today's TLS API traffic and tomorrow's `WireGuard` handshake cannot
//! recover the PSK without first breaking ML-KEM-1024 — which by definition
//! requires a CRQC capable of solving Module-LWE, the lattice problem ML-KEM
//! reduces to.
//!
//! ## Reusing existing API field names
//!
//! `rosenpassPublicKey` and `rosenpassEndpoint` are repurposed to carry the
//! ML-KEM ciphertext and per-connect nonce respectively. This avoids a
//! breaking schema change and keeps backwards compat with existing
//! `ConnectResponse` deserialisation. They are NOT semantically the same as
//! the upstream-Rosenpass fields anymore — see the kdoc on `RosenpassNative`.

#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::pedantic)]
#![allow(clippy::missing_errors_doc, clippy::missing_panics_doc)]

use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jlongArray, jobjectArray};
use jni::JNIEnv;
use log::LevelFilter;
use std::sync::Once;
use zeroize::Zeroizing;

mod errors;
mod handshake;

use errors::throw_runtime;

const TAG: &str = "RosenpassJNI";
const PSK_LEN_BYTES: usize = 32;

static INIT_LOGGER: Once = Once::new();

fn init_logger_once() {
    INIT_LOGGER.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(LevelFilter::Info)
                .with_tag(TAG),
        );
        log::info!(
            "rosenpass-jni v{} initialised (BirdoPQ v1)",
            env!("CARGO_PKG_VERSION")
        );
    });
}

#[no_mangle]
pub extern "system" fn Java_app_birdo_vpn_service_RosenpassNative_nativeVersion<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> JString<'a> {
    init_logger_once();
    let v = format!(
        "rosenpass-jni {} (BirdoPQ v1, ML-KEM-1024, {}, {})",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::ARCH,
        if cfg!(debug_assertions) {
            "debug"
        } else {
            "release"
        }
    );
    // AUDIT-JNI-1: never panic across the FFI boundary. Both allocations are
    // best-effort — if the JVM is so memory-starved that even an empty
    // string allocation fails we hand back a null JString and let the
    // Kotlin caller treat it as "version unavailable" instead of aborting
    // the whole process.
    match env.new_string(v) {
        Ok(s) => s,
        Err(_) => match env.new_string("") {
            Ok(s) => s,
            Err(e) => {
                log::error!(target: TAG, "nativeVersion: JVM string alloc failed: {e}");
                JString::from(unsafe { JObject::from_raw(std::ptr::null_mut()) })
            }
        },
    }
}

/// Which ML-KEM implementation this library was built with.
///
/// Returns the literal `"mlkem1024-rustcrypto"`: `RustCrypto`'s pure-Rust
/// `ml-kem`, exact-pinned, on every ABI (see the decision record in
/// Cargo.toml). Before 1.4.30 this was `"mlkem1024-clean"`, `PQClean`'s
/// portable CLEAN C -- the two values are how a native crash report is
/// attributed to an implementation, and why this constant MUST change with the
/// dependency. `pq_impl_name_matches_linked_crate` asserts it does.
///
/// The value is a build-time fact about this crate, not a probe -- there is
/// deliberately no KEM self-test at load, because a SIGILL in a self-test would
/// only move the crash from the first connect to app start. What makes the
/// literal true is `scripts/check_pq_features.sh` (`pqcrypto-mlkem` absent from
/// every crate and target graph, `ml-kem` resolved to exactly the pinned
/// version and feature set, and the soft-Keccak cfg present only in
/// `.cargo/config.toml`) and `scripts/check_no_sha3_ext.sh` (the shipped .so
/// carries no optional-extension opcode). Kotlin puts this on Sentry as
/// `birdo.pq.impl` so a native crash report says which implementation was
/// running.
#[no_mangle]
pub extern "system" fn Java_app_birdo_vpn_service_RosenpassNative_nativeImplName<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> JString<'a> {
    match env.new_string(PQ_IMPL_NAME) {
        Ok(s) => s,
        Err(e) => {
            log::error!(target: TAG, "nativeImplName: JVM string alloc failed: {e}");
            JString::from(unsafe { JObject::from_raw(std::ptr::null_mut()) })
        }
    }
}

/// The kernel's view of this CPU's optional ISA extensions:
/// `[getauxval(AT_HWCAP), getauxval(AT_HWCAP2)]`, or `[0, 0]` where there is
/// no auxv (non-Android/Linux hosts, e.g. the JVM unit-test runner).
///
/// Why from native code: `getauxval` is not reachable from Java and
/// `/proc/self/auxv` is not readable by an app. `/proc/cpuinfo` exposes the
/// same bits as words, but the raw HWCAP is what the loader, the Go runtime
/// (`internal/cpu`) and compiler-rt's outlined atomics all consult, so it is
/// the value a crash should be correlated against. Read-only, no allocation
/// beyond the 16-byte result, one call each; safe to run at library load.
///
/// The bit meanings are the Linux uapi `asm/hwcap.h` for the running ABI --
/// on arm64, `HWCAP_SHA3` is bit 17 of `AT_HWCAP`, which is precisely the bit
/// every device in the 1.4.25 crash cluster had CLEAR. The Kotlin side
/// (`app.birdo.vpn.utils.CpuFeatures`) decodes them; this function does not
/// interpret anything.
#[no_mangle]
pub extern "system" fn Java_app_birdo_vpn_service_RosenpassNative_nativeCpuFeatures<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> jlongArray {
    let (hwcap, hwcap2) = hwcap_words();
    // AUDIT-JNI-1: never panic across the FFI boundary. A failed allocation
    // returns null; Kotlin treats null as "unavailable" and logs nothing wrong.
    let arr = match env.new_long_array(2) {
        Ok(a) => a,
        Err(e) => {
            log::error!(target: TAG, "nativeCpuFeatures: JVM long[] alloc failed: {e}");
            return std::ptr::null_mut();
        }
    };
    // HWCAP words are unsigned; they are handed over as the same 64 bits and
    // reinterpreted as unsigned on the Kotlin side (`toULong()`).
    #[allow(clippy::cast_possible_wrap)]
    let words: [jlong; 2] = [hwcap as jlong, hwcap2 as jlong];
    if let Err(e) = env.set_long_array_region(&arr, 0, &words) {
        log::error!(target: TAG, "nativeCpuFeatures: set_long_array_region failed: {e}");
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

/// Build-time implementation name reported by [`Java_app_birdo_vpn_service_RosenpassNative_nativeImplName`].
///
/// This is the estate's ONLY field signal for attributing a native crash to a
/// KEM implementation. It changes whenever the KEM crate changes.
const PQ_IMPL_NAME: &str = "mlkem1024-rustcrypto";

#[cfg(any(target_os = "android", target_os = "linux"))]
fn hwcap_words() -> (u64, u64) {
    // SAFETY: getauxval takes an integer key and returns an integer; it does
    // not touch memory we own and cannot fail in a way that needs handling
    // (an unknown key yields 0 and sets errno, which we do not read).
    let hwcap = unsafe { libc::getauxval(libc::AT_HWCAP) };
    let hwcap2 = unsafe { libc::getauxval(libc::AT_HWCAP2) };
    #[allow(clippy::unnecessary_cast)] // c_ulong is u32 on 32-bit ABIs
    (hwcap as u64, hwcap2 as u64)
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn hwcap_words() -> (u64, u64) {
    (0, 0)
}

/// Generate a long-lived ML-KEM-1024 keypair for the client.
/// Returns `[publicKey (~1568 B), secretKey (~3168 B)]`.
#[no_mangle]
pub extern "system" fn Java_app_birdo_vpn_service_RosenpassNative_nativeGenerateKeypair<'a>(
    mut env: JNIEnv<'a>,
    class: JClass<'a>,
) -> jobjectArray {
    init_logger_once();

    match handshake::generate_keypair() {
        Ok(kp) => {
            let byte_array_class = match env.find_class("[B") {
                Ok(c) => c,
                Err(e) => {
                    throw_runtime(&mut env, &format!("find_class([B) failed: {e}"));
                    return std::ptr::null_mut();
                }
            };
            let outer = match env.new_object_array(2, byte_array_class, JObject::null()) {
                Ok(arr) => arr,
                Err(e) => {
                    throw_runtime(&mut env, &format!("alloc byte[][] failed: {e}"));
                    return std::ptr::null_mut();
                }
            };
            let pk_jb = match env.byte_array_from_slice(&kp.public_key) {
                Ok(b) => b,
                Err(e) => {
                    throw_runtime(&mut env, &format!("alloc pk bytes failed: {e}"));
                    return std::ptr::null_mut();
                }
            };
            let sk_jb = match env.byte_array_from_slice(kp.secret_key.as_ref()) {
                Ok(b) => b,
                Err(e) => {
                    throw_runtime(&mut env, &format!("alloc sk bytes failed: {e}"));
                    return std::ptr::null_mut();
                }
            };
            if env.set_object_array_element(&outer, 0, pk_jb).is_err()
                || env.set_object_array_element(&outer, 1, sk_jb).is_err()
            {
                throw_runtime(&mut env, "set_object_array_element failed");
                return std::ptr::null_mut();
            }
            let _ = class;
            outer.into_raw()
        }
        Err(e) => {
            throw_runtime(&mut env, &format!("generate_keypair: {e}"));
            std::ptr::null_mut()
        }
    }
}

/// Decapsulate the server-supplied ML-KEM ciphertext and derive the 32-byte
/// `WireGuard` PSK. Returns null on any failure (caller falls back gracefully).
#[no_mangle]
pub extern "system" fn Java_app_birdo_vpn_service_RosenpassNative_nativeDeriveSharedPsk<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    client_secret_key: JByteArray<'a>,
    server_ciphertext: JByteArray<'a>,
    server_nonce: JByteArray<'a>,
) -> JByteArray<'a> {
    init_logger_once();

    let sk = match env.convert_byte_array(&client_secret_key) {
        Ok(b) => Zeroizing::new(b),
        Err(e) => {
            log::warn!(target: TAG, "read client_secret_key failed: {e}");
            return JObject::null().into();
        }
    };
    let ct = match env.convert_byte_array(&server_ciphertext) {
        Ok(b) => b,
        Err(e) => {
            log::warn!(target: TAG, "read server_ciphertext failed: {e}");
            return JObject::null().into();
        }
    };
    let nonce = match env.convert_byte_array(&server_nonce) {
        Ok(b) => b,
        Err(e) => {
            log::warn!(target: TAG, "read server_nonce failed: {e}");
            return JObject::null().into();
        }
    };

    match handshake::derive_psk(&sk, &ct, &nonce) {
        Ok(psk) => {
            debug_assert_eq!(psk.len(), PSK_LEN_BYTES);
            env.byte_array_from_slice(&psk)
                .unwrap_or_else(|_| JObject::null().into())
        }
        Err(e) => {
            log::warn!(target: TAG, "derive_psk failed: {e} — caller will fallback");
            JObject::null().into()
        }
    }
}

/// Server-side encapsulation, exposed via JNI ONLY for unit tests that
/// exercise the full client↔server roundtrip in-process. Production
/// server-side code uses `native/birdo-pq-server/` instead.
#[no_mangle]
pub extern "system" fn Java_app_birdo_vpn_service_RosenpassNative_nativeEncapsulateForServer<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    client_public_key: JByteArray<'a>,
    server_nonce: JByteArray<'a>,
) -> jobjectArray {
    init_logger_once();

    let pk = match env.convert_byte_array(&client_public_key) {
        Ok(b) => b,
        Err(e) => {
            throw_runtime(&mut env, &format!("read client_public_key: {e}"));
            return std::ptr::null_mut();
        }
    };
    let nonce = match env.convert_byte_array(&server_nonce) {
        Ok(b) => b,
        Err(e) => {
            throw_runtime(&mut env, &format!("read server_nonce: {e}"));
            return std::ptr::null_mut();
        }
    };

    match handshake::encapsulate(&pk, &nonce) {
        Ok((ct, psk)) => {
            let Ok(byte_array_class) = env.find_class("[B") else {
                return std::ptr::null_mut();
            };
            let Ok(outer) = env.new_object_array(2, byte_array_class, JObject::null()) else {
                return std::ptr::null_mut();
            };
            let ct_jb = env.byte_array_from_slice(&ct).unwrap_or_default();
            let psk_jb = env.byte_array_from_slice(&psk).unwrap_or_default();
            if env.set_object_array_element(&outer, 0, ct_jb).is_err()
                || env.set_object_array_element(&outer, 1, psk_jb).is_err()
            {
                return std::ptr::null_mut();
            }
            outer.into_raw()
        }
        Err(e) => {
            throw_runtime(&mut env, &format!("encapsulate: {e}"));
            std::ptr::null_mut()
        }
    }
}

/// Is this persisted secret key still loadable by the linked KEM?
///
/// `ml-kem` enforces FIPS 203 §7.3 -- the 3168-byte expanded decapsulation key
/// embeds `H(ek)`, which is recomputed on load and compared. `pqcrypto-mlkem`
/// checked only the length, so a stored key corrupted in a way that survived
/// the length check used to decapsulate to garbage and fail later as a
/// `WireGuard` handshake that never completed. It is now a clean rejection, and a
/// clean rejection on an existing install must lead to a re-key rather than to
/// the same unusable key being retried on every connect forever.
///
/// Returns `false` on any read failure too: the caller's only reaction either
/// way is to discard and regenerate, which is safe (the server re-pins the new
/// public key on the next handshake).
#[no_mangle]
pub extern "system" fn Java_app_birdo_vpn_service_RosenpassNative_nativeStoredKeyUsable<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    client_secret_key: JByteArray<'a>,
) -> jboolean {
    init_logger_once();

    let sk = match env.convert_byte_array(&client_secret_key) {
        Ok(b) => Zeroizing::new(b),
        Err(e) => {
            log::warn!(target: TAG, "nativeStoredKeyUsable: read secret key failed: {e}");
            return u8::from(false);
        }
    };
    u8::from(handshake::stored_secret_key_is_usable(&sk))
}

#[no_mangle]
pub extern "system" fn Java_app_birdo_vpn_service_RosenpassNative_nativePskLength<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> jint {
    // PSK_LEN_BYTES is the compile-time constant 32, so this conversion is
    // exact on every ABI. The allow is narrow and local rather than a widened
    // lint level, matching nativeCpuFeatures above.
    #[allow(clippy::cast_possible_truncation, clippy::cast_possible_wrap)]
    {
        PSK_LEN_BYTES as jint
    }
}

#[cfg(test)]
mod tests {
    use super::PQ_IMPL_NAME;

    /// `nativeImplName` is surfaced into Sentry as `birdo.pq.impl` and is the
    /// only thing that tells a native crash report which ML-KEM implementation
    /// was running. When the 1.4.25 SIGILL was attributed, that attribution
    /// rested on a human reading docs/SENTRY-SETUP.md -- there was no test.
    ///
    /// This asserts the constant names the crate that is actually linked, by
    /// calling into it: `ml_kem::MlKem1024`'s parameter sizes are compiled in
    /// from the ml-kem crate, so this cannot pass if the KEM were swapped back
    /// without updating the constant.
    #[test]
    fn pq_impl_name_matches_linked_crate() {
        assert_eq!(
            PQ_IMPL_NAME, "mlkem1024-rustcrypto",
            "PQ_IMPL_NAME must name the linked KEM implementation"
        );

        // The linked crate, exercised rather than named: only ml-kem produces
        // these encodings through this API surface.
        let kp = crate::handshake::generate_keypair().expect("keypair from the linked KEM");
        assert_eq!(kp.public_key.len(), crate::handshake::EK_LEN);
        assert_eq!(kp.secret_key.len(), crate::handshake::DK_LEN);

        assert!(
            PQ_IMPL_NAME.starts_with("mlkem1024-"),
            "the impl name must stay parseable as <algorithm>-<implementation>"
        );
        assert_ne!(
            PQ_IMPL_NAME, "mlkem1024-clean",
            "\"mlkem1024-clean\" means PQClean CLEAN C; this build links RustCrypto ml-kem"
        );
    }
}
