package app.birdo.vpn.ui.screen

import androidx.annotation.StringRes
import app.birdo.vpn.R
import app.birdo.vpn.service.FailureKind
import app.birdo.vpn.service.SessionCopy
import app.birdo.vpn.service.VpnState
import app.birdo.vpn.service.isConnectingPhase
import app.birdo.vpn.ui.components.BadgeTone
import app.birdo.vpn.ui.viewmodel.VpnUiState
import app.birdo.vpn.ui.viewmodel.multiHopActive

/**
 * What the Connect screen says and offers for a session state, as pure
 * functions so the vocabulary and the controls are unit-tested rather than
 * checked by eye. The words are the canonical ones of the 2026-09-30 parity
 * audit (P1-parity.md, "Connection states").
 */
internal data class HomeConnection(
    val isConnected: Boolean,
    /** A setup is in flight — including a server switch (P1-parity-016). */
    val isConnecting: Boolean,
    val isSwitching: Boolean,
    val isDisconnecting: Boolean,
    /** The supervisor is recovering a dropped session (A1-008). */
    val isReconnecting: Boolean,
    /** The kill switch is blocking and nothing is connecting. */
    val isBlocking: Boolean,
)

internal fun homeConnection(state: VpnUiState): HomeConnection {
    val s = state.vpnState
    val isConnected = s is VpnState.Connected
    val isReconnecting = s is VpnState.Reconnecting
    // A switch keeps its own words for the whole teardown-and-dial window,
    // including the moment the old tunnel is down and the new dial not yet up.
    val isSwitching = state.switching &&
        (s.isConnectingPhase || s is VpnState.Disconnecting || s is VpnState.Disconnected)
    val isConnecting = s.isConnectingPhase || isSwitching
    val isDisconnecting = s is VpnState.Disconnecting && !isSwitching
    return HomeConnection(
        isConnected = isConnected,
        isConnecting = isConnecting,
        isSwitching = isSwitching,
        isDisconnecting = isDisconnecting,
        isReconnecting = isReconnecting,
        isBlocking = state.killSwitchActive && !isConnected && !isConnecting &&
            !isReconnecting && !isDisconnecting,
    )
}

internal enum class PillIcon { NONE, SYNC, ERROR, OFFLINE }

internal data class PillModel(
    @param:StringRes val text: Int,
    val tone: BadgeTone,
    val icon: PillIcon,
    val pulse: Boolean,
)

/** The status pill. Reconnecting used to fall through to "Not Connected" (A1-008, P1-parity-001). */
internal fun pillModel(state: VpnUiState): PillModel {
    val c = homeConnection(state)
    val s = state.vpnState
    return when {
        c.isConnected -> PillModel(
            if (state.multiHopActive) R.string.status_protected_multihop else R.string.status_protected,
            BadgeTone.Success,
            PillIcon.NONE,
            pulse = true,
        )
        c.isSwitching -> PillModel(R.string.status_switching, BadgeTone.Warning, PillIcon.SYNC, pulse = false)
        c.isReconnecting -> PillModel(R.string.status_reconnecting, BadgeTone.Warning, PillIcon.SYNC, pulse = false)
        c.isConnecting -> PillModel(R.string.connecting, BadgeTone.Warning, PillIcon.SYNC, pulse = false)
        c.isDisconnecting -> PillModel(R.string.disconnecting, BadgeTone.Warning, PillIcon.SYNC, pulse = false)
        s is VpnState.Error -> PillModel(R.string.status_error, BadgeTone.Danger, PillIcon.ERROR, pulse = false)
        else -> PillModel(R.string.status_not_connected, BadgeTone.Neutral, PillIcon.OFFLINE, pulse = false)
    }
}

internal enum class CtaAction { CONNECT, CONNECT_MULTI_HOP, DISCONNECT, NONE }

internal data class CtaModel(
    @param:StringRes val label: Int,
    val action: CtaAction,
    /** Spinner, and the button is not tappable. */
    val busy: Boolean,
    /** Offer the separate Cancel control (A1-010). */
    val showCancel: Boolean,
)

