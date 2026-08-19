package app.birdo.vpn.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.action.actionStartActivity
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
import app.birdo.vpn.service.VpnState

/**
 * Birdo VPN home screen widget — minimal dark card that shows VPN status.
 * Matches the app's dark glassmorphic design language.
 */
class BirdoWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences("birdo_widget", Context.MODE_PRIVATE)
        // The persisted flag alone is a STICKY claim of protection: it survives
        // process death, so after a reboot or a force-stop the widget kept
        // rendering an emerald "Protected" for a VPN that was no longer running,
        // and only self-healed on the next explicit connect/disconnect. A
        // security product's most glanceable surface must not assert protection
        // that is not in place.
        //
        // AND it with the service's live state. The widget renders in the app
        // process, so a fresh process (reboot, force-stop, OOM kill) reads
        // Disconnected and the widget goes grey with no receiver to register and
        // no flag to reset. The pref stays as the "we intended to be connected"
        // half so a widget refresh during teardown cannot flash green.
        val isConnected = prefs.getBoolean("vpn_connected", false) &&
            BirdoVpnService.currentState is VpnState.Connected
        val serverName = prefs.getString("server_name", null)

        provideContent {
            BirdoWidgetContent(
                isConnected = isConnected,
                serverName = serverName,
            )
        }
    }
}

@Composable
private fun BirdoWidgetContent(
    isConnected: Boolean,
    serverName: String?,
) {
    val primaryText = ColorProvider(Color(0xFFF2F2F2))
    val dimText = ColorProvider(Color(0x99FFFFFF))
    val accentColor = if (isConnected) {
        ColorProvider(Color(0xFF34D399)) // emerald-400 — protected
    } else {
        ColorProvider(Color(0xFF6B7280))
    }

    // Pixel canvas background with state-based visuals
    val bgDrawable = ImageProvider(
        if (isConnected) R.drawable.widget_bg_connected
        else R.drawable.widget_bg_disconnected,
    )

    val intent = Intent(LocalContext.current, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = android.net.Uri.parse("birdo://connect")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    val context = LocalContext.current
    // Without this the whole widget is an unlabeled clickable surface: TalkBack
    // reads the three Texts but never says it is actionable or what a tap does.
    val widgetDescription = if (isConnected) {
        context.getString(R.string.cd_widget_connected, serverName ?: context.getString(R.string.app_name))
    } else {
        context.getString(R.string.cd_widget_disconnected)
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bgDrawable)
            .clickable(actionStartActivity(intent))
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
                    text = LocalContext.current.getString(R.string.app_name),
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
                text = LocalContext.current.getString(
                    if (isConnected) R.string.status_protected else R.string.status_not_connected,
                ),
                style = TextStyle(
                    color = accentColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )

            // Server name (connected only)
            if (isConnected && serverName != null) {
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

            if (!isConnected) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = LocalContext.current.getString(R.string.widget_tap_to_connect),
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
