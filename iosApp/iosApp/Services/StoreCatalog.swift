import Foundation

/// The pure, testable half of the App Store in-app-purchase rail.
///
/// WHY THIS FILE IS FOUNDATION-ONLY. Everything here is compiled into the
/// un-hosted `BirdoVPNTests` bundle (iosApp/project.yml), so the product-to-plan
/// mapping, the storefront state machine and — above all — the classification
/// of the server's refusals can be asserted on CI without StoreKit, an app
/// host, a sandbox Apple ID or a network. `StoreKitService.swift` holds the
/// half that genuinely needs StoreKit and does as little thinking as possible.
///
/// ── Why in-app purchase exists at all ────────────────────────────────────
/// App Store Review rejected iOS 1.4.20 and macOS 1.4.22 on 21 Aug 2026 under
/// Guideline 3.1.1: "the app accesses digital content purchased outside the
/// app, such as VPN service, but that content isn't available to purchase
/// using In-App Purchase." 3.1.1 is about what the app DOES, not what it says,
/// so removing the purchase CTA (which is what the previous round did) could
/// never satisfy it — the app still unlocks a web-bought entitlement. The only
/// resolution is to sell the subscription through StoreKit as well, which is
/// what this rail is.

// MARK: - Product catalogue

/// Billing period of a Birdo App Store product.
enum StoreBillingPeriod: String, Equatable, Sendable, CaseIterable {
    case monthly
    case yearly

    /// Suffix rendered next to a StoreKit-localised price. Never hardcode the
    /// price itself — `Product.displayPrice` is the only honest source, since
    /// Apple sets it per storefront and per price tier.
    var priceSuffix: String {
        switch self {
        case .monthly: return "/mo"
        case .yearly:  return "/yr"
        }
    }

    var renewalSentence: String {
        switch self {
        case .monthly: return "Renews every month until cancelled."
        case .yearly:  return "Renews every year until cancelled."
        }
    }
}

/// The four auto-renewable subscriptions Birdo sells on the App Store.
///
/// These identifiers are PROVISIONAL. They mirror `DEFAULT_APPLE_PRODUCT_PLANS`
/// in birdo-web's backend (`backend/src/payments/apple/apple-notification.ts`),
/// which is the authority for what a product id entitles. They do not yet
/// exist in App Store Connect — the Paid Applications Agreement is not active,
/// so `Product.products(for:)` returns EMPTY against the real App Store and
/// the whole rail is developed against `iosApp/BirdoVPN.storekit` instead.
/// If a product id is ever renamed it must change in BOTH repos, or the server
/// answers STORE_PRODUCT_UNMAPPED and the purchase unlocks nothing.
enum BirdoStoreProduct: String, CaseIterable, Sendable {
    case operativeMonthly = "app.birdo.vpn.operative.monthly"
    case operativeYearly  = "app.birdo.vpn.operative.yearly"
    case sovereignMonthly = "app.birdo.vpn.sovereign.monthly"
    case sovereignYearly  = "app.birdo.vpn.sovereign.yearly"

    /// Plan slug the backend grants for this product. The client uses it only
    /// to decide which card a product belongs on — the ENTITLEMENT always
    /// comes from the server's own mapping, never from this value.
    var planSlug: String {
        switch self {
        case .operativeMonthly, .operativeYearly: return "OPERATIVE"
        case .sovereignMonthly, .sovereignYearly: return "SOVEREIGN"
        }
    }

    var period: StoreBillingPeriod {
        switch self {
        case .operativeMonthly, .sovereignMonthly: return .monthly
        case .operativeYearly, .sovereignYearly:   return .yearly
        }
    }

    /// Every identifier, in a stable order, for `Product.products(for:)`.
    static var allIdentifiers: [String] { allCases.map(\.rawValue) }

    /// The product for a plan card plus billing toggle, or nil when that
    /// combination is not sold (RECON is free and has no product at all).
    static func product(plan: String, period: StoreBillingPeriod) -> BirdoStoreProduct? {
        let slug = plan.uppercased()
        return allCases.first { $0.planSlug == slug && $0.period == period }
    }

    /// Reverse lookup for an identifier that came back from StoreKit or from a
    /// transaction. Unknown ids return nil rather than guessing a plan — an
    /// id we do not recognise is exactly the case the server refuses with
    /// STORE_PRODUCT_UNMAPPED, and guessing here would paint a plan the user
    /// does not have.
    static func from(identifier: String) -> BirdoStoreProduct? {
        BirdoStoreProduct(rawValue: identifier)
    }
}

