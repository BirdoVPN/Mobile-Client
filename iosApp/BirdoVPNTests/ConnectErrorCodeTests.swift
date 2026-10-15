import XCTest

/// MR-1948 — the connect routes' retryable 503s on iOS.
///
/// ## The gap these pin
///
/// birdo-web answers a connect it cannot serve right now with HTTP 503
/// `{statusCode, message, error: "psk_delivery_unavailable" |
/// "quota_check_unavailable", timestamp, path, details: {retryable: true,
/// retryAfterSeconds: 30}}` and no `Retry-After` header. iOS decoded neither
/// code: `APIClient.error(status:body:)` turned the 503 into `.serverMessage` /
/// `.httpError(503)`, the code and `details.retryAfterSeconds` were thrown
/// away with the body, and nothing honoured the server's wait. Android fixed
/// the same gap in Mobile-Client #481 (MR-1639); this is the iOS half.
///
/// What is pinned here is the pure part, in `ConnectErrorCode.swift`: the code
/// list against the vendored contract, the decode (which must never crash or
/// claim a body it does not understand), the clamp, the copy and the
/// auto-connect hold. That `APIClient`'s two connect routes pass
/// `RetryableConnectRefusal.classify` as their `refusal:` hook, and that
/// `VpnViewModel` records and honours the hold, cannot be asserted from this
/// bundle: APIClient.swift and VpnViewModel.swift do not compile into it.
final class ConnectErrorCodeTests: XCTestCase {

    // MARK: - Bodies

    private func data(_ json: String) -> Data { Data(json.utf8) }

    private static let pskServerMessage =
        "We could not set up a secure connection to this server just now. Please try again in a moment."

    private static let quotaMessage =
        "We could not check your free data allowance just now. Please try again in a moment."

