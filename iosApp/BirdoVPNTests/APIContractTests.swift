import XCTest
@testable import BirdoVPN

/// Wire-protocol contract tests.
///
/// Pins the JSON shapes documented in docs/IOS-PARITY-CONTRACT.md (Android's
/// `shared/src/commonMain/kotlin/**/Models.kt` is the reference
/// implementation). Every fixture below is a REAL-shaped payload as the
/// backend sends it — not a minimal happy-path stub.
///
/// WHY THESE EXIST: on Android, `ConnectRequest` once lost its `@Serializable`
/// annotation — the app compiled green, CI passed, and every connect threw at
/// runtime. These tests make the same class of drift fail the build instead
/// of the user. If one of these tests fails after a model change, the model
/// is wrong — do not "fix" the fixture unless the backend contract itself
/// changed (and then update docs/IOS-PARITY-CONTRACT.md in the same commit).
/// NOTE ON FIXTURE VALUES: the key fields below hold obviously-fake plain
/// strings, not realistic base64. These tests pin the wire CONTRACT — field
/// names, nesting and types — and nothing here validates base64 or key length.
/// Realistic-looking key material would trip the repo's secret scanner
/// (keyword + high-entropy base64 is exactly what it hunts for), and the
/// alternative (SHA-pinned .gitleaksignore entries) breaks on every rebase.
final class APIContractTests: XCTestCase {

    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    // MARK: - LoginResponse

    func testLoginResponseDecodesSnakeCaseTokens() throws {
        // Keys inside `tokens` are snake_case; everything else is camelCase.
        let json = """
        {
            "ok": true,
            "tokens": {
                "access_token": "fixture-access-token.not-a-credential",
                "refresh_token": "fixture-refresh-token.not-a-credential"
            }
        }
        """
        let response = try decoder.decode(LoginResponse.self, from: Data(json.utf8))
        XCTAssertEqual(response.ok, true)
        XCTAssertEqual(response.tokens?.accessToken, "fixture-access-token.not-a-credential")
        XCTAssertEqual(response.tokens?.refreshToken, "fixture-refresh-token.not-a-credential")
        XCTAssertNil(response.requiresTwoFactor ?? nil == nil ? response.challengeToken : nil)
    }

    func testLoginResponseDecodesTwoFactorChallenge() throws {
        // 2FA branch: no tokens; camelCase challenge fields at the top level.
        let json = """
        {
            "requiresTwoFactor": true,
            "challengeToken": "fixture-challenge-token"
        }
        """
        let response = try decoder.decode(LoginResponse.self, from: Data(json.utf8))
        XCTAssertEqual(response.requiresTwoFactor, true)
        XCTAssertEqual(response.challengeToken, "fixture-challenge-token")
        XCTAssertNil(response.tokens)
    }

    // MARK: - VpnServer

    func testVpnServerArrayDecodes() throws {
        let json = """
        [
            {
                "id": "node-lon-1",
                "name": "London 1",
                "country": "United Kingdom",
                "countryCode": "GB",
                "city": "London",
                "hostname": "lon1.birdo.app",
                "ipAddress": "203.0.113.10",
                "port": 51820,
                "load": 37,
                "isPremium": false,
                "isStreaming": true,
                "isP2p": false,
                "isOnline": true
            },
            {
                "id": "node-ams-1",
                "name": "Amsterdam 1",
                "country": "Netherlands",
                "countryCode": "NL",
                "city": "Amsterdam",
                "hostname": "ams1.birdo.app",
                "ipAddress": "203.0.113.20",
                "port": 51820,
                "load": 82,
                "isPremium": true,
                "isStreaming": false,
                "isP2p": true,
                "isOnline": false
            }
        ]
        """
        let servers = try decoder.decode([VpnServer].self, from: Data(json.utf8))
        XCTAssertEqual(servers.count, 2)
        XCTAssertEqual(servers[0].id, "node-lon-1")
        XCTAssertEqual(servers[0].countryCode, "GB")
        XCTAssertEqual(servers[0].hostname, "lon1.birdo.app")
        XCTAssertEqual(servers[0].port, 51820)
        XCTAssertEqual(servers[0].load, 37)
        XCTAssertTrue(servers[0].isStreaming)
        XCTAssertTrue(servers[0].isOnline)
        XCTAssertTrue(servers[1].isPremium)
        XCTAssertTrue(servers[1].isP2p)
        XCTAssertFalse(servers[1].isOnline)
    }

