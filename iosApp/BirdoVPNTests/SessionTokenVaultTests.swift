import XCTest

/// #671 review (MR-2580): what a refresh rotation does when the keychain will
/// not take its pair, and the one lock every pair write takes.
///
/// The server spends the presented refresh token when it answers with the new
/// pair. If the keychain then refuses the new refresh token, the stored one is
/// that spent token: presented again after the server's grace, it reads as
/// theft and every session and WireGuard peer of the account is revoked. So:
/// - the new pair is kept in memory, and this process keeps working on it;
/// - a durable fence marks the stored refresh token spent: nothing presents it
///   again, and a relaunch that finds the fence with no newer pair signs out
///   locally, without a request;
/// - the pair is stored as soon as the keychain takes it, and only a write
///   that stored a new refresh token clears the fence.
/// And one lock (Android's `sessionLock`): a rotation's compare-and-swap and
/// its write are one step, so it never writes over a sign-in, and a sign-in
/// never puts back a refresh token a rotation spent meanwhile.
final class SessionTokenVaultTests: XCTestCase {

    private let a0 = Data("access-0".utf8)
    private let r0 = Data("refresh-0".utf8)
    private let a1 = Data("access-1".utf8)
    private let r1 = Data("refresh-1".utf8)
    private let a2 = Data("access-2".utf8)
    private let r2 = Data("refresh-2".utf8)

    private func signedIn() -> MemoryKeychain {
        MemoryKeychain(access: a0, refresh: r0)
    }

    /// The rotation that presented r0 got (a1, r1), and the keychain refused it.
    private func refusedRotation(_ keychain: MemoryKeychain, _ vault: SessionTokenVault) {
        keychain.fails = { _, _ in true }
        XCTAssertEqual(vault.saveRotation(accessToken: a1, refreshToken: r1, expectingRefreshToken: r0, in: keychain),
                       .keptInMemory)
    }

    // MARK: - A pair the keychain refuses

    func testARotationTheKeychainRefusesIsKeptInMemory() {
        let keychain = signedIn()
        let vault = SessionTokenVault(fence: MemoryFence())
        refusedRotation(keychain, vault)
        XCTAssertEqual(vault.accessToken(in: keychain), a1, "requests do not keep working on the new pair")
        XCTAssertEqual(vault.refreshToken(in: keychain), r1)
    }

    func testTheSpentTokenIsNeverPresentedAgain() {
        let keychain = signedIn()
        keychain.deletesFail = true   // the spent token stays in the keychain
        let fence = MemoryFence()
        let vault = SessionTokenVault(fence: fence)
        refusedRotation(keychain, vault)
        XCTAssertNotEqual(vault.refreshToken(in: keychain), r0)
        XCTAssertTrue(fence.isSet, "nothing durable says the stored refresh token is spent")

        // The process dies; the next one starts from the keychain and the fence.
        let relaunched = SessionTokenVault(fence: fence)
        XCTAssertNil(relaunched.refreshToken(in: keychain), "the spent refresh token would be presented again")
        XCTAssertTrue(relaunched.storedRefreshTokenIsSpent)
    }

    func testAPersistThatWorksStoresThePairAndClearsTheFence() {
        let keychain = signedIn()
        let fence = MemoryFence()
        let vault = SessionTokenVault(fence: fence)
        refusedRotation(keychain, vault)
        keychain.fails = { _, _ in false }
        XCTAssertTrue(vault.persistPending(in: keychain))
        XCTAssertEqual(keychain.pair, .init(access: a1, refresh: r1))
        XCTAssertFalse(fence.isSet)
        XCTAssertEqual(SessionTokenVault(fence: fence).refreshToken(in: keychain), r1)
    }

    func testTheFenceIsClearedOnlyByAWriteThatStoredTheNewRefreshToken() {
        let keychain = signedIn()
        keychain.deletesFail = true
        let fence = MemoryFence()
        let vault = SessionTokenVault(fence: fence)
        refusedRotation(keychain, vault)

        // Still refused.
        XCTAssertFalse(vault.persistPending(in: keychain))
        XCTAssertTrue(fence.isSet)
        XCTAssertNil(SessionTokenVault(fence: fence).refreshToken(in: keychain))

        // The refresh token goes in, the access token does not: the stored
        // refresh token is no longer the spent one.
        keychain.fails = { item, _ in item == .accessToken }
        XCTAssertFalse(vault.persistPending(in: keychain))
        XCTAssertFalse(fence.isSet)
        XCTAssertEqual(vault.accessToken(in: keychain), a1, "the pair in memory was dropped")
        XCTAssertEqual(SessionTokenVault(fence: fence).refreshToken(in: keychain), r1)

        keychain.fails = { _, _ in false }
        XCTAssertTrue(vault.persistPending(in: keychain))
        XCTAssertEqual(keychain.pair, .init(access: a1, refresh: r1))
    }

