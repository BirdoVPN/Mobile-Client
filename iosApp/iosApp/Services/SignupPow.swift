import Foundation
import CryptoKit

// MR-2152 (register A-H11): the sign-up proof-of-work, the Apple twin of
// Android's SignupPow.kt (Mobile-Client #553) and Desktop-Client #407.
//
// The server (birdo-web backend/src/auth/signup-pow.ts, docs/SIGNUP-PROOF-OF-WORK.md)
// issues a challenge on `POST /auth/pow/challenge {"op":"signup"}` and checks,
// on `POST /auth/register/anonymous`, a `pow: { challenge, nonce }` for it:
// SHA-256(UTF-8(challenge + ":" + nonce)) must start with at least
// `difficulty` zero bits. A sign-up that sends none lands in the server's
// stricter "legacy" tier, so the app solves one first.
//
// This file is Foundation + CryptoKit only, so BirdoVPNTests compiles it: the
// wire models, the solver, the refusal classification and the retry policy
// (`SignupPowFlow`) are all pure and are pinned there. APIClient.swift (the
// only caller) cannot be compiled into that bundle.
//
// Sign in with Apple (`POST /auth/apple/native`) does NOT carry a proof: the
// server judges a first Apple sign-in in its own `idp` tier. Only the anonymous
// sign-up sends one.

// MARK: - Wire models

/// Body of `POST /auth/pow/challenge`.
struct PowChallengeRequest: Encodable, Sendable {
    let op = "signup"
}

/// `200` answer of `POST /auth/pow/challenge`. `challenge` is opaque and goes
/// back unchanged. Every field is optional so a shape change is "no proof"
/// (the sign-up still goes out) rather than a failed sign-up. Its redacting
/// description is in CredentialRedactingModels.swift.
struct PowChallengeResponse: Decodable, Sendable, Equatable {
    let algorithm: String?
    let op: String?
    let challenge: String?
    let difficulty: Int?
    let expiresInSeconds: Int?
}

/// What the server verifies: the token it issued and the nonce found for it.
/// Spendable once. Its redacting description is in CredentialRedactingModels.swift.
struct PowSolution: Encodable, Sendable, Equatable {
    let challenge: String
    let nonce: String
}

// MARK: - Refusals

/// A sign-up refusal after which a NEW challenge changes the answer
/// (docs/SIGNUP-PROOF-OF-WORK.md section 4): 400 `pow_invalid` / `pow_expired`,
/// 409 `pow_replayed`, 428 `pow_difficulty_raised` / `pow_required`. Told by
/// `details.code`, never by wording. Thrown from the `refusal:` hook of the
/// register request and caught by `SignupPowFlow`; `message` is the server's
/// own sentence, kept for the case where the retries run out.
struct SignupPowRefusal: Error, Equatable, Sendable {
    static let retryCodes: Set<String> = [
        "pow_difficulty_raised", "pow_expired", "pow_replayed", "pow_invalid", "pow_required",
    ]

    let status: Int
    let code: String
    let message: String?

    /// The refusal for a response, or nil when it is anything else (including
    /// `pow_rate_limited`, which no fresh challenge fixes).
    static func classify(status: Int, body: Data) -> SignupPowRefusal? {
        guard status >= 400,
              let root = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              let details = root["details"] as? [String: Any],
              let code = details["code"] as? String,
              retryCodes.contains(code) else { return nil }
        return SignupPowRefusal(status: status, code: code, message: root["message"] as? String)
    }
}

// MARK: - Solver

enum SignupPow {
    /// `algorithm` of the only proof-of-work the server issues.
    static let algorithm = "sha256-leading-zero-bits"
    /// The server refuses a token below this (POW_MIN_BITS).
    static let minBits = 8
    /// The server never configures more than 28 bits (POW_MAX_CONFIG_BITS);
    /// anything above is not attempted.
    static let maxSolvableBits = 28
    /// Server limit on a token (POW_TOKEN_MAX_LENGTH).
    static let maxChallengeLength = 128
    /// The loop looks at cancellation and the deadline every 4096 hashes.
    static let checkMask: UInt64 = 0xFFF

    /// Number of leading zero bits in a digest.
    static func leadingZeroBits<S: Sequence>(_ digest: S) -> Int where S.Element == UInt8 {
        var bits = 0
        for byte in digest {
            if byte == 0 {
                bits += 8
                continue
            }
            return bits + byte.leadingZeroBitCount
        }
        return bits
    }

    /// The nonce for counter `i`: lower-case base 36, "0" for 0. Same as
    /// `i.toString(36)` in the server's reference solver.
    static func nonce(for i: UInt64) -> String {
        String(i, radix: 36)
    }

    /// Whether `nonce` solves `challenge` at `bits`: one SHA-256, what the
    /// server's `meetsDifficulty` does.
    static func meets(challenge: String, nonce: String, bits: Int) -> Bool {
        leadingZeroBits(SHA256.hash(data: Array("\(challenge):\(nonce)".utf8))) >= bits
    }

    enum SolveError: Error, Equatable {
        case difficultyOutOfRange
    }

