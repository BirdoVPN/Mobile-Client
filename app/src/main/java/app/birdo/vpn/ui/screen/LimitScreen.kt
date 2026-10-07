package app.birdo.vpn.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import app.birdo.vpn.ui.components.BirdoPullToRefresh
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import app.birdo.vpn.R
import app.birdo.vpn.data.model.SubscriptionStatus
import app.birdo.vpn.ui.components.BirdoButton
import app.birdo.vpn.ui.components.BirdoButtonSize
import app.birdo.vpn.ui.components.BirdoButtonVariant
import app.birdo.vpn.ui.components.BirdoCard
import app.birdo.vpn.ui.theme.BirdoAccent
import app.birdo.vpn.ui.theme.BirdoColors
import app.birdo.vpn.ui.theme.BirdoRed
import app.birdo.vpn.ui.theme.BirdoYellow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * "Limit" section, shown for every user type: the current plan and its data
 * allowance. Free (RECON) plans get a speed-dial gauge of the 10 GB/mo cap;
 * paid plans get the unlimited state. Reads /vpn/stats (bandwidthUsedGb /
 * bandwidthLimitGb / bandwidthPeriodEnd / bandwidthIsFresh) — real per-user
 * usage of BOTH directions (uploads + downloads), synced from the nodes about
 * every minute and flushed on disconnect. Honest about staleness: never
 * presents a frozen number as live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitScreen(
    subscription: SubscriptionStatus?,
    onRefresh: () -> Unit,
    onUpgrade: () -> Unit,
    /** A fetch is in flight: the refresh control says so instead of seeming to do nothing. */
    isLoading: Boolean = false,
    /**
     * Why the last fetch failed. With no data yet this replaces the spinner,
     * which used to turn forever on any failure (A2-018); with data, the
     * stale figure stays up and this sits beside it.
     */
    error: String? = null,
) {
    val palette = BirdoColors.current

    val limitGb = subscription?.bandwidthLimitGb ?: 0L
    // 0 (coerced from null) == unlimited: paid plan or an active reward window.
    val hasCap = limitGb > 0L
    val usedGb = subscription?.bandwidthUsedGb ?: 0.0
    val neverSynced = subscription?.bandwidthLastSyncAt == null
    val isFresh = subscription?.bandwidthIsFresh == true

    val fraction = if (hasCap) (usedGb / limitGb).coerceIn(0.0, 1.0).toFloat() else 0f
    val meterColor = gaugeColor(fraction)
    val remainingGb = max(0.0, limitGb - usedGb)

    // Pull-to-refresh, as on iOS (A2-023).
    BirdoPullToRefresh(
        isLoading = isLoading,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            // A tab with no top bar owns its status-bar inset (A2-010).
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Plan header: name on the left, allowance pill on the right ──
            Column {
                SectionLabel(stringResource(R.string.limit_section_your_plan))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The name takes what the pill leaves and wraps: at 200 %
                    // font scale "Sovereign plan" measured first and squeezed
                    // the pill (A2-024).
                    Text(
                        planDisplayName(subscription?.plan),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (subscription != null) {
                        Spacer(Modifier.width(8.dp))
                        AllowancePill(
                            text = if (hasCap) {
                                stringResource(R.string.data_gb_per_month, limitGb)
                            } else {
                                stringResource(R.string.subscription_unlimited)
                            },
                            palette = palette,
                        )
                    }
                }
            }

            if (error != null && subscription != null) {
                // Stale figure below, the reason here.
                Text(
                    error,
                    fontSize = 12.sp,
                    color = BirdoRed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            BirdoCard(modifier = Modifier.fillMaxWidth()) {
                when {
                    subscription == null && error != null && !isLoading ->
                        LoadFailedState(message = error, onRetry = onRefresh, palette = palette)
                    subscription == null -> LoadingState(palette)
                    !hasCap -> UnlimitedState(palette)
                    else -> Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        SpeedDialGauge(
                            fraction = fraction,
                            usedGb = usedGb,
                            limitGb = limitGb,
                            color = meterColor,
                            trackColor = palette.onSurface.copy(alpha = if (palette.isLight) 0.09f else 0.14f),
                            knobCenterColor = palette.surface,
                            usedTextColor = palette.onSurface,
                            subTextColor = palette.onSurfaceMuted,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(232.dp),
                        )

                        // Freshness — the meter is only as honest as the last sync.
                        val syncedAgo = syncAge(subscription.bandwidthLastSyncAt, Instant.now())?.text()
                        val freshnessText = when {
                            neverSynced -> stringResource(R.string.limit_sync_awaiting)
                            isFresh -> syncedAgo?.let { stringResource(R.string.limit_sync_updated, it) }
                                ?: stringResource(R.string.limit_sync_up_to_date)
                            else -> syncedAgo?.let { stringResource(R.string.limit_sync_last_update, it) }
                                ?: stringResource(R.string.limit_sync_may_be_delayed)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isFresh) BirdoAccent else palette.onSurfaceFaint),
                            )
                            Text(
                                freshnessText,
                                fontSize = 12.sp,
                                color = palette.onSurfaceMuted,
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        // Prominent refresh: a tonal accent pill, unmistakably a button.
                        // It shows its own progress: before, a tap changed nothing
                        // on screen until (or unless) the number moved (A2-018).
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(BirdoAccent.copy(alpha = if (palette.isLight) 0.16f else 0.14f))
                                .clickable(enabled = !isLoading, role = Role.Button, onClick = onRefresh)
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = BirdoAccent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    tint = BirdoAccent,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Text(
                                stringResource(if (isLoading) R.string.limit_refreshing else R.string.limit_refresh),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BirdoAccent,
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = palette.hairlineSoft, thickness = 1.dp)
                        Spacer(Modifier.height(14.dp))

                        // Used / Left / Resets — equal columns with hairline separators.
                        // Intrinsic height, so the separators grow with cells
                        // whose text wraps at large font scales (A2-024).
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatCell(
                                stringResource(R.string.limit_stat_used),
                                stringResource(R.string.data_gb, gbFigure(usedGb)),
                                meterColor,
                                palette,
                                Modifier.weight(1f),
                            )
                            StatDivider(palette)
                            StatCell(
                                stringResource(R.string.limit_stat_left),
                                stringResource(R.string.data_gb, gbFigure(remainingGb)),
                                palette.onSurface,
                                palette,
                                Modifier.weight(1f),
                            )
                            StatDivider(palette)
                            StatCell(
                                stringResource(R.string.limit_stat_resets),
                                formatResetDate(subscription.bandwidthPeriodEnd)
                                    ?: stringResource(R.string.limit_resets_monthly),
                                palette.onSurface,
                                palette,
                                Modifier.weight(1f),
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            stringResource(R.string.limit_counting_note),
                            fontSize = 11.sp,
                            color = palette.onSurfaceFaint,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (subscription != null && hasCap) {
                BirdoCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(BirdoAccent.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Bolt,
                                    contentDescription = null,
                                    tint = BirdoAccent,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                            Text(
                                stringResource(
                                    if (fraction >= 0.9f) R.string.limit_upsell_almost_out
                                    else R.string.limit_upsell_need_more,
                                ),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.onSurface,
                            )
                        }
                        Text(
                            stringResource(R.string.limit_upsell_body, limitGb),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = palette.onSurfaceMuted,
                        )
                        BirdoButton(
                            text = stringResource(R.string.limit_upsell_cta),
                            onClick = onUpgrade,
                            variant = BirdoButtonVariant.Primary,
                            size = BirdoButtonSize.Medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Speedometer arc: 270° sweep with the gap at the bottom. Clean track +
 * progress + a knob at the current position; the percentage readout sits in
 * the bottom gap so the centre stays uncluttered.
 */
@Composable
private fun SpeedDialGauge(
    fraction: Float,
    usedGb: Double,
    limitGb: Long,
    color: Color,
    trackColor: Color,
    knobCenterColor: Color,
    usedTextColor: Color,
    subTextColor: Color,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 900),
        label = "gaugeFill",
    )
    val density = LocalDensity.current
    val strokePx = with(density) { 18.dp.toPx() }
    val knobOuterPx = with(density) { 13.dp.toPx() }
    val knobInnerPx = with(density) { 5.5.dp.toPx() }

    val startAngle = 135f
    val sweep = 270f

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // The knob is the widest element riding the arc — inset for it so
            // nothing clips at the edges and the arc stays optically centred.
            val inset = max(strokePx / 2f, knobOuterPx) + 2f
            val diameter = min(size.width, size.height) - inset * 2f
            val topLeft = Offset(
                (size.width - diameter) / 2f,
                (size.height - diameter) / 2f,
            )
            val arcSize = Size(diameter, diameter)
            val radius = diameter / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Track.
            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            // Progress.
            if (animated > 0f) {
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
            // Knob at the current position: colored ring + surface-colored core.
            val pa = Math.toRadians((startAngle + sweep * animated).toDouble())
            val knobCenter = Offset(
                center.x + cos(pa).toFloat() * radius,
                center.y + sin(pa).toFloat() * radius,
            )
            drawCircle(color = color, radius = knobOuterPx, center = knobCenter)
            drawCircle(color = knobCenterColor, radius = knobInnerPx, center = knobCenter)
        }

        // Centre readout: the number is the hero, the unit line supports it.
        // Both SHRINK to fit rather than overflow the fixed ring: at 200 %
        // font scale the 40 sp figure used to spill across the arc (A2-024).
        // The ring keeps its size; the text inside it adapts.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 36.dp),
        ) {
            BasicText(
                gaugeValue(usedGb),
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = usedTextColor,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 40.sp),
            )
            BasicText(
                stringResource(R.string.limit_gauge_of_used, limitGb),
                style = TextStyle(color = subTextColor, textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 13.sp),
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // Percentage sits in the bottom gap of the arc.
        Text(
            "${(fraction * 100).roundToInt()}%",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = subTextColor,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp),
        )
    }
}

@Composable
private fun LoadingState(palette: app.birdo.vpn.ui.theme.BirdoSemanticPalette) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            color = BirdoAccent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(32.dp),
        )
        Text(
            stringResource(R.string.limit_loading),
            fontSize = 13.sp,
            color = palette.onSurfaceMuted,
        )
    }
}

/** No figure to show and the fetch failed: say why and offer the way out. */
@Composable
private fun LoadFailedState(
    message: String,
    onRetry: () -> Unit,
    palette: app.birdo.vpn.ui.theme.BirdoSemanticPalette,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            message,
            fontSize = 13.sp,
            color = palette.onSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        BirdoButton(
            text = stringResource(R.string.retry),
            onClick = onRetry,
            variant = BirdoButtonVariant.Secondary,
            icon = Icons.Filled.Refresh,
        )
    }
}

