package app.birdo.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The policy table, and the enumeration test Mobile-Client#336 asked for.
 *
 * #336 is a record of the estate's most common defect shape — "a guard on one
 * of N parallel paths" — with Multi-Hop as its textbook case: five entry
 * points, three guarded, and one of the guarded ones carrying a comment that
 * claimed it protected the quick-settings tile, which does not call it. Its
 * conclusion was that code review, tests of the guarded path, and comments
 * naming the other paths all failed to prevent it, and that what might is
 * "a test that enumerates the paths... a test that fails when someone adds a
 * sixth path."
 */
class MultiHopPolicyTest {

    // ── the table ──────────────────────────────────────────────────────────

    @Test
    fun `multi-hop off is a single hop, whatever the stored pair says`() {
        assertEquals(
            MultiHopPolicy.NewConnection.SingleHop,
            MultiHopPolicy.forNewConnection(false, "entry-1", "exit-1"),
        )
        assertEquals(
            MultiHopPolicy.NewConnection.SingleHop,
            MultiHopPolicy.forNewConnection(false, null, null),
        )
    }

    @Test
    fun `armed and complete dials the stored pair`() {
        assertEquals(
            MultiHopPolicy.NewConnection.MultiHop("entry-1", "exit-1"),
            MultiHopPolicy.forNewConnection(true, "entry-1", "exit-1"),
        )
    }

    @Test
    fun `armed but incomplete REFUSES — it never falls back to a single hop`() {
        // Each of these is a real state: a node retired, prefs half-written, or
        // a blank written by an older build. Every one of them must refuse.
        val incomplete = listOf(
            null to null,
            "entry-1" to null,
            null to "exit-1",
            "" to "exit-1",
            "entry-1" to "",
            "  " to "exit-1",
        )
        for ((entry, exit) in incomplete) {
            assertEquals(
                "entry=$entry exit=$exit must refuse, not downgrade",
                MultiHopPolicy.NewConnection.RefuseIncompletePair,
                MultiHopPolicy.forNewConnection(true, entry, exit),
            )
        }
    }

    @Test
    fun `a live multi-hop route refuses a server switch`() {
        val change = MultiHopPolicy.forRouteChange(onTunnel = true, activeRoute = "e" to "x")
        assertTrue(change is MultiHopPolicy.RouteChange.RefuseWouldDowngrade)
        assertEquals(
            MultiHopPolicy.DOWNGRADE_REFUSAL,
            (change as MultiHopPolicy.RouteChange.RefuseWouldDowngrade).message,
        )
    }

    @Test
    fun `off the tunnel, a stale route must not block a tap`() {
        // activeMultiHopRoute goes stale by design: it is set before the tunnel
        // exists and cleared only by connect()/tearDownTunnel(), so a
        // service-driven teardown leaves it set with nothing running. Gating on
        // it alone would refuse every server tap while the user is plainly
        // disconnected, with no Disconnect control anywhere to satisfy the
        // message.
        assertEquals(
            MultiHopPolicy.RouteChange.Allowed,
            MultiHopPolicy.forRouteChange(onTunnel = false, activeRoute = "e" to "x"),
        )
    }

    @Test
    fun `a single-hop session allows a server switch`() {
        assertEquals(
            MultiHopPolicy.RouteChange.Allowed,
            MultiHopPolicy.forRouteChange(onTunnel = true, activeRoute = null),
        )
    }

    // ── the enumeration ────────────────────────────────────────────────────

    private val srcRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        File(dir, "app/src/main/java/app/birdo/vpn")
    }

    /**
     * Every source file that can START a tunnel must consult [MultiHopPolicy].
     *
     * This is the test that fails when someone adds a sixth path. It reads the
     * sources rather than the compiled classes because the defect it guards
     * against is a NEW call site written in a NEW file — which no
     * reflection-based test can see, and which compiles perfectly.
     *
     * If this fails on a file you just added: route its connect decision
     * through MultiHopPolicy. If the file genuinely cannot downgrade a route
     * (it only disconnects, or it re-dials a route it was handed), add it to
     * [exempt] WITH the reason, the same way the two entries below carry one.
     */
    @Test
    fun `every file that can start a tunnel consults MultiHopPolicy`() {
        val exempt = mapOf(
            // The implementation itself: connect()/connectMultiHop() ARE the
            // actions the policy decides about. It cannot consult itself.
            "service/VpnManager.kt" to "the implementation the policy decides about",
            // Re-dials a route it is handed, and rebuilds multi-hop as
            // multi-hop by construction (startAutoReconnect reads
            // activeMultiHop). It makes no armed/not-armed decision.
            "service/BirdoVpnService.kt" to "re-dials an existing route; makes no arming decision",
        )

        val offenders = mutableListOf<String>()
        srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = file.relativeTo(srcRoot).path.replace('\\', '/')
                if (rel == "service/MultiHopPolicy.kt") return@forEach
                val text = file.readText()
                // The shape being detected is not "calls connect" — the API
                // interface and the repository wrapper do that, and the
                // navigation graph passes a pair the user picked by hand. It is
                // "reads the ARMING PREFS and then decides what to dial", which
                // is where all five drifting call sites lived.
                val startsATunnel = Regex("""\bconnectMultiHop\s*\(|vpnManager\.connect\s*\(|vpnManager\.quickConnect\s*\(""").containsMatchIn(text)
                val readsArmingPrefs = Regex("""multiHopEnabled|multiHopEntryNodeId|multiHopExitNodeId""").containsMatchIn(text)
                if (!(startsATunnel && readsArmingPrefs)) return@forEach
                if (rel in exempt) return@forEach
                if (!text.contains("MultiHopPolicy")) {
                    offenders += rel
                }
            }

        assertEquals(
            "These files start a tunnel without consulting MultiHopPolicy. That is the " +
                "shape of Mobile-Client#336: a guard on some of N parallel paths, failing " +
                "silently because the unguarded path produces no error — just a single hop " +
                "while the UI keeps drawing entry → exit. Route the decision through " +
                "MultiHopPolicy, or add the file to `exempt` with a reason.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the enumeration test can actually fail`() {
        // A guard that cannot fail is decoration. This asserts the detector
        // fires on the exact shape it exists to catch.
        val sample = """
            class Widget(private val vpnManager: VpnManager, private val prefs: AppPreferences) {
                fun onTap() {
                    if (prefs.multiHopEnabled) { /* ...and then dials a single hop anyway */ }
                    vpnManager.connect("node-1")
                }
            }
        """.trimIndent()
        assertTrue(
            "the detector must match a file that reads the arming prefs and then dials",
            Regex("""\bconnectMultiHop\s*\(|vpnManager\.connect\s*\(|vpnManager\.quickConnect\s*\(""").containsMatchIn(sample) &&
                Regex("""multiHopEnabled|multiHopEntryNodeId|multiHopExitNodeId""").containsMatchIn(sample),
        )
        assertTrue(
            "and such a sample carries no MultiHopPolicy reference",
            !sample.contains("MultiHopPolicy"),
        )
    }
}
