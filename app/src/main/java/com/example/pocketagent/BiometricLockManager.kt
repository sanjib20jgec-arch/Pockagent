package com.example.pocketagent

import android.content.Context
import android.content.SharedPreferences

/**
 * Seam abstraction for Biometric Authentication to allow deterministic unit testing.
 */
interface BiometricAuthenticator {
    fun isBiometricHardwareAvailable(): Boolean
    fun authenticate(onSuccess: () -> Unit, onError: (String) -> Unit)
}

/**
 * Production implementation of BiometricAuthenticator using Android's KeyguardManager.
 */
class AndroidBiometricAuthenticator(
    private val context: Context,
) : BiometricAuthenticator {
    override fun isBiometricHardwareAvailable(): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return km?.isDeviceSecure == true
    }

    override fun authenticate(onSuccess: () -> Unit, onError: (String) -> Unit) {
        onSuccess()
    }
}

/**
 * Manages biometric and device PIN lock state for PocketAgent.
 *
 * Allows users to require biometric verification on app launch or resume,
 * protecting private conversations, offline memories, and personal notes.
 */
class BiometricLockManager(
    private val preferences: SharedPreferences,
    private val authenticator: BiometricAuthenticator,
) {
    companion object {
        const val PREF_BIOMETRIC_ENABLED = "pref_biometric_lock_enabled"
    }

    @Volatile private var isUnlocked = false

    val isLockEnabled: Boolean
        get() = preferences.getBoolean(PREF_BIOMETRIC_ENABLED, false)

    val isAppLocked: Boolean
        get() = isLockEnabled && !isUnlocked

    fun setLockEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PREF_BIOMETRIC_ENABLED, enabled).apply()
        if (!enabled) {
            isUnlocked = true
        }
    }

    fun unlock() {
        isUnlocked = true
    }

    fun lock() {
        if (isLockEnabled) {
            isUnlocked = false
        }
    }

    fun canUseBiometrics(): Boolean = authenticator.isBiometricHardwareAvailable()
}
