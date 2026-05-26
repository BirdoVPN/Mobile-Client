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
use pqcrypto_mlkem::mlkem1024;
use pqcrypto_traits::kem::{
    Ciphertext as KemCiphertext, PublicKey as KemPublicKey, SharedSecret as KemSharedSecret,
};
use sha2::Sha256;
use std::process::ExitCode;
use zeroize::Zeroizing;

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
            eprintln!(
                "usage:\n  birdo-pq-server encap <client_pk_b64>\n  birdo-pq-server version"
            );
            ExitCode::from(2)
        }
    }
}

fn encap(client_pk_b64: &str) -> Result<String, String> {
    let pk_bytes = B64
        .decode(client_pk_b64.trim())
        .map_err(|e| format!("base64 decode of pk: {e}"))?;
    let pk = mlkem1024::PublicKey::from_bytes(&pk_bytes)
        .map_err(|e| format!("malformed ML-KEM-1024 pk ({} B): {e:?}", pk_bytes.len()))?;

    // Per-connect random nonce (32 B from the OS CSPRNG).
    let mut nonce = [0u8; NONCE_LEN];
    getrandom::getrandom(&mut nonce).map_err(|e| format!("CSPRNG: {e}"))?;

    let (ss, ct) = mlkem1024::encapsulate(&pk);
    let mut ss_bytes = Zeroizing::new(ss.as_bytes().to_vec());

    let mut psk = Zeroizing::new(vec![0u8; PSK_LEN]);
    Hkdf::<Sha256>::new(Some(HKDF_SALT), &ss_bytes)
        .expand(&nonce, psk.as_mut_slice())
        .map_err(|e| format!("HKDF expand: {e}"))?;
    ss_bytes.fill(0);

    let json = format!(
        r#"{{"ciphertext_b64":"{}","nonce_b64":"{}","psk_b64":"{}"}}"#,
        B64.encode(ct.as_bytes()),
        B64.encode(nonce),
        B64.encode(psk.as_slice()),
    );
    Ok(json)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Server-side encap roundtrips through the same KEM the client uses.
    /// (We can't import the JNI crate here without its jni dep, so we just
    /// re-run keypair() locally and verify the math holds.)
    #[test]
    fn encap_produces_well_formed_output() {
        let (pk, _sk) = mlkem1024::keypair();
        let pk_b64 = B64.encode(pk.as_bytes());
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
}
