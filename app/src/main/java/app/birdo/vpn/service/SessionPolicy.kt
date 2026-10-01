package app.birdo.vpn.service

/**
 * The session supervisor's decisions, as pure functions.
 *
 * WHY THIS FILE EXISTS. Android's recovery behaviour used to be a handful of
 * flags scattered over VpnManager — a fixed five-attempt loop that gave up
 * for good after ~62 s (A1-001), fired for every Error including a 426 or a
 * plan refusal and for sessions that had never connected (A1-003), retried a
 * server-side revoke into eviction ping-pong (A1-004), and never resumed when
 * the network came back. Each flag was right in isolation; together they had
 * no model anyone could test. iOS solved the same problem with a small pure
 * decision type (TunnelCircuitBreaker) that its host and extension both
 * consult. This is the Android twin: every rule about WHETHER and WHEN to
 * re-dial lives here, with no Android, no coroutines and no I/O, so each row
 * of the table below is a unit test rather than a device session.
 *
 * | Failure                                   | Handling                              |
 * |-------------------------------------------|---------------------------------------|
 * | terminal ([FailureKind.terminal])         | stop now, typed user-facing error     |
 * | a user dial that never reached Connected  | stop, show the error (no auto-retry)  |
 * | offline                                   | wait for the network, then retry now  |
 * | retryable, online                         | capped exponential backoff + jitter   |
 * | retryable budget spent                    | stop in a clear failed state; resume  |
 * |                                           | once after [ReconnectPolicy.TRIP_COOLDOWN_MS] |
 *
 * VpnManager owns the side effects (jobs, intents, the block); this file
 * owns the verdicts.
 */

/**
 * WHY a connection attempt or a live session stopped carrying traffic, as far
 * as recovery is concerned. Carried on [VpnState.Error] so every surface —
 * Home, notification, tile, widget, the supervisor — reads the same verdict
 * instead of re-deriving it from message text.
 */
enum class FailureKind(val terminal: Boolean) {
    /** Offline, timeout, 5xx, rate limit, or an engine hiccup: try again later. */
    TRANSIENT(false),

    /**
     * The tunnel came up and never completed a handshake (probe verdict,
     * connect watchdog, or the stealth transport also failing). A re-dial to
     * the same place rarely helps, so the budget is small.
     */
    NEVER_ESTABLISHED(false),

    /** A handshake happened and later went stale: NAT rebind, node restart, roam. */
    DIED_AFTER_HANDSHAKE(false),

    /**
     * The heartbeat said `valid = false` after the device went longer than the
     * backend's reap window without one (see VpnManager.REAP_WINDOW_MS). The
     * server reaped an idle peer; nothing ended the session on purpose. One
     * automatic re-dial.
     */
    REAPED(false),

    /** `valid = false` while heartbeats were flowing: revoked, or another device took the slot. */
    REVOKED(true),

    /**
     * The heartbeat said "evicted" (WEB-HB): another device of this account
     * connected and took the slot. Never re-dialled on its own — that would
     * evict the other device in turn, the ping-pong of A1-004.
     */
    EVICTED(true),

    /** 401 after the refresh token was rejected. */
    SIGN_IN_REQUIRED(true),

    /** 426: below the backend's support floor. */
    UPDATE_REQUIRED(true),

    /** 402: the plan does not cover this. */
    PLAN_REQUIRED(true),

    /** Quantum Protection was requested and could not be completed. Fail-closed by design. */
    QUANTUM_FAILED(true),

    /** Stealth Mode was requested and could not start. Fail-closed by design. */
    STEALTH_FAILED(true),

    /** Any other refusal: another 4xx, a local engine or integrity refusal, an incomplete Multi-Hop pair. */
    REFUSED(true),

    /** establish() refused, or VpnService.prepare() says consent is missing. */
    VPN_PERMISSION_REQUIRED(true),

    /** The privacy consent screen has not been accepted, so the app must not contact the backend. */
    SETUP_REQUIRED(true),

    /** onRevoke(): another VPN app took over, or the VPN permission was removed. */
    VPN_TAKEN_OVER(true),
    ;

