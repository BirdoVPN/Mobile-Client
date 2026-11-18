import XCTest

/// MR-179 hold origin: what signing out of this app does to an account
/// deletion on its 24-hour hold.
///
/// birdo-web #883 (`heldDeletionVoided`): a sign-out voids a held deletion
/// ONLY when the session that signs out filed it. One asked for on the website
/// (no session) or on another device survives this app's sign-out. The app
/// says "signing out cancels the deletion" only for a hold this session filed,
/// and cancels such a hold itself before signing out.
///
/// The logic is `AccountDeletion.swift`'s (HeldDeletionRecord,
/// HeldDeletionController, SignOutHold), run here against a fake server and
/// an in-memory keychain store. The last section pins where AuthViewModel,
/// KeychainService and ProfileView call it, by reading their sources (they
/// cannot be compiled into this bundle).
final class HeldDeletionOriginTests: XCTestCase {

    private static let applyAfterDate = Date(timeIntervalSince1970: 1_794_657_600) // 2026-11-14T12:00:00Z
    private let filedOnThisDevice = PendingDeletion(id: "app1", applyAfter: HeldDeletionOriginTests.applyAfterDate)
    private let filedElsewhere = PendingDeletion(id: "web1", applyAfter: HeldDeletionOriginTests.applyAfterDate)

    private let gb = Locale(identifier: "en_GB")
    private func utc() throws -> TimeZone { try XCTUnwrap(TimeZone(identifier: "UTC")) }

    /// The date as every text formats it.
    private func when() throws -> String {
        let banner = DeletionHold.bannerText(applyAfter: Self.applyAfterDate, timeZone: try utc(), locale: gb)
        return String(banner.dropFirst("Your account will be deleted on ".count).dropLast())
    }

    private func line(_ notice: SignOutHoldNotice) throws -> String? {
        notice.message(timeZone: try utc(), locale: gb)
    }

    // The common wording (Desktop-Client #439, Mobile-Client #623, this app).
    private let keeps = "Signing out of this app may not cancel this deletion. To keep your account, cancel the deletion."
    private let unchecked = "We couldn't check whether a deletion is waiting. Signing out may not cancel it. "
        + "To keep your account, stay signed in and cancel it once you're online."

    // MARK: - The hold request's answer (#883 bodies)

