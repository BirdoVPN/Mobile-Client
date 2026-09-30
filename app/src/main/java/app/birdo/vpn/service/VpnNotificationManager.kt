package app.birdo.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import app.birdo.vpn.MainActivity
import app.birdo.vpn.R
import app.birdo.vpn.utils.FormatUtils

/**
 * Single-responsibility manager for all VPN notification construction.
 *
 * Owns the two channels (the ongoing status notification and the
 * high-importance security alerts), the foreground notification, the alerts,
 * and the post-disconnect "Not connected" notice. Keeps [BirdoVpnService]
 * focused on tunnel lifecycle.
 *
 * What each surface SAYS is decided by the pure functions in the companion
 * ([statusModel], [alertFor], [connectedBody]) so it can be unit-tested;
 * this class only turns those models into Android notifications. The words
 * follow the canonical vocabulary of the 2026-09-30 parity audit
 * (P1-parity-028): sentence case, no glyph prefixes, "via {location} · {IP}".
 */
internal class VpnNotificationManager(private val context: Context) {

    /** The tone of the ongoing notification, which picks its icon and accent. */
    enum class Tone { PROTECTED, BUSY, ERROR, IDLE }

    /** An action button on the ongoing notification. */
    enum class Action { DISCONNECT, STOP_BLOCKING, RECONNECT, CONNECT }

    data class StatusModel(
        @param:StringRes val title: Int,
        val tone: Tone,
        val actions: List<Action>,
    )

    /**
     * A high-importance alert: something stopped and the user may need to act.
     * [key] de-duplicates, so an unchanged state never re-alerts.
     */
    data class AlertModel(
        val key: String,
        @param:StringRes val title: Int,
        val body: String,
        /** Offer "Reconnect" (VpnManager.connectPreferred) as well as opening the app. */
        val reconnect: Boolean,
    )

