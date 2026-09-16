package app.birdo.vpn.utils

import android.os.Build
import android.util.Log
import io.sentry.Sentry
import java.io.File

/**
 * CPU-feature attribution for native crash reports.
 *
 * ## Why this exists
 *
 * Every Android build from 1.3.25 to 1.4.25 died with `SIGILL` in
 * `librosenpass_jni.so` on any arm64 device whose cores lack FEAT_SHA3
 * (Snapdragon 6xx/7xx/888, Helio G8x/G9x -- most of the mid-range). The crash
 * reports said *where* (an `eor3` in `keccakx2_squeezeblocks`) but not *why*
 * the same binary ran fine on flagships, and it took three independent
 * investigations to tie the seven Play devices to "no `sha3` HWCAP". This
 * puts the answer on every report up front: which optional ISA extensions
 * the kernel says this CPU has, which ABI the process runs as, and which
 * ML-KEM implementation the native library was built with.
 *
 * ## What it is not
 *
 * Attribution only. It changes no behaviour, dispatches nothing, and runs no
 * KEM self-test (a `SIGILL` inside a self-test would merely move the crash
 * from the first connect to app start). The gates that prevent the crash are
 * `scripts/check_pq_features.sh` and `scripts/check_no_sha3_ext.sh`; this is
 * what makes the next one diagnosable in minutes rather than days.
 *
 * ## Sources
 *
 * - `AT_HWCAP` / `AT_HWCAP2` via `RosenpassNative.nativeCpuFeatures()` --
 *   `getauxval(3)` from Rust, because neither is reachable from Java and
 *   `/proc/self/auxv` is not app-readable. Decoded here with the bit layout of
 *   the Linux uapi header `arch/arm64/include/uapi/asm/hwcap.h` (v6.12) and
 *   named with the kernel's own `/proc/cpuinfo` strings
 *   (`arch/arm64/kernel/cpuinfo.c` `hwcap_str[]`), so `cpu.sha3` here and
 *   `sha3` in a cpuinfo dump mean the same bit.
 * - The `Features` line of `/proc/cpuinfo`, which IS app-readable and carries
 *   the same information as words, for the case where the native library
 *   never loaded.
 *
 * ## Privacy
 *
 * A CPU's feature set is a property of the SoC model, shared by every unit
 * ever sold; it identifies a chip family, not a user. Tag values pass through
 * BirdoApp's `beforeSend` scrubber like everything else.
 */
object CpuFeatures {

    private const val TAG = "CpuFeatures"

    /** Sentry limits a tag value to 200 characters; longer ones are dropped server-side. */
    private const val SENTRY_TAG_MAX = 200

    /** Tags every report carries; set once at Sentry init from Kotlin-only sources. */
    const val TAG_ABI = "birdo.abi"
    const val TAG_CPUINFO_FEATURES = "birdo.cpu.features"

    /** Tags set once the native library has loaded. */
    const val TAG_PQ_IMPL = "birdo.pq.impl"
    const val TAG_HWCAP = "cpu.hwcap"
    const val TAG_HWCAP2 = "cpu.hwcap2"

    /**
     * arm64 `AT_HWCAP` bits, verbatim from the v6.12 uapi header (bit index ->
     * kernel `/proc/cpuinfo` name). Only the bits a native crash is likely to
     * hinge on are decoded; the raw words are tagged as well, so nothing is
     * lost by leaving a bit out of this table.
     */
    internal val ARM64_HWCAP_BITS: Map<Int, String> = mapOf(
        0 to "fp",
        1 to "asimd",
        3 to "aes",
        4 to "pmull",
        5 to "sha1",
        6 to "sha2",
        7 to "crc32",
        8 to "atomics",
        9 to "fphp",
        10 to "asimdhp",
        12 to "asimdrdm",
        13 to "jscvt",
        14 to "fcma",
        15 to "lrcpc",
        17 to "sha3",
        18 to "sm3",
        19 to "sm4",
        20 to "asimddp",
        21 to "sha512",
        22 to "sve",
        23 to "asimdfhm",
        27 to "flagm",
        29 to "sb",
        30 to "paca",
        31 to "pacg",
    )

    /** arm64 `AT_HWCAP2` bits, same source. */
    internal val ARM64_HWCAP2_BITS: Map<Int, String> = mapOf(
        1 to "sve2",
        13 to "i8mm",
        14 to "bf16",
        17 to "bti",
        18 to "mte",
        43 to "mops",
        44 to "hbc",
        47 to "lse128",
    )

    /**
     * The features whose absence has caused, or would cause, a `SIGILL` in a
     * library we ship, in the order they appear in the one-line log. Each
     * becomes a `cpu.<name>` Sentry tag with the value `true` / `false`.
     */
    internal val TAGGED_FEATURES: List<String> = listOf(
        "asimd", "aes", "pmull", "sha1", "sha2", "sha3", "sha512",
        "atomics", "asimddp", "asimdrdm", "fphp", "asimdhp", "crc32",
        "sve", "i8mm", "bf16", "bti", "mte",
    )

