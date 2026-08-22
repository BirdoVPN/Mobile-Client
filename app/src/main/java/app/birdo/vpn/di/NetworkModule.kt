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
            // runs before the connect phase, and .dns() is a DoH lookup
            // (Cloudflare, three bootstrap addresses) that falls back to the
            // system resolver. P6-CLI-A-06 caps that attempt at ~5s, so the walk
            // to the system resolver is no longer the ~15s a three-provider
            // chain cost — but it is still time spent inside a Dns.lookup that
            // the phase timeouts do not bound, and withAutoRefresh can then run
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
                // SOURCE OF TRUTH: third_party/cert-pins.json (vendored from
                // birdo-shared/cert-pins.json). This array must equal the
                // birdo.app pin set declared there, and so must the copies in
                // network_security_config.xml, iosApp/.../APIClient.swift and
                // the desktop client's src-tauri/src/api/cert_pin.rs.
                // scripts/check-cert-pins.sh enforces that in CI on every PR.
                //
                // Accepted if ANY cert in the PRESENTED chain matches ANY pin.
                // A pin the server never sends is dormant: real insurance for a
                // future CA migration, but useless for today's handshake.
                // Measured 2026-08-22: leaf CN=birdo.app -> WE1 -> GTS Root R4.

                // --- live in the presented chain ---
                // WE1 - Google Trust Services intermediate
                "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",
                // GTS Root R4 - the actual trust anchor api.birdo.app chains to.
                // ADDED 2026-08-22. Until now WE1 was the ONLY pin here that
                // could ever match: the other two are anchors this chain does
                // not present, so if the leaf had moved off WE1 the app would
                // have lost the API outright with no remote recovery.
                "sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=",

                // --- dormant: cross-CA insurance, not in today's chain ---
                // GlobalSign ECC Root CA - R4 (alternate Google cross-sign anchor)
                "sha256/CLOmM1/OXvSPjw5UOYbAf9GKOxImEp9hhku9W90fHMk=",
                // ISRG Root X1 - Let's Encrypt root. A default Let's Encrypt
                // server sends the leaf + ONE issuing intermediate and never
                // this root, so the root pin alone could not have rescued a
                // migration. The four issuing intermediates below make the
                // cross-CA backup real rather than decorative.
                "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",
                // Let's Encrypt R10 (RSA issuing intermediate)
                "sha256/K7rZOrXHknnsEhUH8nLL4MZkejquUuIvOIr6tCa0rbo=",
                // Let's Encrypt R11 (RSA issuing intermediate)
                "sha256/bdrBhpj38ffhxpubzkINl0rG+UyossdhcBYj+Zx2fcc=",
                // Let's Encrypt E5 (ECDSA issuing intermediate)
                "sha256/NYbU7PBwV4y9J67c4guWTki8FJ+uudrXL0a4V4aRcrg=",
                // Let's Encrypt E6 (ECDSA issuing intermediate)
                "sha256/0Bbh/jEZSKymTy3kTOhsmlHKBB32EDu1KojrP3YfV9c=",
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
