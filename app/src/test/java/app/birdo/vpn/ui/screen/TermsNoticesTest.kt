package app.birdo.vpn.ui.screen

import app.birdo.vpn.data.model.TermsNotice
import app.birdo.vpn.data.model.UserProfile
import app.birdo.vpn.di.NetworkModule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * MR-2199, the Android half of birdo-web #808: an account with no address on
 * file gets no e-mail about a terms change, so `GET /auth/me` names the notices
 * that apply and Home shows them until their effective date.
 *
 * Decoded through THE wire serializer ([NetworkModule.json]). That the rules
 * reach Home is pinned by TermsNoticeWiringTest; iOS and macOS are pinned by
 * TermsNoticeTests.swift, to the same cases.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TermsNoticesTest {

    private val t0 = Instant.parse("2026-11-05T12:00:00Z").toEpochMilli()
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private val goodJson =
        """{"version":"2026-10-30","text":"We have changed our Terms.",""" +
            """"effectiveAt":"2026-12-05T00:00:00.000Z","path":"/legal/changes"}"""

    private fun profile(field: String): UserProfile =
        NetworkModule.json.decodeFromString("""{"id":"u1","email":"a@b.com"$field}""")

    private fun notices(value: String) = profile(""","termsNotices":$value""").termsNotices

    private fun at(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun notice(
        version: String = "2026-10-30",
        text: String = "We have changed our Terms of Service.",
        effectiveAt: String = at(t0 + 30 * day),
        path: String? = "/legal/changes",
    ) = TermsNotice(version, text, effectiveAt, path)

    // ── Decoding ─────────────────────────────────────────────────────────

    @Test
    fun `every entry is read, in the order sent, with its text as sent`() {
        val read = notices(
            """[$goodJson,{"version":"older","text":"  Second <b>one</b>.  ",""" +
                """"effectiveAt":"2026-12-06T00:00:00.000Z","path":"/legal/changes"}]""",
        )
        assertEquals(listOf("2026-10-30", "older"), read.map { it.version })
        assertEquals("We have changed our Terms.", read[0].text)
        assertEquals("2026-12-05T00:00:00.000Z", read[0].effectiveAt)
        assertEquals("/legal/changes", read[0].path)
        assertEquals("  Second <b>one</b>.  ", read[1].text)
    }

    @Test
    fun `no key means no notice, and the profile is what it was`() {
        val user = profile("")
        assertEquals(emptyList<TermsNotice>(), user.termsNotices)
        assertEquals("u1", user.id)
        assertEquals("a@b.com", user.email)
    }

    @Test
    fun `null or any shape but a list shows nothing and keeps the profile`() {
        for (bad in listOf("null", "\"soon\"", "42", "true", "{}", goodJson, "[[]]")) {
            val user = profile(""","termsNotices":$bad""")
            assertEquals(bad, emptyList<TermsNotice>(), user.termsNotices)
            assertEquals(bad, "u1", user.id)
        }
    }

    @Test
    fun `a bad entry does not hide a good one`() {
        val read = notices(
            """[{},"soon",42,null,[],$goodJson,{"version":"v"},""" +
                """{"version":1,"text":"t","effectiveAt":"e"},""" +
                """{"version":"v","text":null,"effectiveAt":"e"},""" +
                """{"version":"v","text":"t","effectiveAt":5}]""",
        )
        assertEquals(listOf("2026-10-30"), read.map { it.version })
    }

    @Test
    fun `a path that is not a string costs the link, never the text`() {
        for (path in listOf("null", "42", "{}", "[]", "true")) {
            val read = notices("""[{"version":"v","text":"t","effectiveAt":"e","path":$path}]""")
            assertEquals(path, listOf("t"), read.map { it.text })
            assertNull(path, read.single().path)
        }
        assertNull(notices("""[{"version":"v","text":"t","effectiveAt":"e"}]""").single().path)
    }

    // ── What is shown ────────────────────────────────────────────────────

    @Test
    fun `shown up to the last moment before effectiveAt, and not at or after it`() {
        val end = t0 + day
        val one = listOf(notice(effectiveAt = at(end)))
        assertEquals(one, TermsNotices.inForce(one, end - 1))
        assertEquals(emptyList<TermsNotice>(), TermsNotices.inForce(one, end))
        assertEquals(emptyList<TermsNotice>(), TermsNotices.inForce(one, end + 1))
    }

    @Test
    fun `each entry goes at its own date, and the rest keep the server's order`() {
        val later = notice(version = "later", effectiveAt = at(t0 + 2 * day))
        val sooner = notice(version = "sooner", effectiveAt = at(t0 + day))
        assertEquals(listOf(later, sooner), TermsNotices.inForce(listOf(later, sooner), t0))
        assertEquals(listOf(later), TermsNotices.inForce(listOf(later, sooner), t0 + day))
    }

    @Test
    fun `a bad effectiveAt is not shown, whatever a lenient parser would make of it`() {
        val bad = listOf(
            "", "soon", "2099", "5 Dec 2099", "2099-01-01", "2099-01-01T00:00:00", "2099-01-01T00:00Z",
            " 2099-01-01T00:00:00Z", "2099-13-01T00:00:00Z", "2099-02-30T00:00:00Z", "2099-01-01 00:00:00Z",
        )
        for (date in bad) {
            assertEquals(date, emptyList<TermsNotice>(), TermsNotices.inForce(listOf(notice(effectiveAt = date)), t0))
        }
    }

    @Test
    fun `a bad date does not take a good entry with it`() {
        val good = notice(version = "good")
        assertEquals(listOf(good), TermsNotices.inForce(listOf(notice(effectiveAt = "soon"), good), t0))
    }

    @Test
    fun `every full timestamp with a zone is read, to the same instant`() {
        val end = Instant.parse("2099-01-01T00:00:00Z").toEpochMilli()
        for (date in listOf("2099-01-01T00:00:00Z", "2099-01-01T00:00:00.000Z", "2099-01-01T01:00:00+01:00")) {
            assertEquals(date, end, TermsNotices.endMs(notice(effectiveAt = date)))
        }
    }

    @Test
    fun `a blank text is not shown`() {
        for (text in listOf("", "   ", "\n\t")) {
            assertEquals(emptyList<TermsNotice>(), TermsNotices.inForce(listOf(notice(text = text)), t0))
        }
    }

    @Test
    fun `the sentence is shown whole, past any limit the error banners use`() {
        // The 2026-10-30 notice is 237 characters, and it may carry anything.
        val long = "We have changed <our> Terms: " + "x".repeat(1_000)
        assertEquals(long, TermsNotices.inForce(listOf(notice(text = long)), t0).single().text)
    }

    // ── The link ─────────────────────────────────────────────────────────

    @Test
    fun `See every change opens the path the server named, on this site only`() {
        assertEquals("https://birdo.app/legal/changes", TermsNotices.link(notice()))
        val refused = listOf(null, "", "legal/changes", "//evil.example/x", "https://evil.example/x", "/\\evil", "/a b")
        for (path in refused) {
            assertNull(path, TermsNotices.link(notice(path = path)))
        }
    }

    @Test
    fun `a refused path leaves the text, and the other entry's link`() {
        val refused = notice(version = "refused", path = "https://evil.example/x")
        val fine = notice(version = "fine")
        val shown = TermsNotices.inForce(listOf(refused, fine), t0)
        assertEquals(listOf("refused", "fine"), shown.map { it.version })
        assertEquals(listOf(null, "https://birdo.app/legal/changes"), shown.map(TermsNotices::link))
    }

    // ── With the app left open ───────────────────────────────────────────

    @Test
    fun `the clock is read once a minute, and exactly at the date when that is sooner`() {
        val one = listOf(notice(effectiveAt = at(t0 + 30 * day)))
        assertEquals(minute, TermsNotices.nextCheckMs(one, t0))
        assertEquals(1_500L, TermsNotices.nextCheckMs(listOf(notice(effectiveAt = at(t0 + 1_500))), t0))
        assertNull("nothing left to end", TermsNotices.nextCheckMs(one, t0 + 30 * day))
        assertNull(TermsNotices.nextCheckMs(emptyList(), t0))
        assertNull("a bad date is never waited on", TermsNotices.nextCheckMs(listOf(notice(effectiveAt = "soon")), t0))
    }

    @Test
    fun `the soonest entry sets the wait, and a bad one never does`() {
        val list = listOf(
            notice(effectiveAt = at(t0 + day)),
            notice(effectiveAt = "soon"),
            notice(effectiveAt = at(t0 + 2_000)),
        )
        assertEquals(2_000L, TermsNotices.nextCheckMs(list, t0))
    }

    @Test
    fun `goes the moment the time reaches effectiveAt, with no new fetch`() = runTest {
        val end = 30 * day
        val seen = mutableListOf<List<TermsNotice>>()
        val list = listOf(notice(effectiveAt = at(t0 + end)))
        val job = launch { TermsNotices.inForceOver(list) { t0 + currentTime }.collect { seen += it } }

        runCurrent()
        assertEquals(list, seen.last())
        advanceTimeBy(end - 1)
        runCurrent()
        assertEquals(list, seen.last())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(emptyList<TermsNotice>(), seen.last())
        assertTrue("the watch ends with the last notice", job.isCompleted)
    }

    @Test
    fun `drops each entry at its own date`() = runTest {
        val later = notice(version = "later", effectiveAt = at(t0 + 3 * minute))
        val sooner = notice(version = "sooner", effectiveAt = at(t0 + 90_000))
        val seen = mutableListOf<List<String>>()
        launch { TermsNotices.inForceOver(listOf(later, sooner)) { t0 + currentTime }.collect { l -> seen += l.map { it.version } } }

        advanceTimeBy(90_000 - 1)
        runCurrent()
        assertEquals(listOf("later", "sooner"), seen.last())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("later"), seen.last())
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(emptyList<String>(), seen.last())
    }

    @Test
    fun `a device that slept past the date drops it within a tick`() = runTest {
        // A wait does not count while the device sleeps: the wall clock jumps past it.
        var wall = t0
        val seen = mutableListOf<List<TermsNotice>>()
        val list = listOf(notice(effectiveAt = at(t0 + day)))
        launch { TermsNotices.inForceOver(list) { wall }.collect { seen += it } }

        runCurrent()
        assertEquals(list, seen.last())
        wall = t0 + 2 * day
        advanceTimeBy(minute)
        runCurrent()
        assertEquals(emptyList<TermsNotice>(), seen.last())
    }

    @Test
    fun `no notice means no watch`() = runTest {
        val seen = mutableListOf<List<TermsNotice>>()
        val job = launch { TermsNotices.inForceOver(emptyList()) { t0 }.collect { seen += it } }
        runCurrent()
        assertEquals(listOf(emptyList<TermsNotice>()), seen)
        assertTrue(job.isCompleted)
    }
}