    companion object {
        /**
         * Classify an API failure by HTTP status. 0 is a transport failure
         * (no response at all) and 503 is what BirdoRepository returns for a
         * refresh that failed transiently — both are worth another try. A 4xx
         * other than 408/429 is the server saying no, and asking again will
         * get the same answer.
         */
        fun fromHttpStatus(code: Int): FailureKind = when (code) {
            401 -> SIGN_IN_REQUIRED
            402 -> PLAN_REQUIRED
            426 -> UPDATE_REQUIRED
            408, 429 -> TRANSIENT
            in 400..499 -> REFUSED
            else -> TRANSIENT
        }
    }
}

internal object ReconnectPolicy {

    /**
     * Failures further apart than this do not add up to a streak, so a node
     * that drops once an hour can never accumulate its way to a give-up
     * (iOS TunnelCircuitBreaker.failureWindow).
     */
    const val FAILURE_WINDOW_MS = 10 * 60_000L

    /**
     * How long a spent budget holds before the supervisor tries once more on
     * its own. The last-resort reset: an unattended phone (Always-on, screen
     * off) returns to normal without anyone opening the app, so a wrong trip
     * costs a delay, never a VPN that stays off (iOS tripCooldown).
     */
    const val TRIP_COOLDOWN_MS = 15 * 60_000L

    const val INITIAL_DELAY_MS = 2_000L

    /** Backoff cap. Five minutes keeps a long outage from hammering the API. */
    const val MAX_DELAY_MS = 5 * 60_000L

    /** ±10 %, so a fleet that lost the same node does not re-dial in lockstep. */
    const val JITTER_FRACTION = 0.10

    /**
     * Automatic re-dials allowed for a streak whose latest failure is [kind].
     *
     * A drop after a real handshake or a transport error is what a NAT rebind
     * or a node restart looks like, so it gets the largest budget: with the
     * backoff below, eight attempts span roughly eight and a half minutes.
     * A tunnel that never handshook will not start handshaking because we
     * asked again, so two. A reap gets exactly one fresh /vpn/connect.
     * Offline failures are not counted at all (see [onFailure]).
     */
    fun maxAttempts(kind: FailureKind): Int = when (kind) {
        FailureKind.TRANSIENT, FailureKind.DIED_AFTER_HANDSHAKE -> 8
        FailureKind.NEVER_ESTABLISHED -> 2
        FailureKind.REAPED -> 1
        else -> 0
    }

    /** Who asked for the session, which decides whether it may heal itself. */
    enum class Owner {
        NONE,
        /** A tap in the app, the tile, the widget or a notification action. */
        USER,
        /** A system start: Always-on, a sticky restart, or an app update. */
        HEADLESS,
    }

    data class Session(
        /** Somebody wants the tunnel up. Cleared by a Disconnect, a terminal failure or a user dial that failed. */
        val wantUp: Boolean = false,
        val owner: Owner = Owner.NONE,
        /** This session reached Connected at least once. */
        val established: Boolean = false,
        /** Consecutive counted failures in the current streak. */
        val failures: Int = 0,
        val lastFailureAt: Long = 0L,
        /** When the budget ran out, or null while it has not. */
        val trippedAt: Long? = null,
        /**
         * This streak began with a reap: its one rebuild is the whole budget,
         * so a rebuild that fails gives up instead of retrying under the
         * failure's own (larger) budget (WEB-HB's client table).
         */
        val reapStreak: Boolean = false,
        /**
         * The one re-dial the cooldown allows (REVIEW-AND-011): a failure now
         * gives up again at once instead of starting a fresh eight-attempt
         * streak, which re-armed the block for ~8.5 min every 15 min.
         */
        val lastChance: Boolean = false,
    ) {
        /**
         * A user dial that never connected does NOT heal itself: the user is
         * looking at the error, and a retry loop would overwrite it (A1-003).
         * A session that was up, or one the system owns, does.
         */
        val mayAutoRetry: Boolean
            get() = wantUp && (established || owner == Owner.HEADLESS)

        fun connected(): Session = copy(
            established = true,
            failures = 0,
            lastFailureAt = 0L,
            trippedAt = null,
            lastChance = false,
            reapStreak = false,
        )

        companion object {
            val IDLE = Session()
            fun userDial(): Session = Session(wantUp = true, owner = Owner.USER)
            fun headless(): Session = Session(wantUp = true, owner = Owner.HEADLESS)
        }
    }

