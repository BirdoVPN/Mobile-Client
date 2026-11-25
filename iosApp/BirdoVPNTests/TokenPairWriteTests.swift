import XCTest
import Security

/// MR-2580 (L1 of the #665 review): the session's two tokens are stored refresh
/// token FIRST, each in one step, and no failure and no kill leaves the NEW
/// access token next to the PRIOR refresh token.
///
/// Before: `KeychainService.save` wrote the access token first, and each write
/// was a delete followed by an add. A kill between the two writes left (new
/// access, prior refresh). After a refresh rotation that refresh token is
/// already spent, and presenting it about an hour later reads as theft to the
/// server: every session of the account and its WireGuard peers are revoked.
/// After a sign-in or a number rotation it was a sign-out an hour later.
///
/// The keychain here is in memory: the two token items behind `TokenPairStore`,
/// and the Security calls behind `KeychainCalls`.
final class TokenPairWriteTests: XCTestCase {

    private let priorAccess = Data("access-prior".utf8)
    private let priorRefresh = Data("refresh-prior".utf8)
    private let newAccess = Data("access-new".utf8)
    private let newRefresh = Data("refresh-new".utf8)

    private var everyWrite: [TokenPairWrite.Replaced] { [.anotherSession, .consumedRefreshToken] }

    private func signedIn() -> MemoryKeychain {
        MemoryKeychain(access: priorAccess, refresh: priorRefresh)
    }

    private func save(_ keychain: MemoryKeychain, _ replacing: TokenPairWrite.Replaced) -> Bool {
        TokenPairWrite.save(accessToken: newAccess, refreshToken: newRefresh, replacing: replacing, in: keychain)
    }

    // MARK: - The order

    func testTheRefreshTokenIsWrittenBeforeTheAccessToken() {
        for replacing in everyWrite {
            let keychain = signedIn()
            XCTAssertTrue(save(keychain, replacing))
            XCTAssertEqual(keychain.writes, [.refreshToken, .accessToken], "\(replacing)")
            XCTAssertEqual(keychain.pair, .init(access: newAccess, refresh: newRefresh), "\(replacing)")
        }
    }

    /// The app holds no background task, so it can be killed after any keychain
    /// change. Every state the keychain passes through is one a kill can leave.
    func testNoKillPointPairsTheNewAccessTokenWithAnotherRefreshToken() {
        for replacing in everyWrite {
            for keychain in [signedIn(), MemoryKeychain(access: nil, refresh: nil)] {
                XCTAssertTrue(save(keychain, replacing))
                for (index, state) in keychain.states.enumerated() where state.access == newAccess {
                    XCTAssertEqual(state.refresh, newRefresh,
                                   "a kill after change \(index) leaves the new access token with "
                                       + "\(state.refresh == nil ? "no" : "the prior") refresh token (\(replacing))")
                }
                // A refresh rotation's first write: the new refresh token, next
                // to the prior access token, whose next 401 refreshes with it.
                if replacing == .consumedRefreshToken {
                    XCTAssertEqual(keychain.states.first?.refresh, newRefresh)
                }
            }
        }
    }

    /// #671 review: a sign-in over another account's pair (an account switch
    /// without a sign-out). Every kill point leaves ONE account: the prior
    /// access token goes before the new refresh token comes, so requests never
    /// act on the prior account while the refresh moves to the new one.
    func testEveryKillPointOfASignInLeavesOneAccount() {
        let keychain = signedIn()
        XCTAssertTrue(save(keychain, .anotherSession))
        for (index, state) in keychain.states.enumerated() {
            XCTAssertFalse(state.access == priorAccess && state.refresh == newRefresh,
                           "a kill after change \(index) leaves the prior account's access token "
                               + "with the new account's refresh token")
        }
        XCTAssertEqual(keychain.pair, .init(access: newAccess, refresh: newRefresh))
    }

    // MARK: - What a failure leaves

