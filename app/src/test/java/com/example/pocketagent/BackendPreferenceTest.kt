package com.example.pocketagent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackendPreferenceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class MockEngine(val isGpu: Boolean) : AutoCloseable {
        var isClosed = false
        override fun close() {
            isClosed = true
        }
    }

    @Test
    fun parsesBackendPreferenceStringsCorrectly() {
        assertEquals(BackendPreference.GPU_PREFERRED, BackendPreference.fromString("GPU"))
        assertEquals(BackendPreference.GPU_PREFERRED, BackendPreference.fromString("gpu_preferred"))
        assertEquals(BackendPreference.GPU_PREFERRED, BackendPreference.fromString("  GPU  "))
        assertEquals(BackendPreference.CPU_ONLY, BackendPreference.fromString("CPU"))
        assertEquals(BackendPreference.CPU_ONLY, BackendPreference.fromString("cpu_only"))
        assertEquals(BackendPreference.CPU_ONLY, BackendPreference.fromString("  CPU  "))
        assertEquals(BackendPreference.GPU_PREFERRED, BackendPreference.fromString(null))
        assertEquals(BackendPreference.GPU_PREFERRED, BackendPreference.fromString("unknown_value"))
    }

    @Test
    fun rejectsNonExistentOrEmptyModelFile() {
        val manager = InferenceEngineManager<MockEngine>(
            engineFactory = { isGpu, _, _ -> MockEngine(isGpu) }
        )
        val missing = File(temporaryFolder.root, "missing.litertlm")
        val cacheDir = temporaryFolder.newFolder("cache")

        assertThrows(IllegalArgumentException::class.java) {
            manager.getOrInitializeEngine(missing, cacheDir)
        }

        val empty = temporaryFolder.newFile("empty.litertlm")
        assertThrows(IllegalArgumentException::class.java) {
            manager.getOrInitializeEngine(empty, cacheDir)
        }
    }

    @Test
    fun gpuPreferredSucceedsWhenGpuAvailable() {
        val model = temporaryFolder.newFile("test.litertlm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val cacheDir = temporaryFolder.newFolder("cache")

        var isGpuInvoked = false
        val mockEngine = MockEngine(isGpu = true)

        val manager = InferenceEngineManager<MockEngine>(
            preference = BackendPreference.GPU_PREFERRED,
            engineFactory = { isGpu, _, _ ->
                isGpuInvoked = isGpu
                mockEngine
            }
        )

        val result = manager.getOrInitializeEngine(model, cacheDir)
        assertEquals(mockEngine, result)
        assertEquals(ActiveBackend.GPU, manager.activeBackend)
        assertNull(manager.lastFallbackReason)
        assertTrue(isGpuInvoked)
    }

    @Test
    fun gpuPreferredGracefullyFallsBackToCpuWhenGpuInitFails() {
        val model = temporaryFolder.newFile("test.litertlm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val cacheDir = temporaryFolder.newFolder("cache")

        val attemptedBackends = mutableListOf<String>()
        var fallbackReported: String? = null
        val mockEngine = MockEngine(isGpu = false)

        val manager = InferenceEngineManager<MockEngine>(
            preference = BackendPreference.GPU_PREFERRED,
            engineFactory = { isGpu, _, _ ->
                if (isGpu) {
                    attemptedBackends.add("GPU")
                    throw IllegalStateException("libOpenCL.so not found on device")
                } else {
                    attemptedBackends.add("CPU")
                    mockEngine
                }
            }
        ).apply {
            onFallback = { reason -> fallbackReported = reason }
        }

        val result = manager.getOrInitializeEngine(model, cacheDir)
        assertEquals(mockEngine, result)
        assertEquals(ActiveBackend.CPU, manager.activeBackend)
        assertEquals(listOf("GPU", "CPU"), attemptedBackends)
        assertNotNull(manager.lastFallbackReason)
        assertTrue(manager.lastFallbackReason!!.contains("libOpenCL.so not found"))
        assertEquals(manager.lastFallbackReason, fallbackReported)
    }

    @Test
    fun cpuOnlyPreferenceDirectlyUsesCpuWithoutAttemptingGpu() {
        val model = temporaryFolder.newFile("test.litertlm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val cacheDir = temporaryFolder.newFolder("cache")

        val attemptedBackends = mutableListOf<String>()
        val mockEngine = MockEngine(isGpu = false)

        val manager = InferenceEngineManager<MockEngine>(
            preference = BackendPreference.CPU_ONLY,
            engineFactory = { isGpu, _, _ ->
                if (isGpu) {
                    attemptedBackends.add("GPU")
                    error("GPU should not be attempted under CPU_ONLY preference")
                } else {
                    attemptedBackends.add("CPU")
                    mockEngine
                }
            }
        )

        val result = manager.getOrInitializeEngine(model, cacheDir)
        assertEquals(mockEngine, result)
        assertEquals(ActiveBackend.CPU, manager.activeBackend)
        assertEquals(listOf("CPU"), attemptedBackends)
        assertNull(manager.lastFallbackReason)
    }

    @Test
    fun propagatesExceptionWhenCpuInitializationFails() {
        val model = temporaryFolder.newFile("test.litertlm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val cacheDir = temporaryFolder.newFolder("cache")

        val manager = InferenceEngineManager<MockEngine>(
            preference = BackendPreference.CPU_ONLY,
            engineFactory = { _, _, _ -> throw RuntimeException("Corrupted model file") }
        )

        val error = assertThrows(RuntimeException::class.java) {
            manager.getOrInitializeEngine(model, cacheDir)
        }
        assertEquals("Corrupted model file", error.message)
        assertNull(manager.activeBackend)
        assertNull(manager.engine)
    }

    @Test
    fun returnsCachedEngineOnSubsequentCallsUntilClosed() {
        val model = temporaryFolder.newFile("test.litertlm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val cacheDir = temporaryFolder.newFolder("cache")

        var factoryCallCount = 0
        val mockEngine = MockEngine(isGpu = false)

        val manager = InferenceEngineManager<MockEngine>(
            preference = BackendPreference.CPU_ONLY,
            engineFactory = { _, _, _ ->
                factoryCallCount++
                mockEngine
            }
        )

        val first = manager.getOrInitializeEngine(model, cacheDir)
        val second = manager.getOrInitializeEngine(model, cacheDir)
        assertEquals(first, second)
        assertEquals(1, factoryCallCount)

        manager.close()
        assertTrue(mockEngine.isClosed)
        assertNull(manager.engine)
        assertNull(manager.activeBackend)

        val third = manager.getOrInitializeEngine(model, cacheDir)
        assertEquals(2, factoryCallCount)
        assertEquals(mockEngine, third)
    }
}
