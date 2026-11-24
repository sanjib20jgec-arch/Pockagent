package com.example.pocketagent

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Manages app-lock state for PocketAgent.
 *
 * The lock protects local conversations, memories, notes, and model-backed workflows when
 * enabled. Authentication is performed with AndroidX [BiometricPrompt] using device credential
 * as fallback when available.
 */
class BiometricLockManager(
    private val preferences: SharedPreferences,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val canAuthenticate: (Context) -> Boolean = { context ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
        } else {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val hasBiometric = BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
            keyguard?.isDeviceSecure == true || hasBiometric
        }
    },
) {
    companion object {
        const val PREF_BIOMETRIC_ENABLED = "pref_biometric_lock_enabled"
        private const val PREF_LAST_UNLOCKED_AT = "pref_biometric_last_unlocked_at"
        private const val LOCK_TIMEOUT_MS = 60_000L
    }

    @Volatile private var isUnlocked = false
    @Volatile private var lastBackgroundedAt: Long = 0L

    val isLockEnabled: Boolean
        get() = preferences.getBoolean(PREF_BIOMETRIC_ENABLED, false)

    val isAppLocked: Boolean
        get() = isLockEnabled && !isUnlocked

    fun setLockEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PREF_BIOMETRIC_ENABLED, enabled).apply()
        isUnlocked = !enabled
        if (!enabled) {
            lastBackgroundedAt = 0L
        }
    }

    fun markBackgrounded() {
        if (isLockEnabled) {
            lastBackgroundedAt = nowMs()
        }
    }

    fun shouldLockOnResume(): Boolean {
        if (!isLockEnabled) return false
        if (!isUnlocked) return true
        val backgroundedAt = lastBackgroundedAt
        if (backgroundedAt == 0L) return false
        return nowMs() - backgroundedAt >= LOCK_TIMEOUT_MS
    }

    fun lock() {
        if (isLockEnabled) {
            isUnlocked = false
        }
    }

    fun unlock() {
        isUnlocked = true
        lastBackgroundedAt = 0L
        preferences.edit().putLong(PREF_LAST_UNLOCKED_AT, nowMs()).apply()
    }

    fun canUseBiometrics(context: Context): Boolean = canAuthenticate(context)

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock PocketAgent",
        subtitle: String = "Authenticate to access your local conversations and data",
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onCancel: () -> Unit = {},
    ) {
        if (!isLockEnabled) {
            unlock()
            onSuccess()
            return
        }
        if (!canUseBiometrics(activity)) {
            onError("No device lock screen or biometric credential is configured on this device.")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlock()
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                ) {
                    onCancel()
                } else {
                    onError(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                // BiometricPrompt handles retry UI; do not treat as fatal.
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptInfoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            promptInfoBuilder.setDeviceCredentialAllowed(true)
        }
        prompt.authenticate(promptInfoBuilder.build())
    }
}
