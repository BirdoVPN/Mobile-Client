package app.birdo.vpn.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Speed
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
import kotlin.math.sin

/**
 * Anonymous-only "Limit" section: the free-tier (RECON) data allowance as a
 * speed-dial gauge. Reads /vpn/stats (bandwidthUsedGb / bandwidthLimitGb /
 * bandwidthPeriodEnd / bandwidthIsFresh) — real per-user usage synced from the
 * nodes every ~5 min. Honest about staleness (never presents a frozen number as
 * live) and hides the meter entirely for unlimited/paid subscriptions.
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeading("Data Limit")

        BirdoCard(modifier = Modifier.fillMaxWidth()) {
            if (!hasCap) {
                UnlimitedState(palette)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SpeedDialGauge(
                        fraction = fraction,
                        usedGb = usedGb,
                        limitGb = limitGb,
                        color = meterColor,
                        trackColor = palette.onSurface.copy(alpha = if (palette.isLight) 0.10f else 0.14f),
                        usedTextColor = palette.onSurface,
                        subTextColor = palette.onSurfaceMuted,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(228.dp),
                    )

                    // Freshness line — the meter is only as honest as the last sync.
                    val freshnessText = when {
                        neverSynced -> "Awaiting first sync — connect to start counting"
                        isFresh -> relativeAgo(subscription?.bandwidthLastSyncAt)?.let { "Updated $it" }
                            ?: "Up to date"
                        else -> "May be delayed — " + (relativeAgo(subscription?.bandwidthLastSyncAt)
                            ?.let { "last update $it" } ?: "awaiting sync")
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
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Used / Remaining / Resets breakdown.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatCell("Used", formatGb(usedGb), meterColor, palette.onSurfaceMuted)
                    StatCell("Remaining", formatGb(remainingGb), palette.onSurface, palette.onSurfaceMuted)
                    StatCell(
                        "Resets",
                        formatResetDate(subscription?.bandwidthPeriodEnd) ?: "Monthly",
                        palette.onSurface,
                        palette.onSurfaceMuted,
                    )
                }
            }
        }

        if (hasCap) {
            BirdoCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = BirdoAccent,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            if (fraction >= 0.9f) "You're almost out of data" else "Need more data?",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.onSurface,
                        )
                    }
                    Text(
                        "Free accounts include ${limitGb} GB per month. Upgrade to Operative for unlimited data on every server.",
                        fontSize = 13.sp,
                        color = palette.onSurfaceMuted,
                    )
                    BirdoButton(
                        text = "Upgrade for unlimited",
                        onClick = onUpgrade,
                        variant = BirdoButtonVariant.Primary,
                        size = BirdoButtonSize.Medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    BirdoButton(
                        text = "Refresh usage",
                        onClick = onRefresh,
                        variant = BirdoButtonVariant.Ghost,
                        size = BirdoButtonSize.Small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Speedometer-style arc: 270° sweep with a gap at the bottom, ticks + pointer. */
@Composable
private fun SpeedDialGauge(
    fraction: Float,
    usedGb: Double,
    limitGb: Long,
    color: Color,
    trackColor: Color,
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
    val strokePx = with(density) { 20.dp.toPx() }
    val tickLenPx = with(density) { 9.dp.toPx() }
    val tickWidthPx = with(density) { 2.dp.toPx() }
    val pointerRadiusPx = with(density) { 8.dp.toPx() }

    val startAngle = 135f
    val sweep = 270f

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = strokePx / 2f + tickLenPx
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
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = sweep * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            // Tick marks around the arc (11 ticks → 10 segments).
            val ticks = 10
            for (i in 0..ticks) {
                val a = Math.toRadians((startAngle + sweep * (i.toFloat() / ticks)).toDouble())
                val outer = radius + strokePx / 2f + with(density) { 3.dp.toPx() }
                val inner = outer + tickLenPx
                val cosA = cos(a).toFloat()
                val sinA = sin(a).toFloat()
                drawLine(
                    color = trackColor,
                    start = Offset(center.x + cosA * outer, center.y + sinA * outer),
                    end = Offset(center.x + cosA * inner, center.y + sinA * inner),
                    strokeWidth = tickWidthPx,
                    cap = StrokeCap.Round,
                )
            }
            // Pointer dot at the current value.
            val pa = Math.toRadians((startAngle + sweep * animated).toDouble())
            drawCircle(
                color = color,
                radius = pointerRadiusPx,
                center = Offset(
                    center.x + cos(pa).toFloat() * radius,
                    center.y + sin(pa).toFloat() * radius,
                ),
            )
        }

        // Center readout.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                formatGb(usedGb),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = usedTextColor,
            )
            Text(
                "of ${limitGb} GB",
                fontSize = 14.sp,
                color = subTextColor,
            )
            Text(
                "${(fraction * 100).toInt()}% used",
                fontSize = 12.sp,
                color = subTextColor,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun UnlimitedState(palette: app.birdo.vpn.ui.theme.BirdoSemanticPalette) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Speed,
            contentDescription = null,
            tint = BirdoAccent,
            modifier = Modifier.size(40.dp),
        )
        Text(
            "Unlimited data",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.onSurface,
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
private fun StatCell(label: String, value: String, valueColor: Color, labelColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
        Text(label, fontSize = 11.sp, color = labelColor, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        color = BirdoColors.current.onSurfaceMuted,
    )
}

private fun gaugeColor(fraction: Float): Color = when {
    fraction >= 0.9f -> BirdoRed
    fraction >= 0.75f -> BirdoYellow
    else -> BirdoAccent
}

private fun formatGb(gb: Double): String {
    val safe = max(0.0, gb)
    return if (safe >= 10.0) "${safe.toInt()} GB"
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
