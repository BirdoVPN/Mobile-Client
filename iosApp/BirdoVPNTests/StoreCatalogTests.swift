import XCTest

/// Guards the App Store in-app-purchase rail (App Store Guideline 3.1.1 — iOS
/// 1.4.20 and macOS 1.4.22 were both rejected on 21 Aug 2026 for unlocking a
/// web-bought entitlement without offering In-App Purchase).
///
/// The rail moves real money, so the parts of it that can be tested without
/// StoreKit are tested here: `iosApp/Services/StoreCatalog.swift` is
/// Foundation-only and compiled straight into this un-hosted bundle — no app
/// host, no sandbox Apple ID, no network. Everything asserted below is a pure
/// function, which is exactly why it was split out of `StoreKitService`.
///
/// WHAT THESE ASSERT, AND WHY IT IS `isTerminal`/`isAlarming` AND NOT THE ENUM
/// CASE. The enum case is an implementation detail; those two booleans are the
/// user's actual experience. `isAlarming` decides red-error banner vs calm
/// info banner, and `isTerminal` decides whether the same refusal is re-shown
/// on every launch and every Restore tap or recorded once and suppressed. A
/// refusal can be routed to the right case and still be experienced as a
/// screaming banner telling a signed-in user to sign in, forever — which is
/// precisely the bug `.jwsInvalid` was added to fix.
final class StoreCatalogTests: XCTestCase {

    /// Every case, in one place, so the exhaustiveness checks below cannot
    /// quietly stop covering a case that someone adds later.
    private static let allRefusals: [StoreLinkRefusal] = [
        .alreadyLinkedToAnotherAccount,
        .notThePurchaser,
        .productUnmapped,
        .jwsInvalid,
        .needsSignIn,
        .rateLimited,
        .transient,
    ]

    // MARK: - StoreLinkRefusal.classify — the server codes

    /// 409 STORE_TRANSACTION_ALREADY_LINKED: this App Store subscription
    /// belongs to a different Birdo account. Terminal (repeating the identical
    /// request gets the identical answer) and calm (it is a decision to
    /// explain, with a named way out, not a failure to alarm anyone about).
    func testAlreadyLinkedIsTerminalAndCalm() {
        let refusal = StoreLinkRefusal.classify(status: 409,
                                                code: "STORE_TRANSACTION_ALREADY_LINKED")
        XCTAssertEqual(refusal, .alreadyLinkedToAnotherAccount)
        XCTAssertTrue(refusal.isTerminal)
        XCTAssertFalse(refusal.isAlarming)
    }

    /// 409 STORE_TRANSACTION_NOT_PURCHASED: Family Sharing, or an ownership
    /// type the backend does not treat as entitling. Nothing the presenter can
    /// do, so terminal and calm.
    func testNotThePurchaserIsTerminalAndCalm() {
        let refusal = StoreLinkRefusal.classify(status: 409,
                                                code: "STORE_TRANSACTION_NOT_PURCHASED")
        XCTAssertEqual(refusal, .notThePurchaser)
        XCTAssertTrue(refusal.isTerminal)
        XCTAssertFalse(refusal.isAlarming)
    }

    /// 409 STORE_PRODUCT_UNMAPPED: the backend does not map this product id to
    /// a plan. Terminal — retrying cannot teach it — but genuinely alarming:
    /// the user paid and got nothing, and the copy has to send them to support.
    func testProductUnmappedIsTerminalAndAlarming() {
        let refusal = StoreLinkRefusal.classify(status: 409, code: "STORE_PRODUCT_UNMAPPED")
        XCTAssertEqual(refusal, .productUnmapped)
        XCTAssertTrue(refusal.isTerminal)
        XCTAssertTrue(refusal.isAlarming)
    }

    /// 401 APPLE_JWS_INVALID — the regression test for the reported bug.
    ///
    /// `linkTransaction` throws `UnauthorizedException` when Apple's signature
    /// does not verify, so this code arrives on a 401 and NOT on a 409. Before
    /// the fix the code was discarded on the way out of `APIClient` (a blind
    /// refresh-and-retry collapsed it to a bare `.unauthorized`), so
    /// `classify(401, nil)` returned `.needsSignIn` — non-terminal and
    /// alarming, i.e. a red "Sign in" banner shown to an ALREADY SIGNED-IN
    /// user on every launch and every Restore tap. A bundle-id / trust-anchor
    /// / sandbox-environment mismatch is the most likely first-contact failure
    /// in sandbox, so this is the message the owner would actually have got.
    ///
    /// It must be terminal (nothing about the request will change) and calm
    /// (not user-actionable, no money at risk, and it is Birdo's fault).
    func testJwsInvalidIsTerminalAndCalm() {
        let refusal = StoreLinkRefusal.classify(status: 401, code: "APPLE_JWS_INVALID")
        XCTAssertEqual(refusal, .jwsInvalid)
        XCTAssertTrue(refusal.isTerminal)
        XCTAssertFalse(refusal.isAlarming)
    }