    /// A relaunch that finds the fence and no newer pair: signed out locally.
    /// `refreshToken` is nil, so `APIClient.refreshTokens` throws before any
    /// request (pinned below), and the cold start purges the session.
    func testARelaunchWithTheFenceSetSignsOutLocally() {
        let fence = MemoryFence(set: true)
        let keychain = signedIn()
        let vault = SessionTokenVault(fence: fence)
        XCTAssertTrue(vault.storedRefreshTokenIsSpent)
        XCTAssertNil(vault.refreshToken(in: keychain))

        // The sign-out cannot delete yet (locked since boot): the fence stays.
        keychain.deletesFail = true
        vault.clear(in: keychain)
        XCTAssertTrue(fence.isSet)
        XCTAssertNil(vault.refreshToken(in: keychain))

        keychain.deletesFail = false
        vault.clear(in: keychain)
        XCTAssertEqual(keychain.pair, .init(access: nil, refresh: nil))
        XCTAssertFalse(fence.isSet)
    }

    /// The fence is set while the keychain is locked. In this process, the
    /// unlock stores the pair kept in memory; in a relaunch, the fence alone
    /// (readable before the first unlock) keeps the spent token from being
    /// presented, and the unlocked sign-out clears it.
    func testAFenceSetWhileLockedHoldsUntilUnlock() {
        let fence = MemoryFence()
        let keychain = signedIn()
        keychain.deletesFail = true
        let vault = SessionTokenVault(fence: fence)
        refusedRotation(keychain, vault)
        XCTAssertEqual(vault.refreshToken(in: keychain), r1)

        keychain.fails = { _, _ in false }
        keychain.deletesFail = false
        XCTAssertTrue(vault.persistPending(in: keychain), "the unlock did not store the pair kept in memory")
        XCTAssertFalse(fence.isSet)
        XCTAssertEqual(vault.refreshToken(in: keychain), r1)

        // The other way: the process dies while locked.
        let lockedFence = MemoryFence()
        let lockedKeychain = signedIn()
        lockedKeychain.deletesFail = true
        refusedRotation(lockedKeychain, SessionTokenVault(fence: lockedFence))
        lockedKeychain.failLookups = true
        let relaunched = SessionTokenVault(fence: lockedFence)
        XCTAssertNil(relaunched.refreshToken(in: lockedKeychain))
        XCTAssertTrue(relaunched.storedRefreshTokenIsSpent)
        lockedKeychain.failLookups = false
        lockedKeychain.deletesFail = false
        XCTAssertNil(relaunched.refreshToken(in: lockedKeychain), "the spent token is presented after the unlock")
        relaunched.clear(in: lockedKeychain)
        XCTAssertFalse(lockedFence.isSet)
    }

    func testASignInReplacesAFencedSession() {
        let fence = MemoryFence()
        let keychain = signedIn()
        let vault = SessionTokenVault(fence: fence)
        refusedRotation(keychain, vault)
        XCTAssertTrue(fence.isSet)
        keychain.fails = { _, _ in false }
        XCTAssertTrue(vault.save(accessToken: a2, refreshToken: r2, email: nil, in: keychain))
        XCTAssertFalse(fence.isSet)
        XCTAssertEqual(vault.accessToken(in: keychain), a2, "the old pair in memory outlived the sign-in")
        XCTAssertEqual(vault.refreshToken(in: keychain), r2)
    }

    /// The fence itself cannot be written: nothing durable would keep the
    /// spent token from a relaunch, so the session is signed out now, and the
    /// caller revokes the dropped pair with its own access token.
    func testAFenceThatCannotBeWrittenSignsOutNow() {
        let fence = MemoryFence()
        fence.failSets = true
        let keychain = signedIn()
        let vault = SessionTokenVault(fence: fence)
        keychain.fails = { _, _ in true }
        XCTAssertEqual(vault.saveRotation(accessToken: a1, refreshToken: r1, expectingRefreshToken: r0, in: keychain),
                       .signedOut)
        XCTAssertNil(vault.refreshToken(in: keychain), "the spent refresh token can still be presented")
        XCTAssertNil(vault.accessToken(in: keychain))
        XCTAssertEqual(keychain.pair, .init(access: nil, refresh: nil))
    }