    /// A sign-in: the prior pair is another session's, not spent by this write.
    func testAFailedRefreshWriteOnASignInLeavesThePriorPairIntact() {
        let keychain = signedIn()
        keychain.fails = { item, _ in item == .refreshToken }
        XCTAssertFalse(save(keychain, .anotherSession))
        XCTAssertEqual(keychain.pair, .init(access: priorAccess, refresh: priorRefresh))
        XCTAssertFalse(keychain.states.contains { $0.access == newAccess },
                       "the new access token was stored with no refresh token to go with it")
    }

    /// #671 review, item 1 (inverted from the first head, which kept the
    /// "prior pair intact" here): after a refresh rotation the prior refresh
    /// token is the one the server just spent. Where the keychain still allows
    /// a delete it goes; where it does not, `SessionTokenVault` fences it.
    func testAFailedRefreshWriteAfterARotationRemovesTheSpentToken() {
        let keychain = signedIn()
        keychain.fails = { item, _ in item == .refreshToken }
        XCTAssertEqual(TokenPairWrite.write(accessToken: newAccess, refreshToken: newRefresh,
                                            replacing: .consumedRefreshToken, in: keychain), .notStored)
        XCTAssertNil(keychain.pair.refresh, "the spent refresh token is still stored, ready to be presented")
        XCTAssertEqual(keychain.pair.access, priorAccess)
        XCTAssertFalse(keychain.states.contains { $0.access == newAccess })
    }

    /// A sign-in or a number rotation: the device is left as it was, so the
    /// caller's "not stored" is true.
    func testAFailedAccessWriteOnASignInPutsThePriorPairBack() {
        // Writes: 0 the new refresh token, 1 the new access token (refused),
        // then the put-backs.
        let keychain = signedIn()
        keychain.fails = { item, earlier in item == .accessToken && earlier == 1 }
        XCTAssertFalse(save(keychain, .anotherSession))
        XCTAssertEqual(keychain.pair, .init(access: priorAccess, refresh: priorRefresh))

        // The access token refused every time: still the prior account only.
        let refusing = signedIn()
        refusing.fails = { item, _ in item == .accessToken }
        XCTAssertFalse(save(refusing, .anotherSession))
        XCTAssertEqual(refusing.pair.refresh, priorRefresh)
        XCTAssertNotEqual(refusing.pair.access, newAccess)
    }

    /// A cold start counts a live refresh token as signed in, so a sign-in that
    /// reported failure must not leave one.
    func testAFailedAccessWriteOnAFirstSignInLeavesNoSession() {
        let keychain = MemoryKeychain(access: nil, refresh: nil)
        keychain.fails = { item, _ in item == .accessToken }
        XCTAssertFalse(save(keychain, .anotherSession))
        XCTAssertEqual(keychain.pair, .init(access: nil, refresh: nil))
    }

    /// A refresh rotation: the server spent the prior refresh token, so it is
    /// never put back. The new one stays, and the prior access token's next 401
    /// refreshes with it.
    func testAFailedAccessWriteAfterARefreshRotationKeepsTheNewRefreshToken() {
        let keychain = signedIn()
        keychain.fails = { item, _ in item == .accessToken }
        XCTAssertFalse(save(keychain, .consumedRefreshToken))
        XCTAssertEqual(keychain.pair.refresh, newRefresh,
                       "the spent refresh token is stored again: presented later, it reads as theft")
        XCTAssertEqual(keychain.pair.access, priorAccess)
    }

