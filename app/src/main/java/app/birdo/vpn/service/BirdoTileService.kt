package app.birdo.vpn.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import app.birdo.vpn.R
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.repository.ApiResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
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
     * Collects [BirdoVpnService.stateFlow] only while the tile is bound.
     * Without it the tile rendered a static snapshot taken at bind time, so it
     * went stale during the entire connect/disconnect sequence the user is
     * watching, and after a drop or an Adaptive Transport fallback it could
     * read "Disconnected" over a live tunnel — and [onClick] acts on the LIVE
     * state, so tapping a tile that reads Disconnected disconnected the VPN,
     * the opposite of the user's intent.
     */
    private var stateJob: Job? = null

    companion object {
        private const val TAG = "BirdoTile"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        stateJob?.cancel()
        stateJob = scope.launch {
            BirdoVpnService.stateFlow.collect {
                withContext(Dispatchers.Main) { updateTile() }
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()

        val currentState = BirdoVpnService.currentState
        Log.i(TAG, "Tile clicked — current state: $currentState")

        when (currentState) {
            // KillSwitchActive is a tappable state: all traffic is blocked and
            // disconnect() is what releases the block. It used to fall into the
            // else branch, which rendered an ACTIVE tile that silently ignored
            // every tap — a device with all traffic blocked and no way out of it
            // from the shade.
            is VpnState.Connected, is VpnState.KillSwitchActive -> {
                // Destructive action (drops the tunnel / clears the fail-closed
                // kill switch): from a LOCKED device, demand unlock first —
                // mirrors setAuthenticationRequired on the notification action.
                if (isLocked) {
                    unlockAndRun { performTileDisconnect() }
                } else {
                    performTileDisconnect()
                }
            }
            is VpnState.Disconnected, is VpnState.Error -> {
                // Guard: don't attempt VPN if user isn't authenticated
                if (!tokenManager.isLoggedIn()) {
                    Log.w(TAG, "Tile connect blocked — user not authenticated")
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (launchIntent != null) {
                        openAppAndCollapse(launchIntent)
                    }
                    return
                }

                // Need to check VPN permission
                if (!vpnManager.isVpnPermissionGranted()) {
                    // Can't request permission from tile — open app instead
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (launchIntent == null) {
                        Log.e(TAG, "Failed to get launch intent for $packageName")
                        return
                    }
                    openAppAndCollapse(launchIntent)
                    return
                }

                // MULTI-HOP FIRST. vpnManager.quickConnect() builds a
                // SINGLE-HOP tunnel unconditionally. VpnViewModel.quickConnect()
                // guards against that and its comment names this tile as a
                // caller it protects -- but the tile injects VpnManager directly
                // and never passes through the ViewModel, so the guard did not
                // apply here. Tapping the tile with Multi-Hop armed therefore
                // built one hop while the app kept drawing the entry -> exit
                // route the user chose, which is the silent downgrade the other
                // entry points exist to prevent.
                if (appPreferences.multiHopEnabled) {
                    val entry = appPreferences.multiHopEntryNodeId
                    val exit = appPreferences.multiHopExitNodeId
                    // The pref alone is NOT the armed state. HomeScreen renders
                    // `multiHop.enabled && isSovereign`, and nothing clears the
                    // pref when a plan lapses -- so an ex-SOVEREIGN account keeps
                    // multiHopEnabled == true forever. Dialling multi-hop for
                    // them means the backend refuses it, VpnManager sets its own
                    // Error state, and BirdoVpnService.currentState (the only
                    // thing updateTile reads) never moves: a tile that connects
                    // nothing and says nothing, every tap. Single-hop is both what
                    // they are entitled to and what the app already draws, so
                    // there is no downgrade being hidden.
                    val plan = repository.cachedSubscriptionOrNull()?.plan
                    when {
                        // Entitlement unknown (cold process, or the cache aged
                        // out). Refuse to guess: guessing single-hop can silently
                        // downgrade a paying user, guessing multi-hop can dead-end
                        // a lapsed one. Hand off to the UI, which has a live
                        // subscription and somewhere to show an error -- the same
                        // escape hatch this branch already uses for permission.
                        plan == null -> {
                            openAppOrLog("Multi-hop armed but entitlement unknown")
                            return
                        }
                        // Not entitled: a single hop is correct AND is what the
                        // app displays for this account, so fall through to it.
                        !plan.equals("SOVEREIGN", ignoreCase = true) -> {}
                        // Armed but incomplete (a node was retired, or prefs are
                        // half-written). Quietly substituting a single hop is the
                        // failure this branch exists to prevent.
                        entry.isNullOrBlank() || exit.isNullOrBlank() -> {
                            openAppOrLog("Multi-hop armed but entry/exit incomplete")
                            return
                        }
                        else -> {
                            scope.launch {
                                try {
                                    // Deliberately NO fallback to quickConnect()
                                    // on failure: a single hop presented as the
                                    // chosen multi-hop route is indistinguishable
                                    // from success and leaks the jurisdiction the
                                    // user paid to hide. Staying disconnected is
                                    // the honest result -- but it must be VISIBLE,
                                    // so the returned ApiResult is logged rather
                                    // than discarded. connectMultiHop RETURNS an
                                    // error, it does not throw, so the catch below
                                    // would never have fired on a refusal.
                                    when (val r = vpnManager.connectMultiHop(entry, exit)) {
                                        is ApiResult.Success ->
                                            if (!r.data.success) {
                                                Log.e(TAG, "Tile multi-hop refused: " + r.data.message)
                                            }
                                        is ApiResult.Error ->
                                            Log.e(TAG, "Tile multi-hop failed: " + r.message)
                                    }
                                    withContext(Dispatchers.Main) { updateTile() }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to connect multi-hop via tile", e)
                                }
                            }
                            return
                        }
                    }
                }

                scope.launch {
                    try {
                        vpnManager.quickConnect()
                        withContext(Dispatchers.Main) { updateTile() }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to connect via tile", e)
                    }
                }
            }
            else -> {
                // Connecting/Disconnecting — do nothing
                Log.d(TAG, "Tile clicked during transition, ignoring")
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
            Log.e(TAG, reason + ", and no launch intent is available")
        }
    }

    private fun performTileDisconnect() {
        scope.launch {
            try {
                vpnManager.disconnect()
                withContext(Dispatchers.Main) { updateTile() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disconnect via tile", e)
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = BirdoVpnService.currentState

        when (state) {
            is VpnState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "BirdoVPN"
                // The shade is readable from a LOCKED device and had no opt-out:
                // gate the exit-node name on the same preference that governs
                // the notification's location line.
                tile.subtitle =
                    if (appPreferences.showLocationInNotification) {
                        BirdoVpnService.connectedServer ?: "Connected"
                    } else {
                        "Connected"
                    }
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
            is VpnState.Connecting, is VpnState.Disconnecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "BirdoVPN"
                tile.subtitle = if (state is VpnState.Connecting) "Connecting…" else "Disconnecting…"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
            is VpnState.Disconnected, is VpnState.Error -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "BirdoVPN"
                tile.subtitle = "Disconnected"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
            // The kill switch is not a connection. It rendered as an ACTIVE tile
            // reading "Working\u2026", which claims a working VPN while every packet
            // on the device is being dropped. INACTIVE + the truth.
            is VpnState.KillSwitchActive -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "BirdoVPN"
                tile.subtitle = "Traffic blocked"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
            else -> {
                // Authenticating, StealthConnecting, Reconnecting
                tile.state = Tile.STATE_ACTIVE
                tile.label = "BirdoVPN"
                tile.subtitle = "Working\u2026"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
        }

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
