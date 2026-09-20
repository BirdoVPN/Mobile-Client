import XCTest

/// OPEN-WORK H3 — the warn-only iOS version floor.
///
/// The backend has attached `clientUpdate` to every below-floor iOS connect
/// since the floor shipped, and NO client ever read it: not iOS, not Android,
/// not desktop. Android and desktop are `block`, so they get a structured 426
/// they do handle; iOS is the ONLY platform that receives the advisory at all,
/// and it decoded the response into a struct that had no such field. The floor
/// warned nobody.
///
/// iOS is warn-only deliberately — an App Review queue sits between a fix and
/// the user and there is no sideload escape hatch — so the fix is to RENDER the
/// advisory, not to start blocking.
///
/// What is pinned here is the decoding, because that is the whole risk surface:
/// an advisory that fails to decode must never fail a connect that is otherwise
/// perfectly good, and an advisory with no words must never render a banner.
/// That the field is actually WIRED into the connect paths cannot be asserted
/// from this bundle (APIClient.swift and VpnViewModel.swift do not compile into
/// it) — `IosVersionFloorWiringTest` on the Android side reads those two files
/// as source and does it there.
final class VersionFloorAdvisoryTests: XCTestCase {

    private func decode(_ json: String) throws -> ClientUpdateAdvisory {
        try JSONDecoder().decode(ClientUpdateAdvisory.self, from: Data(json.utf8))
    }

    // MARK: - The shape the backend actually sends

    func testDecodesTheBackendsAdvisory() throws {
        // Verbatim from vpn.controller.ts's `ClientUpdateAdvisory`.
        let advisory = try decode("""
        {
          "required": false,
          "minVersion": "1.4.30",
          "currentVersion": "1.4.26",
          "updateUrl": "https://birdo.app/clients",
          "message": "A newer version of BirdoVPN is available. Please update — this build will stop being supported."
        }
        """)
        XCTAssertFalse(advisory.required, "iOS is warn-only; the backend sends required=false")
        XCTAssertEqual(advisory.minVersion, "1.4.30")
        XCTAssertEqual(advisory.currentVersion, "1.4.26")
        XCTAssertTrue(advisory.isRenderable)
    }

    func testRequiredIsDecodedRatherThanAssumedFalse() throws {
        // If the floor is ever tightened to `block` server-side, the client
        // already carries the flag instead of needing a release to learn it.
        XCTAssertTrue(try decode(#"{"required": true, "message": "Update to reconnect."}"#).required)
    }

    // MARK: - Advice must never break a working tunnel

    func testEveryFieldIsOptional() throws {
        // A backend that adds or renames a field must not be able to throw
        // here — the caller decodes this with `try?` for the same reason, and
        // both layers erring the same way is the point.
        let advisory = try decode("{}")
        XCTAssertFalse(advisory.required)
        XCTAssertEqual(advisory.minVersion, "")
        XCTAssertEqual(advisory.message, "")
        XCTAssertFalse(advisory.isRenderable)
    }

    func testUnknownFieldsAreIgnored() throws {
        let advisory = try decode("""
        {"message": "Please update.", "minVersion": "1.4.30", "severity": "info", "expiresAt": 99}
        """)
        XCTAssertEqual(advisory.message, "Please update.")
        XCTAssertEqual(advisory.minVersion, "1.4.30")
    }

    func testAWrongTypeThrowsSoTheCallerCanSwallowIt() {
        // The caller uses `try?`, so throwing here is CORRECT — it degrades to
        // "no advisory" rather than to a failed connect. Asserting it proves
        // the caller's `try?` is load-bearing and not decoration.
        XCTAssertThrowsError(try decode(#"{"minVersion": 1430}"#))
    }

    // MARK: - Never render an empty banner

    func testAdvisoryWithNoMessageIsNotRenderable() throws {
        let advisory = try decode(#"{"minVersion": "1.4.30", "required": false}"#)
        XCTAssertTrue(advisory.message.isEmpty)
        XCTAssertFalse(advisory.isRenderable,
                       "a clientUpdate with no words must not produce a blank banner")
    }

    func testWhitespaceOnlyMessageStillCountsAsPresent() throws {
        // Deliberately NOT trimmed: the server owns this copy, and silently
        // reinterpreting what it sent is how a client and a backend start
        // disagreeing about what was shown to the user.
        XCTAssertTrue(try decode(#"{"message": " "}"#).isRenderable)
    }

    // MARK: - The update destination

    func testUpdateLinkPointsAtTheAppStoreNotTheWebsite() throws {
        // The server sends birdo.app/clients — correct for desktop, a dead end
        // on iOS, where the App Store is the only place to update from.
        let url = try XCTUnwrap(ClientUpdateAdvisory.appStoreURL)
        XCTAssertEqual(url.host, "apps.apple.com")
    }
}