// MARK: - Storefront state

/// What the purchase UI shows. Exists so "no products resolved" can never
/// render as an empty list that looks like a broken screen, and so no state is
/// a spinner with no way out.
///
/// THE CASE THAT MATTERS TODAY is `.unavailable`. Until the Paid Applications
/// Agreement is active there are no products in App Store Connect, so a real
/// device gets an EMPTY array back — not an error. An empty array must
/// therefore be a first-class, explained state.
enum StorefrontState: Equatable, Sendable {
    /// Products are being fetched. The UI must pair this with a deadline —
    /// see `loadDeadlineSeconds`.
    case loading
    /// At least one product resolved; the cards are purchasable.
    case ready
    /// The fetch finished (or timed out) and nothing is purchasable. Carries
    /// the honest explanation to show the user.
    case unavailable(String)

    /// Hard ceiling on the spinner. `Product.products(for:)` can hang behind a
    /// captive portal or a wedged storefront, and a purchase screen that spins
    /// forever is indistinguishable from a broken app to App Review.
    static let loadDeadlineSeconds: Double = 15

    /// Shown when StoreKit answered normally but returned no products. This is
    /// the expected state on the App Store today, so the copy must not read
    /// like a crash — and must not promise a date we do not control.
    static let noProductsMessage =
        "Subscriptions can't be purchased in the app just yet — the App Store has no Birdo "
        + "products to offer on this device. Nothing is wrong with your account, and anything "
        + "you already pay for still works. Please try again after the next update."

    /// Shown when the fetch itself failed (offline, storefront error, timeout).
    static let loadFailedMessage =
        "Couldn't reach the App Store to load subscription prices. Check your connection and "
        + "try again."

    /// The single decision point, kept pure so every branch is testable.
    ///
    /// - Parameters:
    ///   - isLoading: a fetch is in flight.
    ///   - hasAttempted: at least one fetch has completed, successfully or not.
    ///     Distinguishes "not asked yet" from "asked and got nothing".
    ///   - productCount: how many products StoreKit actually returned.
    ///   - loadFailed: the fetch threw or hit the deadline.
    static func decide(isLoading: Bool,
                       hasAttempted: Bool,
                       productCount: Int,
                       loadFailed: Bool) -> StorefrontState {
        // A stale-but-good catalogue beats a spinner: if we already have
        // products, a background refresh must not blank the screen.
        if productCount > 0 { return .ready }
        if isLoading { return .loading }
        if !hasAttempted { return .loading }
        return .unavailable(loadFailed ? loadFailedMessage : noProductsMessage)
    }
}

// MARK: - Server refusals

/// How `POST /payments/store/apple/link` answered.
///
/// Classification is on the HTTP STATUS and the backend's `details.code` ONLY
/// — never on the message text, which is copy that changes without notice
/// (the same rule `AnonymousCreateFailure` already follows).
///
/// The codes come from `AppleStoreService` on birdo-web's
/// `feat/store-entitlement-rail`:
///   409 STORE_TRANSACTION_ALREADY_LINKED  (reason ALREADY_OWNED |
///                                          PURCHASED_ON_ANOTHER_ACCOUNT)
///   409 STORE_TRANSACTION_NOT_PURCHASED   (reason FAMILY_SHARED | UNKNOWN)
///   409 STORE_PRODUCT_UNMAPPED
enum StoreLinkRefusal: Equatable, Sendable {
    /// This App Store subscription belongs to a DIFFERENT Birdo account. The
    /// account-sharing refusal. Not an error to retry — a decision to explain.
    case alreadyLinkedToAnotherAccount
    /// Apple says the presenter did not buy it (Family Sharing, or an
    /// ownership type nobody has taught the backend about).
    case notThePurchaser
    /// The backend does not map this product id to a plan.
    case productUnmapped
    /// No usable session. The purchase is safe; it just cannot be bound yet.
    case needsSignIn
    /// The rail's 20-per-60s bucket. Genuinely transient.
    case rateLimited
    /// Everything else: 5xx, transport failures, unparseable answers.
    case transient

