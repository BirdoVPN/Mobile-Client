package app.birdo.vpn.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.billing.BirdoBillingPeriod
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.birdo.vpn.R
import app.birdo.vpn.data.model.SubscriptionStatus
import app.birdo.vpn.ui.components.BirdoCard
import app.birdo.vpn.ui.components.BirdoIconAction
import app.birdo.vpn.ui.components.BirdoSegmentedControl
import app.birdo.vpn.ui.components.BirdoTopBar
import app.birdo.vpn.ui.theme.*

private data class PlanInfo(
    val id: String,
    @StringRes val name: Int,
    @StringRes val tagline: Int,
    /** The web's GBP list prices: only the sideload and F-Droid builds show them (A2-022). */
    @StringRes val priceMonthly: Int,
    @StringRes val priceYearly: Int,
    val features: List<PlanFeature>,
    val isPopular: Boolean = false,
) {
    /** Tier colour — single source of truth, shared with Profile. */
    val accent: Color get() = BirdoBrand.planAccent(id)
}

/** One line of a plan's feature list. A number in it goes through `<plurals>` (A2-031). */
private sealed interface PlanFeature {
    data class Plain(@StringRes val text: Int) : PlanFeature
    data class Counted(@PluralsRes val text: Int, val count: Int) : PlanFeature
}

@Composable
private fun PlanFeature.label(): String = when (this) {
    is PlanFeature.Plain -> stringResource(text)
    is PlanFeature.Counted -> pluralStringResource(text, count, count)
}

/**
 * What each plan includes ON ANDROID — the platform this paywall sells on.
 *
 * Audit 2026-09-29 (A-10 / D-4 / A-24): this list used to be copied between
 * platforms and sold things that do not exist here — "Speed test" (no mobile
 * implementation), "2 server locations" (the web deliberately states no
 * count; the fleet changes), split tunnelling as a paid extra although Android
 * offers it on every plan, and 2FA / biometric lock as paid although they are
 * on every plan. Every line below is a feature this app actually has, on the
 * plan it is listed under. Keep it that way: add a line only with the code.
 */
private val plans = listOf(
    PlanInfo(
        id = "RECON",
        name = R.string.subscription_plan_recon,
        tagline = R.string.subscription_tagline_recon,
        priceMonthly = R.string.subscription_price_free,
        priceYearly = R.string.subscription_price_free,
        features = listOf(
            PlanFeature.Counted(R.plurals.subscription_feature_devices, 1),
            PlanFeature.Plain(R.string.subscription_feature_core_locations),
            PlanFeature.Counted(R.plurals.subscription_feature_monthly_data, 10),
            PlanFeature.Plain(R.string.subscription_feature_wireguard),
            PlanFeature.Plain(R.string.subscription_feature_post_quantum),
            PlanFeature.Plain(R.string.subscription_feature_kill_switch),
            PlanFeature.Plain(R.string.subscription_feature_dns_leak),
            PlanFeature.Plain(R.string.subscription_feature_split_tunneling),
            // Every plan since owner decision D6 (2026-10-01).
            PlanFeature.Plain(R.string.subscription_feature_custom_dns),
            PlanFeature.Plain(R.string.subscription_feature_two_factor),
            PlanFeature.Plain(R.string.subscription_feature_hide_app_contents),
        ),
    ),
    PlanInfo(
        id = "OPERATIVE",
        name = R.string.plan_name_operative,
        tagline = R.string.subscription_tagline_operative,
        priceMonthly = R.string.subscription_price_operative_monthly,
        priceYearly = R.string.subscription_price_operative_yearly,
        features = listOf(
            PlanFeature.Counted(R.plurals.subscription_feature_devices, 5),
            PlanFeature.Plain(R.string.subscription_feature_all_locations),
            PlanFeature.Plain(R.string.subscription_feature_unlimited_bandwidth),
            PlanFeature.Plain(R.string.subscription_feature_wireguard),
            PlanFeature.Plain(R.string.subscription_feature_post_quantum),
            PlanFeature.Plain(R.string.subscription_feature_kill_switch),
            PlanFeature.Plain(R.string.subscription_feature_dns_leak),
            PlanFeature.Plain(R.string.subscription_feature_split_tunneling),
            PlanFeature.Plain(R.string.subscription_feature_stealth),
            PlanFeature.Plain(R.string.subscription_feature_custom_dns),
            PlanFeature.Plain(R.string.subscription_feature_two_factor),
            PlanFeature.Plain(R.string.subscription_feature_hide_app_contents),
            PlanFeature.Plain(R.string.subscription_feature_priority_support),
        ),
        isPopular = true,
    ),
    PlanInfo(
        id = "SOVEREIGN",
        name = R.string.plan_name_sovereign,
        tagline = R.string.subscription_tagline_sovereign,
        priceMonthly = R.string.subscription_price_sovereign_monthly,
        priceYearly = R.string.subscription_price_sovereign_yearly,
        features = listOf(
            PlanFeature.Counted(R.plurals.subscription_feature_devices, 10),
            PlanFeature.Plain(R.string.subscription_feature_all_locations),
            PlanFeature.Plain(R.string.subscription_feature_unlimited_bandwidth),
            PlanFeature.Plain(R.string.subscription_feature_wireguard),
            PlanFeature.Plain(R.string.subscription_feature_post_quantum),
            PlanFeature.Plain(R.string.subscription_feature_kill_switch),
            PlanFeature.Plain(R.string.subscription_feature_dns_leak),
            PlanFeature.Plain(R.string.subscription_feature_split_tunneling),
            PlanFeature.Plain(R.string.subscription_feature_stealth),
            PlanFeature.Plain(R.string.subscription_feature_multi_hop),
            PlanFeature.Plain(R.string.subscription_feature_port_forwarding),
            PlanFeature.Plain(R.string.subscription_feature_custom_dns),
            PlanFeature.Plain(R.string.subscription_feature_two_factor),
            PlanFeature.Plain(R.string.subscription_feature_hide_app_contents),
            PlanFeature.Plain(R.string.subscription_feature_priority_support),
        ),
    ),
)

