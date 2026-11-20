package app.birdo.vpn.service

import app.birdo.vpn.data.model.VpnServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MR-2492 (MR-2212's rule, the same on every client): which relay the one
 * automatic move goes to when the server a session was on went offline for
 * good. The Android twin of iOS RelayRetryTests.swift (Mobile #645,
 * 70b26a0e), case for case.
 *
 * Owner rule, 2026-11-19: a server the client chose may move to another relay
 * in any country; a location the user picked never leaves its country; never
 * a node the server lists offline (a jurisdiction hold depools the node) or
 * one this plan cannot use; ONE move; the log says counts only.
 */
class RelayRetryTest {

    private fun node(
        id: String,
        countryCode: String = "DE",
        city: String = "Frankfurt",
        load: Int = 10,
        isOnline: Boolean = true,
        accessible: Boolean = true,
    ) = VpnServer(
        id = id,
        name = id,
        country = "country-of-$countryCode",
        countryCode = countryCode,
        city = city,
        load = load,
        accessible = accessible,
        isOnline = isOnline,
    )

    private val automatic = SelectionOrigin.AUTOMATIC
    private val user = SelectionOrigin.USER

    // ── The same-city rule (iOS #549's, kept) ─────────────────────────────

    @Test
    fun `picks the least loaded online sibling in the same city`() {
        val list = listOf(node("a"), node("b", load = 40), node("c", load = 5))
        assertEquals("c", RelayRetry.sibling("a", list)?.id)
    }

    @Test
    fun `never returns the relay that failed`() {
        assertNull(RelayRetry.sibling("a", listOf(node("a"))))
    }

    @Test
    fun `other cities and countries are not siblings`() {
        val list = listOf(node("a"), node("b", city = "Berlin"), node("c", countryCode = "NL"))
        assertNull(RelayRetry.sibling("a", list))
    }

    @Test
    fun `offline and out-of-plan siblings are skipped`() {
        val list = listOf(
            node("a"), node("b", isOnline = false), node("c", accessible = false), node("d", load = 90),
        )
        assertEquals("d", RelayRetry.sibling("a", list)?.id)
    }

    @Test
    fun `case and whitespace in the location are ignored`() {
        val list = listOf(node("a"), node("b", countryCode = "de", city = " frankfurt "))
        assertEquals("b", RelayRetry.sibling("a", list)?.id)
    }

    @Test
    fun `an empty city never matches`() {
        val list = listOf(node("a", city = ""), node("b", city = ""))
        assertNull(RelayRetry.sibling("a", list))
    }

    @Test
    fun `an unknown failed id yields no sibling`() {
        assertNull(RelayRetry.sibling("zzz", listOf(node("a"), node("b"))))
    }

    // ── The fleet as it is: one relay per city ─────────────────────────────

    /** `birdo-infra:deploy/fleet-nodes.conf`: 10 relays, 10 cities, 9 countries (as iOS's test). */
    private val fleet = listOf(
        node("ams", countryCode = "NL", city = "Amsterdam", load = 30),
        node("fra", countryCode = "DE", city = "Frankfurt", load = 10),
        node("lon", countryCode = "GB", city = "London", load = 25),
        node("lax", countryCode = "US", city = "Los Angeles", load = 40),
        node("nyc", countryCode = "US", city = "New York", load = 15),
        node("sao", countryCode = "BR", city = "São Paulo", load = 35),
        node("sin", countryCode = "SG", city = "Singapore", load = 45),
        node("sto", countryCode = "SE", city = "Stockholm", load = 20),
        node("tyo", countryCode = "JP", city = "Tokyo", load = 50),
        node("yyz", countryCode = "CA", city = "Toronto", load = 55),
    )

    @Test
    fun `on today's fleet the same-city rule never finds a relay`() {
        for (n in fleet) {
            assertNull(n.id, RelayRetry.sibling(n.id, fleet))
        }
    }

    /**
     * The rule on the same fleet: an automatic pick always has somewhere to
     * go; a user's pick only where its country has a second relay (today the
     * United States).
     */
    @Test
    fun `on today's fleet only an automatic pick leaves the country`() {
        for (n in fleet) {
            val auto = RelayRetry.next(n.id, fleet, automatic)
            assertNotNull(n.id, auto)
            assertNotEquals(n.id, auto?.server?.id)
            val mine = RelayRetry.next(n.id, fleet, user)
            if (n.countryCode == "US") {
                assertEquals(n.id, "US", mine?.server?.countryCode)
                assertEquals(n.id, RelayRetry.Reach.SAME_COUNTRY, mine?.reach)
            } else {
                assertNull("${n.id}: a user's location never leaves its country", mine)
            }
        }
        assertEquals("lax", RelayRetry.next("nyc", fleet, user)?.server?.id)
        assertEquals("nyc", RelayRetry.next("lax", fleet, user)?.server?.id)
    }

    // ── Automatic pick: may move to another country, once ──────────────────

    @Test
    fun `an automatic pick moves to the best relay in another country`() {
        val list = listOf(
            node("fra"),
            node("ams", countryCode = "NL", city = "Amsterdam", load = 30),
            node("sto", countryCode = "SE", city = "Stockholm", load = 20),
        )
        val choice = RelayRetry.next("fra", list, automatic)
        assertEquals("the least loaded of the other relays", "sto", choice?.server?.id)
        assertEquals(RelayRetry.Reach.OTHER_COUNTRY, choice?.reach)
    }

    @Test
    fun `an automatic pick still takes the same city first`() {
        val list = listOf(node("fra"), node("fra2", load = 60), node("ams", countryCode = "NL", city = "Amsterdam", load = 0))
        val choice = RelayRetry.next("fra", list, automatic)
        assertEquals("fra2", choice?.server?.id)
        assertEquals(RelayRetry.Reach.SAME_CITY, choice?.reach)
    }

    @Test
    fun `an automatic pick inside its own country is reported as such`() {
        val list = listOf(
            node("nyc", countryCode = "US", city = "New York"),
            node("lax", countryCode = "US", city = "Los Angeles", load = 1),
            node("ams", countryCode = "NL", city = "Amsterdam", load = 50),
        )
        assertEquals(RelayRetry.Reach.SAME_COUNTRY, RelayRetry.next("nyc", list, automatic)?.reach)
    }

    @Test
    fun `an automatic pick moves to another country exactly once`() {
        val list = listOf(
            node("fra"),
            node("ams", countryCode = "NL", city = "Amsterdam", load = 30),
            node("sto", countryCode = "SE", city = "Stockholm", load = 20),
        )
        val budget = RelayRetryBudget()
        // Frankfurt goes away: one move, to another country.
        val first = RelayRetry.plan("fra", list, automatic, budget)
        assertEquals("sto", first?.server?.id)
        assertEquals(RelayRetry.Reach.OTHER_COUNTRY, first?.reach)
        assertTrue(budget.spent)
        // Stockholm, the move, does not come up either: no second move, even
        // though Amsterdam is still there to try.
        assertNull(RelayRetry.plan("sto", list, automatic, budget))
        assertNull(RelayRetry.plan("fra", list, automatic, budget))
        assertTrue(budget.spent)
    }

    // ── User pick: never leaves its country ────────────────────────────────

    @Test
    fun `a user pick never leaves its country`() {
        val list = listOf(
            node("fra"),
            node("ams", countryCode = "NL", city = "Amsterdam", load = 0),
            node("lon", countryCode = "GB", city = "London", load = 0),
        )
        assertNull(RelayRetry.next("fra", list, user))
        val budget = RelayRetryBudget()
        assertNull(RelayRetry.plan("fra", list, user, budget))
        assertFalse("no move happened, so none was spent", budget.spent)
    }

    @Test
    fun `a user pick may move within its country`() {
        val list = listOf(
            node("nyc", countryCode = "US", city = "New York"),
            node("lax", countryCode = "US", city = "Los Angeles", load = 70),
            node("yyz", countryCode = "CA", city = "Toronto", load = 0),
        )
        val choice = RelayRetry.next("nyc", list, user)
        assertEquals("never Toronto, however empty", "lax", choice?.server?.id)
        assertEquals(RelayRetry.Reach.SAME_COUNTRY, choice?.reach)
    }

    @Test
    fun `a user pick prefers the same city`() {
        val list = listOf(
            node("nyc", countryCode = "US", city = "New York"),
            node("nyc2", countryCode = "US", city = "New York", load = 80),
            node("lax", countryCode = "US", city = "Los Angeles", load = 0),
        )
        val choice = RelayRetry.next("nyc", list, user)
        assertEquals("nyc2", choice?.server?.id)
        assertEquals(RelayRetry.Reach.SAME_CITY, choice?.reach)
    }

    @Test
    fun `a user pick with no known country stays put`() {
        val list = listOf(
            node("a", countryCode = " ", city = ""),
            node("b", countryCode = " ", city = "", load = 0),
            node("c", countryCode = "NL", city = "Amsterdam", load = 0),
        )
        assertNull(RelayRetry.next("a", list, user))
    }

    @Test
    fun `a user pick in the same country is matched whatever the case`() {
        val list = listOf(
            node("nyc", countryCode = "US", city = "New York"),
            node("lax", countryCode = " us ", city = "Los Angeles"),
        )
        assertEquals("lax", RelayRetry.next("nyc", list, user)?.server?.id)
    }

    // ── Held or ineligible nodes are never picked ──────────────────────────

    /**
     * A node held for a jurisdiction mismatch fails its health check and is
     * listed `isOnline = false`; a node above the plan is `accessible = false`.
     */
    @Test
    fun `a held or ineligible node is never picked`() {
        for (origin in SelectionOrigin.entries) {
            val list = listOf(
                node("nyc", countryCode = "US", city = "New York"),
                node("nyc-held", countryCode = "US", city = "New York", load = 0, isOnline = false),
                node("lax-paid", countryCode = "US", city = "Los Angeles", load = 0, accessible = false),
                node("lax-held", countryCode = "US", city = "Los Angeles", load = 0, isOnline = false),
                node("lax", countryCode = "US", city = "Los Angeles", load = 90),
            )
            assertEquals("$origin", "lax", RelayRetry.next("nyc", list, origin)?.server?.id)
        }
        val onlyIneligible = listOf(
            node("fra"),
            node("ams", countryCode = "NL", city = "Amsterdam", load = 0, isOnline = false),
            node("lon", countryCode = "GB", city = "London", load = 0, accessible = false),
        )
        assertNull(RelayRetry.next("fra", onlyIneligible, automatic))
        val budget = RelayRetryBudget()
        assertNull(RelayRetry.plan("fra", onlyIneligible, automatic, budget))
        assertFalse(budget.spent)
    }

    @Test
    fun `the failed relay is never the target`() {
        for (origin in SelectionOrigin.entries) {
            assertNull("$origin", RelayRetry.next("a", listOf(node("a", load = 0)), origin))
        }
    }

    // ── A server deleted from the fleet (owner decision 2026-11-19, round 3) ──
    //
    // The backend's server_offline covers a drain AND a delete; a deleted node
    // is gone from the next /vpn/servers. iOS's `next` moves nothing when the
    // failed relay is not listed; the owner's rule for that case:
    //   * a server the client chose moves to the best eligible server anywhere;
    //   * a location the user picked moves only inside the country it was last
    //     listed in (the list cached before the refresh), else it stops.

    @Test
    fun `an automatic pick whose server was deleted moves to the best server anywhere`() {
        val list = listOf(
            node("ams", countryCode = "NL", city = "Amsterdam", load = 30),
            node("sto", countryCode = "SE", city = "Stockholm", load = 20),
            node("lon", countryCode = "GB", city = "London", load = 0, isOnline = false),
        )
        val choice = RelayRetry.next("fra", list, automatic)
        assertEquals("the plan filter still applies: never the offline London", "sto", choice?.server?.id)
        assertEquals(RelayRetry.Reach.OTHER_COUNTRY, choice?.reach)
        val budget = RelayRetryBudget()
        assertEquals("sto", RelayRetry.plan("fra", list, automatic, budget)?.server?.id)
        assertTrue("the same single move", budget.spent)
        assertNull(RelayRetry.plan("sto", list, automatic, budget))
    }

    @Test
    fun `a user pick whose server was deleted moves only inside the country it was last listed in`() {
        val lastKnown = node("fra", countryCode = "DE", city = "Frankfurt")
        val list = listOf(
            node("ber", countryCode = "DE", city = "Berlin", load = 50),
            node("ams", countryCode = "NL", city = "Amsterdam", load = 0),
        )
        val choice = RelayRetry.next("fra", list, user, lastKnown)
        assertEquals("never Amsterdam, however empty", "ber", choice?.server?.id)
        assertEquals(RelayRetry.Reach.SAME_COUNTRY, choice?.reach)
    }

    @Test
    fun `a user pick whose server was deleted, with nothing left in its country, stays put`() {
        val lastKnown = node("fra", countryCode = "DE", city = "Frankfurt")
        val list = listOf(node("ams", countryCode = "NL", city = "Amsterdam", load = 0))
        assertNull(RelayRetry.next("fra", list, user, lastKnown))
    }

    @Test
    fun `a user pick whose deleted server's country is unknown stays put`() {
        val list = listOf(node("ber", countryCode = "DE", city = "Berlin"), node("ams", countryCode = "NL", city = "Amsterdam"))
        assertNull("no cached entry", RelayRetry.next("fra", list, user))
        // A cached entry for ANOTHER server says nothing about this one.
        assertNull(RelayRetry.next("fra", list, user, node("ber", countryCode = "DE", city = "Berlin")))
        assertNull("a cached entry with no country", RelayRetry.next("fra", list, user, node("fra", countryCode = " ")))
    }

    // ── Exactly one move ───────────────────────────────────────────────────

    @Test
    fun `the budget allows exactly one retry`() {
        val budget = RelayRetryBudget()
        assertFalse(budget.spent)
        assertTrue(budget.take())
        assertFalse("a second retry", budget.take())
        assertFalse(budget.take())
        assertTrue(budget.spent)
        // A dial the user (or the system) starts, or a session that comes up, is a new start.
        budget.refill()
        assertTrue(budget.take())
        assertFalse(budget.take())
    }

    @Test
    fun `a spent budget plans nothing and changes nothing`() {
        val budget = RelayRetryBudget()
        budget.take()
        assertNull(RelayRetry.plan("a", listOf(node("a"), node("b")), automatic, budget))
        assertTrue(budget.spent)
    }

    // ── Who chose the last server ──────────────────────────────────────────

    @Test
    fun `the stored flag reads back as who chose the last server, the user by default`() {
        assertEquals(SelectionOrigin.AUTOMATIC, SelectionOrigin.ofLastServer(autoPicked = true))
        assertEquals(SelectionOrigin.USER, SelectionOrigin.ofLastServer(autoPicked = false))
    }

    // ── Counts only in the log ─────────────────────────────────────────────

    @Test
    fun `the retry log line names no relay`() {
        // Distinct, greppable markers in every field a log line could leak.
        val failed = node("id-marker-failed", countryCode = "QX", city = "Failedcityx", load = 1)
        val near = node("id-marker-near", countryCode = "QX", city = "Nearcityx", load = 2)
        val far = node("id-marker-far", countryCode = "QZ", city = "Farcityx", load = 0)
        val list = listOf(failed, near, far)
        val budget = RelayRetryBudget()
        val moved = RelayRetry.plan(failed.id, list, automatic, budget)
        val spent = RelayRetry.plan(far.id, list, automatic, budget)
        val lines = listOf(
            RelayRetry.logLine(RelayRetry.next(failed.id, list, automatic), alreadySpent = false),
            RelayRetry.logLine(RelayRetry.next(failed.id, list, user), alreadySpent = false),
            RelayRetry.logLine(RelayRetry.next(failed.id, listOf(failed), user), alreadySpent = false),
            RelayRetry.logLine(moved, alreadySpent = false),
            RelayRetry.logLine(spent, alreadySpent = true),
        )
        assertEquals(
            listOf(
                "[RelayRetry] server offline: automatic retry 1 of 1 (other-country)",
                "[RelayRetry] server offline: automatic retry 1 of 1 (same-country)",
                "[RelayRetry] server offline: no relay qualifies for a retry (0 of 1)",
                "[RelayRetry] server offline: automatic retry 1 of 1 (other-country)",
                "[RelayRetry] server offline: the automatic retry is spent (1 of 1)",
            ),
            lines,
        )
        for (line in lines) {
            for (n in list) {
                for (field in listOf(n.id, n.name, n.city, n.countryCode, n.country)) {
                    assertFalse("$line leaks $field", line.lowercase().contains(field.lowercase()))
                }
            }
        }
    }
}
