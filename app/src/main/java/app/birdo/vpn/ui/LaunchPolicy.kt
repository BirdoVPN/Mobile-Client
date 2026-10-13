package app.birdo.vpn.ui

import android.os.Build

/**
 * When the Hide App Contents cover goes up. Pure so the rules are pinned by
 * unit tests; MainActivity only feeds them lifecycle facts.
 */
internal object AppCoverPolicy {

    /**
     * On create: covered when the feature is on, unless this is a RECREATION of
     * an activity that was uncovered. Folding or unfolding a foldable, or the
     * system switching dark mode, recreates the activity, and each one used to
     * raise the cover and a fresh biometric prompt in the middle of use (A2-046).
     * [savedCovered] is null on a real cold start.
     */
    fun coveredAtCreate(enabled: Boolean, savedCovered: Boolean?): Boolean =
        enabled && (savedCovered ?: true)

    /**
     * On stop: raise the cover so returning to the app asks again, except
     * while a prompt is up (the device-credential fallback backgrounds us, and
     * covering there loops the prompt) and during a configuration-change
     * recreation, which is not the user leaving.
     */
    fun coverOnStop(enabled: Boolean, authenticating: Boolean, changingConfigurations: Boolean): Boolean =
        enabled && !authenticating && !changingConfigurations

    /** What a hardware key does while the cover is up. */
    enum class CoverKey { PASS, UNLOCK, DROP }

    /**
     * A hardware key while covered (MR-937). The cover is drawn in the app's
     * own window now, not a Dialog window of its own that took the keyboard,
     * so the app underneath would still get Tab, the arrows and Enter: focus
     * moved onto a control nobody can see, and Enter pressed it. Every key is
     * dropped except the system ones (Back, which the cover turns into "leave
     * the app"; volume; media), and Enter, Space or the D-pad centre, released,
     * asks to unlock like the cover's button.
     */
    fun keyWhileCovered(covered: Boolean, systemKey: Boolean, confirmReleased: Boolean): CoverKey = when {
        !covered || systemKey -> CoverKey.PASS
        confirmReleased -> CoverKey.UNLOCK
        else -> CoverKey.DROP
    }
}

/**
 * Whether to explain, and then ask for, the notification permission now.
 *
 * Asked ONCE per install, in context: when the first tunnel starts coming up,
 * the moment the connection notification becomes relevant. It used to be a
 * bare system prompt fired from onCreate, over the consent screen on first
 * launch and again on the next launch after a denial (A2-026). Only Android 13+
 * has a runtime notification permission.
 */
internal fun shouldExplainNotifications(
    sdkInt: Int,
    granted: Boolean,
    alreadyExplained: Boolean,
    tunnelStarting: Boolean,
): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !alreadyExplained && tunnelStarting
