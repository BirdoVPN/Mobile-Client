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
import app.birdo.vpn.data.model.SubscriptionStatus
import app.birdo.vpn.data.model.UserProfile
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
@Composable
fun LimitScreen(
    user: UserProfile?,
    subscription: SubscriptionStatus?,
    onRefresh: () -> Unit,
    onUpgrade: () -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Plan header: name on the left, allowance pill on the right ──
        Column {
            SectionLabel("Your Plan")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    planDisplayName(subscription?.plan),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.onSurface,
                )
                if (subscription != null) {
                    AllowancePill(
                        text = if (hasCap) "$limitGb GB / month" else "Unlimited",
                        palette = palette,
                    )
                }
            }
        }

        BirdoCard(modifier = Modifier.fillMaxWidth()) {
            when {
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
                    val freshnessText = when {
                        neverSynced -> "Awaiting first sync — connect to start counting"
                        isFresh -> relativeAgo(subscription.bandwidthLastSyncAt)?.let { "Updated $it" }
                            ?: "Up to date"
                        else -> relativeAgo(subscription.bandwidthLastSyncAt)?.let { "Last update $it" }
                            ?: "May be delayed"
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
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(BirdoAccent.copy(alpha = if (palette.isLight) 0.16f else 0.14f))
                            .clickable(role = Role.Button, onClick = onRefresh)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = BirdoAccent,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "Refresh usage",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BirdoAccent,
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = palette.hairlineSoft, thickness = 1.dp)
                    Spacer(Modifier.height(14.dp))

                    // Used / Left / Resets — equal columns with hairline separators.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatCell("Used", formatGb(usedGb), meterColor, palette, Modifier.weight(1f))
                        StatDivider(palette)
                        StatCell("Left", formatGb(remainingGb), palette.onSurface, palette, Modifier.weight(1f))
                        StatDivider(palette)
                        StatCell(
                            "Resets",
                            formatResetDate(subscription.bandwidthPeriodEnd) ?: "Monthly",
                            palette.onSurface,
                            palette,
                            Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Counts uploads and downloads · Updates about every minute",
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
                            if (fraction >= 0.9f) "You're almost out of data" else "Need more data?",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.onSurface,
                        )
                    }
                    Text(
                        "Free accounts include $limitGb GB per month. Upgrade to Operative for unlimited data on every server.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = palette.onSurfaceMuted,
                    )
                    BirdoButton(
                        text = "Upgrade for unlimited",
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                gaugeValue(usedGb),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = usedTextColor,
            )
            Text(
                "of $limitGb GB used",
                fontSize = 13.sp,
                color = subTextColor,
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
            "Loading your usage…",
            fontSize = 13.sp,
            color = palette.onSurfaceMuted,
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
            "Unlimited data",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "Your plan has no data cap — use as much as you like.",
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
            .height(30.dp)
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
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

private fun planDisplayName(plan: String?): String = when (plan?.uppercase()) {
    "OPERATIVE" -> "Operative plan"
    "SOVEREIGN" -> "Sovereign plan"
    else -> "Free plan"
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

private fun formatGb(gb: Double): String {
    val safe = max(0.0, gb)
    return if (safe >= 10.0) "${safe.roundToInt()} GB"
    else "${(Math.round(safe * 100.0) / 100.0)} GB"
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

/** Relative "3 min ago" / "just now" from an ISO instant; null on parse failure. */
private fun relativeAgo(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val then = Instant.parse(iso)
        val secs = max(0L, Instant.now().epochSecond - then.epochSecond)
        when {
            secs < 60 -> "just now"
            secs < 3600 -> "${secs / 60} min ago"
            secs < 86400 -> "${secs / 3600} h ago"
            else -> "${secs / 86400} d ago"
        }
    } catch (_: Throwable) {
        null
    }
}
