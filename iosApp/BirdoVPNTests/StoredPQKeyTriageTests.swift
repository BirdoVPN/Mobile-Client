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

    // MARK: - MR-2121: the stored public key must be the one inside the secret key

    /// Where FIPS 203 puts `ek` in the expanded ML-KEM-1024 decapsulation key:
    /// `dk_pke (1536) || ek (1568) || H(ek) (32) || z (32)`.
    private static let embeddedOffset = 1536

    /// A blob laid out like a real one: `pk || sk`, with the pk embedded in the
    /// sk at 1536, and every other byte distinct from its neighbours so an
    /// off-by-one read cannot match by accident.
    private func agreeingBlob() -> Data {
        let pk = (0..<Self.pkLen).map { UInt8(truncatingIfNeeded: $0 &* 7 &+ 3) }
        var sk = (0..<Self.skLen).map { UInt8(truncatingIfNeeded: $0 &* 13 &+ 1) }
        sk.replaceSubrange(Self.embeddedOffset ..< Self.embeddedOffset + Self.pkLen, with: pk)
        return Data(pk) + Data(sk)
    }

    /// THE regression test, a port of desktop's
    /// `read_keypair_rejects_public_key_that_disagrees_with_secret_key`. The
    /// secret key loads (the KEM says so), so only comparing the two halves can
    /// catch this. Before the fix the pair was used, and a public key this
    /// client cannot decapsulate for was uploaded on every connect.
    func testStoredPublicKeyThatDisagreesWithTheSecretKeyIsDiscarded() {
        var blob = agreeingBlob()
        blob[0] ^= 0x01
        guard case .discardAndRekey(let reason) = decide(blob: blob, loadable: true) else {
            return XCTFail("a stored public key that is not the one inside the secret key must be "
                           + "discarded, not uploaded on every connect")
        }
        XCTAssertTrue(reason.contains("public key"),
                      "the log line should say the halves disagree, got: \(reason)")
    }

    /// The same, with the difference in the LAST byte of the embedded key, on
    /// the secret-key side: the whole 1568 bytes are compared.
    func testADifferenceAtTheEndOfTheEmbeddedKeyIsCaughtToo() {
        var blob = agreeingBlob()
        blob[Self.pkLen + Self.embeddedOffset + Self.pkLen - 1] ^= 0x80
        guard case .discardAndRekey = decide(blob: blob, loadable: true) else {
            return XCTFail("the comparison must cover the whole embedded key")
        }
    }

    func testAgreeingHalvesAreUsed() {
        XCTAssertEqual(decide(blob: agreeingBlob(), loadable: true), .use)
    }

    /// The offset is derived from the two lengths, not hardcoded.
    func testTheEmbeddedKeyIsReadWhereFips203PutsIt() {
        let blob = agreeingBlob()
        XCTAssertTrue(StoredPQKeyTriage.publicKeyMatchesSecretKey(
            blob: blob, publicKeyLength: Self.pkLen, secretKeyLength: Self.skLen))
        // A blob whose sk carries the pk one byte later does not match.
        let pk = blob.prefix(Self.pkLen)
        var shifted = agreeingBlob()
        let oneLate = Self.pkLen + Self.embeddedOffset + 1
        shifted.replaceSubrange(oneLate ..< oneLate + Self.pkLen, with: pk)
        XCTAssertFalse(StoredPQKeyTriage.publicKeyMatchesSecretKey(
            blob: shifted, publicKeyLength: Self.pkLen, secretKeyLength: Self.skLen))
    }

    /// Data slices keep their parent's indices. The comparison must index from
    /// the blob's own start, or a slice compares the wrong bytes.
    func testTheComparisonIndexesFromTheBlobsOwnStart() {
        let parent = Data([0xFF, 0xFE]) + agreeingBlob()
        let slice = parent.dropFirst(2)
        XCTAssertEqual(slice.startIndex, 2)
        XCTAssertEqual(decide(blob: slice, loadable: true), .use)
    }

    /// A key that fails §7.3 is still reported as that, not as a disagreement:
    /// the KEM's check comes first.
    func testAnUnloadableKeyIsReportedAsUnloadableEvenWhenTheHalvesAgree() {
        guard case .discardAndRekey(let reason) = decide(blob: agreeingBlob(), loadable: false) else {
            return XCTFail("an unloadable key must be discarded")
        }
        XCTAssertTrue(reason.contains("7.3"), "got: \(reason)")
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
