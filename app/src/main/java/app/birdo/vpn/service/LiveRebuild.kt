package app.birdo.vpn.service

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
         * place (`same-entry-exit-change`). Nothing was evicted or minted.
         */
        CANNOT_REBUILD_HERE,

        /** Any other `success: false` (a device cap, a plan, a lookup error): nothing was touched. */
        REFUSED,

        /** /connect failed (transport, 4xx, 5xx). Nothing was minted; the old tunnel was never touched. */
        REQUEST_FAILED,

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
        Event.CANNOT_REBUILD_HERE, Event.DEFERRAL_NOT_HONOURED -> Directive.LEGACY_TEARDOWN
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
        Event.CANNOT_REBUILD_HERE, Event.REFUSED, Event.REQUEST_FAILED -> Release(newKey = false, oldKey = false)
        Event.DEFERRAL_NOT_HONOURED, Event.ROUTE_NOT_CONFIRMED, Event.FAILED_BEFORE_SWAP ->
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
