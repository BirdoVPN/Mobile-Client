import XCTest

/// MR-2581 (L2 of the #665 review; I5 of the #653 review): a 401 ends only the
/// session it was the answer for, and a number rotation answered after a
/// sign-out stores nothing.
///
/// Before:
/// - `AuthViewModel.logoutIfUnauthorized` wiped the keychain on any
///   `.unauthorized`, without asking which session's refresh token died. A
///   refresh of the OLD session that got its 401 while a number rotation was
///   committing wiped the NEW pair: the user was signed out right after a
///   rotation that worked.
/// - The rotation's commit was not fenced. A sign-out that landed while the
///   confirm was on the wire let the commit write the new pair into a
///   signed-out app, and the next cold start signed the user back in.
/// - The sheet could be swiped away mid-confirm, which made that sign-out easy.
///
/// Android fences both: it clears only inside the 401 branch, while the stored
/// refresh token is still the one presented, under its session lock.
final class SessionEndFenceTests: XCTestCase {

    private let oldRefresh = "refresh-old"
    private let newRefresh = "refresh-new"
    private let newNumber = "777788889999000011112222"
    private let oldNumber = "111122223333444455556666"

    // MARK: - A 401 ends only the session it answered

    func testA401EndsOnlyTheSessionItAnswered() {
        XCTAssertTrue(DeadRefreshToken.mayEnd(stored: oldRefresh, dead: oldRefresh),
                      "the session that got the 401 is still the stored one")
        XCTAssertFalse(DeadRefreshToken.mayEnd(stored: newRefresh, dead: oldRefresh),
                       "a pair stored after the old session's 401 (a number rotation, a sign-in) was wiped")
        XCTAssertTrue(DeadRefreshToken.mayEnd(stored: nil, dead: oldRefresh), "nothing is stored: nothing to keep")
        XCTAssertTrue(DeadRefreshToken.mayEnd(stored: newRefresh, dead: nil),
                      "no refresh died (a retry's 401 after a refresh that worked): as before")
    }

    func testTheRecordFollowsTheStoredSession() {
        let dead = DeadRefreshToken()
        XCTAssertTrue(dead.mayEndSession(stored: oldRefresh))
        dead.record(oldRefresh)
        XCTAssertTrue(dead.mayEndSession(stored: oldRefresh))
        // The rotation's commit lands between the old session's 401 and the
        // wipe that 401 asks for.
        XCTAssertFalse(dead.mayEndSession(stored: newRefresh))
        // The new session dies in its turn.
        dead.record(newRefresh)
        XCTAssertTrue(dead.mayEndSession(stored: newRefresh))
        // A refresh that worked: nothing on file is known dead.
        dead.record(oldRefresh)
        dead.forget()
        XCTAssertTrue(dead.mayEndSession(stored: newRefresh))
    }

    // MARK: - A rotation answered after a sign-out stores nothing

    private final class RotationStore: RotationCommitStore {
        var access: String?
        var refresh: String?
        var anonymousId: String?
        var failTokens = false
        private(set) var writes = 0

        func saveRotationSession(accessToken: String, refreshToken: String) -> Bool {
            writes += 1
            guard !failTokens else { return false }
            access = accessToken
            refresh = refreshToken
            return true
        }

        func saveAnonymousId(_ id: String) -> Bool {
            writes += 1
            anonymousId = id
            return true
        }
    }

    private func commit(_ store: RotationStore, sentFrom: UInt64, current: UInt64) -> AnonNumberRotationCommit.Outcome {
        AnonNumberRotationCommit.commit(newNumber: newNumber, accessToken: "access-new", refreshToken: newRefresh,
                                        to: store, sentFrom: sentFrom, current: current)
    }

    func testASignOutWhileTheConfirmIsOnTheWireStoresNothing() {
        let store = RotationStore()
        store.anonymousId = oldNumber
        XCTAssertEqual(commit(store, sentFrom: 4, current: 5), .sessionChanged)
        XCTAssertEqual(store.writes, 0, "the new pair was written into a signed-out app")
        XCTAssertNil(store.refresh)
        XCTAssertEqual(store.anonymousId, oldNumber)
    }

    func testTheSameSessionStoresAsBefore() {
        let store = RotationStore()
        XCTAssertEqual(commit(store, sentFrom: 5, current: 5), .stored)
        XCTAssertEqual(store.access, "access-new")
        XCTAssertEqual(store.refresh, newRefresh)
        XCTAssertEqual(store.anonymousId, newNumber)

        let refused = RotationStore()
        refused.failTokens = true
        XCTAssertEqual(commit(refused, sentFrom: 5, current: 5), .notStored)
        XCTAssertNil(refused.anonymousId)
    }

    // MARK: - Where it is wired (source pins: AuthViewModel, APIClient and
    // ReplaceNumberSheet cannot be compiled into this bundle)