@Composable
private fun UnlimitedState(palette: app.birdo.vpn.ui.theme.BirdoSemanticPalette) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(BirdoAccent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Speed,
                contentDescription = null,
                tint = BirdoAccent,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            stringResource(R.string.unlimited_data),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(R.string.limit_unlimited_body),
            fontSize = 13.sp,
            color = palette.onSurfaceMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    valueColor: Color,
    palette: app.birdo.vpn.ui.theme.BirdoSemanticPalette,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            color = palette.onSurfaceMuted,
        )
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun StatDivider(palette: app.birdo.vpn.ui.theme.BirdoSemanticPalette) {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight(0.8f)
            .background(palette.hairlineSoft),
    )
}

@Composable
private fun AllowancePill(text: String, palette: app.birdo.vpn.ui.theme.BirdoSemanticPalette) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(BirdoAccent.copy(alpha = if (palette.isLight) 0.12f else 0.16f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = BirdoAccent,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        color = BirdoColors.current.onSurfaceMuted,
        modifier = Modifier
            .padding(start = 4.dp, bottom = 6.dp)
            .semantics { heading() },
    )
}

@Composable
private fun planDisplayName(plan: String?): String = when (plan?.uppercase()) {
    "OPERATIVE" -> stringResource(R.string.plan_title_operative)
    "SOVEREIGN" -> stringResource(R.string.plan_title_sovereign)
    else -> stringResource(R.string.plan_title_free)
}