    // MARK: - ConnectResponse

    func testConnectResponseDecodesFullFieldSet() throws {
        // Client-generated-keypair connect: `privateKey` is deliberately
        // ABSENT (contract protocol rule 1 — the server must never know the
        // client's private key).
        let json = """
        {
            "success": true,
            "message": "connected",
            "keyId": "fixture-key-id-single-hop",
            "publicKey": "fixture-client-public-key-echoed-back",
            "presharedKey": "fixture-preshared-key-from-server",
            "assignedIp": "10.66.0.5/32",
            "clientIpv6": "fd00:b1d0::5/128",
            "serverPublicKey": "fixture-server-public-key",
            "endpoint": "lon1.birdo.app:51820",
            "dns": ["10.66.0.1", "1.1.1.1"],
            "allowedIps": ["0.0.0.0/0", "::/0"],
            "mtu": 1420,
            "persistentKeepalive": 25,
            "serverNode": {
                "id": "node-lon-1",
                "name": "London 1",
                "region": "eu-west",
                "country": "United Kingdom",
                "hostname": "lon1.birdo.app"
            },
            "stealthEnabled": false
        }
        """
        let response = try decoder.decode(ConnectResponse.self, from: Data(json.utf8))
        XCTAssertTrue(response.success)
        XCTAssertEqual(response.keyId, "fixture-key-id-single-hop")
        XCTAssertNil(response.privateKey,
                     "client-generated keypair: server must not send a private key")
        XCTAssertEqual(response.presharedKey, "fixture-preshared-key-from-server")
        XCTAssertEqual(response.assignedIp, "10.66.0.5/32")
        XCTAssertEqual(response.clientIpv6, "fd00:b1d0::5/128")
        XCTAssertEqual(response.endpoint, "lon1.birdo.app:51820")
        XCTAssertEqual(response.dns, ["10.66.0.1", "1.1.1.1"])
        XCTAssertEqual(response.allowedIps, ["0.0.0.0/0", "::/0"])
        XCTAssertEqual(response.mtu, 1420)
        XCTAssertEqual(response.persistentKeepalive, 25)
        XCTAssertEqual(response.serverNode?.id, "node-lon-1")
        XCTAssertEqual(response.serverNode?.region, "eu-west")
        XCTAssertEqual(response.serverNode?.hostname, "lon1.birdo.app")
    }

    // MARK: - MultiHopConnectResponse

    func testMultiHopConnectResponseDecodes() throws {
        let json = """
        {
            "success": true,
            "keyId": "fixture-key-id-multi-hop",
            "publicKey": "fixture-client-public-key-echoed-back",
            "assignedIp": "10.66.1.9/32",
            "serverPublicKey": "fixture-entry-node-public-key",
            "endpoint": "lon1.birdo.app:51820",
            "dns": ["10.66.0.1"],
            "allowedIps": ["0.0.0.0/0", "::/0"],
            "mtu": 1380,
            "persistentKeepalive": 25,
            "multiHop": {
                "entryNode": {
                    "id": "node-lon-1",
                    "name": "London 1",
                    "country": "United Kingdom",
                    "region": "eu-west"
                },
                "exitNode": {
                    "id": "node-ams-1",
                    "name": "Amsterdam 1",
                    "country": "Netherlands",
                    "region": "eu-west"
                },
                "route": "London 1 -> Amsterdam 1"
            },
            "stealthEnabled": false
        }
        """
        let response = try decoder.decode(MultiHopConnectResponse.self, from: Data(json.utf8))
        XCTAssertTrue(response.success)
        XCTAssertEqual(response.keyId, "fixture-key-id-multi-hop")
        XCTAssertNil(response.privateKey)
        XCTAssertEqual(response.mtu, 1380)
        XCTAssertEqual(response.allowedIps, ["0.0.0.0/0", "::/0"])
        XCTAssertEqual(response.multiHop?.entryNode.id, "node-lon-1")
        XCTAssertEqual(response.multiHop?.exitNode.id, "node-ams-1")
        XCTAssertEqual(response.multiHop?.exitNode.country, "Netherlands")
        XCTAssertEqual(response.multiHop?.route, "London 1 -> Amsterdam 1")
    }

