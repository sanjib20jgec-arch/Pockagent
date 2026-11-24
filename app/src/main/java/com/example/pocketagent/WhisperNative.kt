package com.example.pocketagent

/** Minimal JNI seam around the pinned whisper.cpp CPU build. */
internal object WhisperNative {
    init {
        System.loadLibrary("pocketagent_whisper")
    }

    external fun loadModel(modelPath: String): Long
    external fun transcribe(context: Long, audioSamples: FloatArray, maxChars: Int): String
    external fun release(context: Long)
}
