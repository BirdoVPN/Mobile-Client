package app.birdo.vpn.perf

import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.metrics.performance.PerformanceMetricsState
import app.birdo.vpn.BuildConfig

/**
 * On-device, in-memory frame-timing instrumentation for the Home-screen globe.
 *
 * ## What this is allowed to know
 *
 * Frame durations and nothing else. No account, no server, no country, no
 * network state, no wall-clock, no persistence, no network sink. The only
 * per-frame label it attaches is which globe quality tier was on screen —
 * three fixed strings, [STATE_FULL] / [STATE_LITE] / [STATE_OFF], enumerated in
 * [STATE_VALUES] so a test can pin that the set never grows into something
 * user-specific. Pinned by `PrivacyBoundaryTest` (P6-CLI-PERF-01).
 *
 * ## Why it is not on in release by default
 *
 * Play Vitals already reports slow and frozen frames for the Play cohort, for
 * free, with no code and no data of ours. This exists to answer the one
 * question Vitals structurally cannot — *which part of the screen* costs the
 * frames — and that question is answered on a device you are holding, not by a
 * fleet. So it is a developer instrument: on in debug, and available in a real
 * minified release build only when someone deliberately passes
 * `-PperfOverlay=true`, because a debug build's frame times are not the frame
 * times users get.
 */
internal object GlobePerf {

    /**
     * `BuildConfig.DEBUG` and `BuildConfig.PERF_OVERLAY` are both `static final
     * boolean` constants, so in a normal release build this folds to `false` and
     * R8 removes every call site and this whole package with it.
     */
    @JvmField
    val ENABLED: Boolean = BuildConfig.DEBUG || BuildConfig.PERF_OVERLAY

    /** JankStats per-frame state key. */
    const val STATE_KEY = "globe"

    const val STATE_FULL = "full"
    const val STATE_LITE = "lite"
    const val STATE_OFF = "off"

    /**
     * The complete set of values that may ever be attached to a frame. Adding a
     * value that varies with the user (a server id, a country, a plan) would
     * turn a frame histogram into a behavioural record; this list is asserted
     * verbatim by the privacy test so that change cannot be made quietly.
     */
    val STATE_VALUES = listOf(STATE_FULL, STATE_LITE, STATE_OFF)
}

/**
 * Holds the current globe tag and pushes it into JankStats' per-frame state.
 *
 * A [PerformanceMetricsState] holder only has a live `state` once JankStats is
 * tracking the window, and the globe composes before the overlay attaches — so
 * the tag is also kept here and re-pushed by [flush] once tracking starts.
 * Without that, every frame in the first session would be labelled `off`.
 */
internal object GlobePerfState {

    @Volatile
    var current: String = GlobePerf.STATE_OFF
        private set

    /**
     * The last tier the globe actually rendered at, or `null` before it has
     * rendered at all. NOT the same thing as [GlobePerfControls.forcedLite]:
     * that flag is `null` on any device running its auto-assigned tier, and on
     * a low-RAM device the auto tier is LITE — so reading the override to
     * decide which histogram holds "the globe on" points at an empty FULL
     * bucket on exactly the old hardware this instrument exists to investigate.
     *
     * Deliberately keeps its value while the globe is hidden ([current] goes to
     * [GlobePerf.STATE_OFF] then): hiding the globe is how the OFF baseline is
     * collected, and the delta row still has to know which tier it is
     * subtracting that baseline from.
     */
    @Volatile
    var lastActive: String? = null
        private set

    private var holder: PerformanceMetricsState.Holder? = null

    fun set(view: View, value: String) {
        if (!GlobePerf.ENABLED) return
        current = value
        if (value != GlobePerf.STATE_OFF) lastActive = value
        val h = PerformanceMetricsState.getHolderForHierarchy(view)
        holder = h
        h.state?.putState(GlobePerf.STATE_KEY, value)
    }

    /** Re-applies [current] once JankStats has started tracking the window. */
    fun flush() {
        if (!GlobePerf.ENABLED) return
        holder?.state?.putState(GlobePerf.STATE_KEY, current)
    }
}

/**
 * Debug-only render controls the overlay drives.
 *
 * [globeHidden] exists for a methodological reason, not convenience. The app
 * already stops drawing the globe when the server sheet is open, which looks
 * like a free A/B — but that baseline is contaminated by the sheet's own scrim,
 * list and animation. To attribute cost to the globe you need a baseline where
 * the ONLY thing that changed is the globe, and that is what this toggle gives.
 */
internal object GlobePerfControls {
    /** `null` = use the tier the device was auto-assigned. */
    val forcedLite = mutableStateOf<Boolean?>(null)
    val globeHidden = mutableStateOf(false)
    val overlayExpanded = mutableStateOf(true)
}
