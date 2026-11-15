package app.birdo.vpn.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * MR-953 F5 — while Stealth runs, a failed DoH lookup never becomes a NEW
 * query to the network's own resolver.
 *
 * During a Stealth session the app's UID is outside its tunnel (Xray is a child
 * process), so the bypass client's lookups leave from the real IP. They used to
 * fall back to the system resolver whenever DoH failed: a cleartext lookup of
 * api.birdo.app to the network operator, on exactly the networks Stealth hides
 * from. Now the answer the device already has is reused (a DoH answer, or the
 * system answer obtained BEFORE Stealth began), or the lookup fails. These
 * drive [DohResolver.resolveWith] with fake collaborators; no network is used.
 */
class DohResolverStealthTest {

    private val dohAnswer = listOf(InetAddress.getByAddress("api.birdo.app", byteArrayOf(192.toByte(), 0, 2, 10)))
    private val systemAnswer = listOf(InetAddress.getByAddress("api.birdo.app", byteArrayOf(198.toByte(), 51, 100, 7)))

    private var systemCalls = 0
    private var now = 1_000L
    private val system: (String) -> List<InetAddress> = { systemCalls++; systemAnswer }
    private val dohFails: (String) -> List<InetAddress> = { throw IOException("DoH blocked") }
    private val dohWorks: (String) -> List<InetAddress> = { dohAnswer }

    private fun resolve(
        doh: (String) -> List<InetAddress>,
        stealth: Boolean,
        cache: MutableMap<String, DohResolver.Remembered> = mutableMapOf(),
        host: String = "api.birdo.app",
    ) = DohResolver.resolveWith(host, doh, system, { stealth }, cache, { now })

    private fun assertLookupFails(block: () -> Unit) {
        try {
            block()
            fail("the lookup must fail")
        } catch (_: UnknownHostException) {
        }
    }

    @Test
    fun `stealth with DoH failing and nothing remembered fails the lookup and never asks the system resolver`() {
        try {
            resolve(dohFails, stealth = true)
            fail("the lookup must fail during Stealth when DoH fails")
        } catch (e: UnknownHostException) {
            assertTrue(e.cause is IOException)
        }
        assertEquals("the system resolver was asked during Stealth", 0, systemCalls)
    }

    @Test
    fun `stealth with DoH failing reuses an earlier DoH answer for the same host`() {
        val cache = mutableMapOf<String, DohResolver.Remembered>()
        assertEquals(dohAnswer, resolve(dohWorks, stealth = true, cache = cache))
        assertEquals(dohAnswer, resolve(dohFails, stealth = true, cache = cache, host = "API.birdo.app"))
        assertEquals(0, systemCalls)
    }

    @Test
    fun `an earlier answer for ANOTHER host is not used`() {
        val cache = mutableMapOf("cloudflare-dns.com" to DohResolver.Remembered(dohAnswer, now))
        assertLookupFails { resolve(dohFails, stealth = true, cache = cache) }
        assertEquals(0, systemCalls)
    }

    // ── Review of #622, item 8: a network that blocks DoH ────────────────────

    /**
     * The whole point. On a network that blocks DoH the connect-time lookup
     * (Stealth is not active yet) is answered by the system resolver. Until
     * this fix that answer was thrown away, so once Stealth was up every
     * lookup failed, the heartbeat stopped, the server reaped the key after
     * five minutes and the session cycled. Reusing it asks nobody anything.
     */
    @Test
    fun `the answer obtained before stealth began is reused during stealth with no new system query`() {
        val cache = mutableMapOf<String, DohResolver.Remembered>()
        assertSame(systemAnswer, resolve(dohFails, stealth = false, cache = cache))
        assertEquals(1, systemCalls)
        repeat(5) {
            now += 60_000
            assertSame(systemAnswer, resolve(dohFails, stealth = true, cache = cache))
        }
        assertEquals("a NEW system-resolver query was made during Stealth", 1, systemCalls)
    }