    /** Decoded view of one process's HWCAP words. Pure data; safe to build on the JVM. */
    data class Decoded(
        val abi: String,
        val hwcap: ULong,
        val hwcap2: ULong,
        /** Every feature name from the tables that is set; empty when [decodable] is false. */
        val present: Set<String>,
        /** False on an ABI whose HWCAP layout this object does not know (only arm64 is decoded). */
        val decodable: Boolean,
    ) {
        fun has(name: String): Boolean = name in present

        /** `cpu.<name>=true|false` for every [TAGGED_FEATURES] entry plus the raw words. */
        fun sentryTags(): Map<String, String> {
            val tags = LinkedHashMap<String, String>()
            tags[TAG_HWCAP] = "0x" + hwcap.toString(16)
            tags[TAG_HWCAP2] = "0x" + hwcap2.toString(16)
            if (decodable) {
                for (f in TAGGED_FEATURES) tags["cpu.$f"] = has(f).toString()
            }
            return tags
        }

        /** One logcat line: `abi=arm64-v8a hwcap=0x... hwcap2=0x... features=fp asimd ... missing=sha3 ...`. */
        fun summary(): String {
            val sb = StringBuilder()
            sb.append("abi=").append(abi)
            sb.append(" hwcap=0x").append(hwcap.toString(16))
            sb.append(" hwcap2=0x").append(hwcap2.toString(16))
            if (decodable) {
                sb.append(" features=").append(present.joinToString(" "))
                val missing = TAGGED_FEATURES.filterNot { has(it) }
                sb.append(" missing=").append(if (missing.isEmpty()) "-" else missing.joinToString(" "))
            } else {
                sb.append(" (HWCAP layout not decoded for this ABI)")
            }
            return sb.toString()
        }
    }

    /**
     * Decode `[AT_HWCAP, AT_HWCAP2]` for [abi]. The words are the raw `long`s
     * from the JNI side reinterpreted as unsigned; on a 32-bit ABI the upper
     * half is always zero.
     */
    fun decode(abi: String, hwcap: Long, hwcap2: Long): Decoded {
        val w1 = hwcap.toULong()
        val w2 = hwcap2.toULong()
        if (abi != "arm64-v8a") {
            return Decoded(abi, w1, w2, emptySet(), decodable = false)
        }
        val present = LinkedHashSet<String>()
        for ((bit, name) in ARM64_HWCAP_BITS) if ((w1 shr bit) and 1UL == 1UL) present += name
        for ((bit, name) in ARM64_HWCAP2_BITS) if ((w2 shr bit) and 1UL == 1UL) present += name
        return Decoded(abi, w1, w2, present, decodable = true)
    }

    /**
     * The `Features` line of `/proc/cpuinfo` (first occurrence -- the kernel
     * prints the same system-wide HWCAP-derived line for every core), or null
     * when unreadable. Cheap: one small read, no parsing beyond a prefix match.
     */
    fun procCpuinfoFeatures(path: String = "/proc/cpuinfo"): String? =
        runCatching {
            File(path).useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") }
                    ?.substringAfter(':')
                    ?.trim()
            }
        }.getOrNull()

    /** The ABI the platform prefers for this process, or "unknown" off-device (JVM tests). */
    fun preferredAbi(): String =
        runCatching { Build.SUPPORTED_ABIS?.firstOrNull() }.getOrNull() ?: "unknown"

    /**
     * Baseline attribution from Kotlin-only sources: the ABI and the cpuinfo
     * Features line. Called once from BirdoApp right after Sentry init so that
     * a native crash in ANY library -- including one that happens before the
     * PQ library is ever loaded -- carries them. No-op when Sentry is not
     * initialised (debug builds); the values are still returned for logging.
     */
    fun tagSentryBaseline(): Map<String, String> {
        val tags = LinkedHashMap<String, String>()
        tags[TAG_ABI] = preferredAbi()
        procCpuinfoFeatures()?.let { tags[TAG_CPUINFO_FEATURES] = it.take(SENTRY_TAG_MAX) }
        applyTags(tags)
        return tags
    }

    /**
     * Called by RosenpassNative once `librosenpass_jni.so` has loaded, with the
     * raw HWCAP words from `nativeCpuFeatures()` and the implementation name
     * from `nativeImplName()`. Logs one line and tags the Sentry scope, so a
     * later native crash (the next `SIGILL`) says which feature the device
     * lacked and which ML-KEM code was running.
     */
    fun reportNativeLoad(hwcapWords: LongArray?, pqImpl: String?): Decoded? {
        val abi = preferredAbi()
        val decoded = hwcapWords?.takeIf { it.size >= 2 }?.let { decode(abi, it[0], it[1]) }
        val impl = pqImpl?.takeIf { it.isNotBlank() } ?: "unknown"
        Log.i(TAG, "${decoded?.summary() ?: "abi=$abi hwcap=unavailable"} pq.impl=$impl")
        val tags = LinkedHashMap<String, String>()
        tags[TAG_ABI] = abi
        tags[TAG_PQ_IMPL] = impl
        decoded?.sentryTags()?.let { tags.putAll(it) }
        applyTags(tags)
        return decoded
    }

    private fun applyTags(tags: Map<String, String>) {
        // Sentry.setTag on an uninitialised SDK (debug builds) is a no-op on
        // the NoOp hub; nothing here may throw into a library-load path.
        runCatching {
            for ((k, v) in tags) Sentry.setTag(k, v.take(SENTRY_TAG_MAX))
        }.onFailure { Log.w(TAG, "could not tag Sentry scope: ${it.javaClass.simpleName}") }
    }
}
