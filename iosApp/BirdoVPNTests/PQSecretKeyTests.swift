import XCTest

/// MR-2123: the container `BirdoPQManager` keeps the ML-KEM secret key in.
///
/// What can be asserted without a debugger: the key goes in intact, the
/// native call sees exactly those bytes, and `wipe()` really leaves zeros in
/// the SAME buffer (the one the native call read), not in a copy of it.
final class PQSecretKeyTests: XCTestCase {

    private static let skLen = 3168

    private func pattern() -> [UInt8] {
        (0..<Self.skLen).map { UInt8(truncatingIfNeeded: $0 &* 13 &+ 1) }
    }

    func testHoldsExactlyTheBytesItWasGiven() {
        let key = PQSecretKey(copying: pattern())
        XCTAssertEqual(key.count, Self.skLen)
        key.withUnsafeBytes { XCTAssertEqual(Array($0), pattern()) }
    }

    func testCopiesFromDataSlicesToo() {
        // BirdoPQManager builds it from `blob.suffix(skLen)`, a slice whose
        // indices do not start at zero.
        var blob = Data(repeating: 0x11, count: 1568)
        blob.append(contentsOf: pattern())
        let key = PQSecretKey(copying: blob.suffix(Self.skLen))
        key.withUnsafeBytes { XCTAssertEqual(Array($0), pattern()) }
    }

    func testWipeZeroesTheBufferTheNativeCallReads() {
        let key = PQSecretKey(copying: pattern())
        let before = key.withUnsafeBytes { $0.baseAddress }

        key.wipe()

        key.withUnsafeBytes { bytes in
            XCTAssertEqual(bytes.baseAddress, before, "wiped in place, not swapped for a new buffer")
            XCTAssertTrue(bytes.allSatisfy { $0 == 0 }, "the secret key is still in memory after wipe()")
        }
        key.wipe() // idempotent
        XCTAssertEqual(key.count, Self.skLen)
    }

    func testTheSourceIsNotAliased() {
        var source = pattern()
        let key = PQSecretKey(copying: source)
        for index in source.indices { source[index] = 0 }
        key.withUnsafeBytes { XCTAssertEqual(Array($0), pattern(), "a copy, so the caller can wipe its own") }
    }
}