    /// The fence goes only once the NEW refresh token reads back: a write that
    /// reported success and stored nothing keeps it.
    func testTheFenceIsClearedOnlyWhenTheNewRefreshTokenReadsBack() {
        let fence = MemoryFence()
        let keychain = signedIn()
        let vault = SessionTokenVault(fence: fence)
        refusedRotation(keychain, vault)
        keychain.fails = { _, _ in false }
        keychain.dropsWrites = [.refreshToken]
        XCTAssertFalse(vault.persistPending(in: keychain))
        XCTAssertTrue(fence.isSet, "cleared on a write that did not read back")
        XCTAssertEqual(vault.refreshToken(in: keychain), r1)
        keychain.dropsWrites = []
        XCTAssertTrue(vault.persistPending(in: keychain))
        XCTAssertFalse(fence.isSet)
    }

    // MARK: - One account at every kill point, every session item

    private let e0 = Data("email-0".utf8)
    private let e1 = Data("email-1".utf8)
    private let m0 = Data("method-0".utf8)
    private let n0 = Data("number-0".utf8)

    /// Which accounts' items a state holds: 0 the prior one, 1 the new one.
    private func accounts(_ state: MemoryKeychain.Pair) -> Set<Int> {
        var found: Set<Int> = []
        for (value, prior, new) in [(state.access, a0, a1), (state.refresh, r0, r1), (state.email, e0, e1)] {
            if value == prior { found.insert(0) }
            if value == new { found.insert(1) }
        }
        if state.method == m0 || state.anonymousId == n0 { found.insert(0) }
        return found
    }

    /// #671 review, item 4: a sign-in over another account's session. The
    /// prior e-mail, sign-in method and anonymous number go first with the
    /// prior access token, and the new e-mail is written last
    /// (`AuthViewModel` writes the method and the number after `save`).
    func testEveryKillPointOfASignInLeavesOneAccountsItems() {
        let keychain = MemoryKeychain(access: a0, refresh: r0, email: e0, method: m0, anonymousId: n0)
        let vault = SessionTokenVault(fence: MemoryFence())
        XCTAssertTrue(vault.save(accessToken: a1, refreshToken: r1, email: e1, in: keychain))
        for (index, state) in keychain.states.enumerated() {
            XCTAssertLessThanOrEqual(accounts(state).count, 1, "a kill after change \(index) mixes two accounts")
        }
        XCTAssertEqual(keychain.pair, .init(access: a1, refresh: r1, email: e1))

        // A failed sign-in puts every prior item back.
        let failed = MemoryKeychain(access: a0, refresh: r0, email: e0, method: m0, anonymousId: n0)
        failed.fails = { item, earlier in item == .accessToken && earlier == 1 }
        XCTAssertFalse(SessionTokenVault(fence: MemoryFence()).save(accessToken: a1, refreshToken: r1, email: e1,
                                                                   in: failed))
        XCTAssertEqual(failed.pair, .init(access: a0, refresh: r0, email: e0, method: m0, anonymousId: n0))
        for state in failed.states {
            XCTAssertLessThanOrEqual(accounts(state).count, 1)
        }
    }

    /// #671 review, item 3 (Low): a failed put-back whose delete fails too,
    /// then `saveWithRetry`'s second attempt. Never two accounts' tokens.
    func testARetryAfterAFailedPutBackNeverMixesAccounts() {
        let keychain = signedIn()
        keychain.fails = { item, earlier in item == .accessToken || (item == .refreshToken && earlier > 0) }
        keychain.onWrite = { item in
            if item == .accessToken { keychain.deletesFail = true }
        }
        let vault = SessionTokenVault(fence: MemoryFence())
        XCTAssertFalse(SessionPersistence.saveWithRetry {
            vault.save(accessToken: a1, refreshToken: r1, email: nil, in: keychain)
        })
        for (index, state) in keychain.states.enumerated() {
            XCTAssertLessThanOrEqual(accounts(state).count, 1, "change \(index) mixes two accounts")
        }
        XCTAssertLessThanOrEqual(accounts(keychain.pair).count, 1)
    }

    // MARK: - The interleavings of the #671 review, item 3

