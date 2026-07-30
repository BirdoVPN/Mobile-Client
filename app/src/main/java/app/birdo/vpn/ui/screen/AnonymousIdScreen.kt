package app.birdo.vpn.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.ui.components.BirdoButton
import app.birdo.vpn.ui.components.BirdoCard
import app.birdo.vpn.ui.theme.BirdoBrand
import app.birdo.vpn.ui.theme.BirdoColors

/**
 * Shown EXACTLY ONCE, immediately after an anonymous account is minted, and it
 * cannot be dismissed without confirming.
 *
 * The 24-digit account number is the account's ONLY credential: there is no
 * email, no password reset, and the server never reveals it again. Android used
 * to discard it (the register response was checked for `ok` and nothing else)
 * and drop the user straight on Home, so every anonymous account created here
 * became permanently unrecoverable the moment the app lost its tokens. iOS and
 * desktop both gate on acknowledgement — this is the Android half of that.
 *
 * The confirm button stays disabled until the checkbox is ticked, so the number
 * cannot be skipped past by reflex.
 */
@Composable
fun AnonymousIdScreen(
    anonymousId: String,
    onAcknowledge: () -> Unit,
) {
    val palette = BirdoColors.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Save your account number",
            color = palette.onBackground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This is the only way to sign back in. There is no email and no " +
                "password reset — if you lose it, the account is gone for good.",
            color = palette.onSurfaceMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))

        BirdoCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 22.dp,
            surface = palette.surface,
            border = BirdoBrand.GlassStrokeGradient,
            contentPadding = PaddingValues(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Same copyable row the Profile tab uses, so the number looks the
                // same everywhere the user meets it.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surfaceRaised)
                        .clickable(role = Role.Button) {
                            clipboard.setText(AnnotatedString(anonymousId))
                            Toast
                                .makeText(context, "Account number copied", Toast.LENGTH_SHORT)
                                .show()
                        }
                        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ACCOUNT NUMBER",
                            color = palette.onSurfaceFaint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        // Grouped in fours: a 24-digit run is unreadable, and this
                        // is a number people transcribe by hand.
                        Text(
                            text = anonymousId.chunked(4).joinToString(" "),
                            color = palette.onBackground,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy account number",
                        tint = palette.accent,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(18.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Checkbox) { saved = !saved }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = saved,
                        onCheckedChange = { saved = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = palette.accent,
                            uncheckedColor = palette.onSurfaceFaint,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "I've saved my account number somewhere safe",
                        color = palette.onBackground,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        BirdoButton(
            text = "Continue",
            onClick = onAcknowledge,
            modifier = Modifier.fillMaxWidth(),
            enabled = saved,
        )
    }
}