    enum class GiveUpReason {
        /** Nobody wants the session (a Disconnect already landed). Nothing to do. */
        NOT_WANTED,
        /** [FailureKind.terminal]: asking again gets the same answer. */
        TERMINAL,
        /** A user dial that never reached Connected. */
        NEVER_CONNECTED,
        /** The retryable budget for this streak is spent. */
        BUDGET_EXHAUSTED,
    }

    sealed interface Decision {
        /** Re-dial after [delayMs]. [attempt] is 1-based within the streak. */
        data class Retry(val attempt: Int, val delayMs: Long) : Decision

        /** Offline: do not spend budget; re-dial the moment the network returns. */
        data object WaitForNetwork : Decision

        data class GiveUp(val reason: GiveUpReason, val kind: FailureKind, val attempts: Int) : Decision
    }

    data class Outcome(val session: Session, val decision: Decision)

    /**
     * Fold one failure into [session] and decide what happens next.
     *
     * @param online what the network monitor says right now. An offline
     *   failure is not evidence against the server, so it neither counts nor
     *   backs off: the supervisor waits and re-dials on the online edge.
     * @param jitter a value in [-1, 1]; injected so tests are deterministic.
     */
    fun onFailure(
        session: Session,
        kind: FailureKind,
        online: Boolean,
        nowMs: Long,
        jitter: Double,
    ): Outcome {
        if (!session.wantUp) {
            return Outcome(session, Decision.GiveUp(GiveUpReason.NOT_WANTED, kind, session.failures))
        }
        if (kind.terminal) {
            return Outcome(
                session.copy(wantUp = false),
                Decision.GiveUp(GiveUpReason.TERMINAL, kind, session.failures),
            )
        }
        if (!session.mayAutoRetry) {
            return Outcome(
                session.copy(wantUp = false),
                Decision.GiveUp(GiveUpReason.NEVER_CONNECTED, kind, session.failures),
            )
        }
        if (!online) return Outcome(session, Decision.WaitForNetwork)
        if (session.lastChance) {
            return Outcome(
                session.copy(failures = 1, lastFailureAt = nowMs, trippedAt = nowMs, lastChance = false),
                Decision.GiveUp(GiveUpReason.BUDGET_EXHAUSTED, kind, 1),
            )
        }

        val continues = session.failures > 0 && nowMs - session.lastFailureAt <= FAILURE_WINDOW_MS
        val failures = if (continues) session.failures + 1 else 1
        val reapStreak = kind == FailureKind.REAPED || (continues && session.reapStreak)
        // Judged by the budget of the MOST RECENT kind, as on iOS: a streak
        // that degrades into never-handshaking stops at that kind's smaller
        // budget instead of riding the larger one it started with. A streak a
        // reap began keeps the reap's budget whatever its rebuild fails with.
        val budget = if (reapStreak) maxAttempts(FailureKind.REAPED) else maxAttempts(kind)
        if (failures > budget) {
            return Outcome(
                session.copy(failures = failures, lastFailureAt = nowMs, trippedAt = nowMs, reapStreak = reapStreak),
                Decision.GiveUp(GiveUpReason.BUDGET_EXHAUSTED, kind, failures - 1),
            )
        }
        return Outcome(
            session.copy(failures = failures, lastFailureAt = nowMs, reapStreak = reapStreak),
            Decision.Retry(attempt = failures, delayMs = backoffMs(failures, jitter)),
        )
    }

    /** 2 s, 4 s, 8 s … capped at [MAX_DELAY_MS], ±[JITTER_FRACTION]. */
    fun backoffMs(attempt: Int, jitter: Double): Long {
        val exponent = (attempt - 1).coerceIn(0, 20)
        return jittered((INITIAL_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS), jitter)
    }

    /** [baseMs] ±[JITTER_FRACTION]; [jitter] in [-1, 1]. */
    fun jittered(baseMs: Long, jitter: Double): Long =
        (baseMs * (1.0 + JITTER_FRACTION * jitter.coerceIn(-1.0, 1.0))).toLong()

