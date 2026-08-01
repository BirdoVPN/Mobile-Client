package app.birdo.vpn.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the HMAC key-set migration invariant.
 *
 * The real sign/verify path needs AndroidKeyStore and cannot run as a plain unit
 * test, but the migration hazard is not in the crypto — it is in the key LISTS,
 * and that part is pure data.
 *
 * The hazard: adding a key to `PROTECTED_KEYS` changes the canonical string, so
 * every installed app fails `verify` once and `resetToSafeDefaults` wipes the
 * user's kill switch, DNS and split tunnel. That is indistinguishable from the
 * tamper response it is not, it happens to EVERY user at once, and it only shows
 * up after release. These assertions fail in CI instead.
 */
class SettingsHmacKeySetTest {

    @Test
    fun `the newest legacy set is the current set minus its newest additions`() {
        // If you add a protected key, you must ALSO append the pre-change list to
        // LEGACY_PROTECTED_KEY_SETS. Otherwise upgrading installs have no set
        // their stored HMAC can match, and every one of them gets reset.
        val current = SettingsHmac.PROTECTED_KEYS
        val newestLegacy = SettingsHmac.LEGACY_PROTECTED_KEY_SETS.first()

        assertTrue(
            "Legacy set must be a subset of the current set — a key was REMOVED " +
                "from PROTECTED_KEYS without recording that history",
            current.containsAll(newestLegacy),
        )
        assertTrue(
            "Legacy set must be strictly smaller than the current set; if they " +
                "are equal, PROTECTED_KEYS changed without a new legacy entry",
            newestLegacy.size < current.size,
        )
        // Order matters: the canonical string is a join over the list in order,
        // so reordering PROTECTED_KEYS breaks every stored signature just as
        // surely as adding a key.
        assertEquals(
            "Legacy set must be the current set's PREFIX — keys were reordered, " +
                "which invalidates every stored HMAC",
            newestLegacy,
            current.take(newestLegacy.size),
        )
    }

    @Test
    fun `the multi-hop route is covered`() {
        // The reason this migration exists. An unsigned exit node is rewritable
        // on a rooted device, which silently relocates where the user's traffic
        // leaves the network — the one property Multi-Hop is sold on.
        assertTrue(SettingsHmac.PROTECTED_KEYS.contains("multi_hop_enabled"))
        assertTrue(SettingsHmac.PROTECTED_KEYS.contains("multi_hop_entry_node"))
        assertTrue(SettingsHmac.PROTECTED_KEYS.contains("multi_hop_exit_node"))
    }

    @Test
    fun `protected keys are unique`() {
        // A duplicate would be hashed twice and silently double-weight one
        // setting in the canonical form.
        val keys = SettingsHmac.PROTECTED_KEYS
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `every legacy set is a distinct prefix of the current set`() {
        // Holds for future migrations too, not just today's single entry.
        val current = SettingsHmac.PROTECTED_KEYS
        val seen = mutableSetOf<List<String>>()
        for (legacy in SettingsHmac.LEGACY_PROTECTED_KEY_SETS) {
            assertEquals(
                "Every historical set must be a prefix of the current one",
                legacy,
                current.take(legacy.size),
            )
            assertTrue("Duplicate legacy key set", seen.add(legacy))
        }
    }
}
