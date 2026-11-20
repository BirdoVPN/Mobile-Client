import XCTest

/// MR-2532 [SIGNOUT-DURING-REFRESH-HOLD], owner decision 2026-11-20 (option a):
/// a sign-out ends its session on the server with the REFRESH token whenever
/// the access token cannot, and it never refreshes (`SignOutRevocation`,
/// RefreshAnswer.swift). Before, it sent the bearer alone: an access token
/// that had expired (an hour after the last request, or during a refresh
/// hold) got a 401, the keychain was wiped, and the session lived on the
/// server until it expired.
///
/// The policy is pinned here; APIClient.swift and AuthViewModel.swift cannot
/// be compiled into this bundle, so their use of it is pinned as source.
/// Android twin: BirdoRepository.endServerSession (BirdoRepositoryTest).
final class SignOutRevocationTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    /// A JWT whose payload carries `exp` (the header and signature are not read).
    private func jwt(exp: TimeInterval?) -> String {
        var claims: [String: Any] = ["sub": "user-1"]
        if let exp { claims["exp"] = exp }
        let payload = try! JSONSerialization.data(withJSONObject: claims)
            .base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "eyJhbGciOiJIUzI1NiJ9.\(payload).sig"
    }

    private func plan(access: String?, refresh: String?) -> SignOutRevocation.Plan {
        SignOutRevocation.plan(for: SignOutTokens(accessToken: access, refreshToken: refresh), now: now)
    }

    func testAUsableAccessTokenGoesFirstAndTheRefreshTokenOnlyOnA401() {
        let live = jwt(exp: now.timeIntervalSince1970 + 600)
        XCTAssertEqual(plan(access: live, refresh: "RT"), .bearerThenRefreshTokenOn401)
        XCTAssertTrue(SignOutRevocation.refreshTokenAfterBearer(status: 401, hasRefreshToken: true))
        for status in [200, 204, 403, 429, 500, 503] {
            XCTAssertFalse(SignOutRevocation.refreshTokenAfterBearer(status: status, hasRefreshToken: true), "\(status)")
        }
        XCTAssertFalse(SignOutRevocation.refreshTokenAfterBearer(status: nil, hasRefreshToken: true),
                       "no answer: nothing more is sent")
        XCTAssertFalse(SignOutRevocation.refreshTokenAfterBearer(status: 401, hasRefreshToken: false))
    }

    /// The case the row is about: the access token has expired (or will on
    /// the way), so the bearer cannot end the session. The refresh token goes
    /// at once; nothing refreshes first.
    func testAnExpiredOrExpiringAccessTokenSendsTheRefreshToken() {
        let expired = jwt(exp: now.timeIntervalSince1970 - 10)
        let expiring = jwt(exp: now.timeIntervalSince1970 + SignOutRevocation.accessTokenSkew - 5)
        XCTAssertEqual(plan(access: expired, refresh: "RT"), .refreshToken)
        XCTAssertEqual(plan(access: expiring, refresh: "RT"), .refreshToken)
        XCTAssertEqual(plan(access: nil, refresh: "RT"), .refreshToken)
    }

    func testAnUnreadableAccessTokenIsNotPresented() {
        for bad in ["", "not-a-jwt", "a.b", "a.%%%.c", jwt(exp: nil)] {
            XCTAssertFalse(SignOutRevocation.accessTokenUsable(bad, now: now), bad)
            XCTAssertEqual(plan(access: bad, refresh: "RT"), .refreshToken, bad)
        }
    }

    func testNothingIsSentWithoutAnyTokenTheServerCouldActOn() {
        XCTAssertEqual(plan(access: nil, refresh: nil), .nothing)
        XCTAssertEqual(plan(access: nil, refresh: ""), .nothing)
        XCTAssertEqual(plan(access: jwt(exp: now.timeIntervalSince1970 - 10), refresh: nil), .nothing)
    }

    /// A refresh on the wire when the sign-out lands has consumed the token the
    /// sign-out reads. The rotation it then gets is never stored, and the
    /// session it renewed is revoked with the token it was handed.
    func testARotationThatLandsAfterASignOutIsRevoked() {
        XCTAssertTrue(SignOutRevocation.revokesLateRotation(generationAtStart: 4, generationNow: 5))
        XCTAssertFalse(SignOutRevocation.revokesLateRotation(generationAtStart: 4, generationNow: 4))
    }

    // MARK: - Source pins (APIClient and AuthViewModel cannot be compiled here)

    private func source(_ path: String) throws -> String {
        // .../iosApp/BirdoVPNTests/SignOutRevocationTests.swift -> .../iosApp
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let url = root.appendingPathComponent(path)
        guard FileManager.default.fileExists(atPath: url.path) else {
            XCTFail("\(path) not found under \(root.path): this pin would be vacuous")
            return ""
        }
        return try String(contentsOf: url, encoding: .utf8).replacingOccurrences(of: "\r\n", with: "\n")
    }

    private func slice(_ text: String, from start: String, to end: String,
                       file: StaticString = #filePath, line: UInt = #line) -> String {
        guard let a = text.range(of: start),
              let b = text.range(of: end, range: a.upperBound..<text.endIndex) else {
            XCTFail("not found: \(start) … \(end)", file: file, line: line)
            return ""
        }
        return String(text[a.lowerBound..<b.upperBound])
    }

    /// The sign-out reads BOTH tokens before the wipe and hands them to
    /// APIClient.signOut; the bearer-only `logout(bearerToken:)` is gone.
    func testTheSignOutReadsBothTokensBeforeTheWipe() throws {
        let vm = try source("iosApp/ViewModels/AuthViewModel.swift")
        let logout = slice(vm, from: "    func logout() {", to: "\n    }\n")
        let read = try XCTUnwrap(logout.range(of:
            "SignOutTokens(accessToken: keychain.accessToken, refreshToken: keychain.refreshToken)"))
        let send = try XCTUnwrap(logout.range(of: "await api.signOut(tokens)"))
        let wipe = try XCTUnwrap(logout.range(of: "completeLocalLogout()"))
        XCTAssertLessThan(read.lowerBound, wipe.lowerBound)
        XCTAssertLessThan(send.lowerBound, wipe.lowerBound)
        let api = try source("iosApp/Services/APIClient.swift")
        XCTAssertFalse(api.contains("logout(bearerToken:"))
        XCTAssertFalse(vm.contains("logout(bearerToken:"))
    }

    /// APIClient.signOut carries out the plan and never refreshes.
    func testTheServerHalfNeverRefreshes() throws {
        let api = try source("iosApp/Services/APIClient.swift")
        let signOut = slice(api, from: "func signOut(_ tokens: SignOutTokens) async {", to: "\n    }\n")
        let post = slice(api, from: "private func postLogout(bearer: String?, refreshToken: String?) async -> Int? {",
                         to: "\n    }\n")
        for body in [signOut, post] {
            for forbidden in ["refreshTokens(", "refreshActor", "/auth/refresh", "keychain."] {
                XCTAssertFalse(body.contains(forbidden), forbidden)
            }
        }
        XCTAssertTrue(signOut.contains("SignOutRevocation.plan(for: tokens, now: Date())"))
        XCTAssertTrue(signOut.contains("SignOutRevocation.refreshTokenAfterBearer(status: status,"))
        XCTAssertTrue(post.contains("\"/auth/logout\""))
        XCTAssertTrue(post.contains("request.httpBody = try? encoder.encode(RefreshBody(refresh_token: refreshToken))"))
    }

    /// The refresh fence revokes a rotation that lands after a sign-out, with
    /// the token that rotation handed back, and still stores nothing.
    func testTheRefreshFenceRevokesALateRotation() throws {
        let api = try source("iosApp/Services/APIClient.swift")
        let refresh = slice(api, from: "private func refreshTokens() async throws {", to: "\n    }\n")
        let fence = slice(refresh, from: "guard generationNow == generationAtStart,", to: "            return\n        }\n")
        XCTAssertTrue(fence.contains("SignOutRevocation.revokesLateRotation(generationAtStart: generationAtStart,"))
        XCTAssertTrue(fence.contains("SignOutTokens(accessToken: nil, refreshToken: tokens.refreshToken ?? refresh)"))
        XCTAssertTrue(fence.contains("await self?.signOut(late)"))
        XCTAssertFalse(fence.contains("keychain.save("))
    }
}
