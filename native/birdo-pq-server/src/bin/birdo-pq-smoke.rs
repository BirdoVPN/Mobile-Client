//! `birdo-pq-smoke` — run the whole BirdoPQ v1 KEM on this CPU and say whether
//! it produced the right answer.
//!
//! ## Why this binary exists
//!
//! The 1.4.25 SIGILL was proved, and its fix was proved, by running an aarch64
//! binary under `qemu-aarch64 -cpu cortex-a53` — a core WITHOUT ARMv8.2
//! FEAT_SHA3 — and watching the exit code. Exit 132 is SIGILL. No static
//! analysis produces that answer, and no lens that only reads disassembly can
//! tell you whether a runtime CPU check actually runs.
//!
//! The KEM changed in 2026-09 from PQClean's portable CLEAN C to RustCrypto
//! `ml-kem`. That did NOT remove the FEAT_SHA3 opcodes: `ml-kem → sha3 0.11 →
//! keccak 0.2.2` ships `backends/aarch64_sha3.rs` with the same four mnemonics
//! PQClean's `keccak2x/feat.S` had. What it added is the runtime gate PQClean's
//! aarch64 path lacked — `cpufeatures`' `getauxval(AT_HWCAP) &
//! (HWCAP_SHA3|HWCAP_SHA512)`. That gate is now a single point of failure whose
//! wrong answer is an instant crash on seven device models, so it gets executed,
//! not read.
//!
//! ## What CI asserts with it (`.github/workflows/android.yml`)
//!
//! 1. `-cpu cortex-a53` (no FEAT_SHA3): exit 0. The gate took the soft path.
//! 2. `-cpu max` (FEAT_SHA3 present): exit 0 AND the same PSK. The fast path
//!    computes the same answer, so the dispatch is not just safe but correct.
//! 3. Rebuilt with `RUSTFLAGS='-C target-feature=+sha3'`, `-cpu cortex-a53`:
//!    exit 132. `cpufeatures`' `__unless_target_features!` elides the HWCAP
//!    check when the feature is statically enabled, so the fast path runs
//!    unconditionally and SIGILLs. If that run does NOT crash, the harness is
//!    not exercising Keccak at all and every other result here is worthless.
//!
//! Output is deterministic (the KAT half) plus one random round trip, so runs
//! on different cores are directly comparable line by line.
//!
//! ```text
//! cargo run --bin birdo-pq-smoke
//! KAT_EK_OK
//! KAT_DK_OK
//! KAT_SS_OK
//! KAT_PSK=26b9198c86c74e2f7a49331c4b55c7cc40b3aa63442c66580e656c1d4012e933
//! RANDOM_ROUNDTRIP_OK
//! STORED_PQCLEAN_PSK=0dbff86489cb94c97cebfd18af9ef0f20aab166a37199b3c5ab7256e5c81a0ed
//! SMOKE_OK
//! ```

use hkdf::Hkdf;
use ml_kem::array::Array;
use ml_kem::kem::{Decapsulate, Encapsulate, Generate, KeyExport};
use ml_kem::ml_kem_1024::{DecapsulationKey, EncapsulationKey};
#[allow(deprecated)]
use ml_kem::ExpandedKeyEncoding;
use ml_kem::{ExpandedDecapsulationKey, MlKem1024};
use sha2::Sha256;
use std::process::ExitCode;
use zeroize::Zeroizing;

include!("../../../testdata/birdo_pq_kat_vectors.rs");

type StoredDk = ExpandedDecapsulationKey<MlKem1024>;

const HKDF_SALT: &[u8] = b"BirdoPQ-v1-PSK";

fn unhex(s: &str) -> Vec<u8> {
    hex::decode(s).expect("fixture hex")
}

fn psk_of(ss: &[u8], nonce: &[u8]) -> Zeroizing<Vec<u8>> {
    let mut psk = Zeroizing::new(vec![0u8; 32]);
    Hkdf::<Sha256>::new(Some(HKDF_SALT), ss)
        .expand(nonce, psk.as_mut_slice())
        .expect("HKDF length OK (RFC 5869)");
    psk
}

