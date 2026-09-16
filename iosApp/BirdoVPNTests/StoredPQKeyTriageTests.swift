import XCTest

/// The iOS half of the BirdoPQ re-key mitigation.
///
/// ## The defect these pin
///
/// `birdo_pq_stored_key_usable` was added to the Rust C-ABI crate, declared in
/// `birdo_pq_ios.h`, exported from the xcframework — and never called from
/// Swift. `iosApp/` was not touched at all. The Android twin self-heals; iOS
/// did not, so an install whose stored ML-KEM key fails the FIPS 203 §7.3
/// check `ml-kem` now enforces would have thrown `quantumHandshakeFailed` on
/// every connect, forever, with nothing but a logout able to clear it.
///
/// Two things had to become visible to CI for that to be catchable:
///
///   1. the DECISION — pinned here, on a pure function;
///   2. the WIRING — `scripts/check_pq_ios_wiring.sh`, which fails if a
///      production `birdo_pq_*` export has no Swift call site.
///
/// `BirdoPQManager.swift` itself cannot be compiled into this bundle (it
/// imports `BirdoPQNative` and talks to the data-protection Keychain, which a
/// host-less unit-test bundle has no entitlement for), which is exactly why
/// the rule was extracted into `StoredPQKeyTriage.swift`.
final class StoredPQKeyTriageTests: XCTestCase {

    /// The real FIPS 203 ML-KEM-1024 lengths. Passed as parameters in
    /// production (from `BIRDO_PQ_PUBLIC_KEY_LEN` / `BIRDO_PQ_SECRET_KEY_LEN`)
    /// so the header stays the single source of truth; repeated here so a
    /// silent change to either constant shows up as a failing test rather than
    /// as a triage rule that quietly accepts a different key size.
    private static let pkLen = 1568
    private static let skLen = 3168
    private static var blobLen: Int { pkLen + skLen }

    private func decide(
        blob: Data?,
        loadable: Bool = true,
        onSecretKey: ((Data) -> Void)? = nil
    ) -> StoredPQKeyDecision {
        StoredPQKeyTriage.decide(
            blob: blob,
            publicKeyLength: Self.pkLen,
            secretKeyLength: Self.skLen,
            secretKeyIsLoadable: { sk in
                onSecretKey?(sk)
                return loadable
            }
        )
    }

    // MARK: - The branch that was missing

    /// THE regression test. Before the fix nothing consulted the native KEM at
    /// all: a well-sized blob was returned as-is and used on every connect.
    func testUnloadableSecretKeyIsDiscardedAndRekeyed() {
        let blob = Data(repeating: 0x5A, count: Self.blobLen)
        guard case .discardAndRekey(let reason) = decide(blob: blob, loadable: false) else {
            return XCTFail("a stored key the KEM cannot load must be discarded, not reused — "
                           + "reusing it fails quantumHandshakeFailed on every connect, forever")
        }
        XCTAssertTrue(reason.contains("7.3"),
                      "the log line should name the check that rejected it, got: \(reason)")
    }

    /// And the native check must actually be consulted — with the SECRET KEY,
    /// re-based. `Data.suffix` keeps the parent's indices, so handing the raw
    /// slice to `withUnsafeBytes` would read 1568 bytes past the start.
    func testTheNativeCheckIsAskedAboutTheRebasedSecretKey() {
        var blob = Data(repeating: 0x11, count: Self.pkLen)
        blob.append(Data(repeating: 0x22, count: Self.skLen))

        var seen: Data?
        _ = decide(blob: blob, loadable: true, onSecretKey: { seen = $0 })

        guard let seen else {
            return XCTFail("secretKeyIsLoadable was never called — the FIPS 203 check was skipped")
        }
        XCTAssertEqual(seen.count, Self.skLen)
        XCTAssertEqual(seen.startIndex, 0, "the slice must be re-based before it reaches the FFI")
        XCTAssertEqual(seen.first, 0x22, "the secret-key half, not the public-key half")
        XCTAssertEqual(seen.last, 0x22)
    }

    // MARK: - The branches that already existed

    func testWellFormedLoadableKeyIsUsed() {
        let blob = Data(repeating: 0x5A, count: Self.blobLen)
        XCTAssertEqual(decide(blob: blob, loadable: true), .use)
    }

    func testNoStoredBlobGeneratesFresh() {
        XCTAssertEqual(decide(blob: nil), .generateFresh)
    }

    func testWrongSizedBlobIsDiscardedWithoutCallingTheKEM() {
        var asked = false
        let decision = decide(
            blob: Data(repeating: 0x5A, count: Self.blobLen - 1),
            loadable: true,
            onSecretKey: { _ in asked = true }
        )
        guard case .discardAndRekey(let reason) = decision else {
            return XCTFail("a short blob must be discarded, got \(decision)")
        }
        XCTAssertFalse(asked, "a blob of the wrong length must not be handed to the FFI at all")
        XCTAssertTrue(reason.contains("\(Self.blobLen)"),
                      "the log line should name the expected size, got: \(reason)")
    }

    /// An empty item is not "absent": it is a corrupt item and must be deleted,
    /// otherwise it is re-read on every launch.
    func testEmptyBlobIsDiscardedNotTreatedAsAbsent() {
        guard case .discardAndRekey = decide(blob: Data(), loadable: true) else {
            return XCTFail("an empty stored item must be deleted, not mistaken for no item")
        }
    }

    /// The decision is a pure function of its inputs — no Keychain, no cache,
    /// no native library — which is the only reason it can be asserted here.
    func testDecisionIsDeterministic() {
        let blob = Data(repeating: 0x5A, count: Self.blobLen)
        XCTAssertEqual(decide(blob: blob, loadable: false), decide(blob: blob, loadable: false))
    }
}
