package com.example.pocketagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class AgentService : AccessibilityService() {
    companion object { @Volatile var inst: AgentService? = null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: Engine? = null
    private var job: Job? = null
    private var nodes = listOf<AccessibilityNodeInfo>()

    private val SYS = """You control an Android phone. Each turn you see the GOAL, your PREVIOUS actions, and the SCREEN as numbered elements.
Reply with exactly ONE JSON object, nothing else. Actions:
{"a":"tap","id":N} {"a":"type","id":N,"text":"..."} {"a":"scroll","dir":"down|up"} {"a":"back"} {"a":"home"} {"a":"open","app":"name"} {"a":"done","msg":"result"}
Rules: pick ids only from SCREEN. Use open to start apps. Type into an edit field before tapping send/search. Say done when the goal is complete or impossible."""

    override fun onServiceConnected() { inst = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { inst = null; job?.cancel(); engine?.close(); super.onDestroy() }

    fun run(goal: String) {
        job?.cancel()
        job = scope.launch {
            showStop()
            try { agent(goal) } catch (e: CancellationException) { Bus.say("Stopped.") }
            catch (e: Throwable) { Bus.say("Error: ${e.message}") }
            finally { hideStop() }
        }
    }
    fun stop() { job?.cancel() }

    private var stopBtn: Button? = null
    private val ui = Handler(Looper.getMainLooper())
    private fun wm() = getSystemService(WINDOW_SERVICE) as WindowManager

    private fun showStop() { ui.post {
        if (stopBtn != null) return@post
        val b = Button(this).apply { text = "■ STOP"; setTextColor(Color.WHITE); setBackgroundColor(Color.RED); setOnClickListener { stop() } }
        val lp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; y = 140 }
        wm().addView(b, lp); stopBtn = b
    } }

    private fun hideStop() { ui.post { stopBtn?.let { try { wm().removeView(it) } catch (_: Exception) {} }; stopBtn = null } }

    private suspend fun agent(goal: String) {
        // Instant path: no model needed for "open <app>"
        Regex("^(open|launch|start)\\s+(.+)$", RegexOption.IGNORE_CASE).find(goal.trim())?.let {
            if (openApp(it.groupValues[2])) { Bus.say("Opened ${it.groupValues[2]}"); return }
        }
        val eng = engine ?: loadEngine().also { engine = it }
        val hist = mutableListOf<String>()
        repeat(15) {
            delay(900)
            val prompt = "GOAL: $goal\nPREVIOUS: ${hist.takeLast(4).joinToString("; ").ifEmpty { "none" }}\nSCREEN:\n${dump()}\nJSON:"
            val raw = eng.createConversation(ConversationConfig(systemInstruction = Contents.of(SYS)))
                .use { it.sendMessage(prompt).toString() }
            val j = try { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }
                    catch (e: Exception) { hist += "bad-output"; Bus.say("? $raw"); return@repeat }
            val a = j.optString("a"); val id = j.optInt("id", -1)
            val sig = j.toString()
            if (hist.count { x -> x == sig } >= 2) { Bus.say("Stuck in a loop, stopping."); return }
            hist += sig; Bus.say("→ $sig")
            when (a) {
                "tap" -> nodes.getOrNull(id)?.let { click(it) }
                "type" -> nodes.getOrNull(id)?.let {
                    val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, j.optString("text")) }
                    it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
                }
                "scroll" -> swipe(j.optString("dir") != "up")
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "open" -> if (!openApp(j.optString("app"))) hist += "app-not-found"
                "done" -> { Bus.say("✔ ${j.optString("msg")}"); return }
            }
        }
        Bus.say("Step limit reached.")
    }

    private fun loadEngine(): Engine {
        val path = File(filesDir, "model.litertlm").path
        fun make(b: Backend) = Engine(EngineConfig(modelPath = path, backend = b, cacheDir = cacheDir.path)).also { it.initialize() }
        Bus.say("Loading model…")
        return try { make(Backend.GPU()) } catch (e: Throwable) { Bus.say("GPU unavailable, using CPU"); make(Backend.CPU()) }
    }

    private fun label(n: AccessibilityNodeInfo): String =
        (n.text ?: n.contentDescription ?: n.hintText ?: n.viewIdResourceName?.substringAfter('/') ?: "").toString().take(40)

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (out.size >= 40) return
        if (n.isVisibleToUser && (n.isClickable || n.isEditable || n.isScrollable || (n.childCount == 0 && label(n).isNotBlank()))) out.add(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out) }
    }

    private fun dump(): String {
        val root = rootInActiveWindow ?: return "(screen not readable)"
        val list = mutableListOf<AccessibilityNodeInfo>(); collect(root, list); nodes = list
        return list.mapIndexed { i, n ->
            val k = when { n.isEditable -> "edit"; n.isClickable -> "btn"; n.isScrollable -> "scroll"; else -> "text" }
            "$i $k \"${label(n)}\""
        }.joinToString("\n")
    }

    private fun click(n: AccessibilityNodeInfo) {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        val r = Rect(); n.getBoundsInScreen(r)
        gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 50)
    }

    private fun swipe(down: Boolean) {
        val m = resources.displayMetrics; val x = m.widthPixels / 2f
        val (y1, y2) = if (down) m.heightPixels * .7f to m.heightPixels * .3f else m.heightPixels * .3f to m.heightPixels * .7f
        gesture(Path().apply { moveTo(x, y1); lineTo(x, y2) }, 250)
    }

    private fun gesture(p: Path, ms: Long) {
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    private fun openApp(name: String): Boolean {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(q, 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().equals(name.trim(), true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(name.trim(), true) } ?: return false
        startActivity(pm.getLaunchIntentForPackage(hit.activityInfo.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
