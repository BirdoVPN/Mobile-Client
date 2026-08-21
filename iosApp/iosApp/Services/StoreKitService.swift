import Foundation
import StoreKit

/// StoreKit 2 in-app purchase rail — the half that genuinely needs StoreKit.
///
/// Every decision this file could make instead lives in `StoreCatalog.swift`,
/// which is Foundation-only and unit tested. What is left here is I/O:
/// fetching products, driving `Product.purchase`, listening to
/// `Transaction.updates`, and handing signed transactions to the server.
///
/// ── The contract with the server (birdo-web, feat/store-entitlement-rail) ──
///   1. POST /payments/store/apple/purchase-token  -> the SERVER mints the
///      appAccountToken. Never invent one client-side.
///   2. Product.purchase(options: [.appAccountToken(minted)])
///   3. The result must be `.verified`. An `.unverified` result is never sent
///      to the server and never trusted.
///   4. POST /payments/store/apple/link with the JWS. The body carries no
///      plan, price or expiry — the server decides from Apple's signature.
///   5. Only then transaction.finish().
///
/// Renewals, refunds and revocations arrive at the server directly from Apple
/// (POST .../notifications, App Store Server Notifications V2), so this client
/// never polls for them. It does still need `Transaction.updates`: Ask-to-Buy
/// approvals, Family Sharing changes, purchases made on another device and
/// renewals that happen while the app is open all surface there, and a
/// purchase completed outside our own `purchase()` call is otherwise never
/// seen by this process at all.
@MainActor
final class StoreKitService: ObservableObject {
    static let shared = StoreKitService()

    // MARK: - Published state

    /// Products StoreKit resolved, in catalogue order. EMPTY is a normal,
    /// expected state today — see `storefront`.
    @Published private(set) var products: [Product] = []

    /// What the purchase UI should render. Never a bare empty list, never an
    /// unbounded spinner.
    @Published private(set) var storefront: StorefrontState = .loading

    /// Identifier of the product currently being bought, for a per-card
    /// spinner. nil when idle.
    @Published private(set) var purchasingProductId: String?

    @Published private(set) var isRestoring = false

    /// One banner at a time; the newest wins.
    @Published var notice: StoreNotice?

    /// Set when the server reports the account is ALSO paying on another rail.
    /// Must be rendered — it tells the user they are being billed twice and
    /// where to cancel. Sticky until dismissed: it is about money.
    @Published var duplicateBilling: AppleDuplicateBilling?

    // MARK: - Collaborators (injected so nothing here reaches for a singleton)

    /// True when there is a usable Birdo session. Purchasing REQUIRES one: the
    /// server binds the purchase to an account, and there is no account to
    /// bind to otherwise.
    ///
    /// `@MainActor` on the closure type is load-bearing under Swift 6 strict
    /// concurrency: every collaborator these hooks touch (AuthViewModel,
    /// VpnViewModel) is main-actor isolated, so a plain `() -> Void` could not
    /// legally call any of them at the assignment site.
    var isSignedIn: @MainActor () -> Bool = { false }

    /// Raise the point-of-use sign-in sheet (the 5.1.1(v) guest-shell one).
    var requestSignIn: @MainActor () -> Void = {}

    /// Called after the server ACCEPTS an entitlement, so the plan snapshot
    /// (`/vpn/stats`) is re-fetched and every plan gate in the app opens.
    var onEntitlementChanged: @MainActor () -> Void = {}

    private let api: APIClient

    // MARK: - Internals

    private var updatesTask: Task<Void, Never>?
    private var hasAttemptedLoad = false
    private var lastLoadFailed = false
    private var isLoadingProducts = false

    /// `originalID`s the server has refused for a reason that will not change
    /// by asking again (the 409s). Suppresses repeats FOR THIS SESSION ONLY —
    /// the transaction is deliberately left unfinished, so Apple re-presents it
    /// on the next launch and a support ownership-transfer binds itself with no
    /// further action from the user. See `StoreLinkRefusal.shouldFinishTransaction`.
    private var terminallyRefused: Set<UInt64> = []

    init(api: APIClient = .shared) {
        self.api = api
    }

    deinit {
        updatesTask?.cancel()
    }

    // MARK: - Lifecycle

    /// Start the long-lived transaction listener and load the catalogue.
    /// Idempotent — safe to call from `.onAppear` / `.task` on every render.
    ///
    /// The listener is NOT optional and NOT tied to any screen: it must be
    /// running before the first `purchase()` so an Ask-to-Buy approval that
    /// lands minutes later is still linked, and so a purchase made on another
    /// device reaches the server from here too.
    func start() {
        if updatesTask == nil {
            updatesTask = Task { [weak self] in
                for await update in Transaction.updates {
                    guard let self else { return }
                    await self.ingest(update, announceSuccess: false)
                }
            }
        }
        Task { await loadProducts() }
    }

