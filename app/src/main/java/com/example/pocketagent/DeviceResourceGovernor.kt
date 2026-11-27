package com.example.pocketagent

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Snapshot of recommended inference limits and memory/thermal mitigations for the current device state.
 */
data class ResourcePolicy(
    val recommendedMaxTokens: Int,
    val shouldWarnThermal: Boolean,
    val shouldPreferCpu: Boolean,
    val shouldTrimIdleWeights: Boolean,
    val summary: String,
)

/**
 * Evaluates device thermal status and available RAM to recommend safe token ceilings,
 * backend preferences, and idle weight trimming.
 */
object DeviceResourceGovernor {

    private const val THERMAL_MODERATE = 2
    private const val THERMAL_SEVERE = 3
    private const val SEVERE_THERMAL_MAX_TOKENS = 512
    private const val MODERATE_THERMAL_MAX_TOKENS = 1024
    private const val LOW_MEMORY_MAX_TOKENS = 768
    private const val LOW_RAM_THRESHOLD_MB = 768L

    fun evaluatePolicy(
        thermalStatus: Int,
        availRamMb: Long,
        lowMemory: Boolean,
        requestedMaxTokens: Int,
    ): ResourcePolicy {
        var maxTokens = requestedMaxTokens
        var warnThermal = false
        var preferCpu = false
        var trimIdleWeights = false
        val reasons = mutableListOf<String>()

        if (thermalStatus >= THERMAL_SEVERE) {
            maxTokens = minOf(maxTokens, SEVERE_THERMAL_MAX_TOKENS)
            warnThermal = true
            preferCpu = true
            trimIdleWeights = true
            reasons += "Severe thermal load"
        } else if (thermalStatus == THERMAL_MODERATE) {
            maxTokens = minOf(maxTokens, MODERATE_THERMAL_MAX_TOKENS)
            warnThermal = true
            reasons += "Moderate thermal load"
        }

        if (lowMemory || availRamMb in 1L..LOW_RAM_THRESHOLD_MB) {
            maxTokens = minOf(maxTokens, LOW_MEMORY_MAX_TOKENS)
            trimIdleWeights = true
            reasons += "Low available RAM"
        }

        val summary = if (reasons.isEmpty()) "Optimal" else reasons.joinToString("; ")
        return ResourcePolicy(
            recommendedMaxTokens = maxTokens,
            shouldWarnThermal = warnThermal,
            shouldPreferCpu = preferCpu,
            shouldTrimIdleWeights = trimIdleWeights,
            summary = summary,
        )
    }

    fun snapshot(context: Context, requestedMaxTokens: Int): ResourcePolicy {
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        } else {
            0
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        var availRamMb = 0L
        var lowMemory = false
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo)
            availRamMb = memoryInfo.availMem / (1024L * 1024L)
            lowMemory = memoryInfo.lowMemory
        }

        return evaluatePolicy(
            thermalStatus = thermalStatus,
            availRamMb = availRamMb,
            lowMemory = lowMemory,
            requestedMaxTokens = requestedMaxTokens,
        )
    }
}
