import XCTest

/// Owner item 38 — pins `ConsentRecord`, the versioned consent store.
///
/// The rule (Android `AppPreferences.hasAcceptedCurrentConsent` has the same
/// one): an acceptance of an OLDER disclosure does not count, so a user who
/// accepted the pre-29-Sep text sees the current screen once; accepting it
/// records the current version and the screen never comes back at launch.
final class ConsentRecordTests: XCTestCase {

    private var suiteName = ""
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "ConsentRecordTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    func testFreshInstallHasAcceptedNothing() {
        XCTAssertEqual(ConsentRecord.acceptedVersion(in: defaults), 0)
        XCTAssertFalse(ConsentRecord.hasAcceptedCurrent(in: defaults))
        XCTAssertEqual(RootRoute.decide(hasConsented: false,
                                        consentDeferred: ConsentRecord.isDeferred(in: defaults)),
                       .consent)
    }

    /// The migration: the pre-versioning Boolean is an acceptance of version
    /// 1, which no longer counts — so this user is shown the new screen.
    func testPreVersioningAcceptanceIsVersionOneAndIsAskedAgain() {
        defaults.set(true, forKey: ConsentRecord.acceptedKey)
        XCTAssertEqual(ConsentRecord.acceptedVersion(in: defaults), 1)
        XCTAssertFalse(ConsentRecord.hasAcceptedCurrent(in: defaults))
        XCTAssertEqual(RootRoute.decide(hasConsented: ConsentRecord.hasAcceptedCurrent(in: defaults),
                                        consentDeferred: ConsentRecord.isDeferred(in: defaults)),
                       .consent, "an old acceptance must route to the consent screen once")
    }

    /// ...and once they accept, never again.
    func testAcceptingRecordsTheCurrentVersionAndStaysAccepted() {
        defaults.set(true, forKey: ConsentRecord.acceptedKey)
        ConsentRecord.recordAcceptance(in: defaults, at: Date(timeIntervalSince1970: 1_000))
        XCTAssertEqual(ConsentRecord.acceptedVersion(in: defaults), ConsentRecord.currentVersion)
        XCTAssertTrue(ConsentRecord.hasAcceptedCurrent(in: defaults))
        XCTAssertTrue(defaults.bool(forKey: ConsentRecord.acceptedKey),
                      "the Boolean is still written, so an older build reads it")
        XCTAssertEqual(defaults.double(forKey: ConsentRecord.timestampKey), 1_000_000)
        XCTAssertFalse(ConsentRecord.isDeferred(in: defaults))
    }

    func testAcceptingClearsAnEarlierDeferral() {
        ConsentRecord.recordDeferral(in: defaults)
        ConsentRecord.recordAcceptance(in: defaults)
        XCTAssertFalse(ConsentRecord.isDeferred(in: defaults))
        XCTAssertTrue(ConsentRecord.hasAcceptedCurrent(in: defaults))
    }

    /// "Not now" on the re-consent screen still never dead-ends: the user lands
    /// in the shell and the screen does not come back at launch.
    func testDeferringWithdrawsAndRoutesToTheShell() {
        ConsentRecord.recordAcceptance(in: defaults)
        ConsentRecord.recordDeferral(in: defaults)
        XCTAssertFalse(ConsentRecord.hasAcceptedCurrent(in: defaults))
        XCTAssertTrue(ConsentRecord.isDeferred(in: defaults))
        XCTAssertNil(defaults.object(forKey: ConsentRecord.timestampKey))
        XCTAssertEqual(RootRoute.decide(hasConsented: false, consentDeferred: true), .shell)
    }

    /// A version recorded by a NEWER build counts as accepted here.
    func testNewerVersionCounts() {
        defaults.set(true, forKey: ConsentRecord.acceptedKey)
        defaults.set(ConsentRecord.currentVersion + 1, forKey: ConsentRecord.versionKey)
        XCTAssertTrue(ConsentRecord.hasAcceptedCurrent(in: defaults))
    }

    /// A version number without the Boolean is not an acceptance.
    func testVersionWithoutTheFlagIsNotAcceptance() {
        defaults.set(ConsentRecord.currentVersion, forKey: ConsentRecord.versionKey)
        XCTAssertFalse(ConsentRecord.hasAcceptedCurrent(in: defaults))
    }

    /// The screenshot harness passes `-gdpr_consent_version 2` as a launch
    /// argument, which arrives as the STRING "2".
    func testStringVersionFromLaunchArgumentsIsRead() {
        defaults.set(true, forKey: ConsentRecord.acceptedKey)
        defaults.set("2", forKey: ConsentRecord.versionKey)
        XCTAssertTrue(ConsentRecord.hasAcceptedCurrent(in: defaults))
    }

    func testCurrentVersionMatchesAndroid() {
        // Android: AppPreferences.CURRENT_CONSENT_VERSION = 2 (same text date).
        XCTAssertEqual(ConsentRecord.currentVersion, 2)
    }
}
