package com.example.pocketagent

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BiometricLockManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeAuth: FakeBiometricAuthenticator
    private lateinit var lockManager: BiometricLockManager

    class FakeBiometricAuthenticator : BiometricAuthenticator {
        var hardwareAvailable = true
        var authenticated = false

        override fun isBiometricHardwareAvailable(): Boolean = hardwareAvailable

        override fun authenticate(onSuccess: () -> Unit, onError: (String) -> Unit) {
            authenticated = true
            onSuccess()
        }
    }

    class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val map: MutableMap<String, Any>) : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { value?.let { map[key!!] = it }; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { map[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { map[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { map[key!!] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { map[key!!] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { map.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { map.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        fakeAuth = FakeBiometricAuthenticator()
        lockManager = BiometricLockManager(fakePrefs, fakeAuth)
    }

    @Test
    fun `default lock state is disabled and unlocked`() {
        assertFalse(lockManager.isLockEnabled)
        assertFalse(lockManager.isAppLocked)
        assertTrue(lockManager.canUseBiometrics())
    }

    @Test
    fun `enabling lock sets lock active when locked`() {
        lockManager.setLockEnabled(true)
        assertTrue(lockManager.isLockEnabled)

        lockManager.lock()
        assertTrue(lockManager.isAppLocked)

        lockManager.unlock()
        assertFalse(lockManager.isAppLocked)
    }

    @Test
    fun `disabling lock immediately unlocks app`() {
        lockManager.setLockEnabled(true)
        lockManager.lock()
        assertTrue(lockManager.isAppLocked)

        lockManager.setLockEnabled(false)
        assertFalse(lockManager.isAppLocked)
    }
}