    /// Classify a failed /link call.
    ///
    /// - Parameters:
    ///   - status: HTTP status, or nil when the client minted the failure
    ///     (transport error, unusable 2xx body).
    ///   - code: the backend's `details.code`, when it sent one.
    static func classify(status: Int?, code: String?) -> StoreLinkRefusal {
        // The code is authoritative when present — it is the field the backend
        // added precisely so a client would not have to guess from a status.
        switch code {
        case "STORE_TRANSACTION_ALREADY_LINKED": return .alreadyLinkedToAnotherAccount
        case "STORE_TRANSACTION_NOT_PURCHASED":  return .notThePurchaser
        case "STORE_PRODUCT_UNMAPPED":           return .productUnmapped
        default: break
        }
        switch status {
        case 401, 403: return .needsSignIn
        case 429:      return .rateLimited
        // A 409 whose code we do not recognise is still a CONFLICT: the server
        // decided, and repeating the identical request gets the identical
        // answer. Treat it as terminal rather than retrying into a wall.
        case 409:      return .alreadyLinkedToAnotherAccount
        default:       return .transient
        }
    }

    /// Is another attempt with the same transaction pointless?
    ///
    /// This is what stops the account-sharing 409 from becoming a retry loop:
    /// terminal refusals are recorded once and never re-presented for the rest
    /// of the app session.
    var isTerminal: Bool {
        switch self {
        case .alreadyLinkedToAnotherAccount, .notThePurchaser, .productUnmapped:
            return true
        case .needsSignIn, .rateLimited, .transient:
            return false
        }
    }

    /// Should the transaction be `finish()`ed on this outcome?
    ///
    /// ALWAYS FALSE. A transaction is finished only once the server has
    /// ACCEPTED it. Leaving a refused one unfinished means Apple re-presents
    /// it on the next launch, so the day a support ownership-transfer happens
    /// the entitlement binds itself with no further action from the user —
    /// and, because it is exactly one re-presentation per launch rather than a
    /// loop, it costs one request, not a rate-limit ban. Suppressing repeats
    /// WITHIN a session is `isTerminal`'s job, not `finish()`'s.
    var shouldFinishTransaction: Bool { false }

    /// Fallback copy, used only when the server did not explain itself. When
    /// it did, show ITS message — those strings were written for these exact
    /// cases (the "you bought this on a different Birdo account" one in
    /// particular names the way out, including the support transfer).
    var fallbackMessage: String {
        switch self {
        case .alreadyLinkedToAnotherAccount:
            return "This App Store subscription is already linked to a different Birdo account. "
                + "Sign in to that account to use it, or contact support and we can transfer it."
        case .notThePurchaser:
            return "Birdo can only activate an App Store subscription for the person who bought "
                + "it, so this one has not been added to your account."
        case .productUnmapped:
            return "Birdo does not recognise this App Store product yet. Your purchase is safe — "
                + "please contact support and we will activate it."
        case .needsSignIn:
            return "Sign in to Birdo to add this subscription to your account. Your purchase is "
                + "safe: tap Restore Purchases once you are signed in."
        case .rateLimited:
            return "Too many requests in a row. Your purchase is safe — wait a minute, then tap "
                + "Restore Purchases."
        case .transient:
            return "Your purchase went through, but Birdo could not confirm it just now. Your "
                + "purchase is safe — tap Restore Purchases to try again."
        }
    }

    /// Tone for the banner. A refusal the user cannot act on is not an alarm.
    var isAlarming: Bool {
        switch self {
        case .alreadyLinkedToAnotherAccount, .notThePurchaser: return false
        default: return true
        }
    }
}

// MARK: - Restore outcome

/// What "Restore Purchases" found. Apple REQUIRES a visible restore control
/// for auto-renewable subscriptions, and it must say something in every case —
/// including the common one where there is nothing to restore.
enum StoreRestoreOutcome: Equatable, Sendable {
    /// n entitlements were found and accepted by the server.
    case restored(Int)
    /// StoreKit reported no current entitlements for this Apple ID.
    case nothingToRestore
    /// Entitlements were found but the server refused them.
    case refused(String)
    /// Could not talk to the server.
    case failed(String)

    static let nothingToRestoreMessage =
        "No Birdo subscription was found for this Apple ID. If you bought one with a different "
        + "Apple ID, sign in to that one in the App Store and try again."

    var message: String {
        switch self {
        case .restored(let count):
            return count == 1
                ? "Your subscription has been restored to this account."
                : "\(count) subscriptions have been restored to this account."
        case .nothingToRestore: return Self.nothingToRestoreMessage
        case .refused(let text): return text
        case .failed(let text):  return text
        }
    }

    var isSuccess: Bool {
        if case .restored = self { return true }
        return false
    }
}
