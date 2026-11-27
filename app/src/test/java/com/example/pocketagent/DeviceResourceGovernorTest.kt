package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceResourceGovernorTest {

    @Test
    fun `normal conditions keep requested tokens and report optimal`() {
        val policy = DeviceResourceGovernor.evaluatePolicy(
            thermalStatus = 0,
            availRamMb = 4096L,
            lowMemory = false,
            requestedMaxTokens = 2048,
        )

        assertEquals(2048, policy.recommendedMaxTokens)
        assertFalse(policy.shouldWarnThermal)
        assertFalse(policy.shouldPreferCpu)
        assertFalse(policy.shouldTrimIdleWeights)
        assertEquals("Optimal", policy.summary)
    }

    @Test
    fun `moderate thermal caps tokens to 1024 and warns`() {
        val policy = DeviceResourceGovernor.evaluatePolicy(
            thermalStatus = 2,
            availRamMb = 4096L,
            lowMemory = false,
            requestedMaxTokens = 2048,
        )

        assertEquals(1024, policy.recommendedMaxTokens)
        assertTrue(policy.shouldWarnThermal)
        assertFalse(policy.shouldPreferCpu)
        assertFalse(policy.shouldTrimIdleWeights)
        assertTrue(policy.summary.contains("Moderate", ignoreCase = true))
    }

    @Test
    fun `severe thermal caps tokens to 512 prefers cpu and trims idle weights`() {
        val policy = DeviceResourceGovernor.evaluatePolicy(
            thermalStatus = 3,
            availRamMb = 4096L,
            lowMemory = false,
            requestedMaxTokens = 2048,
        )

        assertEquals(512, policy.recommendedMaxTokens)
        assertTrue(policy.shouldWarnThermal)
        assertTrue(policy.shouldPreferCpu)
        assertTrue(policy.shouldTrimIdleWeights)
        assertTrue(policy.summary.contains("Severe", ignoreCase = true))
    }

    @Test
    fun `critical thermal status above 3 also applies severe throttling`() {
        val policy = DeviceResourceGovernor.evaluatePolicy(
            thermalStatus = 5,
            availRamMb = 4096L,
            lowMemory = false,
            requestedMaxTokens = 256,
        )

        assertEquals(256, policy.recommendedMaxTokens)
        assertTrue(policy.shouldWarnThermal)
        assertTrue(policy.shouldPreferCpu)
        assertTrue(policy.shouldTrimIdleWeights)
    }

    @Test
    fun `low ram threshold caps tokens to 768 and trims idle weights`() {
        val policy = DeviceResourceGovernor.evaluatePolicy(
            thermalStatus = 0,
            availRamMb = 768L,
            lowMemory = false,
            requestedMaxTokens = 2048,
        )

        assertEquals(768, policy.recommendedMaxTokens)
        assertFalse(policy.shouldWarnThermal)
        assertFalse(policy.shouldPreferCpu)
        assertTrue(policy.shouldTrimIdleWeights)
    }

    @Test
    fun `lowMemory flag caps tokens to 768 and trims idle weights`() {
        val policy = DeviceResourceGovernor.evaluatePolicy(
            thermalStatus = 0,
            availRamMb = 2048L,
            lowMemory = true,
            requestedMaxTokens = 2048,
        )

        assertEquals(768, policy.recommendedMaxTokens)
        assertFalse(policy.shouldWarnThermal)
        assertFalse(policy.shouldPreferCpu)
        assertTrue(policy.shouldTrimIdleWeights)
    }
}
