import XCTest
import CryptoKit

/// MR-2152 (A-H11): the sign-up proof-of-work.
///
/// The vectors below were produced by the SERVER's own code, not by this
/// solver: birdo-web `backend/src/auth/signup-pow.ts` at origin/main, run under
/// node with `powKeyFromSecret("unit-test-fixture-not-a-real-key")`,
/// `mintPowToken(key, "signup", bits, 1767225600000, 300, id)` and the server's
/// `solvePow(token, bits)` (first nonce in base-36 counting order), then
/// `powDigest` / `leadingZeroBits`. The same three vectors are pinned by
/// Android's SignupPowTest, so the apps cannot drift apart.
final class SignupPowTests: XCTestCase {

    private struct Vector {
        let bits: Int
        let token: String
        let nonce: String
        let digestHex: String
        let zeroBits: Int
    }

    private let vectors = [
        Vector(bits: 12,
               token: "p1.70c42181.signup.12.1767225900.00112233445566778899aabbccddeeff.FTFqEjbzmcjhEPSJfhOucNw2mAsGJfEwszbNogxzKXE",
               nonce: "4q",
               digestHex: "000c62912e9e0f0930c26f38adfdee013fa6f19ab882eb089bd7d5d4ab29e38f",
               zeroBits: 12),
        Vector(bits: 16,
               token: "p1.70c42181.signup.16.1767225900.0f1e2d3c4b5a69788796a5b4c3d2e1f0.7D8KY3uaVUM_wSXlvO-FGOBUwVWLoPWJxUG04Jq2RhU",
               nonce: "9g9",
               digestHex: "00003f9631bf9e92d5857b8e8391c7b270ff0f9b86746c8f720d751eb1f46545",
               zeroBits: 18),
        Vector(bits: 20,
               token: "p1.70c42181.signup.20.1767225900.a1b2c3d4e5f60718293a4b5c6d7e8f90.KDwfk6hm_h0tz-fkVqxIYsmJpyafRwzXXjiCrqCTlnc",
               nonce: "uzs8",
               digestHex: "00000fcc2990cae169300c5c0e8ff3132117becce2c288cbd5b8c8f8597f8c33",
               zeroBits: 20),
    ]

