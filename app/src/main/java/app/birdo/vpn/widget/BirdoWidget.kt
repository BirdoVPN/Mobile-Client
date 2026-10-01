package app.birdo.vpn.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.birdo.vpn.MainActivity
import app.birdo.vpn.R
import app.birdo.vpn.service.BirdoVpnService
import app.birdo.vpn.service.MultiHopPolicy
import app.birdo.vpn.service.QuickToggle
import app.birdo.vpn.service.VpnManagerEntryPoint
import app.birdo.vpn.service.VpnState
import app.birdo.vpn.service.isConnectingPhase
import app.birdo.vpn.utils.FaultReporter
import dagger.hilt.android.EntryPointAccessors

/**
 * Birdo VPN home screen widget — minimal dark card that shows VPN status.
 * Matches the app's dark glassmorphic design language.
 */
class BirdoWidget : GlanceAppWidget() {

    /** The widget's rendering of a session state. */
    enum class Look { PROTECTED, BUSY, BLOCKED, ERROR, IDLE }

    data class WidgetModel(
        val look: Look,
        @param:StringRes val status: Int,
        /** What a tap does, said in words; null when the status says it. */
        @param:StringRes val hint: Int?,
    )

    companion object {
        /**
         * The widget for a state. It used to be binary — "Protected" or "Not
         * connected — Tap to connect" — so a device with every packet blocked
         * read as one inviting a connect (A1-044). The live state is
         * VpnManager's, read in-process, so a fresh process after a reboot or a
         * force-stop renders what is true now, never a stale persisted claim.
         */
        fun widgetModel(state: VpnState, killSwitchActive: Boolean): WidgetModel = when {
            state is VpnState.Connected -> WidgetModel(Look.PROTECTED, R.string.status_protected, R.string.widget_tap_to_disconnect)
            state is VpnState.Reconnecting -> WidgetModel(Look.BUSY, R.string.status_reconnecting, R.string.widget_tap_to_cancel)
            state.isConnectingPhase -> WidgetModel(Look.BUSY, R.string.connecting, R.string.widget_tap_to_cancel)
            state is VpnState.Disconnecting -> WidgetModel(Look.BUSY, R.string.disconnecting, null)
            killSwitchActive -> WidgetModel(Look.BLOCKED, R.string.tile_traffic_blocked, R.string.widget_tap_to_stop_blocking)
            state is VpnState.Error -> WidgetModel(Look.ERROR, R.string.status_error, R.string.widget_tap_to_connect)
            else -> WidgetModel(Look.IDLE, R.string.status_not_connected, R.string.widget_tap_to_connect)
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences("birdo_widget", Context.MODE_PRIVATE)
        val manager = try {
            EntryPointAccessors.fromApplication(context.applicationContext, VpnManagerEntryPoint::class.java).vpnManager()
        } catch (e: Exception) {
            Log.w("BirdoWidget", "VpnManager unavailable to the widget", e)
            null
        }
        val state = manager?.state?.value ?: BirdoVpnService.currentState
        val model = widgetModel(state, BirdoVpnService.killSwitchActive)
        // The server name only while connected: the persisted value outlives
        // the session and must never be drawn next to anything but "Protected".
        val serverName = if (state is VpnState.Connected) {
            manager?.connectedServer?.value ?: prefs.getString("server_name", null)
        } else {
            null
        }

        provideContent {
            BirdoWidgetContent(model = model, serverName = serverName)
        }
    }
}

/**
 * A2-014: the widget said "Tap to connect" and a tap only opened the app.
 * Now it is the toggle it claims to be, through the same decision as the
 * Quick Settings tile ([QuickToggle] + [MultiHopPolicy]): connect or
 * disconnect in place when the app can do that without the user, otherwise
 * open the app — signed out, no VPN permission yet, or a Multi-Hop
 * entitlement it cannot see. A widget tap is a user interaction, which is on
 * Android's list of exemptions for starting a foreground service.
 *
 * The dial is handed to VpnManager fire-and-forget: this runs inside a
 * broadcast, which must not wait out an API call.
 */
class WidgetToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                VpnManagerEntryPoint::class.java,
            )
            val manager = entryPoint.vpnManager()
            val prefs = app.birdo.vpn.data.preferences.AppPreferences(context.applicationContext)
            val action = QuickToggle.decide(
                state = manager.state.value,
                killSwitchActive = BirdoVpnService.killSwitchActive,
                signedIn = entryPoint.tokenManager().isLoggedIn(),
                vpnPermissionGranted = manager.isVpnPermissionGranted(),
                consentAccepted = prefs.hasAcceptedCurrentConsent,
            )
            when (action) {
                QuickToggle.Action.DISCONNECT -> manager.requestDisconnect()
                QuickToggle.Action.OPEN_APP -> openApp(context)
                QuickToggle.Action.NONE -> Unit
                QuickToggle.Action.CONNECT -> {
                    val decision = MultiHopPolicy.forNewConnection(
                        prefs.multiHopEnabled,
                        prefs.multiHopEntryNodeId,
                        prefs.multiHopExitNodeId,
                    )
                    val knownPlan = entryPoint.repository().cachedSubscriptionOrNull()?.plan ?: prefs.lastKnownPlan
                    when (val plan = QuickToggle.connectPlan(decision, knownPlan)) {
                        is QuickToggle.ConnectPlan.OpenApp -> openApp(context)
                        // connectPreferred dials the armed pair through the
                        // same MultiHopPolicy decision, so the entitled
                        // multi-hop case and the single hop are one call.
                        is QuickToggle.ConnectPlan.MultiHop -> manager.requestConnectPreferred(multiHopEntitled = true)
                        is QuickToggle.ConnectPlan.Preferred -> manager.requestConnectPreferred(plan.multiHopEntitled)
                    }
                }
            }
            BirdoWidget().update(context, glanceId)
        } catch (e: Exception) {
            // The user tapped and nothing happened; with no UI to say so, the
            // operator must hear it.
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "widget_toggle_threw",
                "Home-screen widget toggle threw — the tap did nothing",
                e,
            )
            openApp(context)
        }
    }

    private fun openApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = "birdo://connect".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }
}

