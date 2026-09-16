package app.birdo.vpn.data.api

import app.birdo.vpn.BuildConfig
import app.birdo.vpn.data.auth.TokenManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that adds auth headers to every request.
 * - User-Agent: Birdo-Android/<version> (Android)
 * - X-Desktop-Client: birdo-android (on POST requests)
 * - Authorization: Bearer <token> (when logged in, API host only)
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header("User-Agent", "Birdo-Android/${app.birdo.vpn.BuildConfig.APP_VERSION} (Android)")

        // Identify this client on all requests — backend auth guards may check it
        builder.header("X-Desktop-Client", "birdo-android")

        // Add auth token if available — TokenManager is now non-suspend,
        // no runBlocking needed (was blocking OkHttp dispatcher threads).
        //
        // API HOST ONLY. This client now also talks to the WEB origin
        // (birdo.app) for the public `/api/client-config` endpoint, which needs
        // no token. A bearer token must not travel to a host that has no use
        // for it merely because it shares an OkHttp client — the blast radius
        // of a future misrouted or redirected request should not include the
        // session. Every authenticated endpoint is on API_BASE_URL.
        val token = if (original.url.host == API_HOST) tokenManager.getAccessToken() else null
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }

        return chain.proceed(builder.build())
    }

    private companion object {
        /**
         * Parsed ONCE. `API_BASE_URL` is a compile-time constant, and this ran
         * on every request in the app before it was hoisted.
         *
         * `null` here (an unparseable base URL) means no request would ever
         * carry a token — every authenticated call 401s and the app logs
         * itself out — so AuthInterceptorTest asserts it parses and asserts
         * the host it resolves to.
         */
        val API_HOST: String? = BuildConfig.API_BASE_URL.toHttpUrlOrNull()?.host
    }
}
