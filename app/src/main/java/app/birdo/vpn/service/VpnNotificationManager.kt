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
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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

        val iconRes = when (state) {
            is VpnState.Connected -> R.drawable.ic_notif_connected
            is VpnState.Connecting -> R.drawable.ic_notif_connecting
            is VpnState.Error -> R.drawable.ic_notif_error
            else -> R.drawable.ic_notif_disconnected
        }

        val title = when (state) {
            is VpnState.Connected -> "● Birdo VPN — Protected"
            is VpnState.Connecting -> "◌ Birdo VPN — Connecting…"
            is VpnState.Disconnecting -> "◌ Birdo VPN — Disconnecting…"
            is VpnState.Error -> "✕ Birdo VPN — Connection Error"
            else -> if (killSwitchActive) "● Birdo VPN — Kill Switch Active"
                    else "○ Birdo VPN — Not Protected"
        }

        val accentColor = when (state) {
            is VpnState.Connected -> 0xFF22C55E.toInt()  // Green
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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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

        // Action buttons
        when {
            state is VpnState.Connected || state is VpnState.Connecting -> {
                builder.addAction(R.drawable.ic_notif_disconnected, "Disconnect", stopPendingIntent)
            }
            killSwitchActive -> {
                builder.addAction(R.drawable.ic_notif_disconnected, "Disable Kill Switch", stopPendingIntent)
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
                .setContentTitle("○ Birdo VPN — Not Protected")
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
