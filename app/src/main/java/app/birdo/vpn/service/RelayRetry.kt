package app.birdo.vpn.service

import app.birdo.vpn.data.model.VpnServer

/**
 * MR-2492, TEST-FIRST COMMIT: these declarations carry TODAY's behaviour, so
 * RelayRetryTest compiles and fails on it. Today a server that went offline
 * is replaced by VpnManager.quickDial(exclude = last): the best other relay
 * in any country, whoever chose the location, on every re-dial. The next
 * commit replaces them with iOS RelayRetry's rule (Mobile #645, 70b26a0e).
 */
enum class SelectionOrigin {
    AUTOMATIC,
    USER,
    ;

    companion object {
        fun ofLastServer(autoPicked: Boolean): SelectionOrigin = if (autoPicked) AUTOMATIC else USER
    }
}

class RelayRetryBudget {
    var spent: Boolean = false
        private set

    fun refill() {
        spent = false
    }

    fun take(): Boolean {
        if (spent) return false
        spent = true
        return true
    }
}

object RelayRetry {
    enum class Reach(val label: String) {
        SAME_CITY("same-city"),
        SAME_COUNTRY("same-country"),
        OTHER_COUNTRY("other-country"),
    }

    data class Choice(val server: VpnServer, val reach: Reach)

    @Suppress("UNUSED_PARAMETER")
    fun next(failedId: String, list: List<VpnServer>, origin: SelectionOrigin): Choice? =
        VpnManager.bestServer(list.filter { it.id != failedId })?.let { Choice(it, Reach.OTHER_COUNTRY) }

    @Suppress("UNUSED_PARAMETER")
    fun plan(failedId: String, list: List<VpnServer>, origin: SelectionOrigin, budget: RelayRetryBudget): Choice? =
        next(failedId, list, origin)

    @Suppress("UNUSED_PARAMETER")
    fun sibling(failedId: String, list: List<VpnServer>): VpnServer? = null

    @Suppress("UNUSED_PARAMETER")
    fun logLine(choice: Choice?, alreadySpent: Boolean): String =
        "Heartbeat: this server went offline — moving to another"
}
