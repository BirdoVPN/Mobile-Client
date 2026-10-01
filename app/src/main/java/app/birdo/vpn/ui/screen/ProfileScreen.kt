package app.birdo.vpn.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.R
import app.birdo.vpn.billing.PlaySubscriptionLinks
import app.birdo.vpn.data.model.DeletionPreflightResponse
import app.birdo.vpn.data.model.SubscriptionStatus
import app.birdo.vpn.data.model.UserProfile
import app.birdo.vpn.ui.components.BirdoCard
import app.birdo.vpn.ui.theme.BirdoBrand
import app.birdo.vpn.ui.theme.BirdoColors
import app.birdo.vpn.ui.theme.BirdoGreen
import app.birdo.vpn.ui.theme.BirdoRed
import app.birdo.vpn.ui.theme.BirdoSurface
import app.birdo.vpn.ui.theme.BirdoWhite60
import app.birdo.vpn.ui.theme.BirdoWhite80
import app.birdo.vpn.ui.components.SignOutConfirmDialog
import app.birdo.vpn.ui.viewmodel.VoucherResult
import app.birdo.vpn.utils.accountNumberOf
import app.birdo.vpn.utils.copySensitiveToClipboard
import app.birdo.vpn.utils.filterTwoFactorInput
import app.birdo.vpn.utils.formatAnonymousId
import app.birdo.vpn.utils.is2faCodeComplete
import app.birdo.vpn.utils.isAnonymousUser
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
    onRedeemVoucher: ((code: String, onResult: (VoucherResult) -> Unit) -> Unit)? = null,
    onManageOnWeb: () -> Unit,
    onLogout: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
    /** (password, two-factor code): the code only once the server asked for it. */
    onDeleteAccount: (String, String?) -> Unit = { _, _ -> },
    isDeletingAccount: Boolean = false,
    deleteAccountError: String? = null,
    /** The server wants the account's 2FA code before it deletes it (item 85). */
    deleteRequiresTwoFactor: Boolean = false,
    onClearDeleteError: () -> Unit = {},
    // Second-pass #9: fetched when the deletion dialog opens (null while
    // loading or after a failure, when the dialog keeps its static warning).
    deletionPreflight: DeletionPreflightResponse? = null,
    onDeleteDialogOpened: () -> Unit = {},
    // Google Play build: hide the "Manage on web" row, which links out to the
    // billing dashboard (external-purchase steering). See IS_PLAY_BUILD.
    isPlayBuild: Boolean = BuildConfig.IS_PLAY_BUILD,
    /** Anonymous accounts are reminded, before signing out, to keep the account number. */
    isAnonymousAccount: Boolean = false,
) {
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showVoucherDialog by rememberSaveable { mutableStateOf(false) }
    var showSignOutConfirm by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // A tab with no top bar owns its status-bar inset (A2-010).
            .statusBarsPadding()
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

        SectionLabel(stringResource(R.string.profile_section_account))
        ProfileActionRow(
            icon = Icons.Outlined.CardGiftcard,
            title = stringResource(R.string.voucher_title),
            subtitle = null,
            onClick = { showVoucherDialog = true },
        )
        if (!isPlayBuild) {
            ProfileActionRow(
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                title = stringResource(R.string.subscription_manage_web),
                subtitle = null,
                onClick = onManageOnWeb,
            )
        }
        ProfileActionRow(
            icon = Icons.Outlined.Policy,
            title = stringResource(R.string.settings_privacy_policy),
            subtitle = stringResource(R.string.profile_privacy_link),
            onClick = { onOpenUrl("https://birdo.app/privacy") },
        )
        ProfileActionRow(
            icon = Icons.Outlined.Description,
            title = stringResource(R.string.settings_terms_of_service),
            subtitle = stringResource(R.string.profile_terms_link),
            onClick = { onOpenUrl("https://birdo.app/terms") },
        )

        Spacer(Modifier.height(8.dp))

        SectionLabel(stringResource(R.string.profile_section_session))
        // Anonymous accounts carry a synthetic `anon_…@anonymous.local` email —
        // never surface that string; it reads as a bug and carries the number.
        val signOutSubtitle = when {
            isAnonymousUser(user) -> stringResource(R.string.account_anonymous)
            !user?.email.isNullOrBlank() -> user.email
            else -> stringResource(R.string.sign_out_subtitle)
        }
        ProfileActionRow(
            icon = Icons.AutoMirrored.Outlined.Logout,
            title = stringResource(R.string.sign_out),
            subtitle = signOutSubtitle,
            // Confirmed, as on the Connect tab (A2-006): the same one tap loses
            // an anonymous account whose number was never saved.
            onClick = { showSignOutConfirm = true },
            destructive = true,
        )
        ProfileActionRow(
            icon = Icons.Default.DeleteForever,
            title = stringResource(R.string.settings_delete_account),
            subtitle = null,
            onClick = {
                showDeleteDialog = true
                onDeleteDialogOpened()
            },
            destructive = true,
        )

        Spacer(Modifier.height(40.dp))
    }

    if (showDeleteDialog) {
        DeleteAccountDialog(
            // Deleting the account cannot cancel a Play subscription; the
            // dialog says so and links to where it can be cancelled.
            onManageStoreSubscription = { onOpenUrl(PlaySubscriptionLinks.MANAGE) },
            // SSO and password-less anonymous accounts have no password to
            // confirm. The backend already accepts a password-less delete from
            // them (GDPR Art. 17); it was this dialog that trapped them, by
            // keeping Delete disabled until a non-blank password was typed.
            requiresPassword = user?.hasPassword ?: true,
            requiresTwoFactor = deleteRequiresTwoFactor,
            preflight = deletionPreflight,
            isDeletingAccount = isDeletingAccount,
            error = deleteAccountError,
            onConfirm = onDeleteAccount,
            onDismiss = {
                showDeleteDialog = false
                onClearDeleteError()
            },
        )
    }

    if (showVoucherDialog && onRedeemVoucher != null) {
        VoucherRedeemDialog(
            onRedeem = onRedeemVoucher,
            onDismiss = { showVoucherDialog = false },
        )
    }

    if (showSignOutConfirm) {
        SignOutConfirmDialog(
            isConnected = isConnected,
            isAnonymousAccount = isAnonymousAccount,
            onConfirm = onLogout,
            onDismiss = { showSignOutConfirm = false },
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
    val rawEmail = user?.email ?: ""
    // The 24-digit account number IS an anonymous account's only credential.
    // The server names it since the 2026-10-01 account API (item 86); an older
    // one only inside the synthetic `anon_<number>@anonymous.local` email.
    val isAnon = isAnonymousUser(user)
    val accountNumber = accountNumberOf(user)
    val displayName = when {
        !user?.name.isNullOrBlank() -> user.name!!
        isAnon -> stringResource(R.string.account_anonymous)
        rawEmail.isNotBlank() -> rawEmail.substringBefore('@')
        else -> stringResource(R.string.profile_display_name_fallback)
    }
    val plan = subscription?.plan ?: "RECON"
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.account_number_clip_label)
    val copiedMessage = stringResource(R.string.anon_created_copied)

    BirdoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        surface = palette.surface,
        border = BirdoBrand.GlassStrokeGradient,
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Identity header — no app icon here (it lives in Settings → About).
            // Long emails / 24-digit anon ids truncate gracefully so the plan chip
            // never gets pushed off-screen.
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        color = palette.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!isAnon && rawEmail.isNotBlank()) {
                        Text(
                            text = rawEmail,
                            color = palette.onSurfaceMuted,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                PlanChip(plan = plan)
            }

            // Anonymous account number — copyable QOL (it's the only credential,
            // so users need to save it). Tapping the copy button puts the FULL id
            // on the clipboard even though the display truncates.
            if (accountNumber != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surfaceRaised)
                        .clickable(role = Role.Button) {
                            copySensitiveToClipboard(context, clipLabel, accountNumber)
                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        }
                        .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.login_anonymous_id_label).uppercase(),
                            color = palette.onSurfaceFaint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            // Grouped in fours, as the sign-in field reads it back.
                            text = formatAnonymousId(accountNumber),
                            color = palette.onBackground,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.anon_created_copy_cd),
                        tint = palette.accent,
                        modifier = Modifier.padding(8.dp).size(18.dp),
                    )
                }
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
                        text = stringResource(if (isConnected) R.string.status_protected else R.string.status_not_connected),
                        color = palette.onBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = publicIp?.let { stringResource(R.string.profile_public_ip, it) }
                            ?: stringResource(R.string.profile_tap_connect),
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
                        text = planTitle(plan),
                        color = palette.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when {
                            endsAtFormatted != null && isActive -> stringResource(R.string.profile_renews, endsAtFormatted)
                            // Canceled / expiring: the date is when access ENDS,
                            // calling that "Renews" would be a lie.
                            endsAtFormatted != null -> stringResource(R.string.profile_access_until, endsAtFormatted)
                            isActive -> stringResource(R.string.subscription_active)
                            else -> stringResource(R.string.profile_free_plan_upsell)
                        },
                        color = palette.onSurfaceMuted,
                        fontSize = 12.sp,
                    )
                }
                StatusPillSmall(active = isActive)
            }

            // Plan benefits chips. They wrap: three in a Row did not fit at
            // large font scales and the last one was squeezed to a sliver
            // (A2-024).
            if (subscription != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BenefitChip(
                        label = pluralStringResource(
                            R.plurals.profile_devices,
                            subscription.maxConnections,
                            subscription.maxConnections,
                        ),
                    )
                    BenefitChip(
                        label = if (subscription.bandwidthLimitGb > 0) {
                            stringResource(R.string.data_gb_per_month, subscription.bandwidthLimitGb)
                        } else {
                            stringResource(R.string.unlimited_data)
                        },
                    )
                    if (subscription.hasPremiumServers) {
                        BenefitChip(label = stringResource(R.string.profile_premium_servers))
                    }
                }
            }

            // CTA
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(planGradient(plan))
                    .clickable(onClick = onManage, role = Role.Button)
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
                    text = stringResource(if (isActive) R.string.profile_manage_subscription else R.string.profile_upgrade_plan),
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
        Triple(BirdoGreen.copy(alpha = 0.18f), BirdoGreen, stringResource(R.string.subscription_badge_active))
    else
        Triple(palette.surfaceRaised, palette.onSurfaceMuted, stringResource(R.string.subscription_badge_inactive))
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun planGradient(plan: String): Brush = BirdoBrand.planGradient(plan)

