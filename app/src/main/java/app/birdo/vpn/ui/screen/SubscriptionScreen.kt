package app.birdo.vpn.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.BuildConfig
import androidx.compose.ui.res.stringResource
import app.birdo.vpn.R
import app.birdo.vpn.data.model.SubscriptionStatus
import app.birdo.vpn.ui.components.BirdoCard
import app.birdo.vpn.ui.components.BirdoIconAction
import app.birdo.vpn.ui.components.BirdoSegmentedControl
import app.birdo.vpn.ui.components.BirdoTopBar
import app.birdo.vpn.ui.theme.*

private data class PlanInfo(
    val id: String,
    val name: String,
    val tagline: String,
    val priceMonthly: String,
    val priceYearly: String,
    val features: List<String>,
    val isPopular: Boolean = false,
) {
    /** Tier colour — single source of truth, shared with Profile. */
    val accent: Color get() = BirdoBrand.planAccent(id)
}

private val plans = listOf(
    PlanInfo(
        id = "RECON",
        name = "Recon",
        tagline = "Test the waters",
        priceMonthly = "Free",
        priceYearly = "Free",
        features = listOf(
            "1 device connection",
            "2 server locations",
            "10 GB monthly bandwidth",
            "WireGuard\u00ae encryption",
            "Post-quantum encryption",
            "Kill switch",
            "DNS leak protection",
        ),
    ),
    PlanInfo(
        id = "OPERATIVE",
        name = "Operative",
        tagline = "Most popular",
        priceMonthly = "£3.99/mo",
        priceYearly = "£38/yr",
        features = listOf(
            "5 device connections",
            "All server locations",
            "Unlimited bandwidth",
            "WireGuard\u00ae encryption",
            "Post-quantum encryption",
            "Kill switch",
            "Split tunneling",
            "Stealth mode",
            "Speed test",
            "2FA / TOTP",
            "Biometric lock",
            "Priority support",
        ),
        isPopular = true,
    ),
    PlanInfo(
        id = "SOVEREIGN",
        name = "Sovereign",
        tagline = "Full control",
        priceMonthly = "£9.99/mo",
        priceYearly = "£99/yr",
        features = listOf(
            "10 device connections",
            "All server locations",
            "Unlimited bandwidth",
            "WireGuard\u00ae encryption",
            "Post-quantum encryption",
            "Kill switch",
            "Split tunneling",
            "Stealth mode",
            "Multi-hop routing",
            "Port forwarding",
            "Speed test",
            "2FA / TOTP",
            "Biometric lock",
            "Custom DNS",
            "Priority support",
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    currentSubscription: SubscriptionStatus?,
    onNavigateBack: () -> Unit,
    onSelectPlan: (planId: String, period: String) -> Unit,
    onManageOnWeb: () -> Unit,
    billingReady: Boolean = false,
    billingMessage: String? = null,
    billingIsError: Boolean = false,
    billingIsPurchasing: Boolean = false,
    onClearBillingMessage: () -> Unit = {},
    // Google Play build: hide all external-purchase steering (no "buy"/"manage
    // on web" links). Premium tiers become an informational feature comparison.
    isPlayBuild: Boolean = BuildConfig.IS_PLAY_BUILD,
) {
    var billingPeriod by remember { mutableStateOf("yearly") }
    val palette = BirdoColors.current

    Scaffold(
        topBar = {
            BirdoTopBar(
                title = stringResource(R.string.subscription_title),
                onBack = onNavigateBack,
                actions = {
                    // Play build: no web-billing steering in the toolbar.
                    if (!isPlayBuild) {
                        BirdoIconAction(
                            icon = Icons.Default.OpenInNew,
                            contentDescription = stringResource(R.string.subscription_manage_web),
                            onClick = onManageOnWeb,
                        )
                    }
                },
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // Current plan hero card — dense, single row of metrics.
            if (currentSubscription != null) {
                CurrentPlanHero(currentSubscription)
                Spacer(Modifier.height(20.dp))
            }

            Text(
                stringResource(if (isPlayBuild) R.string.subscription_plans_features else R.string.subscription_choose_plan),
                style = MaterialTheme.typography.titleMedium,
                color = palette.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )

            // Billing period toggle — the shared control, so the selected
            // segment is accent-tinted like everywhere else (it used to be a
            // solid white slab, alien to the dark-glass system).
            BirdoSegmentedControl(
                options = listOf(
                    "monthly" to stringResource(R.string.subscription_monthly),
                    "yearly" to stringResource(R.string.subscription_yearly),
                ),
                selectedKey = billingPeriod,
                onSelect = { billingPeriod = it },
            )

            Spacer(Modifier.height(16.dp))

            // Plan cards
            plans.forEach { plan ->
                val isCurrent = currentSubscription?.plan?.equals(plan.id, ignoreCase = true) == true
                PlanCard(
                    plan = plan,
                    isCurrent = isCurrent,
                    price = if (billingPeriod == "yearly") plan.priceYearly else plan.priceMonthly,
                    isPurchasing = billingIsPurchasing,
                    showManageButton = !isPlayBuild,
                    onSelect = { onSelectPlan(plan.id, billingPeriod) },
                )
                Spacer(Modifier.height(12.dp))
            }

            // Billing status banner (Play unavailable / purchase result)
            if (billingMessage != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (billingIsError) BirdoRed.copy(alpha = 0.12f) else BirdoGreen.copy(alpha = 0.12f),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (billingIsError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (billingIsError) BirdoRed else BirdoGreen,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            billingMessage,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (billingIsError) BirdoRed else BirdoGreen,
                        )
                        TextButton(onClick = onClearBillingMessage) {
                            Text(stringResource(R.string.dismiss), color = palette.onSurfaceMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (!billingReady && !isPlayBuild) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.subscription_web_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }

            // Footer note about vouchers (redeemable on the Profile tab). The
            // Play build omits any steering to external purchase / web billing.
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    if (isPlayBuild) R.string.subscription_voucher_note
                    else R.string.subscription_voucher_note_web,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurfaceMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun CurrentPlanHero(sub: SubscriptionStatus) {
    val isActive = sub.status.equals("ACTIVE", ignoreCase = true)
    val palette = BirdoColors.current
    val planAccent = BirdoBrand.planAccent(sub.plan)
    BirdoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(planAccent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.WorkspacePremium, null, tint = planAccent, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        sub.plan.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(if (isActive) R.string.subscription_active else R.string.subscription_inactive),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isActive) BirdoGreen else palette.onSurfaceMuted,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (isActive) BirdoGreen.copy(alpha = 0.18f) else palette.surfaceRaised,
                ) {
                    Text(
                        stringResource(if (isActive) R.string.subscription_badge_active else R.string.subscription_badge_inactive),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = if (isActive) BirdoGreen else palette.onSurfaceMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MetricCell(
                    stringResource(R.string.subscription_metric_devices),
                    "${sub.activeConnections}/${sub.maxConnections}",
                    Modifier.weight(1f),
                )
                MetricCell(
                    stringResource(R.string.subscription_metric_bandwidth),
                    if (sub.bandwidthLimitGb > 0) "${sub.bandwidthLimitGb} GB" else stringResource(R.string.subscription_unlimited),
                    Modifier.weight(1f),
                )
                MetricCell(
                    stringResource(R.string.subscription_metric_premium),
                    stringResource(if (sub.hasPremiumServers) R.string.yes else R.string.no),
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = BirdoColors.current
    Column(modifier = modifier) {
        Text(label, color = palette.onSurfaceMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = palette.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlanCard(
    plan: PlanInfo,
    isCurrent: Boolean,
    price: String,
    isPurchasing: Boolean = false,
    showManageButton: Boolean = true,
    onSelect: () -> Unit,
) {
    val palette = BirdoColors.current
    val shape = RoundedCornerShape(16.dp)
    val popularBorder = if (plan.isPopular) {
        Brush.linearGradient(listOf(plan.accent, plan.accent.copy(alpha = 0.3f)))
    } else {
        BirdoBrand.GlassStrokeGradient
    }

    BirdoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        border = popularBorder,
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            plan.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.onSurface,
                        )
                        if (plan.isPopular) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = plan.accent.copy(alpha = 0.15f),
                            ) {
                                Text(
                                    stringResource(R.string.subscription_popular),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = plan.accent,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        if (isCurrent) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = BirdoGreen.copy(alpha = 0.15f),
                            ) {
                                Text(
                                    stringResource(R.string.subscription_current),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BirdoGreen,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Text(plan.tagline, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceMuted)
                }
                Text(
                    price,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (plan.id == "RECON") palette.onSurfaceMuted else palette.onSurface,
                )
            }

            Spacer(Modifier.height(16.dp))

            plan.features.forEach { feature ->
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Check,
                        null,
                        tint = plan.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        feature,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onSurfaceMuted,
                    )
                }
            }

            if (!isCurrent && plan.id != "RECON" && showManageButton) {
                Spacer(Modifier.height(16.dp))
                // Account-management framing (no in-app "buy" CTA): subscriptions
                // are purchased and changed on the web. This opens the billing
                // page on birdo.app rather than initiating an in-app purchase.
                // Hidden entirely in the Play build (showManageButton = false).
                app.birdo.vpn.ui.components.BirdoButton(
                    text = stringResource(R.string.subscription_manage_web),
                    onClick = onSelect,
                    variant = app.birdo.vpn.ui.components.BirdoButtonVariant.Secondary,
                    icon = Icons.Default.OpenInNew,
                    isLoading = isPurchasing,
                    enabled = !isPurchasing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