    /**
     * After the [TRIP_COOLDOWN_MS] cooldown: ONE more re-dial, keeping who
     * owns the session. If it fails the supervisor gives up again and waits
     * another cooldown; if it connects, [Session.connected] starts over.
     */
    fun afterCooldown(session: Session): Session =
        session.copy(failures = 0, lastFailureAt = 0L, trippedAt = null, lastChance = true)
}

/**
 * What a heartbeat reply means for the session (WEB-HB's client table).
 *
 * birdo-web now says WHY a key is no longer valid, in `reason`. Absent (an
 * older backend) or unknown, the answer is exactly today's: phase A's
 * inference from the gap since the last good beat. Present, it decides:
 *
 * | reason | the client |
 * |---|---|
 * | ok | nothing |
 * | server_offline, valid | today (the node is draining; the session lives) |
 * | server_offline, invalid | re-dial a DIFFERENT server, fail-closed meanwhile |
 * | revoked | stop, no retry (the owner rule) |
 * | evicted | stop, never re-dial, say another device took the slot |
 * | reaped | one quiet rebuild behind the block (the reap budget) |
 * | not_found | last good beat >= 5 min ago: reaped; else revoked |
 */
internal object HeartbeatPolicy {

    enum class Verdict { ALIVE, REAPED, REVOKED, EVICTED, SERVER_GONE }

    /** birdo-web's stale-key reap (cleanup.service.ts); the heartbeat runs on awake time only. */
    const val REAP_WINDOW_MS = 5 * 60_000L

    fun verdict(valid: Boolean, reason: String?, sinceLastOkMs: Long): Verdict = when (reason) {
        "ok" -> Verdict.ALIVE
        "server_offline" -> if (valid) Verdict.ALIVE else Verdict.SERVER_GONE
        "revoked" -> Verdict.REVOKED
        "evicted" -> Verdict.EVICTED
        "reaped" -> Verdict.REAPED
        "not_found" -> inferred(sinceLastOkMs)
        // Absent or a reason this build does not know: today's handling.
        else -> if (valid) Verdict.ALIVE else inferred(sinceLastOkMs)
    }

    /**
     * Phase A's inference, and WEB-HB's not_found rule: a key gone after the
     * device went longer than the reap window without a good beat was reaped
     * while it slept; one gone while beats were flowing was ended on purpose.
     */
    private fun inferred(sinceLastOkMs: Long): Verdict =
        if (sinceLastOkMs >= REAP_WINDOW_MS) Verdict.REAPED else Verdict.REVOKED
}

/**
 * What a system start (not a tap) of BirdoVpnService should do.
 *
 * Three starts reach the service without an app request behind them:
 *  - [STICKY_RESTART]: `intent == null`, the platform re-creating a
 *    START_STICKY service after the process was killed;
 *  - [ALWAYS_ON]: `VpnService.SERVICE_INTERFACE`, sent by the platform for
 *    Always-on VPN at boot, on user unlock and when the setting changes
 *    (AOSP Vpn.startAlwaysOnVpn: startService after a 60 s power allowlist);
 *  - [PACKAGE_REPLACED]: BirdoVpnService.ACTION_HEADLESS_CONNECT from
 *    PackageReplacedReceiver after an app update killed the process;
 *  - [PROCESS_RESTART]: BirdoVpnService.ACTION_RESUME_SESSION from BirdoApp
 *    when its process starts and finds the session down.
 */
enum class SystemStartKind {
    STICKY_RESTART,
    ALWAYS_ON,
    PACKAGE_REPLACED,

    /**
     * BirdoApp found, when its process started (the user opened the app, the
     * widget or the tile woke it), a session the user wanted and no service:
     * the previous process died — a crash, a low-memory kill — and Android
     * did not restart the service. Seen live on API 35 (2026-09-30): no
     * restart after `am crash` or `kill -9`, the device unprotected, and the
     * dead service's "Protected" notification still showing.
     */
    PROCESS_RESTART,
}

internal object SystemStartPolicy {

    data class Plan(
        /** Arm the blocking interface before anything else. */
        val armBlock: Boolean,
        /** Hand VpnManager a headless connect. */
        val connect: Boolean,
        /** Why the session cannot come up without the user, or null. */
        val actionNeeded: FailureKind?,
    ) {
        /** Nothing to hold and nothing to do: stop without a trace. */
        val idle: Boolean get() = !armBlock && !connect && actionNeeded == null
    }