    /// What a failed sign-in cannot put back (it could not be read, or the
    /// write back failed) it removes: the prior access token then ends in a
    /// sign-out at its next 401, never in the session that was not stored.
    func testWhatAFailedSignInCannotPutBackItRemoves() {
        let unread = signedIn()
        unread.failLookups = true
        unread.fails = { item, _ in item == .accessToken }
        XCTAssertFalse(save(unread, .anotherSession))
        XCTAssertEqual(unread.pair, .init(access: priorAccess, refresh: nil))

        let stuck = signedIn()
        stuck.fails = { item, earlier in
            (item == .accessToken && earlier == 1) || (item == .refreshToken && earlier > 0)
        }
        XCTAssertFalse(save(stuck, .anotherSession))
        // #671 review, delta 7: the prior access token goes back only together
        // with the prior refresh token. Its put-back failed, so neither is left.
        XCTAssertEqual(stuck.pair, .init(access: nil, refresh: nil))
    }

    /// A refresh rotation reads nothing first: a lookup that fails cannot stop
    /// the new pair from being stored.
    func testARefreshRotationNeedsNothingRead() {
        let keychain = signedIn()
        keychain.failLookups = true
        XCTAssertTrue(save(keychain, .consumedRefreshToken))
        XCTAssertEqual(keychain.pair, .init(access: newAccess, refresh: newRefresh))
    }

    // MARK: - One token item, one step (KeychainItemQuery)

    private let service = "app.birdo.vpn"
    private let account = "refresh_token"
    private let group = "TEAMID0000.app.birdo.vpn.host"
    private var itemKey: String { "\(service)/\(account)/\(group)" }

    private func replace(_ data: Data, _ calls: SecItemCalls, group: String? = nil) -> Bool {
        KeychainItemQuery.replace(data, service: service, account: account, group: group ?? self.group, calls: calls)
    }

    func testAStoredTokenIsReplacedInPlaceNeverDeleted() {
        let calls = SecItemCalls()
        XCTAssertTrue(replace(Data("v1".utf8), calls))
        XCTAssertTrue(replace(Data("v2".utf8), calls))
        XCTAssertEqual(calls.made, ["update", "add", "update"])
        XCTAssertEqual(calls.items, [itemKey: Data("v2".utf8)])
    }

    func testAFailedReplaceLeavesTheStoredTokenAsItWas() {
        let calls = SecItemCalls()
        XCTAssertTrue(replace(Data("v1".utf8), calls))
        for status in [errSecInteractionNotAllowed, errSecMissingEntitlement, errSecIO] {
            calls.updateAnswers = [status]
            calls.addAnswers = [status]
            XCTAssertFalse(replace(Data("v2".utf8), calls), "\(status)")
            XCTAssertEqual(calls.items, [itemKey: Data("v1".utf8)], "\(status)")
        }
        XCTAssertFalse(calls.made.contains("delete"))
    }

    /// Another writer adds the item between the update that found nothing and
    /// the add: that copy is replaced, not deleted.
    func testAReplaceRacingAnotherWriterStillReplaces() {
        let calls = SecItemCalls()
        calls.items[itemKey] = Data("theirs".utf8)
        calls.updateAnswers = [errSecItemNotFound]
        XCTAssertTrue(replace(Data("ours".utf8), calls))
        XCTAssertEqual(calls.made, ["update", "add", "update"])
        XCTAssertEqual(calls.items, [itemKey: Data("ours".utf8)])
    }

    /// #671 review: the other writer's copy, then an update that fails: false,
    /// and their copy is left as it was (never deleted).
    func testAReplaceRacingAnotherWriterThatThenFailsLeavesTheirs() {
        let calls = SecItemCalls()
        calls.items[itemKey] = Data("theirs".utf8)
        calls.updateAnswers = [errSecItemNotFound, errSecIO]
        XCTAssertFalse(replace(Data("ours".utf8), calls))
        XCTAssertEqual(calls.made, ["update", "add", "update"])
        XCTAssertEqual(calls.items, [itemKey: Data("theirs".utf8)])
    }

