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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "BirdoTile"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        val currentState = BirdoVpnService.currentState
        Log.i(TAG, "Tile clicked — current state: $currentState")

        when (currentState) {
            is VpnState.Connected -> {
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
                tile.subtitle = BirdoVpnService.connectedServer ?: "Connected"
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
            else -> {
                // Authenticating, StealthConnecting, Reconnecting, KillSwitchActive
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