/** Friendly plan naming — matches the Limit tab ("Free plan", not "RECON plan"). */
@Composable
private fun planTitle(plan: String): String = when (plan.uppercase()) {
    "RECON" -> stringResource(R.string.plan_title_free)
    "OPERATIVE" -> stringResource(R.string.plan_title_operative)
    "SOVEREIGN" -> stringResource(R.string.plan_title_sovereign)
    else -> stringResource(R.string.plan_title_other, plan)
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
    val iconTint = if (destructive) BirdoRed else palette.accent
    val titleColor = if (destructive) BirdoRed else palette.onBackground

    BirdoCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button),
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
 * Parses a backend renewal-date string and returns it as a friendly
 * "MMM d, yyyy" (e.g. "Aug 12, 2026") — matches the human date style used on
 * the Limit tab instead of a raw ISO date. Accepts ISO-8601 with time-zone
 * (`2026-05-12T00:00:00Z`) or plain `yyyy-MM-dd`. Returns null if input is
 * null/blank/unparseable.
 */
private val RENEWAL_DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy")

private fun formatRenewalDate(raw: String?): String? {
    val v = raw?.trim().orEmpty()
    if (v.isEmpty()) return null
    // 1. Already a plain date.
    runCatching { return LocalDate.parse(v).format(RENEWAL_DATE_FORMAT) }
    // 2. Offset / zoned date-time.
    runCatching {
        return OffsetDateTime.parse(v)
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalDate()
            .format(RENEWAL_DATE_FORMAT)
    }
    // 3. Trim trailing time portion as a fallback.
    val datePart = v.substringBefore('T')
    runCatching {
        return LocalDate.parse(datePart).format(RENEWAL_DATE_FORMAT)
    }
    return null
}

