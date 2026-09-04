package app.birdo.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import app.birdo.vpn.MainActivity
import app.birdo.vpn.R
import app.birdo.vpn.utils.FormatUtils

/**
 * Single-responsibility manager for all VPN notification construction.
 *
 * Owns the notification channel, foreground notification, and the
 * post-disconnect "not protected" notification. Keeps [BirdoVpnService]
 * focused on tunnel lifecycle.
 */
internal class VpnNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "birdo_vpn_channel"
        const val NOTIFICATION_ID = 1
        const val DISCONNECTED_NOTIFICATION_ID = 2
        private const val TAG = "VpnNotif"
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ── Channel ──────────────────────────────────────────────────

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notification while VPN is active"
            setShowBadge(false)
            // PRIVATE: the lock screen shows only the redacted public version
            // (see buildForegroundNotification.setPublicVersion) — the exit-node
            // name / server IP must not be readable from a locked handset at a
            // border check or over a shoulder. NOTE: channel settings are
            // immutable after creation, so existing installs keep PUBLIC at the
            // channel level; the per-notification visibility below still applies.
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
    }

    // ── Foreground notification ──────────────────────────────────

    /**
     * Build the foreground service notification.
     *
     * @param status  One-line status text (body of the notification).
     * @param state   Current [VpnState] — drives icon, title, accent colour, actions.
     * @param extras  Optional extra details for `BigTextStyle` expansion.
     */
    fun buildForegroundNotification(
        status: String,
        state: VpnState = VpnState.Disconnected,
        connectedSince: Long = 0L,
        killSwitchActive: Boolean = false,
        killSwitchEnabled: Boolean = false,
        splitTunnelingEnabled: Boolean = false,
        splitTunnelAppCount: Int = 0,
        rxBytes: Long = 0L,
        txBytes: Long = 0L,
    ): Notification {
        val openIntent = Intent()
            .setClassName(context.packageName, MainActivity::class.java.name)
            .setPackage(context.packageName)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingOpen = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPendingIntent = PendingIntent.getService(
            context, 1,
            Intent(BirdoVpnService.ACTION_STOP)
                .setClassName(context.packageName, BirdoVpnService::class.java.name)
                .setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val connectIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("birdo://connect"))
            .setClassName(context.packageName, MainActivity::class.java.name)
            .setPackage(context.packageName)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val connectPendingIntent = PendingIntent.getActivity(
            context, 2, connectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Subjectless `when` + [isConnectingPhase], not an inline
        // `is VpnState.Connecting`. The inline form missed
        // [VpnState.StealthConnecting], which BirdoVpnService publishes for the
        // WHOLE stealth setup (Xray start, Rosenpass exchange, WgNative turnOn,
        // establish) — and updateNotification passes currentState straight in.
        // So for those seconds the ongoing notification showed the disconnected
        // icon and "Not Protected" over a connect that was very much in flight.
        // Same stale-enumeration shape isConnectingPhase was introduced to end;
        // this file was the call site it missed. See its kdoc.
        val iconRes = when {
            state is VpnState.Connected -> R.drawable.ic_notif_connected
            state.isConnectingPhase -> R.drawable.ic_notif_connecting
            state is VpnState.Error -> R.drawable.ic_notif_error
            else -> R.drawable.ic_notif_disconnected
        }

        val title = when {
            state is VpnState.Connected -> "● BirdoVPN — Protected"
            state.isConnectingPhase -> "◌ BirdoVPN — Connecting…"
            state is VpnState.Disconnecting -> "◌ BirdoVPN — Disconnecting…"
            state is VpnState.Error -> "✕ BirdoVPN — Connection Error"
            else -> if (killSwitchActive) "● BirdoVPN — Kill Switch Active"
                    else "○ BirdoVPN — Not Protected"
        }

        val accentColor = when (state) {
            is VpnState.Connected -> 0xFF34D399.toInt()  // emerald-400 — protected
            is VpnState.Error     -> 0xFFEF4444.toInt()  // Red
            else                  -> 0xFF6B7280.toInt()  // Gray
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(status)
            .setSmallIcon(iconRes)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // PRIVATE + a bland public version: on the lock screen the OS shows
            // only the title-level state, never the server name / IP / duration
            // that the status line can carry. Full detail stays in the
            // post-unlock shade.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setSmallIcon(iconRes)
                    .setContentIntent(pendingOpen)
                    .setOngoing(true)
                    .setSilent(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setShowWhen(false)
                    .setColor(accentColor)
                    .build()
            )
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setColor(accentColor)
            .setColorized(true)

        // Chronometer for connected state
        if (state is VpnState.Connected && connectedSince > 0) {
            builder.setUsesChronometer(true)
            builder.setWhen(connectedSince)
            builder.setShowWhen(true)
        }

        // Expanded view — multi-line info
        if (state is VpnState.Connected) {
            val bigText = buildString {
                append(status)
                if (rxBytes > 0 || txBytes > 0) {
                    append("\n↓ ${formatBytes(rxBytes)}  ↑ ${formatBytes(txBytes)}")
                }
                if (killSwitchEnabled) append("\nKill Switch enabled")
                if (splitTunnelingEnabled && splitTunnelAppCount > 0) {
                    append("\n$splitTunnelAppCount apps bypassing VPN")
                }
            }
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }
        if (state is VpnState.Error) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(status))
        }

        // Action buttons. The two destructive actions (drop the tunnel /
        // clear the fail-closed kill switch) require device unlock before
        // firing (API 31+; a no-op on older releases): anyone briefly holding
        // a locked handset must not be able to strip its VPN protection from
        // the lock screen.
        val disconnectAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notif_disconnected, "Disconnect", stopPendingIntent,
        ).setAuthenticationRequired(true).build()
        val disableKillSwitchAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notif_disconnected, "Disable Kill Switch", stopPendingIntent,
        ).setAuthenticationRequired(true).build()
        when {
            state is VpnState.Connected || state.isConnectingPhase -> {
                builder.addAction(disconnectAction)
            }
            killSwitchActive -> {
                builder.addAction(disableKillSwitchAction)
            }
            state is VpnState.Disconnected || state is VpnState.Error -> {
                builder.addAction(R.drawable.ic_notif_connected, "Connect", connectPendingIntent)
            }
        }

        return builder.build()
    }

    // ── Post-disconnect notification ─────────────────────────────

    /**
     * Post a standalone "Not Protected" notification that persists after the
     * foreground service is torn down, with a quick "Connect" action.
     */
    fun postDisconnectedNotification() {
        try {
            val openIntent = Intent()
                .setClassName(context.packageName, MainActivity::class.java.name)
                .setPackage(context.packageName)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingOpen = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val connectIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("birdo://connect"))
                .setClassName(context.packageName, MainActivity::class.java.name)
                .setPackage(context.packageName)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val connectPending = PendingIntent.getActivity(
                context, 2, connectIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_disconnected)
                .setContentTitle("○ BirdoVPN — Not Protected")
                .setContentText("Tap Connect to protect your connection")
                .setContentIntent(pendingOpen)
                .setSilent(true)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(0xFF6B7280.toInt())
                .setColorized(true)
                .addAction(R.drawable.ic_notif_connected, "Connect", connectPending)
                .build()

            notificationManager.notify(DISCONNECTED_NOTIFICATION_ID, notif)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post disconnected notification", e)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    fun update(notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cancelDisconnected() {
        notificationManager.cancel(DISCONNECTED_NOTIFICATION_ID)
    }

    /** Format bytes into human-readable string. Delegates to shared [FormatUtils]. */
    fun formatBytes(bytes: Long): String = FormatUtils.formatBytes(bytes)
}
