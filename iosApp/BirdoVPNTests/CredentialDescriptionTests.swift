import XCTest

/// MR-1949 — the iOS twin of Android's `CredentialToStringTest` (MR-944,
/// MR-1870): no credential-bearing model prints a secret, by any of Swift's
/// four printing paths.
///
/// Swift prints a struct through reflection by default, so before MR-1949
/// every one of these models printed its password, tokens, 2FA challenge and
/// code, SSO code and verifier, account number or WireGuard keys in full the
/// moment it was interpolated into a log line, crash breadcrumb or `po`.
///
/// The models are the REAL ones: AuthWire.swift, ConnectWire.swift and
/// AccountDeletion.swift are compiled into this bundle for exactly this.
final class CredentialDescriptionTests: XCTestCase {

    private let secrets = [
        "user@example.com",
        "hunter2-password",
        "device-xyz-123",
        "iPhone of Alice",
        "access-secret-abc",
        "refresh-secret-def",
        "challenge-secret-ghi",
        "otp-424242",
        "sso-code-jkl",
        "pkce-verifier-mno",
        "apple-identity-token-pqr",
        "apple-nonce-stu",
        "123456789012345678901234",
        "apple-signed-transaction-vwx",
        "app-account-token-yz0",
        "apple-authorization-code-123",
        "wg-private-key-aaa",
        "wg-preshared-key-789",
        "pq-ciphertext-abc",
    ]

    /// Every way a model reaches text: `print` / `String(describing:)`,
    /// interpolation, `debugPrint` / `String(reflecting:)` and `dump` (which
    /// walks the Mirror). The message names the type and how many secrets
    /// leaked, never the secret or the printed text: test logs are uploaded.
    private func assertNoSecret(_ model: Any, file: StaticString = #filePath, line: UInt = #line) {
        var dumped = ""
        dump(model, to: &dumped)
        let renderings = [String(describing: model), "\(model)", String(reflecting: model), dumped]
        for (index, printed) in renderings.enumerated() {
            let leaked = secrets.filter { printed.contains($0) }.count
            XCTAssertEqual(leaked, 0,
                           "\(type(of: model)) printing path #\(index) printed \(leaked) secret(s)",
                           file: file, line: line)
        }
    }

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    private let tokens = TokenPairData(accessToken: "access-secret-abc", refreshToken: "refresh-secret-def")

    // MARK: - Session

    func testTokenPairPrintsNeitherToken() {
        assertNoSecret(tokens)
        XCTAssertEqual(tokens.description, "TokenPairData(accessToken: [REDACTED], refreshToken: [REDACTED])")
    }

    func testLoginResultPrintsNoTokenOrChallenge() {
        assertNoSecret(LoginResult.success(tokens))
        assertNoSecret(LoginResult.twoFactorRequired(challengeToken: "challenge-secret-ghi"))
        XCTAssertTrue(LoginResult.twoFactorRequired(challengeToken: "challenge-secret-ghi")
            .description.contains("twoFactorRequired"), "LoginResult lost which outcome it was")
    }

    func testAnonymousRegistrationPrintsNoAccountNumberOrToken() {
        assertNoSecret(AnonymousRegistration(anonymousId: "123456789012345678901234", tokens: tokens))
        // Absent still reads as absent.
        XCTAssertTrue(AnonymousRegistration(anonymousId: nil, tokens: tokens).description
            .contains("anonymousId: nil"))
    }

    func testUserProfilePrintsNoEmailOrAccountNumber() {
        let profile = UserProfile(id: "user-1", email: "user@example.com", name: "Alice",
                                  emailVerified: true, hasPassword: false, isSSO: false,
                                  accountType: "anonymous", isAnonymousField: true,
                                  accountNumber: "123456789012345678901234")
        assertNoSecret(profile)
        XCTAssertTrue(profile.description.contains("accountType: anonymous"), "UserProfile lost its type")
    }

    // MARK: - Sign-in request bodies

    func testLoginBodyPrintsNoCredentialOrDeviceIdentifier() {
        let body = LoginBody(email: "user@example.com", password: "hunter2-password",
                             deviceId: "device-xyz-123", deviceName: "iPhone of Alice",
                             platformVersion: "18.4", appVersion: "1.4.34")
        assertNoSecret(body)
        // Still useful in a log: the non-identifying context survives.
        XCTAssertTrue(body.description.contains("appVersion: 1.4.34"), "LoginBody lost its app version")
    }

    func testAnonymousLoginBodyPrintsNoAccountNumberOrPassword() {
        assertNoSecret(AnonymousLoginBody(anonymousId: "123456789012345678901234",
                                          password: "hunter2-password",
                                          deviceId: "device-xyz-123", deviceName: "iPhone of Alice",
                                          platformVersion: "18.4", appVersion: "1.4.34"))
    }

