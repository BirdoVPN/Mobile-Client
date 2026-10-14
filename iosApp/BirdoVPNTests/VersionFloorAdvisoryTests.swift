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
        let advisory = try decode(#"{"updateUrl": "https://birdo.app/clients", "message": "Update."}"#)
        XCTAssertEqual(advisory.storeURL, ClientUpdateAdvisory.appStoreURL)
    }

    func testAnAppStoreUpdateUrlFromTheServerIsUsed() throws {
        let link = "https://apps.apple.com/app/birdovpn/id6670361940"
        let advisory = try decode(#"{"updateUrl": "\#(link)", "message": "Update."}"#)
        XCTAssertEqual(advisory.storeURL?.absoluteString, link)
    }

    func testAServerLinkOffTheAppStoreIsNeverOpened() throws {
        for link in ["http://apps.apple.com/app/id6670361940",
                     "https://apps.apple.com.example.net/app",
                     "https://example.com/apps.apple.com",
                     "not a url"] {
            let advisory = ClientUpdateAdvisory(required: true, minVersion: "1.4.40",
                                                currentVersion: "1.4.33", updateUrl: link, message: "m")
            XCTAssertEqual(advisory.storeURL, ClientUpdateAdvisory.appStoreURL, link)
        }
    }

    /// Review of #484: an https apps.apple.com link is not enough — it must be
    /// THIS app's page, or the server (or a typo) could send the user to
    /// another app's listing to "update".
    func testOnlyThisAppsOwnAppStorePageIsAccepted() {
        for link in ["https://apps.apple.com/app/id6670361940",
                     "https://apps.apple.com/id6670361940",
                     "https://apps.apple.com/gb/app/id6670361940",
                     "https://apps.apple.com/app/birdovpn/id6670361940",
                     "https://apps.apple.com/us/app/birdo-vpn/id6670361940",
                     "https://APPS.apple.com/gb/app/birdovpn/id6670361940",
                     "https://apps.apple.com/gb/app/birdovpn/id6670361940?mt=12"] {
            let advisory = ClientUpdateAdvisory(required: true, minVersion: "1.4.40",
                                                currentVersion: "1.4.33", updateUrl: link, message: "m")
            XCTAssertEqual(advisory.storeURL?.absoluteString, link, link)
        }
    }

    func testAnotherAppsOrAnOddlyShapedAppStoreLinkFallsBack() {
        for link in ["https://apps.apple.com/app/id1234567890",
                     "https://apps.apple.com/gb/app/other-app/id1234567890",
                     "https://apps.apple.com/app/id66703619400",
                     "https://apps.apple.com/app/id6670361940/",
                     "https://apps.apple.com/app/id6670361940#x",
                     "https://apps.apple.com/developer/birdo/id6670361940",
                     "https://apps.apple.com/gb/app/birdovpn/extra/id6670361940",
                     "https://apps.apple.com/GB/app/id6670361940",
                     "https://apps.apple.com:8443/app/id6670361940",
                     "https://user@apps.apple.com/app/id6670361940",
                     "https://apps.apple.com/",
                     "https://apps.apple.com"] {
            let advisory = ClientUpdateAdvisory(required: true, minVersion: "1.4.40",
                                                currentVersion: "1.4.33", updateUrl: link, message: "m")
            XCTAssertEqual(advisory.storeURL, ClientUpdateAdvisory.appStoreURL, link)
        }
    }

    func testTheFallbackIsItselfThisAppsPage() throws {
        let url = try XCTUnwrap(ClientUpdateAdvisory.appStoreURL)
        XCTAssertTrue(ClientUpdateAdvisory.isOwnAppStorePage(url))
        XCTAssertEqual(url.absoluteString, "https://apps.apple.com/app/id6670361940")
    }

    // MARK: - MR-1661: the 426 refusal (the hard floor)

    /// The contract's `update_required` (contract/vpn-protocol.schema.json):
    /// before MR-1661 every one of these fields was thrown away and the user
    /// saw a fixed sentence with no version and no way to update.
    private let contract426 = """
    {"statusCode": 426, "message": "Client version 1.4.33 is no longer supported. Please update.",
     "error": "update_required", "timestamp": "2026-10-13T20:00:00.000Z", "path": "/api/v1/vpn/connect",
     "details": {"minVersion": "1.4.40", "currentVersion": "1.4.33", "updateUrl": "https://birdo.app/clients"}}
    """

    func testA426BecomesARequiredAdvisoryCarryingTheServersVersions() throws {
        let refusal = try XCTUnwrap(UpdateRequiredRefusal.classify(status: 426, body: Data(contract426.utf8)))
        XCTAssertTrue(refusal.advisory.required, "a refusal is not advice: it must not be dismissible")
        XCTAssertEqual(refusal.advisory.minVersion, "1.4.40")
        XCTAssertEqual(refusal.advisory.currentVersion, "1.4.33")
        XCTAssertEqual(refusal.advisory.updateUrl, "https://birdo.app/clients")
        XCTAssertTrue(refusal.advisory.isRenderable)
        XCTAssertTrue(refusal.advisory.message.contains("1.4.40"), "the banner must name the floor")
        XCTAssertTrue(refusal.advisory.message.contains("1.4.33"), "the banner must name this build")
        // The App Store button, not birdo.app/clients.
        XCTAssertEqual(refusal.advisory.storeURL?.host, "apps.apple.com")
    }

    func testTheErrorLineReadsExactlyAsBefore() throws {
        let refusal = try XCTUnwrap(UpdateRequiredRefusal.classify(status: 426, body: Data(contract426.utf8)))
        XCTAssertEqual(refusal.localizedDescription,
                       "This app version is no longer supported. Update BirdoVPN to reconnect.")
    }

    func testALatestFloorAsksForTheLatestVersion() throws {
        let body = #"{"statusCode": 426, "error": "update_required", "details": {"minVersion": "latest", "currentVersion": "", "updateUrl": ""}}"#
        let refusal = try XCTUnwrap(UpdateRequiredRefusal.classify(status: 426, body: Data(body.utf8)))
        XCTAssertTrue(refusal.advisory.message.contains("latest version"))
        XCTAssertFalse(refusal.advisory.message.contains("()"), "an absent version must not print as ()")
    }

    func testA426WithoutTheContractsBodyStillRaisesTheBanner() throws {
        // Claimed on the STATUS, as the old mapping was: a proxy's HTML 426
        // still tells the user to update.
        let refusal = try XCTUnwrap(UpdateRequiredRefusal.classify(status: 426, body: Data("<html>".utf8)))
        XCTAssertTrue(refusal.advisory.required)
        XCTAssertTrue(refusal.advisory.isRenderable)
        XCTAssertEqual(refusal.advisory.minVersion, "")
    }

    func testOnlyA426IsClaimed() {
        for status in [400, 401, 403, 409, 429, 500, 503] {
            XCTAssertNil(UpdateRequiredRefusal.classify(status: status, body: Data(contract426.utf8)),
                         "status \(status) is not the version floor")
        }
    }
}