/**
 * Confirmation dialog requiring password re-entry before account deletion,
 * and the account's 2FA code when the server asks for it (item 85).
 */
@Composable
private fun DeleteAccountDialog(
    requiresPassword: Boolean,
    requiresTwoFactor: Boolean,
    preflight: DeletionPreflightResponse?,
    isDeletingAccount: Boolean,
    error: String?,
    onConfirm: (password: String, twoFactorCode: String?) -> Unit,
    onDismiss: () -> Unit,
    onManageStoreSubscription: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    // Not saved across process death either: a one-time code, like the password.
    var twoFactorCode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isDeletingAccount) onDismiss() },
        containerColor = BirdoSurface,
        titleContentColor = BirdoRed,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = BirdoRed, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.delete_dialog_title), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            // Scrolls: the store warning below makes this taller than a small
            // phone's dialog once the password field and keyboard are up.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        if (requiresPassword) R.string.delete_dialog_message
                        else R.string.delete_dialog_message_no_password
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BirdoWhite60,
                )
                // Audit 2026-09-29, A-8 / C-9: this dialog used to say the
                // "subscription will be deleted". A Play or App Store
                // subscription is not — only the store can cancel it — so the
                // user is told BEFORE confirming, with the way to cancel it.
                Spacer(Modifier.height(12.dp))
                // Second-pass #9: when the preflight has answered and names
                // store subscriptions that will keep billing, say which ones
                // BEFORE the confirm button. Loading, failed, or none named:
                // the static warning, which is true either way. Deletion is
                // never gated on the preflight.
                val stillBilling = preflight?.storeSubscriptionsStillBilling.orEmpty()
                if (stillBilling.isNotEmpty()) {
                    val stores = stillBilling.map { sub ->
                        when {
                            sub.isGooglePlay -> stringResource(R.string.store_still_billing_google_play)
                            sub.isAppStore -> stringResource(R.string.store_still_billing_app_store)
                            else -> stringResource(R.string.store_still_billing_unknown_store)
                        }
                    }.distinct().joinToString("; ")
                    Text(
                        stringResource(R.string.delete_dialog_preflight_store, stores),
                        style = MaterialTheme.typography.bodySmall,
                        color = BirdoRed,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Text(
                        stringResource(R.string.delete_dialog_store_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = BirdoWhite80,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (preflight?.webSubscriptionWillBeCancelled == true) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.delete_dialog_preflight_web),
                        style = MaterialTheme.typography.bodySmall,
                        color = BirdoWhite80,
                    )
                }
                TextButton(
                    onClick = onManageStoreSubscription,
                    enabled = !isDeletingAccount,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.delete_dialog_manage_play), color = BirdoGreen)
                }
                if (requiresPassword) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.delete_dialog_password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !isDeletingAccount,
                        isError = error != null && !requiresTwoFactor,
                        // A password manager can fill the confirmation (A2-017).
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.Password },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BirdoRed,
                            cursorColor = BirdoWhite80,
                            focusedLabelColor = BirdoRed,
                        ),
                    )
                }
                if (requiresTwoFactor) {
                    // The login 2FA field's rules: a TOTP or a backup code.
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.delete_dialog_2fa_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = BirdoWhite80,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = twoFactorCode,
                        onValueChange = { twoFactorCode = filterTwoFactorInput(it) },
                        label = { Text(stringResource(R.string.login_2fa_code_label)) },
                        placeholder = { Text(stringResource(R.string.login_2fa_placeholder)) },
                        // Text, not Number: backup codes have hex letters. No
                        // autocorrect, so the IME cannot rewrite the code.
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            autoCorrectEnabled = false,
                        ),
                        singleLine = true,
                        enabled = !isDeletingAccount,
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BirdoRed,
                            cursorColor = BirdoWhite80,
                            focusedLabelColor = BirdoRed,
                        ),
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = BirdoRed)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password, twoFactorCode.takeIf { requiresTwoFactor }) },
                enabled = (!requiresPassword || password.isNotBlank()) &&
                    (!requiresTwoFactor || is2faCodeComplete(twoFactorCode)) &&
                    !isDeletingAccount,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BirdoRed,
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

