import XCTest

/// MR-745 — pins `BirdoPQNonce`, compiled directly into this un-hosted bundle.
///
/// The server-minted per-connect nonce becomes the HKDF `info` of the BirdoPQ
/// PSK. iOS used to accept any length, including an empty one; Android and
/// desktop refuse anything outside 1...64 bytes. These pin the same bound, and
/// that an out-of-bounds value is refused (nil, so the derivation fails closed)
/// rather than truncated or padded into something usable.
final class PQNonceTests: XCTestCase {

    private func base64(count: Int) -> String {
        Data((0..<count).map { UInt8(truncatingIfNeeded: $0) }).base64EncodedString()
    }

    /// The server mints 32 bytes: the everyday case decodes unchanged.
    func testTheServersThirtyTwoByteNonceDecodes() {
        let expected = Data((0..<32).map { UInt8(truncatingIfNeeded: $0) })
        XCTAssertEqual(BirdoPQNonce.decode(expected.base64EncodedString()), expected)
    }

    func testBothEndsOfTheBoundAreAccepted() {
        XCTAssertEqual(BirdoPQNonce.decode(base64(count: 1))?.count, 1)
        XCTAssertEqual(BirdoPQNonce.decode(base64(count: 64))?.count, 64)
    }

    /// THE regression: before MR-745 a 65-byte (or 64 KiB) nonce reached the
    /// native HKDF as-is.
    func testANonceLongerThanSixtyFourBytesIsRefused() {
        XCTAssertNil(BirdoPQNonce.decode(base64(count: 65)),
                     "a 65-byte nonce must be refused, as Android refuses it")
        XCTAssertNil(BirdoPQNonce.decode(base64(count: 64 * 1024)),
                     "an unbounded nonce must be refused before it is even decoded")
    }

    func testAnAbsentEmptyOrMalformedNonceIsRefused() {
        XCTAssertNil(BirdoPQNonce.decode(nil))
        XCTAssertNil(BirdoPQNonce.decode(""))
        XCTAssertNil(BirdoPQNonce.decode("not base64!"))
        // Valid Base64 of zero bytes is still no nonce.
        XCTAssertNil(BirdoPQNonce.decode(Data().base64EncodedString()))
    }

    func testTheBoundMatchesAndroid() {
        // Android: BirdoPqManager.MAX_NONCE_BYTES = 64.
        XCTAssertEqual(BirdoPQNonce.maxBytes, 64)
        XCTAssertEqual(BirdoPQNonce.maxBase64Length, base64(count: 64).count)
    }
}
