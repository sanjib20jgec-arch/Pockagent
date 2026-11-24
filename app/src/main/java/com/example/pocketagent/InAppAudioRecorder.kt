package com.example.pocketagent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seam abstraction for audio input hardware, allowing unit testing without Android AudioRecord stubs.
 */
interface AudioInputSource : AutoCloseable {
    fun startRecording()
    fun read(buffer: ShortArray, offset: Int, size: Int): Int
    fun stop()
    override fun close() {
        stop()
    }
}

/**
 * Standard Android AudioRecord implementation using 16-bit 16kHz mono microphone input.
 */
class DefaultAudioRecordSource(
    private val sampleRate: Int = AudioRecordingBuffer.SAMPLE_RATE,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) : AudioInputSource {

    private var audioRecord: AudioRecord? = null

    override fun startRecording() {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        require(minBufferSize > 0) { "Audio hardware format unsupported for 16kHz 16-bit mono recording." }
        val bufferSize = maxOf(minBufferSize * 2, 4096)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("AudioRecord failed to initialize.")
        }
        record.startRecording()
        audioRecord = record
    }

    override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
        val record = audioRecord ?: throw IOException("AudioRecord is not recording.")
        return record.read(buffer, offset, size)
    }

    override fun stop() {
        val record = audioRecord
        audioRecord = null
        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: Exception) {
            } finally {
                record.release()
            }
        }
    }
}

/**
 * Listener for real-time in-app microphone recording events.
 */
interface AudioRecorderListener {
    fun onRecordingStarted() {}
    fun onAmplitude(rms: Float) {}
    fun onSilenceCutoff() {}
    fun onMaxDurationReached() {}
    fun onRecordingComplete(samples: FloatArray)
    fun onError(error: Throwable)
}

/**
 * In-app audio recorder that captures microphone audio via Android AudioRecord in PCM 16-bit 16kHz mono.
 *
 * Audio is recorded directly into a bounded in-memory buffer without requiring system WAV pickers.
 * Hard bounds: max 60 seconds duration, max 25 MiB size, automatic silence cutoff.
 * Zero-wipes all in-memory audio buffers and safely discards any temporary cache files.
 */
class InAppAudioRecorder(
    private val buffer: AudioRecordingBuffer = AudioRecordingBuffer(),
    private val sourceFactory: () -> AudioInputSource = { DefaultAudioRecordSource() }
) {
    companion object {
        fun hasMicrophonePermission(context: Context): Boolean {
            return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    val isRecordingActive: Boolean
        get() = isRecording.get()

    val recordedDurationSeconds: Float
        get() = buffer.recordedDurationSeconds

    /**
     * Starts in-app audio recording.
     * Must be called only after microphone permission has been confirmed.
     */
    @Synchronized
    fun startRecording(listener: AudioRecorderListener) {
        if (isRecording.get()) {
            listener.onError(IllegalStateException("Audio recorder is already recording."))
            return
        }

        buffer.wipe()
        isRecording.set(true)

        val thread = Thread({
            var source: AudioInputSource? = null
            val readBuffer = ShortArray(2048)
            try {
                source = sourceFactory()
                source.startRecording()
                listener.onRecordingStarted()

                while (isRecording.get()) {
                    val readCount = source.read(readBuffer, 0, readBuffer.size)
                    if (readCount > 0) {
                        buffer.append(readBuffer, 0, readCount)
                        val rms = buffer.calculateRms(readBuffer, 0, readCount)
                        listener.onAmplitude(rms)

                        if (buffer.isFull) {
                            listener.onMaxDurationReached()
                            break
                        }
                        if (buffer.isSilenceCutoffReached) {
                            listener.onSilenceCutoff()
                            break
                        }
                    } else if (readCount < 0) {
                        throw IOException("Audio recording read error: code $readCount")
                    }
                }

                // If cancelled while recording, don't trigger completion
                if (isRecording.getAndSet(false)) {
                    val samples = buffer.toNormalizedFloats()
                    listener.onRecordingComplete(samples)
                }
            } catch (error: Throwable) {
                isRecording.set(false)
                buffer.wipe()
                listener.onError(error)
            } finally {
                try {
                    source?.stop()
                } catch (_: Exception) {}
                readBuffer.fill(0)
            }
        }, "PocketAgentAudioRecorder")

        recordingThread = thread
        thread.start()
    }

    /**
     * Requests the active recording to stop gracefully and produce samples.
     */
    @Synchronized
    fun stopRecording() {
        if (isRecording.get()) {
            isRecording.set(false)
            recordingThread?.interrupt()
        }
    }

    /**
     * Cancels the active recording and immediately zero-wipes all audio memory.
     */
    @Synchronized
    fun cancelRecording() {
        isRecording.set(false)
        recordingThread?.interrupt()
        buffer.wipe()
    }

    /**
     * Securely zero-wipes the internal audio buffer.
     */
    fun wipe() {
        buffer.wipe()
    }

    /**
     * Helper to zero-wipe a FloatArray of samples after Whisper transcription completes.
     */
    fun wipeSamples(samples: FloatArray?) {
        AudioRecordingBuffer.wipe(samples)
    }

    /**
     * Optionally writes the current recorded buffer to a temporary WAV file in cacheDir
     * for verification or staging.
     */
    fun writeTemporaryWavFile(cacheDir: File): File {
        val tempFile = File.createTempFile("pocketagent-mic-", ".wav", cacheDir)
        try {
            buffer.writeWavFile(tempFile)
            return tempFile
        } catch (e: Exception) {
            AudioRecordingBuffer.secureDelete(tempFile)
            throw e
        }
    }
}
