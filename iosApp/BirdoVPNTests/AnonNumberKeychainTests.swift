import XCTest

/// MR-1707 — a masked account number from the server never reaches the
/// keychain, and the Profile card keeps the mask as it came.
///
/// With `ANON_NUMBER_MASKING` on, `/auth/me` answers `accountNumber` as
/// stars plus the last four digits. Before this, `AccountIdentity` reduced it
/// to its digits (a four-digit "number") and `AnonymousNumberRecord` wrote
/// whatever the profile said over the stored one. These use only the
/// pre-existing seams, so they fail on the old code and pass on the new.
final class AnonNumberKeychainTests: XCTestCase {

    private let real = "123456789012345678901234"
    private let mask = "********************1234"

    private final class Store: InstallIdentityStore {
        var deviceId: String?
        var anonymousId: String?
        var hasSession: Bool { false }
        init(anonymousId: String?) { self.anonymousId = anonymousId }
        func saveDeviceId(_ id: String) -> Bool { deviceId = id; return true }
        func saveAnonymousId(_ id: String) -> Bool { anonymousId = id; return true }
        func clearAnonymousId() { anonymousId = nil }
        func clearSession() {}
    }

    func testAMaskedProfileNeverOverwritesTheStoredRealNumber() {
        let store = Store(anonymousId: real)
        AnonymousNumberRecord.recordProfile(isAnonymous: true, number: mask, in: store)
        XCTAssertEqual(store.anonymousId, real)
    }

    func testAMaskedSignInNumberIsNeverStored() {
        let store = Store(anonymousId: nil)
        AnonymousNumberRecord.recordSignIn(anonymous: true, number: mask, in: store)
        XCTAssertNil(store.anonymousId)
    }

    func testTheServersMaskResolvesAsTheMaskNotAsFourDigits() {
        let r = AccountIdentity.resolve(accountType: "anonymous", isAnonymous: true,
                                        accountNumber: mask, email: nil)
        XCTAssertEqual(r.accountNumber, mask)
    }

    func testTheCardShowsTheMaskEvenWithARealNumberStored() {
        let profile = AccountIdentity.Resolved(isAnonymous: true, accountNumber: mask)
        XCTAssertEqual(AnonymousAccountDisplay.number(profile: profile, signedIn: true, storedNumber: real),
                       mask)
    }
}
