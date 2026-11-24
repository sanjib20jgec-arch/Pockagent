package com.example.pocketagent

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BiometricLockManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private var now = 1_000L
    private lateinit var lockManager: BiometricLockManager

    class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = apply { if (key != null && value != null) data[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) data[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) data[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) data[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) data[key] = value }
            override fun remove(key: String?) = apply { if (key != null) data.remove(key) }
            override fun clear() = apply { data.clear() }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        now = 1_000L
        lockManager = BiometricLockManager(fakePrefs, nowMs = { now }, canAuthenticate = { true })
    }

    @Test
    fun `default lock state is disabled and unlocked`() {
        assertFalse(lockManager.isLockEnabled)
        assertFalse(lockManager.isAppLocked)
    }

    @Test
    fun `enabling lock locks app until unlocked`() {
        lockManager.setLockEnabled(true)
        assertTrue(lockManager.isLockEnabled)
        assertTrue(lockManager.isAppLocked)

        lockManager.unlock()
        assertFalse(lockManager.isAppLocked)
    }

    @Test
    fun `disabling lock immediately unlocks app`() {
        lockManager.setLockEnabled(true)
        assertTrue(lockManager.isAppLocked)

        lockManager.setLockEnabled(false)
        assertFalse(lockManager.isLockEnabled)
        assertFalse(lockManager.isAppLocked)
    }

    @Test
    fun `background timeout locks app on resume`() {
        lockManager.setLockEnabled(true)
        lockManager.unlock()
        assertFalse(lockManager.isAppLocked)

        lockManager.markBackgrounded()
        now += 59_000L
        assertFalse(lockManager.shouldLockOnResume())

        now += 2_000L
        assertTrue(lockManager.shouldLockOnResume())
        lockManager.lock()
        assertTrue(lockManager.isAppLocked)
    }
}
