package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * MR-1181 / MR-037: the store screenshots. scripts/play_listing.py uploads
 * store-assets/screenshot-0*.png to Google Play; F-Droid shows
 * fdroid/metadata/app.birdo.vpn/en-US/phoneScreenshots/. The frames retired on
 * 2026-10-21 showed an account identifier in the top bar: the Play home screen
 * a staff e-mail address, two F-Droid frames 12 digits of an anonymous account
 * number (that account is treated as burned). The current UI names neither.
 *
 * This pins the set store-assets/SCREENSHOTS-README.md documents, at the pixel
 * sizes it is captured at, and refuses every retired frame by content digest,
 * so a revert or a copy from a stale checkout cannot publish one again.
 */
class StoreScreenshotsTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private val playDir get() = File(repoRoot, "store-assets")
    private val fdroidDir get() = File(repoRoot, "fdroid/metadata/app.birdo.vpn/en-US/phoneScreenshots")

    /** SHA-256 of each retired frame: the Play set of 2026-09-20 and the F-Droid set of 2026-07-13. */
    private val retired = mapOf(
        "761262a87cccb5c12d9b729d30c2ddeb1bf7c5790b4a5c0b35bb1fbeb1f2107d" to "Play 01 login (2026-09-20)",
        "caba4f437d64454d5bad43bedcc208dab299b35d34116c62c026310156c38119" to "Play 02 home, staff e-mail in the top bar",
        "db32cc1f8c359fdc17a4069b815bbda03ad3bc16f9658a73aa29d92aaa82c53e" to "Play 03 servers (2026-09-20)",
        "d50072a8aee8757cf2fcddcf314e8fb1ada7c1fe016556902834eccb364b7137" to "Play 04 security (2026-09-20)",
        "da0dd745a2c7a85c67fa6ca849e76600a32bbed6d1b5ea0dc8b9977c3c34155a" to "F-Droid 01 login (2026-07-13)",
        "8fbfe8f676fee2937de70881d552b05c6809004a24dc399b883ea956266c6b70" to "F-Droid 02 home, account digits in the top bar",
        "2554b864efb38d17b069a01f6b2748432504ccdb1171ca1a8b6268ffabd83d86" to "F-Droid 03 servers, account digits in the top bar",
        "b7b166f5f61a37913a6276781244447cdff2281f74b4e063893492aae530d9e9" to "F-Droid 04 settings (2026-07-13)",
        "0db3924ffdf5472c6cee36f9d018a4497fbb7ebc6aff0b3046403ad968a0b639" to "F-Droid 05 split tunnelling (2026-07-13)",
    )

    /** Width and height from the PNG's IHDR chunk, which the format puts first. */
    private fun pngSize(file: File): Pair<Int, Int> {
        val head = file.inputStream().use { it.readNBytes(24) }
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue("${file.name} is not a PNG", head.size == 24 && head.copyOfRange(0, 8).contentEquals(signature))
        assertEquals("${file.name}: first chunk", "IHDR", String(head, 12, 4, Charsets.US_ASCII))
        val buf = ByteBuffer.wrap(head, 16, 8)
        return buf.int to buf.int
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `the Play set is the four documented frames at 1440x3120`() {
        val shots = playDir.listFiles { f -> f.name.matches(Regex("screenshot-0.*\\.png")) }!!
            .map { it.name }.sorted()
        assertEquals(
            listOf(
                "screenshot-01-login.png",
                "screenshot-02-home-protected.png",
                "screenshot-03-servers.png",
                "screenshot-04-security.png",
            ),
            shots,
        )
        for (name in shots) {
            val file = File(playDir, name)
            assertEquals("$name size", 1440 to 3120, pngSize(file))
            // play_listing.py refuses anything over Play's 8 MiB limit.
            assertTrue("$name is over 8 MiB", file.length() <= 8L * 1024 * 1024)
        }
    }

    @Test
    fun `the F-Droid set is five frames at 1080x2400`() {
        val shots = fdroidDir.listFiles { f -> f.extension == "png" }!!.map { it.name }.sorted()
        assertEquals(listOf("01.png", "02.png", "03.png", "04.png", "05.png"), shots)
        for (name in shots) {
            assertEquals("$name size", 1080 to 2400, pngSize(File(fdroidDir, name)))
        }
    }

    @Test
    fun `no retired frame is published again`() {
        val published = listOf(File(repoRoot, "store-assets"), File(repoRoot, "fdroid"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "png" }.toList() }
        assertTrue("no store images found", published.size >= 9)
        val back = published.mapNotNull { file ->
            retired[sha256(file)]?.let { "${file.relativeTo(repoRoot).invariantSeparatorsPath} is the retired $it" }
        }
        assertTrue(back.joinToString("\n"), back.isEmpty())
    }
}
