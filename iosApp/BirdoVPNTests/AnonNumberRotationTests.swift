import XCTest

/// MR-1707 — the masked anonymous number and the two-phase "replace number"
/// flow (Desktop-Client #416 against birdo-web #850). Fake numbers only.
final class AnonNumberRotationTests: XCTestCase {

    private let oldNumber = "111122223333444455556666"
    private let newNumber = "777788889999000011112222"
    private let mask = "********************6666"

    // MARK: - The mask

    func testTheMaskFormatterMatchesDesktopsRule() {
        XCTAssertTrue(AnonNumberMask.isMasked(mask))
        XCTAssertEqual(AnonNumberMask.lastDigits(mask), "6666")
        XCTAssertEqual(AnonNumberMask.display(lastDigits: "6666"), "•••• … 6666")
        XCTAssertFalse(AnonNumberMask.isMasked(oldNumber))
        for bad in ["", "****", "********************66", "66********************",
                    "********************abcd", "*********************6666"] {
            XCTAssertFalse(AnonNumberMask.isMasked(bad), bad)
            XCTAssertNil(AnonNumberMask.lastDigits(bad), bad)
        }
    }

    func testOnlyARealNumberIsStorable() {
        XCTAssertEqual(AnonNumberMask.storable(oldNumber), oldNumber)
        XCTAssertNil(AnonNumberMask.storable(mask))
        XCTAssertNil(AnonNumberMask.storable("•••• … 6666"))
        XCTAssertNil(AnonNumberMask.storable(nil))
    }

    func testTheCardNoteIsDesktopsWording() {
        XCTAssertEqual(AnonNumberMask.cardNote,
                       "Shown once at sign-up or when you replace it. Replace it if you lost it.")
    }

    // MARK: - The flag

    func testTheFlagShipsOffAndHidesTheEntry() {
        XCTAssertFalse(AnonNumberRotation.isEnabled)
        XCTAssertFalse(AnonNumberRotation.entryVisible(isAnonymous: true))
        XCTAssertFalse(AnonNumberRotation.entryVisible(flag: false, isAnonymous: true))
        XCTAssertTrue(AnonNumberRotation.entryVisible(flag: true, isAnonymous: true))
        XCTAssertFalse(AnonNumberRotation.entryVisible(flag: true, isAnonymous: false))
    }

    // MARK: - The 401 classification

    private func body(_ json: String) -> Data { Data(json.utf8) }

    func testAHandlers401IsTheVerdictAndNeverRefreshed() {
        for json in [
            #"{"statusCode":401,"error":"rotation_proof_invalid","message":"Unauthorized"}"#,
            #"{"statusCode":401,"error":"rotation_proof_invalid"}"#,
            #"{"statusCode":401,"error":"rotation_proof_invalid","message":"Invalid credentials"}"#,
            #"{"statusCode":401,"message":"Incorrect account number or password"}"#,
            #"{"statusCode":401,"error":"Unauthorized","message":"Invalid proof"}"#,
            #"{"statusCode":401,"message":["a","b"]}"#,
        ] {
            XCTAssertEqual(AnonNumberRotationWire.classify401(body: body(json)), .proofRejected, json)
        }
    }

    func testOnlyABareUnauthorizedRefreshes() {
        for json in [
            #"{"statusCode":401,"message":"Unauthorized"}"#,
            #"{"statusCode":401,"error":"Unauthorized","message":"Unauthorized"}"#,
            #"{"statusCode":401,"message":" unauthorized "}"#,
            #"{"statusCode":401}"#,
            "",
            "not json",
        ] {
            XCTAssertEqual(AnonNumberRotationWire.classify401(body: body(json)), .sessionExpired, json)
        }
    }