@Composable
private fun BirdoWidgetContent(
    model: BirdoWidget.WidgetModel,
    serverName: String?,
) {
    val primaryText = ColorProvider(Color(0xFFF2F2F2))
    val dimText = ColorProvider(Color(0x99FFFFFF))
    // Muted status colours: green good, amber busy, red blocked or failed.
    val accentColor = when (model.look) {
        BirdoWidget.Look.PROTECTED -> ColorProvider(Color(0xFF34D399)) // emerald-400 — protected
        BirdoWidget.Look.BUSY -> ColorProvider(Color(0xFFFBBF24))
        BirdoWidget.Look.BLOCKED, BirdoWidget.Look.ERROR -> ColorProvider(Color(0xFFEF4444))
        BirdoWidget.Look.IDLE -> ColorProvider(Color(0xFF6B7280))
    }

    // Pixel canvas background with state-based visuals
    val bgDrawable = ImageProvider(
        if (model.look == BirdoWidget.Look.PROTECTED) R.drawable.widget_bg_connected
        else R.drawable.widget_bg_disconnected,
    )

    val context = LocalContext.current
    // Without this the whole widget is an unlabeled clickable surface: TalkBack
    // reads the Texts but never says it is actionable or what a tap does.
    val widgetDescription = if (model.look == BirdoWidget.Look.PROTECTED) {
        context.getString(R.string.cd_widget_connected, serverName ?: context.getString(R.string.app_name))
    } else {
        context.getString(
            R.string.cd_widget_state,
            context.getString(model.status),
            model.hint?.let { context.getString(it) }.orEmpty(),
        ).trim()
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bgDrawable)
            .clickable(actionRunCallback<WidgetToggleAction>())
            .semantics { contentDescription = widgetDescription }
            .padding(16.dp),
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            // Top row: App name + status indicator dot
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                // Status dot
                Box(
                    modifier = GlanceModifier
                        .size(8.dp)
                        .cornerRadius(4.dp)
                        .background(accentColor),
                ) {}

                Spacer(modifier = GlanceModifier.width(8.dp))

                Text(
                    text = context.getString(R.string.app_name),
                    style = TextStyle(
                        color = primaryText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            // Status text
            Text(
                text = context.getString(model.status),
                style = TextStyle(
                    color = accentColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )

            // Server name (connected only)
            if (serverName != null) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = serverName,
                    style = TextStyle(
                        color = dimText,
                        fontSize = 12.sp,
                    ),
                    maxLines = 1,
                )
            }

            if (model.hint != null) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = context.getString(model.hint),
                    style = TextStyle(
                        color = dimText,
                        fontSize = 12.sp,
                    ),
                )
            }
        }
    }
}

/**
 * Receiver that Android OS calls to create/update the widget.
 */
class BirdoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BirdoWidget()
}
