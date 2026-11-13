import XCTest

/// Owner items 43 / 85 / 97 — pins the pure decisions behind account deletion
/// (`AccountDeletion.swift`).
final class AccountDeletionTests: XCTestCase {

    // MARK: - Item 43: which failures leave the outcome UNKNOWN

    /// While connected, the erasure revokes this device's peer before the
    /// server answers, so a successful deletion usually arrives as a TIMEOUT.
    /// That must be checked, never reported as "deletion failed".
    func testLostAnswerIsUnknownNotFailure() {
        XCTAssertTrue(AccountDeletion.outcomeIsUnknown(URLError(.timedOut)))
        XCTAssertTrue(AccountDeletion.outcomeIsUnknown(URLError(.networkConnectionLost)))
        XCTAssertTrue(AccountDeletion.outcomeIsUnknown(URLError(.badServerResponse)))
    }

    /// The request provably never left: a definitive failure, nothing to check.
    func testRequestThatNeverLeftIsADefinitiveFailure() {
        XCTAssertFalse(AccountDeletion.outcomeIsUnknown(URLError(.notConnectedToInternet)))
        XCTAssertFalse(AccountDeletion.outcomeIsUnknown(URLError(.cannotFindHost)))
        XCTAssertFalse(AccountDeletion.outcomeIsUnknown(URLError(.dnsLookupFailed)))
        XCTAssertFalse(AccountDeletion.outcomeIsUnknown(URLError(.secureConnectionFailed)))
        // The pinning delegate cancels the challenge on a pin mismatch.
        XCTAssertFalse(AccountDeletion.outcomeIsUnknown(URLError(.cancelled)))
    }

    /// A server that ANSWERED is never unknown, whatever it said.
    func testAnsweredRefusalsAreNotUnknown() {
        struct NotAURLError: Error {}
        XCTAssertFalse(AccountDeletion.outcomeIsUnknown(NotAURLError()))
        XCTAssertFalse(AccountDeletion.outcomeIsUnknown(DeletionRefusal.twoFactorRequired))
    }

    // MARK: - Item 85: two-factor refusals

    private func classify(_ status: Int, _ json: String) -> DeletionRefusal? {
        DeletionRefusal.classify(status: status, body: Data(json.utf8))
    }

