import XCTest

/// MR-1729 — pins `HostOnlyKeychain`: the host app's own items (tokens, email,
/// device_id, anonymous_id, sign-in method, the BirdoPQ keypair) live in an
/// access group the PacketTunnel extension is not entitled to, and an upgrading
/// install keeps every one of them on the way there.
///
/// Before: they were written with no access group, so they landed in the
/// default group, `<team>.app.birdo.vpn`, which the extension is entitled to as
/// well: the extension could read the refresh token.
///
/// The keychain here is an in-memory stand-in that behaves like the system's
/// for what matters: an add with no group lands in the process's FIRST group,
/// a lookup or delete with no group covers every group the process is entitled
/// to, and a named group the process is not entitled to fails with
/// errSecMissingEntitlement. Two processes share it: the app (shared group,
/// then host group) and the PacketTunnel extension (shared group only), as the
/// entitlements files declare — which the last test checks.
final class HostOnlyKeychainTests: XCTestCase {

    private let shared = "TEAMID0000.app.birdo.vpn"
    private var host: String { HostOnlyKeychain.hostGroup(forShared: shared) }

    private struct Fixture {
        let device: DeviceKeychain
        let app: ProcessKeychain
        let tunnel: ProcessKeychain
        let placement: HostKeychainPlacement
    }

    private func fixture() -> Fixture {
        let device = DeviceKeychain()
        return Fixture(device: device,
                       app: ProcessKeychain(device, groups: [shared, host]),
                       tunnel: ProcessKeychain(device, groups: [shared]),
                       placement: .hostOnly(host: host, shared: shared))
    }

    /// What an older build left on the device: every item written with no
    /// access group (`KeychainService.write`, `BirdoPQManager`).
    @discardableResult
    private func seedOlderBuild(_ store: ProcessKeychain) -> [HostKeychainItem: Data] {
        var values: [HostKeychainItem: Data] = [:]
        for item in HostKeychainItem.allCases {
            let data = Data("v1-\(item.rawValue)".utf8)
            XCTAssertTrue(store.store(data, service: item.service, account: item.account, group: nil))
            values[item] = data
        }
        return values
    }

    private func appRead(_ f: Fixture, _ item: HostKeychainItem) -> Data? {
        HostOnlyKeychain.read(item, placement: f.placement, store: f.app)
    }

    /// The extension asks the way any process can: no group, so every group it
    /// is entitled to.
    private func tunnelRead(_ f: Fixture, _ item: HostKeychainItem) -> Data? {
        f.tunnel.lookup(service: item.service, account: item.account, group: nil).data
    }

    @discardableResult
    private func moveAll(_ f: Fixture) -> [HostKeychainItem: HostKeychainMigration] {
        HostOnlyKeychain.migrateAll(placement: f.placement, store: f.app)
    }

    // MARK: - The closure: the extension cannot read the refresh token

    func testAfterTheMoveTheTunnelExtensionCannotReadTheRefreshToken() {
        let f = fixture()
        let old = seedOlderBuild(f.app)
        // The defect as an older build left it.
        XCTAssertEqual(tunnelRead(f, .refreshToken), old[.refreshToken])

        moveAll(f)

        let refresh = HostKeychainItem.refreshToken
        XCTAssertEqual(f.tunnel.lookup(service: refresh.service, account: refresh.account, group: nil), .notFound)
        XCTAssertEqual(f.tunnel.lookup(service: refresh.service, account: refresh.account, group: host),
                       .failed(errSecMissingEntitlementStatus))
        for item in HostKeychainItem.allCases {
            XCTAssertNil(tunnelRead(f, item), "\(item) is still readable by the PacketTunnel extension")
        }
    }

    func testNewWritesLandWhereOnlyTheAppCanReadThem() {
        let token = Data("v2-refresh".utf8)

        let fresh = fixture()
        XCTAssertTrue(HostOnlyKeychain.write(token, .refreshToken, placement: fresh.placement, store: fresh.app))
        XCTAssertEqual(appRead(fresh, .refreshToken), token)
        XCTAssertNil(tunnelRead(fresh, .refreshToken))

        // Over a copy an older build left: replaced, and the shared copy goes.
        let upgraded = fixture()
        seedOlderBuild(upgraded.app)
        XCTAssertTrue(HostOnlyKeychain.write(token, .refreshToken, placement: upgraded.placement, store: upgraded.app))
        XCTAssertEqual(appRead(upgraded, .refreshToken), token)
        XCTAssertNil(tunnelRead(upgraded, .refreshToken))
    }

    // MARK: - Never lose a session

    func testAnUpgradedInstallKeepsEveryItem() {
        let f = fixture()
        let old = seedOlderBuild(f.app)
        let outcomes = moveAll(f)
        for item in HostKeychainItem.allCases {
            XCTAssertEqual(outcomes[item], .moved, "\(item)")
            XCTAssertEqual(appRead(f, item), old[item], "\(item)")
            XCTAssertEqual(f.app.lookup(service: item.service, account: item.account, group: host).data,
                           old[item], "\(item)")
        }
        XCTAssertEqual(f.device.count(in: shared), 0)
    }

