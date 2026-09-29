package app.birdo.vpn.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import app.birdo.vpn.R
import app.birdo.vpn.ui.TestTags
import app.birdo.vpn.ui.theme.*

/**
 * GDPR-compliant consent screen shown on first launch, and again whenever
 * what it says changes materially (AppPreferences.CURRENT_CONSENT_VERSION).
 * The user must accept the Terms of Service and the Privacy Policy, and is
 * told the Terms' minimum age, before proceeding.
 *
 * Crash reports are a separate, OPTIONAL choice on this screen, OFF unless the
 * user switches them on — accepting the policy does not switch them on.
 * [onAccept] carries that choice; the caller persists it and lets BirdoApp
 * start the SDK only if it is true.
 */
@Composable
fun ConsentScreen(
    onAccept: (crashReportsEnabled: Boolean) -> Unit,
    onDecline: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    // Default OFF, deliberately: an opt-in that starts ticked is not one.
    var crashReports by rememberSaveable { mutableStateOf(false) }

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
                CrashReportsChoice(
                    checked = crashReports,
                    onCheckedChange = { crashReports = it },
                )
                Spacer(modifier = Modifier.height(16.dp))
                DataItem(
                    title = stringResource(R.string.consent_no_data_sales_title),
                    description = stringResource(R.string.consent_no_data_sales_desc),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // BOTH documents are linked, because accepting accepts both (audit
        // 2026-09-29, B-13: in-app signups previously accepted the privacy
        // policy only, so there was no evidence those users took the Terms).
        PolicyLink(
            text = stringResource(R.string.consent_read_terms),
            url = TERMS_URL,
            context = context,
        )
        PolicyLink(
            text = stringResource(R.string.consent_read_privacy_policy),
            url = PRIVACY_URL,
            context = context,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // The Terms' minimum age, stated where the account is created.
        Text(
            text = stringResource(R.string.consent_age_notice),
            color = BirdoWhite60,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Accept button
        Button(
            onClick = { onAccept(crashReports) },
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

        // What "I Agree & Continue" means, stated next to the button.
        Text(
            text = stringResource(R.string.consent_terms_notice),
            color = BirdoWhite40,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.consent_required_notice),
            color = BirdoWhite20,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

private const val TERMS_URL = "https://birdo.app/terms"
private const val PRIVACY_URL = "https://birdo.app/privacy"

@Composable
private fun PolicyLink(text: String, url: String, context: android.content.Context) {
    TextButton(
        onClick = {
            // runCatching: a device with no browser must not crash the one
            // screen every new user has to get past.
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        },
    ) {
        Text(
            text = text,
            color = BirdoAccent,
            fontSize = 14.sp,
            textDecoration = TextDecoration.Underline,
        )
    }
}

/**
 * The optional crash-report item: the same title + description as the other
 * items, with a switch. The whole row is ONE toggleable so TalkBack announces a
 * single labelled switch.
 */
@Composable
private fun CrashReportsChoice(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag(TestTags.CONSENT_CRASH_REPORTS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            DataItem(
                title = stringResource(R.string.consent_crash_reports_title),
                description = stringResource(R.string.consent_crash_reports_desc),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            // The row's toggleable owns the interaction.
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BirdoWhite,
                checkedTrackColor = BirdoAccent,
            ),
        )
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