    /**
     * @param sessionShouldBeUp the persisted user intent (AppPreferences).
     * @param alwaysOn `VpnService.isAlwaysOn()`: the user chose Always-on VPN
     *   for this app, which is itself an intent to be up.
     * @param lockdown `VpnService.isLockdownEnabled()`: Android blocks every
     *   packet without a VPN anyway, so arming our own block costs nothing and
     *   keeps the notification honest.
     */
    fun plan(
        kind: SystemStartKind,
        sessionShouldBeUp: Boolean,
        alwaysOn: Boolean,
        lockdown: Boolean,
        killSwitchPref: Boolean,
        signedIn: Boolean,
        consentAccepted: Boolean,
        vpnPermissionGranted: Boolean,
    ): Plan {
        val wantUp = sessionShouldBeUp || alwaysOn || kind == SystemStartKind.ALWAYS_ON
        // Without VPN consent neither a block nor a tunnel can be established
        // (establish() returns null), so the only honest move is to say so.
        if (!vpnPermissionGranted) {
            return Plan(
                armBlock = false,
                connect = false,
                actionNeeded = if (wantUp) FailureKind.VPN_PERMISSION_REQUIRED else null,
            )
        }
        // Block only for a session somebody actually wants. A sticky restart
        // used to arm whenever the kill switch was on, and a service left
        // foreground after a failed user dial then came back after a process
        // kill as a full block nobody asked for, with no alert
        // (REVIEW-AND-004).
        val armBlock = (killSwitchPref || lockdown) && wantUp
        val actionNeeded = when {
            !wantUp -> null
            // Audit D-12: no request to the backend before the privacy
            // consent screen has been accepted, even a headless one.
            !consentAccepted -> FailureKind.SETUP_REQUIRED
            !signedIn -> FailureKind.SIGN_IN_REQUIRED
            else -> null
        }
        return Plan(
            armBlock = armBlock,
            connect = wantUp && actionNeeded == null,
            actionNeeded = actionNeeded,
        )
    }

    /**
     * Whether a starting app process must bring the session back itself
     * ([SystemStartKind.PROCESS_RESTART]): the user wanted it up and no
     * service is running in this process to hold it.
     */
    fun resumeOnProcessStart(sessionShouldBeUp: Boolean, serviceRunning: Boolean): Boolean =
        sessionShouldBeUp && !serviceRunning
}

/**
 * The user-facing sentences the supervisor publishes, in one place so the
 * notification, Home and the tests quote the same text. Wording follows the
 * canonical vocabulary in the 2026-09-30 parity audit (P1-parity.md).
 */
internal object SessionCopy {
    const val SESSION_EXPIRED = "Your session has expired. Sign in again."
    const val UPDATE_REQUIRED = "This version of BirdoVPN is no longer supported. Update to keep connecting."
    const val RATE_LIMITED = "Too many attempts. Please wait a moment."
    const val REVOKED = "Connection has been revoked. Please reconnect."
    /** The heartbeat said "evicted" (WEB-HB's canonical sentence). */
    const val EVICTED =
        "Another device on your account connected, so this one was disconnected. Tap Connect to take it back."
    /** The heartbeat said a Multi-Hop node went away for good ("server_offline", valid = false). */
    const val SERVER_OFFLINE = "This server went offline. Reconnecting to another location…"
    const val MULTI_HOP_ROUTE_OFFLINE =
        "A server on your Multi-Hop route went offline. Choose another entry or exit."
    const val REAPED = "The server dropped this connection while the device was idle. Reconnecting…"
    const val NO_TUNNEL = "Couldn't establish a secure tunnel to this server. Try another location."
    const val QUANTUM_FAILED =
        "Quantum-protected handshake failed. Not connecting, because continuing would fall back to " +
            "weaker encryption. Try again, or turn off Quantum Protection in Settings to connect without it."
    const val STEALTH_FAILED =
        "Stealth Mode couldn't start. Not connecting, so your traffic isn't sent unprotected. " +
            "Try again, or choose another location."
    const val VPN_PERMISSION = "BirdoVPN needs VPN permission to connect."
    const val SETUP_REQUIRED = "Open BirdoVPN to finish setting up before it can connect."
    const val VPN_TAKEN_OVER =
        "Android turned BirdoVPN off: another VPN app took over, or VPN permission was removed."
    const val INCOMPLETE_MULTI_HOP =
        "Multi-Hop is on but no entry/exit pair is selected. Choose both, or turn Multi-Hop off."
    const val STILL_BLOCKED = "The kill switch is blocking traffic until you reconnect or disconnect."

