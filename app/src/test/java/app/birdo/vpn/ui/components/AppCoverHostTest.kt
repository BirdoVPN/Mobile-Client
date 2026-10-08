package app.birdo.vpn.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MR-937 (REVIEW-AND-016): nothing stacks above the Hide App Contents cover.
 *
 * The cover used to be a Dialog, and a Compose dialog that opened after it got
 * a newer window, drawn above it. These run the real thing under Robolectric:
 * real Compose dialog and sheet windows, which the test rule finds as roots of
 * their own. While covered there must be NO dialog window (the cover is not
 * one, and the app's are not shown), and the cover must be on screen.
 *
 * SDK 34: Robolectric's SDK 35+ jars need Java 21, and CI runs 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppCoverHostTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var covered by mutableStateOf(false)

    private fun host(
        onBackWhileCovered: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
            MaterialTheme {
                AppCoverHost(covered = covered, onUnlock = {}, onBackWhileCovered = onBackWhileCovered) {
                    content()
                }
            }
        }
    }

    private fun assertCoverAloneOnScreen() {
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        rule.onNodeWithText(SECRET).assertDoesNotExist()
        rule.onNodeWithTag(APP_COVER_TAG).assertIsDisplayed()
    }

    @Test
    fun `a dialog open when the cover goes up is not shown while covered, and is back after unlock`() {
        host {
            BirdoAlertDialog(onDismissRequest = {}, confirmButton = { Text("OK") }, text = { Text(SECRET) })
        }
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText(SECRET).assertIsDisplayed()

        covered = true
        assertCoverAloneOnScreen()

        covered = false
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText(SECRET).assertIsDisplayed()
        rule.onNodeWithTag(APP_COVER_TAG).assertDoesNotExist()
    }

    /**
     * The review's case: an async result (the anonymous account number,
     * "Account deleted", a voucher result) opens its dialog AFTER the cover
     * went up. A Dialog cover put that window under the new one.
     */
    @Test
    fun `a dialog that opens while the cover is up does not appear until unlock`() {
        var resultArrived by mutableStateOf(false)
        covered = true
        host {
            if (resultArrived) {
                BirdoAlertDialog(onDismissRequest = {}, confirmButton = { Text("OK") }, text = { Text(SECRET) })
            }
        }
        assertCoverAloneOnScreen()

        resultArrived = true
        assertCoverAloneOnScreen()

        covered = false
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText(SECRET).assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `a bottom sheet is not shown while covered, and is back after unlock`() {
        host {
            BirdoModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState()) {
                Text(SECRET)
            }
        }
        rule.onNodeWithText(SECRET).assertExists()

        covered = true
        rule.onNodeWithText(SECRET).assertDoesNotExist()
        rule.onNodeWithTag(APP_COVER_TAG).assertIsDisplayed()

        covered = false
        rule.onNodeWithText(SECRET).assertExists()
    }

    @Test
    fun `the cover takes the touches and Back, so the hidden screen gets neither`() {
        var underClicks = 0
        var underBacks = 0
        var coverBacks = 0
        host(onBackWhileCovered = { coverBacks++ }) {
            BackHandler { underBacks++ }
            Box(Modifier.fillMaxSize().clickable { underClicks++ })
        }
        // Uncovered, both reach the screen: the assertions below can fail.
        rule.onRoot().performTouchInput { click(Offset(20f, 20f)) }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, underClicks)
        assertEquals(1, underBacks)

        covered = true
        rule.waitForIdle()
        rule.onRoot().performTouchInput { click(Offset(20f, 20f)) }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        assertEquals("a tap went through the cover", 1, underClicks)
        assertEquals("Back popped the hidden screen", 1, underBacks)
        assertEquals(1, coverBacks)
    }

    /**
     * The old Dialog's own window took the keyboard; this window still gets it.
     * Keys go in through the activity's real dispatch, as a hardware keyboard's do.
     */
    @Test
    fun `a hardware key never reaches the hidden screen, and Enter asks to unlock`() {
        val hidden = FocusRequester()
        var keysUnder = 0
        var unlocks = 0
        rule.setContent {
            MaterialTheme {
                AppCoverHost(covered = covered, onUnlock = { unlocks++ }, onBackWhileCovered = {}) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .focusRequester(hidden)
                            .focusTarget()
                            .onKeyEvent { keysUnder++; true },
                    )
                }
            }
        }
        // Uncovered, the screen gets its keys: the assertions below can fail.
        rule.runOnIdle { hidden.requestFocus() }
        pressKey(AndroidKeyEvent.KEYCODE_A)
        assertEquals(2, keysUnder)

        covered = true
        rule.waitForIdle()
        // Even with focus put back on the hidden screen.
        rule.runOnIdle { hidden.requestFocus() }
        for (code in listOf(AndroidKeyEvent.KEYCODE_TAB, AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_A)) {
            pressKey(code)
        }
        assertEquals("a key reached the hidden screen", 2, keysUnder)
        assertEquals(0, unlocks)

        pressKey(AndroidKeyEvent.KEYCODE_ENTER)
        assertEquals("a key reached the hidden screen", 2, keysUnder)
        assertEquals(1, unlocks)
    }

    private fun pressKey(code: Int) {
        rule.runOnUiThread {
            val decor = rule.activity.window.decorView
            decor.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, code))
            decor.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, code))
        }
        rule.waitForIdle()
    }

    /** The soft keyboard sends no key events: a focused field must give up focus instead. */
    @Test
    fun `a text field under the cover loses focus, so nothing can be typed into it`() {
        val field = FocusRequester()
        var fieldFocused = false
        host {
            BasicTextField(
                value = "",
                onValueChange = {},
                modifier = Modifier
                    .testTag("field")
                    .focusRequester(field)
                    .onFocusChanged { fieldFocused = it.isFocused },
            )
        }
        rule.runOnIdle { field.requestFocus() }
        rule.waitForIdle()
        assertTrue(fieldFocused)

        covered = true
        rule.waitForIdle()

        assertFalse(fieldFocused)
    }

    private companion object {
        const val SECRET = "838568571611234567890123"
    }
}
