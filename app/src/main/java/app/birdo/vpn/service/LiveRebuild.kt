package app.birdo.vpn.service

import app.birdo.vpn.data.repository.FailureReason

/**
 * A1-034: the PURE half of the in-place live rebuild — the Android twin of
 * iOS `LiveRebuild.swift` (#350, #354).
 *
 * WHY. A server switch or a settings reapply used to tear the session down
 * to the kill-switch block (or to cleartext, for a kill-switch-off user),
 * DELETE the old peer, dial a fresh /connect and rebuild the service: a
 * several-second blackout of the whole device, every time. Now the /connect
 * rides the LIVE tunnel with `rebuild: true` and the key it rides
 * (`currentKeyId`), the server defers that one key's eviction until the new
 * peer handshakes, the new interface is established on the RUNNING service
 * (`VpnService.Builder.establish()` swaps routing to it atomically), and the
 * old key is released only once the new peer has answered.
 *
 * WHERE ANDROID DIFFERS FROM iOS, AND WHY. iOS swaps the peer on one utun
 * and can swap the previous profile back when the new peer stays silent.
 * Android cannot revert: a second establish() needs the old session's
 * private key, which BirdoVpnService deliberately drops from memory once the
 * tunnel is up (H-11). So the iOS #354 lesson is applied as: a rebuild that
 * fails BEFORE the old tunnel is touched keeps the old session exactly as it
 * was; one that fails AFTER the swap fails CLOSED (the block, when the kill
 * switch is on) and says so — never open.
 *
 * Every outcome goes through [directive]; VpnManager.finishLiveRebuild is its
 * only interpreter, so what the tests pin here is what the app does.
 */
internal object LiveRebuildPolicy {

    /** What one rebuild attempt observed at the point it must decide. */
    enum class Event {
        /**
         * `success: false` with a `rebuildRefused` the server will refuse the
         * same way every time: it holds no live session under the key we ride
         * (`unknown-current-key`), or will not move a Multi-Hop entry's exit in
         * place (`same-entry-exit-change`). The SERVER's answer: nothing was
         * evicted or minted.
         */
        CANNOT_REBUILD_HERE,

        /**
         * The SERVICE's answer, after the server minted the new key and held
         * the old one back: there is no live session on the device to move
         * (the dead-tunnel handler or a block got there first, or it runs
         * Stealth). Today's path, like [CANNOT_REBUILD_HERE] — but the new key
         * is given back, and the teardown releases the old one, which the
         * session key is put back to (VpnManager.finishLiveRebuild). Answered
         * as CANNOT_REBUILD_HERE, both keys stayed out until the stale sweep.
         */
        NO_LIVE_SESSION,

        /** Any other `success: false` (a device cap, a plan, a lookup error): nothing was touched. */
        REFUSED,

        /**
         * /connect was answered with a failure (4xx, 5xx, a body that did not
         * decode). The answer itself proves the live tunnel carries the API.
         * Nothing was minted; the old tunnel was never touched.
         */
        REQUEST_FAILED,

        /**
         * REVIEW-AND2-003: /connect got no HTTP answer at all through the live
         * tunnel (no route, a timeout, a TLS failure). A tunnel that handshakes
         * but cannot carry the API's TLS — an MTU black hole (handshakes are
         * small, a certificate flight is not), a node whose egress is broken —
         * fails every rebuild this way, and keeping the session kept the user
         * on it for good: the dead-tunnel check sees answers, a heartbeat's
         * transport error never tears down, and the MTU change that would fix
         * it could never be applied. So it is taken as the tunnel's inability
         * to carry the change: today's path, behind the block, over the bypass
         * client. A key the server minted before the answer was lost is
         * retired by its supersede sweeper at the deferral deadline.
         */
        REQUEST_UNANSWERED,

        /** Minted, but `deferredKeyId` did not echo the key we ride: the old peer may already be gone. */
        DEFERRAL_NOT_HONOURED,

        /** Multi-Hop: the server confirmed a different route. Minted; nothing swapped. */
        ROUTE_NOT_CONFIRMED,

        /**
         * The service refused the new config before establish() moved routing
         * (a requested-vs-granted guard, the PQ derivation, the config, or
         * establish() returning null — which leaves "the existing interface
         * and its file descriptor untouched", per the platform docs).
         */
        FAILED_BEFORE_SWAP,

        /** The new peer handshaked: the switch is real. */
        NEW_PEER_HANDSHAKED,

        /** The swap happened and the new peer did not answer: failed closed in the service. */
        FAILED_AFTER_SWAP,

        /** A Disconnect or a newer dial took over while the rebuild ran. */
        SUPERSEDED,
    }

    enum class Directive {
        /** Today's path: the fail-closed teardown, then a fresh dial. Only when the SERVER cannot defer. */
        LEGACY_TEARDOWN,

