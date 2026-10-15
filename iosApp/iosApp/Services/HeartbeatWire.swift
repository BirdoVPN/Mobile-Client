import Foundation

// MR-1638. Foundation-only BY DESIGN, and in its own file for the same reason
// ConnectWire.swift is: the heartbeat reply is part of the wire contract
// (`$defs.HeartbeatResponse` in contract/vpn-protocol.schema.json), and the
// only way to check the REAL model against it is to compile it into the test
// bundle, which cannot host APIClient.swift. See ConnectContractTests.

/// Response of `POST /vpn/heartbeat/{keyId}`. `valid: false` means the
/// connection was revoked server-side (silent free-tier eviction) — the
/// client must disconnect and surface `message`. Fail-open on decode
/// surprises: an absent `valid` must never kill a live tunnel.
struct HeartbeatResult: Sendable {
    let valid: Bool
    let serverOnline: Bool?
    let message: String?
}

extension HeartbeatResult: Decodable {
    private enum CodingKeys: String, CodingKey {
        case valid, serverOnline, message
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        valid = try c.decodeIfPresent(Bool.self, forKey: .valid) ?? true
        serverOnline = try c.decodeIfPresent(Bool.self, forKey: .serverOnline)
        message = try c.decodeIfPresent(String.self, forKey: .message)
    }
}
