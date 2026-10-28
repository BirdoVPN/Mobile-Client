import XCTest

/// MR-139 — a connect refused inside its 200, keyed on `refusalReason`.
///
/// ## The gap these pin
///
/// Both connect routes answer a refusal the user can act on with HTTP 200
/// `{ success: false, message }`. iOS threw `.serverMessage(message)` and the
/// banner showed the server's words, whatever they were: the device cap, a
/// server outside the plan, the Free allowance used up and a full server all
/// looked alike, and nothing offered the way out. Since birdo-web #739 the
/// envelope carries `refusalReason` (`$defs.ConnectRefusal`), and Android keys
/// on it since Mobile-Client #511; this is the iOS half.
///
/// What is pinned here is the pure part, in `ConnectRefusal.swift`: the
/// reason list against the vendored contract, the decode (an unknown reason
/// reads as absent, an older envelope still decodes), the copy and whether
/// Home offers the plans. That `APIClient.decodeConnectResult` throws it, that
/// the three dial functions record it and that Home shows "View plans" off it
/// cannot be asserted from this bundle: the JVM guard
/// `IosConnectRefusalWiringTest` pins those.
final class ConnectRefusalTests: XCTestCase {

    private static let deviceCapWording = "Device limit reached (5 devices for OPERATIVE plan)"
    private static let typedReasons = ConnectRefusalReason.allCases.filter { $0 != .deviceLimit }

    private func refusal(_ message: String?, _ reason: String?, quota: Bool = false) -> ConnectRefusedError {
        ConnectRefusedError.classify(message: message, refusalReason: reason, quotaExceeded: quota)
    }

    // MARK: - The vendored contract

    /// `$defs` of contract/vpn-protocol.schema.json, carried as a resource of
    /// this bundle (project.yml). A function, not a stored static: `[String:
    /// Any]` is not Sendable under Swift 6.
    private static func contractDefs() -> [String: Any] {
        let bundle = Bundle(for: ConnectRefusalTests.self)
        guard let url = bundle.url(forResource: "vpn-protocol.schema", withExtension: "json"),
              let raw = try? Data(contentsOf: url),
              let object = try? JSONSerialization.jsonObject(with: raw),
              let schema = object as? [String: Any],
              let defs = schema["$defs"] as? [String: Any] else {
            XCTFail("contract/vpn-protocol.schema.json is not in the test bundle, or has no $defs")
            return [:]
        }
        return defs
    }

    /// THE drift guard. A re-vendor that adds a reason fails here until it is
    /// mapped on purpose, and a reason this client invents fails here too.
    func testReasonsAreExactlyTheContracts() {
        let defs = Self.contractDefs()
        let contractReasons = ((defs["ConnectRefusal"] as? [String: Any])?["enum"] as? [String]) ?? []
        XCTAssertFalse(contractReasons.isEmpty, "$defs.ConnectRefusal.enum is missing or empty")
        let clientReasons = ConnectRefusalReason.allCases.map(\.rawValue)
        XCTAssertEqual(Set(clientReasons), Set(contractReasons),
                       "ConnectRefusalReason no longer mirrors $defs.ConnectRefusal — map the new reason on purpose")

        // ConnectResponse.refusalReason names the same values.
        let response = defs["ConnectResponse"] as? [String: Any]
        let field = (response?["properties"] as? [String: Any])?["refusalReason"] as? [String: Any]
        let fieldReasons = (field?["enum"] as? [String]) ?? []
        XCTAssertEqual(Set(fieldReasons), Set(contractReasons),
                       "ConnectResponse.refusalReason and $defs.ConnectRefusal disagree")
    }

    func testAMissingOrUnknownReasonReadsAsAbsent() {
        XCTAssertNil(ConnectRefusalReason(wire: nil))
        XCTAssertNil(ConnectRefusalReason(wire: ""))
        XCTAssertNil(ConnectRefusalReason(wire: "a_reason_newer_than_this_build"))
        for reason in ConnectRefusalReason.allCases {
            XCTAssertEqual(ConnectRefusalReason(wire: reason.rawValue), reason)
        }
    }

    // MARK: - The copy

    /// The device cap has its own sentence, whatever the server wrote: it
    /// says what to do about it, and the wording is no longer read at all.
    func testTheDeviceCapHasItsOwnSentenceWhateverTheWording() {
        for message in [Self.deviceCapWording, "Limite d'appareils atteinte", "", nil] {
            let cap = refusal(message, "device_limit")
            XCTAssertEqual(cap.reason, .deviceLimit)
            XCTAssertEqual(cap.message, ConnectRefusedError.deviceLimitMessage, String(describing: message))
            XCTAssertFalse(cap.offersPlans, "a plan upgrade is one way out, but the sentence says both")
        }
    }