    /// The contract: 403 `{"error":"two_factor_required"}`.
    func testContractBodiesClassify() {
        XCTAssertEqual(classify(403, #"{"error":"two_factor_required"}"#), .twoFactorRequired)
        XCTAssertEqual(classify(403, #"{"error":"two_factor_invalid"}"#), .twoFactorInvalid)
    }

    /// The same codes as GlobalExceptionFilter actually serialises them, and
    /// in the other places a backend might put them.
    func testCodeIsFoundWhereverTheFilterPutsIt() {
        XCTAssertEqual(classify(403, #"{"statusCode":403,"message":"Two-factor code required","error":"two_factor_required","requestId":"r"}"#),
                       .twoFactorRequired)
        XCTAssertEqual(classify(403, #"{"message":"Forbidden","error":"Forbidden","details":{"code":"two_factor_invalid"}}"#),
                       .twoFactorInvalid)
        XCTAssertEqual(classify(403, #"{"code":"TWO_FACTOR_REQUIRED"}"#), .twoFactorRequired)
        XCTAssertEqual(classify(401, #"{"error":"two_factor_required"}"#), .twoFactorRequired,
                       "a 401 carrying the code is the same refusal")
    }

    /// Everything else stays with the generic mapping: wrong password,
    /// rate limit, server errors, prose that merely mentions two-factor.
    func testOtherRefusalsAreNotTwoFactor() {
        XCTAssertNil(classify(401, #"{"message":"Incorrect password","error":"Unauthorized"}"#))
        XCTAssertNil(classify(429, #"{"message":"Too many attempts","error":"Too Many Requests"}"#))
        XCTAssertNil(classify(500, #"{"error":"two_factor_required"}"#), "5xx is never a 2FA refusal")
        XCTAssertNil(classify(403, #"{"message":"two_factor_required is needed for this"}"#))
        XCTAssertNil(classify(403, "not json"))
        XCTAssertNil(classify(403, "[]"))
    }

    // MARK: - Item 85: the request body

    private func encoded(_ body: DeleteAccountBody) throws -> [String: String] {
        let data = try JSONEncoder().encode(body)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
    }

    /// A password-less account with no second factor sends `{}`, exactly as
    /// before the new fields existed.
    func testEmptyBodyStaysEmpty() throws {
        XCTAssertEqual(try encoded(DeleteAccountBody(password: nil)), [:])
        XCTAssertEqual(try encoded(DeleteAccountBody(password: "  ", twoFactorCode: "")), [:])
    }

    func testTwoFactorCodeUsesTheContractKey() throws {
        XCTAssertEqual(try encoded(DeleteAccountBody(password: " hunter2 ", twoFactorCode: " 123456 ")),
                       ["password": "hunter2", "twoFactorCode": "123456"])
    }

    // MARK: - MR-179: the step-up (owner 2026-11-13)

    /// The step-up refusals are told apart by `error`, never by the sentence.
    func testStepUpRefusalsClassifyByCode() {
        XCTAssertEqual(classify(403, #"{"statusCode":403,"error":"account_number_required","message":"x"}"#), .accountNumberRequired)
        XCTAssertEqual(classify(403, #"{"error":"account_number_invalid"}"#), .accountNumberInvalid)
        XCTAssertEqual(classify(403, #"{"error":"email_code_required"}"#), .emailCodeRequired)
        XCTAssertEqual(classify(403, #"{"error":"email_code_invalid"}"#), .emailCodeInvalid)
        XCTAssertEqual(classify(403, #"{"error":"apple_reauth_required"}"#), .appleReauthRequired)
        XCTAssertEqual(classify(403, #"{"error":"apple_reauth_invalid"}"#), .appleReauthInvalid)
        // A 429 stays the generic rate limit; prose alone is never a refusal.
        XCTAssertNil(classify(429, #"{"error":"too_many_attempts","message":"Too many attempts."}"#))
        XCTAssertNil(classify(403, #"{"note":"account_number_required"}"#))
    }

    func testStepUpProofUsesTheContractKeysAndIsOmittedWhenAbsent() throws {
        XCTAssertEqual(try encoded(DeleteAccountBody(password: nil, accountNumber: "1234 5678 9012 3456 7890 1234")),
                       ["accountNumber": "123456789012345678901234"])
        XCTAssertEqual(try encoded(DeleteAccountBody(password: nil, emailCode: " 654 321 ")),
                       ["emailCode": "654321"])
        XCTAssertEqual(try encoded(DeleteAccountBody(password: nil, appleIdentityToken: "id.tok", appleNonce: "n0nce")),
                       ["appleIdentityToken": "id.tok", "appleNonce": "n0nce"])
        // Nothing sent: still `{}`, as before.
        XCTAssertEqual(try encoded(DeleteAccountBody(password: nil, accountNumber: " ", emailCode: "")), [:])
    }

    func testAccountNumberAndEmailCodeCompleteness() {
        XCTAssertTrue(AccountDeletion.isCompleteAccountNumber("123456789012345678901234"))
        XCTAssertTrue(AccountDeletion.isCompleteAccountNumber("1234 5678 9012 3456 7890 1234"))
        XCTAssertFalse(AccountDeletion.isCompleteAccountNumber("12345678901234567890123"))
        XCTAssertFalse(AccountDeletion.isCompleteAccountNumber(""))
        XCTAssertTrue(AccountDeletion.isCompleteEmailCode("654321"))
        XCTAssertFalse(AccountDeletion.isCompleteEmailCode("65432"))
        XCTAssertFalse(AccountDeletion.isCompleteEmailCode("65432a"))
    }

    func testPreflightStepUpDecodesAndAnOlderBackendHasNone() throws {
        let with = try JSONDecoder().decode(
            DeletionStepUp.self,
            from: Data(#"{"factors":["account_number","two_factor"],"enforced":true}"#.utf8))
        XCTAssertTrue(with.needsAccountNumber)
        XCTAssertFalse(with.needsEmailCode)
        XCTAssertEqual(with.enforced, true)
        let sent = try JSONDecoder().decode(DeletionCodeSent.self,
                                            from: Data(#"{"success":true,"sentTo":"o***@example.com"}"#.utf8))
        XCTAssertEqual(sent.sentTo, "o***@example.com")
    }

    // MARK: - Item 97: Sign in with Apple

    func testAppleCodeUsesTheContractKeyAndIsOmittedWhenAbsent() throws {
        XCTAssertEqual(try encoded(DeleteAccountBody(password: nil, appleAuthorizationCode: "c0de.1")),
                       ["appleAuthorizationCode": "c0de.1"])
        XCTAssertEqual(try encoded(DeleteAccountBody(password: nil, twoFactorCode: "123456",
                                                     appleAuthorizationCode: nil)),
                       ["twoFactorCode": "123456"])
    }

    /// /auth/me does not say whether an account is Apple-linked: offered when
    /// this device signed in with Apple, or the email is Apple's alias / relay.
    func testAppleReauthIsOfferedOnlyWhenTheAppKnows() {
        XCTAssertTrue(AccountDeletion.offersAppleReauth(signedInWithAppleOnThisDevice: true,
                                                        accountEmail: "person@example.com"))
        XCTAssertTrue(AccountDeletion.offersAppleReauth(signedInWithAppleOnThisDevice: false,
                                                        accountEmail: "apple_0123abcd@appleid.local"))
        XCTAssertTrue(AccountDeletion.offersAppleReauth(signedInWithAppleOnThisDevice: false,
                                                        accountEmail: "x7y8@PrivateRelay.AppleID.com"))
        XCTAssertFalse(AccountDeletion.offersAppleReauth(signedInWithAppleOnThisDevice: false,
                                                         accountEmail: "person@example.com"))
        XCTAssertFalse(AccountDeletion.offersAppleReauth(signedInWithAppleOnThisDevice: false,
                                                         accountEmail: "anon_123456789012345678901234@anonymous.local"))
        XCTAssertFalse(AccountDeletion.offersAppleReauth(signedInWithAppleOnThisDevice: false,
                                                         accountEmail: nil))
    }

    func testCodeCompleteness() {
        XCTAssertTrue(AccountDeletion.isCompleteTwoFactorCode("123456"))
        XCTAssertTrue(AccountDeletion.isCompleteTwoFactorCode("abcd-ef01-2345-6789"))
        XCTAssertTrue(AccountDeletion.isCompleteTwoFactorCode("ABCDEF01"))
        XCTAssertFalse(AccountDeletion.isCompleteTwoFactorCode("12345"))
        XCTAssertFalse(AccountDeletion.isCompleteTwoFactorCode(""))
        XCTAssertFalse(AccountDeletion.isCompleteTwoFactorCode("abcd"))
    }

    // MARK: - MR-179: the 24-hour hold (birdo-web #883)

    private let applyAfter = "2026-11-14T12:00:00.000Z"
    private var applyAfterDate: Date { Date(timeIntervalSince1970: 1_794_657_600) } // 2026-11-14T12:00:00Z

    private func success(_ status: Int, _ json: String) -> DeletionHold.SuccessKind {
        DeletionHold.classifySuccess(status: status, body: Data(json.utf8))
    }

    private func conflict(_ status: Int, _ json: String) -> DeletionHoldRefusal? {
        DeletionHold.classifyConflict(status: status, body: Data(json.utf8))
    }

    func testHoldRequestSendsTheMethodWithThePasswordAndCode() throws {
        XCTAssertEqual(try encoded(DeleteAccountBody(password: "hunter2", twoFactorCode: "123456", stepUpMethod: "hold")),
                       ["password": "hunter2", "twoFactorCode": "123456", "stepUpMethod": "hold"])
        XCTAssertEqual(try encoded(DeleteAccountBody(password: nil, stepUpMethod: DeletionHold.method)),
                       ["stepUpMethod": "hold"])
        // Not asked for: not sent.
        XCTAssertEqual(try encoded(DeleteAccountBody(password: nil)), [:])
    }

    func testHoldIsOfferedOnlyWhenThePreflightSaysSo() throws {
        let on = try JSONDecoder().decode(
            DeletionStepUp.self,
            from: Data(#"{"factors":["account_number"],"enforced":true,"holdAvailable":true}"#.utf8))
        XCTAssertEqual(on.holdAvailable, true)
        let off = try JSONDecoder().decode(
            DeletionStepUp.self, from: Data(#"{"factors":["account_number"],"enforced":false,"holdAvailable":false}"#.utf8))
        XCTAssertEqual(off.holdAvailable, false)
        // An older backend: missing means false (`== true` is the gate).
        let old = try JSONDecoder().decode(DeletionStepUp.self, from: Data(#"{"factors":["account_number"],"enforced":true}"#.utf8))
        XCTAssertNil(old.holdAvailable)
        XCTAssertFalse(old.holdAvailable == true)
    }

    /// 202 `change_pending` is a hold, NOT a deletion; a hold that cannot be
    /// read is never a success either.
    func testA202IsAHoldNeverADeletion() {
        let held = #"{"success":true,"pending":true,"code":"change_pending","message":"x","stepUp":{"methods":["hold"],"pendingChangeId":"p1","applyAfter":"2026-11-14T12:00:00.000Z"}}"#
        XCTAssertEqual(success(202, held), .held(PendingDeletion(id: "p1", applyAfter: applyAfterDate)))
        // The markers alone are enough, whatever the status.
        XCTAssertEqual(success(200, held), .held(PendingDeletion(id: "p1", applyAfter: applyAfterDate)))
        XCTAssertEqual(success(202, #"{"success":true,"pending":true,"stepUp":["hold"]}"#), .unreadableHold)
        XCTAssertEqual(success(202, #"{"success":true,"pending":true,"stepUp":{"pendingChangeId":"","applyAfter":"2026-11-14T12:00:00Z"}}"#), .unreadableHold)
        XCTAssertEqual(success(202, #"{"pending":true,"stepUp":{"pendingChangeId":"p1","applyAfter":"tomorrow"}}"#), .unreadableHold)
        XCTAssertEqual(success(202, ""), .unreadableHold)
        XCTAssertEqual(success(202, "<html>"), .unreadableHold)
        // A 200 erasure stays a deletion, as before.
        XCTAssertEqual(success(200, #"{"success":true,"deletedItems":3,"storeSubscriptionsStillBilling":[]}"#), .deletion)
    }

    func testA409NamesTheHoldThatAlreadyWaitsOrSaysItIsUnavailable() {
        let p0 = PendingDeletion(id: "p0", applyAfter: applyAfterDate)
        XCTAssertEqual(conflict(409, #"{"statusCode":409,"error":"change_already_pending","message":"x","stepUp":{"methods":["hold"],"pendingChangeId":"p0","applyAfter":"2026-11-14T12:00:00.000Z"}}"#),
                       .alreadyPending(p0))
        XCTAssertEqual(conflict(409, #"{"error":"change_already_pending","details":{"stepUp":{"pendingChangeId":"p0","applyAfter":"2026-11-14T12:00:00Z"}}}"#),
                       .alreadyPending(p0))
        // The exception filter passes only `details`: no stepUp on the wire.
        XCTAssertEqual(conflict(409, #"{"statusCode":409,"message":"A deletion is already on hold.","error":"change_already_pending","timestamp":"t","path":"/api/v1/gdpr/delete"}"#),
                       .alreadyPending(nil))
        XCTAssertEqual(conflict(409, #"{"error":"hold_unavailable","message":"Deleting in 24 hours is not available right now."}"#),
                       .unavailable(message: "Deleting in 24 hours is not available right now."))
        XCTAssertEqual(conflict(409, #"{"error":"hold_unavailable"}"#), .unavailable(message: nil))
        XCTAssertNil(conflict(409, #"{"error":"Conflict","message":"x"}"#))
        XCTAssertNil(conflict(403, #"{"error":"change_already_pending"}"#))
        XCTAssertNil(conflict(409, "not json"))
    }

    /// On 401 / 403 / 429 `stepUp` is an ARRAY of factors: the refusal is
    /// still classified by its code, and no array is ever read as a hold.
    func testTheRefusalsStepUpArrayIsNeverAHold() {
        XCTAssertEqual(classify(403, #"{"statusCode":403,"error":"two_factor_required","message":"x","stepUp":["password","two_factor"]}"#),
                       .twoFactorRequired)
        XCTAssertEqual(classify(403, #"{"error":"two_factor_invalid","stepUp":["two_factor"]}"#), .twoFactorInvalid)
        XCTAssertNil(classify(429, #"{"error":"too_many_attempts","stepUp":["account_number"]}"#))
        XCTAssertNil(classify(403, #"{"error":"step_up_no_factor","message":"x","stepUp":[]}"#))
        XCTAssertNil(DeletionHold.pending(fromStepUp: ["account_number", "two_factor"]))
        XCTAssertNil(DeletionHold.pending(fromStepUp: nil))
        XCTAssertNil(DeletionHold.pending(fromStepUp: ["pendingChangeId": 7, "applyAfter": "2026-11-14T12:00:00Z"] as [String: Any]))
        XCTAssertNil(conflict(403, #"{"error":"two_factor_required","stepUp":["two_factor"]}"#))
    }

    func testThePendingChangesListYieldsTheAccountDeletion() {
        let list = #"{"pendingChanges":[{"id":"e1","action":"email_change","description":"x","applyAfter":"2026-11-14T10:00:00.000Z","createdAt":"t"},{"id":"p1","action":"account_delete","description":"delete the account","applyAfter":"2026-11-14T12:00:00.000Z","createdAt":"t"}]}"#
        XCTAssertEqual(DeletionHold.accountDeletion(inPendingChanges: Data(list.utf8)),
                       .some(PendingDeletion(id: "p1", applyAfter: applyAfterDate)))
        // None on hold: known to be none.
        XCTAssertEqual(DeletionHold.accountDeletion(inPendingChanges: Data(#"{"pendingChanges":[]}"#.utf8)), .some(nil))
        // Not the list at all: unknown (the banner keeps what it shows).
        XCTAssertTrue(DeletionHold.accountDeletion(inPendingChanges: Data("<html>".utf8)) == nil)
        XCTAssertTrue(DeletionHold.accountDeletion(inPendingChanges: Data(#"{"error":"x"}"#.utf8)) == nil)
    }

    func testInstantsParseWithOrWithoutFractionsAndOffsets() {
        XCTAssertEqual(DeletionHold.parseInstant("2026-11-14T12:00:00.000Z"), applyAfterDate)
        XCTAssertEqual(DeletionHold.parseInstant("2026-11-14T12:00:00Z"), applyAfterDate)
        XCTAssertEqual(DeletionHold.parseInstant("2026-11-14T13:00:00+01:00"), applyAfterDate)
        XCTAssertNil(DeletionHold.parseInstant("tomorrow"))
        XCTAssertNil(DeletionHold.parseInstant(""))
    }

    /// The banner says when, in the device's zone.
    func testTheBannerShowsApplyAfterInTheDevicesZone() throws {
        let berlin = DeletionHold.bannerText(applyAfter: applyAfterDate,
                                             timeZone: try XCTUnwrap(TimeZone(identifier: "Europe/Berlin")),
                                             locale: Locale(identifier: "en_GB"))
        XCTAssertTrue(berlin.hasPrefix("Your account will be deleted on "), berlin)
        XCTAssertTrue(berlin.contains("13:00"), berlin)
        XCTAssertTrue(berlin.contains("2026"), berlin)
        let utc = DeletionHold.bannerText(applyAfter: applyAfterDate,
                                          timeZone: try XCTUnwrap(TimeZone(identifier: "UTC")),
                                          locale: Locale(identifier: "en_GB"))
        XCTAssertTrue(utc.contains("12:00"), utc)
    }
}