    // MARK: - PortForward

    func testPortForwardDecodes() throws {
        let json = """
        {
            "id": "fixture-port-forward-id",
            "externalPort": 41820,
            "internalPort": 8080,
            "protocol": "tcp",
            "enabled": true
        }
        """
        let forward = try decoder.decode(PortForward.self, from: Data(json.utf8))
        XCTAssertEqual(forward.id, "fixture-port-forward-id")
        XCTAssertEqual(forward.externalPort, 41820)
        XCTAssertEqual(forward.internalPort, 8080)
        XCTAssertEqual(forward.`protocol`, "tcp")
        XCTAssertTrue(forward.enabled)
    }

    // MARK: - SubscriptionStatus

    func testSubscriptionStatusDecodes() throws {
        let json = """
        {
            "plan": "SOVEREIGN",
            "status": "ACTIVE",
            "activeConnections": 2,
            "maxConnections": 10,
            "bandwidthLimitGb": 0,
            "hasPremiumServers": true,
            "subscriptionEndsAt": "2026-08-12T00:00:00.000Z"
        }
        """
        let status = try decoder.decode(SubscriptionStatus.self, from: Data(json.utf8))
        XCTAssertEqual(status.plan, "SOVEREIGN")
        XCTAssertEqual(status.status, "ACTIVE")
        XCTAssertEqual(status.activeConnections, 2)
        XCTAssertEqual(status.maxConnections, 10)
        XCTAssertEqual(status.bandwidthLimitGb, 0)
        XCTAssertTrue(status.hasPremiumServers)
        XCTAssertEqual(status.subscriptionEndsAt, "2026-08-12T00:00:00.000Z")
    }

    // MARK: - ApiErrorBody

    func testApiErrorBodyDecodesErrorCode() throws {
        let json = """
        {
            "errorCode": "DEVICE_LIMIT_REACHED",
            "message": "Device limit reached - remove a device to connect"
        }
        """
        let body = try decoder.decode(ApiErrorBody.self, from: Data(json.utf8))
        XCTAssertNotNil(body.errorCode,
                        "wire value DEVICE_LIMIT_REACHED must map onto the error-code type")
        XCTAssertEqual(body.message, "Device limit reached - remove a device to connect")
    }

    // MARK: - ConnectRequest (encode side)

    func testConnectRequestEncodesExactContractFieldNames() throws {
        let request = ConnectRequest(
            serverNodeId: "node-lon-1",
            deviceName: "iPhone",
            preferredRegion: "eu-west",
            clientPublicKey: "fixture-client-public-key",
            stealthMode: true,
            quantumProtection: true,
            pqClientPublicKey: "fixture-ml-kem-1024-public-key",
            integrityToken: "opaque-attestation-token"
        )
        let data = try encoder.encode(request)
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any],
            "ConnectRequest must encode to a JSON object"
        )
        // EXACT key set. A renamed or dropped field here means the backend
        // sees a different protocol — this is the Android @Serializable
        // regression, caught at test time instead of in production.
        XCTAssertEqual(
            Set(object.keys),
            [
                "serverNodeId", "deviceName", "preferredRegion",
                "clientPublicKey", "stealthMode", "quantumProtection",
                "pqClientPublicKey", "integrityToken",
            ]
        )
        XCTAssertEqual(object["serverNodeId"] as? String, "node-lon-1")
        XCTAssertEqual(object["deviceName"] as? String, "iPhone")
        XCTAssertEqual(object["preferredRegion"] as? String, "eu-west")
        XCTAssertEqual(object["clientPublicKey"] as? String,
                       "fixture-client-public-key")
        XCTAssertEqual(object["stealthMode"] as? Bool, true)
        XCTAssertEqual(object["quantumProtection"] as? Bool, true)
        XCTAssertEqual(object["integrityToken"] as? String, "opaque-attestation-token")
    }
}
