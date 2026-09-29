package app.birdo.vpn.data.model

import app.birdo.vpn.di.NetworkModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit 2026-09-29, A-8 / C-9: deleting a Birdo account cannot cancel an App
 * Store or Google Play subscription, so the backend lists the ones still
 * billing in the delete response (`storeSubscriptionsStillBilling`) and the app
 * tells the user where to cancel. The field is new; a backend that predates it
 * must still produce a successful deletion. Decoded through the app's own
 * [NetworkModule.json], the Retrofit converter's configuration.
 */
class DeleteAccountResponseTest {

    private val json: Json = NetworkModule.json

    private fun decode(body: String): DeleteAccountResponse =
        json.decodeFromString(serializer<DeleteAccountResponse>(), body)

    @Test
    fun `a response without the field still decodes, with an empty list`() {
        val r = decode("""{"success":true,"message":"Your data has been deleted.","deletedItems":7,"anonymizedItems":2}""")
        assertTrue(r.success)
        assertTrue(r.storeSubscriptionsStillBilling.isEmpty())
    }

    @Test
    fun `an explicit null is treated as none`() {
        val r = decode("""{"success":true,"storeSubscriptionsStillBilling":null}""")
        assertTrue(r.storeSubscriptionsStillBilling.isEmpty())
    }

    @Test
    fun `still-billing store subscriptions are carried through`() {
        val r = decode(
            """
            {"success":true,"storeSubscriptionsStillBilling":[
              {"store":"GOOGLE_PLAY","productId":"birdo_operative","expiresAt":"2026-11-01T00:00:00.000Z"},
              {"store":"APPLE_APP_STORE","productId":"app.birdo.vpn.sovereign.yearly","expiresAt":null},
              {"somethingNew":1}
            ]}
            """.trimIndent(),
        )
        val subs = r.storeSubscriptionsStillBilling
        assertEquals(3, subs.size)
        assertTrue(subs[0].isGooglePlay)
        assertFalse(subs[0].isAppStore)
        assertEquals("birdo_operative", subs[0].productId)
        assertTrue(subs[1].isAppStore)
        // An entry with nothing recognisable is kept (the user is still told
        // "an app store" is billing) rather than failing the whole decode.
        assertFalse(subs[2].isGooglePlay || subs[2].isAppStore)
    }

    // ── GET api/v1/gdpr/delete/preflight (second-pass #9) ─────────────

    private fun decodePreflight(body: String): DeletionPreflightResponse =
        json.decodeFromString(serializer<DeletionPreflightResponse>(), body)

    @Test
    fun `the preflight decodes the backend's shape`() {
        // gdpr.controller.ts deletePreflight: { success: true, ...DeletionPreflight }.
        val p = decodePreflight(
            """
            {"success":true,
             "storeSubscriptionsStillBilling":[
               {"store":"APPLE_APP_STORE","productId":"app.birdo.vpn.operative.yearly","expiresAt":"2027-01-01T00:00:00.000Z"}
             ],
             "webSubscriptionWillBeCancelled":true}
            """.trimIndent(),
        )
        assertTrue(p.success)
        assertEquals(1, p.storeSubscriptionsStillBilling.size)
        assertTrue(p.storeSubscriptionsStillBilling[0].isAppStore)
        assertTrue(p.webSubscriptionWillBeCancelled)
    }

    @Test
    fun `a sparse preflight means nothing to name`() {
        val p = decodePreflight("""{"success":true,"storeSubscriptionsStillBilling":null}""")
        assertTrue(p.storeSubscriptionsStillBilling.isEmpty())
        assertFalse(p.webSubscriptionWillBeCancelled)
    }
}
