// BirdoPQ v1 — ML-KEM-1024 PSK derivation (C ABI for iOS / Swift).
//
// `c_header_declares_every_export` in src/lib.rs asserts that every
// #[no_mangle] extern "C" symbol in the Rust source is declared here (it did
// NOT before 2026-09, despite this comment claiming so). Signatures
// themselves are still on you: if you change one here, change it in
// `src/lib.rs` and re-run `scripts/build-birdo-pq-xcframework.sh`.

#ifndef BIRDO_PQ_IOS_H
#define BIRDO_PQ_IOS_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// FIPS 203 ML-KEM-1024 sizes (also returned by the *_len() accessors).
#define BIRDO_PQ_PUBLIC_KEY_LEN  1568
#define BIRDO_PQ_SECRET_KEY_LEN  3168
#define BIRDO_PQ_CIPHERTEXT_LEN  1568
#define BIRDO_PQ_PSK_LEN         32

// Status codes — keep in sync with `BirdoPqStatus` in src/lib.rs.
#define BIRDO_PQ_OK                  0
#define BIRDO_PQ_ERR_NULL           -1
#define BIRDO_PQ_ERR_BUF_SIZE       -2
#define BIRDO_PQ_ERR_BAD_SECRET_KEY -3
#define BIRDO_PQ_ERR_BAD_CIPHERTEXT -4
#define BIRDO_PQ_ERR_INTERNAL       -99

size_t birdo_pq_public_key_len(void);
size_t birdo_pq_secret_key_len(void);
size_t birdo_pq_ciphertext_len(void);
size_t birdo_pq_psk_len(void);

// Generate a fresh ML-KEM-1024 keypair into caller-owned buffers.
int32_t birdo_pq_generate_keypair(
    uint8_t* out_pk, size_t out_pk_len,
    uint8_t* out_sk, size_t out_sk_len);

// Decapsulate the server-supplied ciphertext with the persistent client
// secret key, then HKDF-SHA-256 the shared secret through `nonce` into a
// 32-byte WireGuard PSK.
int32_t birdo_pq_derive_psk(
    const uint8_t* sk, size_t sk_len,
    const uint8_t* ct, size_t ct_len,
    const uint8_t* nonce, size_t nonce_len,
    uint8_t* out_psk, size_t out_psk_len);

// Test-only encapsulator. Exported so a round trip can be exercised without
// the backend. NOTE: no Swift code calls it -- the round trip is exercised by
// `cargo test` in this crate instead, and scripts/check_pq_ios_wiring.sh lists
// it (with the four *_len accessors) as the only exports allowed to have no
// Swift call site. Do not call from production code.
int32_t birdo_pq_test_encapsulate(
    const uint8_t* pk, size_t pk_len,
    uint8_t* out_ct, size_t out_ct_len,
    uint8_t* out_psk, size_t out_psk_len,
    const uint8_t* nonce, size_t nonce_len);

// Which ML-KEM implementation this library was built with, as a
// NUL-terminated static C string: "mlkem1024-rustcrypto" for RustCrypto
// ml-kem, "mlkem1024-clean" for the PQClean CLEAN C it replaced. Never null;
// the caller must not free it. Android has had the equivalent
// (nativeImplName) since the 1.4.25 SIGILL post-mortem; without this, an
// Apple-side crash in the same class could not name the implementation.
// Attach it to crash metadata.
const char* birdo_pq_impl_name(void);

// Is a Keychain-persisted 3168-byte secret key still loadable by the linked
// KEM? Returns 1 for yes, 0 for no.
//
// ml-kem enforces FIPS 203 section 7.3 -- the expanded key embeds H(ek),
// which is recomputed on load and compared -- where the previous
// implementation checked only the length. A key that fails this is NOT
// recoverable, so a 0 here (and likewise BIRDO_PQ_ERR_BAD_SECRET_KEY from
// birdo_pq_derive_psk on a correctly-sized key) means "delete the Keychain
// item and generate a fresh keypair", not "retry". The server re-pins the new
// public key on the next handshake.
int32_t birdo_pq_stored_key_usable(const uint8_t* sk, size_t sk_len);

#ifdef __cplusplus
}
#endif

#endif  // BIRDO_PQ_IOS_H
