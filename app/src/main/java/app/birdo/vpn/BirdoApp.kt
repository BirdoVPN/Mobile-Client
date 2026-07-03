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
            // Covers BOTH the event message AND every exception value/stack-trace
            // message — the latter is the real vector, since an uncaught crash
            // (the normal path) has a null event.message but its exception string
            // can embed a VPN endpoint host/IP, UUIDs, or key material captured
            // during connection setup. isSendDefaultPii=false and no
            // screenshots/view-hierarchy/breadcrumb SDK limit the rest.
            val scrub: (String?) -> String? = { s ->
                s
                    ?.replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE), "[UUID]")
                    ?.replace(Regex("[0-9a-fA-F]{64}"), "[KEY]")
                    ?.replace(Regex("https?://[\\w.:-]+"), "[URL]")
            }
            options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
                event.message?.let { it.formatted = scrub(it.formatted) }
                event.exceptions?.forEach { ex -> ex.value = scrub(ex.value) }
                event
            }
        }
    }
}
