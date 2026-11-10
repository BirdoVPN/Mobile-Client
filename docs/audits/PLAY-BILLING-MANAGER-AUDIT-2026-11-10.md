# PlayBillingManager audit (MR-967, MR-968, MA-AND-007)

Scope: `app/src/main/java/app/birdo/vpn/billing/PlayBillingManager.kt` at main `4df80ba`, with the parts of
`PurchaseIngestor.kt` and `PlayStoreCatalog.kt` it depends on. Read-only audit; nothing here was fixed.
Line numbers are `PlayBillingManager.kt` unless a file is named. Classes: DEFECT (wrong behaviour reachable
today), RISK (needs a particular timing or state to bite), FINE (checked, sound).

## Acknowledgement rules

| # | Class | Where | Finding |
|---|---|---|---|
| 1 | FINE | PurchaseIngestor.kt:101-165, PBM:765-776 | `acknowledgePurchase` has one call site (PBM:770), reached only from the `Accepted` branch of `ingest` after `link` succeeded. A refused purchase takes the `Refused` branch (PurchaseIngestor.kt:155-163), records the refusal and never acknowledges; `check(!shouldAcknowledge)` backs it. |
| 2 | FINE | PurchaseIngestor.kt:112-113 | A PENDING purchase is returned as `Pending` before any link or acknowledge. Acknowledging a pending purchase is a DEVELOPER_ERROR, so skipping it is correct. UNSPECIFIED is never trusted or acknowledged (:117-121). |
| 3 | FINE | PurchaseIngestor.kt:146-147 | An already-acknowledged purchase skips the call (one round trip). |
| 4 | FINE | PBM:774-775 | `ITEM_ALREADY_OWNED` from acknowledge counts as success (the server's own acknowledgement won the race). |
| 5 | RISK | PBM:719-724, 765-771 | A failed acknowledge after server accept is only logged (debug builds only, `log` at :784-786) and not retried until the next reconcile. The server acknowledges from /link and RTDN, so the backstop exists; the gap is that the client's third chance is silent in release builds. Google refunds an unacknowledged purchase after 3 days. |
| 6 | RISK | PBM:769-771 | `acknowledgePurchase` has no timeout and no cancellation handling beyond `suspendCancellableCoroutine`. If the billing service is mid-reconnect the callback may never arrive; the coroutine then holds whatever lock its caller holds (see #14). |

## Pending purchases

| # | Class | Where | Finding |
|---|---|---|---|
| 7 | FINE | PBM:172-177 | `enablePendingPurchases` with one-time products and prepaid plans (required by PBL 8+). |
| 8 | FINE | PBM:593-610, 631-675 | Pending purchases reach the app-scoped listener or the reconcile sweep and are surfaced as an info notice; `ownsBirdoSubscription` is set only for PURCHASED (:597-603, :654-656). |
| 9 | RISK | PBM:604-609 | `onPurchasesUpdated(OK)` ingests every purchase in the list without the "is it one of our products" filter that `reconcile` applies (:653). A foreign product id earns a server 409 and an alarming notice for something the user did not buy through Birdo. Low reach (the listener only sees purchases made through this app's own flow). |

## Obfuscated account id

| # | Class | Where | Finding |
|---|---|---|---|
| 10 | FINE | PBM:445, 494-499 | The id is minted by the server (`mintGooglePurchaseIntent`) BEFORE the flow, and set with `setObfuscatedAccountId` (a UUID, 36 chars, under Play's 64 limit). A failed mint never shows the Play sheet and says so (:446-460, 429 has its own copy). |
| 11 | RISK | PBM:714-758, 650-668 | The client never compares `Purchase.accountIdentifiers.obfuscatedAccountId` to the id it minted. Binding relies wholly on the server matching the intent row. On reconcile/restore, a purchase from Google account A is presented while Birdo account B is signed in; the server must refuse or transfer it. The client cannot tell a "wrong account" refusal from any other (it is a generic `Refused`). Server side to confirm (birdo-web `google-play-store.service.ts`). |
| 12 | RISK | PBM:134-163, 144 | No sign-out / account-switch hook: `knownPurchases`, `ownsBirdoSubscription` and the `PurchaseRefusalMemory` survive a logout and a login as someone else, until the next reconcile or Restore. Terminal refusals stay suppressed for the whole app session (PlayStoreCatalog.kt:410-440) and `ownsBirdoSubscription` can hide purchase buttons from the new account until the first reconcile answers. `onSignedIn` (:236) reconciles but does not clear either. |
| 13 | FINE | PBM:476-490, 533-539 | Cross-grade uses the PBL 9 product replacement params keyed on the old product id; null when only the same product is owned (avoids Play rejecting a self replacement). `knownPurchases` is only refreshed by reconcile, so it can be stale for a cross-grade started right after another device's purchase (a RISK of an ITEM_ALREADY_OWNED, which then reconciles, :546-548). |

## Restore, reconcile, errors, reconnect

| # | Class | Where | Finding |
|---|---|---|---|
| 14 | DEFECT (concurrency) | PBM:245-278, 631-675, 216-239 | `refreshInternal` is serialised by `gate`, but `reconcile` is also launched directly, outside the gate, from `onAppResumed` (:226), `onSignedIn` (:238), `reconcileAfterAlreadyOwned` (:583) and `restorePurchases` (:694). Two sweeps can run at once (resume + sign-in at cold start is the common pair) and both present the same token to `/link`. The server is idempotent per token, but the second call can trip the rail's 20/60 s bucket and each run races on `knownPurchases` (:650) and `lastRefusalText` (:711). Fix: run reconcile under `gate`. |
| 15 | DEFECT (thread safety) | PlayStoreCatalog.kt:410-440 | `PurchaseRefusalMemory` uses a plain `mutableSetOf` and `mutableMapOf`, touched from concurrent `Dispatchers.Default` coroutines (see #14: `record`, `shouldSkip`, `clear` from restore). A `ConcurrentModificationException` or a lost write is possible. Fix: `synchronized` or a mutex; trivial, but it is outside this file so left for the lead. |
| 16 | RISK | PBM:277, 245-252 | `reconcile` runs inside `gate.withLock` but outside the `withTimeoutOrNull` deadline (:262). A wedged Play Store (query or connect never calling back) holds `gate` indefinitely, and every later `start()`/`refresh()` queues behind it. The same applies to `restorePurchases`: no deadline, so the Restore spinner (`isRestoring`) can spin forever. The `finally` at :695-697 only covers a throw, not a hang. |
| 17 | RISK | PBM:281-305 | `connect()` can be called concurrently (from `reconcile` outside the gate, #14). Two `startConnection` calls on one client are tolerated by the library but each registers a listener; the `resumed` flag is a plain var (fine, callbacks are on the main thread). Minor. |
| 18 | FINE | PBM:182, 292-303 | `enableAutoServiceReconnection` set; a disconnect before setup finished is resumed once so the coroutine does not hang until the deadline. |
| 19 | FINE | PBM:306-326 | Connect result mapping: unavailable/unsupported/other all end in a user-facing storefront failure with no purchase affordance (the offers list is the only thing that enables a buy button, :49-54). |
| 20 | FINE | PBM:682-705 | Restore always answers (nothing to restore, refused, failed, restored); forgets refusals first; requires sign-in. Double tap is rejected by `isRestoring`, though the check-then-set (:683, :689) is not atomic (RISK, trivial: two taps in the same frame run two sweeps). |
| 21 | RISK | PBM:672-674 | A sweep where every purchase was `Skipped` (refusal memory) returns `Refused` with the TRANSIENT fallback text, so Restore says "try again" for a terminal refusal. Restore calls `forgetRefusals` first, so only the resume/sign-in sweeps hit it, and those are silent (`announce = false`). Cosmetic. |
| 22 | RISK | PBM:141-142, 608, 669 | `entitlementChanged` is a `MutableSharedFlow` with no replay and buffer 1, collected only in `BirdoNavGraph.kt:211` (Activity lifetime). An accept while the Activity is gone (a purchase approved in the background, the process kept alive) emits into the void; the plan snapshot is refreshed only by the next stats fetch or resume. |

## Server verification path

| # | Class | Where | Finding |
|---|---|---|---|
| 23 | FINE | PBM:146-152 | Only the purchase token goes to the server (`linkGooglePurchase`); `IngestablePurchase.toString` redacts it. Entitlement is written by the server after its own Play Developer API check; the client never grants anything locally. |
| 24 | FINE | PBM:195, 241 | The whole rail is gated on `BuildConfig.IS_PLAY_BUILD`; F-Droid and sideload builds never bind the billing service. |

## Threading

| # | Class | Where | Finding |
|---|---|---|---|
| 25 | FINE | PBM:504-510 | `launchBillingFlow` runs on `Dispatchers.Main` and only if the Activity is still alive. |
| 26 | FINE | PBM:131, 133-134 | Scope is `SupervisorJob + Default`; state is a `MutableStateFlow` updated with `update{}` (atomic). |
| 27 | RISK | PBM:157-159, 413-419 | `hasAttemptedLoad`, `isLoading`, `lastFailure` are plain vars written under `gate` in `refreshInternal` and without it in `markUnavailable` (called from `start()` on the caller thread). Only reachable when the rail is disabled, where nothing else writes them. Cosmetic. |
| 28 | RISK | PBM:433-441 | `purchase()` checks `purchasingProductId` then sets it non-atomically; two taps from two threads could both launch. In practice both calls are on the main thread. |
| 29 | FINE | PBM:784-786 | Logging is gated on `BuildConfig.DEBUG` and never prints a token (only product ids and response codes). |

## Test coverage and the fake-client seam

`PurchaseIngestorTest`, `StorePurchaseGateTest`, `PlayStoreCatalogTest` and `GoogleStoreContractTest` cover the
policy (ordering of link then acknowledge, refusal memory, gate). `PlayBillingManager` itself has NO test:
`billingClient` is built inside the class (`by lazy { BillingClient.newBuilder(context)... }`, :165-184), so a
fake client cannot be injected without a change to the class. The seam needed is small: a constructor
parameter `billingClientFactory: (PurchasesUpdatedListener) -> BillingClient` defaulting to the current
builder (Hilt `@Inject` needs a provider or a secondary constructor), plus an injectable `Dispatcher` for
`scope`. `BillingClient` is an abstract class, so MockK can fake it on the JVM. Not done here: it touches the
Hilt graph and is more than a trivial edit.

## Suggested follow-ups (for the lead)

1. #14 + #15: run `reconcile` under `gate` and make `PurchaseRefusalMemory` thread-safe (small, one PR).
2. #16: put `reconcile`/`restorePurchases` under a deadline (reuse `StorefrontState.LOAD_DEADLINE_MS`).
3. #12: clear `knownPurchases`, `ownsBirdoSubscription` and refusal memory on sign-out / account change.
4. #11: confirm on the server that a purchase whose obfuscated account id belongs to another Birdo account is
   refused with a distinct reason, and have the client use it.
5. The billing-with-fake-client test after the seam above.
