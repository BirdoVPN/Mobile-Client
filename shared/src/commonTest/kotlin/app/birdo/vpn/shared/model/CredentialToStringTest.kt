package app.birdo.vpn.shared.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MR-944: the sign-in request and the token pair never print a secret, and
 * neither do the classes that carry a token pair, since a data class prints
 * its fields through their own toString.
 */
class CredentialToStringTest {

    private val secrets = listOf(
        "user@example.com",
        "hunter2-password",
        "device-xyz-123",
        "Pixel of Alice",
        "access-secret-abc",
        "refresh-secret-def",
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
}