    /// The attributes are the ones every build has added these items with
    /// (KeychainService's header): only the way the value is written changed.
    func testANewItemKeepsTheAttributesEveryBuildAddedItWith() throws {
        for accessGroup in [self.group, nil] as [String?] {
            let calls = SecItemCalls()
            XCTAssertTrue(KeychainItemQuery.replace(Data("v1".utf8), service: service, account: account,
                                                    group: accessGroup, calls: calls))
            let added = try XCTUnwrap(calls.added.first) as NSDictionary
            XCTAssertEqual(added[kSecClass as String] as? String, kSecClassGenericPassword as String)
            XCTAssertEqual(added[kSecAttrService as String] as? String, service)
            XCTAssertEqual(added[kSecAttrAccount as String] as? String, account)
            XCTAssertEqual(added[kSecValueData as String] as? Data, Data("v1".utf8))
            XCTAssertEqual(added[kSecAttrAccessible as String] as? String,
                           kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
            XCTAssertEqual(added[kSecAttrSynchronizable as String] as? Bool, false)
            XCTAssertEqual(added[kSecUseDataProtectionKeychain as String] as? Bool, true)
            XCTAssertEqual(added[kSecAttrAccessGroup as String] as? String, accessGroup)
            XCTAssertEqual(added.count, accessGroup == nil ? 7 : 8, "an attribute was added")
        }
    }

    /// An update names the item exactly as a delete does, and changes its value
    /// only: never its accessibility, access group or synchronizable flag.
    func testAReplaceChangesTheValueAndNothingElse() throws {
        let calls = SecItemCalls()
        calls.items[itemKey] = Data("v1".utf8)
        XCTAssertTrue(replace(Data("v2".utf8), calls))
        XCTAssertEqual(calls.made, ["update"])
        let update = try XCTUnwrap(calls.updates.first)
        let changes = update.changes as NSDictionary
        XCTAssertEqual(changes.count, 1)
        XCTAssertEqual(changes[kSecValueData as String] as? Data, Data("v2".utf8))
        let query = update.query as NSDictionary
        XCTAssertEqual(Set(query.allKeys.compactMap { $0 as? String }),
                       Set([kSecClass, kSecAttrService, kSecAttrAccount, kSecAttrAccessGroup,
                            kSecUseDataProtectionKeychain].map { $0 as String }))
        XCTAssertEqual(query[kSecAttrAccessGroup as String] as? String, group)
        XCTAssertEqual(query[kSecUseDataProtectionKeychain as String] as? Bool, true)
    }

    /// Every other host-only item is still deleted, then added: a failed
    /// `device_id` write must leave NO id, never the previous install's
    /// (MR-1726, `InstallIdentity`).
    func testEveryOtherItemIsStillDeletedThenAdded() {
        let calls = SecItemCalls()
        let key = "\(service)/device_id/\(group)"
        calls.items[key] = Data("ios_previous".utf8)
        calls.addAnswers = [errSecIO]
        XCTAssertFalse(KeychainItemQuery.store(Data("ios_next".utf8), service: service, account: "device_id",
                                               group: group, calls: calls))
        XCTAssertEqual(calls.made, ["delete", "add"])
        XCTAssertNil(calls.items[key])
    }

    // MARK: - Every caller (source pins: KeychainService, APIClient, AuthViewModel
    // and ReplaceNumberSheet cannot be compiled into this bundle)

    private func appSources() throws -> [(name: String, text: String)] {
        // .../iosApp/BirdoVPNTests/TokenPairWriteTests.swift -> .../iosApp
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        var sources: [(name: String, text: String)] = []
        for folder in ["iosApp", "PacketTunnel"] {
            let dir = root.appendingPathComponent(folder)
            guard let walk = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil) else {
                XCTFail("\(dir.path) cannot be listed: these pins would be vacuous")
                continue
            }
            for case let url as URL in walk where url.pathExtension == "swift" {
                let text = try String(contentsOf: url, encoding: .utf8)
                sources.append((url.lastPathComponent, text.replacingOccurrences(of: "\r\n", with: "\n")))
            }
        }
        XCTAssertGreaterThan(sources.count, 50, "too few sources found under \(root.path): these pins would be vacuous")
        return sources
    }

