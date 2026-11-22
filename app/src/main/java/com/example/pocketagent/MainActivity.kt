package com.example.pocketagent

import android.app.Activity
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.provider.Settings
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private lateinit var log: TextView
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val goal = EditText(this).apply { hint = "e.g. open Settings, or: turn on Wi-Fi" }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        log = TextView(this).apply { text = Bus.text.toString() }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32)
            addView(btn("1. Pick model (.litertlm)") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1) })
            addView(btn("2. Enable accessibility service") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            addView(goal)
            addView(btn("🎤 Speak (offline)") { listen(goal) })
            addView(btn("Run") { runGoal(goal.text.toString()) })
            addView(btn("Stop") { AgentService.inst?.stop() })
            addView(ScrollView(this@MainActivity).apply { addView(log) })
        }
        setContentView(col)
        Bus.onChange = { t -> runOnUiThread { log.text = t } }
    }

    private fun runGoal(g: String) {
        val svc = AgentService.inst
        if (svc == null) Bus.say("Enable the accessibility service first.")
        else if (!File(filesDir, "model.litertlm").exists() && !g.startsWith("open", true)) Bus.say("Pick a model first.")
        else { Bus.say("Goal: $g"); svc.run(g) }
    }

    private fun listen(goal: EditText) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2); Bus.say("Allow the mic, then tap Speak again."); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { Bus.say("No speech recognizer on this phone."); return }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t != null) { goal.setText(t); runGoal(t) }
                r.destroy()
            }
            override fun onError(e: Int) { Bus.say("Didn't catch that (error $e). If offline: install your language's offline speech pack."); r.destroy() }
            override fun onReadyForSpeech(p: Bundle?) { Bus.say("Listening…") }
            override fun onBeginningOfSpeech() {}; override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}; override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}; override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val uri = data?.data ?: return
        Bus.say("Copying model…")
        Thread {
            contentResolver.openInputStream(uri)?.use { i -> File(filesDir, "model.litertlm").outputStream().use { i.copyTo(it) } }
            Bus.say("Model ready.")
        }.start()
    }
}
