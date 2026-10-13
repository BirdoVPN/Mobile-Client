package app.birdo.vpn.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MR-1870: the Play purchase token is the one value the link rail sends the
 * server, and neither class that carries it prints it. The shared API models
 * are pinned by the shared module's CredentialToStringTest; these two live in
 * the app.
 */
class PurchaseTokenToStringTest {

    private val token = "play-purchase-token-abc"

    @Test
    fun `the link request prints no purchase token`() {
        val printed = GooglePlayLinkRequest(purchaseToken = token).toString()
        assertFalse("$token printed in $printed", printed.contains(token))
    }

    @Test
    fun `an ingestable purchase prints no purchase token`() {
        val printed = IngestablePurchase(
            purchaseToken = token,
            state = IngestablePurchase.State.PURCHASED,
            isAcknowledged = false,
            productIds = listOf("birdo_operative_monthly"),
        ).toString()
        assertFalse("$token printed in $printed", printed.contains(token))
        // Still useful in a log: the purchase's state and products survive.
        assertTrue(printed, printed.contains("state=PURCHASED"))
        assertTrue(printed, printed.contains("birdo_operative_monthly"))
    }
}
