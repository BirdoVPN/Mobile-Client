import XCTest

/// MR-1618 -- "Forgot password?" opens the website page that calls
/// `POST /auth/password/request-reset` (Turnstile-guarded, browser only).
final class AuthLinksTests: XCTestCase {
    func testResetPasswordPointsAtTheWebsiteRoute() {
        XCTAssertEqual(AuthLinks.resetPassword.scheme, "https")
        XCTAssertEqual(AuthLinks.resetPassword.host, "birdo.app")
        XCTAssertEqual(AuthLinks.resetPassword.path, "/reset-password")
    }
}
