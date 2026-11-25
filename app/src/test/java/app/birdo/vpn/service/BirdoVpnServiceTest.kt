package app.birdo.vpn.service

import app.birdo.vpn.data.model.ConnectResponse
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/**
 * Unit tests for [BirdoVpnService] Companion-object state management,
 * the [VpnState] sealed class, and reactive [StateFlow] emissions.
 *
 * BirdoVpnService is an Android Service so instance methods (startTunnel,
 * buildVpnInterface, etc.) require an Android runtime. These tests focus on
 * the companion-level state machine and state flow contract, which are
 * shared across the entire process.
 *
 * Covers:
 *  - VpnState sealed-class behaviour (identity, equality, Error message)
 *  - Companion initial state (Disconnected, null server, 0 bytes, etc.)
 *  - updateState() drives both currentState and stateFlow
 *  - setConfig() stores the ConnectResponse
 *  - stateFlow emits correct state transitions (Turbine)
 *  - Intent action and extra constants
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BirdoVpnServiceTest {

    /** Reflection handle for the private `updateState` method. */
    private lateinit var updateStateMethod: Method

    @Before
    fun setup() {
        // Expose the private updateState method so we can drive state transitions
        updateStateMethod = BirdoVpnService.Companion::class.java
            .getDeclaredMethod("updateState", VpnState::class.java)
        updateStateMethod.isAccessible = true

        // Reset companion state to known defaults
        resetCompanionState()
    }

    @After
    fun tearDown() {
        resetCompanionState()
    }

    /** Reset companion to default values using reflection. */
    private fun resetCompanionState() {
        updateStateMethod.invoke(BirdoVpnService.Companion, VpnState.Disconnected)

        // Reset MutableStateFlow-backed fields via reflection
        resetStateFlow("_connectedServerFlow", null)
        resetStateFlow("_connectedSinceFlow", 0L)
        resetStateFlow("_killSwitchActiveFlow", false)
        resetStateFlow("_publicIpFlow", null)
        resetStateFlow("_rxBytesFlow", 0L)
        resetStateFlow("_txBytesFlow", 0L)
        setCompanionField("activeConfig", null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun resetStateFlow(name: String, value: Any?) {
        try {
            val field = BirdoVpnService.Companion::class.java.getDeclaredField(name)
            field.isAccessible = true
            val flow = field.get(BirdoVpnService.Companion) as kotlinx.coroutines.flow.MutableStateFlow<Any?>
            flow.value = value
        } catch (_: NoSuchFieldException) {
            // Field absent — no-op
        }
    }

    private fun setCompanionField(name: String, value: Any?) {
        try {
            val field = BirdoVpnService.Companion::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(BirdoVpnService.Companion, value)
        } catch (_: NoSuchFieldException) {
            // Some fields are Kotlin properties — try the backing field via the class itself
            try {
                val field = BirdoVpnService::class.java.getDeclaredField(name)
                field.isAccessible = true
                field.set(null, value) // static field
            } catch (_: NoSuchFieldException) {
                // Field no longer exists after refactor — ignore
            }
        }
    }

    // ── VpnState sealed class ────────────────────────────────────

    @Test
    fun `VpnState Disconnected is a singleton`() {
        assertSame(VpnState.Disconnected, VpnState.Disconnected)
    }

    @Test
    fun `VpnState Connecting is a singleton`() {
        assertSame(VpnState.Connecting, VpnState.Connecting)
    }

    @Test
    fun `VpnState Connected is a singleton`() {
        assertSame(VpnState.Connected, VpnState.Connected)
    }

    @Test
    fun `VpnState Disconnecting is a singleton`() {
        assertSame(VpnState.Disconnecting, VpnState.Disconnecting)
    }

    @Test
    fun `VpnState Error carries message`() {
        val error = VpnState.Error("Tunnel crashed")
        assertEquals("Tunnel crashed", error.message)
    }

    @Test
    fun `VpnState Error equality by message`() {
        val a = VpnState.Error("fail")
        val b = VpnState.Error("fail")
        assertEquals(a, b)
    }

    @Test
    fun `VpnState Error inequality when messages differ`() {
        val a = VpnState.Error("fail 1")
        val b = VpnState.Error("fail 2")
        assertNotEquals(a, b)
    }

    // ── MR-2595: only the probe's no-answer verdict marks an Error ────────
    //
    // The retry on another relay is spent only by a TRUE no-answer (Desktop
    // #470, review L2): never by the adapter, the kill switch, a rate limit,
    // the Stealth helper or Quantum Protection. The service is where that is
    // decided, and its verdicts need an Android runtime, so the two places
    // that mark an Error are pinned in its source, and nothing else may.

    @Test
    fun `MR-2595 an Error is not unanswered unless the service says so`() {
        assertFalse(VpnState.Error("fail").unanswered)
        assertFalse(VpnState.Error(SessionCopy.NO_TUNNEL, FailureKind.NEVER_ESTABLISHED).unanswered)
        assertNotEquals(
            VpnState.Error(SessionCopy.NO_TUNNEL, FailureKind.NEVER_ESTABLISHED),
            VpnState.Error(SessionCopy.NO_TUNNEL, FailureKind.NEVER_ESTABLISHED, unanswered = true),
        )
    }

    private val serviceSource: String by lazy {
        var dir = java.io.File("").absoluteFile
        while (!java.io.File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${java.io.File("").absolutePath}")
        }
        java.io.File(dir, "app/src/main/java/app/birdo/vpn/service/BirdoVpnService.kt").readText()
    }

    private fun section(start: String, end: String): String {
        assertTrue("BirdoVpnService.kt has no `$start`", serviceSource.contains(start))
        return serviceSource.substringAfter(start).substringBefore(end)
    }

    @Test
    fun `MR-2595 the probe's no-answer on the last transport is the Error marked unanswered`() {
        val verdict = section("private fun onProbeVerdict(", "    // ── A1-034")
        // Over Stealth there is no transport left: the Error says the server never answered.
        val stealth = verdict.substringAfter("no handshake over stealth").substringBefore("TransportProbe.Result.ABORTED")
        assertTrue(
            "the probe's BLOCKED verdict over Stealth must publish its Error with `unanswered = true`",
            stealth.contains("unanswered = true"),
        )
        // Direct, the verdict is remembered for this setup...
        val direct = verdict.substringAfter("TransportProbe.Result.BLOCKED").substringBefore("no handshake over stealth")
        assertTrue("the direct BLOCKED verdict must be remembered for its setup", direct.contains("unansweredGen = gen"))
        // ...so the timeout that ends it, when VpnManager makes no fallback, says the same.
        val timeout = section("private val connectTimeoutRunnable = Runnable {", "\n    }\n")
        assertTrue(
            "the connect timeout of a setup the probe found unanswered must mark its Error",
            timeout.contains("unanswered = unansweredGen == gen"),
        )
    }

    @Test
    fun `MR-2595 nothing else in the service marks an Error unanswered`() {
        val marks = Regex("""\bunanswered\s*=(?!=)""").findAll(serviceSource).count()
        assertEquals(
            "BirdoVpnService.kt marks an Error unanswered in $marks places. Only the probe's two no-answer " +
                "verdicts may: a failure of this device's own must never spend the retry on another relay",
            2,
            marks,
        )
    }

    @Test
    fun `VpnState subtypes are distinguishable via when`() {
        val states = listOf(
            VpnState.Disconnected,
            VpnState.Connecting,
            VpnState.Connected,
            VpnState.Disconnecting,
            VpnState.Error("test"),
        )

        for (state in states) {
            val label = when (state) {
                is VpnState.Disconnected  -> "disconnected"
                is VpnState.Connecting    -> "connecting"
                is VpnState.Connected     -> "connected"
                is VpnState.Disconnecting -> "disconnecting"
                is VpnState.Error         -> "error:${state.message}"
                else                      -> "other:${state::class.simpleName}"
            }
            assertNotNull(label)
        }
    }

    // ── Companion initial state ──────────────────────────────────

    @Test
    fun `initial state is Disconnected`() {
        assertEquals(VpnState.Disconnected, BirdoVpnService.currentState)
    }

    @Test
    fun `initial stateFlow value is Disconnected`() {
        assertEquals(VpnState.Disconnected, BirdoVpnService.stateFlow.value)
    }

    @Test
    fun `initial connectedServer is null`() {
        assertNull(BirdoVpnService.connectedServer)
    }

    @Test
    fun `initial connectedSince is zero`() {
        assertEquals(0L, BirdoVpnService.connectedSince)
    }

    @Test
    fun `initial killSwitchActive is false`() {
        assertFalse(BirdoVpnService.killSwitchActive)
    }

    @Test
    fun `initial publicIp is null`() {
        assertNull(BirdoVpnService.publicIp)
    }

    @Test
    fun `initial rxBytes and txBytes are zero`() {
        assertEquals(0L, BirdoVpnService.rxBytes)
        assertEquals(0L, BirdoVpnService.txBytes)
    }

    // ── updateState drives currentState and stateFlow ────────────

    @Test
    fun `updateState sets currentState`() {
        updateStateMethod.invoke(BirdoVpnService.Companion, VpnState.Connecting)
        assertEquals(VpnState.Connecting, BirdoVpnService.currentState)
    }

    @Test
    fun `updateState sets stateFlow value`() {
        updateStateMethod.invoke(BirdoVpnService.Companion, VpnState.Connected)
        assertEquals(VpnState.Connected, BirdoVpnService.stateFlow.value)
    }

    @Test
    fun `updateState keeps currentState and stateFlow in sync`() {
        val transitions = listOf(
            VpnState.Connecting,
            VpnState.Connected,
            VpnState.Disconnecting,
            VpnState.Disconnected,
        )
        for (state in transitions) {
            updateStateMethod.invoke(BirdoVpnService.Companion, state)
            assertSame(BirdoVpnService.currentState, BirdoVpnService.stateFlow.value)
        }
    }

    @Test
    fun `updateState with Error sets both fields`() {
        val err = VpnState.Error("socket timeout")
        updateStateMethod.invoke(BirdoVpnService.Companion, err)

        assertEquals(err, BirdoVpnService.currentState)
        assertEquals(err, BirdoVpnService.stateFlow.value)
        assertEquals(
            "socket timeout",
            (BirdoVpnService.currentState as VpnState.Error).message
        )
    }

    // ── stateFlow emissions (Turbine) ────────────────────────────

    @Test
    fun `stateFlow emits full connect lifecycle`() = runTest {
        BirdoVpnService.stateFlow.test {
            assertEquals(VpnState.Disconnected, awaitItem())

            updateStateMethod.invoke(BirdoVpnService.Companion, VpnState.Connecting)
            assertEquals(VpnState.Connecting, awaitItem())

            updateStateMethod.invoke(BirdoVpnService.Companion, VpnState.Connected)
            assertEquals(VpnState.Connected, awaitItem())

            updateStateMethod.invoke(BirdoVpnService.Companion, VpnState.Disconnecting)
            assertEquals(VpnState.Disconnecting, awaitItem())

            updateStateMethod.invoke(BirdoVpnService.Companion, VpnState.Disconnected)
            assertEquals(VpnState.Disconnected, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stateFlow emits error state`() = runTest {
        BirdoVpnService.stateFlow.test {
            assertEquals(VpnState.Disconnected, awaitItem()) // initial

            updateStateMethod.invoke(
                BirdoVpnService.Companion,
                VpnState.Error("WireGuard engine unavailable")
            )
            val emitted = awaitItem()
            assertTrue(emitted is VpnState.Error)
            assertEquals(
                "WireGuard engine unavailable",
                (emitted as VpnState.Error).message
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stateFlow does not emit when same state is set twice`() = runTest {
        BirdoVpnService.stateFlow.test {
            assertEquals(VpnState.Disconnected, awaitItem())

            // Set same state again — MutableStateFlow deduplicates
            updateStateMethod.invoke(BirdoVpnService.Companion, VpnState.Disconnected)
            expectNoEvents()

            updateStateMethod.invoke(BirdoVpnService.Companion, VpnState.Connecting)
            assertEquals(VpnState.Connecting, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── setConfig ────────────────────────────────────────────────

    @Test
    fun `setConfig stores config for later use`() {
        val config = ConnectResponse(
            privateKey = "base64key==",
            serverPublicKey = "serverpub64==",
            endpoint = "1.2.3.4:51820",
            assignedIp = "10.8.0.5",
            dns = listOf("1.1.1.1"),
            allowedIps = listOf("0.0.0.0/0"),
            mtu = 1420,
            persistentKeepalive = 25,
            presharedKey = null,
            serverNode = null,
            keyId = "key-123",
        )
        BirdoVpnService.setConfig(config)

        // Read activeConfig via reflection (it's private)
        val configField = BirdoVpnService::class.java.getDeclaredField("activeConfig")
        configField.isAccessible = true
        val stored = configField.get(null) as ConnectResponse?

        assertNotNull(stored)
        assertEquals("base64key==", stored!!.privateKey)
        assertEquals("1.2.3.4:51820", stored.endpoint)
        assertEquals("10.8.0.5", stored.assignedIp)
    }

    @Test
    fun `setConfig replaces previous config`() {
        val first = ConnectResponse(
            privateKey = "key1", serverPublicKey = "pub1",
            endpoint = "1.1.1.1:51820", assignedIp = "10.8.0.1",
            dns = null, allowedIps = null, mtu = null,
            persistentKeepalive = null, presharedKey = null,
            serverNode = null, keyId = null,
        )
        val second = ConnectResponse(
            privateKey = "key2", serverPublicKey = "pub2",
            endpoint = "2.2.2.2:51820", assignedIp = "10.8.0.2",
            dns = null, allowedIps = null, mtu = null,
            persistentKeepalive = null, presharedKey = null,
            serverNode = null, keyId = null,
        )
        BirdoVpnService.setConfig(first)
        BirdoVpnService.setConfig(second)

        val configField = BirdoVpnService::class.java.getDeclaredField("activeConfig")
        configField.isAccessible = true
        val stored = configField.get(null) as ConnectResponse?

        assertEquals("key2", stored!!.privateKey)
        assertEquals("2.2.2.2:51820", stored.endpoint)
    }

    // ── Intent constants ─────────────────────────────────────────

    @Test
    fun `ACTION constants are distinct strings`() {
        val actions = setOf(
            BirdoVpnService.ACTION_START,
            BirdoVpnService.ACTION_STOP,
            BirdoVpnService.ACTION_KILL_SWITCH_BLOCK,
        )
        assertEquals(3, actions.size)
    }

    @Test
    fun `ACTION_START contains birdo identifier`() {
        assertTrue(BirdoVpnService.ACTION_START.contains("birdo"))
    }

    @Test
    fun `EXTRA constants are distinct strings`() {
        val extras = setOf(
            BirdoVpnService.EXTRA_KILL_SWITCH,
            BirdoVpnService.EXTRA_SPLIT_TUNNEL_ENABLED,
            BirdoVpnService.EXTRA_SPLIT_TUNNEL_APPS,
        )
        assertEquals(3, extras.size)
    }
}
