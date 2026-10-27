package app.birdo.vpn.widget

import android.content.Context
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.service.VpnManager
import app.birdo.vpn.ui.viewmodel.SettingsViewModel
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * MR-2129 (AUD-MOB-13): the home-screen widget drew the server's location
 * whatever Settings → Show Server Location said, while the notification and
 * the Quick Settings tile honour it. The model's rule is tested with the tile's
 * in SessionSurfaceModelsTest; this pins the two places it is wired: the
 * widget's composition collects the setting (and the state it draws), and
 * changing the setting redraws the widget (it otherwise redraws only on a state
 * change). Glance itself does not run in a unit test, so the render is pinned
 * in its source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetLocationPrivacyTest {

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    @Test
    fun `the widget draws the server name the model allows, under the location setting`() {
        val file = File(repoRoot, "app/src/main/java/app/birdo/vpn/widget/BirdoWidget.kt")
        assertTrue("scan target is missing: $file", file.isFile)
        val source = file.readText().replace("\r\n", "\n")
        val render = source.substringAfter("override suspend fun provideGlance(").substringBefore("\nclass ")
        assertTrue("provideGlance has no provideContent", "provideContent {" in render)
        val beforeComposition = render.substringBefore("provideContent {")
        val composition = render.substringAfter("provideContent {")

        // MR-2129 follow-up: Glance keeps a session's composition alive and
        // update() does not re-run provideGlance while it is, so the model is
        // built from values the composition collects, never from ones read
        // before it (the server's name outlived Show Server Location going
        // off, and a Connecting → Connected change inside a session was lost).
        listOf(
            "val state by stateFlow.collectAsState()",
            "val connectedServer by serverFlow.collectAsState()",
            "val killSwitchActive by BirdoVpnService.killSwitchActiveFlow.collectAsState()",
            "val showLocation by appPreferences.showLocationInNotificationFlow",
            "showLocation = showLocation,",
            "BirdoWidgetContent(model = model, serverName = model.serverLabel)",
        ).forEach { assertTrue("the composition must read: $it", it in composition) }
        assertFalse("the model is built before the composition", "widgetModel(" in beforeComposition)
        assertFalse("a value is read before the composition", ".value" in beforeComposition)
        assertFalse("serverName = serverName" in render)
    }

    @Test
    fun `changing Show Server Location redraws the widget, on the manager's scope`() {
        val prefs = mockk<AppPreferences>(relaxed = true)
        val vpnManager = mockk<VpnManager>(relaxed = true)
        val vm = SettingsViewModel(prefs, mockk<Context>(relaxed = true), vpnManager)

        vm.setShowLocationInNotification(true)

        verify { prefs.showLocationInNotification = true }
        // Not this ViewModel's scope, which its screen can cancel first.
        verify(exactly = 1) { vpnManager.requestWidgetRefresh() }

        vm.setShowLocationInNotification(false)
        verify(exactly = 2) { vpnManager.requestWidgetRefresh() }
    }
}
