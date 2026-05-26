# birdo-pq-server

Server-side helper binary for **BirdoPQ v1** post-quantum WireGuard PSK
derivation, the protocol shipped by the Birdo Android client in
`../rosenpass-jni/`.

## What it does

Every `/connect` request that includes a `pq_client_public_key` field is
handled by:

1. The backend application shells out to `birdo-pq-server encap <client_pk_b64>`.
2. The binary encapsulates against the client's ML-KEM-1024 public key,
   derives a 32-byte WireGuard PSK via `HKDF-SHA-256(salt="BirdoPQ-v1-PSK",
   IKM=ss, info=nonce)`, and prints `{ciphertext_b64, nonce_b64, psk_b64}`
   on stdout.
3. The backend:
   - returns `ciphertext_b64` to the client in `ConnectResponse.rosenpassPublicKey`,
   - returns `nonce_b64` in `ConnectResponse.rosenpassEndpoint`,
   - injects `psk_b64` into the WireGuard peer config via
     `wg set <iface> peer <client_wg_pubkey> preshared-key <psk_file>`.

The Android client decapsulates with its persisted ML-KEM-1024 secret key
and runs the exact same HKDF, arriving at the same 32-byte PSK.

## Why this exists (vs. upstream `rosenpass exchange-config`)

- **No libsodium dependency** — we couldn't cross-compile libsodium for
  Android, which made vendoring upstream `rosenpass` impractical.
- **No static `[[peers]]` config** — upstream Rosenpass requires every peer
  pre-registered in a TOML file, which doesn't scale to a VPN with thousands
  of clients.
- **Same HNDL guarantee** — the PSK is derived from a NIST FIPS 203 PQ KEM
  (ML-KEM-1024, security category 5), the same primitive Mullvad ships in
  production: <https://mullvad.net/en/blog/2023/4/3/quantum-resistant-tunnels-are-now-available-with-mullvad-vpn>.

If/when libsodium cross-compile is solved upstream, this binary can be
swapped for `rosenpass exchange-config` with a one-line API contract change
(switch from "server returns ciphertext" to "server returns its own
Rosenpass public key + UDP endpoint"). Both shapes already exist in
`ConnectResponse`.

## Build

```bash
cd native/birdo-pq-server
cargo build --release
# binary at target/release/birdo-pq-server
```

Cross-compile for Linux server deployment (from any host with cross-rs
installed):

```bash
cross build --release --target x86_64-unknown-linux-musl
# fully static binary at target/x86_64-unknown-linux-musl/release/birdo-pq-server
```

## Test

```bash
cargo test
```

## CLI

```text
birdo-pq-server encap <client_pk_b64>
birdo-pq-server version
```

`encap` prints a single line of JSON on stdout, exits 0 on success.
On error, prints to stderr and exits non-zero.
