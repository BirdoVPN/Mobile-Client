import XCTest

/// MR-1726 — pins `InstallIdentity.checkOnLaunch`, the first-run sentinel that
/// makes the Apple session and device identity per install (Android parity),
/// and `LocalSignOut.wipe`, what a sign-out removes.
///
/// The keychain outlives the app, so before this a reinstall picked up the
/// previous install's session, `device_id` and `anonymous_id`. The store here
/// is an in-memory stand-in for `KeychainService` (which conforms in the app)
/// and the defaults are a throwaway suite, so nothing touches a real keychain.
final class InstallIdentityTests: XCTestCase {

    private final class MemoryStore: InstallIdentityStore {
        var deviceId: String?
        var anonymousId: String?
        var accessToken: String?
        var refreshToken: String?
        var failWrites = false
        var ignoreClears = false
        var ignoreSessionClears = false
        private(set) var writes = 0

        init(deviceId: String? = nil, anonymousId: String? = nil,
             accessToken: String? = nil, refreshToken: String? = nil) {
            self.deviceId = deviceId
            self.anonymousId = anonymousId
            self.accessToken = accessToken
            self.refreshToken = refreshToken
        }

        var hasSession: Bool { accessToken != nil || refreshToken != nil }

        func clearSession() {
            guard !ignoreSessionClears else { return }
            accessToken = nil
            refreshToken = nil
        }

        /// Delete-then-add, as `KeychainService.write` is: a failed write
        /// leaves NO id, not the old one.
        func saveDeviceId(_ id: String) -> Bool {
            writes += 1
            deviceId = nil
            guard !failWrites else { return false }
            deviceId = id
            return true
        }

        /// Delete-then-add, like `saveDeviceId`.
        func saveAnonymousId(_ id: String) -> Bool {
            writes += 1
            anonymousId = nil
            guard !failWrites else { return false }
            anonymousId = id
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
    private let previousAccess = "access-aaaa"
    private let previousRefresh = "refresh-aaaa"

    /// A previous install that was signed in: session, device id, account number.
    private func signedInLeftovers() -> MemoryStore {
        MemoryStore(deviceId: previousDevice, anonymousId: previousAnonymous,
                    accessToken: previousAccess, refreshToken: previousRefresh)
    }

    private func check(_ store: MemoryStore, protectedData: Bool = true) -> InstallIdentity.Outcome {
        InstallIdentity.checkOnLaunch(defaults: defaults, store: store,
                                      protectedDataAvailable: protectedData,
                                      mintDeviceId: { self.freshDevice })
    }

    // MARK: - Fresh install (no sentinel, no record of an earlier launch)

    func testAFreshInstallStartsSignedOutWithANewDeviceId() {
        // Owner, 2026-10-14: "sign out on reinstall", as Android's does.
        let store = signedInLeftovers()
        XCTAssertEqual(check(store), .discardedPreviousInstall)
        XCTAssertFalse(store.hasSession, "a reinstall inherited the previous install's session")
        XCTAssertNil(store.accessToken)
        XCTAssertNil(store.refreshToken)
        XCTAssertEqual(store.deviceId, freshDevice)
        XCTAssertNil(store.anonymousId)
        XCTAssertTrue(defaults.bool(forKey: InstallIdentity.sentinelKey))
    }

    func testOnlyALeftoverSessionIsStillSignedOut() {
        // A refresh token alone is a live session (AuthViewModel's cold start
        // treats either token as one).
        let store = MemoryStore(refreshToken: previousRefresh)
        XCTAssertEqual(check(store), .discardedPreviousInstall)
        XCTAssertFalse(store.hasSession)
        XCTAssertEqual(store.writes, 0, "no device id was left, so none is minted here")
    }

    func testASessionThatWillNotClearChangesNothingElseAndRetries() {
        // A live session must never be left running under a new device id.
        let store = signedInLeftovers()
        store.ignoreSessionClears = true
        XCTAssertEqual(check(store), .cleanupIncomplete)
        XCTAssertEqual(store.deviceId, previousDevice)
        XCTAssertEqual(store.anonymousId, previousAnonymous)
        XCTAssertEqual(store.writes, 0)
        XCTAssertFalse(defaults.bool(forKey: InstallIdentity.sentinelKey))
    }

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
            let store = signedInLeftovers()
            let outcome = InstallIdentity.checkOnLaunch(defaults: upgraded, store: store,
                                                        protectedDataAvailable: true,
                                                        mintDeviceId: { self.freshDevice })
            XCTAssertEqual(outcome, .upgradeKeptIdentity, key)
            XCTAssertEqual(store.accessToken, previousAccess, "an upgrade signed the user out (\(key))")
            XCTAssertEqual(store.refreshToken, previousRefresh, key)
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
        let store = signedInLeftovers()
        XCTAssertEqual(check(store, protectedData: false), .retryNextLaunch)
        XCTAssertTrue(store.hasSession, "signed out before the first unlock, on data that could not be read")
        XCTAssertEqual(store.deviceId, previousDevice)
        XCTAssertEqual(store.anonymousId, previousAnonymous)
        XCTAssertFalse(defaults.bool(forKey: InstallIdentity.sentinelKey))
    }

