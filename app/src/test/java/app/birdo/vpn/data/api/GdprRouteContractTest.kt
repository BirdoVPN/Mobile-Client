package app.birdo.vpn.data.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.HTTP

/**
 * Audit 2026-09-29, P0-6 / C-2: in-app account deletion on Android called
 * `DELETE https://api.birdo.app/v1/gdpr/delete`. The backend serves erasure
 * only at `@Controller('api/v1/gdpr')` + `@Delete('delete')`, and the
 * api.birdo.app proxy does not rewrite paths, so every deletion 404'd — the
 * Google Play in-app deletion requirement and the Art. 17 promise were both
 * unmet, and nothing in the build noticed.
 *
 * Read from the Retrofit annotation itself, not from a copy of the string, so
 * the test fails if either the constant or the annotation drifts.
 */
class GdprRouteContractTest {

    private val http: HTTP by lazy {
        val method = BirdoApi::class.java.methods.single { it.name == "deleteAccount" }
        val annotation = method.getAnnotation(HTTP::class.java)
        assertNotNull("BirdoApi.deleteAccount lost its @HTTP annotation", annotation)
        annotation!!
    }

    @Test
    fun `deletion is sent to the route the backend serves`() {
        assertEquals("api/v1/gdpr/delete", http.path)
        assertEquals(BirdoApi.GDPR_DELETE_PATH, http.path)
    }

    @Test
    fun `deletion is a DELETE that carries the password body`() {
        assertEquals("DELETE", http.method)
        assertTrue("the password confirmation rides the request body", http.hasBody)
    }

    @Test
    fun `resolved against the API base URL it lands on the api prefixed route`() {
        // NetworkModule: baseUrl(BuildConfig.API_BASE_URL + "/"), API_BASE_URL =
        // https://api.birdo.app. A leading slash on the path would silently
        // resolve from the host root too, so the whole URL is asserted.
        val resolved = "https://api.birdo.app/".toHttpUrl().resolve(http.path)?.toString()
        assertEquals("https://api.birdo.app/api/v1/gdpr/delete", resolved)
    }

    @Test
    fun `no other client route still points at the unprefixed gdpr path`() {
        val offenders = BirdoApi::class.java.methods.mapNotNull { m ->
            val paths = listOfNotNull(
                m.getAnnotation(HTTP::class.java)?.path,
                m.getAnnotation(retrofit2.http.GET::class.java)?.value,
                m.getAnnotation(retrofit2.http.POST::class.java)?.value,
                m.getAnnotation(retrofit2.http.DELETE::class.java)?.value,
            )
            paths.firstOrNull { it.contains("gdpr") && !it.startsWith("api/v1/gdpr/") }
                ?.let { "${m.name} -> $it" }
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
