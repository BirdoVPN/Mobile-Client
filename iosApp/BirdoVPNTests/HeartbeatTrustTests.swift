import Security
import XCTest

/// MR-953 F1 (review of #621) — the heartbeat connection's trust decision, RUN,
/// not read.
///
/// `PinnedTrust` (HeartbeatTrust.swift) decides on BOTH ways into the tunnel
/// whether a connection may carry the bearer token: the iOS 18 / macOS 15
/// verify block and the iOS 17 / macOS 14 delegate both end in
/// `PinnedTrust.isTrusted`, through `HeartbeatPins.isTrusted`, which only adds
/// the production pin set. Until now that was covered by source-text checks
/// alone. These evaluate real certificate chains with Security.framework.
///
/// The fixtures are throwaway certificates made for this file (their private
/// keys were never kept): a P-256 root that is nobody's CA, an RSA-2048 leaf
/// for `heartbeat.fixture.test` it signed, and three self-signed certificates
/// for the other key types. Evaluation is anchored to the fixture root and
/// fixed in time, so the tests do not depend on the system's root store or on
/// today's date. The expected SPKI hashes were computed with OpenSSL
/// (`x509 -pubkey | pkey -pubin -outform DER | dgst -sha256 -binary | base64`),
/// not with the code under test.
///
/// What stays device-only: that NetworkExtension really consults the delegate
/// or the verify block, the TLS handshake itself, and that the connection
/// leaves through the tunnel (the F1 capture).
final class HeartbeatTrustTests: XCTestCase {

    private static let host = "heartbeat.fixture.test"
    /// Inside the leaf's validity (2026-11-01 to 2029-01-15).
    private static let verifyDate = Date(timeIntervalSince1970: 1_811_808_000) // 2027-06-01
    private static let afterExpiry = Date(timeIntervalSince1970: 1_893_456_000) // 2030-01-01

    private static let rootPin = "YgynfC6GQylRpnyAIV38bTon6l+cC1bS1jgATsf5R3s="
    private static let leafPin = "jHiatDIu1iljHEfjVEevRyThrXrkuhKxW4m/nf2QIq4="
    private static let p384Pin = "DSAv2sdrQkOlknHwHhe8/QkPt4iljMt6XuviQZXByp4="
    private static let rsa4096Pin = "TFISoCmokO5r40sJHAvb5VVm5B2WhKhR9h32cmUBzYc="
    /// A well-formed pin that belongs to none of the fixtures.
    private static let otherPin = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private func certificate(_ base64: String, file: StaticString = #filePath, line: UInt = #line) throws -> SecCertificate {
        let der = try XCTUnwrap(Data(base64Encoded: base64), "fixture is not base64", file: file, line: line)
        return try XCTUnwrap(SecCertificateCreateWithData(nil, der as CFData), "fixture is not a certificate",
                             file: file, line: line)
    }

    /// The leaf's chain as the system presents it to the decision, anchored to
    /// the fixture root (or to nothing) and fixed in time.
    private func anchor(_ trust: SecTrust, at date: Date = HeartbeatTrustTests.verifyDate,
                        anchored: Bool = true) throws {
        if anchored {
            let root = try certificate(Self.rootDER)
            XCTAssertEqual(SecTrustSetAnchorCertificates(trust, [root] as CFArray), errSecSuccess)
        }
        XCTAssertEqual(SecTrustSetVerifyDate(trust, date as CFDate), errSecSuccess)
    }

    private func leafTrust(at date: Date = HeartbeatTrustTests.verifyDate, anchored: Bool = true) throws -> SecTrust {
        var trust: SecTrust?
        let leaf = try certificate(Self.leafDER)
        // Deliberately NOT the SSL policy: the decision must set it itself.
        XCTAssertEqual(SecTrustCreateWithCertificates([leaf] as CFArray, SecPolicyCreateBasicX509(), &trust),
                       errSecSuccess)
        let made = try XCTUnwrap(trust)
        try anchor(made, at: date, anchored: anchored)
        return made
    }

