package app.birdo.vpn.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.StringRes
import app.birdo.vpn.R
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.utils.FaultReporter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * Quick Settings Tile — allows toggling VPN from the notification shade.
 * Long-press opens the app. Tap connects/disconnects.
 *
 * Matches Windows client's system tray quick connect/disconnect.
 */
@AndroidEntryPoint
class BirdoTileService : TileService() {

    @Inject lateinit var vpnManager: VpnManager
    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var appPreferences: app.birdo.vpn.data.preferences.AppPreferences
    // Needed only for the cached entitlement in the Multi-Hop branch below.
    @Inject lateinit var repository: app.birdo.vpn.data.repository.BirdoRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Collects VpnManager's state and the kill-switch flag only while the tile
     * is bound. Without it the tile rendered a static snapshot taken at bind
     * time, so it went stale during the entire connect/disconnect sequence the
     * user is watching — and [onClick] acts on the LIVE state, so tapping a
     * tile that read Disconnected over a live tunnel disconnected the VPN,
     * the opposite of the user's intent.
     *
     * VpnManager's state, not the service's (A1-002, A1-021): the service
     * reads Disconnected for the whole API phase of a dial, which let a second
     * tap start a second dial, and never learns about the manager's own
     * outcomes (Reconnecting, a backend refusal).
     */
    private var stateJob: Job? = null