    private func hex<S: Sequence>(_ digest: S) -> String where S.Element == UInt8 {
        digest.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Solver

    func testDigestAndLeadingZeroBitsMatchEveryServerVector() {
        for v in vectors {
            let digest = SHA256.hash(data: Array("\(v.token):\(v.nonce)".utf8))
            XCTAssertEqual(hex(digest), v.digestHex, "bits=\(v.bits)")
            XCTAssertEqual(SignupPow.leadingZeroBits(digest), v.zeroBits, "bits=\(v.bits)")
            XCTAssertTrue(SignupPow.meets(challenge: v.token, nonce: v.nonce, bits: v.bits), "bits=\(v.bits)")
        }
    }

    func testSolveFindsExactlyTheFirstNonceTheServersOwnSolverFinds() async throws {
        for v in vectors {
            let nonce = try await SignupPow.solve(challenge: v.token, bits: v.bits)
            XCTAssertEqual(nonce, v.nonce, "bits=\(v.bits)")
        }
    }

    func testANonceBelowTheTargetIsNotAccepted() {
        // "4q" has 12 zero bits, not 13.
        XCTAssertFalse(SignupPow.meets(challenge: vectors[0].token, nonce: vectors[0].nonce, bits: 13))
    }

    func testNonceIsLowerCaseBase36FromZero() {
        XCTAssertEqual(SignupPow.nonce(for: 0), "0")
        XCTAssertEqual(SignupPow.nonce(for: 35), "z")
        XCTAssertEqual(SignupPow.nonce(for: 36), "10")
        XCTAssertEqual(SignupPow.nonce(for: UInt64("uzs8", radix: 36)!), "uzs8")
        // The server accepts 1 to 32 characters of [0-9A-Za-z].
        let widest = SignupPow.nonce(for: UInt64.max)
        XCTAssertTrue(widest.range(of: "^[0-9A-Za-z]{1,32}$", options: .regularExpression) != nil)
    }

    func testLeadingZeroBitsCountsWholeAndPartialBytes() {
        XCTAssertEqual(SignupPow.leadingZeroBits([UInt8]([0x80])), 0)
        XCTAssertEqual(SignupPow.leadingZeroBits([UInt8]([0x01])), 7)
        XCTAssertEqual(SignupPow.leadingZeroBits([UInt8]([0x00, 0xFF])), 8)
        XCTAssertEqual(SignupPow.leadingZeroBits([UInt8]([0x00, 0x0F])), 12)
        XCTAssertEqual(SignupPow.leadingZeroBits([UInt8]([0x00, 0x00])), 16)
    }

    func testADifficultyTheAppWillNotAttemptIsRefusedUpFront() async {
        do {
            _ = try await SignupPow.solve(challenge: vectors[0].token, bits: SignupPow.maxSolvableBits + 1)
            XCTFail("expected difficultyOutOfRange")
        } catch {
            XCTAssertEqual(error as? SignupPow.SolveError, .difficultyOutOfRange)
        }
    }

    func testASolveThatCannotFinishBeforeItsDeadlineGivesUpWithNil() async throws {
        // 28 bits is about 268M hashes; the deadline is already past, and the
        // loop looks at it on its first iteration.
        let nonce = try await SignupPow.solve(challenge: vectors[0].token, bits: 28,
                                              deadline: Date(timeIntervalSinceNow: -1))
        XCTAssertNil(nonce)
    }

    /// Cancelling the awaiting task stops the search. 28 bits cannot finish for
    /// this token within the first 2^22 nonces (checked with the server's own
    /// code: no nonce below 4194304 reaches more than 21 bits for it), so the
    /// search is certainly still running when it is cancelled.
    func testCancellingTheTaskStopsTheSolve() async throws {
        let challenge = vectors[0].token
        let task = Task { try await SignupPow.solve(challenge: challenge, bits: 28) }
        try await Task.sleep(nanoseconds: 150_000_000)
        task.cancel()
        do {
            let nonce = try await task.value
            XCTFail("a 28-bit solve finished (\(nonce == nil ? "nil" : "nonce")) instead of being cancelled")
        } catch is CancellationError {
            // expected
        } catch {
            XCTFail("expected CancellationError, got \(type(of: error))")
        }
    }

    // MARK: - Wire

    private func encoded<T: Encodable>(_ value: T) throws -> [String: Any] {
        let data = try JSONEncoder().encode(value)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testChallengeRequestIsTheSignupOp() throws {
        XCTAssertEqual(try encoded(PowChallengeRequest()) as? [String: String], ["op": "signup"])
    }

    func testRegisterBodyCarriesPowOnlyWhenThereIsOne() throws {
        let plain = try encoded(DeviceInfoBody(deviceId: "d", deviceName: "n", platformVersion: "17", appVersion: "1"))
        XCTAssertNil(plain["pow"], "a nil proof must be omitted, not sent as null")
        XCTAssertEqual(plain["deviceId"] as? String, "d")

        let withPow = try encoded(DeviceInfoBody(deviceId: "d", deviceName: "n", platformVersion: "17", appVersion: "1",
                                                 pow: PowSolution(challenge: "tok", nonce: "4q")))
        let pow = try XCTUnwrap(withPow["pow"] as? [String: String])
        XCTAssertEqual(pow, ["challenge": "tok", "nonce": "4q"])
    }

    func testChallengeResponseDecodesTheServersAnswer() throws {
        let json = """
        {"algorithm":"sha256-leading-zero-bits","op":"signup","challenge":"\(vectors[2].token)",
         "difficulty":20,"expiresAt":"2026-10-30T12:05:00.000Z","expiresInSeconds":300}
        """
        let c = try JSONDecoder().decode(PowChallengeResponse.self, from: Data(json.utf8))
        XCTAssertEqual(c.algorithm, "sha256-leading-zero-bits")
        XCTAssertEqual(c.difficulty, 20)
        XCTAssertEqual(c.expiresInSeconds, 300)
        XCTAssertEqual(c.challenge, vectors[2].token)
        XCTAssertTrue(SignupPowFlow.isSolvable(c))
    }

    func testModelsPrintNeitherTheChallengeNorTheNonce() {
        let c = PowChallengeResponse(algorithm: SignupPow.algorithm, op: "signup", challenge: "challenge-secret-xyz",
                                     difficulty: 20, expiresInSeconds: 300)
        let s = PowSolution(challenge: "challenge-secret-xyz", nonce: "nonce-secret-xyz")
        let body = DeviceInfoBody(deviceId: "d", deviceName: "n", platformVersion: "17", appVersion: "1", pow: s)
        for model in [c as Any, s as Any, body as Any] {
            var dumped = ""
            dump(model, to: &dumped)
            for printed in [String(describing: model), "\(model)", String(reflecting: model), dumped] {
                XCTAssertFalse(printed.contains("challenge-secret-xyz"), "\(type(of: model)) printed the challenge")
                XCTAssertFalse(printed.contains("nonce-secret-xyz"), "\(type(of: model)) printed the nonce")
            }
        }
        XCTAssertEqual(s.description, "PowSolution(challenge: [REDACTED], nonce: [REDACTED])")
    }

    // MARK: - Refusals

    private func refusalBody(_ status: Int, _ code: String) -> Data {
        Data("""
        {"statusCode":\(status),"message":"The security check did not pass. Please try again.","details":{"code":"\(code)"}}
        """.utf8)
    }

    func testEveryRetryableCodeIsClassifiedFromDetailsCode() {
        let cases: [(Int, String)] = [(428, "pow_difficulty_raised"), (400, "pow_expired"), (409, "pow_replayed"),
                                      (400, "pow_invalid"), (428, "pow_required")]
        for (status, code) in cases {
            let r = SignupPowRefusal.classify(status: status, body: refusalBody(status, code))
            XCTAssertEqual(r?.code, code)
            XCTAssertEqual(r?.status, status)
            XCTAssertEqual(r?.message, "The security check did not pass. Please try again.")
        }
    }

    func testOtherRefusalsAreNotClassified() {
        for (status, code) in [(429, "pow_rate_limited"), (429, "registration_network_limited"),
                               (429, "registration_device_limited"), (403, "attestation_invalid"),
                               (403, "registration_country_not_allowed")] {
            XCTAssertNil(SignupPowRefusal.classify(status: status, body: refusalBody(status, code)), code)
        }
        XCTAssertNil(SignupPowRefusal.classify(status: 400, body: Data("not json".utf8)))
        XCTAssertNil(SignupPowRefusal.classify(status: 400, body: Data(#"{"message":"x"}"#.utf8)))
    }

    // MARK: - Flow (retry and fallback policy, stubbed I/O)

    /// Records what the flow did. A lock-guarded class: the flow's closures are `@Sendable`.
    private final class Script: @unchecked Sendable {
        private let lock = NSLock()
        private var challenges: [PowChallengeResponse?]
        private var registerAnswers: [Result<String, SignupPowRefusal>]
        private(set) var solved: [(String, Int)] = []
        private(set) var sent: [String?] = []
        private(set) var fetches = 0
        private(set) var progress: [Bool] = []
        private(set) var deadlines: [Date] = []

        init(challenges: [PowChallengeResponse?], registerAnswers: [Result<String, SignupPowRefusal>]) {
            self.challenges = challenges
            self.registerAnswers = registerAnswers
        }

        func nextChallenge() -> PowChallengeResponse? {
            lock.lock(); defer { lock.unlock() }
            fetches += 1
            return challenges.isEmpty ? nil : challenges.removeFirst()
        }
        func recordSolve(_ c: String, _ bits: Int, _ deadline: Date) -> String {
            lock.lock(); defer { lock.unlock() }
            solved.append((c, bits)); deadlines.append(deadline)
            return "n\(solved.count)"
        }
        func nextRegister(_ pow: PowSolution?) throws -> String {
            lock.lock(); defer { lock.unlock() }
            sent.append(pow?.challenge)
            let answer: Result<String, SignupPowRefusal> =
                registerAnswers.isEmpty ? .success("created") : registerAnswers.removeFirst()
            return try answer.get()
        }
        func recordProgress(_ on: Bool) {
            lock.lock(); defer { lock.unlock() }
            progress.append(on)
        }
    }

    private func challenge(_ token: String, bits: Int = 20, life: Int = 300) -> PowChallengeResponse {
        PowChallengeResponse(algorithm: SignupPow.algorithm, op: "signup", challenge: token,
                             difficulty: bits, expiresInSeconds: life)
    }

    private func refusal(_ code: String, _ status: Int) -> SignupPowRefusal {
        SignupPowRefusal(status: status, code: code, message: "The security check did not pass. Please try again.")
    }

    private func run(_ s: Script, now: @escaping @Sendable () -> Date = { Date() }) async throws -> String {
        try await SignupPowFlow.register(
            fetch: { s.nextChallenge() },
            solve: { c, b, d in s.recordSolve(c, b, d) },
            send: { pow in try s.nextRegister(pow) },
            progress: { s.recordProgress($0) },
            now: now)
    }

    func testSignUpCarriesTheSolvedChallenge() async throws {
        let s = Script(challenges: [challenge("tok1")], registerAnswers: [])
        let result = try await run(s)
        XCTAssertEqual(result, "created")
        XCTAssertEqual(s.sent, ["tok1"])
        XCTAssertEqual(s.solved.map { $0.0 }, ["tok1"])
        XCTAssertEqual(s.solved.map { $0.1 }, [20])
        XCTAssertEqual(s.progress, [true, false])
    }

    func testTheSolveDeadlineIsFiveSecondsBeforeTheChallengeExpires() async throws {
        let fixed = Date(timeIntervalSince1970: 1_800_000_000)
        let s = Script(challenges: [challenge("tok1", life: 300)], registerAnswers: [])
        _ = try await run(s, now: { fixed })
        XCTAssertEqual(s.deadlines.first, fixed.addingTimeInterval(295))
    }

    func testAChallengeEndpointFailureSignsUpWithoutAProof() async throws {
        let s = Script(challenges: [nil], registerAnswers: [])
        let result = try await run(s)
        XCTAssertEqual(result, "created")
        XCTAssertEqual(s.sent, [nil])
        XCTAssertTrue(s.solved.isEmpty)
        XCTAssertTrue(s.progress.isEmpty, "no solve, no status line")
    }

    func testAnUnsolvableChallengeSignsUpWithoutAProof() async throws {
        let unsolvable = [
            challenge("tok", bits: 32),                    // above what the app attempts
            challenge("tok", bits: 7),                     // below the server's minimum
            challenge(""),                                 // blank
            challenge(String(repeating: "a", count: 129)), // longer than the server allows
            PowChallengeResponse(algorithm: "scrypt", op: "signup", challenge: "tok", difficulty: 20, expiresInSeconds: 300),
            PowChallengeResponse(algorithm: SignupPow.algorithm, op: "login", challenge: "tok", difficulty: 20, expiresInSeconds: 300),
        ]
        for c in unsolvable {
            let s = Script(challenges: [c], registerAnswers: [])
            _ = try await run(s)
            XCTAssertEqual(s.sent, [nil])
            XCTAssertTrue(s.solved.isEmpty)
        }
    }

    func testAChallengeTheSolverRunsOutOfTimeOnSignsUpWithoutAProof() async throws {
        let s = Script(challenges: [challenge("tok1")], registerAnswers: [])
        let result = try await SignupPowFlow.register(
            fetch: { s.nextChallenge() },
            solve: { _, _, _ in nil },
            send: { pow in try s.nextRegister(pow) },
            progress: { s.recordProgress($0) })
        XCTAssertEqual(result, "created")
        XCTAssertEqual(s.sent, [nil])
        XCTAssertEqual(s.progress, [true, false])
    }

    func testEachRetryableRefusalGetsOneFreshChallenge() async throws {
        for (code, status) in [("pow_difficulty_raised", 428), ("pow_expired", 400), ("pow_replayed", 409),
                               ("pow_invalid", 400), ("pow_required", 428)] {
            let s = Script(challenges: [challenge("first"), challenge("second", bits: 21)],
                           registerAnswers: [.failure(refusal(code, status)), .success("created")])
            let result = try await run(s)
            XCTAssertEqual(result, "created", code)
            XCTAssertEqual(s.sent, ["first", "second"], code)
            XCTAssertEqual(s.solved.map { $0.0 }, ["first", "second"], code)
            XCTAssertEqual(s.solved.map { $0.1 }, [20, 21], code)
        }
    }

    func testAfterTwoRefusalsTheSignUpGoesOnceWithoutAProofAndNeverLoops() async throws {
        let s = Script(challenges: [challenge("first"), challenge("second"), challenge("third")],
                       registerAnswers: [.failure(refusal("pow_difficulty_raised", 428)),
                                         .failure(refusal("pow_difficulty_raised", 428)),
                                         .failure(refusal("pow_difficulty_raised", 428)),
                                         .success("never reached")])
        do {
            _ = try await run(s)
            XCTFail("the third refusal must surface")
        } catch let r as SignupPowRefusal {
            XCTAssertEqual(r.code, "pow_difficulty_raised")
        }
        XCTAssertEqual(s.sent, ["first", "second", nil])
        XCTAssertEqual(s.fetches, 2, "exactly one fresh challenge after the first refusal")
    }

    func testTheRequestWithoutAProofCanSucceedAfterTwoRefusals() async throws {
        let s = Script(challenges: [challenge("first"), challenge("second")],
                       registerAnswers: [.failure(refusal("pow_expired", 400)),
                                         .failure(refusal("pow_replayed", 409)),
                                         .success("created")])
        let result = try await run(s)
        XCTAssertEqual(result, "created")
        XCTAssertEqual(s.sent, ["first", "second", nil])
    }

    private struct OtherRefusal: Error {}

    func testOtherErrorsAreNotRetried() async {
        let s = Script(challenges: [challenge("first"), challenge("second")], registerAnswers: [])
        do {
            _ = try await SignupPowFlow.register(
                fetch: { s.nextChallenge() },
                solve: { c, b, d in s.recordSolve(c, b, d) },
                send: { pow in _ = try s.nextRegister(pow); throw OtherRefusal() },
                progress: { s.recordProgress($0) })
            XCTFail("expected the error to propagate")
        } catch {
            XCTAssertTrue(error is OtherRefusal)
        }
        XCTAssertEqual(s.sent, ["first"])
        XCTAssertEqual(s.fetches, 1)
    }

    func testCancellingDuringTheSolveSendsNothingAndTurnsTheStatusOff() async throws {
        let s = Script(challenges: [challenge("first")], registerAnswers: [])
        let task = Task {
            try await SignupPowFlow.register(
                fetch: { s.nextChallenge() },
                // A solve that only ends when its task is cancelled.
                solve: { _, _, _ in
                    while true {
                        try Task.checkCancellation()
                        try await Task.sleep(nanoseconds: 5_000_000)
                    }
                },
                send: { pow in try s.nextRegister(pow) },
                progress: { s.recordProgress($0) })
        }
        try await Task.sleep(nanoseconds: 100_000_000)
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("expected cancellation")
        } catch {
            XCTAssertTrue(error is CancellationError, "got \(type(of: error))")
        }
        XCTAssertTrue(s.sent.isEmpty)
        XCTAssertEqual(s.progress, [true, false])
    }
}