    /// Search for a nonce OFF the caller's actor (a detached task). Returns nil
    /// when `deadline` passes first. Cancellable: cancelling the awaiting task
    /// cancels the search, which checks every 4096 hashes and throws
    /// `CancellationError`.
    static func solve(challenge: String, bits: Int, deadline: Date? = nil) async throws -> String? {
        guard (0...maxSolvableBits).contains(bits) else { throw SolveError.difficultyOutOfRange }
        let work = Task.detached(priority: .userInitiated) { () throws -> String? in
            try search(challenge: challenge, bits: bits, deadline: deadline)
        }
        return try await withTaskCancellationHandler {
            try await work.value
        } onCancel: {
            work.cancel()
        }
    }

    /// The synchronous loop. Counting up in base 36 from 0 is the server's own
    /// reference (`solvePow`), so it finds the same first nonce.
    static func search(challenge: String, bits: Int, deadline: Date?) throws -> String? {
        var message = Array("\(challenge):".utf8)
        let prefixCount = message.count
        var i: UInt64 = 0
        while true {
            if i & checkMask == 0 {
                if Task.isCancelled { throw CancellationError() }
                if let deadline, Date() >= deadline { return nil }
            }
            let text = String(i, radix: 36)
            message.removeSubrange(prefixCount...)
            message.append(contentsOf: text.utf8)
            if leadingZeroBits(SHA256.hash(data: message)) >= bits {
                return text
            }
            i &+= 1
        }
    }
}

// MARK: - Policy

/// The sign-up retry policy, with its I/O injected so it is tested without a
/// network or 2^20 hashes. At most three register requests, never a loop:
/// first with a solved challenge; after a refusal a fresh challenge can change
/// the answer, so ONE more is fetched and solved; if that is refused too, the
/// sign-up is sent once WITHOUT a proof and the server decides (what the
/// contract tells a slow phone to do). Any failure to get a proof (endpoint
/// 429/503, offline, an answer this app will not solve, not solved before the
/// challenge expires) is "send without a proof". Only cancellation propagates
/// out of the proof steps.
enum SignupPowFlow {
    /// One fresh challenge after a refusal, then one request without a proof.
    static let maxRetries = 2
    /// Stop solving this long before the challenge expires: a nonce found later
    /// is refused as expired.
    static let expiryMarginSeconds = 5
    /// A challenge lives at least this long (the server's default is 300 s).
    static let defaultLifeSeconds = 300

    /// `nil` means "no usable challenge" (the caller already swallowed the
    /// failure); only cancellation may throw.
    typealias Fetch = @Sendable () async throws -> PowChallengeResponse?
    /// A nonce, or nil when `deadline` passed first.
    typealias Solve = @Sendable (_ challenge: String, _ bits: Int, _ deadline: Date) async throws -> String?

    /// Whether this app will attempt the challenge the server issued.
    static func isSolvable(_ c: PowChallengeResponse) -> Bool {
        guard c.algorithm == SignupPow.algorithm,
              c.op == "signup",
              let challenge = c.challenge, !challenge.isEmpty,
              challenge.utf8.count <= SignupPow.maxChallengeLength,
              let bits = c.difficulty,
              (SignupPow.minBits...SignupPow.maxSolvableBits).contains(bits) else { return false }
        return true
    }

    /// A fresh challenge, solved; nil when there is nothing to send.
    static func obtain(fetch: Fetch, solve: Solve,
                       progress: @Sendable (Bool) async -> Void,
                       now: @Sendable () -> Date) async throws -> PowSolution? {
        guard let c = try await fetch(), isSolvable(c),
              let challenge = c.challenge, let bits = c.difficulty else { return nil }
        let life = (c.expiresInSeconds ?? 0) > 0 ? (c.expiresInSeconds ?? defaultLifeSeconds) : defaultLifeSeconds
        let budget = max(life - expiryMarginSeconds, expiryMarginSeconds)
        let deadline = now().addingTimeInterval(TimeInterval(budget))
        await progress(true)
        let nonce: String?
        do {
            nonce = try await solve(challenge, bits, deadline)
        } catch {
            // Cancelled mid-solve: the status line still goes off.
            await progress(false)
            throw error
        }
        await progress(false)
        guard let nonce else { return nil }
        return PowSolution(challenge: challenge, nonce: nonce)
    }

    /// Run the sign-up. `send` performs the register request with the given
    /// proof and throws `SignupPowRefusal` for a refusal a new challenge fixes;
    /// every other error propagates untouched.
    static func register<T: Sendable>(
        fetch: Fetch,
        solve: Solve,
        send: @Sendable (PowSolution?) async throws -> T,
        progress: @Sendable (Bool) async -> Void,
        now: @Sendable () -> Date = { Date() }
    ) async throws -> T {
        var pow = try await obtain(fetch: fetch, solve: solve, progress: progress, now: now)
        var retries = 0
        while true {
            do {
                return try await send(pow)
            } catch let refusal as SignupPowRefusal {
                guard retries < maxRetries else { throw refusal }
                retries += 1
                // The last try goes without a proof: the server decides.
                pow = retries < maxRetries
                    ? try await obtain(fetch: fetch, solve: solve, progress: progress, now: now)
                    : nil
            }
        }
    }
}
