package com.example.pocketagent

/** Hardware acceleration preference for LiteRT-LM inference engine. */
enum class BackendPreference {
    GPU_PREFERRED,
    CPU_ONLY;

    companion object {
        fun fromString(value: String?): BackendPreference {
            return when (value?.trim()?.uppercase()) {
                "CPU", "CPU_ONLY" -> CPU_ONLY
                "GPU", "GPU_PREFERRED" -> GPU_PREFERRED
                else -> GPU_PREFERRED
            }
        }
    }
}

/** Represents the actual hardware backend currently in use by an initialized engine. */
enum class ActiveBackend {
    GPU,
    CPU
}