    private func source(_ path: String) throws -> String {
        // .../iosApp/BirdoVPNTests/SessionEndFenceTests.swift -> .../iosApp
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let url = root.appendingPathComponent(path)
        guard FileManager.default.fileExists(atPath: url.path) else {
            XCTFail("\(path) not found under \(root.path): this pin would be vacuous")
            return ""
        }
        return try String(contentsOf: url, encoding: .utf8).replacingOccurrences(of: "\r\n", with: "\n")
    }

    /// From `signature` to the closing brace of that member (nested braces are
    /// indented deeper than four spaces).
    private func member(_ signature: String, in text: String) -> Substring? {
        guard let start = text.range(of: signature),
              let end = text.range(of: "\n    }\n", range: start.upperBound..<text.endIndex) else { return nil }
        return text[start.lowerBound..<end.upperBound]
    }

    func testThe401HandlerWipesOnlyTheSessionThatDied() throws {
        let auth = try source("iosApp/ViewModels/AuthViewModel.swift")
        let handler = try XCTUnwrap(member("func logoutIfUnauthorized(_ error: Error) -> Bool {", in: auth))
        guard let fence = handler.range(of: "api.mayEndStoredSession(storedRefreshToken: keychain.refreshToken)"),
              let wipe = handler.range(of: "completeLocalLogout()") else {
            return XCTFail("the 401 handler wipes the keychain without asking which session died")
        }
        XCTAssertLessThan(fence.lowerBound, wipe.lowerBound)

        let api = try source("iosApp/Services/APIClient.swift")
        let refresh = try XCTUnwrap(member("private func refreshTokens() async throws {", in: api))
        guard let dead = refresh.range(of: "case .dead:"),
              let next = refresh.range(of: "case .refused:", range: dead.upperBound..<refresh.endIndex) else {
            return XCTFail("refreshTokens' 401 branch not found")
        }
        let branch = refresh[dead.upperBound..<next.lowerBound]
        XCTAssertTrue(branch.contains("generationAtAnswer == generationAtStart"),
                      "the 401 branch does not check that no sign-out landed meanwhile")
        XCTAssertTrue(branch.contains("keychain.refreshToken == refresh"),
                      "the 401 branch does not check that the token that died is still the stored one")
        guard let record = branch.range(of: "deadRefreshToken.record(refresh)"),
              let thrown = branch.range(of: "throw APIError.unauthorized") else {
            return XCTFail("the 401 branch does not record which token died")
        }
        XCTAssertLessThan(record.lowerBound, thrown.lowerBound)
        guard let save = refresh.range(of: "guard keychain.save("),
              let forget = refresh.range(of: "deadRefreshToken.forget()") else {
            return XCTFail("a refresh that worked does not forget the dead token")
        }
        XCTAssertLessThan(save.lowerBound, forget.lowerBound, "forgotten before the new pair was stored")
    }

    func testARotationAnsweredAfterASignOutStoresNothing() throws {
        let sheet = try source("iosApp/Views/ReplaceNumberSheet.swift")
        let confirm = try XCTUnwrap(member("func confirm(authVM: AuthViewModel) async {", in: sheet))
        guard let captured = confirm.range(of: "let session = authVM.sessionEpoch"),
              let sent = confirm.range(of: "api.rotateNumberConfirm("),
              let committed = confirm.range(of: "sentFrom: session, current: authVM.sessionEpoch)"),
              let revoke = confirm.range(of: "await api.logout(bearerToken: access)") else {
            return XCTFail("the rotation's commit is not fenced by the session the confirm was sent from")
        }
        XCTAssertLessThan(captured.lowerBound, sent.lowerBound, "the session is read after the confirm was sent")
        XCTAssertLessThan(sent.lowerBound, committed.lowerBound)
        XCTAssertLessThan(committed.lowerBound, revoke.lowerBound)
        XCTAssertTrue(confirm.contains("if outcome == .sessionChanged {"))
        XCTAssertTrue(sheet.contains(".interactiveDismissDisabled(model.isBusy)"),
                      "the sheet can be swiped away while the confirm is on the wire")
        XCTAssertTrue(sheet.contains("PrimaryButton(\"Cancel\", variant: .ghost, isEnabled: !model.isBusy)"),
                      "step 3's Cancel closes the sheet while the confirm is on the wire")

        // A sign-out and a sign-in each start a new epoch, before anything else.
        let auth = try source("iosApp/ViewModels/AuthViewModel.swift")
        let signOut = try XCTUnwrap(member("private func completeLocalLogout() {", in: auth))
        guard let bump = signOut.range(of: "sessionEpoch &+= 1"),
              let wipe = signOut.range(of: "LocalSignOut.wipe(keychain)") else {
            return XCTFail("a sign-out does not start a new session epoch")
        }
        XCTAssertLessThan(bump.lowerBound, wipe.lowerBound)
        XCTAssertEqual(auth.components(separatedBy: "sessionEpoch &+= 1").count, 4,
                       "the sign-out, completeAuthentication and acknowledgeAnonymousId each start one")
    }
}
