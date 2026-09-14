package app.birdo.vpn.service

/**
 * The single place that decides whether an action may build, replace, or
 * downgrade a Multi-Hop route.
 *
 * WHY THIS EXISTS (Mobile-Client#336)
 * -----------------------------------
 * The estate's most common defect shape is "a guard on one of N parallel
 * paths". Multi-Hop was its textbook case: five entry points could start or
 * change a tunnel, three carried the guard, two did not — and one of the
 * guarded ones carried a comment claiming it protected the quick-settings
 * tile, which does not call it. The tile injects VpnManager directly and never
 * reaches the ViewModel. Nothing surfaced the gap, because the guarded paths
 * worked and the unguarded paths produced no error: just a single hop while
 * the UI kept rendering entry → exit. For a feature bought for jurisdictional
 * separation, silently serving the other thing is the worst available failure.
 *
 * The issue's own conclusion was that comments naming the other paths are
 * demonstrably not enough, and that consolidating the decision into one
 * function "would convert a future sixth path from a silent leak into a
 * compile error". This is that function.
 *
 * HOW IT HOLDS
 * ------------
 * Both decisions return a SEALED type. A caller must handle every branch, so
 * adding a case here breaks every call site at compile time rather than
 * letting one of them keep the old behaviour. `MultiHopPolicyCallSiteTest`
 * additionally reads the sources and fails if a new call to
 * `connect(...)`/`connectMultiHop(...)` appears outside a function that
 * consults this object — the enumeration test #336 asks for.
 *
 * This object is pure: no Android, no coroutines, no I/O. That is what makes
 * the policy table testable as a table.
 */
object MultiHopPolicy {

    /** What a fresh connection attempt must do, given the user's settings. */
    sealed interface NewConnection {
        /** Multi-Hop is armed and complete: dial entry → exit, never a single hop. */
        data class MultiHop(val entryNodeId: String, val exitNodeId: String) : NewConnection

        /**
         * Multi-Hop is armed but the pair is incomplete — a node was destroyed,
         * or prefs were half-written. Refuse. Falling back to a single hop is
         * the exact silent downgrade this type exists to prevent, and it would
         * look identical to success to the user.
         */
        data object RefuseIncompletePair : NewConnection

        /** Multi-Hop is off: an ordinary single-hop connection is correct. */
        data object SingleHop : NewConnection
    }

    /** Whether an action may replace the route that is currently up. */
    sealed interface RouteChange {
        data object Allowed : RouteChange

        /** A live multi-hop session would become a single hop. Refuse, with a reason for the UI. */
        data class RefuseWouldDowngrade(val message: String) : RouteChange
    }

    /**
     * The message the UI shows when a server tap would downgrade a live
     * Multi-Hop session. Kept here so every surface says the same thing.
     */
    const val DOWNGRADE_REFUSAL: String =
        "Switching servers would replace your Multi-Hop route with a single hop. " +
            "Disconnect first if you meant to switch."

    /**
     * Decide what a NEW connection attempt should do.
     *
     * Every entry point that can start a tunnel with no server explicitly
     * chosen by the user in that moment — auto-connect on launch, quick
     * connect, the home-screen widget, the quick-settings tile — must route
     * through this. Each of those surfaces has no UI to gate on, which is
     * precisely why each of them silently built a single hop before.
     */
    fun forNewConnection(
        multiHopEnabled: Boolean,
        entryNodeId: String?,
        exitNodeId: String?,
    ): NewConnection {
        if (!multiHopEnabled) return NewConnection.SingleHop
        if (entryNodeId.isNullOrBlank() || exitNodeId.isNullOrBlank()) {
            return NewConnection.RefuseIncompletePair
        }
        return NewConnection.MultiHop(entryNodeId, exitNodeId)
    }

    /**
     * Decide whether picking a different server may proceed.
     *
     * ORDER MATTERS, and getting it wrong is how Android drifted from iOS in
     * the first place. The refusal applies only while something is actually on
     * the tunnel, mirroring iOS's `selectServerLive` (`guard isConnected ||
     * isConnecting` precedes its refusal), and for two concrete reasons:
     *
     *   * A tap that only relabels (nothing connected) can downgrade nothing,
     *     so refusing it is pure obstruction.
     *   * [activeRoute] goes STALE. VpnManager clears it in connect() and
     *     tearDownTunnel() only; a teardown driven by the service — the
     *     notification's Disconnect action, onRevoke(), onDestroy() — reaches
     *     neither. It is also set on connectMultiHop's API success, before the
     *     tunnel exists, so a failed multi-hop dial leaves it set with nothing
     *     running. Gating on it alone would refuse every server tap while the
     *     user is plainly disconnected, with no Disconnect control rendered
     *     anywhere to satisfy the message.
     *
     * No same-node exemption, deliberately, and this matches iOS: the
     * selection label does not track the live route during a multi-hop
     * session, so exempting "tapped the highlighted row" let a second tap wipe
     * the refusal the first one raised.
     */
    fun forRouteChange(onTunnel: Boolean, activeRoute: Pair<String, String>?): RouteChange =
        if (onTunnel && activeRoute != null) {
            RouteChange.RefuseWouldDowngrade(DOWNGRADE_REFUSAL)
        } else {
            RouteChange.Allowed
        }
}
