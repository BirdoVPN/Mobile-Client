package app.birdo.vpn.service

import app.birdo.vpn.data.model.VpnServer

/*
 * MR-2492 (MR-2212's rule, the same on every client; owner 2026-11-19): when
 * the server a session was on is gone, which relay it moves to, and whether
 * it may move at all. The Android twin of iOS `RelayRetry.swift` (Mobile
 * #645, 70b26a0e), decision for decision:
 *
 *  * a server the client chose ([SelectionOrigin.AUTOMATIC]) may move to the
 *    best other relay, in any country;
 *  * a location the USER picked never leaves its country: the same city
 *    first, then another relay in the same country, else nothing;
 *  * only relays /vpn/servers lists as online and open to this plan
 *    ([VpnManager.bestServer], the quick-connect rule). A node held for a
 *    jurisdiction mismatch fails its health check and is listed offline;
 *  * ONE move per connect ([RelayRetryBudget]);
 *  * the log says a count and a reach class only, never a server's id, name,
 *    city, country or address ([logLine]).
 *
 * On Android the move is made when the heartbeat, or the probe after a dead
 * tunnel, says the server went offline for good (HeartbeatPolicy
 * SERVER_GONE). It used to be a quick connect that skipped that server: the
 * best relay in any country, whoever had chosen the location.
 *
 * Pure (no Android, no I/O), so RelayRetryTest pins the table on the JVM.
 */

/**
 * MR-2492: who chose the server a dial goes to. Android re-dials the last
 * server from the tile, the widget and a system start long after the choice,
 * so it is stored with it (AppPreferences.lastServerAutoPicked).
 */
enum class SelectionOrigin {
    /**
     * The client chose it: a quick connect's best server, the pre-select on
     * Home. Nobody asked for that location, so a move may leave it.
     */
    AUTOMATIC,

    /**
     * The user picked it, or nothing says the client did. The safe default:
     * a move never takes it out of its country.
     */
    USER,
    ;

    companion object {
        /** The origin AppPreferences.lastServerAutoPicked records for the last server. */
        fun ofLastServer(autoPicked: Boolean): SelectionOrigin = if (autoPicked) AUTOMATIC else USER
    }
}

/**
 * MR-2492: the one automatic move per connect. Refilled by a dial the user or
 * the system starts and by a session that comes up; taken by the move
 * itself. The move's own dial refills nothing, so a move never chains.
 */
class RelayRetryBudget {
    var spent: Boolean = false
        private set

    /** A dial the user or the system started, or a session that came up. */
    fun refill() {
        spent = false
    }

    /** Take the move. False, changing nothing, when it is already spent. */
    fun take(): Boolean {
        if (spent) return false
        spent = true
        return true
    }
}

object RelayRetry {
    /** How far a move goes from the server that went away: the only thing about the target the log may say. */
    enum class Reach(val label: String) {
        SAME_CITY("same-city"),
        SAME_COUNTRY("same-country"),
        OTHER_COUNTRY("other-country"),
    }

    data class Choice(val server: VpnServer, val reach: Reach)

    /**
     * The relay to move to after [failedId] went away, or null. The first
     * rule that applies wins:
     *  1. another relay in the SAME city and country;
     *  2. [SelectionOrigin.AUTOMATIC]: the best of all the other relays, in any country;
     *  3. [SelectionOrigin.USER]: the best other relay in the SAME country;
     *  4. nothing; the caller then says so instead.
     * "Best" is the quick-connect rule ([VpnManager.bestServer]): online,
     * accessible to this plan, least loaded. null when [failedId] is not in
     * [list]: without the failed relay's country the user's choice cannot be
     * kept, so nothing moves (iOS's rule, kept for either origin).
     */
    fun next(
        failedId: String,
        list: List<VpnServer>,
        origin: SelectionOrigin,
        @Suppress("UNUSED_PARAMETER") lastKnown: VpnServer? = null,
    ): Choice? {
        val failed = list.firstOrNull { it.id == failedId } ?: return null
        sibling(failedId, list)?.let { return Choice(it, Reach.SAME_CITY) }
        val others = list.filter { it.id != failedId }
        return when (origin) {
            SelectionOrigin.AUTOMATIC ->
                VpnManager.bestServer(others)?.let { Choice(it, reach(failed, it)) }
            SelectionOrigin.USER -> {
                val country = normalized(failed.countryCode)
                if (country.isEmpty()) return null
                VpnManager.bestServer(others.filter { normalized(it.countryCode) == country })
                    ?.let { Choice(it, Reach.SAME_COUNTRY) }
            }
        }
    }

    /**
     * [next], behind the budget: the choice with the move taken, or null with
     * the budget untouched (already spent, or no relay qualifies).
     */
    fun plan(
        failedId: String,
        list: List<VpnServer>,
        origin: SelectionOrigin,
        budget: RelayRetryBudget,
        lastKnown: VpnServer? = null,
    ): Choice? {
        if (budget.spent) return null
        val choice = next(failedId, list, origin, lastKnown) ?: return null
        budget.take()
        return choice
    }

    /**
     * Another node in the SAME location as [failedId] (same country code and
     * same non-empty city, compared case-insensitively), online and
     * accessible to this plan, least loaded. null when there is none.
     */
    fun sibling(failedId: String, list: List<VpnServer>): VpnServer? {
        val failed = list.firstOrNull { it.id == failedId } ?: return null
        val city = normalized(failed.city)
        val country = normalized(failed.countryCode)
        if (city.isEmpty() || country.isEmpty()) return null
        return VpnManager.bestServer(
            list.filter {
                it.id != failedId && normalized(it.city) == city && normalized(it.countryCode) == country
            },
        )
    }

    /**
     * The one log line for a move decision: the count and the reach class.
     * It takes the whole choice so no caller needs the server in hand to
     * write a line; RelayRetryTest feeds it marked servers and checks that
     * none of their fields comes out.
     *
     * @param alreadySpent the move was made before this decision (its own
     *   dial did not get through, and the supervisor is trying it again).
     */
    fun logLine(choice: Choice?, alreadySpent: Boolean): String = when {
        choice != null -> "[RelayRetry] server offline: automatic retry 1 of 1 (${choice.reach.label})"
        alreadySpent -> "[RelayRetry] server offline: the automatic retry is spent (1 of 1)"
        else -> "[RelayRetry] server offline: no relay qualifies for a retry (0 of 1)"
    }

    private fun reach(failed: VpnServer, target: VpnServer): Reach {
        val country = normalized(failed.countryCode)
        if (country.isEmpty() || country != normalized(target.countryCode)) return Reach.OTHER_COUNTRY
        val city = normalized(failed.city)
        return if (city.isNotEmpty() && city == normalized(target.city)) Reach.SAME_CITY else Reach.SAME_COUNTRY
    }

    private fun normalized(s: String): String = s.trim().lowercase()
}