    func testA202FilesTheHoldAndA409NamesOneAlreadyWaiting() {
        func answer(_ status: Int, _ json: String) -> DeletionHoldAnswer? {
            DeletionHold.holdRequestAnswer(status: status, body: Data(json.utf8))
        }
        let filed = #"{"success":true,"pending":true,"code":"change_pending","message":"x","stepUp":{"methods":["hold"],"pendingChangeId":"app1","applyAfter":"2026-11-14T12:00:00.000Z"}}"#
        XCTAssertEqual(answer(202, filed), .filed(filedOnThisDevice))
        XCTAssertEqual(answer(202, filed)?.filedHere, true)

        let waiting = #"{"statusCode":409,"error":"change_already_pending","message":"x","details":{"stepUp":{"methods":["hold"],"pendingChangeId":"web1","applyAfter":"2026-11-14T12:00:00.000Z"}}}"#
        XCTAssertEqual(answer(409, waiting), .alreadyPending(filedElsewhere))
        XCTAssertEqual(answer(409, waiting)?.filedHere, false)

        // Not a hold: the refusals keep their own classification.
        XCTAssertNil(answer(409, #"{"error":"change_already_pending","message":"x"}"#))
        XCTAssertNil(answer(409, #"{"error":"hold_unavailable","stepUp":["account_number"]}"#))
        XCTAssertNil(answer(403, #"{"error":"two_factor_required","stepUp":["two_factor"]}"#))
        XCTAssertNil(answer(200, "{}"))
        XCTAssertNil(answer(202, #"{"pending":true,"stepUp":["hold"]}"#))
    }

    // MARK: - The wording

    func testTheCommonWording() throws {
        let date = try when()
        XCTAssertEqual(try line(.filedHere(filedOnThisDevice)),
                       "Signing out cancels the deletion of your account that is scheduled for \(date).")
        XCTAssertEqual(try line(.notFiledHere(filedElsewhere)), "Your account will be deleted on \(date). " + keeps)
        XCTAssertEqual(try line(.unchecked), unchecked)
        XCTAssertEqual(try line(.filedHereUnchecked(id: "app1")), unchecked)
        XCTAssertNil(try line(.nothingWaiting))
        // The app does not know where a listed hold came from: no origin is named.
        for notice in [SignOutHoldNotice.notFiledHere(filedElsewhere), .unchecked, .filedHereUnchecked(id: "app1")] {
            let text = try XCTUnwrap(try line(notice))
            XCTAssertFalse(text.contains("website") || text.contains("another device"), text)
        }
        // The banner and the deletion dialog's hold step.
        XCTAssertEqual(DeletionHold.bannerSignOutNote(filedHere: false), keeps)
        XCTAssertEqual(DeletionHold.bannerSignOutNote(filedHere: true), DeletionHold.signOutCancels)
        XCTAssertEqual(DeletionHold.holdStepSignOutNote(holdKnown: false, listAnswered: true),
                       DeletionHold.signOutCancels)
        XCTAssertNil(DeletionHold.holdStepSignOutNote(holdKnown: true, listAnswered: true),
                     "with a hold waiting, a new request is a 409 this session may not have filed")
        // MR-2389 nit: not before the list answered (a 409 would correct it, too late).
        XCTAssertNil(DeletionHold.holdStepSignOutNote(holdKnown: false, listAnswered: false))
        XCTAssertNil(DeletionHold.holdStepSignOutNote(holdKnown: true, listAnswered: false))
    }

    // MARK: - The hold request's refusal hook (MR-2389 nit: the call site is pinned)

    /// The hook `APIClient.requestDeletionHold` passes is THIS function
    /// (pinned by source below), so a 409 that names the hold reaches the
    /// caller as `.alreadyPending`, never as a failure or as `.filed`.
    func testTheHoldRequestRefusalHookTellsTheAnswersApart() throws {
        func hook(_ status: Int, _ json: String) -> Error? {
            DeletionHold.holdRequestRefusal(status: status, body: Data(json.utf8))
        }
        let waiting = #"{"statusCode":409,"error":"change_already_pending","message":"x","details":{"stepUp":{"methods":["hold"],"pendingChangeId":"web1","applyAfter":"2026-11-14T12:00:00.000Z"}}}"#
        let named = try XCTUnwrap(hook(409, waiting) as? DeletionHoldNamed)
        XCTAssertEqual(named.answer, .alreadyPending(filedElsewhere))
        XCTAssertFalse(named.answer.filedHere)

        XCTAssertEqual(hook(403, #"{"error":"two_factor_required","stepUp":["two_factor"]}"#) as? DeletionRefusal,
                       .twoFactorRequired)
        XCTAssertTrue(hook(409, #"{"error":"hold_unavailable","stepUp":["account_number"]}"#) is DeletionHoldRefusal)
        XCTAssertTrue(hook(409, #"{"error":"change_already_pending","message":"x"}"#) is DeletionHoldRefusal,
                      "an already-pending answer that names no hold stays a refusal")
        XCTAssertNil(hook(500, "{}"), "anything else keeps the generic mapping")
    }

    func testTheHoldRequestUsesTheHookAndMarksOnlyA2xxFiled() throws {
        let api = try source("iosApp/Services/APIClient.swift")
        let request = member("func requestDeletionHold(", in: api)
        XCTAssertTrue(request.contains("refusal: DeletionHold.holdRequestRefusal"), request)
        XCTAssertTrue(request.contains("catch let named as DeletionHoldNamed"))
        XCTAssertTrue(request.contains("return named.answer"))
        XCTAssertTrue(request.contains("DeletionHold.holdRequestAnswer(status: answer.status, body: answer.data)"))
        XCTAssertTrue(request.contains("throw DeletionHoldRefusal.unexpectedAnswer"))
        XCTAssertFalse(request.contains(".filed("), "only holdRequestAnswer decides what this request filed")

        let auth = try source("iosApp/ViewModels/AuthViewModel.swift")
        XCTAssertTrue(auth.contains("let answer = try await api.requestDeletionHold("))
        XCTAssertTrue(auth.contains("showDeletionHold(answer.pending, filedHere: answer.filedHere)"))
    }

    // MARK: - The record

    func testAHoldFiledHereSaysSigningOutCancelsIt() {
        var record = HeldDeletionRecord()
        record.apply(.filed(filedOnThisDevice))
        XCTAssertTrue(record.filedHere)
        let notice = record.signOutNotice(listAnswered: true)
        XCTAssertEqual(notice, .filedHere(filedOnThisDevice))
        XCTAssertTrue(notice.offersCancelDeletion)
        XCTAssertEqual(notice.cancelsBeforeSignOut, "app1")
    }

    func testAListedOrAlreadyPendingHoldMayNotBeCancelledBySigningOut() {
        var listed = HeldDeletionRecord()
        listed.listed(filedElsewhere)
        var conflict = HeldDeletionRecord()
        conflict.apply(.alreadyPending(filedElsewhere))
        for record in [listed, conflict] {
            XCTAssertFalse(record.filedHere)
            let notice = record.signOutNotice(listAnswered: true)
            XCTAssertEqual(notice, .notFiledHere(filedElsewhere))
            XCTAssertTrue(notice.offersCancelDeletion, "Cancel deletion is offered")
            XCTAssertEqual(notice.cancellableId, "web1")
            XCTAssertNil(notice.cancelsBeforeSignOut, "a hold from elsewhere is not cancelled on the way out")
        }
    }

    func testFiledHereSurvivesAReadOfTheSameHoldAndIsDroppedWhenTheIdChanges() {
        var record = HeldDeletionRecord()
        record.apply(.filed(filedOnThisDevice))
        record.listed(filedOnThisDevice)
        XCTAssertTrue(record.filedHere, "the same id: still this session's")
        record.apply(.alreadyPending(filedOnThisDevice))
        XCTAssertTrue(record.filedHere, "a 409 for the same hold keeps it")

        record.listed(filedElsewhere)
        XCTAssertFalse(record.filedHere)
        XCTAssertNil(record.filedHereId)
        record.listed(filedOnThisDevice)
        XCTAssertFalse(record.filedHere, "gone for good, even if the old id comes back")

        var empty = HeldDeletionRecord()
        empty.apply(.filed(filedOnThisDevice))
        empty.listed(nil)
        XCTAssertEqual(empty, HeldDeletionRecord())
        XCTAssertEqual(empty.signOutNotice(listAnswered: true), .nothingWaiting)
    }

    func testACancelClearsOnlyThatHoldAndTheSessionEndClearsAll() {
        var record = HeldDeletionRecord()
        record.apply(.filed(filedOnThisDevice))
        record.cancelled(id: "web1")
        XCTAssertEqual(record.pending, filedOnThisDevice, "another id's cancel leaves this hold")
        record.cancelled(id: "app1")
        XCTAssertEqual(record, HeldDeletionRecord())

        var ended = HeldDeletionRecord()
        ended.apply(.filed(filedOnThisDevice))
        ended.sessionEnded()
        XCTAssertEqual(ended, HeldDeletionRecord())
        ended.listed(filedOnThisDevice)
        XCTAssertFalse(ended.filedHere, "the next session lists it as not its own")
    }

    /// F1: a cold start that cannot read the list still knows the id it filed.
    func testAColdStartWithoutAListAnswerStillKnowsTheHoldItFiled() {
        let restored = HeldDeletionRecord(restoredFiledHereId: "app1")
        let notice = restored.signOutNotice(listAnswered: false)
        XCTAssertEqual(notice, .filedHereUnchecked(id: "app1"))
        XCTAssertEqual(notice.cancelsBeforeSignOut, "app1")
        XCTAssertEqual(notice.cancellableId, "app1")
        XCTAssertNil(HeldDeletionRecord(restoredFiledHereId: "  ").filedHereId)
        XCTAssertEqual(HeldDeletionRecord().signOutNotice(listAnswered: false), .unchecked)
    }

    func testTheReadOrderDropsOlderAnswers() {
        var order = HeldDeletionReadOrder()
        let first = order.begin()
        let second = order.begin()
        let newer = order.admit(second)
        let older = order.admit(first)
        XCTAssertEqual(newer, .apply)
        XCTAssertEqual(older, .superseded, "older than the answer applied")
        let third = order.begin()
        order.wrote()
        let beforeWrite = order.admit(third)
        XCTAssertEqual(beforeWrite, .superseded, "started before a write")
        let fourth = order.begin()
        let afterWrite = order.admit(fourth)
        XCTAssertEqual(afterWrite, .apply)
    }

    // MARK: - The controller: keychain copy, cold start, sign-in

    @MainActor
    func testTheKeychainCopyFollowsTheIdThisSessionFiled() async {
        let h = Harness()
        h.controller.show(.filed(filedOnThisDevice))
        XCTAssertEqual(h.store.heldDeletionFiledHereId, "app1")

        h.server.held = filedOnThisDevice
        let read1 = await h.controller.read()
        XCTAssertTrue(read1)
        XCTAssertEqual(h.store.heldDeletionFiledHereId, "app1", "the same hold listed: kept")
        XCTAssertTrue(h.controller.record.filedHere)

        h.server.held = filedElsewhere
        let read2 = await h.controller.read()
        XCTAssertTrue(read2)
        XCTAssertNil(h.store.heldDeletionFiledHereId, "another hold listed: dropped")

        h.controller.show(.filed(filedOnThisDevice))
        h.controller.endSession()
        XCTAssertNil(h.store.heldDeletionFiledHereId, "the session's end drops it")
        XCTAssertEqual(h.controller.record, HeldDeletionRecord())

        // A 409 never writes it.
        let conflict = Harness()
        conflict.controller.show(.alreadyPending(filedElsewhere))
        XCTAssertTrue(conflict.store.writes.isEmpty)
        XCTAssertEqual(conflict.controller.record.signOutNotice(listAnswered: true), .notFiledHere(filedElsewhere))
    }

    @MainActor
    func testAColdStartRestoresTheIdAndKnowsItsHoldOnceListed() async {
        let h = Harness(stored: "app1")
        h.controller.restore()
        XCTAssertEqual(h.controller.record.filedHereId, "app1")
        XCTAssertNil(h.controller.record.pending)
        XCTAssertTrue(h.store.writes.isEmpty, "restoring reads the store, writes nothing")
        h.server.held = filedOnThisDevice
        let read3 = await h.controller.read()
        XCTAssertTrue(read3)
        XCTAssertTrue(h.controller.record.filedHere)
        XCTAssertEqual(h.controller.record.signOutNotice(listAnswered: true), .filedHere(filedOnThisDevice))

        let other = Harness(stored: "app1")
        other.controller.restore()
        other.server.held = filedElsewhere
        let read4 = await other.controller.read()
        XCTAssertTrue(read4)
        XCTAssertFalse(other.controller.record.filedHere)
        XCTAssertNil(other.store.heldDeletionFiledHereId)
    }

    @MainActor
    func testASignInStartsWithNothingFiled() async {
        // Whatever an earlier session left in the keychain is gone.
        let leftover = Harness(stored: "old1")
        leftover.controller.startSession()
        XCTAssertNil(leftover.store.heldDeletionFiledHereId)
        XCTAssertEqual(leftover.controller.record, HeldDeletionRecord())

        let h = Harness()
        h.controller.show(.filed(filedOnThisDevice))
        h.controller.startSession()
        XCTAssertNil(h.store.heldDeletionFiledHereId)
        h.server.held = filedOnThisDevice
        let read5 = await h.controller.read()
        XCTAssertTrue(read5)
        XCTAssertEqual(h.controller.record.signOutNotice(listAnswered: true), .notFiledHere(filedOnThisDevice),
                       "a new session did not file it")
    }

    // MARK: - Signing out

    /// The click-before-read case: Sign out right after a cold start, before
    /// the list read answered. Deciding at that moment saw no hold and gave no
    /// warning; the decision waits for the read, and warns.
    @MainActor
    func testASignOutTapBeforeTheListReadWaitsForItAndWarns() async {
        let h = Harness()
        h.server.held = filedElsewhere
        h.server.listDelay = .milliseconds(150)
        XCTAssertEqual(h.controller.record.signOutNotice(listAnswered: true), .nothingWaiting,
                       "what the tap would have decided without waiting")

        let answered = await h.check()

        XCTAssertTrue(answered)
        XCTAssertEqual(h.server.reads, 1)
        XCTAssertEqual(h.controller.record.signOutNotice(listAnswered: answered), .notFiledHere(filedElsewhere))
    }

    @MainActor
    func testAKnownHoldDecidesWithoutARead() async {
        let h = Harness()
        h.controller.show(.filed(filedOnThisDevice))
        let answered1 = await h.check()
        XCTAssertTrue(answered1)
        XCTAssertEqual(h.server.reads, 0)
    }

    @MainActor
    func testAFailedReadGivesTheCommonCautionAndStillSignsOut() async throws {
        let h = Harness()
        h.server.listFails = true
        let answered = await h.check()
        XCTAssertFalse(answered)
        let notice = h.controller.record.signOutNotice(listAnswered: answered)
        XCTAssertEqual(notice, .unchecked)
        XCTAssertEqual(try line(notice), unchecked)
        XCTAssertFalse(notice.offersCancelDeletion)
        let step1 = await h.prepareSignOut(notice)
        XCTAssertEqual(step1, .signOut(cancelled: nil))
        XCTAssertTrue(h.server.cancels.isEmpty)
    }

    @MainActor
    func testTheWaitForTheReadIsCapped() async {
        XCTAssertEqual(SignOutHold.readWait, .seconds(3))
        let h = Harness()
        h.server.held = filedElsewhere
        h.server.listDelay = .seconds(4)
        let clock = ContinuousClock()
        let start = clock.now
        let answered = await h.check(wait: .milliseconds(100))
        XCTAssertLessThan(start.duration(to: clock.now), .seconds(2))
        XCTAssertFalse(answered)
        XCTAssertEqual(h.controller.record.signOutNotice(listAnswered: answered), .unchecked)
    }

    /// Sign out with a hold filed here: the app cancels it FIRST, through the
    /// real cancel path, then reads the list, then signs out.
    @MainActor
    func testSigningOutCancelsAHoldFiledHereFirst() async {
        let h = Harness()
        h.server.held = filedOnThisDevice
        h.controller.show(.filed(filedOnThisDevice))
        let step = await h.prepareSignOut(h.controller.record.signOutNotice(listAnswered: true))
        XCTAssertEqual(step, .signOut(cancelled: .cancelled))
        XCTAssertEqual(h.server.cancels, ["app1"])
        XCTAssertEqual(h.server.reads, 1, "the list is read after the cancel")
        XCTAssertNil(h.controller.record.pending)
        XCTAssertNil(h.store.heldDeletionFiledHereId)
        XCTAssertFalse(h.controller.isCancelling)

        // Gone already (404) is the server's word too.
        let gone = Harness()
        gone.controller.show(.filed(filedOnThisDevice))
        gone.server.cancelSaysGone = true
        let step2 = await gone.prepareSignOut(.filedHere(filedOnThisDevice))
        XCTAssertEqual(step2, .signOut(cancelled: .alreadyGone))
    }

    /// F1: cold start, offline. The record knows the id it filed but not the
    /// hold: Sign out cancels that id first, and a failed cancel signs nobody out.
    @MainActor
    func testAColdStartOfflineCancelsTheHoldItFiledBeforeSigningOut() async throws {
        let h = Harness(stored: "app1")
        h.controller.restore()
        h.server.held = filedOnThisDevice
        h.server.listFails = true
        h.server.cancelError = URLError(.notConnectedToInternet)

        let answered = await h.check()
        XCTAssertFalse(answered)
        let notice = h.controller.record.signOutNotice(listAnswered: answered)
        XCTAssertEqual(notice, .filedHereUnchecked(id: "app1"))

        let step = await h.prepareSignOut(notice, wait: .milliseconds(200))
        XCTAssertEqual(h.server.cancels, ["app1"], "the cancel is tried first")
        XCTAssertEqual(step, .blocked(SignOutHold.Blocked(reason: HeldDeletionController.unreachableMessage,
                                                          applyAfter: nil)))
        XCTAssertEqual(h.store.heldDeletionFiledHereId, "app1", "still filed here: nothing was cancelled")

        guard case .blocked(let blocked) = step else { return XCTFail("not blocked") }
        let text = SignOutHold.dialogText(opening: "Are you sure you want to sign out?", signOutLabel: "Sign out",
                                          notice: notice, checking: false, blocked: blocked,
                                          timeZone: try utc(), locale: gb)
        XCTAssertTrue(text.message.contains("You are still signed in, and the deletion you asked for on this device may still be scheduled."),
                      text.message)
        XCTAssertTrue(text.offersSignOutAnyway)

        // Back online: the same Sign out cancels it and goes on.
        h.server.listFails = false
        h.server.cancelError = nil
        let step3 = await h.prepareSignOut(notice)
        XCTAssertEqual(step3, .signOut(cancelled: .cancelled))
        XCTAssertEqual(h.server.cancels, ["app1", "app1"])
        XCTAssertNil(h.store.heldDeletionFiledHereId)
    }

    /// A failed cancel of a dated hold: nobody is signed out, and the text
    /// says the deletion is still scheduled, with its date.
    @MainActor
    func testAFailedCancelBeforeSignOutSignsNobodyOut() async throws {
        let h = Harness()
        h.server.held = filedOnThisDevice
        h.controller.show(.filed(filedOnThisDevice))
        h.server.cancelError = URLError(.timedOut)
        let notice = h.controller.record.signOutNotice(listAnswered: true)
        let step = await h.prepareSignOut(notice)
        let blocked = SignOutHold.Blocked(reason: HeldDeletionController.unreachableMessage,
                                          applyAfter: Self.applyAfterDate)
        XCTAssertEqual(step, .blocked(blocked))
        XCTAssertEqual(h.controller.record.pending, filedOnThisDevice)
        XCTAssertFalse(h.controller.isCancelling)
        XCTAssertEqual(DeletionHold.signOutCancelFailed(reason: blocked.reason, applyAfter: blocked.applyAfter,
                                                        timeZone: try utc(), locale: gb),
                       HeldDeletionController.unreachableMessage
                           + " You are still signed in, and your account is still scheduled to be deleted on "
                           + (try when()) + ". Try again, or sign out anyway: the deletion may then still go ahead.")

        // A 401 on the cancel: the session ended on its own.
        h.server.cancelError = FakeUnauthorized()
        let step4 = await h.prepareSignOut(notice)
        XCTAssertEqual(step4, .sessionEnded)
        XCTAssertEqual(h.sessionsEnded, 1)
    }

    /// F4: after the cancel, a read that names another hold stops the
    /// sign-out; after a 404, so does a read that did not answer (after a 200
    /// it does not: R1 below).
    @MainActor
    func testAfterTheCancelAListedHoldOrAnUnreadListAfterA404StopsTheSignOut() async {
        let another = Harness()
        another.server.held = filedOnThisDevice
        another.server.heldAfterCancel = filedElsewhere
        another.controller.show(.filed(filedOnThisDevice))
        let step5 = await another.prepareSignOut(.filedHere(filedOnThisDevice))
        XCTAssertEqual(step5, .reconsider(listAnswered: true, cancelled: .cancelled))
        XCTAssertEqual(another.controller.record.signOutNotice(listAnswered: true), .notFiledHere(filedElsewhere))

        // A 404 says only that THAT id is gone: with the re-read failed,
        // nothing says no other hold waits, so nobody is signed out yet.
        let goneUnread = Harness()
        goneUnread.server.held = filedOnThisDevice
        goneUnread.server.cancelSaysGone = true
        goneUnread.server.listFailsAfterCancel = true
        goneUnread.controller.show(.filed(filedOnThisDevice))
        let step6 = await goneUnread.prepareSignOut(.filedHere(filedOnThisDevice))
        XCTAssertEqual(step6, .reconsider(listAnswered: false, cancelled: .alreadyGone))
        XCTAssertEqual(goneUnread.controller.record.signOutNotice(listAnswered: false), .unchecked)
    }

    /// MR-2389 R1 (Android #623 `afterCancelFirst`): after a 200 the hold this
    /// session filed IS cancelled, so a re-read that failed is no reason to
    /// stop, to ask for a second tap or to show the generic caution: the
    /// sign-out goes on, saying "The deletion is cancelled.".
    @MainActor
    func testR1ASuccessfulCancelWithAFailedReReadStillSignsOut() async throws {
        let unread = Harness()
        unread.server.held = filedOnThisDevice
        unread.server.listFailsAfterCancel = true
        unread.controller.show(.filed(filedOnThisDevice))
        let step = await unread.prepareSignOut(.filedHere(filedOnThisDevice))
        XCTAssertEqual(step, .signOut(cancelled: .cancelled))
        XCTAssertEqual(unread.server.cancels, ["app1"])
        XCTAssertEqual(unread.server.reads, 1, "the re-read was tried")
        // What the dialog shows while the sign-out runs: no caution, the note.
        XCTAssertEqual(unread.controller.record.signOutNotice(listAnswered: true), .nothingWaiting)
        XCTAssertNil(try line(unread.controller.record.signOutNotice(listAnswered: true)))
        XCTAssertEqual(SignOutHold.cancelNote(.cancelled), "The deletion is cancelled.")

        // The cold start that could not read the list: the same.
        let cold = Harness(stored: "app1")
        cold.controller.restore()
        cold.server.listFails = true
        let step2 = await cold.prepareSignOut(.filedHereUnchecked(id: "app1"), wait: .milliseconds(200))
        XCTAssertEqual(cold.server.cancels, ["app1"])
        XCTAssertEqual(step2, .signOut(cancelled: .cancelled))

        // "Cancel deletion" in the dialog: the same rule for what it says next.
        let dialog = Harness()
        dialog.server.held = filedElsewhere
        dialog.server.listFailsAfterCancel = true
        dialog.controller.show(.alreadyPending(filedElsewhere))
        let result = await SignOutHold.cancelThenRecheck("web1",
                                                         cancel: { await dialog.controller.cancel(id: $0) },
                                                         read: { await dialog.controller.read() },
                                                         wait: .milliseconds(200))
        XCTAssertEqual(result, SignOutHold.CancelResult(outcome: .cancelled, listAnswered: false))
        XCTAssertTrue(result.listKnown, "a 200 is the server's word: no caution after it")
        XCTAssertEqual(dialog.controller.record.signOutNotice(listAnswered: result.listKnown), .nothingWaiting)
    }

    /// The rule behind R1, whole: after a 200 nothing is known to wait unless
    /// a read names a hold; after a 404 only an answered read says so.
    func testWhatIsKnownAfterACancel() {
        typealias R = SignOutHold.CancelResult
        XCTAssertTrue(R(outcome: .cancelled, listAnswered: true).listKnown)
        XCTAssertTrue(R(outcome: .cancelled, listAnswered: false).listKnown)
        XCTAssertTrue(R(outcome: .alreadyGone, listAnswered: true).listKnown)
        XCTAssertFalse(R(outcome: .alreadyGone, listAnswered: false).listKnown)
        XCTAssertFalse(R(outcome: .failed("x"), listAnswered: false).listKnown)
        XCTAssertFalse(R(outcome: .sessionEnded, listAnswered: false).listKnown)

        XCTAssertEqual(SignOutHold.cancelNote(.cancelled), "The deletion is cancelled.")
        XCTAssertEqual(SignOutHold.cancelNote(.alreadyGone), "That deletion was no longer waiting.")
        XCTAssertNil(SignOutHold.cancelNote(.failed("x")))
        XCTAssertNil(SignOutHold.cancelNote(.sessionEnded))
    }

    /// F5: the read after the cancel is capped too. Past it, a 200 still
    /// signs out (R1); a 404 does not.
    @MainActor
    func testTheReadAfterTheCancelIsCapped() async {
        let h = Harness()
        h.server.held = filedOnThisDevice
        h.server.listDelayAfterCancel = .seconds(4)
        h.controller.show(.filed(filedOnThisDevice))
        let clock = ContinuousClock()
        let start = clock.now
        let step = await h.prepareSignOut(.filedHere(filedOnThisDevice), wait: .milliseconds(100))
        XCTAssertLessThan(start.duration(to: clock.now), .seconds(2))
        XCTAssertEqual(step, .signOut(cancelled: .cancelled))

        let gone = Harness()
        gone.server.held = filedOnThisDevice
        gone.server.cancelSaysGone = true
        gone.server.listDelayAfterCancel = .seconds(4)
        gone.controller.show(.filed(filedOnThisDevice))
        let start2 = clock.now
        let step2 = await gone.prepareSignOut(.filedHere(filedOnThisDevice), wait: .milliseconds(100))
        XCTAssertLessThan(start2.duration(to: clock.now), .seconds(2))
        XCTAssertEqual(step2, .reconsider(listAnswered: false, cancelled: .alreadyGone))
    }

    /// MR-2389 R2 (Android #623 `HOLD_CANCEL_TIMEOUT_MS`): one cancel is
    /// capped. Past the cap it FAILED, as unreachable: nothing local says the
    /// hold is gone, the sign-out waiting on it stops (Try again / Sign out
    /// anyway), and the dialog is busy no longer than the cap.
    @MainActor
    func testR2TheCancelIsCapped() async {
        XCTAssertEqual(HeldDeletionController.cancelCap, .seconds(10))
        let h = Harness(cancelTimeout: .milliseconds(100))
        h.server.held = filedOnThisDevice
        h.server.cancelDelay = .seconds(5)
        h.controller.show(.filed(filedOnThisDevice))
        let clock = ContinuousClock()
        let start = clock.now
        let step = await h.prepareSignOut(.filedHere(filedOnThisDevice))
        XCTAssertLessThan(start.duration(to: clock.now), .seconds(3), "bounded by the cap, not by the request")
        XCTAssertEqual(step, .blocked(SignOutHold.Blocked(reason: HeldDeletionController.unreachableMessage,
                                                          applyAfter: Self.applyAfterDate)))
        XCTAssertEqual(h.controller.record.pending, filedOnThisDevice, "not said to be cancelled")
        XCTAssertEqual(h.store.heldDeletionFiledHereId, "app1")
        XCTAssertFalse(h.controller.isCancelling)

        // The banner's cancel is the same call, capped the same way.
        let banner = Harness(cancelTimeout: .milliseconds(100))
        banner.server.cancelDelay = .seconds(5)
        banner.controller.show(.alreadyPending(filedElsewhere))
        let outcome = await banner.controller.cancel(id: "web1")
        XCTAssertEqual(outcome, .failed(HeldDeletionController.unreachableMessage))
        XCTAssertEqual(banner.controller.record.pending, filedElsewhere)
    }

    /// The cap itself: a fast answer passes through, a slow one is cut off
    /// at the cap and its task is cancelled.
    @MainActor
    func testCappedCallAnswersOrTimesOut() async throws {
        let fast = try await CappedCall.run(within: .seconds(5)) { 42 }
        XCTAssertEqual(fast, 42)

        let clock = ContinuousClock()
        let start = clock.now
        let probe = CancellationProbe()
        do {
            _ = try await CappedCall.run(within: .milliseconds(100)) { () async throws -> Int in
                try? await Task.sleep(for: .seconds(5))
                probe.sawCancel = Task.isCancelled
                return 1
            }
            XCTFail("a slow call must time out")
        } catch is CappedCall.TimedOut {
            XCTAssertLessThan(start.duration(to: clock.now), .seconds(3))
        }
        // The slow call's task was cancelled, so its sleep ended early.
        for _ in 0..<100 where !probe.sawCancel { try await Task.sleep(for: .milliseconds(20)) }
        XCTAssertTrue(probe.sawCancel)

        do {
            _ = try await CappedCall.run(within: .seconds(5)) { () async throws -> Int in throw URLError(.timedOut) }
            XCTFail("the call's own error comes through")
        } catch let error as URLError {
            XCTAssertEqual(error.code, .timedOut)
        }
    }

    /// MR-2389 R3 (Android #623 `applyRead`): only a cancel that SUCCEEDS
    /// makes the reads started before it stale. A read that started before a
    /// cancel that failed applies, whether it answers during the cancel or
    /// after it.
    @MainActor
    func testR3AReadStartedBeforeAFailedCancelStillApplies() async {
        // It answers while the cancel is out.
        let during = Harness()
        during.server.scripted = [(answer: filedElsewhere, delay: .milliseconds(100))]
        during.server.cancelDelay = .milliseconds(400)
        during.server.cancelError = URLError(.notConnectedToInternet)
        let read1 = Task { await during.controller.read() }
        while during.server.reads == 0 { await Task.yield() }
        let outcome1 = await during.controller.cancel(id: "web1")
        let answered1 = await read1.value
        XCTAssertEqual(outcome1, .failed(HeldDeletionController.unreachableMessage))
        XCTAssertTrue(answered1)
        XCTAssertEqual(during.controller.record.pending, filedElsewhere)

        // It answers after the cancel failed.
        let after = Harness()
        after.server.scripted = [(answer: filedElsewhere, delay: .milliseconds(400))]
        after.server.cancelError = URLError(.notConnectedToInternet)
        let read2 = Task { await after.controller.read() }
        while after.server.reads == 0 { await Task.yield() }
        let outcome2 = await after.controller.cancel(id: "web1")
        XCTAssertEqual(outcome2, .failed(HeldDeletionController.unreachableMessage))
        let answered2 = await read2.value
        XCTAssertTrue(answered2)
        XCTAssertEqual(after.controller.record.pending, filedElsewhere, "the failed cancel made it stale before R3")
    }

    /// The other half of R3: a read that started before a cancel that
    /// SUCCEEDED, and answers after it, is stale and never brings the hold back.
    @MainActor
    func testR3AReadStartedBeforeASuccessfulCancelIsDropped() async {
        let h = Harness()
        h.controller.show(.filed(filedOnThisDevice))
        h.server.scripted = [(answer: filedOnThisDevice, delay: .milliseconds(400))]
        let read = Task { await h.controller.read() }
        while h.server.reads == 0 { await Task.yield() }
        let outcome = await h.controller.cancel(id: "app1")
        XCTAssertEqual(outcome, .cancelled)
        let answered = await read.value
        XCTAssertTrue(answered, "what is known is fresher than that answer")
        XCTAssertNil(h.controller.record.pending, "the cancelled hold does not come back")
        XCTAssertNil(h.store.heldDeletionFiledHereId)
    }

    /// Review of #632 (L2, Android #623 `cancelledHoldIds`): a hold this
    /// session cancelled (200 or 404) never comes back, whether a lagging
    /// list names it or a late hold-request answer does.
    @MainActor
    func testACancelledHoldIsNeverShownAgain() async {
        let h = Harness()
        h.server.held = filedOnThisDevice
        h.server.heldAfterCancel = filedOnThisDevice          // the list lags behind the cancel
        h.controller.show(.filed(filedOnThisDevice))
        let outcome = await h.controller.cancel(id: "app1")
        XCTAssertEqual(outcome, .cancelled)
        XCTAssertEqual(h.controller.cancelledIds, ["app1"])
        let answered = await h.controller.read()
        XCTAssertTrue(answered)
        XCTAssertNil(h.controller.record.pending, "a lagging list does not bring the cancelled hold back")
        XCTAssertNil(h.store.heldDeletionFiledHereId)
        XCTAssertEqual(h.controller.record.signOutNotice(listAnswered: true), .nothingWaiting)

        // A late 202 for the very hold: refused, nothing claimed, nothing stored.
        let writes = h.store.writes.count
        XCTAssertFalse(h.controller.show(.filed(filedOnThisDevice)))
        XCTAssertNil(h.controller.record.pending)
        XCTAssertEqual(h.store.writes.count, writes)
        // Another hold is still a hold.
        XCTAssertTrue(h.controller.show(.alreadyPending(filedElsewhere)))
        XCTAssertEqual(h.controller.record.pending, filedElsewhere)

        // A 404 is the server's word too.
        let gone = Harness()
        gone.server.held = filedOnThisDevice
        gone.server.heldAfterCancel = filedOnThisDevice
        gone.server.cancelSaysGone = true
        gone.controller.show(.filed(filedOnThisDevice))
        let step = await gone.prepareSignOut(.filedHere(filedOnThisDevice))
        XCTAssertEqual(step, .signOut(cancelled: .alreadyGone), "the lagging list names only the cancelled id")
        XCTAssertEqual(gone.controller.cancelledIds, ["app1"])

        // A failed cancel tombstones nothing.
        let failed = Harness()
        failed.server.cancelError = URLError(.notConnectedToInternet)
        failed.controller.show(.filed(filedOnThisDevice))
        _ = await failed.controller.cancel(id: "app1")
        XCTAssertTrue(failed.controller.cancelledIds.isEmpty)

        // The tombstones belong to the session.
        h.controller.endSession()
        XCTAssertTrue(h.controller.cancelledIds.isEmpty)
        let next = Harness()
        _ = await next.controller.cancel(id: "app1")
        next.controller.startSession()
        XCTAssertTrue(next.controller.cancelledIds.isEmpty)
    }

    /// Review of #632 (L2): the AuthViewModel claims nothing for a refused answer.
    func testAuthViewModelClaimsNothingForACancelledHold() throws {
        let auth = try source("iosApp/ViewModels/AuthViewModel.swift")
        let show = member("private func showDeletionHold(", in: auth)
        XCTAssertTrue(show.contains("guard heldDeletions.show(filedHere ? .filed(pending) : .alreadyPending(pending)) else {"))
        let guardEnd = try XCTUnwrap(show.range(of: "refreshPendingDeletion()\n            return\n        }"))
        let claim = try XCTUnwrap(show.range(of: "deletionHoldFiled = true"))
        XCTAssertLessThan(guardEnd.lowerBound, claim.lowerBound, "deletionHoldFiled must come after the refusal")
    }

    /// MR-2389 nit: the hold step's "signing out cancels the deletion" needs a
    /// list read that answered in THIS session.
    @MainActor
    func testTheListAnsweredFlagFollowsTheSession() async {
        let h = Harness()
        XCTAssertFalse(h.controller.listAnswered)
        h.server.listFails = true
        let failed = await h.controller.read()
        XCTAssertFalse(failed)
        XCTAssertFalse(h.controller.listAnswered, "a failed read answered nothing")
        h.server.listFails = false
        let answered = await h.controller.read()
        XCTAssertTrue(answered)
        XCTAssertTrue(h.controller.listAnswered)
        h.controller.endSession()
        XCTAssertFalse(h.controller.listAnswered)
        _ = await h.controller.read()
        h.controller.startSession()
        XCTAssertFalse(h.controller.listAnswered, "a new session starts unanswered")
        _ = await h.controller.read()
        h.controller.restore()
        XCTAssertFalse(h.controller.listAnswered, "a cold start starts unanswered")
    }

    /// Signing out leaves a hold from elsewhere alone.
    @MainActor
    func testSigningOutLeavesAHoldFromElsewhereAlone() async {
        let h = Harness()
        h.server.held = filedElsewhere
        let read6 = await h.controller.read()
        XCTAssertTrue(read6)
        let step7 = await h.prepareSignOut(h.controller.record.signOutNotice(listAnswered: true))
        XCTAssertEqual(step7, .signOut(cancelled: nil))
        XCTAssertTrue(h.server.cancels.isEmpty)
    }

    /// F6: two cancels of the same hold at once send one request; the second
    /// waits for the first and gets its outcome.
    @MainActor
    func testTwoCancelsOfTheSameHoldSendOneRequest() async {
        let h = Harness()
        h.server.held = filedOnThisDevice
        h.server.cancelDelay = .milliseconds(150)
        h.controller.show(.filed(filedOnThisDevice))
        let first = Task { await h.controller.cancel(id: "app1") }
        let second = Task { await h.controller.cancel(id: "app1") }
        let outcomes = [await first.value, await second.value]
        XCTAssertEqual(outcomes, [.cancelled, .cancelled])
        XCTAssertEqual(h.server.cancels, ["app1"])
        XCTAssertFalse(h.controller.isCancelling)
    }

    /// F7: an older list answer never overwrites a newer one, nor a filing
    /// made while it was out.
    @MainActor
    func testAnOlderListAnswerNeverOverwritesANewerOne() async {
        let h = Harness()
        h.server.scripted = [(answer: filedElsewhere, delay: .milliseconds(300)),
                             (answer: nil, delay: .milliseconds(20))]
        let older = Task { await h.controller.read() }
        let newer = Task { await h.controller.read() }
        let answers = [await older.value, await newer.value]
        XCTAssertEqual(answers, [true, true])
        XCTAssertEqual(h.server.reads, 2)
        XCTAssertNil(h.controller.record.pending, "the newer answer (nothing waits) stands")

        let filing = Harness()
        filing.server.scripted = [(answer: nil, delay: .milliseconds(200))]
        let read = Task { await filing.controller.read() }
        while filing.server.reads == 0 { await Task.yield() }
        filing.controller.show(.filed(filedOnThisDevice))
        _ = await read.value
        XCTAssertEqual(filing.controller.record.pending, filedOnThisDevice, "the filing made meanwhile stands")
    }

    // MARK: - The dialog's text

    /// F2: once the cancel-first failed, its sentence REPLACES the hold's line.
    func testTheBlockedTextReplacesTheHoldsLine() throws {
        let opening = "Are you sure you want to sign out?"
        let notice = SignOutHoldNotice.filedHere(filedOnThisDevice)
        let normal = SignOutHold.dialogText(opening: opening, signOutLabel: "Sign out", notice: notice,
                                            checking: false, blocked: nil, timeZone: try utc(), locale: gb)
        XCTAssertEqual(normal.message, opening + "\n\n" + (try XCTUnwrap(try line(notice))))
        XCTAssertEqual(normal.confirmLabel, "Sign out")
        XCTAssertTrue(normal.offersCancelDeletion)
        XCTAssertFalse(normal.offersSignOutAnyway)

        let blocked = SignOutHold.Blocked(reason: "Unable to reach server. Check your connection.",
                                          applyAfter: Self.applyAfterDate)
        let refused = SignOutHold.dialogText(opening: opening, signOutLabel: "Sign out", notice: notice,
                                             checking: false, blocked: blocked, timeZone: try utc(), locale: gb)
        XCTAssertEqual(refused.message,
                       opening + "\n\n" + DeletionHold.signOutCancelFailed(reason: blocked.reason,
                                                                           applyAfter: blocked.applyAfter,
                                                                           timeZone: try utc(), locale: gb))
        XCTAssertFalse(refused.message.contains("Signing out cancels"), refused.message)
        XCTAssertEqual(refused.confirmLabel, "Try again")
        XCTAssertTrue(refused.offersSignOutAnyway)
        XCTAssertFalse(refused.offersCancelDeletion)

        let checking = SignOutHold.dialogText(opening: opening, signOutLabel: "Sign out", notice: .unchecked,
                                              checking: true, blocked: nil)
        XCTAssertEqual(checking.message, opening)
        XCTAssertEqual(checking.confirmLabel, "Checking\u{2026}")
    }

    /// F9 and the claims: "Stay signed in" in every state, and no text claims
    /// the account is safe. Every text the dialog can show is generated here.
    func testEveryDialogStateSaysStaySignedInAndClaimsNothing() throws {
        let notices: [SignOutHoldNotice] = [.nothingWaiting, .filedHere(filedOnThisDevice),
                                            .notFiledHere(filedElsewhere), .filedHereUnchecked(id: "app1"), .unchecked]
        let blocks: [SignOutHold.Blocked?] = [nil,
                                              SignOutHold.Blocked(reason: "x.", applyAfter: Self.applyAfterDate),
                                              SignOutHold.Blocked(reason: "x.", applyAfter: nil)]
        var messages: [String] = []
        for notice in notices {
            for blocked in blocks {
                for checking in [false, true] {
                    let text = SignOutHold.dialogText(opening: "O.", signOutLabel: "Sign out", notice: notice,
                                                      checking: checking, blocked: blocked,
                                                      timeZone: try utc(), locale: gb)
                    XCTAssertEqual(text.dismissLabel, "Stay signed in")
                    messages.append(text.message)
                }
            }
        }
        XCTAssertEqual(messages.count, 30)
        XCTAssertTrue(messages.contains { $0.contains("Signing out cancels the deletion") })
        XCTAssertTrue(messages.contains { $0.contains(keeps) })
        XCTAssertTrue(messages.contains { $0.contains(unchecked) })
        XCTAssertTrue(messages.contains { $0.contains("still scheduled to be deleted on") })
        for message in messages {
            let lower = message.lowercased()
            XCTAssertFalse(lower.contains("will not be deleted") || lower.contains("won't be deleted")
                               || lower.contains("is safe"), message)
        }
    }

    // MARK: - Where the app calls it (source pins)

    private func source(_ path: String) throws -> String {
        // .../iosApp/BirdoVPNTests/HeldDeletionOriginTests.swift -> .../iosApp
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let url = root.appendingPathComponent(path)
        guard FileManager.default.fileExists(atPath: url.path) else {
            XCTFail("\(path) not found under \(root.path): this pin would be vacuous")
            return ""
        }
        return try String(contentsOf: url, encoding: .utf8).replacingOccurrences(of: "\r\n", with: "\n")
    }

    /// The member starting at `signature`, up to its closing brace (members
    /// are indented four spaces; their bodies further).
    private func member(_ signature: String, in text: String,
                        file: StaticString = #filePath, line: UInt = #line) -> String {
        guard let start = text.range(of: signature) else {
            XCTFail("not found: \(signature)", file: file, line: line)
            return ""
        }
        let rest = text[start.lowerBound...]
        guard let end = rest.range(of: "\n    }\n") else {
            XCTFail("no end for: \(signature)", file: file, line: line)
            return ""
        }
        return String(rest[..<end.upperBound])
    }

    /// The sign-in reset, the sign-out clear and the cold-start restore are
    /// wired where they must be, and the keychain is the store.
    func testAuthViewModelWiresTheSessionLifecycle() throws {
        let auth = try source("iosApp/ViewModels/AuthViewModel.swift")
        let signIn = member("private func completeAuthentication(", in: auth)
        XCTAssertTrue(signIn.contains("heldDeletions.startSession()"), "a sign-in must drop what an earlier session filed")
        let signOut = member("private func completeLocalLogout() {", in: auth)
        XCTAssertTrue(signOut.contains("heldDeletions.endSession()"))
        let launch = member("init(api: APIClient = .shared, keychain: KeychainService = .shared) {", in: auth)
        let live = try XCTUnwrap(launch.range(of: "if sessionLive {"))
        XCTAssertTrue(launch[live.upperBound...].contains("heldDeletions.restore()"),
                      "a cold start with a live session restores the id it filed")
        XCTAssertTrue(auth.contains("store: keychain,"))
        // Only the controller writes the keychain copy.
        XCTAssertFalse(auth.contains("setHeldDeletionFiledHere("))

        let keychain = try source("iosApp/Services/KeychainService.swift")
        XCTAssertTrue(keychain.contains("extension KeychainService: HeldDeletionIdStore {}"))
        XCTAssertTrue(member("func clear() {", in: keychain).contains("delete(.heldDeletionFiledHere)"),
                      "a sign-out's wipe drops the id")
    }

    /// The banner and the hold step take their sentence from the logic above,
    /// never a hard-coded "signing out cancels"; both confirmations are the
    /// shared one.
    func testTheViewsTakeTheirTextFromTheLogic() throws {
        let profile = try source("iosApp/Views/ProfileView.swift")
        let banner = member("private func pendingDeletionBanner(", in: profile)
        XCTAssertTrue(banner.contains("DeletionHold.bannerSignOutNote(filedHere: authVM.heldDeletion.filedHere)"))
        XCTAssertFalse(banner.contains("signOutCancels"))
        let holdStep = member("private var deletionHoldStep: some View {", in: profile)
        XCTAssertTrue(holdStep.contains("DeletionHold.holdStepSignOutNote(holdKnown: authVM.pendingDeletion != nil,"))
        XCTAssertTrue(holdStep.contains("listAnswered: authVM.heldDeletionListAnswered)"))
        XCTAssertFalse(holdStep.contains("Text(DeletionHold.signOutCancels)"))

        let home = try source("iosApp/Views/HomeView.swift")
        for (name, view) in [("ProfileView", profile), ("HomeView", home)] {
            XCTAssertTrue(view.contains("SignOutConfirmation("), name)
            XCTAssertFalse(view.contains("DeletionHold.signOutWarning("), "\(name) words the hold itself")
        }
        let dialog = try source("iosApp/Views/Components/SignOutConfirmation.swift")
        XCTAssertTrue(dialog.contains("SignOutHold.dialogText("))
        XCTAssertTrue(dialog.contains("SignOutHold.prepareSignOut("))
        XCTAssertTrue(dialog.contains("SignOutHold.cancelThenRecheck("))
    }
}

// MARK: - Fakes

private struct FakeUnauthorized: Error {}

/// Whether a capped call's task saw its cancellation.
@MainActor
private final class CancellationProbe {
    var sawCancel = false
}

/// `GET /auth/pending-changes` and the cancel route.
@MainActor
private final class FakePendingChanges {
    /// The hold the server keeps; nil: none.
    var held: PendingDeletion?
    var listFails = false
    var listDelay: Duration = .zero
    /// Answers for the next reads, first in first out, before `held`.
    var scripted: [(answer: PendingDeletion?, delay: Duration)] = []
    var cancelError: Error?
    var cancelDelay: Duration = .zero
    /// The 404: gone already.
    var cancelSaysGone = false
    /// What the server keeps after a successful cancel (another hold, say).
    var heldAfterCancel: PendingDeletion?
    var listFailsAfterCancel = false
    var listDelayAfterCancel: Duration = .zero
    private(set) var reads = 0
    private(set) var cancels: [String] = []

    func list() async throws -> PendingDeletion? {
        reads += 1
        if !scripted.isEmpty {
            let next = scripted.removeFirst()
            if next.delay > .zero { try? await Task.sleep(for: next.delay) }
            return next.answer
        }
        let answer = held
        if listDelay > .zero { try? await Task.sleep(for: listDelay) }
        if listFails { throw URLError(.notConnectedToInternet) }
        return answer
    }

    func cancel(_ id: String) async throws -> Bool {
        cancels.append(id)
        if cancelDelay > .zero { try? await Task.sleep(for: cancelDelay) }
        if let cancelError { throw cancelError }
        held = heldAfterCancel
        if listFailsAfterCancel { listFails = true }
        if listDelayAfterCancel > .zero { listDelay = listDelayAfterCancel }
        return !cancelSaysGone
    }
}

/// The keychain item `held_deletion_filed_here`, in memory.
private final class MemoryHoldStore: HeldDeletionIdStore {
    var heldDeletionFiledHereId: String?
    private(set) var writes: [String?] = []

    init(_ id: String?) {
        heldDeletionFiledHereId = id
    }

    func setHeldDeletionFiledHere(_ id: String?) {
        heldDeletionFiledHereId = id
        writes.append(id)
    }
}

/// A signed-in session: the controller as AuthViewModel builds it, over the fakes.
@MainActor
private final class Harness {
    let server: FakePendingChanges
    let store: MemoryHoldStore
    private(set) var signedIn = true
    private(set) var sessionsEnded = 0
    /// Built in `init`, as AuthViewModel builds its own (no lazy initializer).
    private(set) var controller: HeldDeletionController!

    init(stored: String? = nil, cancelTimeout: Duration = HeldDeletionController.cancelCap) {
        let server = FakePendingChanges()
        self.server = server
        store = MemoryHoldStore(stored)
        controller = HeldDeletionController(
            server: HeldDeletionController.Server(
                list: { try await server.list() },
                cancel: { id in try await server.cancel(id) }),
            store: store,
            endsSession: { [unowned self] error in
                guard error is FakeUnauthorized else { return false }
                self.sessionsEnded += 1
                self.signedIn = false
                return true
            },
            isSignedIn: { [unowned self] in self.signedIn },
            cancelTimeout: cancelTimeout)
    }

    func check(wait: Duration = SignOutHold.readWait) async -> Bool {
        await SignOutHold.check(current: { self.controller.record },
                                read: { await self.controller.read() },
                                wait: wait)
    }

    func prepareSignOut(_ notice: SignOutHoldNotice, wait: Duration = SignOutHold.readWait) async -> SignOutHold.Step {
        await SignOutHold.prepareSignOut(notice,
                                         current: { self.controller.record },
                                         cancel: { await self.controller.cancel(id: $0) },
                                         read: { await self.controller.read() },
                                         wait: wait)
    }
}
