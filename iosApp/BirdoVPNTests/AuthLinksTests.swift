import XCTest

/// MR-1618 -- "Forgot password?" opens the website page that calls
/// `POST /auth/password/request-reset` (Turnstile-guarded, browser only).
/// MR-2372 -- on the auth host, the same URL Android and Desktop open, not
/// `birdo.app/reset-password`, which only redirects there.
final class AuthLinksTests: XCTestCase {
    func testResetPasswordPointsAtTheAuthHostRoute() {
        XCTAssertEqual(AuthLinks.resetPassword.absoluteString, "https://auth.birdo.app/reset-password")
        XCTAssertEqual(AuthLinks.resetPassword.scheme, "https")
        XCTAssertEqual(AuthLinks.resetPassword.host, "auth.birdo.app")
        XCTAssertEqual(AuthLinks.resetPassword.path, "/reset-password")
    }

    /// The Android constant is the parity reference (BirdoNavGraph.kt
    /// `PASSWORD_RESET_URL`); read from the repository when it is on disk.
    func testResetPasswordMatchesAndroid() throws {
        // .../iosApp/BirdoVPNTests/ThisFile.swift -> repository root
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let nav = root.appendingPathComponent("app/src/main/java/app/birdo/vpn/ui/navigation/BirdoNavGraph.kt")
        guard FileManager.default.fileExists(atPath: nav.path) else {
            throw XCTSkip("Android sources not on disk next to the test bundle")
        }
        let android = try String(contentsOf: nav, encoding: .utf8)
        XCTAssertTrue(android.contains("PASSWORD_RESET_URL = \"\(AuthLinks.resetPassword.absoluteString)\""),
                      "iOS and Android open different reset-password pages")
    }
}
