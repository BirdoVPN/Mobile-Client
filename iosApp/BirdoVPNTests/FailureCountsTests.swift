import XCTest

/// MR-2287 (A-M37) — pins `FailureCounts`, the opt-in, counts-only failure
/// diagnostics, compiled directly into this un-hosted bundle.
///
/// The consent model is Android's crash-report choice
/// (`AppPreferences.crashReportsEnabled`, `CrashReporting`): off by default,
/// nothing captured while off, opting out discards what is held, and nothing
/// is ever handed to a sender without consent. And what a report carries is a
/// code and a number: no account, device, server, address or time.
final class FailureCountsTests: XCTestCase {

    private var suiteName = ""
    private var defaults: UserDefaults!
    private var store: FailureCounts!

    override func setUp() {
        super.setUp()
        suiteName = "FailureCountsTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        store = FailureCounts(defaults: defaults, appVersion: "1.4.99")
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        store = nil
        defaults = nil
        super.tearDown()
    }

    private func report() -> FailureCountsReport? {
        store.takeReport()
    }

    // MARK: - The consent gate

    func testOffByDefault() {
        XCTAssertFalse(store.isEnabled)
        XCTAssertNil(defaults.object(forKey: FailureCounts.enabledKey))
    }

    /// The one instance the app uses: off in a process where nobody opted in.
    /// A default-on `shared` (say, an initialiser that opts itself in) fails
    /// here. Read only: this process's standard defaults are never written.
    func testTheSharedStoreIsOffByDefault() {
        XCTAssertFalse(FailureCounts.shared.isEnabled)
        XCTAssertNil(FailureCounts.shared.takeReport())
    }

    /// THE gate: without the user's opt-in nothing is counted, nothing is
    /// stored, and there is never a report to send.
    func testNothingIsCountedOrSentWithoutOptIn() {
        for failure in DialFailure.allCases { store.record(failure) }
        store.record(.tunnelNeverHandshook)
        XCTAssertTrue(store.counts.isEmpty)
        XCTAssertNil(defaults.object(forKey: FailureCounts.countsKey), "a count was stored without consent")
        XCTAssertNil(report(), "a report was produced without consent")
    }

    /// Android drops an event that predates consent. Here none can exist:
    /// what happened before the opt-in was never recorded.
    func testNothingFromBeforeTheOptInIsReported() {
        store.record(.pqNonceInvalid)
        store.setEnabled(true)
        XCTAssertNil(report())
        store.record(.setupRefused)
        XCTAssertEqual(report()?.counts, ["setup_refused": 1])
    }

    func testOptedInFailuresAreCountedByReason() {
        store.setEnabled(true)
        store.record(.tunnelNeverHandshook)
        store.record(.tunnelNeverHandshook)
        store.record(.pqDeriveFailed)
        XCTAssertEqual(store.counts, [.tunnelNeverHandshook: 2, .pqDeriveFailed: 1])
    }

    /// Opting out discards everything held but not yet sent, as Android
    /// discards its unsent outbox, and the next opt-in starts from zero.
    func testOptingOutDiscardsEverythingHeld() {
        store.setEnabled(true)
        store.record(.keychainUnavailable)
        store.setEnabled(false)
        XCTAssertFalse(store.isEnabled)
        XCTAssertNil(defaults.object(forKey: FailureCounts.countsKey))
        XCTAssertNil(defaults.object(forKey: FailureCounts.versionKey))
        XCTAssertNil(report())
        store.record(.keychainUnavailable)
        store.setEnabled(true)
        XCTAssertNil(report(), "a count recorded while opted out survived into the next opt-in")
    }

    /// A count stored by an earlier opt-in that was never discarded (say, a
    /// value restored from a backup with the choice off) is still not sent.
    func testAStrayCountIsNotSentWhileOff() {
        defaults.set(["setup_refused": 3], forKey: FailureCounts.countsKey)
        defaults.set("1.4.99", forKey: FailureCounts.versionKey)
        XCTAssertTrue(store.counts.isEmpty)
        XCTAssertNil(report())
    }

    // MARK: - The report

    func testAReportCarriesTheCountsAndClearsThem() {
        store.setEnabled(true)
        store.record(.pqKeygenFailed)
        store.record(.tunnelNeverHandshook)
        store.record(.tunnelNeverHandshook)
        XCTAssertEqual(report(), FailureCountsReport(schema: 1, platform: FailureCounts.platform, appVersion: "1.4.99",
                                                     counts: ["pq_keygen_failed": 1, "tunnel_never_handshook": 2]))
        XCTAssertTrue(store.counts.isEmpty)
        XCTAssertNil(defaults.object(forKey: FailureCounts.countsKey))
        XCTAssertNil(defaults.object(forKey: FailureCounts.versionKey))
        XCTAssertNil(report(), "the same counts were handed out twice")
        XCTAssertTrue(store.isEnabled, "taking a report is not an opt-out")
    }