    /** What a give-up says about the traffic: the app's own block is released... */
    const val TRAFFIC_RELEASED = "Traffic is no longer being blocked."

    /** ...but Android's lockdown, when it is on, still blocks (the app cannot release it). */
    const val LOCKDOWN_STILL_BLOCKING = "Android's Block connections without VPN setting is still blocking traffic."

    /** Whether [message] already says whether traffic is blocked, so nothing may append a second claim. */
    fun speaksForTraffic(message: String): Boolean =
        message.contains(TRAFFIC_RELEASED) || message.contains(LOCKDOWN_STILL_BLOCKING)
    const val STOPPED_UNEXPECTEDLY = "BirdoVPN could not restart its connection. Open BirdoVPN to reconnect."

    /**
     * The local tunnel engine failed (the service would not start, wg-go
     * crashed, its sockets could not be protected). Never the exception's own
     * text: that is a stack-trace fragment, not something a user can act on
     * (A2-001's rule, applied to the engine).
     */
    const val ENGINE_FAILED = "BirdoVPN couldn't start its secure tunnel. Please try again."

    /** A1-034: a live switch or settings change that did not happen; the session did not move. */
    const val SWITCH_KEPT_PREVIOUS = "Couldn't switch. You're still connected to your previous location."
    const val STILL_ON_PREVIOUS = "You're still connected to your previous location."

    /**
     * A1-034: the new peer never answered AFTER the swap, and Android cannot
     * swap the previous session back (its key is gone from memory by design).
     * Failed closed, and said so — iOS #354's rule.
     */
    fun switchFailedClosed(killSwitch: Boolean): String = if (killSwitch) {
        "That server didn't answer, and the switch couldn't be undone. Traffic stays blocked until you " +
            "reconnect or disconnect."
    } else {
        "That server didn't answer, and the switch couldn't be undone. You're not connected. Tap Connect to try again."
    }

    /** Every network is held behind a sign-in page (hotel, airport Wi-Fi): A1-026. */
    const val CAPTIVE_PORTAL = "This Wi-Fi network needs you to sign in first. Sign in, then connect."

    /** Why a system start cannot bring the session up without the user. */
    fun actionNeeded(kind: FailureKind): String = when (kind) {
        FailureKind.SIGN_IN_REQUIRED -> SESSION_EXPIRED
        FailureKind.SETUP_REQUIRED -> SETUP_REQUIRED
        FailureKind.VPN_PERMISSION_REQUIRED -> VPN_PERMISSION
        else -> NO_TUNNEL
    }

    /**
     * The give-up explanation, iOS TunnelCircuitBreaker.userMessage by
     * failure kind (P1-parity-020). It says what stopped and what the user can
     * do. The middle sentence is the Android truth: the app's own block is
     * released when the supervisor gives up, but Android's "Block connections
     * without VPN" keeps blocking, and saying otherwise would be a false claim
     * about the user's traffic.
     */
    fun giveUp(kind: FailureKind, attempts: Int, lockdown: Boolean): String {
        val plural = if (attempts == 1) "attempt" else "attempts"
        val traffic = if (lockdown) LOCKDOWN_STILL_BLOCKING else TRAFFIC_RELEASED
        return when (kind) {
            FailureKind.NEVER_ESTABLISHED ->
                "BirdoVPN stopped reconnecting after $attempts $plural: the tunnel came up but never " +
                    "reached this server, so no traffic could pass. $traffic Try a different location, " +
                    "or a different network."
            FailureKind.REAPED, FailureKind.REVOKED ->
                "BirdoVPN stopped reconnecting: the server ended this connection (it may have been " +
                    "revoked, or claimed by another device). $traffic Tap Connect to start a new session."
            else ->
                "BirdoVPN stopped reconnecting after $attempts $plural: the connection to this server " +
                    "keeps dropping. $traffic Tap Connect to retry, or pick another location."
        }
    }