    /// The code outranks the status. The same code on a 409, or with no status
    /// at all, must still be `.jwsInvalid` — classification is on the code
    /// first precisely so a backend that moves the status cannot silently
    /// re-route the user into "sign in".
    func testCodeOutranksStatus() {
        XCTAssertEqual(StoreLinkRefusal.classify(status: 409, code: "APPLE_JWS_INVALID"),
                       .jwsInvalid)
        XCTAssertEqual(StoreLinkRefusal.classify(status: nil, code: "APPLE_JWS_INVALID"),
                       .jwsInvalid)
        XCTAssertEqual(StoreLinkRefusal.classify(status: 500,
                                                 code: "STORE_TRANSACTION_ALREADY_LINKED"),
                       .alreadyLinkedToAnotherAccount)
    }

    /// A 409 whose code we do not recognise is still a CONFLICT: the server
    /// decided, and the identical request gets the identical answer. It must
    /// be terminal rather than retried into a wall.
    func testUnknownConflictCodeIsStillTerminal() {
        let refusal = StoreLinkRefusal.classify(status: 409, code: "STORE_SOMETHING_NEW")
        XCTAssertEqual(refusal, .alreadyLinkedToAnotherAccount)
        XCTAssertTrue(refusal.isTerminal)
        XCTAssertFalse(refusal.isAlarming)
    }

    /// A 409 with no code at all takes the same path.
    func testBare409IsTerminal() {
        let refusal = StoreLinkRefusal.classify(status: 409, code: nil)
        XCTAssertEqual(refusal, .alreadyLinkedToAnotherAccount)
        XCTAssertTrue(refusal.isTerminal)
        XCTAssertFalse(refusal.isAlarming)
    }

    // MARK: - StoreLinkRefusal.classify — bare statuses

    /// A CODELESS 401 is the JWT guard, not the endpoint: the session really
    /// is unusable. `.needsSignIn` is right here and only here. Non-terminal,
    /// because signing in genuinely changes the answer, and alarming because
    /// the user has to act.
    func testBare401IsNeedsSignIn() {
        let refusal = StoreLinkRefusal.classify(status: 401, code: nil)
        XCTAssertEqual(refusal, .needsSignIn)
        XCTAssertFalse(refusal.isTerminal)
        XCTAssertTrue(refusal.isAlarming)
    }

    func testBare403IsNeedsSignIn() {
        let refusal = StoreLinkRefusal.classify(status: 403, code: nil)
        XCTAssertEqual(refusal, .needsSignIn)
        XCTAssertFalse(refusal.isTerminal)
        XCTAssertTrue(refusal.isAlarming)
    }

    /// The rail's 20-per-60s bucket. Genuinely transient, so NOT terminal —
    /// suppressing it for the session would strand a purchase that would have
    /// linked a minute later.
    func testBare429IsRetryable() {
        let refusal = StoreLinkRefusal.classify(status: 429, code: nil)
        XCTAssertEqual(refusal, .rateLimited)
        XCTAssertFalse(refusal.isTerminal)
        XCTAssertTrue(refusal.isAlarming)
    }

    /// nil status = the client minted the failure (transport error, unusable
    /// body). Transient and retryable.
    func testNilStatusIsTransient() {
        let refusal = StoreLinkRefusal.classify(status: nil, code: nil)
        XCTAssertEqual(refusal, .transient)
        XCTAssertFalse(refusal.isTerminal)
        XCTAssertTrue(refusal.isAlarming)
    }

    /// 5xx is a transient failure, not a decision.
    func test5xxIsTransient() {
        XCTAssertEqual(StoreLinkRefusal.classify(status: 500, code: nil), .transient)
        XCTAssertEqual(StoreLinkRefusal.classify(status: 503, code: nil), .transient)
    }

    // MARK: - Properties every refusal must hold

    /// No refusal, ever, finishes the transaction. A transaction is finished
    /// ONLY after the server accepts it — an unfinished one is re-presented by
    /// Apple on the next launch, which is what lets a support ownership
    /// transfer bind itself with no further action from the user.
    func testNoRefusalFinishesTheTransaction() {
        for refusal in Self.allRefusals {
            XCTAssertFalse(refusal.shouldFinishTransaction, "\(refusal) must not finish")
        }
    }

    /// Every case must have non-empty fallback copy: it is what the user reads
    /// whenever the server did not write words of its own.
    func testEveryRefusalHasFallbackCopy() {
        for refusal in Self.allRefusals {
            XCTAssertFalse(refusal.fallbackMessage.isEmpty, "\(refusal) has no copy")
        }
    }