    func testAFailedDeviceIdWriteNeverKeepsTheOldIdAndIsFinishedLater() {
        let store = MemoryStore(deviceId: previousDevice, anonymousId: previousAnonymous)
        store.failWrites = true
        XCTAssertEqual(check(store), .cleanupIncomplete)
        // Delete-then-add: nothing of the previous install's id is left, and
        // APIClient would mint a fresh one on first use.
        XCTAssertNil(store.deviceId)
        XCTAssertEqual(store.anonymousId, previousAnonymous, "the clean-up stops at the failure")
        XCTAssertFalse(defaults.bool(forKey: InstallIdentity.sentinelKey))
        XCTAssertTrue(defaults.bool(forKey: InstallIdentity.pendingKey))
        store.failWrites = false
        XCTAssertEqual(check(store), .discardedPreviousInstall)
        XCTAssertNil(store.anonymousId)
        XCTAssertNotEqual(store.deviceId, previousDevice)
        XCTAssertFalse(defaults.bool(forKey: InstallIdentity.pendingKey))
    }

    func testAnAnonymousIdThatWillNotClearIsRetried() {
        let store = MemoryStore(anonymousId: previousAnonymous)
        store.ignoreClears = true
        XCTAssertEqual(check(store), .cleanupIncomplete)
        XCTAssertFalse(defaults.bool(forKey: InstallIdentity.sentinelKey))
    }

    // MARK: - An unfinished clean-up survives the consent screen (vpn-d3 M1)

    func testAnUnfinishedCleanUpIsFinishedEvenAfterConsentIsGiven() {
        // The clean-up fails, the user answers the consent screen in that same
        // launch, and the next launch must not read that consent as an upgrade
        // — which kept the previous install's session and identity for good.
        let store = signedInLeftovers()
        store.ignoreSessionClears = true
        let first = check(store)
        XCTAssertEqual(first, .cleanupIncomplete)
        XCTAssertTrue(first.storedSessionUnusable,
                      "the first launch would run signed in as the previous install's account")
        XCTAssertTrue(defaults.bool(forKey: InstallIdentity.pendingKey))

        ConsentRecord.recordAcceptance(in: defaults)
        store.ignoreSessionClears = false

        XCTAssertEqual(check(store), .discardedPreviousInstall)
        XCTAssertFalse(store.hasSession)
        XCTAssertEqual(store.deviceId, freshDevice)
        XCTAssertNil(store.anonymousId)
        XCTAssertTrue(defaults.bool(forKey: InstallIdentity.sentinelKey))
        XCTAssertFalse(defaults.bool(forKey: InstallIdentity.pendingKey))
        XCTAssertEqual(check(store), .alreadyChecked)
    }

    func testADeviceIdFailureFollowedByConsentIsStillFinished() {
        let store = MemoryStore(deviceId: previousDevice, anonymousId: previousAnonymous)
        store.failWrites = true
        XCTAssertEqual(check(store), .cleanupIncomplete)
        ConsentRecord.recordDeferral(in: defaults)
        store.failWrites = false
        XCTAssertEqual(check(store), .discardedPreviousInstall,
                       "a \"Not now\" given after a failed clean-up was read as an upgrade")
        XCTAssertNil(store.anonymousId)
    }

    func testOnlyAnUnfinishedCleanUpMakesTheStoredSessionUnusable() {
        let others: [InstallIdentity.Outcome] = [.alreadyChecked, .upgradeKeptIdentity, .freshInstall,
                                                 .discardedPreviousInstall, .retryNextLaunch]
        for outcome in others {
            XCTAssertFalse(outcome.storedSessionUnusable, "\(outcome)")
        }
        XCTAssertTrue(InstallIdentity.Outcome.cleanupIncomplete.storedSessionUnusable)
    }

    func testAnUpgradeIsNeverMarkedPending() {
        ConsentRecord.recordAcceptance(in: defaults)
        let store = signedInLeftovers()
        XCTAssertEqual(check(store), .upgradeKeptIdentity)
        XCTAssertFalse(defaults.bool(forKey: InstallIdentity.pendingKey))
        XCTAssertTrue(store.hasSession)
    }

    // MARK: - Sign-out (LocalSignOut.wipe)

    func testSignOutDropsTheSessionAndTheAccountNumberAndKeepsTheDeviceId() {
        // Android's TokenManager.clearAll() drops the pending anonymous id too;
        // iOS kept it, and the anonymous-create path could show it as a NEW
        // account's number.
        let store = signedInLeftovers()
        LocalSignOut.wipe(store)
        XCTAssertFalse(store.hasSession)
        XCTAssertNil(store.anonymousId, "the account number outlived the sign-out")
        XCTAssertEqual(store.deviceId, previousDevice, "sign-out rotated the device id (Android keeps it)")
        XCTAssertEqual(store.writes, 0)
    }

