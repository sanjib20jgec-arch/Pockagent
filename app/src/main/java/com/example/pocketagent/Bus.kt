package com.example.pocketagent
object Bus {
    val text = StringBuilder()
    var onChange: ((String) -> Unit)? = null
    fun say(s: String) { synchronized(text) { text.append(s).append('\n') }; onChange?.invoke(text.toString()) }
}
