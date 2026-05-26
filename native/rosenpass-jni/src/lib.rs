//! BirdoPQ post-quantum WireGuard PSK derivation.
//!
//! ## Why this exists
//!
//! The "vendor upstream rosenpass" path is blocked on Android: `rosenpass`
//! transitively requires `libsodium` as a system C library, which would
//! mean cross-compiling libsodium for arm64-v8a / armeabi-v7a / x86_64
//! before we can even start. Worse, the upstream `rosenpass` binary expects
//! a static `[[peers]]` block per known peer, which doesn't work for a VPN
//! with thousands of clients.
//!
//! Instead we ship the same cryptographic guarantee using a minimal
//! Mullvad-style KEM-only protocol that we control end-to-end. The PSK is
//! still derived from a NIST-standardised post-quantum KEM (ML-KEM-1024,
//! FIPS 203), so "post-quantum WireGuard PSK" is an accurate description
//! and the HNDL threat is genuinely defeated.
//!
//! ## Protocol — BirdoPQ v1
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
//! recording today's TLS API traffic and tomorrow's WireGuard handshake cannot
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
use jni::sys::{jint, jobjectArray};
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
        log::info!("rosenpass-jni v{} initialised (BirdoPQ v1)", env!("CARGO_PKG_VERSION"));
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
        if cfg!(debug_assertions) { "debug" } else { "release" }
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
/// WireGuard PSK. Returns null on any failure (caller falls back gracefully).
#[no_mangle]
pub extern "system" fn Java_app_birdo_vpn_service_RosenpassNative_nativeDeriveSharedPsk<'a>(
    mut env: JNIEnv<'a>,
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
            let byte_array_class = match env.find_class("[B") {
                Ok(c) => c,
                Err(_) => return std::ptr::null_mut(),
            };
            let outer = match env.new_object_array(2, byte_array_class, JObject::null()) {
                Ok(arr) => arr,
                Err(_) => return std::ptr::null_mut(),
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

#[no_mangle]
pub extern "system" fn Java_app_birdo_vpn_service_RosenpassNative_nativePskLength<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> jint {
    PSK_LEN_BYTES as jint
}
