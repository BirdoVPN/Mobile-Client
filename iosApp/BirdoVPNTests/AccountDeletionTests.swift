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

    func testCodeCompleteness() {
        XCTAssertTrue(AccountDeletion.isCompleteTwoFactorCode("123456"))
        XCTAssertTrue(AccountDeletion.isCompleteTwoFactorCode("abcd-ef01-2345-6789"))
        XCTAssertTrue(AccountDeletion.isCompleteTwoFactorCode("ABCDEF01"))
        XCTAssertFalse(AccountDeletion.isCompleteTwoFactorCode("12345"))
        XCTAssertFalse(AccountDeletion.isCompleteTwoFactorCode(""))
        XCTAssertFalse(AccountDeletion.isCompleteTwoFactorCode("abcd"))
    }
}