    // MARK: - The pin hash

    func testTheSPKIHashesAreTheOnesOpenSSLComputes() throws {
        XCTAssertEqual(PinnedTrust.spkiHash(of: try certificate(Self.rootDER)), Self.rootPin, "EC P-256")
        XCTAssertEqual(PinnedTrust.spkiHash(of: try certificate(Self.leafDER)), Self.leafPin, "RSA 2048")
        XCTAssertEqual(PinnedTrust.spkiHash(of: try certificate(Self.p384DER)), Self.p384Pin, "EC P-384")
        XCTAssertEqual(PinnedTrust.spkiHash(of: try certificate(Self.rsa4096DER)), Self.rsa4096Pin, "RSA 4096")
    }

    /// A key type the pin set cannot contain matches no pin, rather than
    /// hashing to something by accident.
    func testAKeyTypeOutsideThePinSetHasNoHash() throws {
        XCTAssertNil(PinnedTrust.spkiHash(of: try certificate(Self.p521DER)))
    }

    // MARK: - The decision

    func testAPinnedChainIsTrusted() throws {
        XCTAssertTrue(PinnedTrust.isTrusted(try leafTrust(), host: Self.host, pins: [Self.rootPin]))
    }

    /// Any certificate in the chain may carry the pin: here only the leaf.
    func testAPinOnTheLeafAloneIsEnough() throws {
        XCTAssertTrue(PinnedTrust.isTrusted(try leafTrust(), host: Self.host, pins: [Self.leafPin, Self.otherPin]))
    }