private fun gaugeColor(fraction: Float): Color = when {
    fraction >= 0.9f -> BirdoRed
    fraction >= 0.75f -> BirdoYellow
    else -> BirdoAccent
}

/** Big dial number: "0.52", "1.7", "10" — unit lives in the line below. */
private fun gaugeValue(gb: Double): String {
    val safe = max(0.0, gb)
    return when {
        safe >= 10.0 -> "${safe.roundToInt()}"
        safe >= 1.0 -> "${(Math.round(safe * 10.0) / 10.0)}"
        else -> "${(Math.round(safe * 100.0) / 100.0)}"
    }
}

/** "0.52", "7.1", "12": the figure for a data_gb label. */
private fun gbFigure(gb: Double): String {
    val safe = max(0.0, gb)
    return if (safe >= 10.0) "${safe.roundToInt()}"
    else "${(Math.round(safe * 100.0) / 100.0)}"
}

/** "2026-07-31T23:59:59.999Z" → "Jul 31"; null/parse-fail → null. */
private fun formatResetDate(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val instant = Instant.parse(iso)
        DateTimeFormatter.ofPattern("MMM d")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    } catch (_: Throwable) {
        null
    }
}

/** How long ago the meter last synced, in the unit the freshness line names. */
internal sealed interface SyncAge {
    data object JustNow : SyncAge
    data class Minutes(val count: Int) : SyncAge
    data class Hours(val count: Int) : SyncAge
    data class Days(val count: Int) : SyncAge
}

/**
 * The age of an ISO instant at [now]; null on a parse failure. A sync stamped
 * after [now] (a phone clock behind the server's) reads as just now. Pure, so
 * the buckets are tested; the words are `<plurals>` (A2-031).
 */
internal fun syncAge(iso: String?, now: Instant): SyncAge? {
    if (iso.isNullOrBlank()) return null
    val then = try {
        Instant.parse(iso)
    } catch (_: Throwable) {
        return null
    }
    val secs = max(0L, now.epochSecond - then.epochSecond)
    return when {
        secs < 60 -> SyncAge.JustNow
        secs < 3600 -> SyncAge.Minutes((secs / 60).toInt())
        secs < 86400 -> SyncAge.Hours((secs / 3600).toInt())
        else -> SyncAge.Days((secs / 86400).toInt())
    }
}

/** "just now", "3 min ago", "2 h ago", "5 d ago". */
@Composable
private fun SyncAge.text(): String = when (this) {
    SyncAge.JustNow -> stringResource(R.string.limit_ago_just_now)
    is SyncAge.Minutes -> pluralStringResource(R.plurals.limit_ago_minutes, count, count)
    is SyncAge.Hours -> pluralStringResource(R.plurals.limit_ago_hours, count, count)
    is SyncAge.Days -> pluralStringResource(R.plurals.limit_ago_days, count, count)
}