    /// Only the genuinely-signed-out case may tell the user to sign in. This
    /// catches the reported bug from the COPY side as well as the
    /// classification side: whatever route a future refusal takes, it must not
    /// arrive at "Sign in to Birdo" unless being signed out is the problem.
    func testOnlyNeedsSignInTellsTheUserToSignIn() {
        XCTAssertTrue(StoreLinkRefusal.needsSignIn.fallbackMessage.contains("Sign in to Birdo"))
        for refusal in Self.allRefusals where refusal != .needsSignIn {
            XCTAssertFalse(refusal.fallbackMessage.contains("Sign in to Birdo"),
                           "\(refusal) tells the user to sign in")
        }
    }

    // MARK: - StorefrontState.decide

    /// Nothing asked yet is LOADING, not "unavailable" — the screen must not
    /// accuse the App Store before it has been consulted.
    func testNotYetAttemptedIsLoading() {
        XCTAssertEqual(
            StorefrontState.decide(isLoading: false, hasAttempted: false,
                                   productCount: 0, loadFailed: false),
            .loading)
    }

    func testInFlightIsLoading() {
        XCTAssertEqual(
            StorefrontState.decide(isLoading: true, hasAttempted: false,
                                   productCount: 0, loadFailed: false),
            .loading)
    }

    /// THE STATE A REAL DEVICE IS IN TODAY. The Paid Applications Agreement is
    /// not active, so `Product.products(for:)` answers normally with an EMPTY
    /// array — not an error. That must be a first-class explained state, and
    /// it must NOT be described to the user as a connection problem.
    func testEmptyAfterASuccessfulFetchIsExplainedNotFailed() {
        let state = StorefrontState.decide(isLoading: false, hasAttempted: true,
                                           productCount: 0, loadFailed: false)
        XCTAssertEqual(state, .unavailable(StorefrontState.noProductsMessage))
        XCTAssertNotEqual(state, .unavailable(StorefrontState.loadFailedMessage))
    }

    /// A failed or timed-out fetch gets the CONNECTION copy, because "check
    /// your connection" is useless advice when the store simply has no
    /// products, and misleading advice is worse than none.
    func testFailedFetchGetsConnectionCopy() {
        XCTAssertEqual(
            StorefrontState.decide(isLoading: false, hasAttempted: true,
                                   productCount: 0, loadFailed: true),
            .unavailable(StorefrontState.loadFailedMessage))
    }

    /// Products present = ready.
    func testProductsPresentIsReady() {
        XCTAssertEqual(
            StorefrontState.decide(isLoading: false, hasAttempted: true,
                                   productCount: 4, loadFailed: false),
            .ready)
    }

    /// A stale-but-good catalogue beats a spinner and beats an error: a
    /// background refresh must never blank a screen that already has
    /// purchasable products on it, whether that refresh is in flight or failed.
    func testExistingProductsSurviveARefreshAndAFailure() {
        XCTAssertEqual(
            StorefrontState.decide(isLoading: true, hasAttempted: true,
                                   productCount: 4, loadFailed: false),
            .ready)
        XCTAssertEqual(
            StorefrontState.decide(isLoading: false, hasAttempted: true,
                                   productCount: 4, loadFailed: true),
            .ready)
    }

    /// The two unavailable messages must actually differ, or the
    /// empty-vs-failed distinction above is decorative.
    func testUnavailableMessagesAreDistinctAndNonEmpty() {
        XCTAssertNotEqual(StorefrontState.noProductsMessage,
                          StorefrontState.loadFailedMessage)
        XCTAssertFalse(StorefrontState.noProductsMessage.isEmpty)
        XCTAssertFalse(StorefrontState.loadFailedMessage.isEmpty)
    }

    /// The spinner must be bounded — a purchase screen that spins forever is
    /// indistinguishable from a broken app to App Review.
    func testLoadDeadlineIsBounded() {
        XCTAssertGreaterThan(StorefrontState.loadDeadlineSeconds, 0)
        XCTAssertLessThanOrEqual(StorefrontState.loadDeadlineSeconds, 30)
    }

    // MARK: - Product catalogue

    func testPlanAndPeriodLookup() {
        XCTAssertEqual(BirdoStoreProduct.product(plan: "OPERATIVE", period: .monthly),
                       .operativeMonthly)
        XCTAssertEqual(BirdoStoreProduct.product(plan: "operative", period: .yearly),
                       .operativeYearly)
        XCTAssertEqual(BirdoStoreProduct.product(plan: "SOVEREIGN", period: .monthly),
                       .sovereignMonthly)
        XCTAssertEqual(BirdoStoreProduct.product(plan: "SOVEREIGN", period: .yearly),
                       .sovereignYearly)
        // RECON is free and has no product at all.
        XCTAssertNil(BirdoStoreProduct.product(plan: "RECON", period: .monthly))
    }

