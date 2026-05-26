# BirdoVPN Native Crypto Roadmap

## Status — BirdoPQ v1 (current, M2 + M3 + M4 complete)

The post-quantum WireGuard PSK feature ships in client v0.2.0 as **BirdoPQ
v1** — a Mullvad-style ML-KEM-1024 KEM-only construction. End-to-end status:

| Layer | Status | File |
|---|---|---|
| Rust JNI crate (client) | ✅ done, 5/5 unit tests pass | `rosenpass-jni/src/{lib,handshake,errors}.rs` |
| Kotlin JNI bridge | ✅ done | `app/src/main/java/app/birdo/vpn/service/RosenpassNative.kt` |
| Kotlin orchestration | ✅ done | `app/src/main/java/app/birdo/vpn/service/RosenpassManager.kt` |
| Encrypted keystore | ✅ done | `app/src/main/java/app/birdo/vpn/service/RosenpassKeyStore.kt` |
| API schema (request + response) | ✅ done | `shared/.../model/Models.kt` |
| Server-side encap binary | ✅ done, 3/3 unit tests pass | `birdo-pq-server/` |
| Backend `/connect` integration (NestJS) | ✅ done, 4/4 unit tests pass | `birdo-web/backend/src/vpn/birdo-pq.service.ts` |
| Cryptographer audit | ⏳ pending — required before flipping default-on |
| Staged rollout (1% → 100%) | ⏳ pending — requires audit + telemetry counters |

### Threat model

BirdoPQ v1 defeats the **Harvest-Now-Decrypt-Later** threat: an adversary
who records today's network traffic and breaks Curve25519 in N years still
cannot recover any prior session's payload, because each session's PSK was
derived from a fresh ML-KEM-1024 encapsulation, which a CRQC must break
*independently for every session* — there is no shared long-term secret to
break once and decrypt the entire archive.

Out of scope: this does NOT defeat an active CRQC-armed attacker who
intercepts and replaces the ML-KEM ciphertext in real time during the API
handshake — that requires authentication of the API channel, which we
already have via TLS 1.3 with X.509. (When TLS deploys hybrid PQ key
exchange, that gap closes too.)

### Why not upstream `rosenpass`?

We tried. The blockers:

1. **libsodium** — `rosenpass` transitively depends on `libsodium-sys-stable
   v1.24.0`, which calls into a system C `libsodium`. There is no Android
   prebuilt, and cross-compiling libsodium for arm64-v8a / armeabi-v7a /
   x86_64 is a multi-day separate project. `default-features = false` does
   not drop the dep — it's a hard requirement of the inner `rosenpass-ciphers`
   crate.
2. **Static peer config** — the upstream `rosenpass exchange-config` daemon
   expects every peer pre-registered in a TOML `[[peers]]` block. That
   doesn't scale to a VPN with thousands of dynamically-connecting clients.
3. **Audit cost** — auditing the upstream Rosenpass spec implementation
   end-to-end is significantly larger scope than auditing a 200-line ML-KEM
   wrapper.

If/when libsodium cross-compile is solved, BirdoPQ v1 can be swapped for
spec-compatible Rosenpass with a one-line API contract change — the
`ConnectResponse` already carries fields shaped for both. Keep
`deploy/monitoring/rosenpass.{toml,service}` as a parking spot for that
future migration.

### Why ML-KEM-1024 (and not ML-KEM-768 or Classic McEliece)

- **NIST FIPS 203** standardised August 2024.
- **Security category 5** matches AES-256 against quantum attackers (the
  highest category NIST defines).
- **Small public key** — 1568 B vs Classic McEliece's ~524 KB, so we can
  upload it on every `/connect` with negligible bandwidth cost.
- **Mullvad prior art** — Mullvad ships ML-KEM in production for the same
  use case, providing third-party validation of the construction.

### Live PSK rotation

**Not implemented** — `wireguard-android` doesn't expose `wgSetConfig` via
JNI, so we cannot hot-swap the PSK on a live tunnel. Each `/connect`
already derives a fresh per-session PQ-PSK, which provides the same HNDL
guarantee the upstream Rosenpass rekey loop targets — an attacker can't
recover prior-session traffic by breaking *any* single session's KEM.

If wireguard-android exposes a live PSK setter in future, add a periodic
rekey loop to `RosenpassManager` (the JNI surface and HKDF helpers are
already in place). Track upstream:
<https://github.com/WireGuard/wireguard-android/issues>

## Pending work

- [x] **Backend `/connect` integration** — implemented in pure TypeScript
      via `@noble/post-quantum` ML-KEM-1024 + `@noble/hashes` HKDF-SHA-256
      (`birdo-web/backend/src/vpn/birdo-pq.service.ts`). No subprocess
      spawn, no native binary deploy. Round-trip unit test proves
      server-derived PSK == client-decap PSK byte-for-byte. The standalone
      `birdo-pq-server` Rust binary is kept as an alternative
      reference/CLI implementation for non-Node backends.
- [ ] **Cryptographer audit** of the BirdoPQ v1 construction:
      - HKDF salt + info parameter choice
      - Implicit rejection behaviour of pqcrypto-mlkem
      - Memory zeroization coverage
      - Side-channel review of the JNI marshalling
- [ ] **On-device integration tests** that exercise generate → upload → decap
      → WireGuard handshake end-to-end against a staging server.
- [ ] **Telemetry counters** for `Mode.{DISABLED, SERVER_PROVIDED, BILATERAL}`
      so the 1% rollout can be measured.
- [ ] **AndroidManifest.xml AD_ID strip** and Play Console data-safety
      finalisation (orthogonal to this milestone).

## History

- **M1** (commit `1f5fff3`) — Rust crate scaffold, JNI surface, gradle Rust
  build task, CI workflow.
- **M4** (commit `3b017a7`) — Mode state machine, EncryptedFile keystore,
  17 passing unit tests. Initial design assumed upstream-Rosenpass-compat
  bilateral UDP exchange.
- **M2 + M3 pivot** (this branch) — discovered libsodium blocker, switched
  to BirdoPQ v1 (ML-KEM-1024 KEM-only over `/connect`). Dropped the rekey
  loop (live PSK swap unavailable on Android). Added standalone
  `birdo-pq-server` binary for backend integration.
