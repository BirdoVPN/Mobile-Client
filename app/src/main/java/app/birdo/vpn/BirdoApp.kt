package app.birdo.vpn

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.sentry.android.core.SentryAndroid

@HiltAndroidApp
class BirdoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            initSentry()
        } catch (e: Exception) {
            // Sentry init should never take down the whole app
            android.util.Log.e("BirdoApp", "Sentry init failed", e)
        }
    }

    private fun initSentry() {
        // Skip Sentry entirely in debug builds — avoids DSN validation issues
        // and keeps development logcat clean.
        if (BuildConfig.DEBUG) return

        SentryAndroid.init(this) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.isEnableAutoSessionTracking = true
            options.environment = "production"
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.APP_VERSION}"

            // Privacy: disable PII collection — critical for a VPN app
            options.isSendDefaultPii = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false

            // Performance — sample 100% of transactions (aligned with Windows client)
            options.tracesSampleRate = 1.0

            // SEC: Scrub sensitive values from error events before they are sent.
            // Covers the event message, every exception value AND breadcrumb
            // message/data — an uncaught crash (the normal path) has a null
            // event.message but its exception string can embed exactly what a
            // VPN client must not export: the endpoint host or IP
            // ("failed to connect to /144.x.x.x (port 51820)",
            // "UnknownHostException: de-fra-1.birdo.app" — no scheme, so a
            // URL-only pattern misses both), account emails, and 44-char base64
            // WireGuard key material. Pattern set mirrors the desktop's
            // sanitize_error (redact.rs: IPv4 + email + bare hostname) plus the
            // key/UUID/URL patterns already here, so the two clients agree.
            // Order matters: URL before host/IP (so scheme'd hosts collapse to
            // [URL]), email before hostname (so the domain half can't be
            // half-matched), IPv6 before IPv4 (mapped forms).
            val scrub: (String?) -> String? = { s ->
                s
                    ?.replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE), "[UUID]")
                    ?.replace(Regex("[0-9a-fA-F]{64}"), "[KEY]")
                    // WireGuard/ML-KEM keys are 32 bytes → 43 base64 chars + '='.
                    ?.replace(Regex("[A-Za-z0-9+/]{43}="), "[KEY]")
                    ?.replace(Regex("https?://[\\w.:-]+"), "[URL]")
                    // IPv6: uncompressed (≥3 hex groups), then "::"-compressed.
                    // The compressed pattern REQUIRES hex after the "::" so a
                    // bare "::" in code symbols (Kotlin/C++ "Class::member")
                    // never matches.
                    ?.replace(Regex("\\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}\\b"), "[IP]")
                    ?.replace(Regex("(?:\\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4})*)?::[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4})*\\b"), "[IP]")
                    ?.replace(Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b"), "[IP]")
                    ?.replace(Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"), "[EMAIL]")
                    // Bare hostnames (≥3 labels, like our node names) — desktop HOST_RE.
                    ?.replace(Regex("\\b[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+\\.[a-zA-Z]{2,}\\b"), "[HOST]")
            }
            options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
                event.message?.let { it.formatted = scrub(it.formatted) }
                event.exceptions?.forEach { ex -> ex.value = scrub(ex.value) }
                // Breadcrumbs ride along on crash events (auto-instrumented
                // network/lifecycle crumbs included) and were sent VERBATIM —
                // scrub both the message and every string data value.
                event.breadcrumbs?.forEach { crumb ->
                    crumb.message = scrub(crumb.message)
                    crumb.data.keys.toList().forEach { key ->
                        val value = crumb.data[key]
                        if (value is String) crumb.setData(key, scrub(value) ?: "")
                    }
                }
                event
            }
        }
    }
}