    /// Starts `other` on another thread at the first main-thread write of
    /// `item`, and lets it run for a while. Without the vault's lock it runs to
    /// its end right there, in the middle of that write's caller; with it, it
    /// waits until the caller is done.
    private final class Interleaving: @unchecked Sendable {
        private let done = DispatchSemaphore(value: 0)
        private var fired = false

        func arm(_ keychain: MemoryKeychain, at item: HostKeychainItem, _ other: @escaping @Sendable () -> Void) {
            keychain.onWrite = { [self] written in
                guard Thread.isMainThread, written == item, !fired else { return }
                fired = true
                DispatchQueue.global().async {
                    other()
                    self.done.signal()
                }
                if done.wait(timeout: .now() + .milliseconds(300)) == .success { done.signal() }
            }
        }

        func finish() {
            XCTAssertEqual(done.wait(timeout: .now() + 10), .success, "the other writer never finished")
        }
    }

    private final class Box: @unchecked Sendable {
        var rotation: SessionTokenVault.RotationSave?
    }

    /// (a) A refresh rotation lands while a sign-in is between reading the
    /// prior pair and putting it back: the spent prior token must not come
    /// back over the rotation's new pair.
    func testARotationDuringASignInsPutBackNeverLeavesTheSpentToken() {
        let keychain = signedIn()
        // The sign-in's (main thread) access writes fail; the rotation's do not.
        keychain.fails = { item, _ in item == .accessToken && Thread.isMainThread }
        let vault = SessionTokenVault(fence: MemoryFence())
        let box = Box()
        let interleaving = Interleaving()
        let (a1, r1, r0) = (self.a1, self.r1, self.r0)
        interleaving.arm(keychain, at: .refreshToken) {
            box.rotation = vault.saveRotation(accessToken: a1, refreshToken: r1, expectingRefreshToken: r0,
                                              in: keychain)
        }
        XCTAssertFalse(vault.save(accessToken: a2, refreshToken: r2, email: nil, in: keychain))
        interleaving.finish()
        XCTAssertNotEqual(keychain.pair.refresh, r0, "the refresh token the rotation spent was put back")
        XCTAssertEqual(box.rotation, .stored)
        XCTAssertEqual(keychain.pair.refresh, r1)
    }

    /// (b) A sign-out lands while a rotation is writing: the rotation's pair
    /// must not outlive the sign-out.
    func testASignOutDuringARotationIsNotUndone() {
        let keychain = signedIn()
        let vault = SessionTokenVault(fence: MemoryFence())
        let interleaving = Interleaving()
        interleaving.arm(keychain, at: .refreshToken) {
            vault.clear(in: keychain)
        }
        _ = vault.saveRotation(accessToken: a1, refreshToken: r1, expectingRefreshToken: r0, in: keychain)
        interleaving.finish()
        XCTAssertEqual(keychain.pair, .init(access: nil, refresh: nil), "the signed-out session came back")
        XCTAssertNil(vault.refreshToken(in: keychain))
    }

    /// (c) A rotation of account A lands while account B's sign-in is writing:
    /// B's pair must be the one left, and no state may mix the two.
    func testARotationDuringASignInNeverMixesAccounts() {
        let keychain = signedIn()
        let vault = SessionTokenVault(fence: MemoryFence())
        let box = Box()
        let interleaving = Interleaving()
        let (a1, r1, r0) = (self.a1, self.r1, self.r0)
        interleaving.arm(keychain, at: .refreshToken) {
            box.rotation = vault.saveRotation(accessToken: a1, refreshToken: r1, expectingRefreshToken: r0,
                                              in: keychain)
        }
        XCTAssertTrue(vault.save(accessToken: a2, refreshToken: r2, email: nil, in: keychain))
        interleaving.finish()
        XCTAssertEqual(box.rotation, .replaced, "a rotation of the old account wrote over the sign-in")
        XCTAssertEqual(keychain.pair, .init(access: a2, refresh: r2))
        for (index, state) in keychain.states.enumerated() {
            let oldAccount = state.access == a0 || state.access == a1 || state.refresh == r0 || state.refresh == r1
            let newAccount = state.access == a2 || state.refresh == r2
            XCTAssertFalse(oldAccount && newAccount, "change \(index) mixes two accounts")
        }
    }

    // MARK: - The compare-and-swap, inside the lock

