package app.birdo.vpn.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween

/**
 * Birdo motion tokens. Centralizes durations & easings so screens animate
 * with a coherent rhythm.
 */
object BirdoMotion {
    // Durations (ms)
    const val Instant = 90
    const val Quick   = 160
    const val Standard = 240
    const val Emphasis = 360
    const val Slow = 520

    // Easings
    val EaseStandard: Easing = CubicBezierEasing(0.2f, 0.0f, 0f, 1.0f)   // emphasized decelerate
    val Accel: Easing    = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val Decel: Easing    = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val Spring: Easing   = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f) // gentle overshoot

    fun standardTween(durationMs: Int = Standard) = tween<Float>(durationMs, easing = EaseStandard)
    fun emphasisTween(durationMs: Int = Emphasis) = tween<Float>(durationMs, easing = Decel)
}