    func testMovingAgainChangesNothing() {
        let f = fixture()
        let old = seedOlderBuild(f.app)
        moveAll(f)
        let again = moveAll(f)
        for item in HostKeychainItem.allCases {
            XCTAssertEqual(again[item], .alreadyMoved, "\(item)")
            XCTAssertEqual(appRead(f, item), old[item], "\(item)")
        }
        // An install that never stored anything.
        let empty = fixture()
        XCTAssertTrue(moveAll(empty).values.allSatisfy { $0 == .nothingStored })
        XCTAssertTrue(empty.device.items.isEmpty)
    }

    func testAFailedCopyNeverDeletesTheOnlyCopy() {
        let f = fixture()
        let old = seedOlderBuild(f.app)
        f.app.failAdds = [host]
        let outcomes = moveAll(f)
        for item in HostKeychainItem.allCases {
            XCTAssertEqual(outcomes[item], .deferred, "\(item)")
            XCTAssertEqual(appRead(f, item), old[item], "\(item): signed out by a failed move")
        }
        XCTAssertEqual(f.device.count(in: shared), HostKeychainItem.allCases.count)

        // The next launch finishes the job.
        f.app.failAdds = []
        XCTAssertTrue(moveAll(f).values.allSatisfy { $0 == .moved })
        XCTAssertNil(tunnelRead(f, .refreshToken))
        XCTAssertEqual(appRead(f, .refreshToken), old[.refreshToken])
    }

    /// An add that reports success is not trusted until it reads back.
    func testACopyThatDoesNotReadBackIsNotTrusted() {
        let f = fixture()
        let old = seedOlderBuild(f.app)
        f.app.dropAdds = [host]
        XCTAssertEqual(HostOnlyKeychain.migrate(.refreshToken, placement: f.placement, store: f.app), .deferred)
        XCTAssertEqual(appRead(f, .refreshToken), old[.refreshToken])
        XCTAssertEqual(f.device.count(in: shared), HostKeychainItem.allCases.count)
    }

    /// Before the first unlock nothing reads: nothing is guessed at, nothing
    /// moved, nothing deleted.
    func testALockedKeychainMovesNothing() {
        let f = fixture()
        let old = seedOlderBuild(f.app)
        f.app.locked = true
        XCTAssertTrue(moveAll(f).values.allSatisfy { $0 == .deferred })
        f.app.locked = false
        XCTAssertEqual(f.device.count(in: shared), HostKeychainItem.allCases.count)
        XCTAssertEqual(f.device.count(in: host), 0)
        XCTAssertEqual(appRead(f, .accessToken), old[.accessToken])
    }

    /// Interrupted between the copy and the delete: the host copy is this
    /// build's own write and wins; the shared one is removed.
    func testAnInterruptedMoveIsFinishedOnTheNextLaunch() {
        let f = fixture()
        let item = HostKeychainItem.refreshToken
        let older = Data("v1".utf8)
        let newer = Data("v2".utf8)
        XCTAssertTrue(f.app.store(older, service: item.service, account: item.account, group: shared))
        XCTAssertTrue(f.app.store(newer, service: item.service, account: item.account, group: host))

        XCTAssertEqual(HostOnlyKeychain.migrate(item, placement: f.placement, store: f.app), .alreadyMoved)
        XCTAssertEqual(appRead(f, item), newer)
        XCTAssertNil(tunnelRead(f, item))
    }

    func testAFailedWriteLeavesThePreviousValueReadable() {
        let f = fixture()
        let old = seedOlderBuild(f.app)
        f.app.failAdds = [host]
        XCTAssertFalse(HostOnlyKeychain.write(Data("v2".utf8), .refreshToken, placement: f.placement, store: f.app))
        XCTAssertEqual(appRead(f, .refreshToken), old[.refreshToken])
    }

    /// Items not moved yet (the move is deferred) still read.
    func testAnItemNotMovedYetStillReads() {
        let f = fixture()
        let old = seedOlderBuild(f.app)
        for item in HostKeychainItem.allCases {
            XCTAssertEqual(appRead(f, item), old[item], "\(item)")
        }
    }

    /// Sign-out: no copy may survive for the read fallback to bring back.
    func testSignOutLeavesNoCopyBehind() {
        let f = fixture()
        seedOlderBuild(f.app)
        // Half-moved: the refresh token is in both groups.
        let refresh = HostKeychainItem.refreshToken
        XCTAssertTrue(f.app.store(Data("v2".utf8), service: refresh.service, account: refresh.account, group: host))

        for item in HostKeychainItem.allCases {
            HostOnlyKeychain.delete(item, store: f.app)
        }
        for item in HostKeychainItem.allCases {
            XCTAssertNil(appRead(f, item), "\(item)")
            XCTAssertNil(tunnelRead(f, item), "\(item)")
        }
        XCTAssertTrue(f.device.items.isEmpty)
    }

