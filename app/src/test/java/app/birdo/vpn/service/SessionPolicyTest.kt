package app.birdo.vpn.service

import app.birdo.vpn.service.ReconnectPolicy.Decision
import app.birdo.vpn.service.ReconnectPolicy.GiveUpReason
import app.birdo.vpn.service.ReconnectPolicy.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session supervisor's verdicts as a table (SessionPolicy.kt). Each row
 * here used to be a device session: A1-001 (a fixed five-attempt budget that
 * never resumed), A1-003 (retries for refusals and for dials that never
 * connected), A1-004 (a revoke retried into eviction ping-pong), A1-014 (no
 * headless start), A1-021 / A2-014 (one-tap surfaces deciding on the wrong
 * state).
 */
class SessionPolicyTest {

    private val t0 = 1_000_000L

    private fun established() = Session.userDial().connected()

    // ── FailureKind ──────────────────────────────────────────────────────

    @Test
    fun `HTTP refusals are terminal, transport trouble is not`() {
        assertEquals(FailureKind.SIGN_IN_REQUIRED, FailureKind.fromHttpStatus(401))
        assertEquals(FailureKind.PLAN_REQUIRED, FailureKind.fromHttpStatus(402))
        assertEquals(FailureKind.REFUSED, FailureKind.fromHttpStatus(403))
        assertEquals(FailureKind.REFUSED, FailureKind.fromHttpStatus(404))
        assertEquals(FailureKind.UPDATE_REQUIRED, FailureKind.fromHttpStatus(426))
        listOf(0, 408, 429, 500, 502, 503).forEach {
            assertEquals("status $it", FailureKind.TRANSIENT, FailureKind.fromHttpStatus(it))
        }
        assertTrue(FailureKind.REVOKED.terminal)
        assertFalse(FailureKind.REAPED.terminal)
    }

    // ── ReconnectPolicy ─────────────────────────────────────────────────

    @Test
    fun `a terminal failure stops at once and drops the intent`() {
        for (kind in FailureKind.entries.filter { it.terminal }) {
            val out = ReconnectPolicy.onFailure(established(), kind, online = true, nowMs = t0, jitter = 0.0)
            assertEquals(kind.name, GiveUpReason.TERMINAL, (out.decision as Decision.GiveUp).reason)
            assertFalse(kind.name, out.session.wantUp)
        }
    }

    @Test
    fun `a user dial that never connected is not retried, whatever the failure`() {
        val out = ReconnectPolicy.onFailure(Session.userDial(), FailureKind.TRANSIENT, online = true, nowMs = t0, jitter = 0.0)
        assertEquals(GiveUpReason.NEVER_CONNECTED, (out.decision as Decision.GiveUp).reason)
        assertFalse(out.session.wantUp)
    }

    @Test
    fun `a headless session retries even before it ever connected`() {
        val out = ReconnectPolicy.onFailure(Session.headless(), FailureKind.TRANSIENT, online = true, nowMs = t0, jitter = 0.0)
        assertEquals(Decision.Retry(attempt = 1, delayMs = 2_000L), out.decision)
    }

    @Test
    fun `offline spends no budget and waits for the network`() {
        var session = established()
        repeat(50) {
            val out = ReconnectPolicy.onFailure(session, FailureKind.DIED_AFTER_HANDSHAKE, online = false, nowMs = t0 + it, jitter = 0.0)
            assertEquals(Decision.WaitForNetwork, out.decision)
            session = out.session
        }
        assertEquals(0, session.failures)
    }

