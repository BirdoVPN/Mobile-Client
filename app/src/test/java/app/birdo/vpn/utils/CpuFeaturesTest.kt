package app.birdo.vpn.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The HWCAP decoder behind the `cpu.*` Sentry tags. The bit layout is the
 * Linux uapi `arch/arm64/include/uapi/asm/hwcap.h`; the fixtures below are
 * real `/proc/cpuinfo` Features lines from the SoCs in the 1.4.25 SIGILL
 * cluster, turned back into the words the kernel derived them from. If a
 * bit here is wrong, the tag that is supposed to explain the next native
 * crash explains the wrong thing.
 */
class CpuFeaturesTest {

    /** Build an AT_HWCAP word from kernel feature names using the decoder's own table (round-trip guard). */
    private fun word(vararg names: String, table: Map<Int, String> = CpuFeatures.ARM64_HWCAP_BITS): Long {
        var w = 0L
        for (n in names) {
            val bit = table.entries.single { it.value == n }.key
            w = w or (1L shl bit)
        }
        return w
    }

    @Test
    fun `sha3 is bit 17 of AT_HWCAP, and only that bit`() {
        // The one bit the whole incident turned on. Pinned to the header, not
        // to the table, so a table edit cannot move it silently.
        val d = CpuFeatures.decode("arm64-v8a", 1L shl 17, 0L)
        assertTrue(d.has("sha3"))
        assertEquals(setOf("sha3"), d.present)
        assertEquals("true", d.sentryTags()["cpu.sha3"])
        assertEquals("false", d.sentryTags()["cpu.atomics"])
    }

    @Test
    fun `snapdragon 675 (Galaxy A70, the reported crash) decodes with no sha3`() {
        // Features line of the snapdragon-675 dump used in the root-cause
        // analysis: "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp
        // asimdhp cpuid asimdrdm lrcpc dcpop asimddp" -- ends before sha3.
        val hwcap = word(
            "fp", "asimd", "aes", "pmull", "sha1", "sha2", "crc32", "atomics",
            "fphp", "asimdhp", "asimdrdm", "lrcpc", "asimddp",
        ) or (1L shl 2) or (1L shl 11) or (1L shl 16) // evtstrm, cpuid, dcpop: not in the decoded table
        val d = CpuFeatures.decode("arm64-v8a", hwcap, 0L)
        assertTrue(d.decodable)
        assertFalse("sha3 must be ABSENT on Kryo 460 (A76/A55)", d.has("sha3"))
        assertFalse(d.has("sha512"))
        assertTrue(d.has("atomics"))
        assertTrue(d.has("asimddp"))
        assertTrue(d.has("sha2"))
        assertTrue(d.has("aes"))
        assertTrue(d.has("fphp"))
        val tags = d.sentryTags()
        assertEquals("false", tags["cpu.sha3"])
        assertEquals("true", tags["cpu.atomics"])
        assertEquals("true", tags["cpu.asimddp"])
        assertEquals("0x" + hwcap.toULong().toString(16), tags["cpu.hwcap"])
        assertTrue(d.summary(), d.summary().contains("missing=sha3 sha512"))
    }

    @Test
    fun `snapdragon 665 (Redmi Note 8, ARMv8-0) lacks atomics as well as sha3`() {
        // snapdragon-665 dump: "fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid".
        val hwcap = word("fp", "asimd", "aes", "pmull", "sha1", "sha2", "crc32") or (1L shl 2) or (1L shl 11)
        val d = CpuFeatures.decode("arm64-v8a", hwcap, 0L)
        assertFalse(d.has("sha3"))
        assertFalse("an ungated LSE atomic would SIGILL here too", d.has("atomics"))
        assertFalse(d.has("asimddp"))
        assertTrue(d.has("crc32"))
    }

