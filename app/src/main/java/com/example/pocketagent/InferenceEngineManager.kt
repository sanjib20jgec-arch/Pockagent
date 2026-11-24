package com.example.pocketagent

import java.io.File

/**
 * Manages LiteRT-LM engine lifecycle and hardware backend selection with automatic graceful
 * fallback from GPU acceleration to CPU execution.
 *
 * Parameterized by [T] (defaulting to [AutoCloseable]) so unit tests on standard JVMs can verify
 * fallback policies without loading native LiteRT-LM classes that require Java 21 bytecode.
 */
class InferenceEngineManager<T : AutoCloseable>(
    var preference: BackendPreference = BackendPreference.GPU_PREFERRED,
    private val engineFactory: (isGpu: Boolean, modelFile: File, cacheDir: File) -> T = { isGpu, model, cache ->
        val config = com.google.ai.edge.litertlm.EngineConfig(
            modelPath = model.absolutePath,
            backend = if (isGpu) com.google.ai.edge.litertlm.Backend.GPU() else com.google.ai.edge.litertlm.Backend.CPU(),
            cacheDir = cache.absolutePath
        )
        @Suppress("UNCHECKED_CAST")
        (com.google.ai.edge.litertlm.Engine(config).also { it.initialize() } as T)
    }
) : AutoCloseable {

    @Volatile var engine: T? = null
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
    ): T {
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
                val newEngine = engineFactory(true, modelFile, cacheDir)
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

        val cpuEngine = engineFactory(false, modelFile, cacheDir)
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