    func testARotationThatIsNoLongerCurrentWritesNothing() {
        // A sign-in stored (a2, r2) while the rotation that presented r0 was
        // on the wire.
        let keychain = MemoryKeychain(access: a2, refresh: r2)
        let vault = SessionTokenVault(fence: MemoryFence())
        XCTAssertEqual(vault.saveRotation(accessToken: a1, refreshToken: r1, expectingRefreshToken: r0, in: keychain),
                       .replaced, "a stored session the rotated pair's revoke would end with its lineage")
        XCTAssertEqual(keychain.pair, .init(access: a2, refresh: r2), "a rotation wrote over a sign-in")

        // Signed out meanwhile (the wipe ran; the generation bump has not).
        let signedOut = MemoryKeychain(access: nil, refresh: nil)
        XCTAssertEqual(vault.saveRotation(accessToken: a1, refreshToken: r1, expectingRefreshToken: r0, in: signedOut),
                       .ended)
        XCTAssertEqual(signedOut.pair, .init(access: nil, refresh: nil), "a rotation brought a signed-out session back")
    }

    // MARK: - #671 review P2: the late revoke is decided under the lock

    /// The generation moved (a sign-out) but the wipe's deletes failed, so the
    /// presented token is still stored: the session ended all the same.
    /// Nothing is written, and it is the late revoke's.
    func testARotationAfterASignOutWhoseWipeFailedIsEndedAndNeverWritten() {
        let keychain = signedIn()
        let vault = SessionTokenVault(fence: MemoryFence())
        XCTAssertEqual(vault.saveRotation(accessToken: a1, refreshToken: r1, expectingRefreshToken: r0,
                                          sessionEnded: true, in: keychain),
                       .ended)
        XCTAssertEqual(keychain.pair, .init(access: a0, refresh: r0), "a rotation wrote into a signed-out session")
        XCTAssertTrue(keychain.writes.isEmpty)
    }

    /// A sign-out and then a sign-in: the stored session is the new one, so
    /// the old session's rotated pair is never revoked (its revoke would end
    /// the device's lineage, the new session with it).
    func testARotationAfterASignOutAndASignInIsReplacedAndNeverRevoked() {
        let keychain = MemoryKeychain(access: a2, refresh: r2)
        let vault = SessionTokenVault(fence: MemoryFence())
        XCTAssertEqual(vault.saveRotation(accessToken: a1, refreshToken: r1, expectingRefreshToken: r0,
                                          sessionEnded: true, in: keychain),
                       .replaced)
        XCTAssertEqual(keychain.pair, .init(access: a2, refresh: r2))
    }

    /// After a sign-out, a keychain that cannot be read may hold a new session:
    /// never revoke on a guess.
    func testAnUnreadableKeychainAfterASignOutIsNeverRevoked() {
        let keychain = signedIn()
        keychain.failLookups = true
        let vault = SessionTokenVault(fence: MemoryFence())
        XCTAssertEqual(vault.saveRotation(accessToken: a1, refreshToken: r1, expectingRefreshToken: r0,
                                          sessionEnded: true, in: keychain),
                       .replaced)
        XCTAssertTrue(keychain.writes.isEmpty)
    }

    func testARotationThatIsStillCurrentIsStored() {
        let keychain = signedIn()
        let vault = SessionTokenVault(fence: MemoryFence())
        XCTAssertEqual(vault.saveRotation(accessToken: a1, refreshToken: r1, expectingRefreshToken: r0, in: keychain),
                       .stored)
        XCTAssertEqual(keychain.pair, .init(access: a1, refresh: r1))
    }

    // MARK: - The fence file

