import XCTest

/// MR-2199, the iOS and macOS half of birdo-web #808: an account with no address
/// on file gets no e-mail about a terms change, so `GET /auth/me` names the
/// notices that apply and Home shows them until their effective date.
///
/// What is pinned here is the pure part, in `AuthWire.swift`: the decode (no
/// shape of `termsNotices` may fail the profile, and a bad entry must not hide a
/// good one), the date rule and the link rule. These are the cases of Android's
/// TermsNoticesTest. That `applyProfile` carries the notices to Home, and that
/// Home drops them with the app left open, cannot be asserted from this bundle:
/// the JVM guard `TermsNoticeWiringTest` reads the source for those.
final class TermsNoticeTests: XCTestCase {

    private static let t0 = ISO8601DateFormatter().date(from: "2026-11-05T12:00:00Z")!
    private static let day: TimeInterval = 86_400

    private static let goodJSON = #"{"version":"2026-10-30","text":"We have changed our Terms.","#
        + #""effectiveAt":"2026-12-05T00:00:00.000Z","path":"/legal/changes"}"#

    private func profile(_ field: String = "") throws -> UserProfile {
        let body = #"{"id":"u1","email":"a@b.com""# + field + "}"
        return try JSONDecoder().decode(UserProfile.self, from: Data(body.utf8))
    }

