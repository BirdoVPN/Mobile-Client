package app.birdo.vpn.utils

import io.sentry.protocol.App
import io.sentry.protocol.Contexts
import io.sentry.protocol.Device
import io.sentry.protocol.Gpu
import io.sentry.protocol.OperatingSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Date
import java.util.TimeZone

/**
 * Crash reporting is OPT-IN (audit 2026-09-29, P1-6 / C-3 / D-12). These pin
 * the decisions behind that: nothing starts without the opt-in, nothing from
 * before the opt-in is sent, and a report carries only what the consent screen
 * says it carries.
 */
class CrashReportingTest {

    private val dsn = "https://abc123@o1.ingest.de.sentry.io/42"

    // ── shouldStart ───────────────────────────────────────────────────────

    @Test
    fun `a release build with a DSN starts only after the user opts in`() {
        assertFalse(CrashReporting.shouldStart(isDebugBuild = false, dsn = dsn, optedIn = false))
        assertTrue(CrashReporting.shouldStart(isDebugBuild = false, dsn = dsn, optedIn = true))
    }

    @Test
    fun `debug builds never start, opted in or not`() {
        assertFalse(CrashReporting.shouldStart(isDebugBuild = true, dsn = dsn, optedIn = true))
    }

    @Test
    fun `no usable DSN means nothing to start`() {
        listOf(null, "", "   ", "null").forEach { value ->
            assertFalse(
                "DSN <$value> must not start the SDK",
                CrashReporting.shouldStart(isDebugBuild = false, dsn = value, optedIn = true),
            )
        }
    }

    // ── predatesConsent ───────────────────────────────────────────────────

    @Test
    fun `an event from before the opt-in is dropped`() {
        val optIn = 1_800_000_000_000L
        // A historical ANR read back from the OS on the SDK's first start.
        assertTrue(CrashReporting.predatesConsent(Date(optIn - 60_000), optIn))
    }

    @Test
    fun `an event from after the opt-in is kept`() {
        val optIn = 1_800_000_000_000L
        assertFalse(CrashReporting.predatesConsent(Date(optIn), optIn))
        assertFalse(CrashReporting.predatesConsent(Date(optIn + 1), optIn))
    }

    @Test
    fun `no consent on record means nothing may be sent`() {
        assertTrue(CrashReporting.predatesConsent(Date(), 0L))
        assertTrue(CrashReporting.predatesConsent(Date(), -1L))
    }

    @Test
    fun `an event without a timestamp is treated as captured now`() {
        assertFalse(CrashReporting.predatesConsent(null, 1_800_000_000_000L))
    }

    // ── minimiseContexts ──────────────────────────────────────────────────

    @Test
    fun `contexts are cut down to model, architecture, OS and app version`() {
        val contexts = Contexts().apply {
            setDevice(
                Device().apply {
                    model = "SM-S926B"
                    archs = arrayOf("arm64-v8a")
                    manufacturer = "samsung"
                    brand = "samsung"
                    name = "Alice's phone"
                    id = "per-install-id"
                    locale = "en_GB"
                    timezone = TimeZone.getTimeZone("Europe/London")
                    memorySize = 8L * 1024 * 1024 * 1024
                    bootTime = Date()
                },
            )
            setOperatingSystem(
                OperatingSystem().apply {
                    name = "Android"
                    version = "15"
                    kernelVersion = "6.1.0-android14"
                    build = "AP3A.240905.015"
                    setRooted(false)
                },
            )
            setApp(
                App().apply {
                    appIdentifier = "app.birdo.vpn"
                    appVersion = "1.4.32"
                    appBuild = "10432"
                    appName = "BirdoVPN"
                    deviceAppHash = "device-app-hash"
                    inForeground = true
                },
            )
            setGpu(Gpu().apply { name = "Adreno 750" })
            put("culture", mapOf("timezone" to "Europe/London"))
        }

        CrashReporting.minimiseContexts(contexts)

        val keys = contexts.keys().toList().toSet()
        assertEquals(setOf("device", "os", "app"), keys)

        val device = contexts.device
        assertNotNull(device)
        assertEquals("SM-S926B", device!!.model)
        assertArrayEquals(arrayOf("arm64-v8a"), device.archs)
        assertNull("the per-install id must not survive", device.id)
        assertNull(device.name)
        assertNull(device.manufacturer)
        assertNull(device.locale)
        assertNull(device.timezone)
        assertNull(device.memorySize)
        assertNull(device.bootTime)

        val os = contexts.operatingSystem
        assertNotNull(os)
        assertEquals("Android", os!!.name)
        assertEquals("15", os.version)
        assertNull(os.kernelVersion)
        assertNull(os.build)
        assertNull(os.isRooted())

        val app = contexts.app
        assertNotNull(app)
        assertEquals("app.birdo.vpn", app!!.appIdentifier)
        assertEquals("1.4.32", app.appVersion)
        assertEquals("10432", app.appBuild)
        assertNull("the device-app hash is a per-device identifier", app.deviceAppHash)
        assertNull(app.appName)
        assertNull(app.inForeground)

        assertNull(contexts.gpu)
    }

    @Test
    fun `contexts that were never set are not invented`() {
        val contexts = Contexts()
        CrashReporting.minimiseContexts(contexts)
        assertTrue(contexts.isEmpty())
    }

    // ── discardUnsentReports ──────────────────────────────────────────────

    @Test
    fun `opting out discards the queued reports and nothing else`() {
        val cacheDir = Files.createTempDirectory("birdo-cache").toFile()
        try {
            val outbox = File(cacheDir, "sentry/abcdef/outbox").apply { mkdirs() }
            File(outbox, "queued.envelope").writeText("{}")
            File(cacheDir, "sentry/abcdef/session.json").writeText("{}")
            val unrelated = File(cacheDir, "image_cache/tile.png").apply {
                parentFile!!.mkdirs()
                writeText("x")
            }

            CrashReporting.discardUnsentReports(cacheDir)

            assertFalse(File(cacheDir, CrashReporting.SENTRY_CACHE_DIR).exists())
            assertTrue("other cache contents must be left alone", unrelated.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `discarding with nothing queued is a no-op`() {
        val cacheDir = Files.createTempDirectory("birdo-cache").toFile()
        try {
            CrashReporting.discardUnsentReports(cacheDir)
            assertTrue(cacheDir.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
