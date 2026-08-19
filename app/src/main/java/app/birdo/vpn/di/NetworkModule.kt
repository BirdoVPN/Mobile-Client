package app.birdo.vpn.di

import app.birdo.vpn.BuildConfig
import app.birdo.vpn.data.api.AuthInterceptor
import app.birdo.vpn.data.api.BirdoApi
import app.birdo.vpn.data.network.DohResolver
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = false // Strict JSON parsing — reject malformed responses
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .dns(DohResolver.dns)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // The three phase timeouts above bound the connect, read and write
            // PHASES individually — they do not bound the call. DNS resolution
            // runs before the connect phase, and .dns() is a serial three-
            // provider DoH chain (each with three bootstrap addresses) that ends
            // in the system resolver, so on a filtered or captive network a
            // single request could sit in lookup far past the nominal 30s with
            // the UI on a spinner and no error. withAutoRefresh can then run
            // call → token refresh → retry on top of that. callTimeout is the
            // only setting that covers the whole thing, including redirects and
            // retries.
            .callTimeout(45, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)

        // ── Certificate Pinning ──────────────────────────────────────
        // Prevents MITM attacks via compromised CAs or rogue proxies.
        // Chain: birdo.app → WE1 (Google Trust Services) → GlobalSign ECC Root CA - R4
        // Pins regenerated 2026-02-22 from live birdo.app certificate chain.
        // Kept in sync with network_security_config.xml (pin-set expiration 2027-06-01).
        // OkHttp validates SPKI pins against ALL certs in the chain (including trust anchor).
        // If birdo.app changes TLS provider, update these pins AND the XML config.
        // SEC: At least one pin from a different CA family ensures a provider
        // migration (e.g. Google → Let's Encrypt) doesn't brick the app.
        if (!BuildConfig.DEBUG) {
            val pins = arrayOf(
                // WE1 — Google Trust Services intermediate (verified 2026-02-22)
                "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",
                // GlobalSign ECC Root CA - R4 (actual root in chain, verified 2026-02-22)
                "sha256/CLOmM1/OXvSPjw5UOYbAf9GKOxImEp9hhku9W90fHMk=",
                // ISRG Root X1 — Let's Encrypt root (cross-CA diversity backup)
                // Ensures app survives a Google → Let's Encrypt CA migration
                "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",
            )
            val pinner = CertificatePinner.Builder()
                // Pin exact domain + all subdomains (matches network_security_config.xml
                // which has includeSubdomains="true"). Without the wildcard, requests to
                // e.g. api.birdo.app would bypass OkHttp cert pinning while still being
                // covered by the Android XML config — defense-in-depth requires both.
                .add("birdo.app", *pins)
                .add("*.birdo.app", *pins)
                .build()
            builder.certificatePinner(pinner)
        }

        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor().apply {
                // HEADERS only — NEVER use BODY: it would log WireGuard private keys,
                // passwords, and auth tokens to Logcat.
                level = HttpLoggingInterceptor.Level.HEADERS
                // SEC: redact every credential-bearing header so even debug logs
                // never expose Bearer tokens, session cookies, CSRF tokens, or
                // backend service-auth secrets in logcat / bug reports.
                redactHeader("Authorization")
                redactHeader("Cookie")
                redactHeader("Set-Cookie")
                redactHeader("X-CSRF-Token")
                redactHeader("X-Service-Auth")
                redactHeader("X-Refresh-Token")
            }
            builder.addInterceptor(logging)
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideBirdoApi(retrofit: Retrofit): BirdoApi {
        return retrofit.create(BirdoApi::class.java)
    }
}