    private func notices(_ value: String) throws -> [TermsNotice] {
        try profile(#","termsNotices":"# + value).termsNotices
    }

    private func at(_ date: Date) -> String {
        ISO8601DateFormatter().string(from: date)
    }

    private func notice(version: String = "2026-10-30",
                        text: String = "We have changed our Terms of Service.",
                        effectiveAt: String? = nil,
                        path: String? = "/legal/changes") -> TermsNotice {
        TermsNotice(version: version, text: text,
                    effectiveAt: effectiveAt ?? at(Self.t0.addingTimeInterval(30 * Self.day)), path: path)
    }

    // MARK: - Decoding

    func testEveryEntryIsReadInTheOrderSentWithItsTextAsSent() throws {
        let second = #"{"version":"older","text":"  Second <b>one</b>.  ","#
            + #""effectiveAt":"2026-12-06T00:00:00.000Z","path":"/legal/changes"}"#
        let read = try notices("[\(Self.goodJSON),\(second)]")
        XCTAssertEqual(read.map(\.version), ["2026-10-30", "older"])
        XCTAssertEqual(read[0].text, "We have changed our Terms.")
        XCTAssertEqual(read[0].effectiveAt, "2026-12-05T00:00:00.000Z")
        XCTAssertEqual(read[0].path, "/legal/changes")
        XCTAssertEqual(read[1].text, "  Second <b>one</b>.  ")
    }

    func testNoKeyMeansNoNoticeAndTheProfileIsWhatItWas() throws {
        let user = try profile()
        XCTAssertEqual(user.termsNotices, [])
        XCTAssertEqual(user.id, "u1")
        XCTAssertEqual(user.email, "a@b.com")
    }

    func testNullOrAnyShapeButAListShowsNothingAndKeepsTheProfile() throws {
        for bad in ["null", #""soon""#, "42", "true", "{}", Self.goodJSON, "[[]]"] {
            let user = try profile(#","termsNotices":"# + bad)
            XCTAssertEqual(user.termsNotices, [], bad)
            XCTAssertEqual(user.id, "u1", bad)
        }
    }

    func testABadEntryDoesNotHideAGoodOne() throws {
        let read = try notices(
            #"[{},"soon",42,null,[],"# + Self.goodJSON + #",{"version":"v"},"#
                + #"{"version":1,"text":"t","effectiveAt":"e"},"#
                + #"{"version":"v","text":null,"effectiveAt":"e"},"#
                + #"{"version":"v","text":"t","effectiveAt":5}]"#)
        XCTAssertEqual(read.map(\.version), ["2026-10-30"])
    }

    func testAPathThatIsNotAStringCostsTheLinkNeverTheText() throws {
        for path in ["null", "42", "{}", "[]", "true"] {
            let read = try notices(#"[{"version":"v","text":"t","effectiveAt":"e","path":"# + path + "}]")
            XCTAssertEqual(read.map(\.text), ["t"], path)
            XCTAssertNil(read[0].path, path)
        }
        XCTAssertNil(try notices(#"[{"version":"v","text":"t","effectiveAt":"e"}]"#)[0].path)
    }

    // MARK: - What is shown

    func testShownUpToTheLastMomentBeforeEffectiveAtAndNotAtOrAfterIt() {
        let end = Self.t0.addingTimeInterval(Self.day)
        let one = [notice(effectiveAt: at(end))]
        XCTAssertEqual(TermsNotice.live(one, at: end.addingTimeInterval(-0.001)), one)
        XCTAssertEqual(TermsNotice.live(one, at: end), [])
        XCTAssertEqual(TermsNotice.live(one, at: end.addingTimeInterval(0.001)), [])
    }

    func testEachEntryGoesAtItsOwnDateAndTheRestKeepTheServersOrder() {
        let later = notice(version: "later", effectiveAt: at(Self.t0.addingTimeInterval(2 * Self.day)))
        let sooner = notice(version: "sooner", effectiveAt: at(Self.t0.addingTimeInterval(Self.day)))
        XCTAssertEqual(TermsNotice.live([later, sooner], at: Self.t0), [later, sooner])
        XCTAssertEqual(TermsNotice.live([later, sooner], at: Self.t0.addingTimeInterval(Self.day)), [later])
    }

    func testABadEffectiveAtIsNotShown() {
        let bad = ["", "soon", "2099", "5 Dec 2099", "2099-01-01", "2099-01-01T00:00:00", "2099-01-01T00:00Z",
                   " 2099-01-01T00:00:00Z", "2099-13-01T00:00:00Z", "2099-01-01 00:00:00Z"]
        for date in bad {
            XCTAssertEqual(TermsNotice.live([notice(effectiveAt: date)], at: Self.t0), [], date)
        }
    }

    func testABadDateDoesNotTakeAGoodEntryWithIt() {
        let good = notice(version: "good")
        XCTAssertEqual(TermsNotice.live([notice(effectiveAt: "soon"), good], at: Self.t0), [good])
    }

    func testEveryFullTimestampWithAZoneIsReadToTheSameInstant() {
        let end = ISO8601DateFormatter().date(from: "2099-01-01T00:00:00Z")
        for date in ["2099-01-01T00:00:00Z", "2099-01-01T00:00:00.000Z", "2099-01-01T01:00:00+01:00"] {
            XCTAssertEqual(notice(effectiveAt: date).endDate, end, date)
        }
    }

    func testABlankTextIsNotShown() {
        for text in ["", "   ", "\n\t"] {
            XCTAssertEqual(TermsNotice.live([notice(text: text)], at: Self.t0), [], text)
        }
    }

    func testTheSentenceIsShownWholePastAnyLimitTheErrorBannersUse() {
        // The 2026-10-30 notice is 237 characters, and it may carry anything.
        let long = "We have changed <our> Terms: " + String(repeating: "x", count: 1_000)
        XCTAssertEqual(TermsNotice.live([notice(text: long)], at: Self.t0).first?.text, long)
    }

    // MARK: - The link

    func testSeeEveryChangeOpensThePathTheServerNamedOnThisSiteOnly() {
        XCTAssertEqual(notice().link?.absoluteString, "https://birdo.app/legal/changes")
        let refused: [String?] = [nil, "", "legal/changes", "//evil.example/x", "https://evil.example/x",
                                  "/\\evil", "/a b"]
        for path in refused {
            XCTAssertNil(notice(path: path).link, String(describing: path))
        }
    }

    func testARefusedPathLeavesTheTextAndTheOtherEntrysLink() {
        let refused = notice(version: "refused", path: "https://evil.example/x")
        let fine = notice(version: "fine")
        let shown = TermsNotice.live([refused, fine], at: Self.t0)
        XCTAssertEqual(shown.map(\.version), ["refused", "fine"])
        XCTAssertEqual(shown.map(\.link?.absoluteString), [nil, "https://birdo.app/legal/changes"])
    }

    // MARK: - With the app left open

    func testTheClockIsReadOnceAMinuteAndExactlyAtTheDateWhenThatIsSooner() throws {
        let one = [notice(effectiveAt: at(Self.t0.addingTimeInterval(30 * Self.day)))]
        XCTAssertEqual(TermsNotice.nextCheck(one, at: Self.t0), 60)
        let soon = [notice(effectiveAt: at(Self.t0.addingTimeInterval(2)))]
        XCTAssertEqual(try XCTUnwrap(TermsNotice.nextCheck(soon, at: Self.t0)), 2, accuracy: 0.001)
        XCTAssertNil(TermsNotice.nextCheck(one, at: Self.t0.addingTimeInterval(30 * Self.day)), "nothing left to end")
        XCTAssertNil(TermsNotice.nextCheck([], at: Self.t0))
        XCTAssertNil(TermsNotice.nextCheck([notice(effectiveAt: "soon")], at: Self.t0), "a bad date is never waited on")
    }

    func testTheSoonestEntrySetsTheWaitAndABadOneNeverDoes() throws {
        let list = [notice(effectiveAt: at(Self.t0.addingTimeInterval(Self.day))),
                    notice(effectiveAt: "soon"),
                    notice(effectiveAt: at(Self.t0.addingTimeInterval(2)))]
        XCTAssertEqual(try XCTUnwrap(TermsNotice.nextCheck(list, at: Self.t0)), 2, accuracy: 0.001)
    }

    /// The clock is read after each wait, so the wait never has to be right: a
    /// device that slept past the date finds it ended on the next look.
    func testADeviceThatSleptPastTheDateFindsItEnded() {
        let list = [notice(effectiveAt: at(Self.t0.addingTimeInterval(Self.day)))]
        let woken = Self.t0.addingTimeInterval(2 * Self.day)
        XCTAssertEqual(TermsNotice.live(list, at: woken), [])
        XCTAssertNil(TermsNotice.nextCheck(list, at: woken))
    }
}