    /// A chain that validates perfectly well but matches no pin: refused. This
    /// is the case pinning exists for (a CA that should not have issued).
    func testAValidButUnpinnedChainIsRefused() throws {
        let trust = try leafTrust()
        var error: CFError?
        XCTAssertEqual(SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, Self.host as CFString)), errSecSuccess)
        XCTAssertTrue(SecTrustEvaluateWithError(trust, &error), "control: the chain itself is valid")
        XCTAssertFalse(PinnedTrust.isTrusted(trust, host: Self.host, pins: [Self.otherPin]))
        XCTAssertFalse(PinnedTrust.isTrusted(trust, host: Self.host, pins: [Self.p384Pin, Self.rsa4096Pin]))
    }

    func testNoPinsTrustNothing() throws {
        XCTAssertFalse(PinnedTrust.isTrusted(try leafTrust(), host: Self.host, pins: []))
    }

    /// The hostname is part of the decision: a pinned chain for another name
    /// is refused, even though the trust arrived without an SSL policy.
    func testAPinnedChainForAnotherHostIsRefused() throws {
        XCTAssertFalse(PinnedTrust.isTrusted(try leafTrust(), host: "api.birdo.app", pins: [Self.rootPin]))
        XCTAssertFalse(PinnedTrust.isTrusted(try leafTrust(), host: "fixture.test", pins: [Self.rootPin]))
    }

    /// Standard validation comes FIRST: a chain the system does not trust is
    /// refused however well it matches the pins.
    func testAPinnedChainThatFailsStandardValidationIsRefused() throws {
        XCTAssertFalse(PinnedTrust.isTrusted(try leafTrust(anchored: false), host: Self.host,
                                             pins: [Self.rootPin, Self.leafPin]))
    }

    func testAnExpiredPinnedChainIsRefused() throws {
        XCTAssertFalse(PinnedTrust.isTrusted(try leafTrust(at: Self.afterExpiry), host: Self.host,
                                             pins: [Self.rootPin, Self.leafPin]))
    }

    // MARK: - The iOS 17 / macOS 14 path

    /// The delegate gets a raw chain. The trust built from it goes through the
    /// same decision: pinned passes, unpinned does not.
    func testTheLegacyTrustBuiltFromTheRawChainIsJudgedByTheSameDecision() throws {
        let chain: [Any] = [try certificate(Self.leafDER), try certificate(Self.rootDER)]
        let pinned = try XCTUnwrap(PinnedTrust.trust(forPeerChain: chain, host: Self.host))
        try anchor(pinned)
        XCTAssertTrue(PinnedTrust.isTrusted(pinned, host: Self.host, pins: [Self.rootPin]))

        let unpinned = try XCTUnwrap(PinnedTrust.trust(forPeerChain: chain, host: Self.host))
        try anchor(unpinned)
        XCTAssertFalse(PinnedTrust.isTrusted(unpinned, host: Self.host, pins: [Self.otherPin]))

        // Without the test's anchor (as on a device, for a root the system
        // does not know) it is refused before the pins are looked at.
        let untrusted = try XCTUnwrap(PinnedTrust.trust(forPeerChain: chain, host: Self.host))
        try anchor(untrusted, anchored: false)
        XCTAssertFalse(PinnedTrust.isTrusted(untrusted, host: Self.host, pins: [Self.rootPin]))
    }

    func testAChainWithNoCertificateMakesNoTrust() {
        XCTAssertNil(PinnedTrust.trust(forPeerChain: [], host: Self.host))
        XCTAssertNil(PinnedTrust.trust(forPeerChain: ["not a certificate" as NSString, NSNumber(value: 1)],
                                       host: Self.host))
        XCTAssertNil(PinnedTrust.unpassableTrust(forPeerChain: [], host: Self.host))
    }

    /// What the delegate hands back for a refused chain must fail the
    /// system's own evaluation too. The control shows the same chain passes
    /// when it is anchored, so the refusal is the trust's doing.
    func testTheTrustHandedBackForARefusedChainCannotPass() throws {
        let chain: [Any] = [try certificate(Self.leafDER), try certificate(Self.rootDER)]
        var error: CFError?

        let control = try XCTUnwrap(PinnedTrust.trust(forPeerChain: chain, host: Self.host))
        try anchor(control)
        XCTAssertTrue(SecTrustEvaluateWithError(control, &error), "control: anchored, the chain passes")

        let refused = try XCTUnwrap(PinnedTrust.unpassableTrust(forPeerChain: chain, host: Self.host))
        XCTAssertEqual(SecTrustSetVerifyDate(refused, Self.verifyDate as CFDate), errSecSuccess)
        XCTAssertFalse(SecTrustEvaluateWithError(refused, &error))
        XCTAssertFalse(PinnedTrust.isTrusted(refused, host: Self.host, pins: [Self.rootPin, Self.leafPin]))
    }

    // MARK: - No byte before approval (iOS 17 / macOS 14 path)

    /// The connection reports itself connected and the pin check has not
    /// approved it: refused, nothing is written.
    func testConnectedWithoutApprovalIsRefused() {
        XCTAssertEqual(PinnedWriteGate().connected(), .refuse)
    }

    func testConnectedAfterApprovalWritesExactlyOnce() {
        let gate = PinnedWriteGate()
        gate.approve()
        XCTAssertEqual(gate.connected(), .write)
        XCTAssertEqual(gate.connected(), .ignore, "the request is written once")
        XCTAssertEqual(gate.connected(), .ignore)
    }

    /// An approval that arrives after the refusal cannot turn it into a write.
    func testALateApprovalDoesNotUndoARefusal() {
        let gate = PinnedWriteGate()
        XCTAssertEqual(gate.connected(), .refuse)
        gate.approve()
        XCTAssertEqual(gate.connected(), .ignore)
    }

    /// The delegate and the state observer run on different threads: however
    /// they interleave, at most one `connected` is ever told to write, and
    /// only with an approval on record.
    func testOnlyOneWriteHoweverTheCallbacksInterleave() {
        for round in 0..<200 {
            let gate = PinnedWriteGate()
            let decisions = Decisions()
            DispatchQueue.concurrentPerform(iterations: 8) { index in
                if index == round % 8 { gate.approve() }
                decisions.add(gate.connected())
            }
            let writes = decisions.all.filter { $0 == .write }.count
            let refusals = decisions.all.filter { $0 == .refuse }.count
            XCTAssertEqual(writes + refusals, 1, "exactly one connected() decides")
            XCTAssertEqual(decisions.all.count, 8)
        }
    }

    private final class Decisions: @unchecked Sendable {
        private let lock = NSLock()
        private var list: [PinnedWriteGate.Decision] = []
        func add(_ decision: PinnedWriteGate.Decision) {
            lock.lock()
            list.append(decision)
            lock.unlock()
        }
        var all: [PinnedWriteGate.Decision] {
            lock.lock()
            defer { lock.unlock() }
            return list
        }
    }

    // MARK: - Fixtures (DER, base64)

    private static let rootDER = [
        "MIIBqDCCAU6gAwIBAgIUU4dCPnA5NKt2UourhqOvrTYoFhswCgYIKoZIzj0EAwIwMjEwMC4GA1UEAwwnQmlyZG8gVGVzdCBGaXh0",
        "dXJlIFJvb3QgKG5vdCBhIHJlYWwgQ0EpMB4XDTI2MTEwMTAwMDAwMFoXDTQ2MTEwMTAwMDAwMFowMjEwMC4GA1UEAwwnQmlyZG8g",
        "VGVzdCBGaXh0dXJlIFJvb3QgKG5vdCBhIHJlYWwgQ0EpMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEB6H3Nfi646tydwCUo2jd",
        "0+JUgtnK2Tz0MUkU2sIUXkPz+skDBrGubSMEpjvICDAZR5eSSz9mpBpMJrrFGLQbgqNCMEAwDwYDVR0TAQH/BAUwAwEB/zAOBgNV",
        "HQ8BAf8EBAMCAQYwHQYDVR0OBBYEFDP4kWK2g9Rw9QiTiOHkyXTm1JIxMAoGCCqGSM49BAMCA0gAMEUCIQCOC1AVa1VgSFWnoTN3",
        "suRtToD9j7Nq7QP+/P+YLRy1+gIgP7RSuC5I/4JIoua71HwSGHYMviL+ZtYV4SW+elhk+xo=",
    ].joined()

    private static let leafDER = [
        "MIICuTCCAmCgAwIBAgIUbVz9rGusobVzu8Uth61qLayro/MwCgYIKoZIzj0EAwIwMjEwMC4GA1UEAwwnQmlyZG8gVGVzdCBGaXh0",
        "dXJlIFJvb3QgKG5vdCBhIHJlYWwgQ0EpMB4XDTI2MTEwMTAwMDAwMFoXDTI5MDExNTAwMDAwMFowITEfMB0GA1UEAwwWaGVhcnRi",
        "ZWF0LmZpeHR1cmUudGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMommd3S1d61dVoc74F3rX+erLf4WAJ7pbpi",
        "njHHpT/abvcpXFt6iyCiDhfqAY5jn0cvqT9ZUkK8U8gjNHRQwKGoTq35vB7WXjnH2GDimSEVP+9Dqbzby7YGlIufXdGyK7wCJM+3",
        "tvhwpey2Nt+B/HQdcpFZf0udSDtny9OjgMLa2aYjnYtGhFB8kM0FLEH7BzC7Z1qHQrhY7DRT29rqQWTN5lcs0sTNJA0krI6EsJ6c",
        "TVLk6d/GTlpHZ6AluJ041s/5qV1KWMuBA7T6zxFaPYqYb/Fx0hFv3xQR+cHHE6t1Hu8GEEuS0zfwZHotg6ELhVqtkmNdq6TsdGVO",
        "nc+oQuECAwEAAaOBmTCBljAMBgNVHRMBAf8EAjAAMA4GA1UdDwEB/wQEAwIFoDATBgNVHSUEDDAKBggrBgEFBQcDATAhBgNVHREE",
        "GjAYghZoZWFydGJlYXQuZml4dHVyZS50ZXN0MB0GA1UdDgQWBBRQs2TvuZEjipPRsVpxielpu1SbITAfBgNVHSMEGDAWgBQz+JFi",
        "toPUcPUIk4jh5Ml05tSSMTAKBggqhkjOPQQDAgNHADBEAiAOxAcMK1rkHQBGpUlcbHYrndZyz+fRBhU0jpMdnsyWLQIgPuKghUoJ",
        "C+LUG7F6zaoBOGK7JuUczI2dbzGPHxsloyk=",
    ].joined()

    private static let p384DER = [
        "MIIB2DCCAV6gAwIBAgIUINUu60bAUHcLMfwfFeNKGmvCpjYwCgYIKoZIzj0EAwMwIzEhMB8GA1UEAwwYQmlyZG8gVGVzdCBGaXh0",
        "dXJlIFAtMzg0MB4XDTI2MTEwMTAwMDAwMFoXDTQ2MTEwMTAwMDAwMFowIzEhMB8GA1UEAwwYQmlyZG8gVGVzdCBGaXh0dXJlIFAt",
        "Mzg0MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEhPQsYJzGThfD/p3+UW93UC+sstBNrQFlDlk0gJKJxX7Tmel2n9IH7hDfRU9KVuer",
        "AFpe2/O8aQ2ZepHjuPBYQKVo9zay+H4bS+Vc4EdfC+KyDg3al0b2A5yXVNWPXyRNo1MwUTAdBgNVHQ4EFgQUJRan0cC4F4xJCs4S",
        "+VD/C5ldm78wHwYDVR0jBBgwFoAUJRan0cC4F4xJCs4S+VD/C5ldm78wDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAwNoADBl",
        "AjEA+gKAz2reL+anEYL56Acivd+gZYyEpK0slJ8CRx15wQOxG/qDOEBXJxtnHT0lZ6ItAjA0E7iGEQzXJTD7OBOkijBieuSG7ULA",
        "sA3I35m64bxDR/KAsipdAVHHpd30YO0mxYE=",
    ].joined()

    private static let rsa4096DER = [
        "MIIFLTCCAxWgAwIBAgIUEUp8MY5eb3qDJlE1BDg8Fykrm8gwDQYJKoZIhvcNAQELBQAwJjEkMCIGA1UEAwwbQmlyZG8gVGVzdCBG",
        "aXh0dXJlIFJTQS00MDk2MB4XDTI2MTEwMTAwMDAwMFoXDTQ2MTEwMTAwMDAwMFowJjEkMCIGA1UEAwwbQmlyZG8gVGVzdCBGaXh0",
        "dXJlIFJTQS00MDk2MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAyfEaJEuVK3o5/csjVmvyJVpalcugZ87Q9PI8Y0UE",
        "s9y3xNZRPTnysC5cp6+gJ0ERBfCAR62j1OczK833VGeRCJRMVoQh5GU8SYwMdF0wIFnCDejvCkj5jdOuo+eEPEXC6z+ER4kRisbi",
        "0CHlIypA2G7tl3BGhTi9UHq36Sddml+DBFtWuW+O+HzKPOSM/Ptus0pO87spl17bfJX5cTadj3POo3lprDUJbrxRkBJsL3NZFLmX",
        "TTdzrAPCho35e9XQP/J31ihdS4Y41NKbOtRjKQADb9sEgU4QVzIdiMNkiqRnNPJd07Yz9GRbYv5a4S5R4wfWGqeOKEv8Oron8j0Z",
        "I60XiX0qZJEX607+Dn1pVlxWB5n5sX3S1qWTMIL12lbhkzIgCIZSWrYv82afmaKb0PXLA8SERxi9dAT7ac53BiS99o2p8PXmBB3f",
        "83H3SuQohcSO1+CfMs1B6PwNFeUk6J7ytZ3GgazmiQ4CRO4s6ALFxYSoT3s6qyv0xgYRnqQn+De0hmUKF7rBXsox2BfYwicGwf7O",
        "NqeXoHW95kIFM6I9HG9XCeSBSn9PLy9e4dHpiQ/p0SBttCLsAkDPWamV7CyVtzWeap14iedoPYIdopTErr5m/L2WwAmBBG6twg5c",
        "A00L/qYmPa4QAsyPlsBtHHHsxYqckfIw3rpGdkwNL4kCAwEAAaNTMFEwHQYDVR0OBBYEFCTkGW1HUxZQEzT6fbvTtQNK80+1MB8G",
        "A1UdIwQYMBaAFCTkGW1HUxZQEzT6fbvTtQNK80+1MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggIBAEL65JX4CwHc",
        "Qb6JIRMExfx0n01ohgLEe8+GZ9UdEHThz5y4y7D7dkYFISkCUjsjJSTj3u7XW5/moX4vgtmfqEkh8J3mqYITRj57BrPMGVcU8ndA",
        "Kz2Lo74YMuSxRVp5VaIzLYyoAOw3Qh9axg8Be2pHolNYsOUQWamYfc3xFC3zzVgBAC9bTSWR05onqztAGzR0r5OvMno1//J+Rg5Q",
        "TGF6hAqeitYYzi2FmNpQcwiDVGHmc1talF1/GoRxj/GJ3o0OK9R/5YlP4KSdoupva5MI31DRfodpzJc9BnGDPaLQ6DFPA0UWGndL",
        "KJh8FN3EmInFzjdaVbvYZjoCYCakg9BNlhxV5Bp/RTtAZtRhu4JgLfR3gWyxBzhsFWhDITt3B2fkKjmhr5LzVw2UaA6XG8SHBeFO",
        "l8fxiMjr5ZlXh/w3F8eWVSMWDStM4+HZRHRaqz4fd57eRRq1ip9nf28vM2yTSc9a3vYUpwT097RY5T/woWH44ASq64BYHOsccVUC",
        "tp9HjTx7zSEE5A5LYvaBCxsQWEjUC/y7tqaK1F7kaIxvAaeVciobAov7TjD3ydqiLbJ2CAV/z45c0d23nGPTEcdIoqK8vgWjhhiL",
        "gVRGNE/Z3tDsqLUhteDwjo9TDmKL+d/8r5e8F89DA1bu/j+U6QQUtHjFQj4T+EflRyjTivvW",
    ].joined()

    private static let p521DER = [
        "MIICIjCCAYSgAwIBAgIUTKcCQO6rhpXvOHphM/I7v4J8dVAwCgYIKoZIzj0EAwQwIzEhMB8GA1UEAwwYQmlyZG8gVGVzdCBGaXh0",
        "dXJlIFAtNTIxMB4XDTI2MTEwMTAwMDAwMFoXDTQ2MTEwMTAwMDAwMFowIzEhMB8GA1UEAwwYQmlyZG8gVGVzdCBGaXh0dXJlIFAt",
        "NTIxMIGbMBAGByqGSM49AgEGBSuBBAAjA4GGAAQBdeCTNAXnODt3U4g7yCHYBnez4aE7rIIzYdLxIQW9/dJjKKeLiVctXh2NRyUW",
        "5fCmM+xNXwCFvjwmo2Pko/OI51IAdYzT9FU02dEAOhkVlUG4nyqTcb+bwlH5UE6q6iI4UgR0thxaB9lWMrIf5j1gv9DfgQ+kwETU",
        "Zwd4t3Z20c6vttmjUzBRMB0GA1UdDgQWBBTdBEK7u4DHG2A8gHiPi9LZwjKxtzAfBgNVHSMEGDAWgBTdBEK7u4DHG2A8gHiPi9LZ",
        "wjKxtzAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMEA4GLADCBhwJCAI9my0ADclomyMDy1alZ7wWBI6QaKO6TQUaT4B9WwbOV",
        "1GhA6KYVTvw6NXzqQ+zgnBbq2QsiK3jROuPaH7VBoEwhAkFPvqZ8lqUzynevAREn+UPD+roTIXOGKU5WfV2oEvDhuELzfvhAmKCb",
        "Q+I8+5/wDRkOBumnXqPDYgWt/CtepMrCZA==",
    ].joined()
}
