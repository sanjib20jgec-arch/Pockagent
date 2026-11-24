package com.example.pocketagent

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * Manages LiteRT-LM engine lifecycle and hardware backend selection with automatic graceful
 * fallback from GPU acceleration to CPU execution.
 */
class InferenceEngineManager(
    var preference: BackendPreference = BackendPreference.GPU_PREFERRED,
    private val engineFactory: (EngineConfig) -> Engine = { config ->
        Engine(config).also { it.initialize() }
    }
) : AutoCloseable {

    @Volatile var engine: Engine? = null
        private set

    @Volatile var activeBackend: ActiveBackend? = null
        private set

    @Volatile var lastFallbackReason: String? = null
        private set

    var onFallback: ((reason: String) -> Unit)? = null

    /**
     * Obtains the current initialized Engine, or initializes a new one according to the
     * specified [BackendPreference]. If GPU initialization fails (e.g. missing OpenCL driver
     * or unsupported GPU op), it automatically falls back to CPU execution.
     */
    @Synchronized
    fun getOrInitializeEngine(
        modelFile: File,
        cacheDir: File,
        preferredBackend: BackendPreference = preference
    ): Engine {
        require(modelFile.isFile && modelFile.length() > 0) {
            "Model file must exist and be non-empty: ${modelFile.name}"
        }

        val current = engine
        if (current != null) {
            return current
        }

        preference = preferredBackend
        lastFallbackReason = null

        if (preferredBackend == BackendPreference.GPU_PREFERRED) {
            try {
                val gpuConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    cacheDir = cacheDir.absolutePath
                )
                val newEngine = engineFactory(gpuConfig)
                engine = newEngine
                activeBackend = ActiveBackend.GPU
                return newEngine
            } catch (gpuError: Throwable) {
                val reason = gpuError.message ?: gpuError.javaClass.simpleName
                val fallbackNote = "GPU initialization failed ($reason); falling back to CPU."
                lastFallbackReason = fallbackNote
                onFallback?.invoke(fallbackNote)
            }
        }

        val cpuConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(),
            cacheDir = cacheDir.absolutePath
        )
        val cpuEngine = engineFactory(cpuConfig)
        engine = cpuEngine
        activeBackend = ActiveBackend.CPU
        return cpuEngine
    }

    /**
     * Closes and releases any active engine instance and resets active backend state.
     */
    @Synchronized
    override fun close() {
        try {
            engine?.close()
        } finally {
            engine = null
            activeBackend = null
        }
    }
}