/**
 * Voucher redemption dialog. Shown when the user taps the "Redeem voucher"
 * row on the Profile tab — keeps voucher entry on the Profile screen
 * (rather than routing through the Subscription page).
 */
@Composable
private fun VoucherRedeemDialog(
    onRedeem: (code: String, onResult: (VoucherResult) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultIsSuccess by remember { mutableStateOf(false) }
    // Resolved in composition; the result arrives in a callback.
    val copy = VoucherCopy(
        redeemed = stringResource(R.string.voucher_redeemed),
        extended = stringResource(R.string.voucher_extended),
        upgraded = stringResource(R.string.voucher_upgraded),
        invalidFormat = stringResource(R.string.voucher_invalid_format),
        notFound = stringResource(R.string.voucher_not_found),
        alreadyRedeemed = stringResource(R.string.voucher_already_redeemed),
        expired = stringResource(R.string.voucher_expired),
        planDowngrade = stringResource(R.string.voucher_plan_downgrade),
        rejected = stringResource(R.string.error_voucher_failed),
        plans = PlanNames(
            free = stringResource(R.string.plan_name_free),
            operative = stringResource(R.string.plan_name_operative),
            sovereign = stringResource(R.string.plan_name_sovereign),
        ),
    )

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        containerColor = BirdoSurface,
        titleContentColor = BirdoWhite80,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CardGiftcard,
                    contentDescription = null,
                    tint = BirdoGreen,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.voucher_title), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.voucher_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = BirdoWhite60,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { newValue ->
                        code = newValue.uppercase().take(24)
                        resultMessage = null
                    },
                    label = { Text(stringResource(R.string.voucher_code_label)) },
                    singleLine = true,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BirdoGreen,
                        cursorColor = BirdoWhite80,
                        focusedLabelColor = BirdoGreen,
                    ),
                )
                resultMessage?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (resultIsSuccess) BirdoGreen else BirdoRed,
                        // Announced: the result of a paid action must reach a
                        // TalkBack user too (A2-032).
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    submitting = true
                    resultMessage = null
                    onRedeem(code.trim()) { result ->
                        submitting = false
                        resultIsSuccess = result is VoucherResult.Redeemed
                        resultMessage = copy.messageFor(result)
                        if (result is VoucherResult.Redeemed) code = ""
                    }
                },
                enabled = !submitting && code.length >= 8,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BirdoGreen,
                    contentColor = Color.Black,
                ),
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.voucher_redeem), fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(stringResource(R.string.cancel), color = BirdoWhite80)
            }
        },
    )
}

/**
 * The voucher dialog's result copy. A failure that is not about the CODE (a
 * dead session, a rate limit, an outage, no network) says so in the mapped
 * error text: all of them used to read "Network error. Try again." (A2-027).
 */
internal data class VoucherCopy(
    val redeemed: String,
    val extended: String,
    val upgraded: String,
    val invalidFormat: String,
    val notFound: String,
    val alreadyRedeemed: String,
    val expired: String,
    val planDowngrade: String,
    val rejected: String,
    val plans: PlanNames,
) {
    fun messageFor(result: VoucherResult): String = when (result) {
        is VoucherResult.Redeemed -> {
            val r = result.response
            redeemed.format(r.durationDays) + " " +
                if (r.extended) extended else upgraded.format(plans.of(r.plan))
        }
        is VoucherResult.Rejected -> when (result.slug) {
            "invalid_format" -> invalidFormat
            "not_found" -> notFound
            "already_redeemed" -> alreadyRedeemed
            "expired" -> expired
            "plan_downgrade" -> planDowngrade
            else -> rejected
        }
        is VoucherResult.Failed -> result.message
    }
}