    /// Every other typed reason keeps the server's sentence, and has copy of
    /// its own (never the generic sentence) when the server sent none.
    func testTheOtherReasonsKeepTheServersSentenceAndHaveTheirOwnFallback() {
        for reason in Self.typedReasons {
            let worded = refusal("  The server's own words.  ", reason.rawValue)
            XCTAssertEqual(worded.reason, reason)
            XCTAssertEqual(worded.message, "The server's own words.", reason.rawValue)

            for silent in [nil, "", "   "] {
                let fallback = refusal(silent, reason.rawValue).message
                XCTAssertEqual(fallback, ConnectRefusedError.fallback(for: reason), reason.rawValue)
                XCTAssertFalse(fallback.isEmpty, reason.rawValue)
                XCTAssertNotEqual(fallback, ConnectRefusedError.genericMessage, reason.rawValue)
            }
        }
        // Distinct copy per reason: a full server is not "outside your plan".
        let fallbacks = Self.typedReasons.map { ConnectRefusedError.fallback(for: $0) }
        XCTAssertEqual(Set(fallbacks).count, fallbacks.count)
    }

    /// Without a reason (an older backend) or with one newer than this build,
    /// a refusal reads exactly as before MR-139: the server's sentence,
    /// verbatim, or the generic one when it sent none. Even the device cap's
    /// wording is not read: before MR-139 iOS never did.
    func testWithoutAKnownReasonItReadsExactlyAsBefore() {
        for reason in [nil, "a_reason_newer_than_this_build"] {
            let worded = refusal(Self.deviceCapWording, reason)
            XCTAssertNil(worded.reason)
            XCTAssertEqual(worded.message, Self.deviceCapWording)
            XCTAssertFalse(worded.offersPlans)
            XCTAssertEqual(refusal(nil, reason).message, ConnectRefusedError.genericMessage)
        }
    }

    // MARK: - What Home offers

    /// "View plans" for a server outside the plan and for the Free allowance
    /// used up (`quotaExceeded` counts whatever the reason says), nothing else.
    func testThePlansAreOfferedForThePlanAndTheFreeAllowanceOnly() {
        XCTAssertTrue(refusal("x", "plan").offersPlans)
        XCTAssertTrue(refusal("x", "quota_exceeded", quota: true).offersPlans)
        XCTAssertTrue(refusal("x", "quota_exceeded").offersPlans)
        XCTAssertTrue(refusal("x", nil, quota: true).offersPlans)
        for reason in ["device_limit", "server_capacity", "server_unavailable", nil, "a_reason_newer_than_this_build"] {
            XCTAssertFalse(refusal("x", reason).offersPlans, String(describing: reason))
        }
    }

    func testTheConnectPathsRenderTheRefusalsMessage() {
        let error: Error = refusal(nil, "device_limit")
        XCTAssertEqual(error.localizedDescription, ConnectRefusedError.deviceLimitMessage)
        let worded: Error = refusal("The server's own words.", "server_capacity")
        XCTAssertEqual(worded.localizedDescription, "The server's own words.")
    }

    // MARK: - The envelope

    /// One body per contract reason decodes with the REAL envelope and gets
    /// its handling; so does a reason newer than this build, and an envelope
    /// from a backend that sends neither field.
    func testTheEnvelopeDecodesEveryReasonAndTheOlderShape() throws {
        let decoder = JSONDecoder()
        let contractReasons = ((Self.contractDefs()["ConnectRefusal"] as? [String: Any])?["enum"] as? [String]) ?? []
        XCTAssertFalse(contractReasons.isEmpty)
        for wire in contractReasons + ["a_reason_newer_than_this_build"] {
            let quota = wire == "quota_exceeded"
            let object: [String: Any] = [
                "success": false,
                "message": "The server's own words.",
                "refusalReason": wire,
                "quotaExceeded": quota,
            ]
            let body = try JSONSerialization.data(withJSONObject: object)
            let envelope = try decoder.decode(ConnectEnvelope.self, from: body)
            XCTAssertEqual(envelope.success, false)
            XCTAssertEqual(envelope.refusalReason, wire)
            XCTAssertNil(envelope.rebuildRefused)
            let refused = ConnectRefusedError.classify(message: envelope.message,
                                                       refusalReason: envelope.refusalReason,
                                                       quotaExceeded: envelope.quotaExceeded ?? false)
            XCTAssertEqual(refused.reason, ConnectRefusalReason(wire: wire), wire)
            XCTAssertEqual(refused.quotaExceeded, quota, wire)
        }

        let older = try decoder.decode(ConnectEnvelope.self,
                                       from: Data(#"{"success":false,"message":"Server is offline"}"#.utf8))
        XCTAssertNil(older.refusalReason)
        XCTAssertNil(older.quotaExceeded)
        XCTAssertEqual(older.message, "Server is offline")
    }
}
