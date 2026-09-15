import Foundation

// K5 connect contract — the two request bodies this client puts on the wire
// for POST /vpn/connect and POST /vpn/multi-hop/connect, plus the encoder that
// serialises them.
//
// Foundation-only by design: BirdoVPNTests is an un-hosted bundle that cannot
// compile APIClient.swift (keychain, KMP framework, URLSession plumbing), so the
// bodies live here and are compiled into the test target directly. The test
// encodes them with `ConnectWire.makeEncoder()` — the SAME factory APIClient
// uses — and checks the resulting keys against contract/vpn-protocol.schema.json,
// the backend's generated schema. Both backend routes refuse unknown keys
// (ValidationPipe forbidNonWhitelisted; zod .strict()), so a renamed field is
// not ignored, it is a 400 on every connect — that already happened once here
// (`serverId` for `serverNodeId`, see the AUDIT-M-DRIFT note in APIClient).
//
// A field added to either struct MUST exist in the vendored schema, and the
// schema MUST be refreshed from birdo-web when the backend changes (see
// contract/README.md).

/// Builds the JSONEncoder every APIClient request body goes through.
///
/// Deliberately the stock configuration: no key strategy (the backend's DTOs
/// are camelCase and so are these structs), no date strategy (no dates on the
/// connect bodies). `nil` optionals are OMITTED — the multi-hop zod schema's
/// `.optional()` accepts an absent key but rejects an explicit `null`.
enum ConnectWire {
    static func makeEncoder() -> JSONEncoder {
        JSONEncoder()
    }
}

/// POST /vpn/connect — twin of birdo-web `ConnectDto`.
struct ConnectBody: Encodable {
    let serverNodeId: String
    /// SSOT stable device identity — reclaims this device's own slot.
    let deviceId: String?
    /// On-device WireGuard (Curve25519) public key. Sending it makes the backend
    /// use it as the peer key and omit `privateKey` from the response, so the
    /// tunnel private key never leaves the device (parity with Android).
    let clientPublicKey: String?
    /// AUDIT-C1: opt the user into bilateral PQ when we have a client pk to
    /// send. Server interprets this together with `pqClientPublicKey`.
    let quantumProtection: Bool?
    /// AUDIT-C1: BirdoPQ v1 ML-KEM-1024 client public key (Base64).
    let pqClientPublicKey: String?
    /// BirdoPQ v1 HNDL opt-in: we decapsulate the ciphertext and derive the
    /// PSK on-device (BirdoPQManager), so the server WITHHOLDS the PSK from
    /// the response — it never crosses the wire under classical TLS. Only ever
    /// true alongside `pqClientPublicKey`; VPNManager fails closed if
    /// decapsulation then fails, so this can never silently downgrade.
    let pqClientCanDecapsulate: Bool?
    /// REBUILD (Mobile-Client #159): this request rides the live tunnel it is
    /// replacing — see `APIClient.getConnectConfig(serverId:rebuildOf:)`.
    /// Twin of birdo-web `ConnectDto.rebuild` / `.currentKeyId`.
    let rebuild: Bool?
    /// The server-side key the live tunnel is riding; the ONE key the server
    /// defers. Sent only together with `rebuild`.
    let currentKeyId: String?
}

/// POST /vpn/multi-hop/connect — twin of birdo-web `multiHopConnectSchema`
/// (`.strict()`; `entryNodeId` and `exitNodeId` are the only required keys).
struct MultiHopBody: Encodable {
    let entryNodeId: String
    let exitNodeId: String
    let deviceId: String?
    /// On-device WireGuard (Curve25519) public key — see ConnectBody.
    let clientPublicKey: String?
    let quantumProtection: Bool?
    let pqClientPublicKey: String?
    /// HNDL opt-in — see ConnectBody. Declared on BOTH bodies (the duplicated
    /// wire-model twin) so the double-hop path keeps the PSK off the wire too.
    let pqClientCanDecapsulate: Bool?
    /// REBUILD (#159) — declared on BOTH bodies (the wire-model twin); the
    /// backend's `multiHopConnectSchema` is `.strict()`.
    let rebuild: Bool?
    let currentKeyId: String?
}
