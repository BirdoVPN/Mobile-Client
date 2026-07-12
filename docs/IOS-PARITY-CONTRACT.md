# iOS ↔ Android parity contract

The iOS client must speak EXACTLY the protocol the Android client speaks today.
Android is the reference implementation; when in doubt, read
`shared/src/commonMain/kotlin/**/Models.kt` and `app/src/main/java/app/birdo/vpn/`
— not this document. This document exists because the iOS app was paused
2026-04-27 and every endpoint and shape below drifted while Android shipped.

Backend base URL: `https://api.birdo.app/` (paths below are relative).
All JSON is **camelCase** unless a field is explicitly annotated snake_case.

## Endpoints (canonical, verified 2026-07-12)

| Method | Path | Request | Response |
|---|---|---|---|
| POST | `auth/login/desktop` | LoginRequest | LoginResponse |
| POST | `auth/2fa/verify` | TwoFactorVerifyRequest | TwoFactorVerifyResponse |
| POST | `auth/refresh` | RefreshRequest (`refresh_token`) | RefreshResponse (`access_token`, `refresh_token`, `expires_in`) |
| POST | `auth/login/anonymous` | AnonymousLoginRequest | AnonymousLoginResponse |
| POST | `auth/logout` | — | — |
| GET | `auth/me` | — | UserProfile |
| GET | `vpn/stats` | — | SubscriptionStatus |
| DELETE | `v1/gdpr/delete` (HAS BODY) | `{password}` | DeleteAccountResponse |
| GET | `v1/gdpr/export` | — | GdprExportResponse |
| POST | `vouchers/redeem` | `{code}` | RedeemVoucherResponse |
| GET | `vpn/servers` | — | `[VpnServer]` |
| GET | `vpn/attestation/nonce` | — | `{nonce}` |
| POST | `vpn/connect` | ConnectRequest | ConnectResponse |
| DELETE | `vpn/connections/{keyId}` | — | — |
| POST | `vpn/heartbeat/{keyId}` | — | HeartbeatResponse |
| POST | `vpn/quality-report` | QualityReport | — |
| POST | `vpn/connections/{keyId}/rotate` | KeyRotationRequest | KeyRotationResponse |
| GET | `vpn/multi-hop/routes` | — | `[MultiHopRoute]` |
| POST | `vpn/multi-hop/connect` | MultiHopConnectRequest | MultiHopConnectResponse |
| GET | `vpn/port-forwards` | — | `[PortForward]` |
| POST | `vpn/port-forwards` | CreatePortForwardRequest | CreatePortForwardResponse |
| DELETE | `vpn/port-forwards/{id}` | — | — |

The old iOS paths (`/servers`, `/vpn/multi-hop`, `/vpn/port-forward`,
`/auth/login`, `DELETE /auth/account`) all 404 against the real backend.

## Key request/response shapes

```
LoginRequest        { email, password, deviceId?, deviceName?, deviceType?,
                      platform?, platformVersion?, appVersion? }
LoginResponse       { ok?, tokens? {access_token, refresh_token},
                      requiresTwoFactor?, challengeToken? }   // tokens keys are snake_case
VpnServer           { id, name, country, countryCode, city, hostname, ipAddress,
                      port=51820, load, isPremium, isStreaming, isP2p, isOnline }
SubscriptionStatus  { plan="RECON", status="INACTIVE", activeConnections,
                      maxConnections, bandwidthLimitGb, hasPremiumServers,
                      subscriptionEndsAt? }
ConnectRequest      { serverNodeId?, deviceName?, preferredRegion?,
                      clientPublicKey?, stealthMode=false, quantumProtection=false,
                      pqClientPublicKey?, integrityToken? }
ConnectResponse     { success, message?, config?, keyId?, privateKey?, publicKey?,
                      presharedKey?, assignedIp?, clientIpv6?, serverPublicKey?,
                      endpoint?, dns? [String], allowedIps? [String], mtu?,
                      persistentKeepalive?, serverNode? {id,name,region,country,hostname},
                      stealthEnabled?, xray…? }
MultiHopConnectRequest  { entryNodeId, exitNodeId, deviceName?, clientPublicKey?,
                      stealthMode, quantumProtection, pqClientPublicKey?, integrityToken? }
MultiHopConnectResponse = ConnectResponse minus serverNode, plus
                      multiHop? { entryNode, exitNode, route }
PortForward         { id, externalPort, internalPort, protocol="tcp", enabled }
CreatePortForwardRequest { internalPort, protocol }
HeartbeatResponse   { valid=true, serverOnline=true, message? }
QualityReport       { keyId, latencyMs, jitterMs, packetLossPercent, bytesIn,
                      bytesOut, handshakeAgeSeconds, connectionState, platform }
KeyRotationRequest  { clientPublicKey } → { success, newKeyId, serverPublicKey,
                      presharedKey?, expiresAt }
ApiErrorBody        { errorCode?, message? } — errorCode is one of:
  AUTH_REQUIRED, AUTH_EXPIRED, SUBSCRIPTION_REQUIRED, SUBSCRIPTION_EXPIRED,
  DEVICE_LIMIT_REACHED, RATE_LIMITED, SERVER_OFFLINE, SERVER_FULL,
  NO_SERVERS_AVAILABLE, TUNNEL_CREATION_FAILED, TUNNEL_START_FAILED, …
```