    /// A build signed without the host group (KeychainService could not
    /// resolve it) keeps the pre-MR-1729 queries instead of failing sign-in.
    func testABuildWithoutTheHostGroupKeepsThePreviousBehaviour() {
        let device = DeviceKeychain()
        let app = ProcessKeychain(device, groups: [shared])
        let old = seedOlderBuild(app)
        XCTAssertEqual(HostOnlyKeychain.migrate(.refreshToken, placement: .unresolved, store: app), .deferred)
        XCTAssertEqual(HostOnlyKeychain.read(.refreshToken, placement: .unresolved, store: app), old[.refreshToken])

        let token = Data("v2-refresh".utf8)
        XCTAssertTrue(HostOnlyKeychain.write(token, .refreshToken, placement: .unresolved, store: app))
        XCTAssertEqual(HostOnlyKeychain.read(.refreshToken, placement: .unresolved, store: app), token)
    }

    // MARK: - What is host-only, and who is entitled to it

    func testEveryItemTheAuditNamedIsHostOnly() {
        let accounts = Set(HostKeychainItem.allCases.map(\.account))
        for name in ["access_token", "refresh_token", "user_email", "device_id",
                     "anonymous_id", "sign_in_method", "birdo_pq_v1_keypair"] {
            XCTAssertTrue(accounts.contains(name), name)
        }
        // None under the service the extension's shared secrets use.
        XCTAssertFalse(HostKeychainItem.allCases.contains { $0.service == "app.birdo.vpn.shared" })
    }

    /// The closure, at the entitlement level: the host group is listed by the
    /// app (iOS and macOS) and by neither PacketTunnel target. The shared group
    /// stays FIRST in the app's list, because it is the default group every
    /// older build's group-less write used.
    func testOnlyTheAppIsEntitledToTheHostGroup() throws {
        let sharedGroup = "$(AppIdentifierPrefix)app.birdo.vpn"
        let hostGroup = HostOnlyKeychain.hostGroup(forShared: sharedGroup)
        for (app, tunnel) in [("BirdoVPN", "PacketTunnel"), ("BirdoVPN-macOS", "PacketTunnel-macOS")] {
            XCTAssertEqual(try keychainGroups(app), [sharedGroup, hostGroup], app)
            XCTAssertEqual(try keychainGroups(tunnel), [sharedGroup], tunnel)
        }
    }

    private func keychainGroups(_ name: String) throws -> [String] {
        let bundle = Bundle(for: HostOnlyKeychainTests.self)
        let url = try XCTUnwrap(bundle.url(forResource: name, withExtension: "entitlements"),
                                "\(name).entitlements is not a resource of the test bundle (project.yml)")
        let plist = try PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil)
        let entitlements = try XCTUnwrap(plist as? [String: Any], name)
        return try XCTUnwrap(entitlements["keychain-access-groups"] as? [String], name)
    }
}

// MARK: - In-memory keychain

private let errSecMissingEntitlementStatus: Int32 = -34018
private let errSecInteractionNotAllowedStatus: Int32 = -25308

/// The device's keychain, shared by every process on it.
private final class DeviceKeychain {
    struct Key: Hashable {
        let group: String
        let service: String
        let account: String
    }

    var items: [Key: Data] = [:]

    func count(in group: String) -> Int {
        items.keys.filter { $0.group == group }.count
    }
}

/// One process's view of it. `groups` is its `keychain-access-groups`, the
/// first being the default.
private final class ProcessKeychain: KeychainGroupStore {
    let device: DeviceKeychain
    let groups: [String]
    /// Adds into these groups fail.
    var failAdds: Set<String> = []
    /// Adds into these groups report success and store nothing.
    var dropAdds: Set<String> = []
    /// Before the first unlock: nothing reads, adds or deletes.
    var locked = false

    init(_ device: DeviceKeychain, groups: [String]) {
        self.device = device
        self.groups = groups
    }

    private func searched(_ group: String?) -> [String] {
        guard let group else { return groups }
        return groups.contains(group) ? [group] : []
    }

    func lookup(service: String, account: String, group: String?) -> KeychainLookup {
        if locked { return .failed(errSecInteractionNotAllowedStatus) }
        if let group, !groups.contains(group) { return .failed(errSecMissingEntitlementStatus) }
        for candidate in searched(group) {
            let key = DeviceKeychain.Key(group: candidate, service: service, account: account)
            if let data = device.items[key] { return .found(data) }
        }
        return .notFound
    }

    func store(_ data: Data, service: String, account: String, group: String?) -> Bool {
        if locked { return false }
        // An add with no group lands in the default (first) group.
        guard let target = group ?? groups.first, groups.contains(target) else { return false }
        remove(service: service, account: account, group: group)
        if failAdds.contains(target) { return false }
        if !dropAdds.contains(target) {
            device.items[DeviceKeychain.Key(group: target, service: service, account: account)] = data
        }
        return true
    }

    func remove(service: String, account: String, group: String?) {
        if locked { return }
        for candidate in searched(group) {
            device.items[DeviceKeychain.Key(group: candidate, service: service, account: account)] = nil
        }
    }
}
