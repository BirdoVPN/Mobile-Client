package app.birdo.vpn.ui

import app.birdo.vpn.testing.KotlinSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-937 (REVIEW-AND-016): no window may stack above the Hide App Contents
 * cover. The cover is drawn in the activity's own window, so a dialog, sheet
 * or popup window would be drawn over it; AppCoverHostTest proves the
 * wrappers in AppCover.kt show none while it is up. This proves nothing goes
 * around them: a window-creating call anywhere else fails the build.
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
     * Calls that put a window over the activity's: Compose dialogs, sheets,
     * popups and menus, and the platform's dialogs and popups.
     */
    private val windowCall = Regex(
        """(?<!\w)(AlertDialog|BasicAlertDialog|Dialog|ComponentDialog|DatePickerDialog|TimePickerDialog|""" +
            """ModalBottomSheet|BottomSheetDialog|Popup|PopupWindow|DropdownMenu|ExposedDropdownMenu|TooltipBox|""" +
            """AlertDialog\.Builder|MaterialAlertDialogBuilder)\s*\(""",
    )

    /** App source, every source set but the tests'. */
    private fun appSources(): List<File> =
        File(repoRoot, "app/src").listFiles().orEmpty()
            .filter { it.isDirectory && it.name != "test" && !it.name.startsWith("androidTest") }
            .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun windowCalls(file: File): List<String> {
        val source = KotlinSource(file.readText())
        return windowCall.findAll(source.code).map { "${it.groupValues[1]} at line ${source.lineOf(it.range.first)}" }.toList()
    }

    @Test
    fun `only the cover-aware wrappers open a dialog, sheet or popup window`() {
        val files = appSources()
        // The scan reaches the app: a wrong root would pass on nothing.
        assertTrue("scanned ${files.size} files", files.size > 50)

        val offenders = files
            .filter { it.relativeTo(repoRoot).invariantSeparatorsPath != allowed }
            .flatMap { file -> windowCalls(file).map { "${file.relativeTo(repoRoot).invariantSeparatorsPath}: $it" } }

        assertEquals(
            "These open a window over the Hide App Contents cover. Use BirdoAlertDialog / " +
                "BirdoModalBottomSheet (ui/components/AppCover.kt), or add a cover-aware wrapper there.",
            emptyList<String>(),
            offenders,
        )
    }

    /** The pattern sees real calls: the wrappers' own are found. */
    @Test
    fun `the guard finds the calls the wrappers make`() {
        val calls = windowCalls(File(repoRoot, allowed)).map { it.substringBefore(" at ") }
        assertTrue("$calls", "AlertDialog" in calls)
        assertTrue("$calls", "ModalBottomSheet" in calls)
    }
}
