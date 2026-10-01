import XCTest

/// Owner item 96 — pins `ClientIdentity`, the User-Agent and X-Desktop-Client
/// pair the APIClient and the PacketTunnel heartbeat both send.
///
/// The backend's `parseClientUserAgent` keys on the exact `Birdo-<Platform>/`
/// token, so a typo here silently drops this build out of version-floor
/// enforcement and telemetry. This bundle runs on the iOS Simulator in CI; the
/// macOS branch is asserted when the bundle is run on a Mac.
final class ClientIdentityTests: XCTestCase {

    func testUserAgentNamesThePlatformTheBuildRunsOn() {
        #if os(macOS)
        XCTAssertEqual(ClientIdentity.userAgent(version: "1.4.32"), "Birdo-macOS/1.4.32 (macOS)")
        XCTAssertEqual(ClientIdentity.clientHeaderValue, "birdo-macos")
        #else
        XCTAssertEqual(ClientIdentity.userAgent(version: "1.4.32"), "Birdo-iOS/1.4.32 (iOS)")
        XCTAssertEqual(ClientIdentity.clientHeaderValue, "birdo-ios")
        #endif
    }

    /// The version-unknown placeholder must survive verbatim: the backend
    /// recognises `0.0.0-unknown` as "unknown, allow" rather than "ancient".
    func testUnknownVersionPlaceholderIsPassedThroughVerbatim() {
        XCTAssertTrue(ClientIdentity.userAgent(version: "0.0.0-unknown")
            .hasPrefix("Birdo-\(ClientIdentity.platformToken)/0.0.0-unknown "))
    }

    /// `buildClientDeviceInfo` guesses the platform from the header VALUE
    /// (`includes("ios")`, `includes("desktop")`) when a body has none. A Mac's
    /// value must match neither, or a body-less request would be filed as an
    /// iPhone or as the Tauri desktop client.
    func testMacHeaderValueCannotBeMistakenForIosOrDesktop() {
        #if os(macOS)
        XCTAssertFalse(ClientIdentity.clientHeaderValue.contains("ios"))
        XCTAssertFalse(ClientIdentity.clientHeaderValue.contains("desktop"))
        #else
        XCTAssertTrue(ClientIdentity.clientHeaderValue.contains("ios"))
        #endif
    }
}
