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
                scope.launch {
                    try {
                        vpnManager.disconnect()
                        withContext(Dispatchers.Main) { updateTile() }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to disconnect via tile", e)
                    }
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

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = BirdoVpnService.currentState

        when (state) {
            is VpnState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Birdo VPN"
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
                tile.label = "Birdo VPN"
                tile.subtitle = if (state is VpnState.Connecting) "Connecting…" else "Disconnecting…"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
            is VpnState.Disconnected, is VpnState.Error -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Birdo VPN"
                tile.subtitle = "Disconnected"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
            // The kill switch is not a connection. It rendered as an ACTIVE tile
            // reading "Working\u2026", which claims a working VPN while every packet
            // on the device is being dropped. INACTIVE + the truth.
            is VpnState.KillSwitchActive -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Birdo VPN"
                tile.subtitle = "Traffic blocked"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
            }
            else -> {
                // Authenticating, StealthConnecting, Reconnecting
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Birdo VPN"
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