/**
 * The main button. Reconnecting and a held block both offer Disconnect — the
 * way out of a recovery loop and out of the block (canonical CTA table). A
 * connect or a switch keeps its canonical, disabled "Connecting…" /
 * "Switching…" label and gets a separate Cancel beside it, because a connect
 * on a filtering network can take 40 s+ with the device's traffic stuck in a
 * tunnel that never handshakes, and there was no way out of it in the app.
 */
internal fun ctaModel(c: HomeConnection, multiHopArmed: Boolean, multiHopReady: Boolean): CtaModel = when {
    c.isConnected || c.isReconnecting || c.isBlocking ->
        CtaModel(R.string.disconnect, CtaAction.DISCONNECT, busy = false, showCancel = false)
    c.isSwitching -> CtaModel(R.string.cta_switching, CtaAction.NONE, busy = true, showCancel = true)
    c.isConnecting -> CtaModel(R.string.connecting, CtaAction.NONE, busy = true, showCancel = true)
    c.isDisconnecting -> CtaModel(R.string.disconnecting, CtaAction.NONE, busy = true, showCancel = false)
    multiHopReady -> CtaModel(R.string.home_connect_multihop, CtaAction.CONNECT_MULTI_HOP, busy = false, showCancel = false)
    // Disabled until both ends are chosen.
    multiHopArmed -> CtaModel(R.string.home_choose_entry_exit, CtaAction.NONE, busy = false, showCancel = false)
    else -> CtaModel(R.string.connect, CtaAction.CONNECT, busy = false, showCancel = false)
}

/** What fixes a session Error, offered on its banner (P1-parity-040). */
internal enum class Remedy(@param:StringRes val label: Int) {
    OPEN_SETTINGS(R.string.banner_action_open_settings),
    VIEW_PLANS(R.string.banner_action_view_plans),
    UPDATE(R.string.update_action),
    CHOOSE_SERVER(R.string.banner_action_choose_server),
}

/**
 * The action that fixes a failure, or null when the Connect button already is
 * the fix (try again) or the app handles it on its own (sign-in).
 */
internal fun remedyFor(kind: FailureKind): Remedy? = when (kind) {
    // "…turn off Quantum Protection / Stealth Mode in Settings to connect without it."
    FailureKind.QUANTUM_FAILED, FailureKind.STEALTH_FAILED -> Remedy.OPEN_SETTINGS
    FailureKind.PLAN_REQUIRED, FailureKind.QUOTA_EXCEEDED -> Remedy.VIEW_PLANS
    FailureKind.UPDATE_REQUIRED -> Remedy.UPDATE
    // "…Try another location."
    FailureKind.NEVER_ESTABLISHED -> Remedy.CHOOSE_SERVER
    else -> null
}

/**
 * The Connect screen's one message banner (A2-011): its own message first
 * (dismissible), else a kill switch that could not be armed, else the
 * session's Error. Never two.
 */
internal fun homeMessage(state: VpnUiState): String? =
    state.connectError ?: killSwitchWarning(state) ?: (state.vpnState as? VpnState.Error)?.message

/**
 * P2-2: the kill switch could not be armed, said over the states that carry
 * no message of their own: Reconnecting, the re-dial, a switch. Its Error said
 * it first, but the supervisor answers a retryable failure with Reconnecting
 * at once, and the alert is held back while the app is on screen, so a user
 * watching Home never saw it. An Error speaks for itself; Connected ends it.
 */
internal fun killSwitchWarning(state: VpnUiState): String? =
    state.killSwitchNotArmed?.takeIf { state.vpnState !is VpnState.Connected && state.vpnState !is VpnState.Error }

/**
 * The banner's action, or null. None for the kill-switch failure (P3-4):
 * NEVER_ESTABLISHED's "Choose server" is no remedy for a block Android
 * refused, and nothing on Home is.
 */
internal fun homeRemedy(state: VpnUiState): Remedy? {
    if (state.connectError != null || killSwitchWarning(state) != null) return null
    val error = state.vpnState as? VpnState.Error ?: return null
    if (SessionCopy.isKillSwitchNotArmed(error.message)) return null
    return remedyFor(error.kind)
}
