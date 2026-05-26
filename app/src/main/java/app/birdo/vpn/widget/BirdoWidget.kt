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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.birdo.vpn.MainActivity
import app.birdo.vpn.R

/**
 * Birdo VPN home screen widget — minimal dark card that shows VPN status.
 * Matches the app's dark glassmorphic design language.
 */
class BirdoWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences("birdo_widget", Context.MODE_PRIVATE)
        val isConnected = prefs.getBoolean("vpn_connected", false)
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
        ColorProvider(Color(0xFF22C55E))
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

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bgDrawable)
            .clickable(actionStartActivity(intent))
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
                    text = "Birdo VPN",
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
                text = if (isConnected) "Protected" else "Not connected",
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
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
            }

            if (!isConnected) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = "Tap to connect",
                    style = TextStyle(
                        color = dimText,
                        fontSize = 11.sp,
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