    func testTwoFactorBodyPrintsNeitherChallengeNorCode() {
        assertNoSecret(TwoFactorBody(challengeToken: "challenge-secret-ghi", token: "otp-424242"))
    }

    func testAppleNativeBodyPrintsNoIdentityTokenOrNonce() {
        assertNoSecret(AppleNativeBody(identityToken: "apple-identity-token-pqr", nonce: "apple-nonce-stu",
                                       deviceId: "device-xyz-123", deviceName: "iPhone of Alice",
                                       platformVersion: "18.4", appVersion: "1.4.34"))
    }

    func testSsoExchangeBodyPrintsNoCodeOrVerifier() {
        let body = SsoExchangeBody(code: "sso-code-jkl", codeVerifier: "pkce-verifier-mno",
                                   deviceId: "device-xyz-123", deviceName: "iPhone of Alice",
                                   platformVersion: "18.4", appVersion: "1.4.34")
        assertNoSecret(body)
        XCTAssertTrue(body.description.contains("platformVersion: 18.4"), "SsoExchangeBody lost its OS version")
    }

    func testRefreshBodyPrintsNoToken() {
        assertNoSecret(RefreshBody(refresh_token: "refresh-secret-def"))
    }

    // MARK: - Sign-in responses (decoded from the wire, as in production)

    func testTokenResponsesPrintNoToken() throws {
        assertNoSecret(try decode(TokenPairDTO.self,
                                  #"{"access_token": "access-secret-abc", "refresh_token": "refresh-secret-def"}"#))
        assertNoSecret(try decode(RefreshTokensResponse.self,
                                  #"{"access_token": "access-secret-abc", "refresh_token": "refresh-secret-def"}"#))
    }

    func testAuthResponsePrintsNoTokenChallengeOrAccountNumber() throws {
        let response = try decode(AuthTokensResponse.self, """
        {"ok": true, "requiresTwoFactor": true, "challengeToken": "challenge-secret-ghi",
         "anonymousId": "123456789012345678901234",
         "tokens": {"access_token": "access-secret-abc", "refresh_token": "refresh-secret-def"}}
        """)
        assertNoSecret(response)
        XCTAssertTrue(response.description.contains("requiresTwoFactor: true"),
                      "AuthTokensResponse lost requiresTwoFactor")
    }

    // MARK: - Account deletion

    func testDeleteAccountBodyPrintsNoPasswordOrCode() {
        assertNoSecret(DeleteAccountBody(password: "hunter2-password", twoFactorCode: "otp-424242",
                                         appleAuthorizationCode: "apple-authorization-code-123"))
        // Which one was sent still shows.
        let codeOnly = DeleteAccountBody(password: nil, twoFactorCode: "otp-424242")
        XCTAssertTrue(codeOnly.description.contains("password: nil"),
                      "DeleteAccountBody hides that no password was sent")
        XCTAssertTrue(codeOnly.description.contains("twoFactorCode: [REDACTED]"),
                      "DeleteAccountBody hides that a code was sent")
    }

    // MARK: - App Store purchases

    func testStoreModelsPrintNoTransactionOrAccountToken() throws {
        assertNoSecret(AppleLinkBody(signedTransaction: "apple-signed-transaction-vwx"))
        assertNoSecret(try decode(ApplePurchaseTokenResponse.self,
                                  #"{"appAccountToken": "app-account-token-yz0"}"#))
    }

    // MARK: - Connect

    func testConnectConfigPrintsNoKeyMaterial() throws {
        var config = try decode(VPNConnectionConfig.self, """
        {"endpoint": "203.0.113.7:51820", "serverPublicKey": "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
         "presharedKey": "wg-preshared-key-789", "assignedIp": "10.100.0.2", "allowedIps": ["0.0.0.0/0"],
         "keyId": "key-1", "quantumEnabled": true, "rosenpassPublicKey": "pq-ciphertext-abc"}
        """)
        // Injected on-device after decode, exactly as APIClient does.
        config.privateKey = "wg-private-key-aaa"
        assertNoSecret(config)
        XCTAssertTrue(config.description.contains("serverAddress: 203.0.113.7"),
                      "VPNConnectionConfig lost its endpoint")
        XCTAssertTrue(config.description.contains("keyId: key-1"), "VPNConnectionConfig lost keyId")
    }

    // MARK: - Codable is untouched

    func testRedactionNeverReachesTheWire() throws {
        // The printed form is redacted; the encoded body must still carry the
        // real values, or every sign-in would send "[REDACTED]".
        let body = TwoFactorBody(challengeToken: "challenge-secret-ghi", token: "otp-424242")
        let json = String(decoding: try JSONEncoder().encode(body), as: UTF8.self)
        XCTAssertTrue(json.contains("challenge-secret-ghi") && json.contains("otp-424242"),
                      "encoding must be unaffected by the redacting description")
        XCTAssertFalse(json.contains("REDACTED"))
    }
}
