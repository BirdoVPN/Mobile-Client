package app.birdo.vpn

import app.birdo.vpn.testing.StringsXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-1707 (owner decision 2026-10-14): an anonymous account number can never
 * be changed. The "save your account number" step after an anonymous sign-up
 * now says so, and what to do if the number leaks, in one line under the
 * existing "save it" warning, on Android and iOS alike.
 *
 * Reads the Swift for the same reason as IosRequiredRowsGuardTest: LoginView
 * cannot compile into the un-hosted iOS test bundle.
 */
class AccountNumberPermanenceCopyTest {

    /**
     * The owner's words, verbatim — including "at birdo.app" (owner decision
     * 2026-10-14: two-factor is switched on from the website, not the app).
     */
    private val line = "It can't be changed. If it leaks, turn on two-factor sign-in at birdo.app or delete your account."

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private fun source(path: String): String {
        val file = File(repoRoot, path)
        assertTrue("scan target is missing: $path — this test would be vacuous", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    @Test
    fun `android's save step says the number is permanent, under the save-it warning`() {
        assertEquals(line, StringsXml.text("anon_created_permanent"))
        val dialog = source("app/src/main/java/app/birdo/vpn/ui/screen/LoginScreen.kt")
            .substringAfter("private fun AnonymousIdSavedDialog(")
            .substringBefore("confirmButton =")
        val warning = dialog.indexOf("stringResource(R.string.anon_created_warning)")
        val permanent = dialog.indexOf("stringResource(R.string.anon_created_permanent)")
        assertTrue("the save step lost its save-it warning", warning >= 0)
        assertTrue("the save step does not say the number is permanent", permanent >= 0)
        assertTrue("the permanence line must sit under the save-it warning", permanent > warning)
    }

    @Test
    fun `ios's save step says the same words, under its save-it line`() {
        val step = source("iosApp/iosApp/Views/LoginView.swift")
            .substringAfter("private func createdIdStep(")
            .substringBefore("private func copyCreatedId(")
        val save = step.indexOf("Text(\"Save it somewhere safe")
        val permanent = step.indexOf("Text(\"$line\")")
        assertTrue("the save step lost its save-it line", save >= 0)
        assertTrue("the save step does not say the number is permanent", permanent >= 0)
        assertTrue("the permanence line must sit under the save-it line", permanent > save)
        assertTrue(
            "the permanence line must come before the acknowledge button",
            permanent < step.indexOf("PrimaryButton(\"I've saved it — continue\""),
        )
    }
}
