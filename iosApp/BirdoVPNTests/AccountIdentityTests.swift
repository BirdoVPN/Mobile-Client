import XCTest

/// Owner item 86 — pins `AccountIdentity`, which decides from `GET /auth/me`
/// whether an account is anonymous and what its account number is.
///
/// The rule: the backend's explicit `accountType` / `isAnonymous` /
/// `accountNumber` win when present; an older backend is still read from the
/// synthetic `anon_<digits>@anonymous.local` email.
final class AccountIdentityTests: XCTestCase {

    private let number = "123456789012345678901234"
    private var synthetic: String { "anon_\(number)@anonymous.local" }

    // MARK: - New backend

    func testExplicitFieldsWinWithNoEmailAtAll() {
        let r = AccountIdentity.resolve(accountType: "anonymous", isAnonymous: true,
                                        accountNumber: number, email: nil)
        XCTAssertEqual(r, .init(isAnonymous: true, accountNumber: number))
    }

    func testAccountTypeAloneIsEnough() {
        XCTAssertTrue(AccountIdentity.resolve(accountType: "Anonymous", isAnonymous: nil,
                                              accountNumber: number, email: nil).isAnonymous)
        XCTAssertFalse(AccountIdentity.resolve(accountType: "standard", isAnonymous: nil,
                                               accountNumber: nil, email: synthetic).isAnonymous,
                       "an explicit 'standard' outranks a synthetic-looking email")
    }

    /// `isAnonymous` is the more specific field; it settles a contradiction.
    func testIsAnonymousOutranksAccountType() {
        XCTAssertFalse(AccountIdentity.resolve(accountType: "anonymous", isAnonymous: false,
                                               accountNumber: number, email: nil).isAnonymous)
    }

    func testStandardAccountNeverHasAnAccountNumber() {
        let r = AccountIdentity.resolve(accountType: "standard", isAnonymous: false,
                                        accountNumber: number, email: "a@b.co")
        XCTAssertEqual(r, .init(isAnonymous: false, accountNumber: nil))
    }

    func testGroupedAccountNumberIsReducedToDigits() {
        let r = AccountIdentity.resolve(accountType: "anonymous", isAnonymous: true,
                                        accountNumber: "1234 5678 9012 3456 7890 1234", email: nil)
        XCTAssertEqual(r.accountNumber, number)
    }

    /// The server marks the account anonymous but omits the number: the
    /// synthetic email still supplies it.
    func testMissingNumberFallsBackToTheEmail() {
        let r = AccountIdentity.resolve(accountType: "anonymous", isAnonymous: true,
                                        accountNumber: nil, email: synthetic)
        XCTAssertEqual(r.accountNumber, number)
    }

    // MARK: - Older backend (fallback)

    func testOldBackendIsReadFromTheSyntheticEmail() {
        let r = AccountIdentity.resolve(accountType: nil, isAnonymous: nil,
                                        accountNumber: nil, email: synthetic)
        XCTAssertEqual(r, .init(isAnonymous: true, accountNumber: number))
    }

    func testOldBackendRealEmailIsStandard() {
        let r = AccountIdentity.resolve(accountType: nil, isAnonymous: nil,
                                        accountNumber: nil, email: "person@example.com")
        XCTAssertEqual(r, .init(isAnonymous: false, accountNumber: nil))
    }

    /// An `accountType` this build does not know is not a guess either way: the
    /// next source decides.
    func testUnknownAccountTypeFallsThrough() {
        XCTAssertTrue(AccountIdentity.resolve(accountType: "corporate", isAnonymous: nil,
                                              accountNumber: nil, email: synthetic).isAnonymous)
        XCTAssertFalse(AccountIdentity.resolve(accountType: "corporate", isAnonymous: nil,
                                               accountNumber: nil, email: "a@b.co").isAnonymous)
    }

    func testMalformedSyntheticEmailYieldsNoNumber() {
        XCTAssertNil(AccountIdentity.numberFromSyntheticEmail("anon_@anonymous.local"))
        XCTAssertNil(AccountIdentity.numberFromSyntheticEmail("anon_12ab@anonymous.local"))
        XCTAssertNil(AccountIdentity.numberFromSyntheticEmail(nil))
    }