    @Test
    fun `a remembered answer is reused up to the ttl and not after it`() {
        val cache = mutableMapOf<String, DohResolver.Remembered>()
        resolve(dohFails, stealth = false, cache = cache)
        now += DohResolver.REMEMBER_TTL_MS
        assertSame(systemAnswer, resolve(dohFails, stealth = true, cache = cache))
        now += 1
        assertLookupFails { resolve(dohFails, stealth = true, cache = cache) }
        assertTrue("an expired answer must be dropped", cache.isEmpty())
        assertEquals(1, systemCalls)
    }

    /**
     * Review of #622, item 7: an expired answer is removed only if it is
     * still the one stored. Here another lookup stores a fresh answer between
     * this lookup's read and its removal; a plain remove(key) deleted it.
     */
    @Test
    fun `an expired answer is dropped without deleting a fresh one stored meanwhile`() {
        val expired = DohResolver.Remembered(systemAnswer, now - DohResolver.REMEMBER_TTL_MS - 1, fromSystem = true)
        val fresh = DohResolver.Remembered(dohAnswer, now)
        val cache = object : HashMap<String, DohResolver.Remembered>() {
            // The read this lookup made, before the fresh answer landed.
            override fun get(key: String): DohResolver.Remembered? =
                if (key == "api.birdo.app") expired else super.get(key)
        }
        cache["api.birdo.app"] = fresh

        assertLookupFails { resolve(dohFails, stealth = true, cache = cache) }

        assertEquals(1, cache.size)
        assertSame("a fresh answer stored meanwhile was deleted", fresh, cache.values.single())
        assertEquals(0, systemCalls)
    }

    @Test
    fun `the ttl is one day`() {
        assertEquals(24L * 60 * 60 * 1000, DohResolver.REMEMBER_TTL_MS)
    }

    @Test
    fun `an answer from a clock that has gone backwards is not reused`() {
        val cache = mutableMapOf<String, DohResolver.Remembered>()
        resolve(dohFails, stealth = false, cache = cache)
        now -= 500
        assertLookupFails { resolve(dohFails, stealth = true, cache = cache) }
    }

    /** DoH is still asked first, and a DoH answer replaces a remembered system one. */
    @Test
    fun `a later DoH answer replaces the remembered system answer`() {
        val cache = mutableMapOf<String, DohResolver.Remembered>()
        resolve(dohFails, stealth = false, cache = cache)
        assertEquals(dohAnswer, resolve(dohWorks, stealth = true, cache = cache))
        assertEquals(dohAnswer, resolve(dohFails, stealth = true, cache = cache))
    }

    @Test
    fun `an empty answer is never remembered`() {
        val cache = mutableMapOf<String, DohResolver.Remembered>()
        DohResolver.resolveWith("api.birdo.app", dohFails, { emptyList() }, { false }, cache, { now })
        DohResolver.resolveWith("api.birdo.app", { emptyList() }, system, { false }, cache, { now })
        assertTrue(cache.isEmpty())
    }

    /** The production map, which forgetRememberedAnswers / forgetSystemAnswers act on. */
    @Suppress("UNCHECKED_CAST")
    private fun liveCache(): MutableMap<String, DohResolver.Remembered> {
        val field = DohResolver::class.java.getDeclaredField("remembered").apply { isAccessible = true }
        return field.get(DohResolver) as MutableMap<String, DohResolver.Remembered>
    }

    /** Sign-out: what was remembered for one account's session is gone for the next. */
    @Test
    fun `forgetting drops every remembered answer`() {
        val live = liveCache()
        live["api.birdo.app"] = DohResolver.Remembered(systemAnswer, now, fromSystem = true)
        live["birdo.app"] = DohResolver.Remembered(dohAnswer, now)
        try {
            DohResolver.forgetRememberedAnswers()
            assertTrue(live.isEmpty())
            assertLookupFails { resolve(dohFails, stealth = true, cache = live) }
            assertEquals(0, systemCalls)
        } finally {
            live.clear()
        }
    }

    // ── Review of #622, item 6: the API and web hosts only ───────────────

