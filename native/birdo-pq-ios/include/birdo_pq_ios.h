// BirdoPQ v1 — ML-KEM-1024 PSK derivation (C ABI for iOS / Swift).
//
// Auto-checked against the Rust definitions by `cargo test` in
// birdo-pq-ios. If you change a signature here, change it in
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

// Test-only encapsulator — used by Swift unit tests for round-trip
// validation. Do not call from production code.
int32_t birdo_pq_test_encapsulate(
    const uint8_t* pk, size_t pk_len,
    uint8_t* out_ct, size_t out_ct_len,
    uint8_t* out_psk, size_t out_psk_len,
    const uint8_t* nonce, size_t nonce_len);

#ifdef __cplusplus
}
#endif

#endif  // BIRDO_PQ_IOS_H