    /// Re-present anything StoreKit still considers current. Called when a
    /// session appears (sign-in, or an anonymous account being minted), which
    /// is the moment a purchase made while signed out can finally be bound.
    func linkExistingEntitlementsAfterSignIn() async {
        guard isSignedIn() else { return }
        var linked = 0
        for await entitlement in Transaction.currentEntitlements {
            if await ingest(entitlement, announceSuccess: false) { linked += 1 }
        }
        if linked > 0 { onEntitlementChanged() }
    }

    // MARK: - Products

    /// Fetch the catalogue, with a hard deadline.
    ///
    /// TWO DISTINCT FAILURES, deliberately kept apart:
    ///   * StoreKit answers with an EMPTY array — the expected state until the
    ///     Paid Applications Agreement is active and the products exist in App
    ///     Store Connect. Not an error; explained as "not purchasable yet".
    ///   * The fetch throws or hangs — offline, captive portal, wedged
    ///     storefront. Explained as a connection problem, with a retry.
    func loadProducts() async {
        guard !isLoadingProducts else { return }
        isLoadingProducts = true
        recomputeStorefront()

        let identifiers = BirdoStoreProduct.allIdentifiers
        let deadline = StorefrontState.loadDeadlineSeconds

        // Race the fetch against the deadline. `nil` from either arm means
        // "no usable answer": a thrown fetch and a timed-out fetch are the
        // same fact to the user and get the same copy.
        let fetched: [Product]? = await withTaskGroup(of: [Product]?.self) { group in
            group.addTask { try? await Product.products(for: identifiers) }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(deadline * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }

        isLoadingProducts = false
        hasAttemptedLoad = true
        lastLoadFailed = (fetched == nil)
        if let fetched {
            // Stable catalogue order regardless of what order StoreKit answers
            // in, so the cards never reshuffle between launches.
            let order = Dictionary(uniqueKeysWithValues: identifiers.enumerated().map { ($0.element, $0.offset) })
            products = fetched.sorted { (order[$0.id] ?? .max) < (order[$1.id] ?? .max) }
        }
        recomputeStorefront()
    }

    private func recomputeStorefront() {
        storefront = StorefrontState.decide(isLoading: isLoadingProducts,
                                            hasAttempted: hasAttemptedLoad,
                                            productCount: products.count,
                                            loadFailed: lastLoadFailed)
    }

    /// The StoreKit product backing a plan card + billing toggle, if it
    /// resolved. nil is a legitimate answer and the UI must handle it.
    func product(plan: String, period: StoreBillingPeriod) -> Product? {
        guard let wanted = BirdoStoreProduct.product(plan: plan, period: period) else { return nil }
        return products.first { $0.id == wanted.rawValue }
    }

    // MARK: - Purchase

    /// Buy `product`, bound to the signed-in Birdo account.
    ///
    /// Order matters and is the server's contract: mint the token FIRST, so the
    /// purchase itself carries the binding. A purchase made without an
    /// appAccountToken cannot be attributed to an anonymous account at all —
    /// Apple sends no Apple ID, no email and no stable user id, and an
    /// anonymous Birdo account has no email to match on either.
    func purchase(_ product: Product) async {
        guard purchasingProductId == nil else { return }
        guard isSignedIn() else {
            // Not an error state — an honest prerequisite. The sheet explains
            // why, and the purchase can be retried straight after.
            requestSignIn()
            return
        }

        purchasingProductId = product.id
        notice = nil
        defer { purchasingProductId = nil }

        let appAccountToken: UUID
        do {
            appAccountToken = try await api.mintApplePurchaseToken()
        } catch {
            // Nothing has been charged: the App Store sheet was never shown.
            notice = .error(Self.purchaseSetupFailureMessage(error))
            return
        }

        do {
            let result = try await product.purchase(options: [.appAccountToken(appAccountToken)])
            switch result {
            case .success(let verification):
                _ = await ingest(verification, announceSuccess: true)
            case .userCancelled:
                // The user said no. Saying anything at all here is noise.
                break
            case .pending:
                // Ask to Buy, or a bank confirmation (SCA). The transaction
                // arrives at `Transaction.updates` if and when it is approved —
                // which is precisely why the listener is not optional.
                notice = .info("Your purchase is waiting for approval. Birdo will unlock "
                               + "automatically once it is approved — you can close the app.")
            @unknown default:
                notice = .error("The App Store returned an outcome Birdo does not understand. "
                                + "If you were charged, tap Restore Purchases.")
            }
        } catch {
            notice = .error(Self.purchaseFailureMessage(error))
        }
    }

    // MARK: - Restore

    /// Restore Purchases. Apple REQUIRES a visible control for this on
    /// auto-renewable subscriptions, and it must answer in every case —
    /// including "there was nothing to restore", which is the common one.
    @discardableResult
    func restorePurchases() async -> StoreRestoreOutcome {
        guard !isRestoring else { return .failed("A restore is already running.") }
        guard isSignedIn() else {
            requestSignIn()
            return .failed(StoreLinkRefusal.needsSignIn.fallbackMessage)
        }

        isRestoring = true
        notice = nil
        defer { isRestoring = false }

        // Clear the session suppression list: the user has explicitly asked us
        // to try again, and they may have just signed in to the right account.
        terminallyRefused.removeAll()

        var outcome = await sweepCurrentEntitlements()

        if case .nothingToRestore = outcome {
            // LAST RESORT ONLY. AppStore.sync() forces an App Store sign-in
            // prompt, so it is wrong to run it on every restore — but it is the
            // one thing that can rebuild a missing receipt (a restored device
            // backup, a fresh install that has not yet synced). Reaching it
            // requires the user to have already asked for a restore AND
            // currentEntitlements to have come back empty.
            do {
                try await AppStore.sync()
                outcome = await sweepCurrentEntitlements()
            } catch {
                // A cancelled password prompt lands here. The honest answer is
                // still "nothing found", not "something broke".
                outcome = .nothingToRestore
            }
        }

        notice = outcome.isSuccess ? .success(outcome.message) : .info(outcome.message)
        if outcome.isSuccess { onEntitlementChanged() }
        return outcome
    }

    private func sweepCurrentEntitlements() async -> StoreRestoreOutcome {
        var found = 0
        var linked = 0
        var refusal: String?

        for await entitlement in Transaction.currentEntitlements {
            found += 1
            if await ingest(entitlement, announceSuccess: false) {
                linked += 1
            } else if refusal == nil, let text = lastRefusalText {
                refusal = text
            }
        }

        if linked > 0 { return .restored(linked) }
        if found == 0 { return .nothingToRestore }
        return .refused(refusal ?? StoreLinkRefusal.transient.fallbackMessage)
    }

    // MARK: - The one path every transaction takes

    /// Text of the most recent refusal, so a sweep can report the first one it
    /// hit without `ingest` having to return it.
    private var lastRefusalText: String?

    /// Verify -> link -> finish. The ONLY place a transaction is finished.
    ///
    /// - Returns: true when the server accepted the entitlement.
    @discardableResult
    private func ingest(_ verification: VerificationResult<Transaction>,
                        announceSuccess: Bool) async -> Bool {
        switch verification {
        case .unverified(let transaction, let error):
            // NEVER sent to the server, NEVER trusted, NEVER finished.
            // Finishing would tell Apple we had handled a transaction whose
            // signature did not check out; leaving it unfinished means it comes
            // back, and a genuine transaction that failed verification for a
            // transient reason (clock skew, a certificate fetch) gets another
            // chance on the next launch.
            let text = "The App Store could not verify this purchase "
                + "(\(String(describing: error))). Birdo has not changed your plan. "
                + "Tap Restore Purchases to try again."
            lastRefusalText = text
            if announceSuccess { notice = .error(text) }
            Self.log("unverified transaction \(transaction.id) — not linked, not finished")
            return false

        case .verified(let transaction):
            if terminallyRefused.contains(transaction.originalID) {
                // Already refused in this session for a reason that cannot
                // change by asking again. No request, no loop.
                return false
            }
            guard isSignedIn() else {
                // Not finished: the purchase is real and must survive until
                // there is an account to bind it to. It is re-presented on the
                // next launch and by linkExistingEntitlementsAfterSignIn().
                lastRefusalText = StoreLinkRefusal.needsSignIn.fallbackMessage
                if announceSuccess { notice = .info(lastRefusalText!) }
                return false
            }

            switch await submit(jws: verification.jwsRepresentation) {
            case .accepted(let result):
                // ONLY NOW. The server owns the entitlement; finishing tells
                // Apple we are done with this transaction.
                await transaction.finish()
                lastRefusalText = nil
                if let duplicate = result.duplicateBilling {
                    duplicateBilling = duplicate
                }
                onEntitlementChanged()
                if announceSuccess {
                    notice = .success(Self.purchasedMessage(plan: result.plan))
                }
                return true

            case .refused(let refusal, let message):
                if refusal.isTerminal { terminallyRefused.insert(transaction.originalID) }
                // Deliberately NOT finished — see shouldFinishTransaction.
                assert(!refusal.shouldFinishTransaction)
                lastRefusalText = message
                notice = refusal.isAlarming ? .error(message) : .info(message)
                Self.log("link refused for originalID \(transaction.originalID): \(refusal)")
                return false
            }
        }
    }

    private enum LinkAttempt {
        case accepted(AppleLinkResult)
        case refused(StoreLinkRefusal, String)
    }

    private func submit(jws: String) async -> LinkAttempt {
        do {
            return .accepted(try await api.linkAppleTransaction(signedTransaction: jws))
        } catch let apiError as APIError {
            let status: Int?
            let code: String?
            let serverText: String?
            switch apiError {
            case .serverRefusal(let message, let httpStatus, let detailsCode, _):
                (status, code, serverText) = (httpStatus, detailsCode, message)
            case .serverMessage(let message, let httpStatus):
                (status, code, serverText) = (httpStatus, nil, message)
            case .unauthorized:
                (status, code, serverText) = (401, nil, nil)
            case .httpError(let httpStatus):
                (status, code, serverText) = (httpStatus, nil, nil)
            default:
                (status, code, serverText) = (nil, nil, nil)
            }
            let refusal = StoreLinkRefusal.classify(status: status, code: code)
            // Prefer the SERVER'S words when it wrote any: the account-sharing
            // and Family Sharing strings were written for exactly these cases
            // and name the way out, including the support transfer.
            let message = (serverText?.isEmpty == false) ? serverText! : refusal.fallbackMessage
            return .refused(refusal, message)
        } catch {
            return .refused(.transient, StoreLinkRefusal.transient.fallbackMessage)
        }
    }

    // MARK: - Copy

    private static func purchasedMessage(plan: String) -> String {
        let name = plan.isEmpty ? "your new plan" : plan.uppercased()
        return "Thank you — \(name) is now active on this Birdo account."
    }

    /// Failure BEFORE the App Store sheet was shown. Nothing was charged, and
    /// the copy must say so rather than leaving the user wondering.
    private static func purchaseSetupFailureMessage(_ error: Error) -> String {
        if let apiError = error as? APIError {
            switch apiError {
            case .unauthorized:
                return "Your session expired before the purchase started. Sign in and try again — "
                    + "nothing was charged."
            case .serverRefusal(let message, let status, _, _):
                if StoreLinkRefusal.classify(status: status, code: nil) == .rateLimited {
                    return "Too many attempts in a row. Wait a minute and try again — nothing was charged."
                }
                if let message, !message.isEmpty { return "\(message) Nothing was charged." }
            case .serverMessage(let message, _):
                if !message.isEmpty { return "\(message) Nothing was charged." }
            default:
                break
            }
        }
        return "Birdo could not start the purchase. Check your connection and try again — "
            + "nothing was charged."
    }

    /// Failure raised by StoreKit itself.
    private static func purchaseFailureMessage(_ error: Error) -> String {
        if let storeKitError = error as? StoreKitError {
            switch storeKitError {
            case .networkError:
                return "The App Store could not be reached. Check your connection and try again."
            case .userCancelled:
                return ""
            case .notEntitled:
                // StoreKitError.notEntitled — the Apple ID cannot transact here.
                // (There is no .notEntitledToPurchase case; that name does not
                // exist in the SDK and fails to compile.)
                return "This Apple ID is not allowed to make purchases on this device."
            default:
                break
            }
        }
        if let purchaseError = error as? Product.PurchaseError {
            switch purchaseError {
            case .productUnavailable:
                return "That subscription is not available on the App Store right now."
            case .purchaseNotAllowed:
                return "Purchases are not allowed on this device — check Screen Time restrictions."
            case .ineligibleForOffer, .invalidOfferIdentifier, .invalidOfferPrice,
                 .invalidOfferSignature, .missingOfferParameters:
                return "That subscription offer is no longer valid. Please try again."
            default:
                break
            }
        }
        return "The purchase did not complete. If you were charged, tap Restore Purchases."
    }

    private static func log(_ message: String) {
        #if DEBUG
        print("[StoreKit] \(message)")
        #endif
    }
}

// MARK: - Banner model

/// One transient message for the purchase UI. Explicit init: the memberwise
/// one would not exist usefully once `id` is defaulted, and the component
/// structs in this app declare theirs by convention.
struct StoreNotice: Identifiable, Equatable {
    enum Kind: Equatable {
        case success
        case info
        case error
    }

    let id: UUID
    let kind: Kind
    let text: String

    init(id: UUID = UUID(), kind: Kind, text: String) {
        self.id = id
        self.kind = kind
        self.text = text
    }

    static func success(_ text: String) -> StoreNotice { StoreNotice(kind: .success, text: text) }
    static func info(_ text: String) -> StoreNotice { StoreNotice(kind: .info, text: text) }
    static func error(_ text: String) -> StoreNotice { StoreNotice(kind: .error, text: text) }
}
