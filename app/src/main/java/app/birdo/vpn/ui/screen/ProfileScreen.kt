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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.data.model.SubscriptionStatus
import app.birdo.vpn.data.model.UserProfile
import app.birdo.vpn.ui.components.BirdoCard
import app.birdo.vpn.ui.theme.BirdoBrand
import app.birdo.vpn.ui.theme.BirdoColors
import app.birdo.vpn.ui.theme.BirdoGreen

/**
 * Profile screen — top-level destination placed before Connect in the bottom
 * nav. Shows user identity, plan badge, and quick actions (subscription,
 * vouchers, manage on web, logout).
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
) {
    val palette = BirdoColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Hero identity card ────────────────────────────────────────────
        ProfileIdentityCard(
            user = user,
            subscription = subscription,
            isConnected = isConnected,
            publicIp = publicIp,
        )

        // ── Plan / Subscription card ──────────────────────────────────────
        PlanCard(subscription = subscription, onManage = onSubscription)

        // ── Quick actions ─────────────────────────────────────────────────
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

        Spacer(Modifier.height(8.dp))

        SectionLabel("Session")
        ProfileActionRow(
            icon = Icons.AutoMirrored.Outlined.Logout,
            title = "Sign out",
            subtitle = user?.email ?: "Sign out of this device",
            onClick = onLogout,
            destructive = true,
        )

        Spacer(Modifier.height(40.dp))
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
    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "·"
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
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(BirdoBrand.PrimaryGradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initial,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
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

            // Status row
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

@Composable
private fun PlanCard(
    subscription: SubscriptionStatus?,
    onManage: () -> Unit,
) {
    val palette = BirdoColors.current
    val plan = subscription?.plan ?: "RECON"
    val status = subscription?.status ?: "INACTIVE"
    val endsAt = subscription?.subscriptionEndsAt

    BirdoCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onManage),
        cornerRadius = 18.dp,
        surface = palette.surface,
        border = BirdoBrand.GlassStrokeGradient,
        contentPadding = PaddingValues(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.accent.copy(alpha = if (palette.isLight) 0.14f else 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    tint = palette.accent,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$plan plan",
                    color = palette.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        endsAt != null -> "Renews $endsAt"
                        status.equals("ACTIVE", true) -> "Active subscription"
                        else -> "Upgrade for premium servers"
                    },
                    color = palette.onSurfaceMuted,
                    fontSize = 12.sp,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = palette.onSurfaceFaint,
            )
        }
    }
}

@Composable
private fun PlanChip(plan: String) {
    val palette = BirdoColors.current
    val (bg, fg) = when (plan.uppercase()) {
        "SOVEREIGN" -> BirdoBrand.PrimaryGradient to Color.White
        "OPERATIVE" -> Brush.linearGradient(listOf(BirdoBrand.Indigo, BirdoBrand.Cyan)) to Color.White
        else -> Brush.linearGradient(listOf(palette.hairline, palette.hairline)) to palette.onSurface
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = plan.uppercase(),
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
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
