import XCTest

/// Guards the App Store Guideline 5.1.1(v) fix (macOS 1.4.22 rejected 10 Aug
/// 2026: "The app requires users to register or log in to access features that
/// are not account based").
///
/// The policy under test lives in `iosApp/ViewModels/GuestAccess.swift`, which
/// is Foundation-only and compiled straight into this un-hosted bundle — no
/// app host, no KMP framework, no network. That is deliberate: the rule that
/// must never regress is a pure decision, so it is tested as one.
final class GuestShellTests: XCTestCase {

    // MARK: - Root routing

    /// The whole fix in one assertion: the app opens into its shell with no
    /// account. There is no `isLoggedIn` parameter to `decide` — being signed
    /// out cannot influence the route, which is exactly the property App
    /// Review rejected the old build for lacking.
    func testShellIsReachableWithoutAnAccount() {
        XCTAssertEqual(RootRoute.decide(hasConsented: true, consentDeferred: false), .shell)
    }

    /// First launch, nothing decided yet: the privacy disclosure comes first.
    /// That is a consent gate, not a registration gate — it asks for no
    /// account and creates none.
    func testFirstLaunchShowsConsent() {
        XCTAssertEqual(RootRoute.decide(hasConsented: false, consentDeferred: false), .consent)
    }

    /// "Not now" on the disclosure must NOT dead-end the user the way the old
    /// Decline did ("You must accept the privacy policy to use Birdo VPN",
    /// with no way forward on a platform where the app cannot exit itself).
    func testDeferringConsentFallsThroughToTheShell() {
        XCTAssertEqual(RootRoute.decide(hasConsented: false, consentDeferred: true), .shell)
    }

    /// Accepting later must not resurrect the gate for a user who deferred.
    func testAcceptedAndDeferredStillLandsOnTheShell() {
        XCTAssertEqual(RootRoute.decide(hasConsented: true, consentDeferred: true), .shell)
    }

    /// Exhaustive: consent is the ONLY thing that can hold the shell back, and
    /// only until the user answers it either way.
    func testConsentIsTheOnlyGate() {
        for hasConsented in [true, false] {
            for deferred in [true, false] {
                let route = RootRoute.decide(hasConsented: hasConsented, consentDeferred: deferred)
                let expected: RootRoute = (hasConsented || deferred) ? .shell : .consent
                XCTAssertEqual(route, expected,
                               "consented=\(hasConsented) deferred=\(deferred)")
            }
        }
    }

    // MARK: - Point-of-use sign-in prompts

    /// Every prompt has to say which action asked for an account, or it is a
    /// wall wearing a sheet.
    func testEverySignInReasonNamesItsActionAndKeepsItsPromise() {
        var titles = Set<String>()
        for reason in SignInReason.allCases {
            XCTAssertFalse(reason.title.isEmpty, "\(reason) has no title")
            XCTAssertFalse(reason.message.isEmpty, "\(reason) has no message")
            titles.insert(reason.title)
            // Each message must say what still works WITHOUT an account —
            // otherwise the sheet reads as "the app needs an account", which
            // is the impression that got the build rejected.
            let m = reason.message.lowercased()
            XCTAssertTrue(m.contains("without") || m.contains("everything else"),
                          "\(reason) never says what works without an account: \(reason.message)")
        }
        XCTAssertEqual(titles.count, SignInReason.allCases.count,
                       "Two reasons share a headline, so the sheet cannot say why it opened")
    }

    // MARK: - Anonymous creation failures (the fourth-rejection trap)

    /// 🔴 The one that would cost another review cycle.
    /// `POST /auth/register/anonymous` allows 3 creations per IP per hour. A
    /// reviewer on a shared address taps "create an anonymous account", gets a
    /// 429, and a 5.1.1 fix becomes a fresh 2.1 App Completeness rejection —
    /// unless the copy says whose limit it is, how long it lasts, and that the
    /// app still works.
    func testRateLimitCopySaysItIsTheNetworkNotTheUser() {
        let message = AnonymousCreateFailure.message(status: 429, serverText:
            "Too many accounts created from this network, please try later")

        XCTAssertTrue(message.lowercased().contains("network"),
                      "Must blame the network, not the user: \(message)")
        XCTAssertTrue(message.lowercased().contains("hour"),
                      "Must say how long the wait is: \(message)")
        XCTAssertTrue(message.contains(AnonymousCreateFailure.stillUsableSuffix),
                      "Must say the app is still usable without an account: \(message)")
        // The old path ran this through mapAuthError, whose 429 branch says
        // "Too many attempts. Please wait a moment." — wrong about whose
        // limit it is and wrong about the wait.
        XCTAssertFalse(message.contains("Too many attempts"), message)
        XCTAssertFalse(message.lowercased().contains("moment"), message)
    }