    /// Every pair the app stores goes through the keychain's session vault: a
    /// new session's with `save`, the refresh rotation's with `saveRotation`
    /// (its compare-and-swap inside the vault's lock). The PacketTunnel
    /// extension stores none.
    func testEveryPairWriteGoesThroughTheVault() throws {
        let sources = try appSources()
        func calls(_ name: String, skipping prefixes: [String]) -> [String: Int] {
            var found: [String: Int] = [:]
            for source in sources {
                var rest = source.text[...]
                while let call = rest.range(of: name) {
                    let head = rest[..<call.lowerBound]
                    rest = rest[call.upperBound...]
                    if prefixes.contains(where: { head.hasSuffix($0) }) { continue }
                    found[source.name, default: 0] += 1
                }
            }
            return found
        }
        XCTAssertEqual(calls("save(accessToken:", skipping: ["func ", "TokenPairWrite.", "session."]),
                       ["AuthViewModel.swift": 2, "ReplaceNumberSheet.swift": 1])
        XCTAssertEqual(calls("saveRotation(accessToken:", skipping: ["func ", "session."]),
                       ["APIClient.swift": 1])
        XCTAssertEqual(calls("TokenPairWrite.save(", skipping: []).merging(calls("TokenPairWrite.write(", skipping: []),
                                                                          uniquingKeysWith: +),
                       ["SessionTokenVault.swift": 2])
    }

    /// Nothing else writes or deletes either token: only `TokenPairWrite` and
    /// the vault, under the vault's one lock (#671 review, delta 5).
    func testOnlyTokenPairWriteWritesTheTokens() throws {
        let sources = try appSources()
        let tokenWrite = try NSRegularExpression(
            pattern: #"\b(setHostData|replaceHostData|deleteHostData|write|replace|delete)\([^)\n]*\.(accessToken|refreshToken)\b"#)
        var writers: Set<String> = []
        for source in sources {
            let text = source.text
            if tokenWrite.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil {
                writers.insert(source.name)
            }
        }
        XCTAssertEqual(writers, ["TokenPairWrite.swift", "SessionTokenVault.swift"])

        let keychain = try XCTUnwrap(sources.first { $0.name == "KeychainService.swift" }).text
        guard let start = keychain.range(of: "func save(accessToken: String, refreshToken: String, email: String?) -> Bool {"),
              let end = keychain.range(of: "\n    }\n", range: start.upperBound..<keychain.endIndex) else {
            return XCTFail("KeychainService.save not found")
        }
        let body = keychain[start.lowerBound..<end.upperBound]
        XCTAssertTrue(body.contains("session.save("), "KeychainService.save writes the pair itself")
    }
}

// MARK: - In-memory keychains

/// The session's items: both tokens, and the e-mail, sign-in method and
/// anonymous number written with them. Every write is one step, as
/// `HostOnlyKeychain.replace` is; any write can be made to fail; every state
/// the items pass through is kept, so a test can ask what a kill after any
/// change would have left; and `onWrite` runs before each write, outside the
/// fake's own lock, so a test can start another writer right there.
final class MemoryKeychain: TokenPairStore, @unchecked Sendable {
    struct Pair: Equatable {
        var access: Data?
        var refresh: Data?
        var email: Data? = nil
        var method: Data? = nil
        var anonymousId: Data? = nil
    }

    private let lock = NSLock()
    private var current: Pair
    private var recorded: [Pair] = []
    private var tried: [HostKeychainItem] = []

    /// Whether a write fails: its item, and how many writes were tried before it.
    var fails: (HostKeychainItem, Int) -> Bool = { _, _ in false }
    /// The keychain cannot be read.
    var failLookups = false
    /// Deletes do nothing (a keychain locked since boot, or an I/O error).
    var deletesFail = false
    /// Writes of these items report success and store nothing.
    var dropsWrites: Set<HostKeychainItem> = []
    /// Runs before each write, with the item, outside the fake's lock.
    var onWrite: ((HostKeychainItem) -> Void)?

