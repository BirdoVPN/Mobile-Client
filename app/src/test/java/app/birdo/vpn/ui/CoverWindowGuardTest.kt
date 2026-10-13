package app.birdo.vpn.ui

import app.birdo.vpn.testing.KotlinSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-937 (REVIEW-AND-016): no window may stack above the Hide App Contents
 * cover. The cover is drawn in the activity's own window, so a dialog, sheet,
 * popup or menu window would be drawn over it; AppCoverHostTest proves the
 * wrappers in AppCover.kt show none while it is up. This proves nothing goes
 * around them: a window-creating call anywhere else fails the build.
 *
 * Out of scope: Toast. A toast is a system window holding one line the app
 * chose; it showed over the old Dialog cover just the same, and nobody can
 * act on it. Today's are VpnManager's settings-reapply notices and the
 * "copied" confirmations in LoginScreen and ProfileScreen.
 */
class CoverWindowGuardTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    /** The one file allowed to open a window: the cover-aware wrappers. */
    private val allowed = "app/src/main/java/app/birdo/vpn/ui/components/AppCover.kt"

    /**
     * A call to anything whose NAME ends like a window, with any prefix:
     * Compose's Dialog, AlertDialog, BasicAlertDialog, ModalBottomSheet, Popup,
     * DropdownMenu, ExposedDropdownMenu, TooltipBox and BasicTooltipBox, and
     * ProgressDialog, AppCompatDialog, DatePickerDialog, BottomSheetDialog,
     * ComponentDialog, PopupWindow, ListPopupWindow and PopupMenu. A fixed
     * list let AppCompatDialog and BasicTooltipBox through. A function or
     * class the app declares itself is judged by its own body instead
     * (SignOutConfirmDialog calls BirdoAlertDialog).
     */
    private val windowSuffix = Regex(
        """(?<!\w)([A-Za-z_]\w*?)?(Dialog|BottomSheet|Popup|PopupWindow|PopupMenu|DropdownMenu|TooltipBox)\s*\(""",
    )

    /** Window openers with no telling suffix, matched by name. */
    private val windowNamed = listOf(
        Regex("""\bAlertDialog\.Builder\s*\("""),
        Regex("""\bMaterialAlertDialogBuilder\s*\("""),
        Regex("""\bMaterialDatePicker\b"""),
        Regex("""\bMaterialTimePicker\b"""),
        Regex("""\bExpandedFullScreenSearchBar\s*\("""),
        Regex("""\bExpandedDockedSearchBar\s*\("""),
        Regex("""\bModalWideNavigationRail\s*\("""),
        // WindowManager.addView, or any other: nothing in the app adds a view today.
        Regex("""\baddView\s*\("""),
        // A DialogFragment (or BottomSheetDialogFragment) shown by its manager.
        Regex("""\.show\(\s*(supportFragmentManager|parentFragmentManager|childFragmentManager)\b"""),
    )

    /** A declaration's own name is not a call: `fun SignOutConfirmDialog(`, `class FooDialog(`. */
    private val declaredHere = Regex("""\b(fun|class)\s*(<[^>]*>\s*)?$""")

    /** App source, every source set but the tests'. */
    private fun appSources(): List<File> =
        File(repoRoot, "app/src").listFiles().orEmpty()
            .filter { it.isDirectory && it.name != "test" && !it.name.startsWith("androidTest") }
            .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private val declaration = Regex("""\b(?:fun|class|object)\s+(?:<[^>]*>\s*)?(\w+)""")

    /** Names the app declares: its own composables and classes. */
    private fun declaredNames(files: List<File>): Set<String> =
        files.flatMapTo(mutableSetOf()) { file ->
            declaration.findAll(KotlinSource(file.readText()).code).map { it.groupValues[1] }.toList()
        }

    /** The window-opening calls in [code] (comments and strings blanked), with their offsets. */
    private fun windowCalls(code: String, declared: Set<String>): List<Pair<String, Int>> {
        val bySuffix = windowSuffix.findAll(code)
            .map { (it.groupValues[1] + it.groupValues[2]) to it.range.first }
            .filterNot { (name, at) ->
                name in declared || declaredHere.containsMatchIn(code.substring(maxOf(0, at - 40), at))
            }
        val byName = windowNamed.flatMap { pattern -> pattern.findAll(code).map { it.value.trim() to it.range.first } }
        return bySuffix.toList() + byName
    }

    private fun windowCalls(file: File, declared: Set<String>): List<String> {
        val source = KotlinSource(file.readText())
        return windowCalls(source.code, declared).map { (name, at) -> "$name at line ${source.lineOf(at)}" }
    }

    @Test
    fun `only the cover-aware wrappers open a dialog, sheet, popup or menu window`() {
        val files = appSources()
        // The scan reaches the app: a wrong root would pass on nothing.
        assertTrue("scanned ${files.size} files", files.size > 50)
        val declared = declaredNames(files)

        val offenders = files
            .filter { it.relativeTo(repoRoot).invariantSeparatorsPath != allowed }
            .flatMap { file -> windowCalls(file, declared).map { "${file.relativeTo(repoRoot).invariantSeparatorsPath}: $it" } }

        assertEquals(
            "These open a window over the Hide App Contents cover. Use BirdoAlertDialog / " +
                "BirdoModalBottomSheet (ui/components/AppCover.kt), or add a cover-aware wrapper there.",
            emptyList<String>(),
            offenders,
        )
    }

    /** The patterns see real calls: the wrappers' own are found. */
    @Test
    fun `the guard finds the calls the wrappers make`() {
        val calls = windowCalls(File(repoRoot, allowed), declared = emptySet()).map { it.substringBefore(" at ") }
        assertTrue("$calls", "AlertDialog" in calls)
        assertTrue("$calls", "ModalBottomSheet" in calls)
    }

    /** Every pattern catches a sample, and the app's wrappers and look-alikes pass. */
    @Test
    fun `each pattern catches a sample`() {
        val declared = setOf("BirdoAlertDialog", "BirdoModalBottomSheet", "SignOutConfirmDialog")
        val caught = listOf(
            "Dialog(onDismissRequest = {}) {}",
            "androidx.compose.ui.window.Dialog(onDismissRequest = {}) {}",
            "AlertDialog(onDismissRequest = {}, confirmButton = {})",
            "BasicAlertDialog(onDismissRequest = {}) {}",
            "ModalBottomSheet(onDismissRequest = {}) {}",
            "Popup(alignment = Alignment.Center) {}",
            "DropdownMenu(expanded = true, onDismissRequest = {}) {}",
            "ExposedDropdownMenu(expanded = true, onDismissRequest = {}) {}",
            "TooltipBox(positionProvider = p, tooltip = {}, state = s) {}",
            "BasicTooltipBox(positionProvider = p, tooltip = {}, state = s) {}",
            "ProgressDialog(context)",
            "AppCompatDialog(context)",
            "DatePickerDialog(context, listener, 2026, 0, 1)",
            "TimePickerDialog(context, listener, 9, 0, true)",
            "BottomSheetDialog(context)",
            "ComponentDialog(context)",
            "PopupWindow(view, 100, 100)",
            "ListPopupWindow(context)",
            "PopupMenu(context, anchor)",
            "AlertDialog.Builder(context).show()",
            "MaterialAlertDialogBuilder(context).show()",
            "MaterialDatePicker.Builder.datePicker().build()",
            "MaterialTimePicker.Builder().build()",
            "ExpandedFullScreenSearchBar(state = s, inputField = {}) {}",
            "ExpandedDockedSearchBar(state = s, inputField = {}) {}",
            "ModalWideNavigationRail(state = s) {}",
            "windowManager.addView(view, params)",
            "dialog.show(supportFragmentManager, tag)",
            "sheet.show( parentFragmentManager, null)",
            "picker.show(childFragmentManager, null)",
        )
        for (sample in caught) {
            assertTrue("not caught: $sample", windowCalls(sample, declared).isNotEmpty())
        }
        val passed = listOf(
            "BirdoAlertDialog(onDismissRequest = {}, confirmButton = {})",
            "BirdoModalBottomSheet(onDismissRequest = {}) {}",
            "SignOutConfirmDialog(isConnected = true, isAnonymousAccount = false, onConfirm = {}, onDismiss = {})",
            "private fun SomeNewDialog(onDismiss: () -> Unit) {",
            "DropdownMenuItem(text = {}, onClick = {})",
            "DialogProperties(dismissOnBackPress = false)",
            "BiometricPrompt(this, executor, callback)",
            "Toast.makeText(context, text, Toast.LENGTH_SHORT).show()",
        )
        for (sample in passed) {
            assertEquals("flagged: $sample", emptyList<Pair<String, Int>>(), windowCalls(sample, declared))
        }
    }
}