/** Where a plan card's price comes from. */
internal enum class PriceLabel { PLAY, WEB, CHECKING, NOT_ON_PLAY }

/**
 * A Play build shows Google Play's own localised price or says why there is
 * none. It used to fall back to the web's GBP figures while the storefront was
 * loading or unavailable: a US or EU user saw "£3.99/mo" that Play would never
 * charge, which then changed under them (A2-022). The web figures stay for the
 * sideload and F-Droid builds, whose checkout is the website. The free plan
 * is "Free" everywhere.
 */
internal fun planPriceLabel(
    planId: String,
    isPlayBuild: Boolean,
    playPrice: String?,
    storefrontLoading: Boolean,
): PriceLabel = when {
    planId.equals("RECON", ignoreCase = true) || !isPlayBuild -> PriceLabel.WEB
    playPrice != null -> PriceLabel.PLAY
    storefrontLoading -> PriceLabel.CHECKING
    else -> PriceLabel.NOT_ON_PLAY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    currentSubscription: SubscriptionStatus?,
    onNavigateBack: () -> Unit,
    onSelectPlan: (planId: String, period: String) -> Unit,
    onManageOnWeb: () -> Unit,
    billingMessage: String? = null,
    billingIsError: Boolean = false,
    billingIsPurchasing: Boolean = false,
    onClearBillingMessage: () -> Unit = {},
    // Google Play build: subscriptions are sold through Play Billing and there
    // is no steering anywhere else. Non-Play builds (sideload APK, F-Droid)
    // keep the web-billing links, which Play policy does not reach.
    isPlayBuild: Boolean = BuildConfig.IS_PLAY_BUILD,
    /**
     * Whether the "Manage on web" affordances may be shown. Deliberately a
     * SEPARATE input from [isPlayBuild] rather than its inverse: an
     * external-offers-ENROLLED Play build is both a Play build and permitted to
     * link out, and collapsing the two would either hide the link from an
     * enrolled build or show it from an unenrolled one. The second mistake is
     * the one that gets `app.birdo.vpn` removed, permanently.
     */
    showWebManageAction: Boolean = !isPlayBuild,
    // -- Play rail ------------------------------------------------------
    // playPriceFor returns Google Play's own localised price for a plan+period,
    // or NULL when that combination is not purchasable on this device.
    //
    // NULL IS THE GATE. A plan card renders a purchase CTA if and only if this
    // returns a price, so a button can never be drawn for something Play never
    // offered. Deriving the button from the plan constants instead is how the
    // iOS build earned its Guideline 2.1 rejection: a "buy" control that
    // dead-ends in "purchasing unavailable".
    playPriceFor: (planId: String, period: String) -> String? = { _, _ -> null },
    // Non-null means nothing is purchasable and this sentence says why. It is a
    // first-class state, not an empty list.
    storefrontMessage: String? = null,
    storefrontLoading: Boolean = false,
    storefrontCanRetry: Boolean = false,
    onRetryStorefront: () -> Unit = {},
    onRestorePurchases: () -> Unit = {},
    isRestoring: Boolean = false,
    duplicateBillingMessage: String? = null,
    onDismissDuplicateBilling: () -> Unit = {},
    /**
     * The account already has a paid plan bought OUTSIDE this store (the
     * website, a voucher, or the other app store) — see StorePurchaseGate.
     * When true no purchase or "Change plan" button is drawn: Play's
     * replacement flow only knows Play purchases, so tapping one would start a
     * second, separate subscription (audit 2026-09-29, A-9 / A-16).
     */
    purchaseManagedElsewhere: Boolean = false,
    /**
     * Opens the Terms of Service / Privacy Policy. The purchase screen links
     * both, as the iOS one already does (audit 2026-09-29, D-20): a
     * subscription is bought here under those terms.
     */
    onOpenUrl: (String) -> Unit = {},
) {
    var billingPeriod by remember { mutableStateOf("yearly") }
    val palette = BirdoColors.current

    Scaffold(
        topBar = {
            BirdoTopBar(
                title = stringResource(R.string.subscription_title),
                onBack = onNavigateBack,
                actions = {
                    // No web-billing steering in an unenrolled Play build.
                    if (showWebManageAction) {
                        BirdoIconAction(
                            icon = Icons.AutoMirrored.Filled.OpenInNew,
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

            // Duplicate billing: the account is paying on another rail too.
            // Sticky and never auto-dismissed, because it is about money.
            if (duplicateBillingMessage != null) {
                BillingBanner(
                    text = duplicateBillingMessage,
                    isError = true,
                    onDismiss = onDismissDuplicateBilling,
                )
                Spacer(Modifier.height(12.dp))
            }

            // Paid elsewhere: say where it is managed instead of offering a
            // second subscription.
            if (purchaseManagedElsewhere) {
                ManagedElsewhereNotice()
                Spacer(Modifier.height(12.dp))
            }

            // Plan cards
            plans.forEach { plan ->
                val isCurrent = currentSubscription?.plan?.equals(plan.id, ignoreCase = true) == true
                // Google Play's own localised price, or null when this plan is
                // not purchasable here. Never hardcode a price we can look up.
                val playPrice = if (isPlayBuild) playPriceFor(plan.id, billingPeriod) else null
                val webPrice =
                    if (billingPeriod == "yearly") plan.priceYearly else plan.priceMonthly
                PlanCard(
                    plan = plan,
                    isCurrent = isCurrent,
                    price = when (
                        planPriceLabel(plan.id, isPlayBuild, playPrice, storefrontLoading)
                    ) {
                        PriceLabel.PLAY -> playPrice.orEmpty()
                        PriceLabel.WEB -> stringResource(webPrice)
                        PriceLabel.CHECKING -> stringResource(R.string.subscription_price_checking)
                        PriceLabel.NOT_ON_PLAY -> stringResource(R.string.subscription_price_not_on_play)
                    },
                    isPurchasing = billingIsPurchasing,
                    // THE GATE. In a Play build a CTA exists only when a real
                    // offer resolved; in a non-Play build the CTA is the
                    // web-billing link it has always been.
                    showManageButton = showWebManageAction,
                    showPurchaseButton = isPlayBuild && playPrice != null && !isCurrent &&
                        !purchaseManagedElsewhere,
                    isChangingPlan = currentSubscription?.plan?.equals("RECON", true) == false,
                    onSelect = { onSelectPlan(plan.id, billingPeriod) },
                )
                Spacer(Modifier.height(12.dp))
            }

            // Auto-renewal terms, stated before the purchase sheet rather
            // than only inside it. Shown only when something is actually
            // purchasable, so it never describes a subscription that cannot be
            // bought.
            if (isPlayBuild && storefrontMessage == null && !storefrontLoading) {
                BirdoBillingPeriod.fromKey(billingPeriod)?.let { period ->
                    Text(
                        stringResource(period.renewalSentenceRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onSurfaceMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }

            // Storefront state. An honest sentence beats an empty list that
            // looks like a broken screen, and beats a spinner with no way out.
            if (isPlayBuild) {
                if (storefrontLoading) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.subscription_loading_prices),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onSurfaceMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                } else if (storefrontMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        storefrontMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onSurfaceMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                    if (storefrontCanRetry) {
                        TextButton(
                            onClick = onRetryStorefront,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text(stringResource(R.string.subscription_retry))
                        }
                    }
                }
            }

            // Billing status banner (purchase result / server refusal).
            if (billingMessage != null) {
                Spacer(Modifier.height(8.dp))
                BillingBanner(
                    text = billingMessage,
                    isError = billingIsError,
                    onDismiss = onClearBillingMessage,
                )
            }

            // Restore Purchases. Always available in a Play build, INCLUDING
            // when nothing is purchasable: a purchase the server refused (or
            // one made while signed out) is exactly the case where the
            // storefront may be empty and the user still has something to
            // recover. A control hidden in the one state it is needed in is
            // not a control.
            if (isPlayBuild) {
                Spacer(Modifier.height(8.dp))
                app.birdo.vpn.ui.components.BirdoButton(
                    text = stringResource(R.string.subscription_restore),
                    onClick = onRestorePurchases,
                    variant = app.birdo.vpn.ui.components.BirdoButtonVariant.Secondary,
                    icon = Icons.Default.Restore,
                    isLoading = isRestoring,
                    enabled = !isRestoring,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.subscription_play_manage),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            } else {
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

            // The terms a subscription is bought under. Birdo's own pages,
            // not a purchase route, so they belong in every build.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = { onOpenUrl("https://birdo.app/terms") }) {
                    Text(
                        stringResource(R.string.settings_terms_of_service),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { onOpenUrl("https://birdo.app/privacy") }) {
                    Text(
                        stringResource(R.string.settings_privacy_policy),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

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
                    if (sub.bandwidthLimitGb > 0) {
                        stringResource(R.string.data_gb, sub.bandwidthLimitGb.toString())
                    } else {
                        stringResource(R.string.subscription_unlimited)
                    },
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
    /**
     * Google Play purchase CTA. Set ONLY when a real, loaded offer backs this
     * card -- see the playPriceFor gate in [SubscriptionScreen]. Never derive
     * it from the plan constants.
     */
    showPurchaseButton: Boolean = false,
    /** The user already pays for something; this is a change, not a first buy. */
    isChangingPlan: Boolean = false,
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
                    // Wraps: at 200 % font scale the name and both chips do not
                    // fit beside the price, and a Row squeezed the last chip to
                    // a sliver of vertical letters (A2-024).
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(plan.name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.onSurface,
                        )
                        if (plan.isPopular) {
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
                    Text(stringResource(plan.tagline), style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceMuted)
                }
                Spacer(Modifier.width(12.dp))
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
                        feature.label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onSurfaceMuted,
                    )
                }
            }

            if (!isCurrent && plan.id != "RECON" && showPurchaseButton) {
                Spacer(Modifier.height(16.dp))
                // Google Play purchase. Reached only when a real offer resolved.
                app.birdo.vpn.ui.components.BirdoButton(
                    text = stringResource(
                        if (isChangingPlan) R.string.subscription_change_plan
                        else R.string.subscription_subscribe,
                    ),
                    onClick = onSelect,
                    variant = app.birdo.vpn.ui.components.BirdoButtonVariant.Primary,
                    icon = Icons.Default.WorkspacePremium,
                    isLoading = isPurchasing,
                    enabled = !isPurchasing,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (!isCurrent && plan.id != "RECON" && showManageButton) {
                Spacer(Modifier.height(16.dp))
                // Non-Play distribution only: subscriptions are purchased and
                // changed on the web. Opens the billing page on birdo.app.
                app.birdo.vpn.ui.components.BirdoButton(
                    text = stringResource(R.string.subscription_manage_web),
                    onClick = onSelect,
                    variant = app.birdo.vpn.ui.components.BirdoButtonVariant.Secondary,
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    isLoading = isPurchasing,
                    enabled = !isPurchasing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Shown instead of purchase buttons when the account's paid plan was bought
 * outside Google Play. Not dismissible: it explains why there is nothing to
 * tap. Deliberately names no other purchase route — this is a Play build.
 */
@Composable
private fun ManagedElsewhereNotice() {
    val palette = BirdoColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = BirdoBlue.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = BirdoBlue,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.subscription_managed_elsewhere),
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurface,
            )
        }
    }
}

/**
 * One status banner. Extracted so the purchase-result banner and the
 * duplicate-billing banner cannot drift apart in colour or affordance.
 */
@Composable
private fun BillingBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val palette = BirdoColors.current
    Surface(
        // Announced: a purchase result is exactly what a TalkBack user must
        // not miss (A2-032).
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(12.dp),
        color = if (isError) BirdoRed.copy(alpha = 0.12f) else BirdoGreen.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (isError) BirdoRed else BirdoGreen,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) BirdoRed else BirdoGreen,
            )
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.dismiss),
                    color = palette.onSurfaceMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
