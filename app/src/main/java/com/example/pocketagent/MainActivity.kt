package com.example.pocketagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val PICK_MODEL = 1
        private const val REQUEST_MIC = 2
        private const val SYSTEM_INSTRUCTION = """You are PocketAgent, a local step-by-step assistant. The user will describe a task but you cannot see or control their device. Give concise, numbered instructions for the user to carry out manually. Never claim that you performed an action, read their screen, or changed a setting. Do not ask for passwords, PINs, one-time codes, recovery phrases, or payment details. For sensitive account, payment, or security tasks, remind the user to verify each step in the official app and make the final decision themselves."""
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var engine: Engine? = null
    private lateinit var goalInput: EditText
    private lateinit var status: TextView
    private lateinit var answer: TextView
    private lateinit var suggestButton: Button
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        goalInput = EditText(this).apply {
            hint = "Describe what you want to do"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        status = TextView(this).apply { text = "Choose a compatible .litertlm model to get started." }
        answer = TextView(this).apply { textSize = 16f; text = "Suggested steps will appear here. Nothing is executed for you." }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(TextView(this@MainActivity).apply {
                text = "PocketAgent — Manual Assistant"
                textSize = 22f
            })
            addView(TextView(this@MainActivity).apply {
                text = "PocketAgent can suggest steps using your selected on-device model. It does not read other apps' screens, tap, type, launch apps, or change settings. Review the suggestions and perform any actions yourself."
                textSize = 15f
            })
            addView(button("1. Choose model (.litertlm)") { chooseModel() })
            addView(goalInput)
            addView(button("Dictate goal") { startDictation() })
            suggestButton = button("Suggest steps") { suggestSteps() }
            addView(suggestButton)
            addView(TextView(this@MainActivity).apply {
                text = "Voice recognition uses the speech provider installed on your device. Offline recognition is requested when supported by that provider."
                textSize = 13f
            })
            addView(status)
            addView(TextView(this@MainActivity).apply {
                text = "Suggestions (not executed)"
                textSize = 18f
            })
            addView(answer)
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun chooseModel() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(picker, PICK_MODEL)
    }

    @Deprecated("Uses the platform document picker for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        status.text = "Copying the selected model into app-private storage…"
        suggestButton.isEnabled = false
        worker.execute {
            try {
                val target = File(filesDir, "model.litertlm")
                val temporary = File(filesDir, "model.litertlm.part")
                contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Could not open the selected file.")
                require(temporary.length() > 0) { "The selected model file is empty." }
                if (target.exists() && !target.delete()) error("Could not replace the previous model.")
                if (!temporary.renameTo(target)) error("Could not save the model in app storage.")
                mainHandler.post {
                    status.text = "Model ready. Enter a task and request suggestions."
                    suggestButton.isEnabled = true
                }
            } catch (e: Exception) {
                File(filesDir, "model.litertlm.part").delete()
                mainHandler.post {
                    status.text = "Model import failed: ${e.message ?: "unknown error"}"
                    suggestButton.isEnabled = true
                }
            }
        }
    }

    private fun suggestSteps() {
        val task = goalInput.text.toString().trim()
        if (task.isEmpty()) {
            status.text = "Describe a task first."
            return
        }
        if (!File(filesDir, "model.litertlm").exists()) {
            status.text = "Choose a compatible .litertlm model first."
            return
        }
        suggestButton.isEnabled = false
        status.text = "Generating suggestions on this device…"
        answer.text = ""
        worker.execute {
            try {
                val localEngine = engine ?: Engine(
                    EngineConfig(
                        modelPath = File(filesDir, "model.litertlm").absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = cacheDir.absolutePath,
                    )
                ).also {
                    it.initialize()
                    engine = it
                }
                val prompt = "Task to explain with manual steps: $task"
                val result = localEngine.createConversation(
                    ConversationConfig(systemInstruction = Contents.of(SYSTEM_INSTRUCTION))
                ).use { conversation -> conversation.sendMessage(prompt).toString().trim() }
                mainHandler.post {
                    answer.text = result.ifBlank { "No suggestion was returned. Try rephrasing the task." }
                    status.text = "Suggestions ready. Review each step and do them yourself."
                    suggestButton.isEnabled = true
                }
            } catch (e: Exception) {
                mainHandler.post {
                    status.text = "Could not generate suggestions: ${e.message ?: "unknown error"}"
                    suggestButton.isEnabled = true
                }
            }
        }
    }

    private fun startDictation() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            status.text = "Allow microphone access, then tap Dictate goal again."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "No speech recognizer is available on this device. You can type your task instead."
            return
        }
        recognizer?.destroy()
        val speech = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening for your task…" }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text != null) {
                    goalInput.setText(text)
                    status.text = "Transcript ready. Review it, then tap Suggest steps."
                } else status.text = "No speech was recognized. You can type your task instead."
                speech.destroy()
                if (recognizer === speech) recognizer = null
            }
            override fun onError(error: Int) {
                status.text = "Speech recognition failed ($error). You can type your task instead."
                speech.destroy()
                if (recognizer === speech) recognizer = null
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speech.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        )
    }

    override fun onDestroy() {
        recognizer?.destroy()
        worker.execute {
            engine?.close()
            engine = null
        }
        worker.shutdown()
        super.onDestroy()
    }
}