fn load_stored(bytes: &[u8]) -> DecapsulationKey {
    let enc = StoredDk::try_from(bytes).expect("3168-byte expanded decapsulation key");
    #[allow(deprecated)]
    let dk = DecapsulationKey::from_expanded_bytes(&enc).expect("stored key passes FIPS 203 7.3");
    dk
}

fn main() -> ExitCode {
    let mut failed = false;
    fn check(name: &str, ok: bool, failed: &mut bool) {
        if ok {
            println!("{name}_OK");
        } else {
            eprintln!("FAIL: {name}");
            *failed = true;
        }
    }

    // ── 1. Deterministic: the @noble server fixture, end to end ───────────
    let seed = Array::try_from(&unhex(NOBLE_KEYGEN_SEED_HEX)[..]).expect("64-byte seed");
    let dk = DecapsulationKey::from_seed(seed);
    check(
        "KAT_EK",
        dk.encapsulation_key().to_bytes().as_slice() == &unhex(NOBLE_EK_HEX)[..],
        &mut failed,
    );
    #[allow(deprecated)]
    let stored = dk.to_expanded_bytes();
    check(
        "KAT_DK",
        stored.as_slice() == &unhex(NOBLE_DK_HEX)[..],
        &mut failed,
    );

    let ct = Array::try_from(&unhex(NOBLE_CT_HEX)[..]).expect("1568-byte ciphertext");
    let ss = dk.decapsulate(&ct);
    check(
        "KAT_SS",
        ss.as_slice() == &unhex(NOBLE_SS_HEX)[..],
        &mut failed,
    );

    let psk = psk_of(&ss, &unhex(NOBLE_NONCE_HEX));
    println!("KAT_PSK={}", hex::encode(psk.as_slice()));
    if psk.as_slice() != &unhex(NOBLE_PSK_HEX)[..] {
        eprintln!("FAIL: KAT_PSK does not match the committed fixture");
        failed = true;
    }

    // ── 2. Random: keygen + encapsulate + decapsulate on THIS cpu ─────────
    //
    // This half is what makes the run a CPU test rather than a vector replay:
    // it takes entropy from the OS, runs keygen (the call that SIGILLed in
    // 1.4.25) and both KEM directions.
    // `try_generate()`, not `generate()`: the latter is the ambient-RNG
    // unwrap that aborts the process. Same call the two shipped crates make,
    // so this binary exercises the real keygen path.
    let fresh = DecapsulationKey::try_generate().expect("system RNG");
    let ek = EncapsulationKey::new(&fresh.encapsulation_key().to_bytes())
        .expect("our own encapsulation key round-trips");
    let (ct2, ss_enc) = ek.encapsulate();
    let ss_dec = fresh.decapsulate(&ct2);
    check("RANDOM_ROUNDTRIP", ss_enc == ss_dec, &mut failed);

    // ── 3. The install-base blob, on this cpu ─────────────────────────────
    let pqclean = load_stored(&unhex(PQCLEAN_DK_HEX));
    let ct3 = Array::try_from(&unhex(PQCLEAN_CT_HEX)[..]).expect("1568-byte ciphertext");
    let ss3 = pqclean.decapsulate(&ct3);
    let psk3 = psk_of(&ss3, &unhex(PQCLEAN_NONCE_HEX));
    println!("STORED_PQCLEAN_PSK={}", hex::encode(psk3.as_slice()));
    if psk3.as_slice() != &unhex(PQCLEAN_PSK_HEX)[..] {
        eprintln!("FAIL: STORED_PQCLEAN_PSK does not match the committed fixture");
        failed = true;
    }

    if failed {
        eprintln!("SMOKE_FAILED");
        return ExitCode::from(1);
    }
    println!("SMOKE_OK");
    ExitCode::SUCCESS
}
