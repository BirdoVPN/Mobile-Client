package app.birdo.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import app.birdo.vpn.ui.theme.BirdoColors
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Animated pixel canvas background matching the Windows client.
 *
 * Renders a full-screen grid of subtly twinkling white squares on a black
 * background. Each pixel fades in/out randomly at low alpha (0-0.15),
 * creating an ambient, alive feeling.
 *
 * Performance: Only ~0.1% of pixels change per frame. We use a flat IntArray
 * for alpha values (0-255 range mapped to 0-0.25 opacity) and redraw at
 * ~15 fps to keep GPU/CPU usage minimal.
 */
@Composable
fun PixelCanvas(
    modifier: Modifier = Modifier,
    pixelSize: Float = 20f,
    gapSize: Float = 1f,
) {
    // Pixel grid state — use a frame counter to trigger recomposition
    var frame by remember { mutableIntStateOf(0) }

    // Grid data — lazy-init on first composition
    val gridState = remember { PixelGridState() }

    // Power: this canvas sits behind EVERY screen, and its ticker is a plain
    // `while (true)` in a LaunchedEffect — which keeps running for as long as
    // the composition is alive, i.e. while the Activity is merely STOPPED.
    // That is 15 wakeups a second, each walking the whole grid, while the phone
    // is in the user's pocket and nothing is on screen. Gate it on the
    // lifecycle: no frames unless we are actually visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    var animating by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> animating = true
                Lifecycle.Event.ON_STOP -> animating = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Animation loop at ~15 fps (66ms) — enough for subtle twinkling
    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        while (true) {
            delay(66L)
            gridState.tick()
            frame++
        }
    }

    // White squares at up to 20% alpha read as a faint shimmer on OLED black, but
    // on the dim-light theme's slate (#1B1C24) the same squares are a blotchy,
    // dirty-looking checkerboard — they have far less contrast to hide behind.
    // Damp them hard on the lighter surface so the texture stays ambient.
    val intensity = if (BirdoColors.current.isLight) 0.3f else 1f

    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val cellSize = pixelSize + gapSize

        // Initialize or resize grid if needed
        val cols = (canvasWidth / cellSize).toInt() + 1
        val rows = (canvasHeight / cellSize).toInt() + 1
        gridState.ensureSize(cols, rows)

        // Force read of frame to subscribe to changes
        @Suppress("UNUSED_VARIABLE") val f = frame

        // Draw pixels
        val pixelSizeObj = Size(pixelSize, pixelSize)
        for (row in 0 until gridState.rows) {
            for (col in 0 until gridState.cols) {
                val alpha = gridState.getAlpha(col, row) * intensity
                if (alpha > 0.005f) {
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(col * cellSize, row * cellSize),
                        size = pixelSizeObj,
                        alpha = alpha,
                    )
                }
            }
        }
    }
}

/**
 * Manages the pixel grid state efficiently using flat arrays.
 * Each pixel has a current alpha and a target alpha, and slowly
 * transitions between them.
 */
private class PixelGridState {
    var cols = 0
        private set
    var rows = 0
        private set

    // Current alpha (0f-0.25f) stored as Int (0-250) for memory efficiency
    private var currentAlpha = IntArray(0)
    // Target alpha
    private var targetAlpha = IntArray(0)
    // Speed (int, maps to 0.002-0.006)
    private var speed = IntArray(0)

    fun ensureSize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        cols = newCols
        rows = newRows
        val total = cols * rows
        currentAlpha = IntArray(total) { Random.nextInt(0, 10) } // 0-0.10 initial
        targetAlpha = IntArray(total) { Random.nextInt(0, 5) }   // some start with a target
        speed = IntArray(total) { 1 + Random.nextInt(3) }        // 1-3 for smoother transitions
    }

    fun tick() {
        val total = cols * rows
        if (total == 0) return

        // Retarget ~0.3% of pixels per frame. Rolling the dice ONCE PER PIXEL to
        // make a 0.3% decision meant ~1k RNG calls every frame (15k/second) to
        // change ~3 pixels. Sampling that many indices directly is statistically
        // the same twinkle for a fraction of the work.
        val retargets = ((total * 0.003f) + 0.5f).toInt().coerceAtLeast(1)
        repeat(retargets) {
            val i = Random.nextInt(total)
            targetAlpha[i] = Random.nextInt(0, 20) // 0-0.20 alpha range
        }

        // Ease every pixel toward its target (cheap int math, no allocation).
        for (i in 0 until total) {
            val cur = currentAlpha[i]
            val target = targetAlpha[i]
            val spd = speed[i]

            if (cur < target) {
                currentAlpha[i] = minOf(cur + spd, target)
            } else if (cur > target) {
                currentAlpha[i] = maxOf(cur - spd, target)
            }
        }
    }

    fun getAlpha(col: Int, row: Int): Float {
        val idx = row * cols + col
        if (idx < 0 || idx >= currentAlpha.size) return 0f
        // Map 0-20 → 0-0.20 alpha
        return currentAlpha[idx] / 100f
    }
}
