package app.birdo.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins on the MERGED manifest (app + every library), as AGP hands it to the
 * unit tests (`isIncludeAndroidResources`, the same file Robolectric reads).
 * The source manifest cannot show what a library adds; this can.
 */
class MergedManifestTest {

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /**
         * MR-2379: transport-runtime's log uploader (pulled in by Play Billing)
         * runs only from these two components, and its upload resolves
         * firebaselogging*.googleapis.com with the system resolver: outside
         * DoH, and outside the tunnel during Stealth. AndroidManifest.xml
         * removes both (tools:node="remove").
         */
        val REMOVED_UPLOADER = listOf(
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver",
        )
    }

    private val mergedManifest: File by lazy {
        val config = javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")
        assertNotNull("AGP wrote no test_config.properties: is isIncludeAndroidResources still on?", config)
        val path = Properties().apply { config!!.use { load(it) } }.getProperty("android_merged_manifest")
        assertNotNull("test_config.properties names no android_merged_manifest", path)
        File(path!!).absoluteFile.also { assertTrue("merged manifest not found at $it", it.isFile) }
    }

    /** android:name of every application component in the merged manifest. */
    private val components: List<String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(mergedManifest)
        listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
        }
    }

    @Test
    fun `the Play Billing log uploader is not in the merged manifest`() {
        // Vacuity guards: this is the merged file (the app's own service AND a
        // library's component are in it), so an absence below means something.
        assertTrue("not the app's manifest: $mergedManifest", "app.birdo.vpn.service.BirdoVpnService" in components)
        assertTrue(
            "library manifests are not merged into $mergedManifest",
            "com.android.billingclient.api.ProxyBillingActivity" in components,
        )
        for (name in REMOVED_UPLOADER) {
            assertFalse(
                "$name is back in the merged manifest: Play Billing's log uploader would resolve " +
                    "firebaselogging*.googleapis.com outside DoH (and outside the tunnel in Stealth). " +
                    "Keep its tools:node=\"remove\" in app/src/main/AndroidManifest.xml (MR-2379).",
                name in components,
            )
        }
    }
}
