# Protocol Stack & Mobile Parity Roadmap

> Living document. Maintained by @drobo. Updated on every release.
> Tracking issue: <https://github.com/BirdoVPN/Desktop-Client/issues/roadmap>

This roadmap captures the obfuscation, anti-correlation and platform-parity work
required to reach feature parity with the leading commercial WireGuard VPNs
(Mullvad, IVPN, Proton). Items are ordered by **shipping risk × user impact**,
not internal effort.

---

## Phase 0 — Already shipped

| Capability                              | Desktop (Win/macOS/Linux) | Android | iOS |
| --------------------------------------- | :-----------------------: | :-----: | :-: |
| WireGuard tunnel                        |                         |        |    |
| RAM-only entry/exit nodes               |                         |        |    |
| Kill switch / always-on VPN             |                         |        |    |
| LAN passthrough                         |                         |        |    |
| IPv6 leak protection                    |                         |        |    |
| DNS leak protection (encrypted DoH)     |                         |        |    |
| Multi-hop (entry -> exit)                |                         |       |   |
| Custom DNS                              |                         |        |    |
| Split tunnelling                        |        (linux/win)      |        |  ¹ |
| Auto-connect / launch-on-boot           |                         |        |    |

¹ Apple does not expose per-app split tunnelling to NetworkExtension; we plan a
  per-domain rule engine instead (see Phase 2).

---

## Phase 1 — Obfuscation (next 2–3 releases)

### 1.1 WireGuard-over-TCP (`wgtcp`)

* **Why:** networks that block UDP entirely (corporate, hotel, mobile carriers).
* **How:** wrap the WireGuard UDP frames in a length-prefixed TCP stream. Use
  the existing `userspace-wireguard` crate as the data plane.
* **Status:** spec drafted, prototype on `wgtcp/` branch.
* **Targets:** desktop first, then mobile via the KMP shared module.

### 1.2 WireGuard-over-Shadowsocks (`wgss`)

* **Why:** survives DPI in environments where TCP is fingerprinted (China-style
  GFW, some corporate ZTNA appliances).
* **How:** layer Shadowsocks 2022 (AEAD) underneath the WireGuard UDP, much like
  Mullvad's Shadowsocks bridge mode.
* **Server side:** `xray-core` already deployed for Reality; reuse the same
  binary with a new inbound tag.
* **Cipher:** `2022-blake3-aes-256-gcm`.

### 1.3 WireGuard-over-QUIC (`wgquic`)

* **Why:** indistinguishable from plain HTTPS/3 traffic on the wire.
* **How:** use `quinn` (Rust) on the desktop, NetworkExtension's
  `NWConnection.quic` on iOS 17+, and Cronet's QUIC stack on Android.
* **Risk:** QUIC's ALPN handshake is itself fingerprintable; we will adopt the
  same `h3`/`h3-29` ALPNs as Cloudflare so flows look identical to a CDN visit.

### 1.4 LWO — Lightweight WireGuard Obfuscation

* **Why:** stripped-down stream-cipher mask over the WireGuard handshake, with
  a fixed 1-byte XOR rotation, to defeat the most common WG-byte-pattern
  detectors (e.g. iran-shecan, china-gfw-`wireguard-` keyword scanners).
* **How:** kernel-mode out-of-scope on macOS; ship a userspace wireguard-go
  fork on all platforms with the LWO transform pluggable on the wire.
* **Compat note:** server must advertise LWO support over the control channel
  before the client engages it; falls back to vanilla WG on any error.

### 1.5 DAITA — Defence Against AI-guided Traffic Analysis

* **Why:** modern ML-based flow classifiers can de-anonymise a WG session by
  inter-packet timing alone. DAITA inserts cover traffic and packet padding
  modelled on the Maybenot framework (open-source, Mullvad-donated).
* **How:** integrate the `maybenot` Rust crate (LICENSE: BSD-3-Clause) into the
  userspace WG data plane. Padding budgets are negotiated per-session; the
  default "Standard" preset costs ≈10% bandwidth.
* **Target:** opt-in toggle, off by default. Server-side cooperation required.

---

## Phase 2 — iOS feature parity

| Gap (vs. desktop)                                         | Plan                                                                                          |
| --------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| Multi-hop                                                 | Implement in the `PacketTunnel/` extension by wrapping a second WG handshake in the first.    |
| Split tunnelling                                          | Per-domain bypass list via `NEDNSSettingsManager` rules (no per-app possible on iOS).         |
| Custom MTU                                                | Expose `tunnelOverheadBytes` in the app settings; respected by `NEPacketTunnelProvider`.      |
| Quick-connect Shortcuts                                   | Donate `INStartVPNIntent` so Siri / Action Button can connect.                                |
| Always-on VPN (managed)                                   | Ship a configuration profile (`.mobileconfig`) for MDM rollouts.                              |
| Cellular-only / Wi-Fi-only auto-connect                   | NEOnDemandRule with `interfaceTypeMatch`.                                                     |
| Per-server `latency` indicator                            | Background ping via `NWConnection`, not raw ICMP (sandboxed).                                 |

---

## Phase 3 — Android feature parity & advanced

* **Always-on toggle hardening:** detect when the user disables system always-on
  and re-prompt on next launch.
* **Tasker / Macrodroid intents:** exported `connect/disconnect` activities.
* **Wear OS companion:** small tile that mirrors connection status.
* **Quick-tile (system tile)** with one-tap connect/disconnect (already done).

---

## Phase 4 — Cross-cutting hardening

* **Reproducible builds** for desktop + Android (mobile already pinned to a
  fixed Rust toolchain `1.83.0` and Java `17 temurin`).
* **GPG-signed Linux artefacts** — see `build-linux.yml` (live).
* **Apple notarization** for desktop dmg (live), TestFlight for iOS (live).
* **Sigstore keyless signing** for every artefact across every platform (live).
* **Public transparency report**: include AppStore / Play Store removal
  requests, GPG fingerprint rotations, Apple cert renewals.

---

## Open questions / RFCs

1. *Should DAITA be on by default for paying users?* — Mullvad ships it off; we
   would prefer on-by-default on mobile where battery cost is lower. RFC open.
2. *Should we replace altool with notarytool's `--upload`?* Apple has begun
   deprecating altool's app upload mode; expect breakage in late 2026.
3. *F-Droid distribution* — F-Droid requires reproducible builds and rejects
   Sentry. Tracking issue separately.