## Protocol rules (not optional)

1. **The client generates its own WireGuard keypair** (WireGuardKit
   `PrivateKey()`), sends `clientPublicKey`, and expects `privateKey` to be
   ABSENT in the response. Accepting a server-generated private key means the
   server knew your key — incompatible with the no-logs posture. Only fall back
   to a server-provided key if `clientPublicKey` was not sent.
2. **Connection lifecycle**: hold `keyId` from ConnectResponse; POST
   `vpn/heartbeat/{keyId}` every 30 s while connected (on `valid:false` →
   disconnect with error); DELETE `vpn/connections/{keyId}` on user disconnect
   (best-effort, don't block teardown on it).
3. **Attestation**: send `integrityToken: nil`. iOS has no Play Integrity; the
   backend's attestation policy is default-off and accepts absent tokens. The
   iOS equivalent (App Attest) is future work — leave the field in the models
   and a TODO, do NOT invent a token.
4. **Client version**: read from the bundle
   (`CFBundleShortVersionString`) — never hardcode. version.properties is the
   single source for both platforms (currently 1.3.42, build 10342).
5. **PQ (ML-KEM-1024)**: keep the existing BirdoPQManager flow — derive
   bilateral PSK when `quantumProtection` is on and the server returns a PQ
   public key; otherwise use the server `presharedKey` if provided.
6. **IPv6**: if `clientIpv6` is absent, the tunnel must still claim `::/0` in
   allowedIPs (blackhole) so IPv6 can't leak around the tunnel — same as
   Android's `::/0` blackhole fix.

## Emerald theme (exact values — the violet era is over)

Core: background #050505, surface #0D0D0D, surfaceVariant #1A1A1A,
card #14141A @70%, border #FFFFFF @8%, text #F2F2F2 (+ white opacity ramp
80/60/40/20/10/05).
Accent (primary, replaces ALL violet #8B5CF6/#A78BFA): **#10B981**
(accentDeep #059669, accentSoft #6EE7B7 for focus/links, accentBg = accent @10%).
Connected state (replaces old greens #22C55E/#4ADE80): **#34D399**
(text #6EE7B7, bg @10%, glow @30%).
Connecting: #EAB308 / #FACC15. Error: #F87171. Info/P2P: #3B82F6.
**Luminance rule**: the idle Connect CTA uses the DARK gradient
#047857 → #064E3B; the luminous #34D399 belongs to the CONNECTED state only.
Do not make the idle button glow — connection state is communicated by
luminance, not hue.
Plan accents: SOVEREIGN #10B981, OPERATIVE #14B8A6, RECON #64748B.

## Payments (owner decision, 2026-07-12)

Payments are **website-only** (Polar, card). No StoreKit, no IAP, no crypto.
The app may DISPLAY the current plan (from `vpn/stats`) and link out to
`https://birdo.app/dashboard/subscription`. Canonical prices £3.99/£9.99 —
but do not hardcode prices in the app at all; the website is the source.
(If the app ever ships to the App Store, link-out billing has its own Apple
rules — that is an OWNER decision recorded in docs/IOS-RELEASE.md.)