    @Test
    fun `only the API host and the web host from BuildConfig are remembered`() {
        assertEquals(setOf("api.birdo.app", "birdo.app"), DohResolver.REMEMBERED_HOSTS)
        assertEquals(
            setOf(
                java.net.URI(app.birdo.vpn.BuildConfig.API_BASE_URL).host,
                java.net.URI(app.birdo.vpn.BuildConfig.WEB_BASE_URL).host,
            ),
            DohResolver.REMEMBERED_HOSTS,
        )
    }

    @Test
    fun `another host is never remembered and never reused`() {
        val cache = mutableMapOf<String, DohResolver.Remembered>()
        assertEquals(dohAnswer, resolve(dohWorks, stealth = false, cache = cache, host = "example.com"))
        assertSame(systemAnswer, resolve(dohFails, stealth = false, cache = cache, host = "example.com"))
        assertEquals(dohAnswer, resolve(dohWorks, stealth = true, cache = cache, host = "cdn.example.net"))
        assertTrue("an answer for another host was remembered: ${cache.keys}", cache.isEmpty())
        // Not even one already in the map is reused.
        cache["example.com"] = DohResolver.Remembered(systemAnswer, now, fromSystem = true)
        assertLookupFails { resolve(dohFails, stealth = true, cache = cache, host = "example.com") }
        assertEquals(1, systemCalls)
    }

    @Test
    fun `a system answer is remembered as one and a DoH answer is not`() {
        val cache = mutableMapOf<String, DohResolver.Remembered>()
        resolve(dohFails, stealth = false, cache = cache)
        assertTrue(cache.getValue("api.birdo.app").fromSystem)
        resolve(dohWorks, stealth = false, cache = cache)
        assertFalse(cache.getValue("api.birdo.app").fromSystem)
    }

    /**
     * A user stop (BirdoVpnService.stopTunnel) forgets the answer the
     * network's resolver gave before Stealth began; BirdoVpnServiceLifecycleTest
     * shows a switch or a re-dial does not. A DoH answer, which disclosed
     * nothing, stays.
     */
    @Test
    fun `forgetting system answers drops them and keeps DoH answers`() {
        val live = liveCache()
        try {
            // Connect-time lookup (Stealth not active yet), DoH blocked.
            assertSame(systemAnswer, resolve(dohFails, stealth = false, cache = live))
            // A DoH answer for the web host from earlier.
            assertEquals(dohAnswer, resolve(dohWorks, stealth = false, cache = live, host = "birdo.app"))
            // During Stealth: reused, no new system query.
            assertSame(systemAnswer, resolve(dohFails, stealth = true, cache = live))
            assertEquals(1, systemCalls)

            DohResolver.forgetSystemAnswers()

            assertFalse(live.containsKey("api.birdo.app"))
            assertTrue(live.containsKey("birdo.app"))
            // A later Stealth session that made no lookup of its own has nothing to reuse.
            assertLookupFails { resolve(dohFails, stealth = true, cache = live) }
            assertEquals(dohAnswer, resolve(dohFails, stealth = true, cache = live, host = "birdo.app"))
            assertEquals("a NEW system-resolver query was made during Stealth", 1, systemCalls)
        } finally {
            live.clear()
        }
    }

    // ── Unchanged outside Stealth ────────────────────────────────────────────

    @Test
    fun `outside stealth the system resolver is still the fail-open fallback, asked every time`() {
        val cache = mutableMapOf<String, DohResolver.Remembered>()
        assertSame(systemAnswer, resolve(dohFails, stealth = false, cache = cache))
        assertSame(systemAnswer, resolve(dohFails, stealth = false, cache = cache))
        assertEquals("outside Stealth a remembered answer must not replace a fresh lookup", 2, systemCalls)
    }

    @Test
    fun `a working DoH answers in both modes without the system resolver`() {
        assertEquals(dohAnswer, resolve(dohWorks, stealth = true))
        assertEquals(dohAnswer, resolve(dohWorks, stealth = false))
        assertEquals(0, systemCalls)
    }

    // Outside Stealth, what main's resolve() did, case by case: the hostname
    // reaches both resolvers as given, the system resolver's own failure is
    // what the caller sees, a pin rejection still falls back, and an empty
    // DoH answer is returned as it is.

