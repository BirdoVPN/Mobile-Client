package app.birdo.vpn.security

import android.content.SharedPreferences
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A plain JCE AES-256 key standing in for the Android Keystore. [AesGcmSealer]
 * is provider-agnostic, so every byte of the sealed format is exercised on the
 * JVM; only the key's residence differs on a device.
 */
class JceKeySource : SecretKeySource {
    private var current: SecretKey? = null
    var generated = 0
        private set

    override fun key(): SecretKey = current ?: KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }.also { current = it; generated++ }

    override fun destroy() {
        current = null
    }
}

/**
 * In-memory [SharedPreferences] with real commit/apply semantics tracking,
 * so tests can assert which write path a caller chose.
 */
class FakeSharedPreferences : SharedPreferences {
    val data = linkedMapOf<String, Any?>()
    var commits = 0
        private set
    var applies = 0
        private set

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?) = apply { key?.let { pending[it] = value } }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { key?.let { pending[it] = values } }
        override fun putInt(key: String?, value: Int) = apply { key?.let { pending[it] = value } }
        override fun putLong(key: String?, value: Long) = apply { key?.let { pending[it] = value } }
        override fun putFloat(key: String?, value: Float) = apply { key?.let { pending[it] = value } }
        override fun putBoolean(key: String?, value: Boolean) = apply { key?.let { pending[it] = value } }
        override fun remove(key: String?) = apply { key?.let { removals += it } }
        override fun clear() = apply { clearAll = true }

        private fun flush() {
            if (clearAll) data.clear()
            removals.forEach { data.remove(it) }
            data.putAll(pending)
        }

        override fun commit(): Boolean {
            flush(); commits++; return true
        }

        override fun apply() {
            flush(); applies++
        }
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