    /// The wire shape, as a sender would encode it: four keys, and every
    /// count keyed by a known code. Nothing else has anywhere to go.
    func testTheReportHoldsOnlyCodesAndCounts() throws {
        store.setEnabled(true)
        for failure in DialFailure.allCases { store.record(failure) }
        let data = try JSONEncoder().encode(try XCTUnwrap(report()))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["schema", "platform", "appVersion", "counts"])
        let counts = try XCTUnwrap(json["counts"] as? [String: Int])
        XCTAssertEqual(Set(counts.keys), Set(DialFailure.allCases.map(\.rawValue)))
        XCTAssertTrue(counts.values.allSatisfy { $0 == 1 })
        XCTAssertEqual(json["platform"] as? String, FailureCounts.platform)
        XCTAssertEqual(json["appVersion"] as? String, "1.4.99")
    }

    // MARK: - The report's values are this build's, not a caller's (L2)

    func testThePlatformIsTheBuildsOwn() {
        #if os(macOS)
        XCTAssertEqual(FailureCounts.platform, "macos")
        #else
        XCTAssertEqual(FailureCounts.platform, "ios")
        #endif
    }

    /// Only a plain x.y.z is reported. A build string with anything appended
    /// (a build number, an install or device tag) becomes the sentinel.
    func testOnlyAPlainVersionIsReported() {
        XCTAssertEqual(FailureCounts.reportableVersion("1.4.41"), "1.4.41")
        for junk in [nil, "", "1.4", "1.4.41 (10441)", "1.4.41-install-3f2a", "iPhone of Alice", "1.4.41.7", "12345.1.1"] {
            XCTAssertEqual(FailureCounts.reportableVersion(junk), FailureCounts.unknownVersion, junk ?? "nil")
        }
        let tagged = FailureCounts(defaults: defaults, appVersion: "1.4.99 (device-xyz-123)")
        tagged.setEnabled(true)
        tagged.record(.setupRefused)
        XCTAssertEqual(tagged.takeReport()?.appVersion, FailureCounts.unknownVersion)
    }

    /// `record` caps, and so does every read: a stored value past the cap
    /// (restored from a backup, or written by hand) is never reported as is,
    /// and a zero or negative one is dropped.
    func testAStoredCountIsCappedOnRead() {
        store.setEnabled(true)
        defaults.set(["setup_refused": 5000, "keychain_unavailable": 0, "pq_derive_failed": -3],
                     forKey: FailureCounts.countsKey)
        defaults.set("1.4.99", forKey: FailureCounts.versionKey)
        XCTAssertEqual(store.counts, [.setupRefused: FailureCounts.maxCount])
        XCTAssertEqual(report()?.counts, ["setup_refused": FailureCounts.maxCount])
    }

    // MARK: - One app version at a time (L3)

    /// THE regression: counts recorded by 1.4.40 were reported as 1.4.41's
    /// after an update. They are discarded instead.
    func testCountsFromAnotherVersionAreNeverReportedUnderThisOne() {
        let old = FailureCounts(defaults: defaults, appVersion: "1.4.40")
        old.setEnabled(true)
        old.record(.tunnelNeverHandshook)
        old.record(.tunnelNeverHandshook)

        let updated = FailureCounts(defaults: defaults, appVersion: "1.4.41")
        XCTAssertTrue(updated.isEnabled, "an update is not an opt-out")
        XCTAssertTrue(updated.counts.isEmpty)
        XCTAssertNil(updated.takeReport())
        XCTAssertNil(defaults.object(forKey: FailureCounts.countsKey), "the old version's counts were kept")
    }

    func testAnUpdateStartsTheCountAgain() {
        let old = FailureCounts(defaults: defaults, appVersion: "1.4.40")
        old.setEnabled(true)
        old.record(.setupRefused)
        old.record(.pqNonceInvalid)

        let updated = FailureCounts(defaults: defaults, appVersion: "1.4.41")
        updated.record(.setupRefused)
        XCTAssertEqual(updated.counts, [.setupRefused: 1])
        XCTAssertEqual(updated.takeReport(),
                       FailureCountsReport(schema: 1, platform: FailureCounts.platform, appVersion: "1.4.41",
                                           counts: ["setup_refused": 1]))
    }

    /// A count with no version on record (a store written before versions
    /// were kept) is never reported either.
    func testACountWithNoVersionIsNotReported() {
        store.setEnabled(true)
        defaults.set(["setup_refused": 2], forKey: FailureCounts.countsKey)
        XCTAssertTrue(store.counts.isEmpty)
        XCTAssertNil(report())
    }

    /// A code this build does not know (written by a newer build, or by
    /// hand) is never sent.
    func testUnknownCodesAreDropped() {
        store.setEnabled(true)
        defaults.set(["a.b.c.d": 1, "setup_refused": 2], forKey: FailureCounts.countsKey)
        defaults.set("1.4.99", forKey: FailureCounts.versionKey)
        XCTAssertEqual(report()?.counts, ["setup_refused": 2])
    }

    func testACountStopsAtTheCap() {
        store.setEnabled(true)
        for _ in 0..<(FailureCounts.maxCount + 5) { store.record(.setupRefused) }
        XCTAssertEqual(store.counts[.setupRefused], FailureCounts.maxCount)
    }

    // MARK: - The reasons

    /// Every reason is a fixed lower_snake literal: no digit run, no dot, no
    /// `@`, nothing that could be an address, a number or a host.
    func testEveryReasonIsAFixedSafeLiteral() {
        let codes = DialFailure.allCases.map(\.rawValue)
        XCTAssertEqual(Set(codes).count, codes.count, "two reasons share a code")
        for code in codes {
            XCTAssertNotNil(code.range(of: "^[a-z_]{4,40}$", options: .regularExpression),
                            "\(code) is not a short lower_snake literal")
        }
        XCTAssertTrue(codes.contains("tunnel_never_handshook"), "the A-M37 case itself is not a reason")
    }
}