    companion object {
        const val CHANNEL_ID = "birdo_vpn_channel"
        /**
         * Security alerts: the kill switch could not be armed, a session
         * stopped and needs the user, the VPN was turned off. IMPORTANCE_HIGH,
         * because these used to be written into the silent LOW-importance
         * status notification, where nobody would notice them (A1-027).
         */
        const val ALERT_CHANNEL_ID = "birdo_vpn_alerts"
        const val NOTIFICATION_ID = 1
        const val DISCONNECTED_NOTIFICATION_ID = 2
        const val ALERT_NOTIFICATION_ID = 3
        private const val TAG = "VpnNotif"

        /**
         * Title, tone and actions of the ongoing notification.
         *
         * "Kill Switch — all traffic blocked" wins over "Connection error"
         * and "Not connected" whenever the block is up, because it is the one
         * fact the user must not miss; the reason, if any, is the body.
         */
        fun statusModel(
            state: VpnState,
            killSwitchActive: Boolean,
            switching: Boolean,
            multiHop: Boolean,
        ): StatusModel {
            val busy = state.isConnectingPhase || state is VpnState.Reconnecting
            val blocked = killSwitchActive && state !is VpnState.Connected && !busy &&
                state !is VpnState.Disconnecting
            return when {
                state is VpnState.Connected -> StatusModel(
                    if (multiHop) R.string.notif_title_protected_multihop else R.string.notif_title_protected,
                    Tone.PROTECTED,
                    listOf(Action.DISCONNECT),
                )
                switching && (busy || state is VpnState.Disconnecting) ->
                    StatusModel(R.string.status_switching, Tone.BUSY, listOf(Action.DISCONNECT))
                state is VpnState.Reconnecting ->
                    StatusModel(R.string.status_reconnecting, Tone.BUSY, listOf(Action.DISCONNECT))
                state.isConnectingPhase ->
                    StatusModel(R.string.connecting, Tone.BUSY, listOf(Action.DISCONNECT))
                state is VpnState.Disconnecting ->
                    StatusModel(R.string.disconnecting, Tone.BUSY, emptyList())
                blocked -> StatusModel(
                    R.string.kill_switch_blocking,
                    Tone.ERROR,
                    if (state is VpnState.Error && offersReconnect(state.kind)) {
                        listOf(Action.RECONNECT, Action.STOP_BLOCKING)
                    } else {
                        listOf(Action.STOP_BLOCKING)
                    },
                )
                state is VpnState.Error -> StatusModel(
                    R.string.status_error,
                    Tone.ERROR,
                    if (offersReconnect(state.kind)) listOf(Action.RECONNECT) else emptyList(),
                )
                else -> StatusModel(R.string.status_not_connected, Tone.IDLE, listOf(Action.CONNECT))
            }
        }

        /**
         * Whether "Reconnect" can plausibly work without the user doing
         * something in the app first. A spent retry budget or a revoke: yes.
         * Signing in, updating, a plan, a permission prompt or a setting: no —
         * those need the app, which the notification's tap opens.
         */
        private fun offersReconnect(kind: FailureKind): Boolean =
            !kind.terminal || kind == FailureKind.REVOKED

        /** The body line while connected: "via {location}[ · {IP}]" (P1-parity-028). */
        fun connectedBody(location: String?, ip: String?): String? {
            val place = location?.takeIf { it.isNotBlank() }
            val address = ip?.takeIf { it.isNotBlank() }
            return when {
                place != null && address != null -> "via $place · $address"
                place != null -> "via $place"
                else -> address
            }
        }

        /**
         * The alert for [state], or null when there is nothing to raise.
         *
         * Alerts are for when the app is NOT on screen — in the app the same
         * words are already on Home, and a heads-up on top of them is noise.
         */
        fun alertFor(
            state: VpnState,
            killSwitchActive: Boolean,
            sessionExpired: Boolean,
            uiForeground: Boolean,
        ): AlertModel? {
            if (uiForeground) return null
            if (state is VpnState.Connected && sessionExpired) {
                return AlertModel("expired", R.string.notif_alert_sign_in, SessionCopy.SESSION_EXPIRED, reconnect = false)
            }
            if (state !is VpnState.Error) return null
            val title = when (state.kind) {
                FailureKind.SIGN_IN_REQUIRED -> R.string.notif_alert_sign_in
                FailureKind.UPDATE_REQUIRED -> R.string.notif_alert_update
                FailureKind.REVOKED -> R.string.notif_alert_revoked
                FailureKind.VPN_TAKEN_OVER -> R.string.notif_alert_turned_off
                FailureKind.SETUP_REQUIRED, FailureKind.VPN_PERMISSION_REQUIRED -> R.string.notif_alert_open_app
                else -> R.string.notif_alert_cant_connect
            }
            val body = if (killSwitchActive) {
                state.message + " " + SessionCopy.STILL_BLOCKED
            } else {
                state.message
            }
            return AlertModel(
                key = state.kind.name + ":" + state.message + ":" + killSwitchActive,
                title = title,
                body = body,
                reconnect = offersReconnect(state.kind),
            )
        }

        /** Posted when a system start could not bring the service back at all. */
        fun stoppedUnexpectedlyAlert(): AlertModel =
            AlertModel("stopped", R.string.notif_alert_stopped, SessionCopy.STOPPED_UNEXPECTEDLY, reconnect = false)

        @DrawableRes
        private fun iconFor(tone: Tone): Int = when (tone) {
            Tone.PROTECTED -> R.drawable.ic_notif_connected
            Tone.BUSY -> R.drawable.ic_notif_connecting
            Tone.ERROR -> R.drawable.ic_notif_error
            Tone.IDLE -> R.drawable.ic_notif_disconnected
        }

        private fun accentFor(tone: Tone): Int = when (tone) {
            Tone.PROTECTED -> 0xFF34D399.toInt() // emerald-400 — protected
            Tone.ERROR -> 0xFFEF4444.toInt()     // red
            Tone.BUSY, Tone.IDLE -> 0xFF6B7280.toInt()
        }
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ── Channels ─────────────────────────────────────────────────

    fun createChannels() {
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
        val alerts = NotificationChannel(
            ALERT_CHANNEL_ID, "Security alerts", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "When the VPN stops protecting you and needs your attention"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
        notificationManager.createNotificationChannel(alerts)
    }

    // ── Pending intents ──────────────────────────────────────────

    private fun openAppIntent(): PendingIntent {
        val openIntent = Intent()
            .setClassName(context.packageName, MainActivity::class.java.name)
            .setPackage(context.packageName)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun connectInAppIntent(): PendingIntent {
        val connectIntent = Intent(Intent.ACTION_VIEW, "birdo://connect".toUri())
            .setClassName(context.packageName, MainActivity::class.java.name)
            .setPackage(context.packageName)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 2, connectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context, requestCode,
            Intent(action)
                .setClassName(context.packageName, BirdoVpnService::class.java.name)
                .setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun NotificationCompat.Builder.addActions(actions: List<Action>): NotificationCompat.Builder {
        for (action in actions) {
            // The destructive actions (drop the tunnel / release the
            // fail-closed block) require device unlock before firing (API 31+;
            // a no-op on older releases): anyone briefly holding a locked
            // handset must not be able to strip its VPN protection from the
            // lock screen. Both go through VpnManager (A1-009), never straight
            // to ACTION_STOP.
            val built = when (action) {
                Action.DISCONNECT -> NotificationCompat.Action.Builder(
                    R.drawable.ic_notif_disconnected,
                    context.getString(R.string.disconnect),
                    serviceIntent(BirdoVpnService.ACTION_USER_DISCONNECT, 1),
                ).setAuthenticationRequired(true).build()
                // A1-030: this used to read "Disable Kill Switch" and only
                // disconnected, leaving the kill switch on. It says what it does.
                Action.STOP_BLOCKING -> NotificationCompat.Action.Builder(
                    R.drawable.ic_notif_disconnected,
                    context.getString(R.string.notif_action_stop_blocking),
                    serviceIntent(BirdoVpnService.ACTION_USER_DISCONNECT, 1),
                ).setAuthenticationRequired(true).build()
                Action.RECONNECT -> NotificationCompat.Action.Builder(
                    R.drawable.ic_notif_connected,
                    context.getString(R.string.notif_action_reconnect),
                    serviceIntent(BirdoVpnService.ACTION_USER_RECONNECT, 3),
                ).build()
                Action.CONNECT -> NotificationCompat.Action.Builder(
                    R.drawable.ic_notif_connected,
                    context.getString(R.string.connect),
                    connectInAppIntent(),
                ).build()
            }
            addAction(built)
        }
        return this
    }

    // ── Foreground notification ──────────────────────────────────

    /**
     * Build the foreground service notification.
     *
     * @param state the session state to render — VpnManager's, which owns it.
     * @param body the one-line body (the reason for an Error, "via …" while connected).
     * @param details extra lines for the expanded view while connected.
     */
    fun buildForegroundNotification(
        state: VpnState,
        body: String? = null,
        killSwitchActive: Boolean = false,
        switching: Boolean = false,
        multiHop: Boolean = false,
        connectedSince: Long = 0L,
        details: List<String> = emptyList(),
    ): Notification {
        val model = statusModel(state, killSwitchActive, switching, multiHop)
        val title = context.getString(model.title)
        val iconRes = iconFor(model.tone)
        val accentColor = accentFor(model.tone)
        val pendingOpen = openAppIntent()
        val text = body ?: (state as? VpnState.Reconnecting)?.let {
            if (it.waitingForNetwork) {
                context.getString(R.string.notif_body_waiting_network)
            } else {
                context.getString(R.string.notif_body_attempt, it.attempt)
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(iconRes)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // PRIVATE + a bland public version: on the lock screen the OS shows
            // only the title-level state, never the server name / IP / duration
            // that the body can carry. Full detail stays in the post-unlock shade.
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
            .addActions(model.actions)

        // Chronometer for connected state: the duration is rendered natively,
        // so the body does not need to carry it.
        if (state is VpnState.Connected && connectedSince > 0) {
            builder.setUsesChronometer(true)
            builder.setWhen(connectedSince)
            builder.setShowWhen(true)
        }

        if (state is VpnState.Connected && details.isNotEmpty()) {
            val bigText = (listOfNotNull(text) + details).joinToString("\n")
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        } else if (state is VpnState.Error && text != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }

        return builder.build()
    }

    /** Expanded-view lines while connected, in plain words (no arrows or glyphs). */
    fun connectedDetails(
        rxBytes: Long,
        txBytes: Long,
        stealthActive: Boolean,
        quantumActive: Boolean,
        killSwitchEnabled: Boolean,
        splitTunnelAppCount: Int,
    ): List<String> = buildList {
        if (rxBytes > 0 || txBytes > 0) {
            add(
                context.getString(
                    R.string.notif_detail_traffic,
                    FormatUtils.formatBytes(rxBytes),
                    FormatUtils.formatBytes(txBytes),
                ),
            )
        }
        if (stealthActive) add(context.getString(R.string.notif_detail_stealth))
        if (quantumActive) add(context.getString(R.string.notif_detail_quantum))
        if (killSwitchEnabled) add(context.getString(R.string.notif_detail_kill_switch))
        if (splitTunnelAppCount > 0) {
            add(
                context.resources.getQuantityString(
                    R.plurals.settings_apps_bypassing, splitTunnelAppCount, splitTunnelAppCount,
                ),
            )
        }
    }

    // ── Alerts ───────────────────────────────────────────────────

    fun postAlert(alert: AlertModel) {
        try {
            val title = context.getString(alert.title)
            val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_error)
                .setContentTitle(title)
                .setContentText(alert.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notif_error)
                        .setContentTitle(title)
                        .build()
                )
                .setColor(0xFFEF4444.toInt())
            if (alert.reconnect) builder.addActions(listOf(Action.RECONNECT))
            notificationManager.notify(ALERT_NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post alert", e)
        }
    }

    fun cancelAlert() {
        notificationManager.cancel(ALERT_NOTIFICATION_ID)
    }

    // ── Post-disconnect notification ─────────────────────────────

    /**
     * Post a standalone "Not connected" notice that persists after the
     * foreground service is torn down, with a quick "Connect" action. Only
     * for a stop the user did not ask for, and only while the Notifications
     * setting is on (the caller decides both).
     */
    fun postDisconnectedNotification() {
        try {
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_disconnected)
                .setContentTitle(context.getString(R.string.status_not_connected))
                .setContentText(context.getString(R.string.notif_disconnected_body))
                .setContentIntent(openAppIntent())
                .setSilent(true)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(0xFF6B7280.toInt())
                .setColorized(true)
                .addActions(listOf(Action.CONNECT))
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
}
