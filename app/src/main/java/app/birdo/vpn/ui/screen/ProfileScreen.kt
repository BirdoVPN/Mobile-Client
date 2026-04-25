package app.birdo.vpn.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.R
import app.birdo.vpn.data.model.SubscriptionStatus
import app.birdo.vpn.data.model.UserProfile
import app.birdo.vpn.ui.components.BirdoCard
import app.birdo.vpn.ui.theme.BirdoBrand
import app.birdo.vpn.ui.theme.BirdoColors
import app.birdo.vpn.ui.theme.BirdoGreen
import app.birdo.vpn.ui.theme.BirdoSurface
import app.birdo.vpn.ui.theme.BirdoWhite60
import app.birdo.vpn.ui.theme.BirdoWhite80
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Profile screen — top-level destination. Identity, subscription, and all
 * Account actions (formerly under Settings → Account): subscription, voucher,
 * privacy, terms, sign out, delete account.
 */
@Composable
fun ProfileScreen(
    user: UserProfile?,
    subscription: SubscriptionStatus?,
    isConnected: Boolean,
    publicIp: String?,
    onSubscription: () -> Unit,
    onRedeemVoucher: () -> Unit,
    onManageOnWeb: () -> Unit,
    onLogout: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
    onDeleteAccount: (String) -> Unit = {},
    isDeletingAccount: Boolean = false,
    deleteAccountError: String? = null,
    onClearDeleteError: () -> Unit = {},
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileIdentityCard(
            user = user,
            subscription = subscription,
            isConnected = isConnected,
            publicIp = publicIp,
        )

        SubscriptionCard(subscription = subscription, onManage = onSubscription)

        Spacer(Modifier.height(4.dp))

        SectionLabel("Account")
        ProfileActionRow(
            icon = Icons.Outlined.Subscriptions,
            title = "Subscription",
            subtitle = "Manage billing and plan",
            onClick = onSubscription,
        )
        ProfileActionRow(
            icon = Icons.Outlined.CardGiftcard,
            title = "Redeem voucher",
            subtitle = "Activate a 30 / 90-day code",
            onClick = onRedeemVoucher,
        )
        ProfileActionRow(
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            title = "Manage on web",
            subtitle = "Open birdo.app in browser",
            onClick = onManageOnWeb,
        )
        ProfileActionRow(
            icon = Icons.Outlined.Policy,
            title = stringResource(R.string.settings_privacy_policy),
            subtitle = "birdo.app/privacy",
            onClick = { onOpenUrl("https://birdo.app/privacy") },
        )
        ProfileActionRow(
            icon = Icons.Outlined.Description,
            title = stringResource(R.string.settings_terms_of_service),
            subtitle = "birdo.app/terms",
            onClick = { onOpenUrl("https://birdo.app/terms") },
        )

        Spacer(Modifier.height(8.dp))

        SectionLabel("Session")
        ProfileActionRow(
            icon = Icons.AutoMirrored.Outlined.Logout,
            title = "Sign out",
            subtitle = user?.email ?: "Sign out of this device",
            onClick = onLogout,
            destructive = true,
        )
        ProfileActionRow(
            icon = Icons.Default.DeleteForever,
            title = stringResource(R.string.settings_delete_account),
            subtitle = stringResource(R.string.settings_delete_account_desc),
            onClick = { showDeleteDialog = true },
            destructive = true,
        )

        Spacer(Modifier.height(40.dp))
    }

    if (showDeleteDialog) {
        DeleteAccountDialog(
            isDeletingAccount = isDeletingAccount,
            error = deleteAccountError,
            onConfirm = { password -> onDeleteAccount(password) },
            onDismiss = {
                showDeleteDialog = false
                onClearDeleteError()
            },
        )
    }
}

@Composable
private fun ProfileIdentityCard(
    user: UserProfile?,
    subscription: SubscriptionStatus?,
    isConnected: Boolean,
    publicIp: String?,
) {
    val palette = BirdoColors.current
    val email = user?.email ?: "Anonymous"
    val name = user?.name?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')
    val plan = subscription?.plan ?: "RECON"

    BirdoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        surface = palette.surface,
        border = BirdoBrand.GlassStrokeGradient,
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                app.birdo.vpn.ui.components.AppIconMark(size = 56.dp, cornerRadius = 18.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        color = palette.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = email,
                        color = palette.onSurfaceMuted,
                        fontSize = 13.sp,
                    )
                }
                PlanChip(plan = plan)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surfaceRaised)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) BirdoGreen else palette.onSurfaceFaint),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isConnected) "Protected" else "Not connected",
                        color = palette.onBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = publicIp?.let { "Public IP · $it" } ?: "Tap Connect to start",
                        color = palette.onSurfaceMuted,
                        fontSize = 11.sp,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = if (isConnected) BirdoGreen else palette.onSurfaceFaint,
                )
            }
        }
    }
}

/**
 * Improved subscription summary card.
 * - Big plan badge with gradient
 * - Status pill (Active / Inactive)
 * - Renewal date formatted as yyyy-MM-dd (date only, no time)
 * - Plan benefits chip row (premium servers, max devices, bandwidth)
 * - Prominent "Manage" CTA
 */