    @Test
    fun `outside stealth both resolvers get the hostname exactly as given`() {
        val asked = mutableListOf<String>()
        DohResolver.resolveWith(
            "API.Birdo.app",
            { asked += "doh:$it"; throw IOException("DoH blocked") },
            { asked += "system:$it"; systemAnswer },
            { false },
            mutableMapOf(),
            { now },
        )
        assertEquals(listOf("doh:API.Birdo.app", "system:API.Birdo.app"), asked)
    }

    @Test
    fun `outside stealth the system resolver's failure reaches the caller unchanged`() {
        val failure = UnknownHostException("no such host")
        try {
            DohResolver.resolveWith("api.birdo.app", dohFails, { throw failure }, { false }, mutableMapOf(), { now })
            fail("the system resolver's failure must propagate")
        } catch (e: UnknownHostException) {
            assertSame(failure, e)
        }
    }

    @Test
    fun `outside stealth a DoH pin rejection still falls back to the system resolver`() {
        val pinRejected: (String) -> List<InetAddress> = {
            throw javax.net.ssl.SSLPeerUnverifiedException("Certificate pinning failure")
        }
        assertSame(systemAnswer, resolve(pinRejected, stealth = false))
        assertEquals(1, systemCalls)
    }

    @Test
    fun `stealth with a DoH pin rejection fails and never asks the system resolver`() {
        val pinRejected: (String) -> List<InetAddress> = {
            throw javax.net.ssl.SSLPeerUnverifiedException("Certificate pinning failure")
        }
        assertLookupFails { resolve(pinRejected, stealth = true) }
        assertEquals(0, systemCalls)
    }

    @Test
    fun `outside stealth an empty DoH answer is returned as it is`() {
        assertTrue(resolve({ emptyList() }, stealth = false).isEmpty())
        assertEquals(0, systemCalls)
    }

    /** The Stealth flag is read only when DoH has failed, never on the hot path. */
    @Test
    fun `stealth is consulted only after DoH fails`() {
        var reads = 0
        DohResolver.resolveWith("api.birdo.app", dohWorks, system, { reads++; true }, mutableMapOf(), { now })
        assertEquals(0, reads)
        assertLookupFails {
            DohResolver.resolveWith("api.birdo.app", dohFails, system, { reads++; true }, mutableMapOf(), { now })
        }
        assertEquals(1, reads)
    }

    @Test
    fun `a blank hostname never reaches either resolver`() {
        var dohCalls = 0
        assertLookupFails {
            DohResolver.resolveWith(" ", { dohCalls++; dohAnswer }, system, { false }, mutableMapOf(), { now })
        }
        assertEquals(0, dohCalls)
        assertEquals(0, systemCalls)
    }

    // ── Review of #622, item 8: DoH's own system fallback ────────────────
    //
    // OkHttp's DnsOverHttps sends a name with no public suffix to its
    // systemDns, which is Dns.SYSTEM unless set.

