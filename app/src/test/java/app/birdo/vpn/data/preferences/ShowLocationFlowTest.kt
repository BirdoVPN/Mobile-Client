package app.birdo.vpn.data.preferences

import android.os.Looper
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * MR-2129: the home-screen widget's composition follows Show Server Location
 * through this flow, so a change reaches a widget whose Glance session is
 * still alive. Written through a second instance, as Settings' is.
 *
 * SDK 34: Robolectric's SDK 35+ jars need Java 21, and CI runs 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShowLocationFlowTest {

    @Test
    fun `the flow gives the setting now and on every change`() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val widgets = AppPreferences(app)
        val settings = AppPreferences(app)

        widgets.showLocationInNotificationFlow.test {
            assertEquals(false, awaitItem())

            settings.showLocationInNotification = true
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(true, awaitItem())

            settings.showLocationInNotification = false
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(false, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