@Composable
private fun SubscriptionCard(
    subscription: SubscriptionStatus?,
    onManage: () -> Unit,
) {
    val palette = BirdoColors.current
    val plan = subscription?.plan ?: "RECON"
    val status = subscription?.status ?: "INACTIVE"
    val isActive = status.equals("ACTIVE", ignoreCase = true)
    val endsAtRaw = subscription?.subscriptionEndsAt
    val endsAtFormatted = remember(endsAtRaw) { formatRenewalDate(endsAtRaw) }

    BirdoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        surface = palette.surface,
        border = BirdoBrand.GlassStrokeGradient,
        contentPadding = PaddingValues(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(planGradient(plan)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$plan plan",
                        color = palette.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when {
                            endsAtFormatted != null -> "Renews $endsAtFormatted"
                            isActive -> "Active subscription"
                            else -> "Free tier — upgrade for premium"
                        },
                        color = palette.onSurfaceMuted,
                        fontSize = 12.sp,
                    )
                }
                StatusPillSmall(active = isActive)
            }

            // Plan benefits chips
            if (subscription != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BenefitChip(
                        label = "${subscription.maxConnections} devices",
                    )
                    BenefitChip(
                        label = if (subscription.bandwidthLimitGb > 0)
                            "${subscription.bandwidthLimitGb} GB / mo"
                        else "Unlimited",
                    )
                    if (subscription.hasPremiumServers) {
                        BenefitChip(label = "Premium servers")
                    }
                }
            }

            // CTA
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(planGradient(plan))
                    .clickable(onClick = onManage)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CreditCard,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (isActive) "Manage subscription" else "Upgrade plan",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun StatusPillSmall(active: Boolean) {
    val palette = BirdoColors.current
    val (bg, fg, label) = if (active)
        Triple(BirdoGreen.copy(alpha = 0.18f), BirdoGreen, "ACTIVE")
    else
        Triple(palette.surfaceRaised, palette.onSurfaceMuted, "INACTIVE")
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BenefitChip(label: String) {
    val palette = BirdoColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(palette.surfaceRaised)
            .border(1.dp, palette.hairlineSoft, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = palette.onSurfaceMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PlanChip(plan: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(planGradient(plan))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = plan.uppercase(),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun planGradient(plan: String): Brush = when (plan.uppercase()) {
    "SOVEREIGN" -> Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFF4C1D95)))
    "OPERATIVE" -> Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF4338CA)))
    else -> Brush.linearGradient(listOf(Color(0xFF475569), Color(0xFF334155)))
}

@Composable
private fun SectionLabel(label: String) {
    val palette = BirdoColors.current
    Text(
        text = label.uppercase(),
        color = palette.onSurfaceMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun ProfileActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val palette = BirdoColors.current
    val iconTint = if (destructive) Color(0xFFEF4444) else palette.accent
    val titleColor = if (destructive) Color(0xFFEF4444) else palette.onBackground

    BirdoCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        cornerRadius = 16.dp,
        surface = palette.surface,
        border = BirdoBrand.GlassStrokeGradient,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.surfaceRaised)
                    .border(1.dp, palette.hairlineSoft, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = titleColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = palette.onSurfaceMuted,
                        fontSize = 12.sp,
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = palette.onSurfaceFaint,
            )
        }
    }
}

/**
 * Parses a backend renewal-date string and returns it as plain `yyyy-MM-dd`.
 * Accepts ISO-8601 with time-zone (`2026-05-12T00:00:00Z`) or already-formatted
 * `yyyy-MM-dd`. Returns null if input is null/blank/unparseable.
 */
private fun formatRenewalDate(raw: String?): String? {
    val v = raw?.trim().orEmpty()
    if (v.isEmpty()) return null
    // 1. Already a plain date.
    runCatching { return LocalDate.parse(v).format(DateTimeFormatter.ISO_LOCAL_DATE) }
    // 2. Offset / zoned date-time.
    runCatching {
        return OffsetDateTime.parse(v)
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
    }
    // 3. Trim trailing time portion as a fallback.
    val datePart = v.substringBefore('T')
    runCatching {
        return LocalDate.parse(datePart).format(DateTimeFormatter.ISO_LOCAL_DATE)
    }
    return null
}

/** Confirmation dialog requiring password re-entry before account deletion. */
@Composable
private fun DeleteAccountDialog(
    isDeletingAccount: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isDeletingAccount) onDismiss() },
        containerColor = BirdoSurface,
        titleContentColor = Color(0xFFEF4444),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.delete_dialog_title), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.delete_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BirdoWhite60,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.delete_dialog_password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !isDeletingAccount,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFEF4444),
                        cursorColor = BirdoWhite80,
                        focusedLabelColor = Color(0xFFEF4444),
                    ),
                )
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF4444))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotBlank() && !isDeletingAccount,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF4444),
                    contentColor = Color.White,
                ),
            ) {
                if (isDeletingAccount) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.delete_dialog_deleting))
                } else {
                    Text(stringResource(R.string.delete_dialog_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeletingAccount) {
                Text(stringResource(R.string.delete_dialog_cancel), color = BirdoWhite80)
            }
        },
    )
}