    /// The SECOND 429. `POST /auth/register/anonymous` also caps 5 creations
    /// per DEVICE per 24 hours, keyed on the stable device id that survives
    /// sign-out, container deletion and reinstall. The copy above — "this
    /// network", "about an hour" — is wrong on both counts for this refusal,
    /// and its implied remedy (change network) cannot work. The backend's body
    /// distinguishes the two; the client must not collapse them.
    func testDeviceRateLimitCopyNamesTheDeviceAndSaysNetworksWontHelp() {
        let message = AnonymousCreateFailure.message(status: 429, serverText:
            "Too many accounts created from this device, please try later")

        XCTAssertTrue(message.lowercased().contains("device"),
                      "Must name the device, not the network: \(message)")
        XCTAssertTrue(message.contains("24 hours"),
                      "Must give the real window, which is a day not an hour: \(message)")
        XCTAssertFalse(message.lowercased().contains("this network"),
                       "Must not blame the network for a device cap: \(message)")
        XCTAssertTrue(message.lowercased().contains("will not help"),
                      "Must say the obvious remedy is futile: \(message)")
        XCTAssertTrue(message.contains(AnonymousCreateFailure.stillUsableSuffix), message)
    }

    /// And the network copy must not regress when the body is absent or
    /// unrecognised — the IP limit is the common case.
    func testUnrecognisedRateLimitBodyFallsBackToTheNetworkCopy() {
        for text in [nil, "", "Too Many Requests"] {
            let message = AnonymousCreateFailure.message(status: 429, serverText: text)
            XCTAssertTrue(message.lowercased().contains("network"), "\(String(describing: text)) -> \(message)")
            XCTAssertTrue(message.lowercased().contains("hour"), "\(String(describing: text)) -> \(message)")
        }
    }

    /// Retrying inside the rate-limit hour burns another attempt and fails
    /// again, which is precisely how a reviewer concludes the app is broken.
    func testRateLimitOffersNoImmediateRetry() {
        XCTAssertFalse(AnonymousCreateFailure.isImmediatelyRetryable(status: 429))
    }

    /// Everything transient is worth a retry button.
    func testTransientFailuresAreRetryable() {
        XCTAssertTrue(AnonymousCreateFailure.isImmediatelyRetryable(status: nil))
        XCTAssertTrue(AnonymousCreateFailure.isImmediatelyRetryable(status: 500))
        XCTAssertTrue(AnonymousCreateFailure.isImmediatelyRetryable(status: 503))
    }

    /// A blocked client header (401 here) is a build problem. It must not read
    /// as "bad credentials" — there are no credentials in this request.
    func testBlockedClientDoesNotImplyBadCredentials() {
        let message = AnonymousCreateFailure.message(status: 401, serverText:
            "This endpoint is only accessible from the Birdo client application")
        XCTAssertFalse(message.lowercased().contains("password"), message)
        XCTAssertTrue(message.contains(AnonymousCreateFailure.stillUsableSuffix), message)
        XCTAssertFalse(AnonymousCreateFailure.isImmediatelyRetryable(status: 401))
    }

    /// Server outage: name it as ours, keep the way out.
    func testServerErrorsCarryTheStatusAndTheWayOut() {
        let message = AnonymousCreateFailure.message(status: 503, serverText: nil)
        XCTAssertTrue(message.contains("503"), message)
        XCTAssertTrue(message.contains(AnonymousCreateFailure.stillUsableSuffix), message)
    }

    /// An unmodelled refusal the backend explained: show ITS words, then the
    /// way out. Never swallow the server's own explanation.
    func testUnmodelledRefusalKeepsTheBackendsWords() {
        let message = AnonymousCreateFailure.message(status: 418, serverText: "Registrations are paused")
        XCTAssertTrue(message.hasPrefix("Registrations are paused"), message)
        XCTAssertTrue(message.contains(AnonymousCreateFailure.stillUsableSuffix), message)
    }

    /// No connection at all: say so, and still promise the guest shell.
    func testOfflineFailureStillPromisesTheGuestShell() {
        let message = AnonymousCreateFailure.message(status: nil, serverText: nil, isOffline: true)
        XCTAssertTrue(message.lowercased().contains("no internet"), message)
        XCTAssertTrue(message.contains(AnonymousCreateFailure.stillUsableSuffix), message)
    }

    /// Whatever went wrong, the user is never dead-ended.
    func testEveryFailureSaysTheAppStillWorks() {
        let statuses: [Int?] = [nil, 400, 401, 418, 429, 500, 503]
        for status in statuses {
            let message = AnonymousCreateFailure.message(status: status, serverText: nil)
            XCTAssertTrue(message.contains(AnonymousCreateFailure.stillUsableSuffix),
                          "status \(String(describing: status)) dead-ends the user: \(message)")
        }
    }
}