    @Test
    fun `backoff doubles from 2 s and caps at 5 min, and the budget ends in a give-up`() {
        var session = established()
        var now = t0
        val delays = mutableListOf<Long>()
        while (true) {
            val out = ReconnectPolicy.onFailure(session, FailureKind.DIED_AFTER_HANDSHAKE, online = true, nowMs = now, jitter = 0.0)
            session = out.session
            when (val d = out.decision) {
                is Decision.Retry -> { delays += d.delayMs; now += d.delayMs + 5_000 }
                is Decision.GiveUp -> {
                    assertEquals(GiveUpReason.BUDGET_EXHAUSTED, d.reason)
                    assertEquals(8, d.attempts)
                    break
                }
                Decision.WaitForNetwork -> error("online")
            }
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 128_000L, 256_000L), delays)
        assertEquals(300_000L, ReconnectPolicy.backoffMs(12, 0.0))
        // Nobody asked to stop: the cooldown may try again later.
        assertTrue(session.wantUp)
        assertTrue(session.trippedAt != null)
    }

    @Test
    fun `jitter stays within ten percent`() {
        assertEquals(1_800L, ReconnectPolicy.backoffMs(1, -1.0))
        assertEquals(2_200L, ReconnectPolicy.backoffMs(1, 1.0))
        assertEquals(66_000L, ReconnectPolicy.jittered(60_000L, 5.0))
    }

    @Test
    fun `a tunnel that never handshakes gets two re-dials, a reap gets one`() {
        fun attemptsBeforeGiveUp(kind: FailureKind): Int {
            var session = established()
            var n = 0
            while (true) {
                val out = ReconnectPolicy.onFailure(session, kind, online = true, nowMs = t0 + n, jitter = 0.0)
                session = out.session
                if (out.decision is Decision.GiveUp) return n
                n++
            }
        }
        assertEquals(2, attemptsBeforeGiveUp(FailureKind.NEVER_ESTABLISHED))
        assertEquals(1, attemptsBeforeGiveUp(FailureKind.REAPED))
        assertEquals(8, attemptsBeforeGiveUp(FailureKind.TRANSIENT))
    }

    @Test
    fun `a streak is judged by its most recent kind`() {
        var session = established()
        repeat(3) { session = ReconnectPolicy.onFailure(session, FailureKind.DIED_AFTER_HANDSHAKE, true, t0 + it, 0.0).session }
        val out = ReconnectPolicy.onFailure(session, FailureKind.NEVER_ESTABLISHED, online = true, nowMs = t0 + 10, jitter = 0.0)
        assertEquals(GiveUpReason.BUDGET_EXHAUSTED, (out.decision as Decision.GiveUp).reason)
    }

    @Test
    fun `failures ten minutes apart never add up, and a connect resets the streak`() {
        var session = established()
        var now = t0
        repeat(20) {
            val out = ReconnectPolicy.onFailure(session, FailureKind.DIED_AFTER_HANDSHAKE, true, now, 0.0)
            assertTrue(out.decision is Decision.Retry)
            session = out.session
            now += ReconnectPolicy.FAILURE_WINDOW_MS + 1
        }
        session = ReconnectPolicy.onFailure(session, FailureKind.DIED_AFTER_HANDSHAKE, true, now, 0.0).session
        assertEquals(0, session.connected().failures)
    }

    @Test
    fun `after the cooldown exactly one re-dial is allowed`() {
        val tripped = established().copy(failures = 9, lastFailureAt = t0, trippedAt = t0)
        val fresh = ReconnectPolicy.afterCooldown(tripped)
        assertNull(fresh.trippedAt)
        assertTrue(fresh.mayAutoRetry)
        assertTrue(fresh.lastChance)
        // That one re-dial fails: give up again at once (REVIEW-AND-011), not a
        // fresh eight-attempt streak that re-arms the block for ~8.5 min.
        val later = t0 + ReconnectPolicy.TRIP_COOLDOWN_MS
        val out = ReconnectPolicy.onFailure(fresh, FailureKind.DIED_AFTER_HANDSHAKE, true, later, 0.0)
        val giveUp = out.decision as Decision.GiveUp
        assertEquals(GiveUpReason.BUDGET_EXHAUSTED, giveUp.reason)
        assertEquals(1, giveUp.attempts)
        assertFalse(out.session.lastChance)
        // Offline it still waits for the network instead of spending the chance.
        assertEquals(
            Decision.WaitForNetwork,
            ReconnectPolicy.onFailure(fresh, FailureKind.TRANSIENT, false, later, 0.0).decision,
        )
        // A connect ends it.
        assertFalse(fresh.connected().lastChance)
    }

    @Test
    fun `a reap's rebuild is its whole budget, whatever the rebuild fails with`() {
        // WEB-HB: one quiet rebuild; a failed rebuild goes to Error instead of
        // retrying under TRANSIENT's eight attempts.
        var session = established()
        val reaped = ReconnectPolicy.onFailure(session, FailureKind.REAPED, true, t0, 0.0)
        assertTrue(reaped.decision is Decision.Retry)
        session = reaped.session
        val rebuildFailed = ReconnectPolicy.onFailure(session, FailureKind.TRANSIENT, true, t0 + 5_000, 0.0)
        assertEquals(GiveUpReason.BUDGET_EXHAUSTED, (rebuildFailed.decision as Decision.GiveUp).reason)
        // A reap more than ten minutes later is a new streak with its own rebuild.
        val later = ReconnectPolicy.onFailure(
            session.connected(), FailureKind.REAPED, true, t0 + ReconnectPolicy.FAILURE_WINDOW_MS + 1, 0.0,
        )
        assertTrue(later.decision is Decision.Retry)
    }

    @Test
    fun `an eviction is terminal`() {
        assertTrue(FailureKind.EVICTED.terminal)
        assertEquals(0, ReconnectPolicy.maxAttempts(FailureKind.EVICTED))
    }

    // ── HeartbeatPolicy (WEB-HB's client table) ──────────────────────────

    @Test
    fun `a heartbeat reason decides, and an absent or unknown one keeps today's inference`() {
        val flowing = 60_000L
        val slept = HeartbeatPolicy.REAP_WINDOW_MS
        fun v(valid: Boolean, reason: String?, gap: Long) = HeartbeatPolicy.verdict(valid, reason, gap)
        // Today's behaviour, for an older backend or a reason this build does not know.
        assertEquals(HeartbeatPolicy.Verdict.ALIVE, v(true, null, flowing))
        assertEquals(HeartbeatPolicy.Verdict.REVOKED, v(false, null, flowing))
        assertEquals(HeartbeatPolicy.Verdict.REAPED, v(false, null, slept))
        assertEquals(HeartbeatPolicy.Verdict.ALIVE, v(true, "quarantined", flowing))
        assertEquals(HeartbeatPolicy.Verdict.REVOKED, v(false, "quarantined", flowing))
        // The table.
        assertEquals(HeartbeatPolicy.Verdict.ALIVE, v(true, "ok", flowing))
        assertEquals(HeartbeatPolicy.Verdict.ALIVE, v(true, "server_offline", flowing))
        assertEquals(HeartbeatPolicy.Verdict.SERVER_GONE, v(false, "server_offline", flowing))
        assertEquals(HeartbeatPolicy.Verdict.REVOKED, v(false, "revoked", slept))
        assertEquals(HeartbeatPolicy.Verdict.EVICTED, v(false, "evicted", slept))
        // A reap is a reap even while beats were flowing (no inference needed).
        assertEquals(HeartbeatPolicy.Verdict.REAPED, v(false, "reaped", flowing))
        // not_found: the last good beat 5 min or more ago means reaped, else revoked.
        assertEquals(HeartbeatPolicy.Verdict.REAPED, v(false, "not_found", slept))
        assertEquals(HeartbeatPolicy.Verdict.REVOKED, v(false, "not_found", slept - 1))
    }

    // ── Free-plan allowance at check-in (birdo-web PR #590) ─────────────

    @Test
    fun `an allowance used past its grace ends the session as a plan decision`() {
        assertEquals(HeartbeatPolicy.Verdict.QUOTA_EXCEEDED, HeartbeatPolicy.verdict(false, "quota_exceeded", 0L))
        // Either field is enough: a reply that carries only the flag is not a revoke.
        assertEquals(HeartbeatPolicy.Verdict.QUOTA_EXCEEDED, HeartbeatPolicy.verdict(false, null, 0L, quotaExceeded = true))
        assertEquals(HeartbeatPolicy.Verdict.QUOTA_EXCEEDED, HeartbeatPolicy.verdict(false, "revoked", 0L, quotaExceeded = true))
        // Inside the grace window the session is alive.
        assertEquals(HeartbeatPolicy.Verdict.ALIVE, HeartbeatPolicy.verdict(true, null, 0L, quotaExceeded = true))
        // An unknown reason keeps today's fallback.
        assertEquals(HeartbeatPolicy.Verdict.ALIVE, HeartbeatPolicy.verdict(true, "some_new_reason", 0L))
        assertEquals(HeartbeatPolicy.Verdict.REVOKED, HeartbeatPolicy.verdict(false, "some_new_reason", 0L))
        assertTrue(FailureKind.QUOTA_EXCEEDED.terminal)
    }

    // ── REVIEW-AND2-001: the probe around a dead tunnel ──────────────────

    @Test
    fun `a dead tunnel's probe stops only for an end the server meant, and re-dials for everything else`() {
        val flowing = 60_000L
        val slept = HeartbeatPolicy.REAP_WINDOW_MS
        fun probe(
            valid: Boolean,
            reason: String?,
            serverOnline: Boolean = valid,
            quota: Boolean = false,
            gap: Long = flowing,
        ) = HeartbeatPolicy.forDeadTunnel(
            app.birdo.vpn.data.model.HeartbeatResponse(
                valid = valid,
                serverOnline = serverOnline,
                reason = reason,
                quotaExceeded = quota,
            ),
            gap,
        )
        // No answer (a transport failure, the 5 s cap, an HTTP error): re-dial, as before.
        assertEquals(HeartbeatPolicy.Verdict.ALIVE, HeartbeatPolicy.forDeadTunnel(null, flowing))
        // The ends the server meant, which the live path can no longer hear.
        assertEquals(HeartbeatPolicy.Verdict.EVICTED, probe(false, "evicted"))
        assertEquals(HeartbeatPolicy.Verdict.REVOKED, probe(false, "revoked", gap = slept))
        assertEquals(HeartbeatPolicy.Verdict.QUOTA_EXCEEDED, probe(false, "quota_exceeded"))
        assertEquals(HeartbeatPolicy.Verdict.QUOTA_EXCEEDED, probe(false, null, quota = true))
        // A reap, and a record the server lost, re-dial — unlike the live
        // path, where not_found while beats flowed reads as a revoke.
        assertEquals(HeartbeatPolicy.Verdict.REAPED, probe(false, "reaped"))
        assertEquals(HeartbeatPolicy.Verdict.REAPED, probe(false, "not_found"))
        // A drained node, or a live key on a node that is not online: another
        // server, because the connect gate refuses the same one.
        assertEquals(HeartbeatPolicy.Verdict.SERVER_GONE, probe(false, "server_offline"))
        assertEquals(HeartbeatPolicy.Verdict.SERVER_GONE, probe(true, "server_offline", serverOnline = false))
        assertEquals(HeartbeatPolicy.Verdict.SERVER_GONE, probe(true, null, serverOnline = false))
        // The key is live: the tunnel died for the network's reasons.
        assertEquals(HeartbeatPolicy.Verdict.ALIVE, probe(true, "ok"))
        assertEquals(HeartbeatPolicy.Verdict.ALIVE, probe(true, null))
        // A backend before WEB-HB ("Connection not found", no reason): the
        // live path's inference, so today's server stops the ping-pong too.
        assertEquals(HeartbeatPolicy.Verdict.REVOKED, probe(false, null, serverOnline = false))
        assertEquals(HeartbeatPolicy.Verdict.REAPED, probe(false, null, serverOnline = false, gap = slept))
        assertEquals(HeartbeatPolicy.Verdict.REVOKED, probe(false, "some_new_reason"))
    }

    @Test
    fun `the grace notice counts whole minutes from the server's clock`() {
        val now = java.time.Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()
        assertEquals(QuotaGrace(10), QuotaPolicy.grace(true, true, 600L, null, now))
        assertEquals(QuotaGrace(2), QuotaPolicy.grace(true, true, 61L, null, now))
        // Never "ends in 0 min".
        assertEquals(QuotaGrace(1), QuotaPolicy.grace(true, true, 0L, null, now))
        // The seconds win over the instant; the instant is the fallback.
        assertEquals(QuotaGrace(10), QuotaPolicy.grace(true, true, 600L, "2026-10-01T12:05:00Z", now))
        assertEquals(QuotaGrace(5), QuotaPolicy.grace(true, true, null, "2026-10-01T12:05:00Z", now))
        // Neither, or an unreadable instant: "soon".
        assertEquals(QuotaGrace(null), QuotaPolicy.grace(true, true, null, null, now))
        assertEquals(QuotaGrace(null), QuotaPolicy.grace(true, true, null, "not a date", now))
        // No notice outside the grace window.
        assertNull(QuotaPolicy.grace(true, false, 600L, null, now))
        assertNull(QuotaPolicy.grace(false, true, 600L, null, now))
    }

    // ── REVIEW-AND-006, A1-031, A1-033 ──────────────────────────────────

    @Test
    fun `a setup failure is never the exception's own text, and a bad config is a refusal`() {
        val (badConfig, badKind) = SessionCopy.forSetupFailure(IllegalArgumentException("Invalid endpoint: 203.0.113.7:51820"))
        assertEquals(SessionCopy.BAD_SERVER_CONFIG, badConfig)
        assertEquals(FailureKind.REFUSED, badKind)
        assertEquals(FailureKind.REFUSED, SessionCopy.forSetupFailure(IllegalStateException("No allowedIPs")).second)
        val (engine, engineKind) = SessionCopy.forSetupFailure(RuntimeException("JNI exploded at 0xdeadbeef"))
        assertEquals(SessionCopy.ENGINE_FAILED, engine)
        assertEquals(FailureKind.TRANSIENT, engineKind)
    }

    @Test
    fun `attestation runs only on a user's fresh dial with nothing blocked`() {
        assertTrue(AttestationPolicy.mayAttest(priorWasLive = false, blockActive = false, automatic = false))
        assertFalse("a switch or a re-dial", AttestationPolicy.mayAttest(true, false, false))
        assertFalse("behind the block Play cannot reach Google", AttestationPolicy.mayAttest(false, true, false))
        assertFalse("a fallback or a reapply", AttestationPolicy.mayAttest(false, false, true))
    }

    @Test
    fun `only a connected stealth session proves Stealth works here`() {
        assertTrue(StealthPreference.provenBy(VpnState.Connected, stealthActive = true))
        assertFalse(StealthPreference.provenBy(VpnState.Connected, stealthActive = false))
        assertFalse(StealthPreference.provenBy(VpnState.Error("x"), stealthActive = true))
        assertFalse(StealthPreference.provenBy(VpnState.Connecting, stealthActive = true))
    }

    @Test
    fun `a Disconnect wins over any failure that lands after it`() {
        val out = ReconnectPolicy.onFailure(Session.IDLE, FailureKind.TRANSIENT, online = true, nowMs = t0, jitter = 0.0)
        assertEquals(GiveUpReason.NOT_WANTED, (out.decision as Decision.GiveUp).reason)
    }

    // ── SessionCopy ──────────────────────────────────────────────────────

    @Test
    fun `the give-up says what stopped, and never claims an unblocked device under lockdown`() {
        val died = SessionCopy.giveUp(FailureKind.DIED_AFTER_HANDSHAKE, 8, lockdown = false)
        assertTrue(died.startsWith("BirdoVPN stopped reconnecting after 8 attempts"))
        assertTrue(died.contains("Traffic is no longer being blocked."))
        val never = SessionCopy.giveUp(FailureKind.NEVER_ESTABLISHED, 1, lockdown = true)
        assertTrue(never.contains("after 1 attempt:"))
        assertFalse(never.contains("no longer being blocked"))
        assertTrue(never.contains("still blocking traffic"))
        assertTrue(SessionCopy.giveUp(FailureKind.REAPED, 1, false).contains("the server ended this connection"))
    }

    @Test
    fun `API refusals get the canonical sentences`() {
        assertEquals(SessionCopy.SESSION_EXPIRED, SessionCopy.forApiError(401, "Unauthorized"))
        assertEquals(SessionCopy.UPDATE_REQUIRED, SessionCopy.forApiError(426, "{\"error\":\"update_required\"}"))
        assertEquals(SessionCopy.RATE_LIMITED, SessionCopy.forApiError(429, "slow down"))
        assertEquals("No active subscription", SessionCopy.forApiError(403, "No active subscription"))
    }

    // ── SystemStartPolicy (A1-014, A1-015) ───────────────────────────────

    private fun plan(
        kind: SystemStartKind,
        sessionShouldBeUp: Boolean = true,
        alwaysOn: Boolean = false,
        lockdown: Boolean = false,
        killSwitch: Boolean = true,
        signedIn: Boolean = true,
        consent: Boolean = true,
        permission: Boolean = true,
    ) = SystemStartPolicy.plan(kind, sessionShouldBeUp, alwaysOn, lockdown, killSwitch, signedIn, consent, permission)

    @Test
    fun `Always-on at boot blocks first when asked to, then connects headlessly`() {
        val p = plan(SystemStartKind.ALWAYS_ON, sessionShouldBeUp = false, lockdown = true, killSwitch = false)
        assertTrue(p.armBlock)
        assertTrue(p.connect)
        assertNull(p.actionNeeded)
    }

    @Test
    fun `a sticky restart re-arms the block only for a session somebody wants`() {
        assertEquals(SystemStartPolicy.Plan(armBlock = true, connect = true, actionNeeded = null), plan(SystemStartKind.STICKY_RESTART))
        // Nothing was wanted (a user dial that never connected left the
        // service foreground): no block nobody asked for (REVIEW-AND-004).
        // Changed from phase A, which armed here.
        assertTrue(plan(SystemStartKind.STICKY_RESTART, sessionShouldBeUp = false).idle)
        assertTrue(plan(SystemStartKind.STICKY_RESTART, sessionShouldBeUp = false, killSwitch = false).idle)
    }

    @Test
    fun `a process that finds the session dead resumes it, block first`() {
        assertEquals(
            SystemStartPolicy.Plan(armBlock = true, connect = true, actionNeeded = null),
            plan(SystemStartKind.PROCESS_RESTART),
        )
        assertEquals(
            SystemStartPolicy.Plan(armBlock = false, connect = true, actionNeeded = null),
            plan(SystemStartKind.PROCESS_RESTART, killSwitch = false),
        )
        assertEquals(FailureKind.SIGN_IN_REQUIRED, plan(SystemStartKind.PROCESS_RESTART, signedIn = false).actionNeeded)
        // BirdoApp asks only for a session the user wanted and no service holds.
        assertTrue(SystemStartPolicy.resumeOnProcessStart(sessionShouldBeUp = true, serviceRunning = false))
        assertFalse(SystemStartPolicy.resumeOnProcessStart(sessionShouldBeUp = true, serviceRunning = true))
        assertFalse(SystemStartPolicy.resumeOnProcessStart(sessionShouldBeUp = false, serviceRunning = false))
    }

    @Test
    fun `an app update restores only a session the user wanted`() {
        assertTrue(plan(SystemStartKind.PACKAGE_REPLACED).connect)
        assertTrue(plan(SystemStartKind.PACKAGE_REPLACED, sessionShouldBeUp = false).idle)
    }

    @Test
    fun `expired credentials or missing consent never fail open silently`() {
        val signedOut = plan(SystemStartKind.ALWAYS_ON, signedIn = false)
        assertTrue("the block holds", signedOut.armBlock)
        assertFalse(signedOut.connect)
        assertEquals(FailureKind.SIGN_IN_REQUIRED, signedOut.actionNeeded)

        val noConsent = plan(SystemStartKind.ALWAYS_ON, consent = false)
        assertFalse("no request before the consent screen (D-12)", noConsent.connect)
        assertEquals(FailureKind.SETUP_REQUIRED, noConsent.actionNeeded)

        val noPermission = plan(SystemStartKind.PACKAGE_REPLACED, permission = false)
        assertFalse("establish() would return null", noPermission.armBlock)
        assertEquals(FailureKind.VPN_PERMISSION_REQUIRED, noPermission.actionNeeded)
    }

    // ── QuickToggle (A1-021, A2-014) ─────────────────────────────────────

    @Test
    fun `a one-tap surface cancels, disconnects or releases the block, and hands off when it cannot connect`() {
        fun decide(
            state: VpnState,
            blocking: Boolean = false,
            signedIn: Boolean = true,
            permission: Boolean = true,
            consent: Boolean = true,
            joinsResume: Boolean = false,
        ) = QuickToggle.decide(state, blocking, signedIn, permission, consent, joinsResume)
        assertEquals(QuickToggle.Action.DISCONNECT, decide(VpnState.Connected))
        assertEquals(QuickToggle.Action.DISCONNECT, decide(VpnState.Connecting))
        assertEquals(QuickToggle.Action.DISCONNECT, decide(VpnState.Reconnecting(2)))
        assertEquals(QuickToggle.Action.DISCONNECT, decide(VpnState.Error("x"), blocking = true))
        assertEquals(QuickToggle.Action.NONE, decide(VpnState.Disconnecting))
        assertEquals(QuickToggle.Action.OPEN_APP, decide(VpnState.Disconnected, signedIn = false))
        assertEquals(QuickToggle.Action.OPEN_APP, decide(VpnState.Disconnected, permission = false))
        assertEquals(QuickToggle.Action.CONNECT, decide(VpnState.Disconnected))
        assertEquals(QuickToggle.Action.CONNECT, decide(VpnState.Error("x")))
        // REVIEW-AND-010: no /vpn/connect before the current consent, but
        // stopping is always allowed.
        assertEquals(QuickToggle.Action.OPEN_APP, decide(VpnState.Disconnected, consent = false))
        assertEquals(QuickToggle.Action.DISCONNECT, decide(VpnState.Connected, consent = false))
        // REVIEW-AND2-004: the tap that started the process joins the resume
        // that start began, whatever the resume shows by the time it lands.
        assertEquals(QuickToggle.Action.NONE, decide(VpnState.Connecting, joinsResume = true))
        assertEquals(QuickToggle.Action.NONE, decide(VpnState.Reconnecting(1), blocking = true, joinsResume = true))
    }

    @Test
    fun `only the resume's own dial, still connecting and a few seconds old, claims the tap`() {
        val window = QuickToggle.RESUME_TAP_WINDOW_MS
        fun joins(age: Long = 800L, sameDial: Boolean = true, state: VpnState = VpnState.Connecting) =
            QuickToggle.joinsResume(age, sameDial, state)
        assertTrue(joins())
        assertTrue(joins(state = VpnState.Reconnecting(1)))
        assertTrue(joins(age = window))
        // Later, the tap is the user's own decision on what the tile shows.
        assertFalse(joins(age = window + 1))
        // A newer dial or a Disconnect took over.
        assertFalse(joins(sameDial = false))
        // Up, or failed: a tap acts on that.
        assertFalse(joins(state = VpnState.Connected))
        assertFalse(joins(state = VpnState.Error("x")))
        assertFalse(joins(state = VpnState.Disconnected))
    }

    @Test
    fun `one entitlement rule for every dial`() {
        assertEquals(true, MultiHopPolicy.entitledByPlan("SOVEREIGN"))
        assertEquals(true, MultiHopPolicy.entitledByPlan("sovereign"))
        assertEquals(false, MultiHopPolicy.entitledByPlan("OPERATIVE"))
        assertEquals(null, MultiHopPolicy.entitledByPlan(null))
    }

    /** REVIEW-AND2-012: a kept session after a settings change is not a failed switch. */
    @Test
    fun `a kept session says which change did not happen`() {
        assertEquals(SessionCopy.SWITCH_KEPT_PREVIOUS, SessionCopy.keptSession(null, settingsChange = false))
        assertEquals(SessionCopy.SETTINGS_KEPT_PREVIOUS, SessionCopy.keptSession(null, settingsChange = true))
        assertEquals("Server busy. ${SessionCopy.STILL_ON_PREVIOUS}", SessionCopy.keptSession("Server busy.", settingsChange = false))
        assertEquals("Server busy. ${SessionCopy.CONNECTION_UNCHANGED}", SessionCopy.keptSession("Server busy.", settingsChange = true))
        assertFalse(SessionCopy.keptSession(null, settingsChange = true).contains("switch"))
        assertFalse(SessionCopy.keptSession("x", settingsChange = true).contains("location"))
    }

    @Test
    fun `a give-up that speaks for the traffic is never contradicted`() {
        val giveUp = SessionCopy.giveUp(FailureKind.DIED_AFTER_HANDSHAKE, 8, lockdown = false)
        assertTrue(SessionCopy.speaksForTraffic(giveUp))
        assertTrue(SessionCopy.speaksForTraffic(SessionCopy.giveUp(FailureKind.TRANSIENT, 1, lockdown = true)))
        assertFalse(SessionCopy.speaksForTraffic(SessionCopy.NO_TUNNEL))
    }

    @Test
    fun `a kill switch that could not be armed never denies Android's own block (REVIEW-AND-005)`() {
        val lockdown = SessionCopy.killSwitchNotArmed(lockdown = true)
        // Under "Block connections without VPN" the OS still blocks, so
        // "traffic is NOT protected" would be false.
        assertFalse(lockdown.contains("NOT protected"))
        assertTrue(lockdown.contains(SessionCopy.LOCKDOWN_STILL_BLOCKING))
        assertEquals(SessionCopy.KILL_SWITCH_NOT_ARMED, SessionCopy.killSwitchNotArmed(lockdown = false))
        listOf(lockdown, SessionCopy.KILL_SWITCH_NOT_ARMED).forEach {
            assertTrue(SessionCopy.isKillSwitchNotArmed(it))
            // Nothing may append "the kill switch is blocking traffic" to it.
            assertTrue(SessionCopy.speaksForTraffic(it))
        }
        assertFalse(SessionCopy.isKillSwitchNotArmed(SessionCopy.NO_TUNNEL))
    }

    @Test
    fun `a one-tap connect never guesses at a Multi-Hop entitlement`() {
        val armed = MultiHopPolicy.NewConnection.MultiHop("de-1", "nl-1")
        assertEquals(QuickToggle.ConnectPlan.MultiHop("de-1", "nl-1"), QuickToggle.connectPlan(armed, "SOVEREIGN"))
        assertEquals(QuickToggle.ConnectPlan.Preferred(multiHopEntitled = false), QuickToggle.connectPlan(armed, "OPERATIVE"))
        assertTrue(QuickToggle.connectPlan(armed, null) is QuickToggle.ConnectPlan.OpenApp)
        assertTrue(
            QuickToggle.connectPlan(MultiHopPolicy.NewConnection.RefuseIncompletePair, "SOVEREIGN") is QuickToggle.ConnectPlan.OpenApp,
        )
        assertEquals(
            QuickToggle.ConnectPlan.Preferred(multiHopEntitled = false),
            QuickToggle.connectPlan(MultiHopPolicy.NewConnection.SingleHop, null),
        )
    }
}