    companion object {
        private const val TAG = "BirdoTile"

        /** What the tile shows: active or not, and a subtitle (a string resource, or the server's name). */
        data class TileModel(val active: Boolean, @param:StringRes val subtitle: Int, val serverLabel: String? = null)

        /**
         * The tile for a state. Blocked is INACTIVE with the truth: the kill
         * switch is not a connection, and an ACTIVE tile reading "Working…"
         * claimed a working VPN while every packet was being dropped.
         */
        fun tileModel(state: VpnState, killSwitchActive: Boolean, serverLabel: String?, showLocation: Boolean): TileModel =
            when {
                state is VpnState.Connected ->
                    TileModel(true, R.string.status_protected, serverLabel?.takeIf { showLocation })
                state is VpnState.Reconnecting -> TileModel(true, R.string.status_reconnecting)
                state.isConnectingPhase -> TileModel(true, R.string.connecting)
                state is VpnState.Disconnecting -> TileModel(true, R.string.disconnecting)
                killSwitchActive -> TileModel(false, R.string.tile_traffic_blocked)
                state is VpnState.Error -> TileModel(false, R.string.status_error)
                else -> TileModel(false, R.string.status_not_connected)
            }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        stateJob?.cancel()
        stateJob = scope.launch {
            combine(vpnManager.state, BirdoVpnService.killSwitchActiveFlow) { _, _ -> }
                .collect { withContext(Dispatchers.Main) { updateTile() } }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()

        val state = vpnManager.state.value
        Log.i(TAG, "Tile clicked — current state: $state")

        when (
            QuickToggle.decide(
                state = state,
                killSwitchActive = BirdoVpnService.killSwitchActive,
                signedIn = tokenManager.isLoggedIn(),
                vpnPermissionGranted = vpnManager.isVpnPermissionGranted(),
                consentAccepted = appPreferences.hasAcceptedCurrentConsent,
                // After a crash this tap is what started the process, and the
                // process start already resumed the session (REVIEW-AND2-004).
                joinsResume = vpnManager.claimTapForResume(),
            )
        ) {
            // Connected, connecting, reconnecting, or blocked: the tap is the
            // way out. KillSwitchActive used to fall into a branch that
            // silently ignored every tap — a device with all traffic blocked
            // and no way out of it from the shade — and a connect in flight
            // could not be cancelled here at all (A1-010).
            QuickToggle.Action.DISCONNECT -> {
                // Destructive action (drops the tunnel / clears the fail-closed
                // kill switch): from a LOCKED device, demand unlock first —
                // mirrors setAuthenticationRequired on the notification action.
                if (isLocked) {
                    unlockAndRun { performTileDisconnect() }
                } else {
                    performTileDisconnect()
                }
            }
            // Signed out, or VPN permission missing: a tile cannot show a
            // sign-in form or a permission prompt, so it hands off to the app.
            // Same shape as every other tile hand-off, so it goes through the
            // one helper that reports the dead end.
            QuickToggle.Action.OPEN_APP -> openAppOrLog("Tile connect needs sign-in or VPN permission")
            QuickToggle.Action.CONNECT -> connectFromTile()
            QuickToggle.Action.NONE -> Log.d(TAG, "Tile clicked while disconnecting or resuming, ignoring")
        }
    }

    /**
     * MULTI-HOP FIRST. The tile injects VpnManager directly and never passes
     * through the ViewModel, so the Multi-Hop guard there does not apply here:
     * tapping the tile with Multi-Hop armed built one hop while the app kept
     * drawing the entry -> exit route the user chose, the silent downgrade
     * the other entry points exist to prevent. The armed/incomplete decision
     * comes from MultiHopPolicy (Mobile-Client#336) and the entitlement rule
     * from QuickToggle, shared with the widget so the two cannot drift.
     *
     * Both dials are VpnManager.connectPreferred, as on the widget: the user's
     * last server, not "the lowest-load node anywhere" (A1-021), or the armed
     * pair through the same MultiHopPolicy decision — and a tap that started
     * this process joins the resume it began instead of minting a second peer
     * (REVIEW-AND2-004).
     */
    private fun connectFromTile() {
        val decision = MultiHopPolicy.forNewConnection(
            appPreferences.multiHopEnabled,
            appPreferences.multiHopEntryNodeId,
            appPreferences.multiHopExitNodeId,
        )
        // The persisted last-known plan, not only the 30 s cache: a one-tap
        // connect for a Multi-Hop user used to open the app every time unless
        // the app had been open in the last 30 s (REVIEW-AND-020).
        val knownPlan = repository.cachedSubscriptionOrNull()?.plan ?: appPreferences.lastKnownPlan
        when (val plan = QuickToggle.connectPlan(decision, knownPlan)) {
            is QuickToggle.ConnectPlan.OpenApp -> openAppOrLog(plan.reason)
            is QuickToggle.ConnectPlan.MultiHop -> scope.launch {
                try {
                    // Deliberately NO fallback to a single hop on failure: a
                    // single hop presented as the chosen multi-hop route is
                    // indistinguishable from success and leaks the jurisdiction
                    // the user paid to hide. Staying disconnected is the honest
                    // result -- and it is VISIBLE: VpnManager publishes every
                    // non-success as a VpnState.Error, which this tile renders,
                    // and the two jurisdiction-leak refusals report at their
                    // root as multihop_route_unconfirmed / _mismatch. This log
                    // line is the `adb logcat` convenience only (R8 strips it).
                    val r = vpnManager.connectPreferred(multiHopEntitled = true)
                    if (r is ApiResult.Error) Log.w(TAG, "Tile multi-hop failed: " + r.message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A THROW, unlike a refusal, never reaches publishError —
                    // it unwinds past VpnManager entirely, so there is no state
                    // change and no breadcrumb. Multi-hop is armed and the
                    // user's tap did nothing: staying disconnected is correct,
                    // being silent about it is not.
                    FaultReporter.report(
                        FaultReporter.PATH_CONNECT,
                        "tile_multihop_threw",
                        "Quick Settings multi-hop connect threw — the tap did nothing",
                        e,
                    )
                }
            }
            is QuickToggle.ConnectPlan.Preferred -> scope.launch {
                try {
                    vpnManager.connectPreferred(plan.multiHopEntitled)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FaultReporter.report(
                        FaultReporter.PATH_CONNECT,
                        "tile_connect_threw",
                        "Quick Settings connect threw — the tap did nothing",
                        e,
                    )
                }
            }
        }
    }

    /** Hand off to the UI when the tile cannot safely decide on its own. */
    private fun openAppOrLog(reason: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launchIntent != null) {
            openAppAndCollapse(launchIntent)
        } else {
            // The tile cannot act and cannot hand off: the user's tap is a
            // no-op with no UI anywhere to explain it. `reason` is one of this
            // file's own literals, never user or server text.
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "tile_no_launch_intent",
                "Quick Settings tile could not hand off to the app: " + reason,
            )
        }
    }

    private fun performTileDisconnect() {
        scope.launch {
            try {
                vpnManager.disconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The tunnel may still be up while the tile redraws to
                // Disconnected — the user believes they are off the VPN.
                FaultReporter.report(
                    FaultReporter.PATH_TUNNEL,
                    "tile_disconnect_threw",
                    "Quick Settings disconnect threw — the tunnel may still be up",
                    e,
                )
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val model = tileModel(
            state = vpnManager.state.value,
            killSwitchActive = BirdoVpnService.killSwitchActive,
            serverLabel = vpnManager.connectedServer.value,
            // The shade is readable from a LOCKED device and had no opt-out:
            // gate the server name on the same preference that governs the
            // notification's location line.
            showLocation = appPreferences.showLocationInNotification,
        )
        tile.state = if (model.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "BirdoVPN"
        tile.subtitle = model.serverLabel ?: getString(model.subtitle)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
        tile.updateTile()
    }

    private fun openAppAndCollapse(launchIntent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapseLegacy(launchIntent)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseLegacy(launchIntent: Intent) {
        startActivityAndCollapse(launchIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
