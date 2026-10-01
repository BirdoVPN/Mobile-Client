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
     * Scrub what a VPN client must not export from a report string, applied
     * to the event message, every exception value and every breadcrumb.
     *
     * An uncaught crash (the normal path) has a null event.message, but its
     * exception string can embed exactly that: the endpoint host or IP
     * ("failed to connect to /144.x.x.x (port 51820)",
     * "UnknownHostException: de-fra-1.birdo.app" — no scheme, so a URL-only
     * pattern misses both), account emails, 44-char base64 WireGuard key
     * material, and an anonymous account's 24-digit number, bare or grouped as
     * the app shows it (REVIEW-AND2-010: `GET /auth/me` sends it bare as
     * `accountNumber` since the 2026-10-01 account API, and only the
     * `anon_…@anonymous.local` email form was caught). The set mirrors the
     * desktop's sanitize_error (redact.rs: IPv4 + email + bare hostname) plus
     * the key/UUID/URL patterns, so the two clients agree.
     *
     * Order matters: the account number first, URL before host/IP (so
     * scheme'd hosts collapse to [URL]), email before hostname (so the domain
     * half can't be half-matched), IPv6 before IPv4 (mapped forms).
     * Idempotent: no replacement matches any pattern.
     */
    fun scrub(s: String?): String? {
        var out = s ?: return null
        for ((pattern, replacement) in SCRUB_RULES) out = pattern.replace(out, replacement)
        return out
    }

    private val SCRUB_RULES: List<Pair<Regex, String>> = listOf(
        // 24 digits, alone or in six groups of four (space or hyphen). The
        // boundaries keep a longer digit run (a timestamp, a hash) whole.
        Regex("""\b\d{4}(?:[ -]?\d{4}){5}\b""") to "[ACCOUNT]",
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE) to "[UUID]",
        Regex("[0-9a-fA-F]{64}") to "[KEY]",
        // WireGuard/ML-KEM keys are 32 bytes → 43 base64 chars + '='.
        Regex("[A-Za-z0-9+/]{43}=") to "[KEY]",
        Regex("https?://[\\w.:-]+") to "[URL]",
        // IPv6: uncompressed (≥3 hex groups), then "::"-compressed. The
        // compressed pattern REQUIRES hex after the "::" so a bare "::" in code
        // symbols (Kotlin/C++ "Class::member") never matches.
        Regex("\\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}\\b") to "[IP]",
        Regex("(?:\\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4})*)?::[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4})*\\b") to "[IP]",
        Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b") to "[IP]",
        Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b") to "[EMAIL]",
        // Bare hostnames (≥3 labels, like our node names) — desktop HOST_RE.
        Regex(
            "\\b[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+" +
                "\\.[a-zA-Z]{2,}\\b",
        ) to "[HOST]",
    )

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
