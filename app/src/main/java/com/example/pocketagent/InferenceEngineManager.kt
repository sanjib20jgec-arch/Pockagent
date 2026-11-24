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
    },
    /**
     * Opens a conversation on the live engine.
     *
     * The manager is generic so JVM tests can drive it with fakes; production always resolves to a
     * real LiteRT-LM [com.google.ai.edge.litertlm.Engine], and that cast lives here rather than
     * leaking into every caller.
     */
    private val conversationFactory: (T, String) -> com.google.ai.edge.litertlm.Conversation = { active, instruction ->
        @Suppress("UNCHECKED_CAST")
        val litertEngine = active as? com.google.ai.edge.litertlm.Engine
            ?: error("Conversation caching requires a real LiteRT-LM Engine.")
        litertEngine.createConversation(
            com.google.ai.edge.litertlm.ConversationConfig(
                systemInstruction = com.google.ai.edge.litertlm.Contents.of(instruction)
            )
        )
    },
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

    // ------------------------------------------------------- conversation (KV) cache

    /**
     * A reusable LiteRT-LM conversation.
     *
     * Reusing it keeps the KV cache warm, so later turns in the same chat only prefill the new
     * user message instead of the system instruction, history, and attachments again. On a 1B
     * INT4 model on a phone that is the difference between a couple of seconds and tens of
     * seconds per turn.
     */
    private var cachedConversation: com.google.ai.edge.litertlm.Conversation? = null

    /** Identity of the conversation currently cached; any change invalidates it. */
    private var cachedKey: String? = null

    /** True while a turn is in flight, so we never hand out the same conversation twice. */
    @Volatile private var isGenerating = false

    @Volatile var lastInvalidationReason: String? = null
        private set

    /** Number of turns served from a warm conversation, for diagnostics and tests. */
    @Volatile var conversationReuseCount: Int = 0
        private set

    /** Number of conversations actually created, for diagnostics and tests. */
    @Volatile var conversationCreateCount: Int = 0
        private set

    /**
     * Returns a conversation for [key], creating one only when none is cached.
     *
     * [key] must capture everything that makes the conversation invalid when it changes: the
     * session id, model path, system instruction, and any attachment context. Callers build it via
     * [conversationKey].
     */
    @Synchronized
    fun getOrCreateConversation(
        key: String,
        systemInstruction: String,
    ): com.google.ai.edge.litertlm.Conversation {
        val activeEngine = engine ?: error("Engine must be initialized before opening a conversation.")
        val existing = cachedConversation
        if (existing != null && cachedKey == key) {
            conversationReuseCount++
            return existing
        }

        if (existing != null) {
            lastInvalidationReason = "conversation key changed"
            closeConversationLocked()
        }

        val created = conversationFactory(activeEngine, systemInstruction)
        cachedConversation = created
        cachedKey = key
        conversationCreateCount++
        return created
    }

    /**
     * Drops the cached conversation so the next turn starts from a clean context.
     *
     * Safe to call when nothing is cached, and safe to call from any thread.
     */
    @Synchronized
    fun invalidateConversation(reason: String) {
        lastInvalidationReason = reason
        closeConversationLocked()
    }

    /** Marks a generation as started so a second caller cannot reuse the same conversation. */
    fun beginGeneration() {
        isGenerating = true
    }

    /** Marks the in-flight generation as finished. */
    fun endGeneration() {
        isGenerating = false
    }

    /** True while a turn is in flight on this engine. */
    val isGenerationActive: Boolean get() = isGenerating

    /** Closes and forgets the cached conversation. */
    @Synchronized
    fun closeConversation() {
        closeConversationLocked()
    }

    private fun closeConversationLocked() {
        val conversation = cachedConversation
        cachedConversation = null
        cachedKey = null
        try {
            conversation?.close()
        } catch (_: Exception) {
            // A conversation that cannot close cleanly must not mask the real error.
        }
    }

    /**
     * Builds the cache key. Every component that must reset the KV cache belongs here, so that
     * switching chat, model, instruction, or attachment reliably starts a fresh context.
     */
    fun conversationKey(
        sessionId: String?,
        modelPath: String,
        systemInstruction: String,
        attachmentHash: Int,
    ): String = buildString {
        append(sessionId ?: "no-session")
        append('|').append(modelPath)
        append('|').append(systemInstruction.hashCode())
        append('|').append(attachmentHash)
    }

    /**
     * Closes and releases any active engine instance and resets active backend state.
     */
    @Synchronized
    override fun close() {
        closeConversation()
        try {
            engine?.close()
        } finally {
            engine = null
            activeBackend = null
        }
    }
}
