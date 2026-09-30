package app.birdo.vpn.utils

import android.util.Log
import io.sentry.protocol.App
import io.sentry.protocol.Contexts
import io.sentry.protocol.Device
import io.sentry.protocol.OperatingSystem
import java.io.File
import java.util.Date

/**
 * The decisions behind OPT-IN crash reporting, kept free of Android and SDK
 * lifecycle so they can be unit tested. BirdoApp owns the SDK calls
 * (`SentryAndroid.init` / `Sentry.close`); everything that decides WHETHER and
 * WHAT is here.
 *
 * Audit 2026-09-29 (P1-6 / C-3 / D-12): Sentry used to start in
 * Application.onCreate for every release build, with release-health sessions
 * (a per-install id on every app start), before the consent screen was drawn,
 * with the SDK's full device/OS context attached and no way to turn it off,
 * while that same screen said "No personal data is included". The product now
 * promises: off by default; on only if the user turns it on; when on, a crash
 * report carries the stack trace, app version, OS version and device
 * model/architecture — no account, no IP-derived location, no browsing or VPN
 * data, and no session tracking.
 */
object CrashReporting {

    private const val TAG = "CrashReporting"

    /**
     * The SDK's on-disk cache, relative to `Context.cacheDir`. sentry-android
     * keeps its outbox, cached envelopes, session files and the NDK crash
     * database under `cacheDir/sentry` (AndroidOptionsInitializer).
     */
    internal const val SENTRY_CACHE_DIR = "sentry"

    /**
     * Whether the crash reporter may run at all.
     *
     * Debug builds never report (they need no DSN — docs/SENTRY-SETUP.md), a
     * build without a usable DSN has nothing to report to, and nothing starts
     * unless the user opted in.
     */
    fun shouldStart(isDebugBuild: Boolean, dsn: String?, optedIn: Boolean): Boolean {
        if (isDebugBuild || !optedIn) return false
        val value = dsn?.trim().orEmpty()
        return value.isNotEmpty() && value != "null"
    }

    /**
     * True when an event describes something that happened BEFORE the user
     * opted in, and so must not be sent.
     *
     * The SDK does not only report what happens while it runs. On its first
     * start it reads the most recent ANR from the OS's ApplicationExitInfo and
     * reports it; that ANR happened while crash reporting was off. Such events
     * carry the time of the original incident, so comparing against the moment
     * of opt-in is enough. An unknown opt-in moment ([consentSinceMillis] <= 0)
     * means there is no consent on record, and nothing may go.
     */
    fun predatesConsent(eventTime: Date?, consentSinceMillis: Long): Boolean {
        if (consentSinceMillis <= 0L) return true
        val at = eventTime?.time ?: return false // no timestamp = captured now
        return at < consentSinceMillis
    }

    /**
     * Reduce an event's contexts to what the product says a crash report
     * contains: device model and CPU architecture, OS name and version, and the
     * app's identifier, version and build. Everything else the SDK attaches is
     * dropped — the device and app contexts carry a per-install id and a
     * device-app hash, and the rest (locale, timezone, screen, memory, storage,
     * battery, boot time, GPU, runtime, "rooted") is diagnostics nobody asked
     * the user about.
     *
     * Works by rebuilding, not by nulling known fields: an SDK update that adds
     * a field to one of these contexts cannot leak it through here.
     */
    fun minimiseContexts(contexts: Contexts) {
        val device = contexts.device
        val os = contexts.operatingSystem
        val app = contexts.app

        contexts.keys().toList().forEach { contexts.remove(it) }

        if (device != null) {
            contexts.setDevice(
                Device().apply {
                    model = device.model
                    archs = device.archs
                },
            )
        }
        if (os != null) {
            contexts.setOperatingSystem(
                OperatingSystem().apply {
                    name = os.name
                    version = os.version
                },
            )
        }
        if (app != null) {
            contexts.setApp(
                App().apply {
                    appIdentifier = app.appIdentifier
                    appVersion = app.appVersion
                    appBuild = app.appBuild
                },
            )
        }
    }

    /**
     * Delete every report the SDK has queued on disk but not yet sent.
     *
     * Called whenever crash reporting is OFF (at start-up and on opt-out, after
     * the SDK is closed). It matters most on the first launch after upgrading
     * from a build that reported unconditionally: that build may have left
     * envelopes and session files in the outbox, and the first start after a
     * later opt-in would otherwise upload data collected before any consent.
     * Also means an opt-out takes effect for what is already queued, not only
     * for what happens next.
     *
     * Never throws: a cache that cannot be cleared must not take the app down.
     */
    fun discardUnsentReports(cacheDir: File) {
        val dir = File(cacheDir, SENTRY_CACHE_DIR)
        if (!dir.exists()) return
        if (!dir.deleteRecursively()) {
            Log.w(TAG, "could not fully clear the unsent crash-report cache")
        }
    }
}
