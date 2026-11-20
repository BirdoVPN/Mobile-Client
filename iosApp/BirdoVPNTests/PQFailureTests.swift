import XCTest

/// MR-2287 (A-M37) — pins `PQFailure` and `PQDecapsulationPlan`, compiled
/// directly into this un-hosted bundle.
///
/// `BirdoPQManager.tryDecapsulate` returned the same nil for a classical
/// session and for every way a post-quantum one could fail, and the reason
/// reached only a Debug-build log. These pin that each failure is now told
/// apart, in the order the manager always checked, that a classical session
/// is still not a failure, and that the codes stay fixed literals safe to
/// show and to count.
final class PQFailureTests: XCTestCase {

    /// ML-KEM-1024's ciphertext length (`BIRDO_PQ_CIPHERTEXT_LEN`).
    private let ctLen = 1568

    private func bytes(_ count: Int) -> Data {
        Data((0..<count).map { UInt8(truncatingIfNeeded: $0) })
    }

    private func plan(quantumEnabled: Bool = true,
                      ciphertext: String?,
                      nonce: Data?,
                      hasKeypair: Bool = true) throws -> PQDecapsulationPlan {
        try PQDecapsulationPlan.make(quantumEnabled: quantumEnabled,
                                     ciphertextBase64: ciphertext,
                                     nonce: nonce,
                                     ciphertextLength: ctLen,
                                     hasKeypair: hasKeypair)
    }

    private func assertFails(_ expected: PQFailure,
                             _ body: () throws -> PQDecapsulationPlan,
                             file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try body(), file: file, line: line) { error in
            XCTAssertEqual(error as? PQFailure, expected, file: file, line: line)
        }
    }

    // MARK: - The plan

    func testAWellFormedPostQuantumAnswerIsDerived() throws {
        let ct = bytes(ctLen)
        let nonce = bytes(32)
        XCTAssertEqual(try plan(ciphertext: ct.base64EncodedString(), nonce: nonce),
                       .derive(ciphertext: ct, nonce: nonce))
    }

    /// The one nil `tryDecapsulate` may still return: the server did not make
    /// the session post-quantum. Whatever else the answer holds is ignored.
    func testAClassicalSessionIsNotAFailure() throws {
        XCTAssertEqual(try plan(quantumEnabled: false, ciphertext: nil, nonce: nil, hasKeypair: false),
                       .classical)
        XCTAssertEqual(try plan(quantumEnabled: false, ciphertext: "junk", nonce: nil), .classical)
    }

    /// THE regression: each of these was the same nil as a classical session.
    func testEachPostQuantumFailureHasItsOwnReason() {
        let goodCt = bytes(ctLen).base64EncodedString()
        let goodNonce = bytes(32)
        assertFails(.ciphertextMissing) { try plan(ciphertext: nil, nonce: goodNonce) }
        assertFails(.ciphertextMalformed) { try plan(ciphertext: "not base64!", nonce: goodNonce) }
        assertFails(.ciphertextWrongSize) {
            try plan(ciphertext: bytes(ctLen - 1).base64EncodedString(), nonce: goodNonce)
        }
        assertFails(.ciphertextWrongSize) { try plan(ciphertext: "", nonce: goodNonce) }
        assertFails(.nonceInvalid) { try plan(ciphertext: goodCt, nonce: nil) }
        assertFails(.keypairUnavailable) { try plan(ciphertext: goodCt, nonce: goodNonce, hasKeypair: false) }
    }

    /// The manager's order: ciphertext, then nonce, then keypair. The first
    /// thing wrong is the reason, so an answer with everything wrong reports
    /// its ciphertext.
    func testTheFirstFailingCheckIsTheReason() {
        assertFails(.ciphertextMissing) { try plan(ciphertext: nil, nonce: nil, hasKeypair: false) }
        assertFails(.ciphertextMalformed) { try plan(ciphertext: "%%%", nonce: nil, hasKeypair: false) }
        assertFails(.nonceInvalid) {
            try plan(ciphertext: bytes(ctLen).base64EncodedString(), nonce: nil, hasKeypair: false)
        }
    }

    /// The nonce reaching the plan is `BirdoPQNonce.decode`'s answer, so an
    /// out-of-bounds nonce (MR-745) is `nonceInvalid`, not a derivation.
    func testAnOutOfBoundsNonceIsInvalid() {
        let tooLong = BirdoPQNonce.decode(bytes(65).base64EncodedString())
        assertFails(.nonceInvalid) {
            try plan(ciphertext: bytes(ctLen).base64EncodedString(), nonce: tooLong)
        }
    }

    // MARK: - The codes

    /// Shown in the message and counted: fixed, lower snake case, `pq_`
    /// prefixed, unique, and short. A digit run or a dot would be the first
    /// sign of something interpolated into one.
    func testTheCodesAreFixedSafeLiterals() {
        let codes = PQFailure.allCases.map(\.code)
        XCTAssertEqual(Set(codes).count, codes.count, "two failures share a code")
        for code in codes {
            XCTAssertTrue(code.hasPrefix("pq_"), code)
            XCTAssertNotNil(code.range(of: "^[a-z_]{4,40}$", options: .regularExpression),
                            "\(code) is not a short lower_snake literal")
        }
        // Pinned: a rename changes what support reads and what was counted.
        XCTAssertEqual(codes, [
            "pq_server_not_pq",
            "pq_ciphertext_missing",
            "pq_ciphertext_malformed",
            "pq_ciphertext_wrong_size",
            "pq_nonce_invalid",
            "pq_keypair_unavailable",
            "pq_derive_failed",
        ])
    }

    /// Every PQ failure is counted under the same code it shows.
    func testEveryFailureIsCountedUnderItsOwnCode() {
        for failure in PQFailure.allCases {
            XCTAssertEqual(DialFailure(failure).rawValue, failure.code)
        }
    }
}