    @Test
    fun `armv9 (Snapdragon 8 Gen 1) decodes sha3 sha512 sve i8mm bf16 bti mte`() {
        // The class of device that never crashed: "... sha3 sm3 sm4 asimddp
        // sha512 sve ..." plus the HWCAP2 set of an A710/X2.
        val hwcap = word(
            "fp", "asimd", "aes", "pmull", "sha1", "sha2", "crc32", "atomics", "fphp", "asimdhp",
            "asimdrdm", "jscvt", "fcma", "lrcpc", "sha3", "sm3", "sm4", "asimddp", "sha512", "sve", "asimdfhm",
            "flagm", "sb", "paca", "pacg",
        )
        val hwcap2 = word("sve2", "i8mm", "bf16", "bti", "mte", table = CpuFeatures.ARM64_HWCAP2_BITS)
        val d = CpuFeatures.decode("arm64-v8a", hwcap, hwcap2)
        for (f in listOf("sha3", "sha512", "sve", "i8mm", "bf16", "bti", "mte", "sm3", "sm4")) {
            assertTrue("expected $f", d.has(f))
        }
        assertEquals("true", d.sentryTags()["cpu.sha3"])
        assertEquals("true", d.sentryTags()["cpu.mte"])
        assertTrue(d.summary(), d.summary().contains("missing=-"))
    }

    @Test
    fun `high HWCAP2 bits (mops, hbc, lse128) decode without overflow`() {
        val hwcap2 = (1L shl 43) or (1L shl 44) or (1L shl 47)
        val d = CpuFeatures.decode("arm64-v8a", 0L, hwcap2)
        assertEquals(setOf("mops", "hbc", "lse128"), d.present)
    }

    @Test
    fun `a word with the sign bit set is treated as unsigned`() {
        // pacg is bit 31 of AT_HWCAP: on a 32-bit c_ulong that is the sign bit
        // once it reaches a Kotlin Long via jlong.
        val d = CpuFeatures.decode("arm64-v8a", 1L shl 31, -1L)
        assertTrue(d.has("pacg"))
        assertEquals("0x80000000", d.sentryTags()["cpu.hwcap"])
        assertEquals("0xffffffffffffffff", d.sentryTags()["cpu.hwcap2"])
    }

    @Test
    fun `other ABIs keep the raw words but decode nothing`() {
        // armeabi-v7a and x86 have their own HWCAP layouts; claiming "sha3=false"
        // there would be a fabricated tag. The raw words still ship.
        for (abi in listOf("armeabi-v7a", "x86_64", "x86", "unknown")) {
            val d = CpuFeatures.decode(abi, 0x1234L, 0x5L)
            assertFalse(abi, d.decodable)
            assertTrue(abi, d.present.isEmpty())
            val tags = d.sentryTags()
            assertEquals("0x1234", tags["cpu.hwcap"])
            assertNull("no cpu.sha3 tag for $abi", tags["cpu.sha3"])
        }
    }

    @Test
    fun `every tagged feature name exists in one of the bit tables`() {
        val known = CpuFeatures.ARM64_HWCAP_BITS.values + CpuFeatures.ARM64_HWCAP2_BITS.values
        for (f in CpuFeatures.TAGGED_FEATURES) assertTrue("$f has no bit", f in known)
        // The names are the kernel's /proc/cpuinfo strings, so a Sentry search
        // for cpu.sha3 and a grep of a cpuinfo dump agree; and every tagged
        // bit is unique.
        assertEquals(CpuFeatures.ARM64_HWCAP_BITS.size, CpuFeatures.ARM64_HWCAP_BITS.values.toSet().size)
        assertEquals(17, CpuFeatures.ARM64_HWCAP_BITS.entries.single { it.value == "sha3" }.key)
        assertEquals(8, CpuFeatures.ARM64_HWCAP_BITS.entries.single { it.value == "atomics" }.key)
        assertEquals(20, CpuFeatures.ARM64_HWCAP_BITS.entries.single { it.value == "asimddp" }.key)
        assertEquals(21, CpuFeatures.ARM64_HWCAP_BITS.entries.single { it.value == "sha512" }.key)
    }

    @Test
    fun `cpuinfo Features line is read from the first core only and trimmed`() {
        val f = File.createTempFile("cpuinfo", ".txt")
        try {
            f.writeText(
                "processor\t: 0\n" +
                    "BogoMIPS\t: 38.40\n" +
                    "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp\n" +
                    "CPU implementer\t: 0x51\n" +
                    "processor\t: 1\n" +
                    "Features\t: fp asimd sha3\n",
            )
            assertEquals(
                "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp",
                CpuFeatures.procCpuinfoFeatures(f.absolutePath),
            )
        } finally {
            f.delete()
        }
        assertNull(CpuFeatures.procCpuinfoFeatures("/nonexistent/cpuinfo"))
    }
}
