package app.birdo.vpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.R
import app.birdo.vpn.ui.theme.BirdoColors

/**
 * How the user may pay, when the Play build is enrolled in Google's
 * billing-choice / external-offers programme.
 *
 * The programme requires a genuine, non-coercive choice: options are presented
 * side by side, neither is pre-selected, and the copy does not disparage or
 * steer away from Google's option. That is a policy constraint, not a design
 * preference — do not "improve" this screen by making one option louder.
 *
 * This sheet is only ever shown when BOTH `IS_PLAY_BUILD` and
 * `PLAY_EXTERNAL_OFFERS` are true. Outside the Play build the app links straight
 * to the web checkout as it always has; inside an UNENROLLED Play build it must
 * show nothing at all, because unenrolled steering gets the app removed.
 *
 * See birdo-web/docs/PLAY-LINK-OUT-BILLING.md.
 */
enum class BillingChoice {
    /** Google Play Billing. Google is merchant of record and remits the VAT. */
    GooglePlay,

    /** Continue on birdo.app — the existing Polar checkout. Polar stays MoR. */
    Web,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirdoBillingChoiceSheet(
    sheetState: SheetState,
    onChoose: (BillingChoice) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Whether to offer Google Play Billing as well as the web checkout.
     *
     * Left as a parameter rather than hardcoded because it is NOT yet settled
     * whether Google's programme obliges an enrolled developer to offer Play
     * Billing side-by-side with a link-out, or whether a link-out may stand
     * alone. That is confirmed at enrolment in the Play Console — see the
     * checklist in PLAY-LINK-OUT-BILLING.md. The sheet supports both shapes so
     * the answer does not require a redesign.
     */
    offerGooglePlayBilling: Boolean = false,
) {
    val palette = BirdoColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surface,
        contentColor = palette.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.billing_choice_title),
                color = palette.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.billing_choice_subtitle),
                color = palette.onSurfaceMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(20.dp))

            if (offerGooglePlayBilling) {
                BillingOptionRow(
                    icon = Icons.Default.ShoppingCart,
                    title = stringResource(R.string.billing_choice_play_title),
                    subtitle = stringResource(R.string.billing_choice_play_subtitle),
                    onClick = { onChoose(BillingChoice.GooglePlay) },
                )
                Spacer(Modifier.height(10.dp))
            }

            BillingOptionRow(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                title = stringResource(R.string.billing_choice_web_title),
                subtitle = stringResource(R.string.billing_choice_web_subtitle),
                onClick = { onChoose(BillingChoice.Web) },
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.billing_choice_note),
                color = palette.onSurfaceFaint,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun BillingOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val palette = BirdoColors.current
    BirdoCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.surfaceRaised)
                    .border(1.dp, palette.hairlineSoft, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = palette.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = palette.onSurfaceMuted, fontSize = 12.sp)
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = palette.onSurfaceFaint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
