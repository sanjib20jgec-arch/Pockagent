package com.example.pocketagent

import java.util.concurrent.atomic.AtomicLong

/**
 * Small production helper for binding in-flight generation callbacks to the generation and
 * originating session that started them. Used by MainActivity to prevent stale completion/cancel
 * callbacks from mutating another session or a newer generation.
 */
class GenerationLifecycleTracker {

    data class GenerationToken(
        val generationId: Long,
        val originatingSessionId: String,
    )

    private val counter = AtomicLong(0L)

    @Volatile
    var activeGenerationId: Long = 0L
        private set

    @Volatile
    var activeOriginatingSessionId: String? = null
        private set

    fun begin(originatingSessionId: String): GenerationToken {
        val id = counter.incrementAndGet()
        activeGenerationId = id
        activeOriginatingSessionId = originatingSessionId
        return GenerationToken(id, originatingSessionId)
    }

    fun isCurrent(generationId: Long): Boolean = generationId != 0L && generationId == activeGenerationId

    fun isCurrent(token: GenerationToken): Boolean =
        isCurrent(token.generationId) && activeOriginatingSessionId == token.originatingSessionId

    /** Invalidates the active generation slot (e.g. when switching sessions after generation). */
    fun invalidateActiveGeneration() {
        activeGenerationId = 0L
        activeOriginatingSessionId = null
    }

    /**
     * True when UI/persistence for [generationId] should be applied to the currently selected UI.
     * Stale or cancelled generations must not mutate current UI.
     */
    fun canApplyUi(generationId: Long): Boolean = isCurrent(generationId)

    /**
     * True when a completion may persist into [originatingSessionId]. Persistence is allowed only
     * for the matching generation token; it may still be valid even if UI is no longer current.
     */
    fun canPersist(generationId: Long, originatingSessionId: String): Boolean =
        generationId != 0L && generationId == activeGenerationId && activeOriginatingSessionId == originatingSessionId

    fun isSessionMutationBlockedDuringGeneration(sessionId: String, isGenerating: Boolean): Boolean =
        isGenerating && activeOriginatingSessionId == sessionId
}
