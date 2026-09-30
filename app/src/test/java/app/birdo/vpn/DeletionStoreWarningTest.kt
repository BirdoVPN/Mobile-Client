package app.birdo.vpn

import app.birdo.vpn.billing.PlaySubscriptionLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Audit 2026-09-29, A-8 / C-9: deleting a Birdo account cancels a web (Polar)
 * subscription but CANNOT cancel an App Store or Google Play one, which keeps
 * billing. Both apps' deletion dialogs said "…and subscription will be
 * deleted", and nothing told a store subscriber to cancel in the store.
 *
 * Now the user is warned before confirming, with a link to the store's own
 * subscription manager, and a list the server returns afterwards
 * (`storeSubscriptionsStillBilling`) is shown if present. Reads Swift for the
 * same reason as IosPurchaseResumeTest: iOS never builds in PR CI.
 */
class DeletionStoreWarningTest {

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
        return file.readText()
    }

    @Test
    fun `the Play subscription link is Play's own manager for this app`() {
        assertEquals(
            "https://play.google.com/store/account/subscriptions?package=app.birdo.vpn",
            PlaySubscriptionLinks.MANAGE,
        )
    }

    @Test
    fun `android warns before confirming and links to Google Play`() {
        val strings = source("app/src/main/res/values/strings.xml")
        assertFalse(
            "the deletion dialog still claims the subscription is deleted with the account",
            strings.contains("and subscription will be deleted"),
        )
        assertTrue(strings.contains("<string name=\"delete_dialog_store_warning\">"))
        assertTrue(strings.contains("One bought through Google Play or the App Store is not"))

        val profile = source("app/src/main/java/app/birdo/vpn/ui/screen/ProfileScreen.kt")
        assertTrue(profile.contains("R.string.delete_dialog_store_warning"))
        assertTrue(profile.contains("onManageStoreSubscription = { onOpenUrl(PlaySubscriptionLinks.MANAGE) }"))
    }

    /** Second-pass #9: the preflight is asked for when the dialog opens. */
    @Test
    fun `android names the still-billing stores before confirming`() {
        val profile = source("app/src/main/java/app/birdo/vpn/ui/screen/ProfileScreen.kt")
        assertTrue(profile.contains("onDeleteDialogOpened()"))
        assertTrue(profile.contains("R.string.delete_dialog_preflight_store"))
        val graph = source("app/src/main/java/app/birdo/vpn/ui/navigation/BirdoNavGraph.kt")
        assertTrue(graph.contains("onDeleteDialogOpened = { authViewModel.loadDeletionPreflight() }"))
        assertTrue(graph.contains("deletionPreflight = authState.deletionPreflight"))
    }

    @Test
    fun `ios names the still-billing stores before confirming`() {
        val api = source("iosApp/iosApp/Services/APIClient.swift")
        assertTrue(api.contains("get(path: \"/api/v1/gdpr/delete/preflight\")"))
        val profile = source("iosApp/iosApp/Views/ProfileView.swift")
        assertTrue("the dialog must ask for the preflight when it opens", profile.contains("authVM.loadDeletionPreflight()"))
        val dialog = profile.substringAfter("private var deleteDialog: some View {")
            .substringBefore("private func confirmDelete()")
        assertTrue(dialog.contains("authVM.deletionPreflightStoreWarning"))
    }

    @Test
    fun `android shows what the server says is still billing after deletion`() {
        val auth = source("app/src/main/java/app/birdo/vpn/ui/viewmodel/AuthViewModel.kt")
        assertTrue(auth.contains("result.data.storeSubscriptionsStillBilling"))
        val graph = source("app/src/main/java/app/birdo/vpn/ui/navigation/BirdoNavGraph.kt")
        assertTrue(graph.contains("authState.storeSubscriptionsStillBilling"))
        assertTrue(graph.contains("R.string.store_still_billing_body"))
    }

    @Test
    fun `ios warns before confirming and opens the App Store subscription manager`() {
        val profile = source("iosApp/iosApp/Views/ProfileView.swift")
        assertFalse(profile.contains("and subscription will be deleted"))
        val dialog = profile.substringAfter("private var deleteDialog: some View {")
            .substringBefore("private func confirmDelete()")
        assertTrue(dialog.contains("One bought through the App Store or Google Play is not"))
        assertTrue(dialog.contains("SystemOpen.manageSubscriptions()"))
    }

    @Test
    fun `ios decodes the still-billing list without ever failing the deletion`() {
        val api = source("iosApp/iosApp/Services/APIClient.swift")
        assertTrue(api.contains("let storeSubscriptionsStillBilling: [StoreSubscriptionStillBilling]?"))
        assertTrue(
            "a body the client cannot decode must not turn a completed erasure into an error",
            api.contains("(try? decoder.decode(DeleteAccountResult.self, from: response))"),
        )
        val content = source("iosApp/iosApp/ContentView.swift")
        assertTrue(content.contains("authVM.storeSubscriptionsStillBilling"))
    }

    /**
     * Second-pass #19: the desktop dialog states what the unified erasure keeps
     * (REMEDIATION-DECISIONS §3); Android and iOS now say the same, in both the
     * password and the password-less variant.
     */
    @Test
    fun `every deletion dialog states the erasure and retention facts`() {
        val facts = "The account is anonymised immediately and fully deleted within 30 days; " +
            "payment records are kept, anonymised, for 7 years for tax."
        val strings = source("app/src/main/res/values/strings.xml")
        listOf("delete_dialog_message", "delete_dialog_message_no_password").forEach { name ->
            val value = Regex("""<string name="$name">([^<]*)</string>""").find(strings)?.groupValues?.get(1)
            assertTrue("$name lost the retention sentence", value.orEmpty().contains(facts))
        }
        val dialog = source("iosApp/iosApp/Views/ProfileView.swift")
            .substringAfter("private var deleteDialog: some View {")
            .substringBefore("private func confirmDelete()")
        assertEquals("both iOS dialog variants must carry it", 2, Regex(Regex.escape(facts)).findAll(dialog).count())
    }
}
