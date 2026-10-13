package app.birdo.vpn.shared.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MR-944: the sign-in request and the token pair never print a secret, and
 * neither do the classes that carry a token pair, since a data class prints
 * its fields through their own toString.
 *
 * MR-1870: nor does any other model that carries a credential: the 2FA
 * challenge and code, the refresh exchange, the SSO handoff, the deletion
 * request, a voucher code, the Play Integrity token, and the connect
 * responses' key material and Stealth credentials.
 */
class CredentialToStringTest {

    private val secrets = listOf(
        "user@example.com",
        "hunter2-password",
        "device-xyz-123",
        "Pixel of Alice",
        "access-secret-abc",
        "refresh-secret-def",
        "challenge-secret-ghi",
        "otp-424242",
        "sso-code-jkl",
        "pkce-verifier-mno",
        "voucher-code-pqr",
        "integrity-token-stu",
        "wg-private-key-vwx",
        "wg-preshared-key-yz0",
        "xray-uuid-123",
        "xray-short-id-456",
        "pq-ciphertext-789",
    )

    private val tokens = TokenPair(accessToken = "access-secret-abc", refreshToken = "refresh-secret-def")

    private fun assertNoSecret(printed: String) {
        for (secret in secrets) {
            assertFalse(printed.contains(secret), "$secret printed in $printed")
        }
    }

    @Test
    fun loginRequestPrintsNoCredentialOrDeviceIdentifier() {
        val request = LoginRequest(
            email = "user@example.com",
            password = "hunter2-password",
            deviceId = "device-xyz-123",
            deviceName = "Pixel of Alice",
            platform = "android",
            platformVersion = "16",
            appVersion = "1.4.34",
        )
        val printed = request.toString()
        assertNoSecret(printed)
        // Still useful in a log: the non-identifying context survives.
        assertTrue(printed.contains("platform=android"), printed)
        assertTrue(printed.contains("appVersion=1.4.34"), printed)
    }

    @Test
    fun tokenPairPrintsNeitherToken() {
        assertNoSecret(tokens.toString())
    }

    @Test
    fun classesHoldingATokenPairPrintNeitherToken() {
        assertNoSecret(LoginResponse(ok = true, tokens = tokens).toString())
        assertNoSecret(LoginResult.Success(ok = true, tokens = tokens).toString())
        assertNoSecret(TwoFactorVerifyResponse(ok = true, tokens = tokens).toString())
    }

    @Test
    fun twoFactorChallengePrintsNoChallengeToken() {
        val response = LoginResponse(requiresTwoFactor = true, challengeToken = "challenge-secret-ghi")
        assertNoSecret(response.toString())
        assertNoSecret(response.toLoginResult().toString())
        assertNoSecret(LoginResult.TwoFactorRequired(true, "challenge-secret-ghi").toString())
        assertTrue(response.toString().contains("requiresTwoFactor=true"), response.toString())
    }

    @Test
    fun twoFactorVerifyRequestPrintsNeitherChallengeNorCode() {
        assertNoSecret(TwoFactorVerifyRequest(challengeToken = "challenge-secret-ghi", token = "otp-424242").toString())
    }

    @Test
    fun refreshExchangePrintsNoToken() {
        assertNoSecret(RefreshRequest(refreshToken = "refresh-secret-def").toString())
        val response = RefreshResponse(
            accessToken = "access-secret-abc",
            refreshToken = "refresh-secret-def",
            expiresIn = 900,
        )
        assertNoSecret(response.toString())
        assertTrue(response.toString().contains("expiresIn=900"), response.toString())
    }

    @Test
    fun nativeOAuthExchangePrintsNoCodeVerifierOrDeviceIdentifier() {
        val request = NativeOAuthExchangeRequest(
            code = "sso-code-jkl",
            codeVerifier = "pkce-verifier-mno",
            deviceId = "device-xyz-123",
            deviceName = "Pixel of Alice",
            platform = "android",
            appVersion = "1.4.34",
        )
        val printed = request.toString()
        assertNoSecret(printed)
        assertTrue(printed.contains("platform=android"), printed)
    }

    @Test
    fun deleteAccountRequestPrintsNoPasswordOrCode() {
        val printed = DeleteAccountRequest(password = "hunter2-password", twoFactorCode = "otp-424242").toString()
        assertNoSecret(printed)
        // Which of the two was sent still shows.
        val codeOnly = DeleteAccountRequest(twoFactorCode = "otp-424242").toString()
        assertNoSecret(codeOnly)
        assertTrue(codeOnly.contains("password=null"), codeOnly)
        assertTrue(codeOnly.contains("twoFactorCode=[REDACTED]"), codeOnly)
    }

    @Test
    fun voucherRequestPrintsNoCode() {
        assertNoSecret(RedeemVoucherRequest(code = "voucher-code-pqr").toString())
    }

    @Test
    fun connectRequestsPrintNoIntegrityTokenOrDeviceIdentifier() {
        val single = ConnectRequest(
            serverNodeId = "node-1",
            deviceName = "Pixel of Alice",
            deviceId = "device-xyz-123",
            integrityToken = "integrity-token-stu",
        )
        assertNoSecret(single.toString())
        assertTrue(single.toString().contains("serverNodeId=node-1"), single.toString())
        val multi = MultiHopConnectRequest(
            entryNodeId = "node-1",
            exitNodeId = "node-2",
            deviceName = "Pixel of Alice",
            deviceId = "device-xyz-123",
            integrityToken = "integrity-token-stu",
        )
        assertNoSecret(multi.toString())
        assertTrue(multi.toString().contains("exitNodeId=node-2"), multi.toString())
    }

    @Test
    fun connectResponsesPrintNoKeyMaterialOrStealthCredential() {
        val single = ConnectResponse(
            success = true,
            config = "[Interface]\nPrivateKey = wg-private-key-vwx",
            keyId = "key-1",
            privateKey = "wg-private-key-vwx",
            presharedKey = "wg-preshared-key-yz0",
            endpoint = "203.0.113.7:51820",
            xrayUuid = "xray-uuid-123",
            xrayShortId = "xray-short-id-456",
            rosenpassPublicKey = "pq-ciphertext-789",
        )
        assertNoSecret(single.toString())
        assertTrue(single.toString().contains("endpoint=203.0.113.7:51820"), single.toString())
        val multi = MultiHopConnectResponse(
            success = true,
            config = "[Interface]\nPrivateKey = wg-private-key-vwx",
            keyId = "key-1",
            privateKey = "wg-private-key-vwx",
            presharedKey = "wg-preshared-key-yz0",
            endpoint = "203.0.113.7:51820",
            xrayUuid = "xray-uuid-123",
            xrayShortId = "xray-short-id-456",
            rosenpassPublicKey = "pq-ciphertext-789",
        )
        assertNoSecret(multi.toString())
        assertTrue(multi.toString().contains("keyId=key-1"), multi.toString())
    }
}
