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
}
