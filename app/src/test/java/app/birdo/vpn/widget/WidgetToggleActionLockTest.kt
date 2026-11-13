package app.birdo.vpn.widget

import android.app.KeyguardManager
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.service.VpnManager
import app.birdo.vpn.service.VpnManagerEntryPoint
import app.birdo.vpn.service.VpnState
import dagger.hilt.android.EntryPointAccessors
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/** F-968-01: the widget's DISCONNECT needs an unlocked device (like the tile and notification). */
class WidgetToggleActionLockTest {

    private lateinit var manager: VpnManager
    private lateinit var context: Context
    private var keyguard: KeyguardManager? = null

    @Before
    fun setUp() {
        manager = mockk(relaxed = true)
        every { manager.state } returns MutableStateFlow<VpnState>(VpnState.Connecting)
        every { manager.isVpnPermissionGranted() } returns true
        every { manager.claimTapForResume() } returns false
        val tokens = mockk<TokenManager> { every { isLoggedIn() } returns true }
        val entryPoint = mockk<VpnManagerEntryPoint> {
            every { vpnManager() } returns manager
            every { tokenManager() } returns tokens
        }
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.KEYGUARD_SERVICE) } answers { keyguard }
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(any<Context>(), VpnManagerEntryPoint::class.java)
        } returns entryPoint
        mockkConstructor(AppPreferences::class)
        every { anyConstructed<AppPreferences>().hasAcceptedCurrentConsent } returns true
        mockkConstructor(BirdoWidget::class)
        coEvery { anyConstructed<BirdoWidget>().update(any(), any<GlanceId>()) } just Runs
    }

    @After
    fun tearDown() = unmockkAll()

    private fun tap() = runBlocking {
        WidgetToggleAction().onAction(
            context,
            mockk<GlanceId>(relaxed = true),
            mockk<ActionParameters>(relaxed = true),
        )
    }

    @Test
    fun `locked device - no disconnect, the app opens for the unlock`() {
        keyguard = mockk { every { isDeviceLocked } returns true }
        tap()
        verify(exactly = 0) { manager.requestDisconnect() }
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun `unknown lock state fails closed`() {
        keyguard = null
        tap()
        verify(exactly = 0) { manager.requestDisconnect() }
    }

    @Test
    fun `unlocked device - disconnect runs`() {
        keyguard = mockk { every { isDeviceLocked } returns false }
        tap()
        verify(exactly = 1) { manager.requestDisconnect() }
    }
}
