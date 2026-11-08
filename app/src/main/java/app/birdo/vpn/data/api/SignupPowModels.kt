package app.birdo.vpn.data.api

import kotlinx.serialization.Serializable

/**
 * Wire types of the sign-up proof-of-work (birdo-web docs/SIGNUP-PROOF-OF-WORK.md,
 * backend/src/auth/pow.controller.ts and signup-pow.service.ts).
 */

/** Body of `POST /auth/pow/challenge`. */
@Serializable
data class PowChallengeRequest(val op: String = "signup")

/**
 * `200` answer of `POST /auth/pow/challenge`. [challenge] is opaque and is sent
 * back unchanged; [difficulty] is the number of leading zero bits to find.
 */
@Serializable
data class PowChallengeResponse(
    val algorithm: String = "",
    val op: String = "",
    val challenge: String = "",
    val difficulty: Int = 0,
    val expiresInSeconds: Int = 0,
)

/** What the server verifies: the token it issued and the nonce found for it. */
@Serializable
data class PowSolution(val challenge: String, val nonce: String)

/**
 * Body for `POST /auth/register/anonymous`: device context plus the optional
 * solved `signup` challenge. [pow] is null (and so omitted from the JSON, the
 * wire serializer does not encode defaults) when no challenge could be fetched
 * or solved; the server then applies its fallback policy.
 */
@Serializable
data class AnonymousRegisterRequest(
    val deviceId: String? = null,
    val deviceName: String? = null,
    val deviceType: String? = null,
    val platform: String? = null,
    val platformVersion: String? = null,
    val appVersion: String? = null,
    val pow: PowSolution? = null,
)