    func testTheFenceFileHoldsNoSecretAndOutlivesTheProcess() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("fence-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let fence = FileSpentRefreshFence(directory: folder)
        XCTAssertFalse(fence.isSet)
        XCTAssertTrue(fence.set())
        XCTAssertTrue(fence.isSet)
        let text = try String(contentsOf: fence.url, encoding: .utf8)
        XCTAssertNotNil(text.range(of: #"^spent at [0-9]+\n$"#, options: .regularExpression), text)
        XCTAssertTrue(FileSpentRefreshFence(directory: folder).isSet, "a relaunch does not see the fence")
        fence.clear()
        XCTAssertFalse(FileSpentRefreshFence(directory: folder).isSet)
    }

    /// The write itself, through an injected writer: atomic, and unprotected
    /// on iOS (the default class would hide it until the first unlock).
    func testTheFenceFileIsWrittenUnprotected() {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("fence-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        final class Recorder: @unchecked Sendable {
            var options: Data.WritingOptions?
        }
        let recorder = Recorder()
        let fence = FileSpentRefreshFence(directory: folder) { data, url, options in
            recorder.options = options
            try data.write(to: url, options: options)
        }
        XCTAssertTrue(fence.set())
        XCTAssertEqual(recorder.options?.contains(.atomic), true)
        #if os(iOS)
        XCTAssertEqual(recorder.options?.contains(.noFileProtection), true, "the fence file is protected")
        XCTAssertEqual(FileSpentRefreshFence.folderAttributes?[.protectionKey] as? FileProtectionType,
                       FileProtectionType.none)
        #endif
    }

    // MARK: - Where it is wired (source pins: APIClient, AuthViewModel and
    // KeychainService cannot be compiled into this bundle)

    private func source(_ path: String) throws -> String {
        // .../iosApp/BirdoVPNTests/SessionTokenVaultTests.swift -> .../iosApp
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let url = root.appendingPathComponent(path)
        guard FileManager.default.fileExists(atPath: url.path) else {
            XCTFail("\(path) not found under \(root.path): this pin would be vacuous")
            return ""
        }
        return try String(contentsOf: url, encoding: .utf8).replacingOccurrences(of: "\r\n", with: "\n")
    }

    private func member(_ signature: String, in text: String) -> Substring? {
        guard let start = text.range(of: signature),
              let end = text.range(of: "\n    }\n", range: start.upperBound..<text.endIndex) else { return nil }
        return text[start.lowerBound..<end.upperBound]
    }

    /// The fence file is unprotected on iOS, so it is there before the first
    /// unlock, when the keychain, UserDefaults and default-class files are not.
    func testTheFenceFileIsReadableBeforeTheFirstUnlock() throws {
        let vault = try source("iosApp/Services/SessionTokenVault.swift")
        XCTAssertTrue(vault.contains("[.atomic, .noFileProtection]"))
        XCTAssertTrue(vault.contains("[.protectionKey: FileProtectionType.none]"))
    }

    func testTheAppStoresThePairKeptInMemoryAndNeverPresentsASpentToken() throws {
        let api = try source("iosApp/Services/APIClient.swift")
        let refresh = try XCTUnwrap(member("private func refreshTokens() async throws {", in: api))
        guard let persist = refresh.range(of: "keychain.persistPendingSession()"),
              let read = refresh.range(of: "guard let refresh = keychain.refreshToken else") else {
            return XCTFail("a refresh does not first store a pair kept in memory")
        }
        XCTAssertLessThan(persist.lowerBound, read.lowerBound)
        guard let save = refresh.range(of: "keychain.saveRotation("),
              let kept = refresh.range(of: "case .stored, .keptInMemory:"),
              let replaced = refresh.range(of: "case .replaced:"),
              let ended = refresh.range(of: "case .ended:"),
              let signedOut = refresh.range(of: "case .signedOut:"),
              kept.upperBound <= replaced.lowerBound, replaced.upperBound <= ended.lowerBound,
              ended.upperBound <= signedOut.lowerBound else {
            return XCTFail("the rotated pair is not stored through saveRotation, or an outcome is not handled in order")
        }
        XCTAssertTrue(refresh[save.lowerBound...].contains("expectingRefreshToken: refresh"))
        XCTAssertFalse(refresh[kept.upperBound..<replaced.lowerBound].contains("throw"),
                       "a pair kept in memory fails the request instead of serving it")
        // #671 review P2: no early return on a generation change; the vault
        // decides under its lock, told that the session ended.
        XCTAssertTrue(refresh[save.lowerBound...].contains("sessionEnded: generationNow != generationAtStart"),
                      "the generation change is not handed to the locked decision")
        XCTAssertFalse(refresh.contains("guard generationNow == generationAtStart else"),
                       "a generation change returns before the locked decision")
        // P2: a stored session other than the renewed one: never revoked.
        let neverRevoked = refresh[replaced.upperBound..<ended.lowerBound]
        XCTAssertFalse(neverRevoked.contains("logout(") || neverRevoked.contains("signOut("),
                       "a rotation over a stored session revokes, ending the device's lineage")
        // P3: the late revoke's site, bearer-free (#666's form; its API is not
        // on main yet, so the site is marked and the pair dropped as on main).
        let late = refresh[ended.upperBound..<signedOut.lowerBound]
        XCTAssertTrue(late.contains("signOut(SignOutTokens(accessToken: nil, refreshToken: tokens.refreshToken ?? refresh))"))
        XCTAssertFalse(late.contains("logout(bearerToken:"), "the late revoke presents a bearer")
        // P4: signed out because nothing durable could fence the spent token:
        // the dropped pair is revoked with ITS OWN token (never the stored
        // one: #666's Android M1), with no refresh handling, and the caller
        // signs out.
        let dropped = refresh[signedOut.upperBound...]
        XCTAssertTrue(dropped.contains("let dropped = tokens.accessToken"))
        XCTAssertTrue(dropped.contains("logout(bearerToken: dropped)"))
        XCTAssertTrue(dropped.contains("signOut(SignOutTokens(accessToken: tokens.accessToken"))
        XCTAssertTrue(dropped.contains("throw APIError.unauthorized"))
        XCTAssertFalse(dropped.contains("keychain.accessToken") || dropped.contains("keychain.refreshToken"),
                       "the dropped pair is revoked with the stored token")

        let retry = try XCTUnwrap(member("func retryHeldRefresh() async throws {", in: api))
        XCTAssertTrue(retry.contains("keychain.persistPendingSession()"), "coming to the foreground stores nothing")

        let keychain = try source("iosApp/Services/KeychainService.swift")
        XCTAssertTrue(keychain.contains("\"UIApplicationProtectedDataDidBecomeAvailable\""),
                      "the first unlock does not store the pair kept in memory")
        // P5: every read of either token goes through the vault, so a sign-out
        // that reads both before its wipe (#666) never presents a spent one.
        XCTAssertTrue(keychain.contains("var accessToken: String? { session.accessToken(in: self)"))
        XCTAssertTrue(keychain.contains("var refreshToken: String? { session.refreshToken(in: self)"))

        let auth = try source("iosApp/ViewModels/AuthViewModel.swift")
        guard let live = auth.range(of: "let sessionLive = "),
              let end = auth.range(of: "hasConsented = storedConsent", range: live.upperBound..<auth.endIndex) else {
            return XCTFail("the cold start's session check not found")
        }
        XCTAssertTrue(auth[live.lowerBound..<end.lowerBound].contains("!keychain.storedRefreshTokenIsSpent"),
                      "a relaunch with a spent refresh token stays signed in")
    }

    /// Only the host app refreshes. The PacketTunnel extension has no refresh
    /// token and compiles none of the code that stores or presents one, so the
    /// fence has no second reader to miss.
    func testOnlyTheAppEverRefreshes() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let folder = root.appendingPathComponent("PacketTunnel")
        let names = try FileManager.default.contentsOfDirectory(atPath: folder.path).filter { $0.hasSuffix(".swift") }
        XCTAssertFalse(names.isEmpty, "PacketTunnel sources not found: this pin would be vacuous")
        for name in names {
            let text = try String(contentsOf: folder.appendingPathComponent(name), encoding: .utf8)
            for word in ["auth/refresh", "refresh_token", "refreshToken", "saveRotation", "SessionTokenVault"] {
                XCTAssertFalse(text.contains(word), "PacketTunnel/\(name) mentions \(word)")
            }
        }
        let project = try source("project.yml")
        guard let tunnel = project.range(of: "\n  PacketTunnel:\n") else {
            return XCTFail("the PacketTunnel target is not in project.yml")
        }
        // PacketTunnel is the last target; another one after it would end it.
        let target = project[tunnel.upperBound...]
        let nextTarget = target.range(of: "\n  [A-Za-z]", options: .regularExpression)
        let body = nextTarget.map { target[..<$0.lowerBound] } ?? target
        for file in ["KeychainService.swift", "APIClient.swift", "SessionTokenVault.swift", "TokenPairWrite.swift"] {
            XCTAssertFalse(body.contains(file), "the PacketTunnel target compiles \(file)")
        }
    }
}

/// The fence, in memory: one object stands for the file across a relaunch.
final class MemoryFence: SpentRefreshFence, @unchecked Sendable {
    private(set) var stored: Bool
    /// The file cannot be written (a full disk).
    var failSets = false

    init(set: Bool = false) {
        stored = set
    }

    var isSet: Bool { stored }

    func set() -> Bool {
        guard !failSets else { return false }
        stored = true
        return true
    }

    func clear() {
        stored = false
    }
}
