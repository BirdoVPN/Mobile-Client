package app.birdo.vpn

import android.app.Application
import app.birdo.vpn.billing.PlayBillingManager
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.utils.CpuFeatures
import app.birdo.vpn.utils.CrashReporting
import dagger.hilt.android.HiltAndroidApp
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class BirdoApp : Application() {

    /**
     * The Google Play purchase rail.
     *
     * Started HERE, at application scope, and not from the subscription screen.
     * Its PurchasesUpdatedListener has to be live before any purchase so that a
     * deferred payment clearing, an Ask-to-Buy approval or a purchase made on
     * another device is still linked to the account — all three arrive long
     * after the purchase sheet closed, and a screen-scoped listener would miss
     * every one of them. The same call reconciles anything Play already
     * considers current, which is how a purchase that completed while the app
     * was dead reaches the server at all.
     *
     * Cheap in a non-Play build: the rail is gated on BuildConfig.IS_PLAY_BUILD
     * and the BillingClient is created lazily, so nothing binds to the Play
     * Store service in the sideload or F-Droid builds.
     */
    @Inject lateinit var playBilling: dagger.Lazy<PlayBillingManager>

    /** Holds the user's crash-report choice. See [applyCrashReportingConsent]. */
    @Inject lateinit var appPreferences: AppPreferences

    /** Opened in the startup warm-up below, off the main thread. */
    @Inject lateinit var tokenManager: dagger.Lazy<TokenManager>

    override fun onCreate() {
        super.onCreate()
        // Crash reporting is OPT-IN. Nothing is initialised here unless the
        // user has already turned it on (consent screen or Settings); a fresh
        // install, and every install upgrading from a build that reported
        // unconditionally, starts with the SDK off and its unsent queue
        // discarded. See CrashReporting for the whole rule.
        applyCrashReportingConsent()
        // STARTUP WARM-UP, OFF THE MAIN THREAD (A2-021). Opening the token
        // store is Keystore work (key generation on a first run, a seal/open
        // probe, the legacy migration, and a delete-and-regenerate recovery
        // when the Keystore misbehaves), and the Play rail's construction
        // builds the whole Retrofit/OkHttp/TokenManager graph. Both used to
        // run right here, on the cold-start critical path of the store build.
        // Started now, they are usually finished by the time the first screen
        // asks for the TokenManager; if not, that caller waits on Hilt's
        // singleton lock, which is no worse than doing the work itself.
        //
        // dagger.Lazy for the rail, and the flag checked HERE rather than only
        // inside start(): in a non-Play build (debug, sideload APK, F-Droid)
        // the rail can never work — Play Billing does not sell to an app the
        // Play Store did not install — so nothing about it is built at all.
        Thread({
            try {
                tokenManager.get()
            } catch (e: Exception) {
                android.util.Log.w("BirdoApp", "Token store warm-up failed", e)
            }
            if (BuildConfig.IS_PLAY_BUILD) {
                try {
                    playBilling.get().start()
                } catch (e: Exception) {
                    // A wedged Play Store must never take down the whole app.
                    android.util.Log.e("BirdoApp", "Play Billing init failed", e)
                }
            }
        }, "birdo-startup").start()
    }

    /**
     * Bring the crash reporter in line with the user's current choice.
     *
     * Idempotent, and the ONE place the SDK is started or stopped: called from
     * [onCreate], from the consent screen when it is accepted, and from the
     * Settings toggle. Opted in (and a release build with a DSN) → start it if
     * it is not running. Otherwise → close it if it is running (Sentry.close()
     * flushes what was already captured under consent and uninstalls the
     * uncaught-exception, ANR and NDK handlers), and when the user is opted out
     * discard anything still queued on disk.
     *
     * Audit 2026-09-29, P1-6 / C-3 / D-12: this used to be an unconditional
     * init with release-health sessions ON, before the consent screen.
     *
     * Second-pass #18: the close and the purge run on [crashReportingWorker],
     * not the caller's thread. Sentry.close() flushes, and can block for up to
     * the SDK's shutdown timeout, so on the main thread (the Settings toggle,
     * onCreate) it could stall the UI. Starting stays on the calling thread:
     * the SDK must be installed from Application.onCreate before anything can
     * crash, and an init waits for a close still in flight (a quick off → on
     * flip), or isEnabled() would still read true, the init would be skipped
     * and the close would then land on the new session.
     */
    fun applyCrashReportingConsent() {
        try {
            val optedIn = appPreferences.crashReportsEnabled
            if (CrashReporting.shouldStart(BuildConfig.DEBUG, BuildConfig.SENTRY_DSN, optedIn)) {
                awaitPendingCrashReportingShutdown()
                if (!Sentry.isEnabled()) initSentry(appPreferences.crashReportsEnabledSince)
            } else {
                val queueDir = cacheDir
                pendingCrashReportingShutdown = crashReportingWorker.submit(Runnable {
                    try {
                        if (Sentry.isEnabled()) Sentry.close()
                        if (!optedIn) CrashReporting.discardUnsentReports(queueDir)
                    } catch (e: Exception) {
                        android.util.Log.e("BirdoApp", "Crash-reporting shutdown failed", e)
                    }
                })
            }
        } catch (e: Exception) {
            // Crash-reporting set-up must never take down the whole app.
            android.util.Log.e("BirdoApp", "Crash-reporting state change failed", e)
        }
    }

    /** One background thread, so closes and purges never overlap or reorder. */
    private val crashReportingWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "birdo-crash-reporting").apply { isDaemon = true }
    }

    @Volatile
    private var pendingCrashReportingShutdown: Future<*>? = null

    /**
     * Bounded wait for a close still in flight. Past the bound the init goes
     * ahead anyway; the worst case is an SDK that is off until the next start,
     * never one that is on without consent.
     */
    private fun awaitPendingCrashReportingShutdown() {
        val pending = pendingCrashReportingShutdown ?: return
        try {
            pending.get(CRASH_REPORTING_SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            android.util.Log.w("BirdoApp", "Crash-reporting shutdown still running", e)
        }
    }

    private companion object {
        /** Sentry's own shutdown timeout is 2 s; allow a little more. */
        const val CRASH_REPORTING_SHUTDOWN_WAIT_SECONDS = 3L
    }

    /**
     * Start Sentry. Reached only through [applyCrashReportingConsent], i.e.
     * only after the user opted in.
     *
     * @param consentSinceMillis when the user opted in; events describing
     *   anything earlier (a historical ANR the SDK reads from the OS on first
     *   start) are dropped in beforeSend.
     */
    private fun initSentry(consentSinceMillis: Long) {
        // Skip Sentry entirely in debug builds — avoids DSN validation issues
        // and keeps development logcat clean. This is also why a debug build
        // never needs the SENTRY_DSN secret; see docs/SENTRY-SETUP.md.
        if (BuildConfig.DEBUG) return

        // Nothing to send to. A release build cannot normally reach this branch
        // — :app:validateSentryDsn refuses to produce a release artifact with a
        // blank or malformed DSN (issue #357) — but an older artifact, or one
        // built with -PallowMissingSentryDsn=true, can. Return before init
        // rather than handing Sentry a value it will either reject with an
        // exception or accept into a permanent no-op.
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank() || dsn == "null") return

        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            // NO release-health sessions. A session envelope is a per-install
            // id sent on every app start: usage telemetry, not a crash report,
            // and not something the consent screen describes.
            options.isEnableAutoSessionTracking = false
            options.environment = "production"
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.APP_VERSION}"

            // ── What a report may describe ─────────────────────────────────
            // The consent screen describes crash and error reports (a crash's
            // stack trace, or FaultReporter's event naming the feature that
            // failed) with app and OS version and device model/architecture
            // (second-pass #7). These stop the SDK COLLECTING the
            // rest (battery, memory, storage, root status, system and
            // connectivity events); beforeSend below then rebuilds the
            // contexts from an allow-list, so anything a future SDK adds is
            // dropped too.
            options.isCollectAdditionalContext = false
            options.isEnableRootCheck = false
            options.isEnableSystemEventBreadcrumbs = false
            options.isEnableNetworkEventBreadcrumbs = false
            // Only the most recent ANR is ever read back from the OS (the SDK
            // default, pinned), and beforeSend drops it when it predates the
            // opt-in.
            options.isReportHistoricalAnrs = false

            // ── Privacy ────────────────────────────────────────────────────
            // This is a VPN whose privacy policy states no connection logs are
            // kept. A crash reporter that exports a destination host, a tunnel
            // address or an account email contradicts that policy just as
            // surely as a server-side log line would. Every switch below is set
            // EXPLICITLY, including the ones that already default the safe way:
            // an SDK default is a decision made by someone else that can change
            // in a version bump, and "we relied on the default" is not an
            // answer to a data-protection question.
            options.isSendDefaultPii = false          // no IP, no device name
            options.isAttachScreenshot = false        // never photograph the UI
            options.isAttachViewHierarchy = false     // …nor describe it
            options.isAttachServerName = false        // no host identity
            options.isSendModules = false             // no dependency inventory

            // User-interaction tracing/breadcrumbs record which control the
            // user touched, keyed by resource id, on every tap. That is a
            // behavioural trace of a privacy tool's UI; it is not needed to
            // diagnose a crash.
            options.isEnableUserInteractionTracing = false
            options.isEnableUserInteractionBreadcrumbs = false

            // Session Replay records the screen. Its defaults are already 0,
            // and it is pinned to 0 here so that a future SDK default, or a
            // stray copy-paste, cannot switch a screen recorder on inside a
            // VPN client.
            options.sessionReplay.sessionSampleRate = 0.0
            options.sessionReplay.onErrorSampleRate = 0.0

            // Performance monitoring is OFF, and not merely unsampled.
            //
            // beforeSend — the scrubber below — does NOT run for transactions;
            // they take the separate beforeSendTransaction path. Span
            // descriptions and transaction names are exactly where a request
            // URL or an endpoint host would appear, so leaving APM on would
            // open an unscrubbed egress channel alongside a carefully scrubbed
            // one. Dropped twice over: nothing is sampled, and anything that
            // somehow is gets discarded before it can be sent.
            options.tracesSampleRate = 0.0
            options.beforeSendTransaction =
                io.sentry.SentryOptions.BeforeSendTransactionCallback { _, _ -> null }

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
            // Scrub breadcrumbs at CAPTURE time, not only on the way out.
            // beforeSend (below) sees only the crumbs attached to an event it
            // is given; a crumb recorded now can also be attached by a code
            // path that does not pass through that callback. Scrubbing on
            // arrival means an unscrubbed value is never held in the ring
            // buffer at all — the buffer is in-process memory that lands in a
            // native crash dump or an ANR trace.
            options.beforeBreadcrumb = io.sentry.SentryOptions.BeforeBreadcrumbCallback { crumb, _ ->
                crumb.message = scrub(crumb.message)
                crumb.data.keys.toList().forEach { key ->
                    val value = crumb.data[key]
                    if (value is String) crumb.setData(key, scrub(value) ?: "")
                }
                crumb
            }

            options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
                // Consent is not retroactive. The SDK's first start reads the
                // most recent ANR back from the OS, and that ANR happened
                // while crash reporting was OFF — drop anything that predates
                // the opt-in.
                if (CrashReporting.predatesConsent(event.timestamp, consentSinceMillis)) {
                    return@BeforeSendCallback null
                }
                event.message?.let { it.formatted = scrub(it.formatted) }
                event.exceptions?.forEach { ex -> ex.value = scrub(ex.value) }
                // Breadcrumbs ride along on crash events (auto-instrumented
                // network/lifecycle crumbs included) and were sent VERBATIM —
                // scrub both the message and every string data value.
                // Re-applied here as well as in beforeBreadcrumb: a crumb can
                // be attached to an event by a path that bypassed the capture
                // hook, and scrub() is idempotent.
                event.breadcrumbs?.forEach { crumb ->
                    crumb.message = scrub(crumb.message)
                    crumb.data.keys.toList().forEach { key ->
                        val value = crumb.data[key]
                        if (value is String) crumb.setData(key, scrub(value) ?: "")
                    }
                }
                // Anything a future caller attaches with Sentry.setExtra /
                // withScope { it.setExtra(...) } — the most likely place for
                // someone to helpfully add "endpoint" or "server" to a report.
                event.extras?.keys?.toList()?.forEach { key ->
                    val value = event.getExtra(key)
                    if (value is String) event.setExtra(key, scrub(value) ?: "")
                }
                // Tags are low-cardinality labels; a scrubbed one is still a
                // label. Same reasoning as extras.
                event.tags?.keys?.toList()?.forEach { key ->
                    event.getTag(key)?.let { event.setTag(key, scrub(it) ?: "") }
                }
                // Belt and braces on identity: isSendDefaultPii=false already
                // stops the SDK populating these, but an explicit
                // Sentry.setUser(...) added later would land here regardless.
                // The account this crash belongs to is not something a VPN
                // needs in order to fix the crash.
                event.user = null
                event.serverName = null
                // Device / OS / app contexts rebuilt from an allow-list: model
                // and architecture, OS name and version, app id/version/build.
                // The SDK's defaults also carry a per-install id, a device-app
                // hash, locale, timezone, screen, memory and storage figures.
                CrashReporting.minimiseContexts(event.contexts)
                event
            }
        }

        // CPU-feature attribution on every report from the first frame on:
        // the process ABI and the kernel's /proc/cpuinfo Features line. Set
        // here, not only when the PQ library loads, so a native crash in ANY
        // library -- or before RosenpassNative ever runs -- still says which
        // ISA extensions the device has. RosenpassNative adds the HWCAP words
        // and the ML-KEM implementation name once the .so is up. The
        // 1.3.25..1.4.25 SIGILL took three investigations to attribute to
        // "no sha3"; this is what would have answered it from the first
        // report. Scope sync to the NDK layer is on by default, so native
        // crash envelopes carry these tags too.
        try {
            CpuFeatures.tagSentryBaseline()
        } catch (e: Exception) {
            android.util.Log.w("BirdoApp", "cpu-feature tags unavailable", e)
        }
    }
}
