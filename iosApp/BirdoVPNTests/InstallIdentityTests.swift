import XCTest

/// MR-1726 — pins `InstallIdentity.checkOnLaunch`, the first-run sentinel that
/// makes the Apple device identity per install (Android parity).
///
/// The keychain outlives the app, so before this a reinstall picked up the
/// previous install's `device_id` and `anonymous_id`. The store here is an
/// in-memory stand-in for `KeychainService` (which conforms in the app) and
/// the defaults are a throwaway suite, so nothing touches a real keychain.
final class InstallIdentityTests: XCTestCase {

    private final class MemoryStore: InstallIdentityStore {
        var deviceId: String?
        var anonymousId: String?
        var failWrites = false
        var ignoreClears = false
        private(set) var writes = 0

        init(deviceId: String? = nil, anonymousId: String? = nil) {
            self.deviceId = deviceId
            self.anonymousId = anonymousId
        }

        func saveDeviceId(_ id: String) -> Bool {
            writes += 1
            guard !failWrites else { return false }
            deviceId = id
            return true
        }

        func clearAnonymousId() {
            if !ignoreClears { anonymousId = nil }
        }
    }

    private var suiteName = ""
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "InstallIdentityTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    private let previousDevice = "ios_previous-install-marker"
    private let previousAnonymous = "000000000000000000000001"
    private let freshDevice = "ios_fresh-install-marker"

    private func check(_ store: MemoryStore, protectedData: Bool = true) -> InstallIdentity.Outcome {
        InstallIdentity.checkOnLaunch(defaults: defaults, store: store,
                                      protectedDataAvailable: protectedData,
                                      mintDeviceId: { self.freshDevice })
    }

    // MARK: - Fresh install (no sentinel, no record of an earlier launch)

    func testAFreshInstallDiscardsAPreviousInstallsIdentity() {
        let store = MemoryStore(deviceId: previousDevice, anonymousId: previousAnonymous)
        XCTAssertEqual(check(store), .discardedPreviousInstall)
        XCTAssertEqual(store.deviceId, freshDevice, "the previous install's device id survived a reinstall")
        XCTAssertNil(store.anonymousId, "the previous install's anonymous account number survived a reinstall")
        XCTAssertTrue(defaults.bool(forKey: InstallIdentity.sentinelKey))
    }

    func testOnlyALeftoverAnonymousIdIsStillDiscarded() {
        let store = MemoryStore(anonymousId: previousAnonymous)
        XCTAssertEqual(check(store), .discardedPreviousInstall)
        XCTAssertNil(store.anonymousId)
        // No device id was left, so none is minted here: APIClient mints on
        // first use, exactly as on any fresh install.
        XCTAssertNil(store.deviceId)
        XCTAssertEqual(store.writes, 0)
    }

    func testACleanFreshInstallOnlySetsTheSentinel() {
        let store = MemoryStore()
        XCTAssertEqual(check(store), .freshInstall)
        XCTAssertNil(store.deviceId)
        XCTAssertEqual(store.writes, 0)
        XCTAssertTrue(defaults.bool(forKey: InstallIdentity.sentinelKey))
    }

    // MARK: - Later launches and upgrades keep the identity

    func testTheCheckRunsOncePerInstall() {
        let store = MemoryStore(deviceId: previousDevice)
        XCTAssertEqual(check(store), .discardedPreviousInstall)
        // A later launch, with the identity this install now owns.
        XCTAssertEqual(check(store), .alreadyChecked)
        XCTAssertEqual(store.deviceId, freshDevice)
        XCTAssertEqual(store.writes, 1)
    }

    func testAnUpgradeFromABuildWithoutTheSentinelKeepsItsIdentity() {
        // Android's migration rule: nobody's device row changes on upgrade. An
        // older build of THIS install wrote the consent record on its first
        // screen; that, not the sentinel, is what marks it as already run.
        for key in InstallIdentity.earlierLaunchKeys {
            let suite = "InstallIdentityTests.upgrade.\(UUID().uuidString)"
            guard let upgraded = UserDefaults(suiteName: suite) else { return XCTFail("no suite") }
            defer { upgraded.removePersistentDomain(forName: suite) }
            upgraded.set(key == ConsentRecord.versionKey ? 2 : true, forKey: key)
            let store = MemoryStore(deviceId: previousDevice, anonymousId: previousAnonymous)
            let outcome = InstallIdentity.checkOnLaunch(defaults: upgraded, store: store,
                                                        protectedDataAvailable: true,
                                                        mintDeviceId: { self.freshDevice })
            XCTAssertEqual(outcome, .upgradeKeptIdentity, key)
            XCTAssertEqual(store.deviceId, previousDevice, "an upgrade rotated the device id (\(key))")
            XCTAssertEqual(store.anonymousId, previousAnonymous, key)
            XCTAssertTrue(upgraded.bool(forKey: InstallIdentity.sentinelKey), key)
        }
        XCTAssertEqual(Set(InstallIdentity.earlierLaunchKeys),
                       [ConsentRecord.acceptedKey, ConsentRecord.versionKey, ConsentRecord.deferredKey])
    }

    func testADeferredConsentStillCountsAsAnEarlierLaunch() {
        // "Not now" writes accepted=false — the key is PRESENT with a false
        // value, which must still read as "this install has run".
        ConsentRecord.recordDeferral(in: defaults)
        let store = MemoryStore(deviceId: previousDevice)
        XCTAssertEqual(check(store), .upgradeKeptIdentity)
        XCTAssertEqual(store.deviceId, previousDevice)
    }

    // MARK: - Nothing is decided when it cannot be done safely

    func testNothingHappensBeforeTheFirstUnlock() {
        let store = MemoryStore(deviceId: previousDevice, anonymousId: previousAnonymous)
        XCTAssertEqual(check(store, protectedData: false), .retryNextLaunch)
        XCTAssertEqual(store.deviceId, previousDevice)
        XCTAssertEqual(store.anonymousId, previousAnonymous)
        XCTAssertFalse(defaults.bool(forKey: InstallIdentity.sentinelKey))
    }

    func testAFailedKeychainWriteRetriesInsteadOfAdoptingTheOldId() {
        let store = MemoryStore(deviceId: previousDevice)
        store.failWrites = true
        XCTAssertEqual(check(store), .retryNextLaunch)
        XCTAssertFalse(defaults.bool(forKey: InstallIdentity.sentinelKey),
                       "the sentinel was set over a device id that was never replaced")
        store.failWrites = false
        XCTAssertEqual(check(store), .discardedPreviousInstall)
        XCTAssertEqual(store.deviceId, freshDevice)
    }

    func testAnAnonymousIdThatWillNotClearRetries() {
        let store = MemoryStore(anonymousId: previousAnonymous)
        store.ignoreClears = true
        XCTAssertEqual(check(store), .retryNextLaunch)
        XCTAssertFalse(defaults.bool(forKey: InstallIdentity.sentinelKey))
    }

    // MARK: - The minted id

    func testTheMintedIdIsRandomAndKeepsTheIosForm() {
        let a = InstallIdentity.mintDeviceId(), b = InstallIdentity.mintDeviceId()
        XCTAssertNotEqual(a, b)
        XCTAssertTrue(a.hasPrefix("ios_"))
        XCTAssertEqual(a.count, 4 + 32)
        XCTAssertNil(a.dropFirst(4).first { !$0.isHexDigit })
    }
}
