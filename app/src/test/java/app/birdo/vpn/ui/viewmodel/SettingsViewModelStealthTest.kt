package app.birdo.vpn.ui.viewmodel

import android.content.Context
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.service.VpnManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The stored Stealth setting and the two things that follow it: VpnManager's
 * "not in your plan" notice, and this screen's own state (final review of
 * #463, #5 and #6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelStealthTest {

    private lateinit var prefs: AppPreferences
    private lateinit var vpnManager: VpnManager

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        prefs = mockk(relaxed = true)
        vpnManager = mockk(relaxed = true)
        every { prefs.stealthModeEnabled } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel() = SettingsViewModel(prefs, mockk<Context>(relaxed = true), vpnManager)

    @Test
    fun `changing Stealth in Settings ends the not-in-plan notice`() {
        val vm = viewModel()

        vm.setStealthMode(false)

        // The notice kept deciding rebuild eligibility past the change (#5).
        verify(exactly = 1) { vpnManager.onStealthSettingChanged() }
        verify(exactly = 1) { vpnManager.requestSettingsReapply() }
    }
}
