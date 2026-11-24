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
        XCTAssertTrue(vault.save(accessToken: a2, refreshToken: r2, in: keychain))
        XCTAssertFalse(fence.isSet)
        XCTAssertEqual(vault.accessToken(in: keychain), a2, "the old pair in memory outlived the sign-in")
        XCTAssertEqual(vault.refreshToken(in: keychain), r2)
    }

    // MARK: - The compare-and-swap, inside the lock

    func testARotationThatIsNoLongerCurrentWritesNothing() {
        // A sign-in stored (a2, r2) while the rotation that presented r0 was
        // on the wire.
        let keychain = MemoryKeychain(access: a2, refresh: r2)
        let vault = SessionTokenVault(fence: MemoryFence())
        XCTAssertEqual(vault.saveRotation(accessToken: a1, refreshToken: r1, expectingRefreshToken: r0, in: keychain),
                       .superseded)
        XCTAssertEqual(keychain.pair, .init(access: a2, refresh: r2), "a rotation wrote over a sign-in")

        // Signed out meanwhile.
        let signedOut = MemoryKeychain(access: nil, refresh: nil)
        XCTAssertEqual(vault.saveRotation(accessToken: a1, refreshToken: r1, expectingRefreshToken: r0, in: signedOut),
                       .superseded)
        XCTAssertEqual(signedOut.pair, .init(access: nil, refresh: nil), "a rotation brought a signed-out session back")
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
        guard let save = refresh.range(of: "keychain.saveRotation(") else {
            return XCTFail("the rotated pair is not stored through saveRotation")
        }
        let after = refresh[save.lowerBound...]
        XCTAssertTrue(after.contains("expectingRefreshToken: refresh"))
        XCTAssertFalse(after.contains("throw"),
                       "a pair kept in memory fails the request instead of serving it")

        let retry = try XCTUnwrap(member("func retryHeldRefresh() async throws {", in: api))
        XCTAssertTrue(retry.contains("keychain.persistPendingSession()"), "coming to the foreground stores nothing")

        let keychain = try source("iosApp/Services/KeychainService.swift")
        XCTAssertTrue(keychain.contains("\"UIApplicationProtectedDataDidBecomeAvailable\""),
                      "the first unlock does not store the pair kept in memory")

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
final class MemoryFence: SpentRefreshFence {
    private(set) var stored: Bool

    init(set: Bool = false) {
        stored = set
    }

    var isSet: Bool { stored }

    func set() -> Bool {
        stored = true
        return true
    }

    func clear() {
        stored = false
    }
}