    init(access: Data?, refresh: Data?, email: Data? = nil, method: Data? = nil, anonymousId: Data? = nil) {
        current = Pair(access: access, refresh: refresh, email: email, method: method, anonymousId: anonymousId)
    }

    var pair: Pair { lock.withLock { current } }
    /// The items after each change: what a kill right after it leaves.
    var states: [Pair] { lock.withLock { recorded } }
    /// Every write tried, in order, failed ones included.
    var writes: [HostKeychainItem] { lock.withLock { tried } }

    func hostLookup(_ item: HostKeychainItem) -> KeychainLookup {
        lock.withLock {
            if failLookups { return .failed(errSecInteractionNotAllowed) }
            return value(item).map { .found($0) } ?? .notFound
        }
    }

    func replaceHostData(_ data: Data, for item: HostKeychainItem) -> Bool {
        onWrite?(item)
        return lock.withLock {
            defer { tried.append(item) }
            if fails(item, tried.count) { return false }
            if dropsWrites.contains(item) { return true }
            set(item, data)
            return true
        }
    }

    func deleteHostData(_ item: HostKeychainItem) {
        lock.withLock {
            guard !deletesFail, value(item) != nil else { return }
            set(item, nil)
        }
    }

    private func value(_ item: HostKeychainItem) -> Data? {
        switch item {
        case .accessToken: return current.access
        case .refreshToken: return current.refresh
        case .userEmail: return current.email
        case .signInMethod: return current.method
        case .anonymousId: return current.anonymousId
        default: return nil
        }
    }

    private func set(_ item: HostKeychainItem, _ data: Data?) {
        switch item {
        case .accessToken: current.access = data
        case .refreshToken: current.refresh = data
        case .userEmail: current.email = data
        case .signInMethod: current.method = data
        case .anonymousId: current.anonymousId = data
        default:
            XCTFail("the session write touched \(item), which is not a session item")
            return
        }
        recorded.append(current)
    }
}

/// The Security calls, in memory, keyed by service, account and access group.
/// An answer can be forced for the next update or add, which then changes
/// nothing.
private final class SecItemCalls: KeychainCalls {
    var items: [String: Data] = [:]
    private(set) var made: [String] = []
    private(set) var added: [[String: Any]] = []
    private(set) var updates: [(query: [String: Any], changes: [String: Any])] = []
    var updateAnswers: [OSStatus] = []
    var addAnswers: [OSStatus] = []

    private func key(_ query: [String: Any]) -> String {
        let fields = query as NSDictionary
        return [kSecAttrService, kSecAttrAccount, kSecAttrAccessGroup]
            .map { (fields[$0 as String] as? String) ?? "-" }
            .joined(separator: "/")
    }

    func add(_ attributes: [String: Any]) -> OSStatus {
        made.append("add")
        added.append(attributes)
        if !addAnswers.isEmpty { return addAnswers.removeFirst() }
        let slot = key(attributes)
        guard items[slot] == nil else { return errSecDuplicateItem }
        items[slot] = (attributes as NSDictionary)[kSecValueData as String] as? Data
        return errSecSuccess
    }

    func update(_ query: [String: Any], _ changes: [String: Any]) -> OSStatus {
        made.append("update")
        updates.append((query, changes))
        if !updateAnswers.isEmpty { return updateAnswers.removeFirst() }
        let slot = key(query)
        guard items[slot] != nil else { return errSecItemNotFound }
        items[slot] = (changes as NSDictionary)[kSecValueData as String] as? Data
        return errSecSuccess
    }

    func delete(_ query: [String: Any]) -> OSStatus {
        made.append("delete")
        return items.removeValue(forKey: key(query)) == nil ? errSecItemNotFound : errSecSuccess
    }
}
