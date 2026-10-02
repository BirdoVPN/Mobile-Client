package app.birdo.vpn.ui.screen

import app.birdo.vpn.data.model.RedeemVoucherResponse
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.ui.viewmodel.VoucherResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions the screens make, pinned without a device. */
class ScreenLogicTest {

    // ── A2-033 ───────────────────────────────────────────────────────────

    @Test
    fun `the connect haptic fires on the way into Connected only`() {
        assertTrue(connectHapticDue(wasConnected = false, isConnected = true))
        // Returning to the tab over a tunnel that was up all along.
        assertFalse(connectHapticDue(wasConnected = true, isConnected = true))
        assertFalse(connectHapticDue(wasConnected = true, isConnected = false))
        assertFalse(connectHapticDue(wasConnected = false, isConnected = false))
    }

    // ── A2-002 ───────────────────────────────────────────────────────────

    @Test
    fun `a DNS address can be typed one character at a time`() {
        // Every prefix of a valid address is ALLOWED in the field; only the
        // complete one is committed. The old setter rejected "1." outright,
        // so the dot never appeared and nothing could be typed.
        val typed = "1.1.1.1".indices.map { "1.1.1.1".substring(0, it + 1) }
        val commits = typed.map(::dnsValueToCommit)
        assertEquals(listOf(null, null, null, null, null, null, "1.1.1.1"), commits)
    }

    @Test
    fun `blank clears the custom resolver, invalid and private addresses never commit`() {
        assertEquals("", dnsValueToCommit(""))
        assertEquals("", dnsValueToCommit("   "))
        assertEquals("9.9.9.9", dnsValueToCommit(" 9.9.9.9 "))
        assertEquals("2606:4700:4700::1111", dnsValueToCommit("2606:4700:4700::1111"))
        assertNull(dnsValueToCommit("1.1.1."))
        assertNull(dnsValueToCommit("8.8"))
        assertNull(dnsValueToCommit("192.168.1.1"))
        assertNull(dnsValueToCommit("dns.google"))
    }

    // ── A2-022 ───────────────────────────────────────────────────────────

    @Test
    fun `a Play build never shows a web price for a paid plan`() {
        assertEquals(PriceLabel.PLAY, planPriceLabel("OPERATIVE", isPlayBuild = true, playPrice = "$4.99/mo", storefrontLoading = false))
        assertEquals(PriceLabel.CHECKING, planPriceLabel("OPERATIVE", isPlayBuild = true, playPrice = null, storefrontLoading = true))
        assertEquals(PriceLabel.NOT_ON_PLAY, planPriceLabel("SOVEREIGN", isPlayBuild = true, playPrice = null, storefrontLoading = false))
        // Free is free everywhere; the sideload and F-Droid builds keep the web figures.
        assertEquals(PriceLabel.WEB, planPriceLabel("RECON", isPlayBuild = true, playPrice = null, storefrontLoading = true))
        assertEquals(PriceLabel.WEB, planPriceLabel("OPERATIVE", isPlayBuild = false, playPrice = null, storefrontLoading = false))
    }

    // ── P1-014 / P1-015 ──────────────────────────────────────────────────

    private fun server(id: String, accessible: Boolean = true, online: Boolean = true, load: Int = 10, city: String = "") =
        VpnServer(id = id, name = id, country = "Germany", countryCode = "DE", city = city, accessible = accessible, isOnline = online, load = load)

    @Test
    fun `the picker lists favourites first, then usable, online, least loaded`() {
        val servers = listOf(
            server("locked", accessible = false, load = 1),
            server("offline", online = false, load = 1),
            server("busy", load = 90),
            server("quiet", load = 5),
            server("fav", load = 99),
        )
        val sorted = filterAndSortServers(servers, "", ServerFilter.All, favorites = setOf("fav"))
        assertEquals(listOf("fav", "quiet", "busy", "offline", "locked"), sorted.map { it.id })
    }

    @Test
    fun `search matches name, country or city`() {
        val servers = listOf(server("de-1", city = "Frankfurt"), server("de-2", city = "Berlin"))
        assertEquals(listOf("de-1"), filterAndSortServers(servers, "frank", ServerFilter.All, emptySet()).map { it.id })
        assertEquals(2, filterAndSortServers(servers, " germany ", ServerFilter.All, emptySet()).size)
        assertTrue(filterAndSortServers(servers, "", ServerFilter.Favorites, emptySet()).isEmpty())
    }

    @Test
    fun `plan names in prose are Title Case, never the slug`() {
        val names = PlanNames(free = "Free", operative = "Operative", sovereign = "Sovereign")
        assertEquals("Sovereign", names.of("SOVEREIGN"))
        assertEquals("Operative", names.of("operative"))
        assertEquals("Free", names.of("RECON"))
        assertEquals("Free", names.of(null))
    }

    // ── A2-027 ───────────────────────────────────────────────────────────

    private val copy = VoucherCopy(
        redeemed = "Added %1\$d days.",
        extended = "Subscription extended.",
        upgraded = "Plan upgraded to %1\$s.",
        invalidFormat = "bad format",
        notFound = "not found",
        alreadyRedeemed = "already used",
        expired = "expired",
        planDowngrade = "downgrade",
        rejected = "rejected",
        plans = PlanNames(free = "Free", operative = "Operative", sovereign = "Sovereign"),
    )

    @Test
    fun `a failure that is not about the code says what it is about`() {
        val failed = copy.messageFor(VoucherResult.Failed("Your session has expired. Sign in again."))
        assertEquals("Your session has expired. Sign in again.", failed)
        assertFalse("Network error" in failed)
    }

    @Test
    fun `a redemption names the plan in prose, and refusals map their slug`() {
        val upgraded = copy.messageFor(
            VoucherResult.Redeemed(RedeemVoucherResponse(ok = true, plan = "SOVEREIGN", durationDays = 30)),
        )
        assertEquals("Added 30 days. Plan upgraded to Sovereign.", upgraded)
        assertEquals("already used", copy.messageFor(VoucherResult.Rejected("already_redeemed")))
        assertEquals("rejected", copy.messageFor(VoucherResult.Rejected(null)))
    }
}