    /** The message for an API refusal, by status; falls back to the server's own (sanitised) text. */
    fun forApiError(code: Int, serverMessage: String): String = when (code) {
        401 -> SESSION_EXPIRED
        426 -> UPDATE_REQUIRED
        429 -> RATE_LIMITED
        else -> serverMessage
    }
}

/**
 * What a one-tap surface — the Quick Settings tile, the home-screen widget —
 * does when tapped. Both used to decide on the SERVICE's state, which stays
 * Disconnected for the whole API phase of a dial, so a second tap started a
 * second dial (A1-021); and the widget only ever opened the app while saying
 * "Tap to connect" (A2-014). One decision, on VpnManager's state, for both.
 */
internal object QuickToggle {

    enum class Action {
        CONNECT,
        /** Disconnect, cancel a dial, or release the kill-switch block. */
        DISCONNECT,
        /** Needs the app: signed out, or the VPN permission prompt. */
        OPEN_APP,
        /** Mid-disconnect: nothing to do. */
        NONE,
    }

    /**
     * @param consentAccepted the CURRENT privacy consent. Without it nothing may
     *   reach the backend (audit D-12), and a widget or tile tap after a
     *   consent-version bump used to send /vpn/connect before the user had seen
     *   the new text (REVIEW-AND-010). Stopping is always allowed.
     */
    fun decide(
        state: VpnState,
        killSwitchActive: Boolean,
        signedIn: Boolean,
        vpnPermissionGranted: Boolean,
        consentAccepted: Boolean,
    ): Action = when {
        state is VpnState.Disconnecting -> Action.NONE
        state is VpnState.Connected || state.isConnectingPhase || state is VpnState.Reconnecting ->
            Action.DISCONNECT
        // Blocked with nothing connecting: the tap is the way out of the block.
        killSwitchActive -> Action.DISCONNECT
        !signedIn || !vpnPermissionGranted || !consentAccepted -> Action.OPEN_APP
        else -> Action.CONNECT
    }

    /** What CONNECT dials, given the Multi-Hop decision and the cached plan. */
    sealed interface ConnectPlan {
        data class MultiHop(val entryNodeId: String, val exitNodeId: String) : ConnectPlan
        /** VpnManager.connectPreferred: the last server, else the best one. */
        data class Preferred(val multiHopEntitled: Boolean) : ConnectPlan
        /** Refuse to guess; the app has a live subscription and somewhere to show an error. */
        data class OpenApp(val reason: String) : ConnectPlan
    }

    /**
     * @param decision [MultiHopPolicy.forNewConnection] on the arming prefs.
     * @param plan the cached plan, or null when unknown (cold process, cache
     *   aged out).
     *
     * The pref alone is NOT the armed state: Home renders
     * `multiHop.enabled && isSovereign`, and nothing clears the pref when a
     * plan lapses — so an ex-SOVEREIGN account keeps multiHopEnabled == true
     * forever. A single hop is both what they are entitled to and what the app
     * draws for them, so there is no downgrade being hidden. With the plan
     * unknown, guessing single-hop could silently downgrade a paying user and
     * guessing multi-hop could dead-end a lapsed one, so hand off to the app.
     */
    fun connectPlan(decision: MultiHopPolicy.NewConnection, plan: String?): ConnectPlan {
        if (decision == MultiHopPolicy.NewConnection.SingleHop) return ConnectPlan.Preferred(multiHopEntitled = false)
        val entitled = MultiHopPolicy.entitledByPlan(plan)
            ?: return ConnectPlan.OpenApp("Multi-hop armed but entitlement unknown")
        if (!entitled) return ConnectPlan.Preferred(multiHopEntitled = false)
        return when (decision) {
            is MultiHopPolicy.NewConnection.MultiHop -> ConnectPlan.MultiHop(decision.entryNodeId, decision.exitNodeId)
            // Armed but incomplete (a node was retired, or prefs are
            // half-written). Quietly substituting a single hop is the failure
            // the policy exists to prevent.
            MultiHopPolicy.NewConnection.RefuseIncompletePair ->
                ConnectPlan.OpenApp("Multi-hop armed but entry/exit incomplete")
            MultiHopPolicy.NewConnection.SingleHop -> ConnectPlan.Preferred(multiHopEntitled = false)
        }
    }
}