    // MARK: - #485 review: no number outlives its account (AnonymousNumberRecord)

    private let newAnonymous = "000000000000000000000002"

    private func purge(_ store: MemoryStore, signedIn: Bool,
                       protectedDataAvailable: Bool = true) -> AnonymousNumberRecord.PurgeOutcome {
        AnonymousNumberRecord.purgeStaleOnLaunch(defaults: defaults, store: store, signedIn: signedIn,
                                                 protectedDataAvailable: protectedDataAvailable)
    }

    func testAnAnonymousSignInWithNoNumberKeepsNoOldNumber() {
        // A registration that did not return the new number: the old code
        // kept the previous account's, and the save step showed it as new.
        let store = MemoryStore(anonymousId: previousAnonymous)
        AnonymousNumberRecord.recordSignIn(anonymous: true, number: nil, in: store)
        XCTAssertNil(store.anonymousId, "a previous account's number survived into a new anonymous account")
    }

    func testAnAnonymousSignInRecordsThisAccountsNumber() {
        let store = MemoryStore(anonymousId: previousAnonymous)
        AnonymousNumberRecord.recordSignIn(anonymous: true, number: newAnonymous, in: store)
        XCTAssertEqual(store.anonymousId, newAnonymous)
    }

    func testAnyOtherSignInDropsTheNumber() {
        let store = MemoryStore(anonymousId: previousAnonymous)
        AnonymousNumberRecord.recordSignIn(anonymous: false, number: newAnonymous, in: store)
        XCTAssertNil(store.anonymousId)
    }

    func testTheProfileCorrectsTheStoredNumber() {
        let store = MemoryStore(anonymousId: previousAnonymous)
        AnonymousNumberRecord.recordProfile(isAnonymous: true, number: newAnonymous, in: store)
        XCTAssertEqual(store.anonymousId, newAnonymous)
        // An anonymous profile with no number leaves this sign-in's.
        AnonymousNumberRecord.recordProfile(isAnonymous: true, number: nil, in: store)
        XCTAssertEqual(store.anonymousId, newAnonymous)
        // A profile that is not anonymous keeps none.
        AnonymousNumberRecord.recordProfile(isAnonymous: false, number: nil, in: store)
        XCTAssertNil(store.anonymousId)
    }

    func testASignedOutLaunchPurgesAnOlderBuildsLeftoverOnce() {
        // Signed out on a build that kept anonymous_id after a sign-out.
        let store = MemoryStore(deviceId: previousDevice, anonymousId: previousAnonymous)
        XCTAssertEqual(purge(store, signedIn: false), .purged)
        XCTAssertNil(store.anonymousId, "a signed-out device still holds a previous account's number")
        XCTAssertEqual(store.deviceId, previousDevice, "the purge touched the device id")
        XCTAssertTrue(defaults.bool(forKey: AnonymousNumberRecord.stalePurgeDoneKey))
        // One-time: a later launch does nothing at all.
        store.anonymousId = newAnonymous
        XCTAssertEqual(purge(store, signedIn: false), .alreadyDone)
        XCTAssertEqual(store.anonymousId, newAnonymous)
    }

    func testASignedInLaunchKeepsTheSessionsNumberAndWaits() {
        let store = signedInLeftovers()
        XCTAssertEqual(purge(store, signedIn: true), .signedIn)
        XCTAssertEqual(store.anonymousId, previousAnonymous, "the purge removed a signed-in session's number")
        XCTAssertFalse(defaults.bool(forKey: AnonymousNumberRecord.stalePurgeDoneKey))
        // The first signed-out launch then does it.
        XCTAssertEqual(purge(store, signedIn: false), .purged)
        XCTAssertNil(store.anonymousId)
    }

    func testThePurgeNeverMarksItselfDoneOnWhatItCouldNotRead() {
        let store = MemoryStore(anonymousId: previousAnonymous)
        XCTAssertEqual(purge(store, signedIn: false, protectedDataAvailable: false), .retryNextLaunch)
        XCTAssertFalse(defaults.bool(forKey: AnonymousNumberRecord.stalePurgeDoneKey))
        store.ignoreClears = true
        XCTAssertEqual(purge(store, signedIn: false), .retryNextLaunch)
        XCTAssertFalse(defaults.bool(forKey: AnonymousNumberRecord.stalePurgeDoneKey))
        store.ignoreClears = false
        XCTAssertEqual(purge(store, signedIn: false), .purged)
    }

    func testASignedOutLaunchWithNothingStoredIsDone() {
        let store = MemoryStore(deviceId: previousDevice)
        XCTAssertEqual(purge(store, signedIn: false), .nothingStored)
        XCTAssertTrue(defaults.bool(forKey: AnonymousNumberRecord.stalePurgeDoneKey))
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