        /** Keep the live session exactly as it is, say why the change did not happen. */
        KEEP_OLD_SESSION,

        /** The new peer is the session: release the old key. */
        COMMIT_NEW,

        /** The service already failed closed; the supervisor and the user take it from here. */
        FAILED_CLOSED,

        /** Someone else owns the session now; release what this attempt minted. */
        ABANDON,
    }

    /** Which keys a directive releases (DELETE /vpn/connections/{keyId}). */
    data class Release(val newKey: Boolean, val oldKey: Boolean)

    fun directive(event: Event): Directive = when (event) {
        Event.CANNOT_REBUILD_HERE, Event.NO_LIVE_SESSION, Event.DEFERRAL_NOT_HONOURED, Event.REQUEST_UNANSWERED ->
            Directive.LEGACY_TEARDOWN
        Event.REFUSED, Event.REQUEST_FAILED, Event.ROUTE_NOT_CONFIRMED, Event.FAILED_BEFORE_SWAP ->
            Directive.KEEP_OLD_SESSION
        Event.NEW_PEER_HANDSHAKED -> Directive.COMMIT_NEW
        Event.FAILED_AFTER_SWAP -> Directive.FAILED_CLOSED
        Event.SUPERSEDED -> Directive.ABANDON
    }

    /**
     * The key a minted-but-unused attempt must give back, and the old key once
     * nothing rides it. The old key is NEVER released while the old session
     * may still be the one carrying traffic.
     */
    fun release(event: Event): Release = when (event) {
        Event.CANNOT_REBUILD_HERE, Event.REFUSED, Event.REQUEST_FAILED, Event.REQUEST_UNANSWERED ->
            Release(newKey = false, oldKey = false)
        Event.NO_LIVE_SESSION, Event.DEFERRAL_NOT_HONOURED, Event.ROUTE_NOT_CONFIRMED, Event.FAILED_BEFORE_SWAP ->
            Release(newKey = true, oldKey = false)
        Event.NEW_PEER_HANDSHAKED -> Release(newKey = false, oldKey = true)
        Event.FAILED_AFTER_SWAP -> Release(newKey = true, oldKey = true)
        Event.SUPERSEDED -> Release(newKey = true, oldKey = false)
    }

    /** The event for a `rebuildRefused` code; an unknown one keeps the session (never stops it). */
    fun forRefusal(code: String?): Event = when (code) {
        "unknown-current-key", "same-entry-exit-change" -> Event.CANNOT_REBUILD_HERE
        else -> Event.REFUSED
    }

    /**
     * The event for a /connect that failed, from its
     * [app.birdo.vpn.data.repository.ApiResult.Error]: no HTTP answer at all
     * is [Event.REQUEST_UNANSWERED]; anything the server answered, whatever
     * the status, is [Event.REQUEST_FAILED]. Code 0 is "no response", and its
     * reason tells a network failure from a 2xx body that did not decode,
     * which the tunnel did carry.
     */
    fun forRequestFailure(code: Int, reason: FailureReason): Event =
        if (code == 0 && reason in UNANSWERED) Event.REQUEST_UNANSWERED else Event.REQUEST_FAILED

    private val UNANSWERED = setOf(FailureReason.OFFLINE, FailureReason.UNREACHABLE, FailureReason.SECURE_CONNECTION)

    /** Only an echo of the EXACT key we ride counts as a deferral. */
    fun deferralHonoured(currentKeyId: String, deferredKeyId: String?): Boolean =
        !deferredKeyId.isNullOrEmpty() && deferredKeyId == currentKeyId

    /**
     * Whether this change may be made in place on the live session.
     *
     * Not with Stealth on either side: Xray is a single child process on one
     * local port, and a second one cannot run beside the session it would
     * replace. Not while the kill-switch block is up (there is no live tunnel
     * to ride), and not without the key the live session rides (an install
     * upgraded under a live tunnel has none): today's path, as on iOS.
     */
    fun eligible(
        sessionConnected: Boolean,
        currentKeyId: String?,
        stealthActive: Boolean,
        stealthWanted: Boolean,
        blockActive: Boolean,
    ): Boolean = sessionConnected && !currentKeyId.isNullOrEmpty() && !stealthActive && !stealthWanted && !blockActive

    /**
     * How long the service waits for the NEW peer's first handshake: iOS's
     * 18 s (LiveRebuildProbe.windowMs), not the fresh dial's 10 s. It must
     * not end on a WireGuard REKEY_TIMEOUT boundary (initiations leave at
     * 0, 5, 10, 15 s) and must end well inside the server's 30 s deferral
     * grace, which runs from the server mint.
     */
    const val PROBE_WINDOW_MS = 18_000L
}
