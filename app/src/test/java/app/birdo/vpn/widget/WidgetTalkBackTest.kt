package app.birdo.vpn.widget

import app.birdo.vpn.R
import app.birdo.vpn.service.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What TalkBack says for the home-screen widget, in every state the widget
 * can show (#521 review, MR-1514 follow-up).
 *
 * The card's contentDescription REPLACES the Texts inside it, so the
 * description is everything a TalkBack user hears. Dropping the "BirdoVPN — "
 * prefix (owner decision 2026-10-28) took away the only words that named the
 * control, and Disconnecting, which has no tap action, was read as
 * "Disconnecting." alone. Each description must name the VPN, say the state,
 * and say what a double-tap does, or "Please wait" while disconnecting.
 *
 * [BirdoWidget.describe] is pure over a string resolver; the strings here
 * are the real ones, read from values/strings.xml by resource name.
 */
class WidgetTalkBackTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    /** name -> value, as Android would return it for these simple strings. */
    private val strings: Map<String, String> by lazy {
        val xml = File(repoRoot, "app/src/main/res/values/strings.xml").readText()
        Regex("""<string name="(\w+)"[^>]*>([^<]*)</string>""").findAll(xml).associate { m ->
            m.groupValues[1] to m.groupValues[2]
                .replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&").replace("\\u2026", "…")
        }
    }

    /** R.string id -> name, by reflection over the generated R class. */
    private val names: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private fun text(id: Int): String {
        val name = names[id] ?: error("no R.string for $id")
        return strings[name] ?: error("no <string name=\"$name\"> in values/strings.xml")
    }

    private val transitional = setOf(R.string.disconnecting)

    /** Every state the widget distinguishes, with the kill switch's two values. */
    private val states: List<Pair<String, BirdoWidget.WidgetModel>> by lazy {
        val vpn = listOf(
            VpnState.Disconnected,
            VpnState.Connecting,
            VpnState.StealthConnecting,
            VpnState.Connected,
            VpnState.Disconnecting,
            VpnState.Reconnecting(attempt = 2),
            VpnState.Reconnecting(waitingForNetwork = true),
            VpnState.KillSwitchActive,
            VpnState.Error("Could not reach the server"),
        )
        vpn.flatMap { state ->
            listOf(false, true).map { blocking ->
                "$state, kill switch blocking=$blocking" to BirdoWidget.widgetModel(state, blocking, "Frankfurt", showLocation = true)
            }
        }
    }

    @Test
    fun `every state is described with the VPN, the state and what a double-tap does`() {
        states.forEach { (label, model) ->
            val said = BirdoWidget.describe(model, model.serverLabel) { text(it) }
            assertTrue("$label: empty", said.isNotBlank())
            assertTrue("$label: \"$said\" does not name the VPN", said.startsWith("VPN "))
            assertFalse("$label: \"$said\" has the brand prefix", said.startsWith("BirdoVPN") || said.startsWith("Birdo VPN"))
            assertFalse("$label: \"$said\" names the brand", said.contains("BirdoVPN —") || said.contains("Birdo VPN —"))
            if (model.status in transitional) {
                assertTrue("$label: \"$said\" does not say to wait", said.contains("Please wait"))
            } else {
                assertTrue("$label: \"$said\" does not say what a double-tap does", Regex("""Double-tap to \w""").containsMatchIn(said))
            }
            val status = text(model.status).trimEnd('.', '…')
            assertTrue(
                "$label: \"$said\" does not say the state ($status)",
                said.contains(status, ignoreCase = true),
            )
            assertFalse("$label: \"$said\" has a double stop", said.contains(".."))
        }
    }

    @Test
    fun `the spoken hints say double-tap and the visible ones say tap`() {
        states.forEach { (label, model) ->
            val spoken = text(model.spokenHint)
            assertTrue("$label: spoken hint \"$spoken\"", spoken.startsWith("Double-tap to ") || spoken == "Please wait")
            model.hint?.let { assertFalse("$label: visible hint \"${text(it)}\" says double-tap", text(it).startsWith("Double-tap")) }
        }
    }

    @Test
    fun `disconnecting is no longer read as the status alone`() {
        val model = BirdoWidget.widgetModel(VpnState.Disconnecting, killSwitchActive = false)
        assertEquals("VPN Disconnecting. Please wait.", BirdoWidget.describe(model, null) { text(it) })
    }

    @Test
    fun `connected names the server when it is shown, the app otherwise`() {
        val shown = BirdoWidget.widgetModel(VpnState.Connected, false, "Frankfurt", showLocation = true)
        assertEquals("VPN protected via Frankfurt. Double-tap to disconnect.", BirdoWidget.describe(shown, shown.serverLabel) { text(it) })
        val hidden = BirdoWidget.widgetModel(VpnState.Connected, false, "Frankfurt", showLocation = false)
        assertEquals("VPN protected via BirdoVPN. Double-tap to disconnect.", BirdoWidget.describe(hidden, hidden.serverLabel) { text(it) })
    }
}
