package com.example.pocketagent

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.lang.reflect.Proxy
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
        val manager = InferenceEngineManager()
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

        var configuredBackend: Backend? = null
        val mockEngine = createMockEngine()

        val manager = InferenceEngineManager(
            preference = BackendPreference.GPU_PREFERRED,
            engineFactory = { config ->
                configuredBackend = config.backend
                mockEngine
            }
        )

        val result = manager.getOrInitializeEngine(model, cacheDir)
        assertEquals(mockEngine, result)
        assertEquals(ActiveBackend.GPU, manager.activeBackend)
        assertNull(manager.lastFallbackReason)
        assertTrue(configuredBackend is Backend.GPU)
    }

    @Test
    fun gpuPreferredGracefullyFallsBackToCpuWhenGpuInitFails() {
        val model = temporaryFolder.newFile("test.litertlm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val cacheDir = temporaryFolder.newFolder("cache")

        var attemptedBackends = mutableListOf<String>()
        var fallbackReported: String? = null
        val mockEngine = createMockEngine()

        val manager = InferenceEngineManager(
            preference = BackendPreference.GPU_PREFERRED,
            engineFactory = { config ->
                if (config.backend is Backend.GPU) {
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

        var attemptedBackends = mutableListOf<String>()
        val mockEngine = createMockEngine()

        val manager = InferenceEngineManager(
            preference = BackendPreference.CPU_ONLY,
            engineFactory = { config ->
                if (config.backend is Backend.GPU) {
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

        val manager = InferenceEngineManager(
            preference = BackendPreference.CPU_ONLY,
            engineFactory = { throw RuntimeException("Corrupted model file") }
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
        val mockEngine = createMockEngine()

        val manager = InferenceEngineManager(
            preference = BackendPreference.CPU_ONLY,
            engineFactory = {
                factoryCallCount++
                mockEngine
            }
        )

        val first = manager.getOrInitializeEngine(model, cacheDir)
        val second = manager.getOrInitializeEngine(model, cacheDir)
        assertEquals(first, second)
        assertEquals(1, factoryCallCount)

        manager.close()
        assertNull(manager.engine)
        assertNull(manager.activeBackend)

        val third = manager.getOrInitializeEngine(model, cacheDir)
        assertEquals(2, factoryCallCount)
        assertEquals(mockEngine, third)
    }

    /**
     * Creates a lightweight mock of Engine for JVM unit testing without loading native libraries.
     */
    private fun createMockEngine(): Engine {
        return try {
            val config = EngineConfig(modelPath = "/stub", backend = Backend.CPU())
            val constructor = Engine::class.java.getDeclaredConstructor(EngineConfig::class.java)
            constructor.isAccessible = true
            constructor.newInstance(config)
        } catch (_: Throwable) {
            // JVM mock using pure reflection without compile-time sun.misc imports
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafeField.isAccessible = true
            val unsafe = theUnsafeField.get(null)
            val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
            allocateInstance.invoke(unsafe, Engine::class.java) as Engine
        }
    }
}