    /// The server's envelope, verbatim in shape (vpn.controller.ts
    /// `rethrowPskDeliveryRefusal` through GlobalExceptionFilter). `details`
    /// is spliced in raw so a test can send it malformed or not at all.
    private func pskBody(details: String? = #"{"retryable":true,"retryAfterSeconds":30}"#) -> Data {
        let detailsField = details.map { #","details":\#($0)"# } ?? ""
        return data(#"{"statusCode":503,"message":"\#(Self.pskServerMessage)","error":"psk_delivery_unavailable","#
                    + #""timestamp":"2026-10-13T09:00:00.000Z","path":"/vpn/connect"\#(detailsField)}"#)
    }

    /// vpn.controller.ts's QuotaCheckUnavailableError catch, same envelope.
    private func quotaBody(retryAfterSeconds: String = "30") -> Data {
        data(#"{"statusCode":503,"message":"\#(Self.quotaMessage)","error":"quota_check_unavailable","#
             + #""timestamp":"2026-10-13T09:00:00.000Z","path":"/vpn/connect","#
             + #""details":{"retryable":true,"retryAfterSeconds":\#(retryAfterSeconds)}}"#)
    }

    private static let tunnelAddressMessage =
        "We could not assign your connection an address on this server just now. Please try again in a moment."

    /// vpn.controller.ts's TunnelAddressUnavailableError catch (birdo-web
    /// #711), same envelope, RETRY_AFTER_SECONDS = 15.
    private func tunnelAddressBody() -> Data {
        data(#"{"statusCode":503,"message":"\#(Self.tunnelAddressMessage)","error":"tunnel_address_unavailable","#
             + #""timestamp":"2026-10-15T09:00:00.000Z","path":"/vpn/multi-hop/connect","#
             + #""details":{"retryable":true,"retryAfterSeconds":15}}"#)
    }

    /// The wait a psk body with this raw `retryAfterSeconds` JSON value yields.
    private func pskWait(_ retryAfterSeconds: String,
                         file: StaticString = #filePath, line: UInt = #line) -> TimeInterval? {
        let refusal = RetryableConnectRefusal.classify(
            status: 503,
            body: pskBody(details: #"{"retryable":true,"retryAfterSeconds":\#(retryAfterSeconds)}"#))
        XCTAssertEqual(refusal?.code, .pskDeliveryUnavailable,
                       "a psk body with retryAfterSeconds=\(retryAfterSeconds) lost its code", file: file, line: line)
        return refusal?.retryAfter
    }

    // MARK: - The vendored contract

    /// `$defs` of contract/vpn-protocol.schema.json, carried as a resource of
    /// this bundle (project.yml). A function, not a stored static: `[String:
    /// Any]` is not Sendable under Swift 6.
    private static func contractDefs() -> [String: Any] {
        let bundle = Bundle(for: ConnectErrorCodeTests.self)
        guard let url = bundle.url(forResource: "vpn-protocol.schema", withExtension: "json"),
              let raw = try? Data(contentsOf: url),
              let object = try? JSONSerialization.jsonObject(with: raw),
              let schema = object as? [String: Any],
              let defs = schema["$defs"] as? [String: Any] else {
            XCTFail("contract/vpn-protocol.schema.json is not in the test bundle, or has no $defs "
                    + "(it is listed under BirdoVPNTests in project.yml with buildPhase: resources)")
            return [:]
        }
        return defs
    }

    private static func properties(of def: [String: Any]) -> [String: Any] {
        (def["properties"] as? [String: Any]) ?? [:]
    }

    /// THE drift guard. A re-vendor that adds a code fails here until it is
    /// mapped on purpose, and a code this client invents fails here too.
    func testErrorCodesAreExactlyTheContracts() {
        let defs = Self.contractDefs()
        let errorCode = defs["ErrorCode"] as? [String: Any]
        let contractCodes = (errorCode?["enum"] as? [String]) ?? []
        XCTAssertFalse(contractCodes.isEmpty, "$defs.ErrorCode.enum is missing or empty")
        let clientCodes = ConnectErrorCode.allCases.compactMap(\.wire)
        XCTAssertEqual(Set(clientCodes), Set(contractCodes),
                       "ConnectErrorCode no longer mirrors $defs.ErrorCode — map the new code on purpose")
        XCTAssertEqual(clientCodes.count, Set(clientCodes).count, "two cases share a wire spelling")
        // Exactly one case has no wire spelling: the fallback.
        XCTAssertEqual(ConnectErrorCode.allCases.filter { $0.wire == nil }, [.unknown])
    }

    /// `isRetryable` is the contract's word, not a guess: a code is retryable
    /// exactly when its definition is a 503 that REQUIRES
    /// `details.retryAfterSeconds`.
    func testRetryableExactlyWhenTheContractMakesItA503WithARequiredWait() {
        let defs = Self.contractDefs()
        for code in ConnectErrorCode.allCases {
            guard let wire = code.wire else { continue }
            guard let def = defs[wire] as? [String: Any] else {
                XCTFail("$defs.\(wire) is missing from the vendored contract")
                continue
            }
            let props = Self.properties(of: def)
            let status = (props["statusCode"] as? [String: Any])?["const"] as? Int
            let detailsRequired = ((props["details"] as? [String: Any])?["required"] as? [String]) ?? []
            let contractSaysRetryable = status == 503 && detailsRequired.contains("retryAfterSeconds")
            XCTAssertEqual(code.isRetryable, contractSaysRetryable,
                           "\(wire): isRetryable disagrees with the contract (status \(String(describing: status)), "
                           + "details.required \(detailsRequired))")
        }
        XCTAssertTrue(ConnectErrorCode.pskDeliveryUnavailable.isRetryable)
        XCTAssertTrue(ConnectErrorCode.quotaCheckUnavailable.isRetryable)
        XCTAssertTrue(ConnectErrorCode.tunnelAddressUnavailable.isRetryable)
        XCTAssertFalse(ConnectErrorCode.unknown.isRetryable)
    }

    /// The sample bodies below are the server's shape, not a guess: each one
    /// carries every key its definition requires, the definition's `error`
    /// and `statusCode` constants, and every required `details` key.
    func testTheSampleBodiesSatisfyTheirContractDefinitions() throws {
        let defs = Self.contractDefs()
        for (wire, body) in [("psk_delivery_unavailable", pskBody()),
                             ("quota_check_unavailable", quotaBody()),
                             ("tunnel_address_unavailable", tunnelAddressBody())] {
            let def = try XCTUnwrap(defs[wire] as? [String: Any], "$defs.\(wire) missing")
            let props = Self.properties(of: def)
            let sample = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
            for key in (def["required"] as? [String]) ?? [] {
                XCTAssertNotNil(sample[key], "\(wire) sample is missing required \(key)")
            }
            XCTAssertEqual(sample["error"] as? String,
                           (props["error"] as? [String: Any])?["const"] as? String)
            XCTAssertEqual(sample["statusCode"] as? Int,
                           (props["statusCode"] as? [String: Any])?["const"] as? Int)
            let details = try XCTUnwrap(sample["details"] as? [String: Any])
            for key in ((props["details"] as? [String: Any])?["required"] as? [String]) ?? [] {
                XCTAssertNotNil(details[key], "\(wire) sample details is missing required \(key)")
            }
        }
    }

    // MARK: - psk_delivery_unavailable

    func testPskDeliveryUnavailableWithItsWait() throws {
        let refusal = try XCTUnwrap(RetryableConnectRefusal.classify(status: 503, body: pskBody()))
        XCTAssertEqual(refusal.code, .pskDeliveryUnavailable)
        XCTAssertEqual(refusal.retryAfter, 30)
        XCTAssertEqual(refusal.status, 503)
        XCTAssertEqual(refusal.serverMessage, Self.pskServerMessage)
        // The copy is the client's own, not the server's sentence.
        XCTAssertEqual(refusal.errorDescription, RetryableConnectRefusal.secureSetupUnavailableCopy)
    }

    /// The contract REQUIRES the wait on this code; a body that lost it on the
    /// way still waits the default rather than being re-asked at once.
    func testPskDeliveryUnavailableWithoutItsWaitDefaultsTo30Seconds() {
        let shapes: [String?] = [nil, "{}", #"{"retryable":true}"#, "null", #""soon""#, "[]"]
        for details in shapes {
            let refusal = RetryableConnectRefusal.classify(status: 503, body: pskBody(details: details))
            XCTAssertEqual(refusal?.code, .pskDeliveryUnavailable, "details \(details ?? "absent") lost the code")
            XCTAssertEqual(refusal?.retryAfter, ConnectRetryAfter.retryableDefault,
                           "details \(details ?? "absent") should wait the 30 s default")
        }
        XCTAssertEqual(ConnectRetryAfter.retryableDefault, 30)
    }

    /// What VpnViewModel actually renders: every connect path shows
    /// `error.localizedDescription`, so the LocalizedError wiring is the copy.
    func testPskCopyIsWhatTheConnectPathsRender() throws {
        let error: Error = try XCTUnwrap(RetryableConnectRefusal.classify(status: 503, body: pskBody()))
        XCTAssertEqual(error.localizedDescription,
                       "Couldn't set up a secure connection to this server just now. Please try again in a moment.")
    }

    /// The condition is HUB-WIDE (its one throw site runs only when the hub has
    /// no node agent), so another location fails the same way: the copy must
    /// say to wait, never to switch. And it must never point the user at the
    /// Quantum Protection toggle: the server refused to connect with less
    /// protection, and the remedy is the same request again shortly.
    func testPskCopyNeverSuggestsAnotherLocationOrTurningProtectionOff() {
        let copy = RetryableConnectRefusal.secureSetupUnavailableCopy.lowercased()
        for banned in ["location", "another server", "different server", "other server", "switch",
                       "quantum", "turn off", "disable", "settings"] {
            XCTAssertFalse(copy.contains(banned), "psk copy must not say \"\(banned)\"")
        }
        XCTAssertTrue(copy.contains("try again in a moment"))
    }

    // MARK: - quota_check_unavailable

    func testQuotaCheckUnavailableKeepsTheServersSentence() throws {
        let refusal = try XCTUnwrap(RetryableConnectRefusal.classify(status: 503, body: quotaBody()))
        XCTAssertEqual(refusal.code, .quotaCheckUnavailable)
        XCTAssertEqual(refusal.retryAfter, 30)
        // Unchanged copy: before this type the 503 rendered the server's own
        // sentence through `.serverMessage`, and it still does.
        XCTAssertEqual(refusal.errorDescription, Self.quotaMessage)
    }

    func testQuotaCheckUnavailableWithNoUsableSentenceFallsBackToGenericCopy() throws {
        // Absent, an empty string, a number and an object.
        let messages: [String?] = [nil, "\"\"", "42", #"{"text":"x"}"#]
        for message in messages {
            var json = #"{"statusCode":503,"error":"quota_check_unavailable","#
                + #""details":{"retryable":true,"retryAfterSeconds":30}"#
            if let message { json += #","message":"# + message }
            let refusal = try XCTUnwrap(RetryableConnectRefusal.classify(status: 503, body: data(json + "}")))
            XCTAssertEqual(refusal.code, .quotaCheckUnavailable)
            XCTAssertEqual(refusal.errorDescription, RetryableConnectRefusal.temporarilyUnavailableCopy,
                           "message \(message ?? "absent")")
        }
    }

    /// Nest's validation shape: `message` as an array of strings.
    func testAnArrayMessageIsJoined() throws {
        let body = data(#"{"statusCode":503,"message":["first.","second."],"error":"quota_check_unavailable","#
                        + #""details":{"retryable":true,"retryAfterSeconds":30}}"#)
        let refusal = try XCTUnwrap(RetryableConnectRefusal.classify(status: 503, body: body))
        XCTAssertEqual(refusal.errorDescription, "first. second.")
    }

    // MARK: - tunnel_address_unavailable

    /// MR-2058: the allocator could not prove an address free and failed
    /// closed. Claimed as retryable, with the server's 15 s and its sentence
    /// (the copy it had before, when the 503 rendered via `.serverMessage`).
    func testTunnelAddressUnavailableWaitsTheServers15SecondsAndKeepsItsSentence() throws {
        let refusal = try XCTUnwrap(RetryableConnectRefusal.classify(status: 503, body: tunnelAddressBody()))
        XCTAssertEqual(refusal.code, .tunnelAddressUnavailable)
        XCTAssertEqual(refusal.retryAfter, 15)
        XCTAssertEqual(refusal.status, 503)
        XCTAssertEqual(refusal.errorDescription, Self.tunnelAddressMessage)
    }

    // MARK: - Codes that are not claimed

    /// A code newer than this build is `.unknown` and is NOT claimed, even when
    /// it names a wait: the refusal falls through to APIClient's existing
    /// mapping and is classified by its status exactly as before.
    func testAnUnknownCodeIsNotClaimed() {
        let body = data(#"{"statusCode":503,"message":"Warming up.","error":"relay_warming_up","#
                        + #""details":{"retryable":true,"retryAfterSeconds":45,"region":"eu"}}"#)
        XCTAssertEqual(ConnectErrorCode(wire: "relay_warming_up"), .unknown)
        XCTAssertNil(RetryableConnectRefusal.classify(status: 503, body: body))
    }

    /// The two non-retryable codes keep their existing handling (the 426 by
    /// status in APIClient, the 403 as the server's message).
    func testNonRetryableContractCodesAreNotClaimed() {
        let floor = data(#"{"statusCode":426,"message":"Update.","error":"update_required","#
                         + #""details":{"minVersion":"1.4.30","currentVersion":"1.4.20","updateUrl":"https://birdo.app"}}"#)
        let attestation = data(#"{"statusCode":403,"message":"Refused.","error":"client_attestation_required"}"#)
        XCTAssertEqual(ConnectErrorCode(wire: "update_required"), .updateRequired)
        XCTAssertEqual(ConnectErrorCode(wire: "client_attestation_required"), .clientAttestationRequired)
        XCTAssertNil(RetryableConnectRefusal.classify(status: 426, body: floor))
        XCTAssertNil(RetryableConnectRefusal.classify(status: 403, body: attestation))
    }

    /// As on Android: a retryable code means "come back shortly" whatever
    /// status it rode in on.
    func testARetryableCodeIsClaimedOnAnyStatus() {
        XCTAssertEqual(RetryableConnectRefusal.classify(status: 409, body: pskBody())?.code, .pskDeliveryUnavailable)
        XCTAssertEqual(RetryableConnectRefusal.classify(status: 409, body: pskBody())?.status, 409)
    }

    // MARK: - Junk never crashes and is never claimed

    func testJunkBodiesAreNotClaimed() {
        let junk: [(String, Data)] = [
            ("empty", Data()),
            ("html", data("<html><body>502 Bad Gateway</body></html>")),
            ("null", data("null")),
            ("array", data(#"["psk_delivery_unavailable"]"#)),
            ("string", data(#""psk_delivery_unavailable""#)),
            ("number", data("503")),
            ("empty object", data("{}")),
            ("truncated", data(#"{"statusCode":503,"error":"psk_delivery_unavailable","det"#)),
            // `{"<C3 28>":1}`: an invalid UTF-8 sequence inside a key.
            ("not utf-8", Data([0x7B, 0x22, 0xC3, 0x28, 0x22, 0x3A, 0x31, 0x7D])),
            ("numeric error", data(#"{"statusCode":503,"error":503}"#)),
            ("null error", data(#"{"statusCode":503,"error":null}"#)),
            ("object error", data(#"{"statusCode":503,"error":{"code":"psk_delivery_unavailable"}}"#)),
            ("reason phrase", data(#"{"statusCode":503,"message":"Service Unavailable","error":"Service Unavailable"}"#)),
            ("wrong case", data(#"{"statusCode":503,"error":"PSK_DELIVERY_UNAVAILABLE"}"#)),
            ("padded", data(#"{"statusCode":503,"error":" psk_delivery_unavailable"}"#)),
            ("code only in details", data(#"{"statusCode":503,"details":{"code":"psk_delivery_unavailable"}}"#)),
        ]
        for (label, body) in junk {
            XCTAssertNil(RetryableConnectRefusal.classify(status: 503, body: body), "\(label) was claimed")
        }
        XCTAssertNil(ConnectErrorEnvelope.decode(Data()))
        XCTAssertNil(ConnectErrorEnvelope.decode(data("<html>")))
        XCTAssertEqual(ConnectErrorCode(wire: nil), .unknown)
        XCTAssertEqual(ConnectErrorCode(wire: ""), .unknown)
    }

    /// A field of the wrong type loses that field only, never the code.
    func testAWrongTypedFieldLosesOnlyThatField() throws {
        let body = data(#"{"statusCode":"503","message":42,"error":"psk_delivery_unavailable","#
                        + #""timestamp":7,"details":{"retryable":"yes","retryAfterSeconds":"soon"}}"#)
        let refusal = try XCTUnwrap(RetryableConnectRefusal.classify(status: 503, body: body))
        XCTAssertEqual(refusal.code, .pskDeliveryUnavailable)
        XCTAssertNil(refusal.serverMessage)
        XCTAssertEqual(refusal.retryAfter, ConnectRetryAfter.retryableDefault)
        XCTAssertEqual(refusal.errorDescription, RetryableConnectRefusal.secureSetupUnavailableCopy)
    }

    // MARK: - The clamp

    func testTheServersWaitIsHonouredAsSent() {
        XCTAssertEqual(pskWait("30"), 30)
        XCTAssertEqual(pskWait("120"), 120)
        XCTAssertEqual(pskWait("45.0"), 45)
    }

    func testTheWaitIsClampedToOneSecondAndTenMinutes() {
        // Upper edge.
        XCTAssertEqual(pskWait("600"), 600)
        XCTAssertEqual(pskWait("601"), 600)
        XCTAssertEqual(pskWait("86400"), 600)
        XCTAssertEqual(pskWait("9223372036854775807"), 600)
        XCTAssertEqual(pskWait("1e300"), 600)
        // Lower edge.
        XCTAssertEqual(pskWait("1"), 1)
        XCTAssertEqual(pskWait("0.5"), 1)
        XCTAssertEqual(pskWait("0.001"), 1)
        // Not a usable wait: the default, never "retry now".
        XCTAssertEqual(pskWait("0"), 30)
        XCTAssertEqual(pskWait("-1"), 30)
        XCTAssertEqual(pskWait("-86400"), 30)
        XCTAssertEqual(pskWait("true"), 30)
        XCTAssertEqual(pskWait("null"), 30)
        XCTAssertEqual(pskWait(#""120""#), 30)
        XCTAssertEqual(pskWait("[30]"), 30)
    }

    func testTheClampItself() {
        XCTAssertEqual(ConnectRetryAfter.minimum, 1)
        XCTAssertEqual(ConnectRetryAfter.maximum, 600)
        XCTAssertNil(ConnectRetryAfter.clamped(seconds: nil))
        XCTAssertNil(ConnectRetryAfter.clamped(seconds: 0))
        XCTAssertNil(ConnectRetryAfter.clamped(seconds: -0.5))
        XCTAssertNil(ConnectRetryAfter.clamped(seconds: Double.nan))
        XCTAssertNil(ConnectRetryAfter.clamped(seconds: Double.infinity))
        XCTAssertNil(ConnectRetryAfter.clamped(seconds: -Double.infinity))
        XCTAssertEqual(ConnectRetryAfter.clamped(seconds: Double.leastNonzeroMagnitude), 1)
        XCTAssertEqual(ConnectRetryAfter.clamped(seconds: 0.999), 1)
        XCTAssertEqual(ConnectRetryAfter.clamped(seconds: 1), 1)
        XCTAssertEqual(ConnectRetryAfter.clamped(seconds: 599.5), 599.5)
        XCTAssertEqual(ConnectRetryAfter.clamped(seconds: 600), 600)
        XCTAssertEqual(ConnectRetryAfter.clamped(seconds: 600.001), 600)
        XCTAssertEqual(ConnectRetryAfter.clamped(seconds: Double.greatestFiniteMagnitude), 600)
        XCTAssertEqual(ConnectRetryAfter.forRetryableRefusal(serverSeconds: nil), 30)
        XCTAssertEqual(ConnectRetryAfter.forRetryableRefusal(serverSeconds: 0), 30)
        XCTAssertEqual(ConnectRetryAfter.forRetryableRefusal(serverSeconds: 12), 12)
    }

    // MARK: - The auto-connect hold

    func testTheHoldLastsExactlyTheServersWait() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let refusal = try XCTUnwrap(RetryableConnectRefusal.classify(status: 503, body: pskBody()))
        let notBefore = AutoDialHold.notBefore(after: refusal, now: now)
        XCTAssertEqual(notBefore.timeIntervalSince(now), 30, accuracy: 0.001)
        XCTAssertTrue(AutoDialHold.isHeld(now: now, notBefore: notBefore))
        XCTAssertTrue(AutoDialHold.isHeld(now: now.addingTimeInterval(29.9), notBefore: notBefore))
        XCTAssertFalse(AutoDialHold.isHeld(now: notBefore, notBefore: notBefore))
        XCTAssertFalse(AutoDialHold.isHeld(now: now.addingTimeInterval(31), notBefore: notBefore))
        XCTAssertFalse(AutoDialHold.isHeld(now: now, notBefore: nil))
    }

    /// The launch auto-connect sleeps its usual 1.5 s settle, or the rest of
    /// the server's wait when that is longer — never less than either.
    func testTheAutoConnectWaitsAtLeastTheServersWait() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertEqual(AutoDialHold.delay(now: now, notBefore: nil, floor: 1.5), 1.5)
        XCTAssertEqual(AutoDialHold.delay(now: now, notBefore: now.addingTimeInterval(-60), floor: 1.5), 1.5)
        XCTAssertEqual(AutoDialHold.delay(now: now, notBefore: now.addingTimeInterval(0.5), floor: 1.5), 1.5)
        XCTAssertEqual(AutoDialHold.delay(now: now, notBefore: now.addingTimeInterval(30), floor: 1.5),
                       30, accuracy: 0.001)
        XCTAssertEqual(AutoDialHold.delay(now: now, notBefore: now.addingTimeInterval(600), floor: 1.5),
                       600, accuracy: 0.001)
    }

    /// `notBefore` is a wall-clock instant. A clock set back after the hold was
    /// recorded makes it look an hour away; the sleep must still never exceed
    /// the 10 minutes the server's own value was clamped to.
    func testAClockSetBackCannotStretchTheWaitPastTenMinutes() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertEqual(AutoDialHold.delay(now: now, notBefore: now.addingTimeInterval(3600), floor: 1.5), 600)
        XCTAssertEqual(AutoDialHold.delay(now: now, notBefore: now.addingTimeInterval(600.5), floor: 1.5), 600)
        XCTAssertEqual(AutoDialHold.delay(now: now, notBefore: Date.distantFuture, floor: 1.5), 600)
    }
}