    func testEachStatusClassifiesByItsCode() {
        func c(_ status: Int, _ json: String, _ step: AnonNumberRotationWire.Step) -> RotationRefusal? {
            AnonNumberRotationWire.classify(status: status, body: body(json), step: step)
        }
        XCTAssertEqual(c(403, #"{"error":"anon_number_rotation_disabled"}"#, .prepare), .disabled)
        XCTAssertNil(c(403, #"{"message":"Forbidden"}"#, .prepare), "a bare 403 is not this refusal")
        XCTAssertEqual(c(409, #"{"error":"rotation_not_pending"}"#, .confirm), .notPending)
        XCTAssertEqual(c(409, #"{"error":"rotation_account_changed"}"#, .confirm), .accountChanged)
        XCTAssertNil(c(409, #"{"message":"Conflict"}"#, .confirm))
        XCTAssertEqual(c(400, #"{"error":"rotation_number_mismatch"}"#, .confirm), .mismatch)
        XCTAssertEqual(c(400, "{}", .prepare), .badRequest)
        XCTAssertEqual(c(429, "{}", .confirm), .rateLimited)
        XCTAssertNil(c(500, "{}", .confirm))
        XCTAssertEqual(c(401, #"{"error":"rotation_proof_invalid"}"#, .prepare), .proofRejected)
    }

    // MARK: - The wire

    func testTheBodiesMatchTheContractAndPrintNothing() throws {
        let prepare = RotatePrepareBody(currentNumber: oldNumber, password: nil, twoFactorCode: "123456")
        let data = try JSONEncoder().encode(prepare)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
        XCTAssertEqual(json, ["currentNumber": oldNumber, "twoFactorCode": "123456"], "a nil password is omitted")
        XCTAssertFalse("\(prepare)".contains("1111"))
        XCTAssertFalse(String(reflecting: prepare).contains("123456"))
        let confirm = RotateConfirmBody(newNumber: newNumber, deviceId: "dev", deviceName: "phone",
                                        platformVersion: "1", appVersion: "1")
        XCTAssertFalse("\(confirm)".contains("7777"))
        let reply = Data(#"{"ok":true,"newNumber":"777788889999000011112222","expiresInSeconds":600}"#.utf8)
        let answer = try JSONDecoder().decode(RotatePrepareResponse.self, from: reply)
        XCTAssertEqual(answer.newNumber, newNumber)
        XCTAssertFalse("\(answer)".contains("7777"))
    }

    // MARK: - The step machine

    private func atConfirm() -> RotationFlow {
        var f = RotationFlow()
        f.prepared(newNumber: newNumber)
        f.saved = true
        f.continueToConfirm()
        XCTAssertEqual(f.step, .confirm)
        return f
    }

    func testStepTwoNeedsTheSavedToggle() {
        var f = RotationFlow()
        f.prepared(newNumber: newNumber)
        XCTAssertEqual(f.step, .showNew)
        XCTAssertFalse(f.saved, "the toggle starts unticked")
        f.continueToConfirm()
        XCTAssertEqual(f.step, .showNew)
        f.saved = true
        f.continueToConfirm()
        XCTAssertEqual(f.step, .confirm)
    }

    func testThe409sAndTheDisabledSwitchRestartAtStepOne() {
        for refusal in [RotationRefusal.notPending, .accountChanged, .disabled] {
            var f = atConfirm()
            f.confirmFailed(.refusal(refusal))
            XCTAssertEqual(f.step, .prove, "\(refusal)")
            XCTAssertNil(f.newNumber, "\(refusal)")
            XCTAssertFalse(f.saved)
            XCTAssertNotNil(f.notice)
        }
        var f = atConfirm()
        f.confirmFailed(.refusal(.notPending))
        XCTAssertEqual(f.notice, .expired)
        f = atConfirm()
        f.confirmFailed(.refusal(.accountChanged))
        XCTAssertEqual(f.notice, .accountChanged)
        f = atConfirm()
        f.confirmFailed(.refusal(.disabled))
        XCTAssertEqual(f.notice, .disabled)
    }

    func testA409OnTheFirstConfirmStillRestartsAtStepOne() {
        var f = atConfirm()
        f.confirmSent()
        f.confirmFailed(.refusal(.notPending))
        XCTAssertEqual(f.step, .prove)
        XCTAssertEqual(f.notice, .expired)
    }

    func testALaterConfirmThatIsRefusedMeansTheNumberMayBeActive() {
        for refusal in [RotationFailure.refusal(.notPending), .refusal(.proofRejected), .sessionEnded] {
            var f = atConfirm()
            f.confirmSent()
            f.confirmFailed(.network)               // the first answer was lost
            XCTAssertEqual(f.step, .confirm)
            f.confirmSent()
            f.confirmFailed(refusal)                // the retry is refused
            XCTAssertEqual(f.step, .maybeCommitted, "\(refusal)")
            XCTAssertEqual(f.notice, .maybeActive)
            XCTAssertNil(f.newNumber)
        }
        XCTAssertEqual(RotationFlow.Notice.maybeActive.message,
                       "Your new number may already be active. Sign in with the new number you saved.")
    }

    func testALaterMismatchOrNetworkErrorStillStaysOnStepThree() {
        var f = atConfirm()
        f.confirmSent()
        f.confirmFailed(.network)
        f.confirmSent()
        f.confirmFailed(.refusal(.mismatch))
        XCTAssertEqual(f.step, .confirm)
        XCTAssertEqual(f.newNumber, newNumber)
    }

    func testAMismatchStaysOnStepThree() {
        var f = atConfirm()
        f.confirmFailed(.refusal(.mismatch))
        XCTAssertEqual(f.step, .confirm)
        XCTAssertEqual(f.newNumber, newNumber)
        XCTAssertEqual(f.notice, .mismatch)
    }

    func testARateLimitStaysOnStepThreeToo() {
        var f = atConfirm()
        f.confirmFailed(.refusal(.rateLimited))
        XCTAssertEqual(f.step, .confirm)
        XCTAssertEqual(f.newNumber, newNumber)
        XCTAssertEqual(f.notice, .rateLimited)
    }

    func testALostConfirmKeepsStepThreeAndTheNumberSoItCanBeRetried() {
        var f = atConfirm()
        // A network error (URLError) is no answer at all.
        XCTAssertEqual(RotationFailure.from(URLError(.networkConnectionLost)), .network)
        f.confirmFailed(RotationFailure.from(URLError(.timedOut)))
        XCTAssertEqual(f.step, .confirm)
        XCTAssertEqual(f.newNumber, newNumber, "the number is still in memory")
        XCTAssertEqual(f.notice, .offline)
        // The retry succeeds.
        f.confirmed(storedLocally: true)
        XCTAssertEqual(f.step, .done)
        XCTAssertNil(f.newNumber)
    }

    func testPrepareFailuresStayOnStepOneWithADistinctNotice() {
        let cases: [(RotationFailure, RotationFlow.Notice)] = [
            (.refusal(.proofRejected), .proofRejected),
            (.refusal(.disabled), .disabled),
            (.refusal(.rateLimited), .rateLimited),
            (.refusal(.badRequest), .notAnAnonymousAccount),
            (.network, .offline),
            (.sessionEnded, .sessionEnded),
        ]
        for (failure, notice) in cases {
            var f = RotationFlow()
            f.prepareFailed(failure)
            XCTAssertEqual(f.step, .prove)
            XCTAssertEqual(f.notice, notice)
            XCTAssertNil(f.newNumber)
        }
    }

    func testClearDropsTheNumberAndTheToggle() {
        var f = atConfirm()
        f.clear()
        XCTAssertEqual(f, RotationFlow())
        XCTAssertFalse("\(atConfirm())".contains("7777"), "the flow never prints the number")
    }

    func testAFailedLocalStoreKeepsTheNewNumberOnScreen() {
        var f = atConfirm()
        f.confirmed(storedLocally: false)
        XCTAssertEqual(f.step, .done)
        XCTAssertEqual(f.newNumber, newNumber)
        XCTAssertEqual(f.notice, .storeFailed)
    }

    // MARK: - The commit

    private final class Store: RotationCommitStore {
        var access: String?
        var refresh: String?
        var anonymousId: String?
        var failTokens = false
        func saveRotationSession(accessToken: String, refreshToken: String) -> Bool {
            guard !failTokens else { return false }
            access = accessToken
            refresh = refreshToken
            return true
        }
        func saveAnonymousId(_ id: String) -> Bool { anonymousId = id; return true }
    }

    func testASuccessfulRotationStoresTheNewNumberAndTheNewTokens() {
        let store = Store()
        store.anonymousId = oldNumber
        XCTAssertTrue(AnonNumberRotationCommit.commit(newNumber: newNumber, accessToken: "a-new",
                                                      refreshToken: "r-new", to: store))
        XCTAssertEqual(store.anonymousId, newNumber)
        XCTAssertEqual(store.access, "a-new")
        XCTAssertEqual(store.refresh, "r-new")
    }

    func testTokensThatCannotBeKeptLeaveTheStoredNumberAlone() {
        let store = Store()
        store.anonymousId = oldNumber
        store.failTokens = true
        XCTAssertFalse(AnonNumberRotationCommit.commit(newNumber: newNumber, accessToken: "a",
                                                       refreshToken: "r", to: store))
        XCTAssertEqual(store.anonymousId, oldNumber)
    }
}
