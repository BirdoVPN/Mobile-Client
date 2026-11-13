package app.birdo.vpn.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * MR-703 / MR-1742 (B2): the migration that deletes what builds before
 * per-connect PQ keys persisted. Against a real temp directory; the Keystore
 * (absent on the JVM) is a recorder.
 */
class BirdoPqLegacyPurgeTest {

    private lateinit var root: File
    private lateinit var dir: File
    private val deletedAliases = mutableListOf<String>()

    @Before
    fun setup() {
        root = Files.createTempDirectory("pq-purge").toFile()
        dir = File(root, BirdoPqLegacyPurge.DIR_NAME)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun purge() = BirdoPqLegacyPurge(dir) { deletedAliases += it }.purge()

    private fun put(name: String, size: Int = 64) = File(dir, name).also {
        dir.mkdirs()
        it.writeBytes(ByteArray(size) { 9 })
    }

    @Test
    fun `every file an older build wrote is deleted, then the empty directory`() {
        val files = listOf(
            put("static.pk", 1568),
            put("static.sk.v2", 3200),
            put("static.sk.enc", 3300),
            put("static.pk.tmp"),
            put("static.sk.v2.tmp"),
        )

        assertEquals(5, purge())

        files.forEach { assertFalse("${it.name} survived", it.exists()) }
        assertFalse("the empty legacy directory survived", dir.exists())
    }

    @Test
    fun `both Keystore keys are deleted`() {
        purge()
        assertEquals(listOf("birdo_rosenpass_sk_v2", "birdo_rosenpass_master"), deletedAliases)
    }

    @Test
    fun `a file that is not ours stays, and so does its directory`() {
        put("static.sk.v2")
        val other = put("notes.txt")

        assertEquals(1, purge())

        assertTrue(other.exists())
        assertTrue(dir.exists())
    }

    @Test
    fun `an install with nothing to delete finds nothing and does not fail`() {
        assertEquals(0, purge())
        assertEquals(0, purge())
        assertFalse(dir.exists())
    }

    @Test
    fun `a Keystore that throws does not stop the purge`() {
        put("static.sk.v2")
        var asked = 0
        val n = BirdoPqLegacyPurge(dir) { asked++; error("keystore unavailable") }.purge()
        assertEquals(1, n)
        assertEquals("the second alias is still tried", 2, asked)
    }
}