    // MARK: - #485 review: what is shown (AnonymousAccountDisplay)

    private let stale = "000000000000000000000001"
    private var anonymous: AccountIdentity.Resolved { .init(isAnonymous: true, accountNumber: number) }
    private let standard = AccountIdentity.Resolved(isAnonymous: false, accountNumber: nil)

    func testAStoredNumberIsNeverShownWhileSignedOut() {
        // The old fallback was `user?.anonymousAccountNumber ?? keychain.anonymousId`.
        XCTAssertNil(AnonymousAccountDisplay.number(profile: nil, signedIn: false, storedNumber: stale))
        XCTAssertFalse(AnonymousAccountDisplay.isAnonymous(profile: nil, signedIn: false,
                                                           email: nil, storedNumber: stale))
    }

    func testAStandardAccountNeverShowsAStoredNumber() {
        XCTAssertNil(AnonymousAccountDisplay.number(profile: standard, signedIn: true, storedNumber: stale))
        XCTAssertFalse(AnonymousAccountDisplay.isAnonymous(profile: standard, signedIn: true,
                                                           email: nil, storedNumber: stale))
    }

    func testTheProfilesNumberWinsOverAStoredOne() {
        XCTAssertEqual(AnonymousAccountDisplay.number(profile: anonymous, signedIn: true, storedNumber: stale),
                       number)
        XCTAssertTrue(AnonymousAccountDisplay.isAnonymous(profile: anonymous, signedIn: true,
                                                          email: nil, storedNumber: nil))
    }

    func testBeforeTheProfileLandsTheSessionsStoredNumberStandsIn() {
        // Signed in, offline cold start: the number recorded at sign-in.
        XCTAssertEqual(AnonymousAccountDisplay.number(profile: nil, signedIn: true, storedNumber: number), number)
        XCTAssertTrue(AnonymousAccountDisplay.isAnonymous(profile: nil, signedIn: true,
                                                          email: nil, storedNumber: number))
        // An account with an email is not anonymous, whatever is stored.
        XCTAssertFalse(AnonymousAccountDisplay.isAnonymous(profile: nil, signedIn: true,
                                                           email: "person@example.com", storedNumber: number))
        // An anonymous profile without a number falls back to this session's.
        let anonymousWithoutNumber = AccountIdentity.Resolved(isAnonymous: true, accountNumber: nil)
        XCTAssertEqual(AnonymousAccountDisplay.number(profile: anonymousWithoutNumber,
                                                      signedIn: true, storedNumber: number), number)
    }

    // MARK: - MR-1362: how the number reads on screen (Android's rule)

    /// The same values Android's AnonymousIdTest pins for formatAnonymousId /
    /// maskAnonymousId, so the two apps cannot drift apart.
    func testGroupedInFoursLikeAndroid() {
        XCTAssertEqual(AccountNumberText.grouped(number), "1234 5678 9012 3456 7890 1234")
        XCTAssertEqual(AccountNumberText.grouped("1234 5678-9012"), "1234 5678 9012")
        XCTAssertEqual(AccountNumberText.grouped("123456"), "1234 56")
        XCTAssertEqual(AccountNumberText.grouped(""), "")
    }

    func testMaskedShowsOnlyTheLastGroupLikeAndroid() {
        XCTAssertEqual(AccountNumberText.masked(number), "•••• •••• •••• •••• •••• 1234")
        XCTAssertEqual(AccountNumberText.masked(number).count, AccountNumberText.grouped(number).count)
        XCTAssertEqual(AccountNumberText.masked("1234"), "1234")
        XCTAssertEqual(AccountNumberText.masked(""), "")
        XCTAssertFalse(AccountNumberText.masked(number).contains("5678"), "a masked number shows no other digits")
        XCTAssertEqual(AccountNumberText.lastGroup(number), "1234")
    }
}
