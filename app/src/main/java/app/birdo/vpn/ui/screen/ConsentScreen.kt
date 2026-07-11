package app.birdo.vpn.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.R
import app.birdo.vpn.ui.TestTags
import app.birdo.vpn.ui.theme.*

/**
 * GDPR-compliant consent screen shown on first launch.
 * User must accept the privacy policy before proceeding.
 */
@Composable
fun ConsentScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Shield icon
        Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = stringResource(R.string.cd_privacy),
            tint = BirdoAccent,
            modifier = Modifier.size(64.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.consent_title),
            color = BirdoWhite,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.consent_subtitle),
            color = BirdoWhite60,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Data processing summary card
        Surface(
            color = BirdoSurfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                DataItem(
                    title = stringResource(R.string.consent_no_logs_title),
                    description = stringResource(R.string.consent_no_logs_desc),
                )
                Spacer(modifier = Modifier.height(16.dp))
                DataItem(
                    title = stringResource(R.string.consent_minimal_data_title),
                    description = stringResource(R.string.consent_minimal_data_desc),
                )
                Spacer(modifier = Modifier.height(16.dp))
                DataItem(
                    title = stringResource(R.string.consent_crash_reports_title),
                    description = stringResource(R.string.consent_crash_reports_desc),
                )
                Spacer(modifier = Modifier.height(16.dp))
                DataItem(
                    title = stringResource(R.string.consent_no_data_sales_title),
                    description = stringResource(R.string.consent_no_data_sales_desc),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Privacy policy link
        TextButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://birdo.app/privacy"))
                context.startActivity(intent)
            },
        ) {
            Text(
                text = stringResource(R.string.consent_read_privacy_policy),
                color = BirdoAccent,
                fontSize = 14.sp,
                textDecoration = TextDecoration.Underline,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Accept button
        Button(
            onClick = onAccept,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag(TestTags.CONSENT_ACCEPT),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BirdoAccent),
        ) {
            Text(
                text = stringResource(R.string.consent_agree),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = BirdoWhite,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Decline button
        OutlinedButton(
            onClick = onDecline,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag(TestTags.CONSENT_DECLINE),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = BirdoWhite40),
        ) {
            Text(
                text = stringResource(R.string.consent_decline),
                fontSize = 14.sp,
                color = BirdoWhite40,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.consent_required_notice),
            color = BirdoWhite20,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun DataItem(title: String, description: String) {
    Column {
        Text(
            text = title,
            color = BirdoWhite,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            color = BirdoWhite60,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}