    /// An unrecognised identifier must return nil rather than guessing a plan.
    /// Guessing would paint a plan the user does not have — and an id we do not
    /// recognise is exactly the case the server refuses with
    /// STORE_PRODUCT_UNMAPPED.
    func testUnknownIdentifierDoesNotGuess() {
        XCTAssertNil(BirdoStoreProduct.from(identifier: "app.birdo.vpn.enterprise.monthly"))
        XCTAssertNil(BirdoStoreProduct.from(identifier: ""))
        XCTAssertEqual(BirdoStoreProduct.from(identifier: "app.birdo.vpn.sovereign.yearly"),
                       .sovereignYearly)
    }

    // MARK: - .storekit drift

    /// THE DRIFT TEST. `iosApp/BirdoVPN.storekit` is carried as a RESOURCE of
    /// this bundle (see iosApp/project.yml) purely so this assertion can exist.
    ///
    /// The .storekit file is the ONLY place the four products exist today —
    /// there are none in App Store Connect until the Paid Applications
    /// Agreement is active — so it is what every local run of the purchase
    /// rail resolves against. If it and `BirdoStoreProduct` drift apart,
    /// `Product.products(for:)` silently returns fewer products than it was
    /// asked for, the missing plan's card renders "Not available to purchase
    /// in the app right now.", and nothing anywhere says why. That is
    /// invisible until somebody runs the app by hand and notices a dead card.
    ///
    /// EXACTLY equal, in both directions: an id in the enum but not the file
    /// is a dead card, and an id in the file but not the enum is a product
    /// nobody can buy and that the server would answer STORE_PRODUCT_UNMAPPED
    /// for if they somehow did.
    func testStoreKitConfigurationDeclaresExactlyTheExpectedProductIds() throws {
        let declared = Set(try Self.declaredSubscriptions().keys)
        let expected = Set(BirdoStoreProduct.allIdentifiers)

        XCTAssertEqual(declared, expected,
                       "BirdoVPN.storekit and BirdoStoreProduct have drifted. "
                       + "Only in the .storekit: \(declared.subtracting(expected).sorted()). "
                       + "Only in the enum: \(expected.subtracting(declared).sorted()).")
        // Four DISTINCT ids, so a copy-paste that duplicates one is caught too.
        XCTAssertEqual(BirdoStoreProduct.allIdentifiers.count, 4)
        XCTAssertEqual(expected.count, 4)
    }

    /// Every declared subscription must also carry the renewal period the enum
    /// claims for it. A monthly id pointing at a P1Y subscription would charge
    /// a year's money behind a "/mo" price suffix.
    func testStoreKitPeriodsMatchTheEnum() throws {
        let declared = try Self.declaredSubscriptions()
        for product in BirdoStoreProduct.allCases {
            let expected = product.period == .monthly ? "P1M" : "P1Y"
            XCTAssertEqual(declared[product.rawValue], expected,
                           "\(product.rawValue) is \(product.period) in the enum but "
                           + "\(declared[product.rawValue] ?? "absent") in BirdoVPN.storekit")
        }
    }

    /// productID -> recurringSubscriptionPeriod, read out of the .storekit
    /// resource. Throws (rather than returning empty) if the resource is
    /// missing, so a dropped `buildPhase: resources` entry in project.yml
    /// FAILS the drift tests instead of making them vacuously pass.
    private static func declaredSubscriptions() throws -> [String: String] {
        let bundle = Bundle(for: StoreCatalogTests.self)
        let url = try XCTUnwrap(
            bundle.url(forResource: "BirdoVPN", withExtension: "storekit"),
            "BirdoVPN.storekit is not in the test bundle. It is listed in "
            + "iosApp/project.yml under BirdoVPNTests with `buildPhase: resources` — if that "
            + "entry was dropped, this drift test silently stops existing.")

        let json = try JSONSerialization.jsonObject(with: try Data(contentsOf: url))
        let root = try XCTUnwrap(json as? [String: Any])
        let groups = try XCTUnwrap(root["subscriptionGroups"] as? [[String: Any]],
                                   "no subscriptionGroups in BirdoVPN.storekit")

        var result: [String: String] = [:]
        for group in groups {
            for subscription in (group["subscriptions"] as? [[String: Any]]) ?? [] {
                guard let id = subscription["productID"] as? String else { continue }
                // Defaulted to "" rather than left absent, so a subscription
                // missing its period still shows up in the id set — otherwise
                // the drift test would blame the wrong thing.
                result[id] = (subscription["recurringSubscriptionPeriod"] as? String) ?? ""
            }
        }
        XCTAssertFalse(result.isEmpty, "BirdoVPN.storekit declares no subscriptions at all")
        return result
    }
}
