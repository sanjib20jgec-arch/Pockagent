package com.example.pocketagent

/** Hardware acceleration preference for LiteRT-LM inference engine. */
enum class BackendPreference(
    val storageValue: String,
    val displayName: String,
) {
    NPU_PREFERRED("npu_preferred", "NPU Preferred"),
    GPU_PREFERRED("gpu_preferred", "GPU Preferred"),
    CPU_ONLY("cpu_only", "CPU Only");

    companion object {
        fun fromString(value: String?): BackendPreference {
            return when (value?.trim()?.uppercase()) {
                "NPU", "NPU_PREFERRED" -> NPU_PREFERRED
                "CPU", "CPU_ONLY" -> CPU_ONLY
                "GPU", "GPU_PREFERRED" -> GPU_PREFERRED
                else -> GPU_PREFERRED
            }
        }

        fun fromStorageValue(value: String?): BackendPreference = fromString(value)
    }
}

/** Represents the actual hardware backend currently in use by an initialized engine. */
enum class ActiveBackend {
    NPU,
    GPU,
    CPU
}
