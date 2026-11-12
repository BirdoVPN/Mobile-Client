import XCTest
import CryptoKit

/// A-M78 / MR-2139: the sign-in proof-of-work during a failed-sign-in spike.
/// The same contract as Desktop-Client #411 and Android's LoginPowTest: the
/// request goes out once without a proof; only a 429 `pow_required` whose
/// `pow.op` is `login` is answered, with ONE retry that carries `pow`; the
/// second answer is final.
///
/// The three vectors were minted by the SERVER's own code (birdo-web
/// `backend/src/auth/signup-pow.ts` at origin/main, run under node:
/// `mintPowToken(key, "login", bits, fixed time and id)`, `solvePow` for the
/// nonce, `powDigest` for the digest; `parsePowToken` accepts each as op
/// `login`). The same three as Desktop-Client #411.
final class LoginPowTests: XCTestCase {

    private struct Vector {
        let bits: Int
        let token: String
        let nonce: String
        let digestHex: String
    }

    private let vectors = [
        Vector(bits: 8,
               token: "p1.7f386f0d.login.8.1800000000.00112233445566778899aabbccddeeff.Gfoljd9Ww43uMw5hpkBNr2An9STpqSYJ2Hnlc5i-5Is",
               nonce: "2s",
               digestHex: "000f1d00ae40e44661710c5571d460c633457e289be052afb6eb8c09cb307e3c"),
        Vector(bits: 12,
               token: "p1.7f386f0d.login.12.1800000000.00112233445566778899aabbccddeeff.uzPNudqEP3ETCZQrBjWZGyxE4pCxo9X508ma6BGIxYA",
               nonce: "42o",
               digestHex: "0000bf9e8d08ce6e71fdb0890b8431e8c7a11e86677710ce44b07b4a478155e1"),
        Vector(bits: 16,
               token: "p1.7f386f0d.login.16.1800000000.00112233445566778899aabbccddeeff.U3WUdfGStXSHBuaTSNGYsd8xEgYbwS1-qswaNsieWVk",
               nonce: "5g7e",
               digestHex: "00004ff5903c46e393a9ec3ae7578474503b68a6c63fe513b83dce38a44b79e0"),
    ]

    // MARK: - Solver against the server's login challenges

    func testTheSolverFindsTheVeryNonceTheServerFindsForEachLoginChallenge() async throws {
        for v in vectors {
            let digest = SHA256.hash(data: Array("\(v.token):\(v.nonce)".utf8))
            XCTAssertEqual(digest.map { String(format: "%02x", $0) }.joined(), v.digestHex, "bits=\(v.bits)")
            XCTAssertTrue(SignupPow.meets(challenge: v.token, nonce: v.nonce, bits: v.bits), "bits=\(v.bits)")
            let nonce = try await SignupPow.solve(challenge: v.token, bits: v.bits)
            XCTAssertEqual(nonce, v.nonce, "bits=\(v.bits)")
        }
    }

    // MARK: - Wire