    private fun countingSystem(onLookup: () -> List<InetAddress>) = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> = onLookup()
    }

    @Test
    fun `DoH's system fallback refuses during stealth and is the system resolver otherwise`() {
        var calls = 0
        var stealth = true
        val guard = DohResolver.stealthGuardedSystemDns({ stealth }, countingSystem { calls++; systemAnswer })

        assertLookupFails { guard.lookup("printer") }
        assertEquals("the network's resolver was asked during Stealth", 0, calls)

        stealth = false
        assertSame(systemAnswer, guard.lookup("printer"))
        assertEquals(1, calls)
    }

    @Test
    fun `outside stealth DoH's system fallback passes the system resolver's failure through unchanged`() {
        val failure = UnknownHostException("no such host")
        val guard = DohResolver.stealthGuardedSystemDns({ false }, countingSystem { throw failure })
        try {
            guard.lookup("printer")
            fail("the system resolver's failure must propagate")
        } catch (e: UnknownHostException) {
            assertSame(failure, e)
        }
    }

    @Test
    fun `the Cloudflare DoH lookup uses the guarded system fallback with the live flag`() {
        val src = source("app/src/main/java/app/birdo/vpn/data/network/DohResolver.kt")
        val builder = src.substringAfter("private val cloudflare: DnsOverHttps = DnsOverHttps.Builder()")
            .substringBefore(".build()")
        assertTrue(
            builder.contains(".systemDns(stealthGuardedSystemDns({ BirdoVpnService.stealthActive }, okhttp3.Dns.SYSTEM))"),
        )
    }

    // ── Wiring ───────────────────────────────────────────────────────────────

    private fun source(path: String): String {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("repo root not found")
        return File(dir, path).readText().replace("\r\n", "\n")
    }

    /**
     * The production path, argument by argument (review of #622: the old
     * guard passed with `doh = Dns.SYSTEM::lookup` or `remembered =
     * mutableMapOf()`). DoH is the Cloudflare client, the map is the one the
     * forget functions clear, the flag is the live one, the clock monotonic.
     */
    @Test
    fun `resolve is wired to Cloudflare DoH, the shared map, the live flag and the allow-list`() {
        val src = source("app/src/main/java/app/birdo/vpn/data/network/DohResolver.kt")
        val call = src.substringAfter("fun resolve(hostname: String): List<InetAddress> = resolveWith(")
        assertTrue("resolve no longer delegates to resolveWith", call.length < src.length)
        val args = call.substringBefore("\n    )").lines()
            .map { it.trim().removeSuffix(",") }
            .filter { it.isNotEmpty() }
            .associate { it.substringBefore(" = ") to it.substringAfter(" = ") }
        assertEquals(
            mapOf(
                "hostname" to "hostname",
                "doh" to "cloudflare::lookup",
                "system" to "okhttp3.Dns.SYSTEM::lookup",
                "stealthActive" to "{ BirdoVpnService.stealthActive }",
                "remembered" to "remembered",
                "nowMs" to "{ SystemClock.elapsedRealtime() }",
                "rememberedHosts" to "REMEMBERED_HOSTS",
            ),
            args,
        )

        // `cloudflare` is the pinned Cloudflare DoH client…
        val cloudflare = src.substringAfter("private val cloudflare: DnsOverHttps = DnsOverHttps.Builder()")
            .substringBefore(".build()")
        assertTrue(cloudflare.contains(".client(bootstrapClient)"))
        assertTrue(cloudflare.contains(""".url("https://cloudflare-dns.com/dns-query".toHttpUrl())"""))

        // …and `remembered` is the one map, the one sign-out and a user stop clear.
        assertEquals(
            1,
            Regex("""private val remembered = ConcurrentHashMap<String, Remembered>\(\)""").findAll(src).count(),
        )
        assertTrue(src.substringAfter("fun forgetRememberedAnswers() {").substringBefore("\n    }").contains("remembered.clear()"))
        assertTrue(
            src.substringAfter("fun forgetSystemAnswers() {").substringBefore("\n    }")
                .contains("remembered.entries.removeAll { it.value.fromSystem }"),
        )

        // resolveWith uses only what it is handed.
        val body = src.substringAfter("internal fun resolveWith(").substringBefore("val dns: okhttp3.Dns")
        for (direct in listOf("okhttp3.Dns.SYSTEM.lookup(", "cloudflare.lookup(", "BirdoVpnService.stealthActive", "SystemClock.")) {
            assertFalse("resolveWith reaches for $direct instead of its parameter", body.contains(direct))
        }
    }

    /** Every sign-out path (log out, deletion, a dead session) clears the tokens through clearAll. */
    @Test
    fun `sign-out forgets the remembered answers`() {
        val tokens = source("app/src/main/java/app/birdo/vpn/data/auth/TokenManager.kt")
        val clearAll = tokens.substringAfter("fun clearAll() {").substringBefore("\n    }")
        assertTrue(clearAll.contains("store.clear()"))
        assertTrue(clearAll.contains("DohResolver.forgetRememberedAnswers()"))
        val repo = source("app/src/main/java/app/birdo/vpn/data/repository/BirdoRepository.kt")
        assertTrue(
            "logout, deleteAccount and the dead-session path must all clear through TokenManager.clearAll()",
            Regex("""tokenManager\.clearAll\(\)""").findAll(repo).count() >= 3,
        )
    }
}