    private func body(_ status: Int, _ code: String, op: String?) -> Data {
        let pow = op.map { #","pow":{"op":"\#($0)","challengePath":"/auth/pow/challenge"}"# } ?? ""
        return Data("""
        {"statusCode":\(status),"message":"Too many sign-in attempts right now, please try again later","details":{"code":"\(code)"\(pow)}}
        """.utf8)
    }

    func testTheEnvelopeNamesTheLoginOp() {
        XCTAssertTrue(LoginPowRefusal.matches(status: 429, body: body(429, "pow_required", op: "login")))
    }

    func testNothingElseMatches() {
        let cases: [(Int, String, String?)] = [
            (429, "pow_required", "signup"),
            (429, "pow_required", nil),
            (429, "pow_required", "other"),
            (429, "pow_invalid", "login"),
            (429, "pow_expired", "login"),
            (429, "pow_rate_limited", nil),
            (400, "pow_required", "login"),
            (401, "invalid_credentials", nil),
            (403, "pow_required", "login"),
        ]
        for (status, code, op) in cases {
            XCTAssertFalse(LoginPowRefusal.matches(status: status, body: body(status, code, op: op)), "\(status) \(code) \(op ?? "-")")
        }
        XCTAssertFalse(LoginPowRefusal.matches(status: 429, body: Data("not json".utf8)))
    }

    private func encoded<T: Encodable>(_ value: T) throws -> [String: Any] {
        let data = try JSONEncoder().encode(value)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testTheChallengeRequestNamesTheOpAndSignUpStaysTheDefault() throws {
        XCTAssertEqual(try encoded(PowChallengeRequest(op: "login")) as? [String: String], ["op": "login"])
        XCTAssertEqual(try encoded(PowChallengeRequest()) as? [String: String], ["op": "signup"])
    }

    func testSignInBodiesCarryPowOnlyOnTheRetry() throws {
        let login = LoginBody(email: "a@b.co", password: "pw-123456", deviceId: "d", deviceName: "n",
                              platformVersion: "17", appVersion: "1")
        XCTAssertNil(try encoded(login)["pow"], "a nil proof must be omitted, not sent as null")
        let anon = AnonymousLoginBody(anonymousId: "123456789012345678901234", password: nil, deviceId: "d",
                                      deviceName: "n", platformVersion: "17", appVersion: "1")
        XCTAssertNil(try encoded(anon)["pow"])

        let proof = PowSolution(challenge: "tok", nonce: "4q")
        let withLogin = try encoded(LoginBody(email: "a@b.co", password: "pw-123456", deviceId: "d", deviceName: "n",
                                              platformVersion: "17", appVersion: "1", pow: proof))
        XCTAssertEqual(withLogin["pow"] as? [String: String], ["challenge": "tok", "nonce": "4q"])
        let withAnon = try encoded(AnonymousLoginBody(anonymousId: "123456789012345678901234", password: nil,
                                                      deviceId: "d", deviceName: "n", platformVersion: "17",
                                                      appVersion: "1", pow: proof))
        XCTAssertEqual(withAnon["pow"] as? [String: String], ["challenge": "tok", "nonce": "4q"])
    }

    func testASolvableChallengeIsPerOp() {
        let c = challenge("tok", op: "login")
        XCTAssertTrue(SignupPowFlow.isSolvable(c, op: "login"))
        XCTAssertFalse(SignupPowFlow.isSolvable(c), "a login challenge is not a sign-up one")
        XCTAssertFalse(SignupPowFlow.isSolvable(challenge("tok", op: "signup"), op: "login"))
    }

    // MARK: - Flow

    private struct Original: Error, Equatable { let tag: String }

    /// Records what the flow did. A lock-guarded class: the flow's closures are `@Sendable`.
    private final class Script: @unchecked Sendable {
        private let lock = NSLock()
        private var challenges: [PowChallengeResponse?]
        private var answers: [Result<String, Error>]
        private(set) var solved: [(String, Int)] = []
        private(set) var sent: [String?] = []
        private(set) var fetches = 0
        private(set) var progress: [Bool] = []

        init(challenges: [PowChallengeResponse?], answers: [Result<String, Error>]) {
            self.challenges = challenges
            self.answers = answers
        }

        func nextChallenge() -> PowChallengeResponse? {
            lock.lock(); defer { lock.unlock() }
            fetches += 1
            return challenges.isEmpty ? nil : challenges.removeFirst()
        }
        func recordSolve(_ c: String, _ bits: Int) -> String {
            lock.lock(); defer { lock.unlock() }
            solved.append((c, bits))
            return "n\(solved.count)"
        }
        func next(_ pow: PowSolution?) throws -> String {
            lock.lock(); defer { lock.unlock() }
            sent.append(pow?.challenge)
            let answer: Result<String, Error> = answers.isEmpty ? .success("signed in") : answers.removeFirst()
            return try answer.get()
        }
        func recordProgress(_ on: Bool) {
            lock.lock(); defer { lock.unlock() }
            progress.append(on)
        }
    }

    private func challenge(_ token: String, bits: Int = 8, op: String = "login") -> PowChallengeResponse {
        PowChallengeResponse(algorithm: SignupPow.algorithm, op: op, challenge: token,
                             difficulty: bits, expiresInSeconds: 300)
    }

    private func spike(_ tag: String = "first") -> Error { LoginPowRefusal(original: Original(tag: tag)) }

    private func run(_ s: Script) async throws -> String {
        try await SignupPowFlow.login(
            fetch: { s.nextChallenge() },
            solve: { c, b, _ in s.recordSolve(c, b) },
            send: { pow in try s.next(pow) },
            progress: { s.recordProgress($0) })
    }

    func testASpikeRefusalIsSolvedAndRetriedOnceWithTheProof() async throws {
        let s = Script(challenges: [challenge("tok1")], answers: [.failure(spike()), .success("signed in")])
        let result = try await run(s)
        XCTAssertEqual(result, "signed in")
        XCTAssertEqual(s.sent, [nil, "tok1"], "the first try carries no proof, the retry does")
        XCTAssertEqual(s.solved.map { $0.0 }, ["tok1"])
        XCTAssertEqual(s.solved.map { $0.1 }, [8])
        XCTAssertEqual(s.fetches, 1)
        XCTAssertEqual(s.progress, [true, false])
    }

    func testASuccessSendsOnceWithoutAProofAndFetchesNothing() async throws {
        let s = Script(challenges: [challenge("tok1")], answers: [.success("signed in")])
        let result = try await run(s)
        XCTAssertEqual(result, "signed in")
        XCTAssertEqual(s.sent, [nil])
        XCTAssertEqual(s.fetches, 0)
        XCTAssertTrue(s.progress.isEmpty)
    }

    func testASecondRefusalIsTheAnswerAndNothingLoops() async {
        let seconds: [Error] = [
            spike("second"),                              // pow_required again
            Original(tag: "pow_replayed"),
            Original(tag: "pow_expired"),
            Original(tag: "pow_invalid"),
        ]
        for second in seconds {
            let s = Script(challenges: [challenge("tok1"), challenge("tok2")],
                           answers: [.failure(spike()), .failure(second), .success("never reached")])
            do {
                _ = try await run(s)
                XCTFail("the second refusal must surface")
            } catch {
                // A second `pow_required` is unwrapped to the error the request would have thrown.
                XCTAssertTrue(error is Original, "got \(type(of: error))")
            }
            XCTAssertEqual(s.sent, [nil, "tok1"], "exactly one retry, pow in the body once")
            XCTAssertEqual(s.fetches, 1)
            XCTAssertEqual(s.solved.count, 1)
        }
    }

    func testNothingIsSolvedForAnyOtherError() async {
        // The hook only throws `LoginPowRefusal` for the spike: any other error is not it.
        let others: [Error] = [Original(tag: "signup pow_required"), Original(tag: "pow_invalid"),
                               Original(tag: "401"), Original(tag: "403"), Original(tag: "429"),
                               URLError(.notConnectedToInternet)]
        for other in others {
            let s = Script(challenges: [challenge("tok1")], answers: [.failure(other)])
            do {
                _ = try await run(s)
                XCTFail("expected the error to propagate")
            } catch {
                XCTAssertEqual(String(describing: error), String(describing: other))
            }
            XCTAssertEqual(s.sent, [nil])
            XCTAssertEqual(s.fetches, 0)
            XCTAssertTrue(s.solved.isEmpty)
        }
    }

    func testAnUnusableChallengeReturnsTheOriginalRefusal() async {
        let unusable: [PowChallengeResponse?] = [
            nil,                                         // fetch failed
            challenge("tok", bits: 32),                  // above what the app attempts
            challenge("tok", bits: 7),                   // below the server's minimum
            challenge("tok", op: "signup"),              // another op
            challenge(""),                               // blank
            PowChallengeResponse(algorithm: "scrypt", op: "login", challenge: "tok", difficulty: 8, expiresInSeconds: 300),
        ]
        for c in unusable {
            let s = Script(challenges: [c], answers: [.failure(spike("original"))])
            do {
                _ = try await run(s)
                XCTFail("the original refusal must surface")
            } catch let e as Original {
                XCTAssertEqual(e.tag, "original")
            } catch {
                XCTFail("got \(type(of: error))")
            }
            XCTAssertEqual(s.sent, [nil])
            XCTAssertTrue(s.solved.isEmpty)
        }
    }

    func testCancellingDuringTheSolveSendsNothingMore() async throws {
        let s = Script(challenges: [challenge("tok1")], answers: [.failure(spike())])
        let task = Task {
            try await SignupPowFlow.login(
                fetch: { s.nextChallenge() },
                // A solve that only ends when its task is cancelled.
                solve: { _, _, _ in
                    while true {
                        try Task.checkCancellation()
                        try await Task.sleep(nanoseconds: 5_000_000)
                    }
                },
                send: { pow in try s.next(pow) },
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
        XCTAssertEqual(s.sent, [nil], "only the first, proof-less try was sent")
        XCTAssertEqual(s.progress, [true, false])
    }
}
