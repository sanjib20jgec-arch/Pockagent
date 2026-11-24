package com.example.pocketagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

/**
 * Parses assistant suggestions for structured actionable system intents (Alarm, Timer,
 * Calendar, Map, Note) and constructs explicit Android Intent objects for user review.
 *
 * CRITICAL SAFETY INVARIANT:
 * Never auto-execute or bypass the user!
 * This tool constructs the explicit Intent and presents preview information
 * ("Tap to open in System App") for the human user to review and launch themselves.
 */
object SystemActionTools {

    // Action strings
    const val ACTION_SET_TIMER = AlarmClock.ACTION_SET_TIMER
    const val ACTION_SET_ALARM = AlarmClock.ACTION_SET_ALARM
    const val ACTION_INSERT_CALENDAR = Intent.ACTION_INSERT
    const val ACTION_VIEW_MAP = Intent.ACTION_VIEW
    const val ACTION_CREATE_NOTE = Intent.ACTION_SEND

    // Extra keys
    const val EXTRA_TIMER_LENGTH = AlarmClock.EXTRA_LENGTH
    const val EXTRA_ALARM_HOUR = AlarmClock.EXTRA_HOUR
    const val EXTRA_ALARM_MINUTES = AlarmClock.EXTRA_MINUTES
    const val EXTRA_MESSAGE = AlarmClock.EXTRA_MESSAGE
    const val EXTRA_SKIP_UI = AlarmClock.EXTRA_SKIP_UI

    const val CALENDAR_EVENTS_URI = "content://com.android.calendar/events"
    const val EXTRA_CALENDAR_TITLE = CalendarContract.Events.TITLE
    const val EXTRA_CALENDAR_DESCRIPTION = CalendarContract.Events.DESCRIPTION
    const val EXTRA_CALENDAR_LOCATION = CalendarContract.Events.EVENT_LOCATION
    const val EXTRA_CALENDAR_BEGIN_TIME = CalendarContract.EXTRA_EVENT_BEGIN_TIME
    const val EXTRA_CALENDAR_END_TIME = CalendarContract.EXTRA_EVENT_END_TIME
    const val EXTRA_CALENDAR_ALL_DAY = CalendarContract.EXTRA_EVENT_ALL_DAY

    const val EXTRA_NOTE_TEXT = Intent.EXTRA_TEXT
    const val EXTRA_NOTE_SUBJECT = Intent.EXTRA_SUBJECT

    /**
     * Inspectable descriptor representing an Intent without requiring Android framework classes in unit tests.
     */
    data class IntentDescriptor(
        val action: String,
        val dataUri: String? = null,
        val mimeType: String? = null,
        val extras: Map<String, Any> = emptyMap(),
        val flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK,
    )

    /**
     * UI preview model to present the action for human review and launch.
     */
    data class ActionReviewUi(
        val action: SystemAction,
        val buttonLabel: String,
        val dialogTitle: String,
        val dialogMessage: String,
        val targetAppCategory: String,
    )

    /**
     * Sealed hierarchy of actionable system operations suggested by the assistant.
     */
    sealed class SystemAction {
        abstract val actionType: ActionType
        abstract val userSummary: String
        abstract val intentAction: String

        // Ironclad safety invariants
        val requiresUserConfirmation: Boolean get() = true
        val autoExecute: Boolean get() = false

        /** Inspectable representation of the intent parameters */
        abstract fun toDescriptor(): IntentDescriptor

        /** Builds the real Android Intent for user-confirmed launch */
        abstract fun toIntent(): Intent

        /**
         * Action: Set a countdown timer in the system Clock app.
         * EXTRA_SKIP_UI is forced to false so the user reviews the timer in Clock app.
         */
        data class SetTimer(
            val seconds: Int,
            val message: String = "",
        ) : SystemAction() {
            init {
                require(seconds > 0) { "Timer duration must be positive (got $seconds seconds)." }
                require(seconds <= 86400 * 7) { "Timer duration exceeds maximum limit of 7 days." }
            }

            override val actionType = ActionType.SET_TIMER
            override val intentAction = ACTION_SET_TIMER
            override val userSummary = if (message.isNotBlank()) {
                "Timer for $message (${formatSeconds(seconds)})"
            } else {
                "Timer for ${formatSeconds(seconds)}"
            }

            override fun toDescriptor() = IntentDescriptor(
                action = intentAction,
                extras = buildMap {
                    put(EXTRA_TIMER_LENGTH, seconds)
                    if (message.isNotBlank()) put(EXTRA_MESSAGE, message.trim())
                    put(EXTRA_SKIP_UI, false) // CRITICAL: Never skip UI
                },
                flags = Intent.FLAG_ACTIVITY_NEW_TASK,
            )

            override fun toIntent(): Intent = Intent(intentAction).apply {
                putExtra(EXTRA_TIMER_LENGTH, seconds)
                if (message.isNotBlank()) putExtra(EXTRA_MESSAGE, message.trim())
                putExtra(EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        /**
         * Action: Set an alarm in the system Clock app.
         * EXTRA_SKIP_UI is forced to false so the user reviews the alarm time.
         */
        data class SetAlarm(
            val hour: Int,
            val minutes: Int,
            val message: String = "",
        ) : SystemAction() {
            init {
                require(hour in 0..23) { "Hour must be between 0 and 23 (got $hour)." }
                require(minutes in 0..59) { "Minutes must be between 0 and 59 (got $minutes)." }
            }

            override val actionType = ActionType.SET_ALARM
            override val intentAction = ACTION_SET_ALARM
            override val userSummary = buildString {
                append(String.format(Locale.ROOT, "Alarm at %02d:%02d", hour, minutes))
                if (message.isNotBlank()) append(" ($message)")
            }

            override fun toDescriptor() = IntentDescriptor(
                action = intentAction,
                extras = buildMap {
                    put(EXTRA_ALARM_HOUR, hour)
                    put(EXTRA_ALARM_MINUTES, minutes)
                    if (message.isNotBlank()) put(EXTRA_MESSAGE, message.trim())
                    put(EXTRA_SKIP_UI, false) // CRITICAL: Never skip UI
                },
                flags = Intent.FLAG_ACTIVITY_NEW_TASK,
            )

            override fun toIntent(): Intent = Intent(intentAction).apply {
                putExtra(EXTRA_ALARM_HOUR, hour)
                putExtra(EXTRA_ALARM_MINUTES, minutes)
                if (message.isNotBlank()) putExtra(EXTRA_MESSAGE, message.trim())
                putExtra(EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        /**
         * Action: Draft an event in the system Calendar app.
         */
        data class DraftCalendarEvent(
            val title: String,
            val description: String = "",
            val location: String = "",
            val beginTimeMillis: Long? = null,
            val endTimeMillis: Long? = null,
            val allDay: Boolean = false,
        ) : SystemAction() {
            init {
                require(title.isNotBlank()) { "Calendar event title cannot be blank." }
                if (beginTimeMillis != null && endTimeMillis != null) {
                    require(endTimeMsSafe(endTimeMillis) >= beginTimeMillis) {
                        "Calendar event end time cannot be before begin time."
                    }
                }
            }

            private fun endTimeMsSafe(t: Long): Long = t

            override val actionType = ActionType.CALENDAR_EVENT
            override val intentAction = ACTION_INSERT_CALENDAR
            override val userSummary = buildString {
                append("Event: ${title.trim()}")
                if (location.isNotBlank()) append(" at ${location.trim()}")
            }

            override fun toDescriptor() = IntentDescriptor(
                action = intentAction,
                dataUri = CALENDAR_EVENTS_URI,
                extras = buildMap {
                    put(EXTRA_CALENDAR_TITLE, title.trim())
                    if (description.isNotBlank()) put(EXTRA_CALENDAR_DESCRIPTION, description.trim())
                    if (location.isNotBlank()) put(EXTRA_CALENDAR_LOCATION, location.trim())
                    if (beginTimeMillis != null) put(EXTRA_CALENDAR_BEGIN_TIME, beginTimeMillis)
                    if (endTimeMillis != null) put(EXTRA_CALENDAR_END_TIME, endTimeMillis)
                    if (allDay) put(EXTRA_CALENDAR_ALL_DAY, true)
                },
                flags = Intent.FLAG_ACTIVITY_NEW_TASK,
            )

            override fun toIntent(): Intent = Intent(intentAction).apply {
                data = Uri.parse(CALENDAR_EVENTS_URI)
                putExtra(EXTRA_CALENDAR_TITLE, title.trim())
                if (description.isNotBlank()) putExtra(EXTRA_CALENDAR_DESCRIPTION, description.trim())
                if (location.isNotBlank()) putExtra(EXTRA_CALENDAR_LOCATION, location.trim())
                if (beginTimeMillis != null) putExtra(EXTRA_CALENDAR_BEGIN_TIME, beginTimeMillis)
                if (endTimeMillis != null) putExtra(EXTRA_CALENDAR_END_TIME, endTimeMillis)
                if (allDay) putExtra(EXTRA_CALENDAR_ALL_DAY, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        /**
         * Action: Open a map or navigation app using a geo: URI.
         */
        data class OpenMap(
            val query: String = "",
            val latitude: Double? = null,
            val longitude: Double? = null,
        ) : SystemAction() {
            init {
                require(query.isNotBlank() || (latitude != null && longitude != null)) {
                    "Either query or latitude and longitude coordinates must be provided."
                }
                if (latitude != null) {
                    require(latitude in -90.0..90.0) { "Latitude must be in range [-90.0, 90.0]." }
                }
                if (longitude != null) {
                    require(longitude in -180.0..180.0) { "Longitude must be in range [-180.0, 180.0]." }
                }
            }

            val geoUriString: String get() {
                val encodedQuery = if (query.isNotBlank()) {
                    URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name()).replace("+", "%20")
                } else ""

                return when {
                    latitude != null && longitude != null -> {
                        if (encodedQuery.isNotEmpty()) {
                            "geo:$latitude,$longitude?q=$encodedQuery"
                        } else {
                            "geo:$latitude,$longitude"
                        }
                    }
                    else -> "geo:0,0?q=$encodedQuery"
                }
            }

            override val actionType = ActionType.OPEN_MAP
            override val intentAction = ACTION_VIEW_MAP
            override val userSummary = if (query.isNotBlank()) {
                "Open map: ${query.trim()}"
            } else {
                "Open map: $latitude, $longitude"
            }

            override fun toDescriptor() = IntentDescriptor(
                action = intentAction,
                dataUri = geoUriString,
                flags = Intent.FLAG_ACTIVITY_NEW_TASK,
            )

            override fun toIntent(): Intent = Intent(intentAction, Uri.parse(geoUriString)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        /**
         * Action: Create a note or share structured text with a note-taking app.
         */
        data class CreateNote(
            val text: String,
            val noteTitle: String = "",
        ) : SystemAction() {
            init {
                require(text.isNotBlank()) { "Note text cannot be blank." }
            }

            override val actionType = ActionType.CREATE_NOTE
            override val intentAction = ACTION_CREATE_NOTE
            override val userSummary = if (noteTitle.isNotBlank()) {
                "Note: ${noteTitle.trim()} - ${text.trim().take(40)}"
            } else {
                "Note: ${text.trim().take(50)}"
            }

            override fun toDescriptor() = IntentDescriptor(
                action = intentAction,
                mimeType = "text/plain",
                extras = buildMap {
                    put(EXTRA_NOTE_TEXT, text.trim())
                    if (noteTitle.isNotBlank()) put(EXTRA_NOTE_SUBJECT, noteTitle.trim())
                },
                flags = Intent.FLAG_ACTIVITY_NEW_TASK,
            )

            override fun toIntent(): Intent = Intent(intentAction).apply {
                type = "text/plain"
                putExtra(EXTRA_NOTE_TEXT, text.trim())
                if (noteTitle.isNotBlank()) putExtra(EXTRA_NOTE_SUBJECT, noteTitle.trim())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    enum class ActionType {
        SET_TIMER,
        SET_ALARM,
        CALENDAR_EVENT,
        OPEN_MAP,
        CREATE_NOTE,
    }

    /**
     * Validates that the given action satisfies all safety requirements:
     * - Requires human confirmation.
     * - Never auto-executes.
     * - For timer and alarm, EXTRA_SKIP_UI is strictly false.
     */
    fun validateSafetyInvariant(action: SystemAction) {
        check(action.requiresUserConfirmation) { "Safety violation: Action must require explicit user confirmation." }
        check(!action.autoExecute) { "Safety violation: Action must not auto-execute." }
        val descriptor = action.toDescriptor()
        if (descriptor.action == ACTION_SET_TIMER || descriptor.action == ACTION_SET_ALARM) {
            val skipUi = descriptor.extras[EXTRA_SKIP_UI] as? Boolean ?: false
            check(!skipUi) { "Safety violation: Clock intents must never set EXTRA_SKIP_UI to true." }
        }
    }

    /**
     * Constructs the UI review model for displaying the actionable button / dialog to the user.
     */
    fun getReviewUi(action: SystemAction): ActionReviewUi {
        validateSafetyInvariant(action)
        return when (action) {
            is SystemAction.SetTimer -> ActionReviewUi(
                action = action,
                buttonLabel = "Review & Set Timer (${formatSeconds(action.seconds)})",
                dialogTitle = "Open Clock to Review Timer?",
                dialogMessage = "PocketAgent prepares this timer in your system Clock app:\n\n" +
                        "• Duration: ${formatSeconds(action.seconds)}\n" +
                        (if (action.message.isNotBlank()) "• Label: ${action.message}\n" else "") +
                        "\nYou must confirm and start it in the Clock app.",
                targetAppCategory = "Clock",
            )
            is SystemAction.SetAlarm -> ActionReviewUi(
                action = action,
                buttonLabel = String.format(Locale.ROOT, "Review & Set Alarm (%02d:%02d)", action.hour, action.minutes),
                dialogTitle = "Open Clock to Review Alarm?",
                dialogMessage = "PocketAgent prepares this alarm in your system Clock app:\n\n" +
                        String.format(Locale.ROOT, "• Time: %02d:%02d\n", action.hour, action.minutes) +
                        (if (action.message.isNotBlank()) "• Label: ${action.message}\n" else "") +
                        "\nYou must confirm and save it in the Clock app.",
                targetAppCategory = "Clock",
            )
            is SystemAction.DraftCalendarEvent -> ActionReviewUi(
                action = action,
                buttonLabel = "Review & Draft Event: ${action.title.take(20)}",
                dialogTitle = "Open Calendar to Review Event?",
                dialogMessage = "PocketAgent prepares this event draft in your system Calendar app:\n\n" +
                        "• Title: ${action.title}\n" +
                        (if (action.location.isNotBlank()) "• Location: ${action.location}\n" else "") +
                        (if (action.description.isNotBlank()) "• Details: ${action.description}\n" else "") +
                        "\nYou can review, edit, and save it in your Calendar.",
                targetAppCategory = "Calendar",
            )
            is SystemAction.OpenMap -> ActionReviewUi(
                action = action,
                buttonLabel = if (action.query.isNotBlank()) "Open Map: ${action.query.take(20)}" else "Open Map Coordinates",
                dialogTitle = "Open Location in Maps?",
                dialogMessage = "PocketAgent prepares this destination in your Maps app:\n\n" +
                        (if (action.query.isNotBlank()) "• Destination: ${action.query}\n" else "") +
                        (if (action.latitude != null && action.longitude != null) "• Coordinates: ${action.latitude}, ${action.longitude}\n" else "") +
                        "\nTap Open to launch your preferred navigation app.",
                targetAppCategory = "Maps",
            )
            is SystemAction.CreateNote -> ActionReviewUi(
                action = action,
                buttonLabel = if (action.noteTitle.isNotBlank()) "Review & Save Note: ${action.noteTitle.take(20)}" else "Review & Save Note",
                dialogTitle = "Send Text to Notes App?",
                dialogMessage = "PocketAgent prepares this note text to share with your chosen notes app:\n\n" +
                        (if (action.noteTitle.isNotBlank()) "• Title: ${action.noteTitle}\n" else "") +
                        "• Content: ${action.text.take(120)}...\n\n" +
                        "Choose an app to save the note.",
                targetAppCategory = "Notes",
            )
        }
    }

    // =========================================================================
    // Parser for Structured Directives and Conversational Assistant Suggestions
    // =========================================================================

    // Structured regexes: e.g. [ACTION:TIMER seconds=300 message="Pasta"]
    private val STRUCTURED_TIMER = Pattern.compile(
        """\[ACTION:TIMER\s+([^\]]+)\]""",
        Pattern.CASE_INSENSITIVE
    )
    private val STRUCTURED_ALARM = Pattern.compile(
        """\[ACTION:ALARM\s+([^\]]+)\]""",
        Pattern.CASE_INSENSITIVE
    )
    private val STRUCTURED_CALENDAR = Pattern.compile(
        """\[ACTION:CALENDAR\s+([^\]]+)\]""",
        Pattern.CASE_INSENSITIVE
    )
    private val STRUCTURED_MAP = Pattern.compile(
        """\[ACTION:MAP\s+([^\]]+)\]""",
        Pattern.CASE_INSENSITIVE
    )
    private val STRUCTURED_NOTE = Pattern.compile(
        """\[ACTION:NOTE\s+([^\]]+)\]""",
        Pattern.CASE_INSENSITIVE
    )

    // Conversational / suggestion regexes
    private val CONV_TIMER = Pattern.compile(
        """(?i)(?:set\s+(?:a\s+)?)?timer\s+(?:for\s+)?(\d+)\s*(seconds?|sec|s|minutes?|min|m|hours?|hr|h)(?:(?:\s+(?:for|called|named|label)\s+)|:?\s+)?(?:["']([^"'\n]+)["']|([^.\n,]+))?"""
    )
    private val CONV_ALARM = Pattern.compile(
        """(?i)(?:set\s+(?:an?\s+)?)?alarm\s+(?:for|at)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?(?:(?:\s+(?:for|called|named|label)\s+)|:?\s+)?(?:["']([^"'\n]+)["']|([^.\n,]+))?"""
    )
    private val CONV_CALENDAR = Pattern.compile(
        """(?i)(?:add|draft|schedule|create)\s+(?:a\s+)?(?:calendar\s+)?event[:\s]+(?:["']([^"'\n]+)["']|(.+?))(?:\s+(?:at|in)\s+["']?([^.\n,]+?)["']?)?(?:\s+(?:on|for)\s+([^\n.]+))?(?:\.|\z)"""
    )
    private val CONV_MAP = Pattern.compile(
        """(?i)(?:open\s+(?:in\s+)?(?:maps?|navigation)|navigate\s+to|directions\s+to)[:\s]+(?:["']([^"'\n]+)["']|([^.\n]+))"""
    )
    private val CONV_GEO = Pattern.compile(
        """(?i)geo:(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)(?:\?q=([^.\n]+))?"""
    )
    private val CONV_NOTE = Pattern.compile(
        """(?i)(?:create|take|save|draft)\s+(?:a\s+)?note[:\s]+(?:["']([^"'\n]+)["']|([^.\n]+))"""
    )

    /**
     * Parses all actionable system intents found within the assistant suggestion text.
     */
    fun parseActions(text: String): List<SystemAction> {
        if (text.isBlank()) return emptyList()

        val results = mutableListOf<SystemAction>()

        // 1. Structured directive tags take precedence
        parseStructuredDirectives(text, results)

        // 2. If no structured directives found, parse conversational patterns
        if (results.isEmpty()) {
            parseConversationalPatterns(text, results)
        }

        // Validate safety on all parsed actions
        return results.filter { action ->
            try {
                validateSafetyInvariant(action)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Parses only the first valid actionable system intent found, or null.
     */
    fun parseFirstAction(text: String): SystemAction? = parseActions(text).firstOrNull()

    private fun parseStructuredDirectives(text: String, out: MutableList<SystemAction>) {
        // [ACTION:TIMER ...]
        val timerMatcher = STRUCTURED_TIMER.matcher(text)
        while (timerMatcher.find()) {
            val params = parseKeyValueParams(timerMatcher.group(1))
            val seconds = params["seconds"]?.toIntOrNull()
                ?: parseDurationString(params["duration"])
            if (seconds != null && seconds > 0) {
                val message = params["message"] ?: params["label"] ?: ""
                try {
                    out.add(SystemAction.SetTimer(seconds, message))
                } catch (_: IllegalArgumentException) {}
            }
        }

        // [ACTION:ALARM ...]
        val alarmMatcher = STRUCTURED_ALARM.matcher(text)
        while (alarmMatcher.find()) {
            val params = parseKeyValueParams(alarmMatcher.group(1))
            var hour = params["hour"]?.toIntOrNull()
            var minutes = params["minutes"]?.toIntOrNull() ?: 0
            val timeStr = params["time"]
            if (timeStr != null && hour == null) {
                val parts = timeStr.split(":")
                hour = parts.getOrNull(0)?.toIntOrNull()
                minutes = parts.getOrNull(1)?.toIntOrNull() ?: 0
            }
            if (hour != null && hour in 0..23 && minutes in 0..59) {
                val message = params["message"] ?: params["label"] ?: ""
                try {
                    out.add(SystemAction.SetAlarm(hour, minutes, message))
                } catch (_: IllegalArgumentException) {}
            }
        }

        // [ACTION:CALENDAR ...]
        val calMatcher = STRUCTURED_CALENDAR.matcher(text)
        while (calMatcher.find()) {
            val params = parseKeyValueParams(calMatcher.group(1))
            val title = params["title"] ?: params["name"]
            if (!title.isNullOrBlank()) {
                val desc = params["description"] ?: params["desc"] ?: ""
                val loc = params["location"] ?: params["loc"] ?: ""
                val begin = params["begin"]?.toLongOrNull() ?: params["start"]?.toLongOrNull()
                val end = params["end"]?.toLongOrNull()
                val allDay = params["allday"]?.toBoolean() ?: false
                try {
                    out.add(SystemAction.DraftCalendarEvent(title, desc, loc, begin, end, allDay))
                } catch (_: IllegalArgumentException) {}
            }
        }

        // [ACTION:MAP ...]
        val mapMatcher = STRUCTURED_MAP.matcher(text)
        while (mapMatcher.find()) {
            val params = parseKeyValueParams(mapMatcher.group(1))
            val q = params["query"] ?: params["q"] ?: ""
            val lat = params["lat"]?.toDoubleOrNull() ?: params["latitude"]?.toDoubleOrNull()
            val lng = params["lng"]?.toDoubleOrNull() ?: params["longitude"]?.toDoubleOrNull()
            if (q.isNotBlank() || (lat != null && lng != null)) {
                try {
                    out.add(SystemAction.OpenMap(q, lat, lng))
                } catch (_: IllegalArgumentException) {}
            }
        }

        // [ACTION:NOTE ...]
        val noteMatcher = STRUCTURED_NOTE.matcher(text)
        while (noteMatcher.find()) {
            val params = parseKeyValueParams(noteMatcher.group(1))
            val content = params["text"] ?: params["content"] ?: ""
            if (content.isNotBlank()) {
                val title = params["title"] ?: params["subject"] ?: ""
                try {
                    out.add(SystemAction.CreateNote(content, title))
                } catch (_: IllegalArgumentException) {}
            }
        }
    }

    private fun parseConversationalPatterns(text: String, out: MutableList<SystemAction>) {
        // Geo coordinates URI
        val geoMatcher = CONV_GEO.matcher(text)
        if (geoMatcher.find()) {
            val lat = geoMatcher.group(1)?.toDoubleOrNull()
            val lng = geoMatcher.group(2)?.toDoubleOrNull()
            val query = geoMatcher.group(3) ?: ""
            if (lat != null && lng != null) {
                try {
                    out.add(SystemAction.OpenMap(query = query, latitude = lat, longitude = lng))
                    return
                } catch (_: IllegalArgumentException) {}
            }
        }

        // Timer
        val timerMatcher = CONV_TIMER.matcher(text)
        if (timerMatcher.find()) {
            val amount = timerMatcher.group(1)?.toIntOrNull()
            val unit = timerMatcher.group(2)?.lowercase(Locale.ROOT) ?: "m"
            val label = (timerMatcher.group(3) ?: timerMatcher.group(4) ?: "").trim()
            if (amount != null && amount > 0) {
                val seconds = when {
                    unit.startsWith("s") -> amount
                    unit.startsWith("h") -> amount * 3600
                    else -> amount * 60
                }
                try {
                    out.add(SystemAction.SetTimer(seconds, label))
                    return
                } catch (_: IllegalArgumentException) {}
            }
        }

        // Alarm
        val alarmMatcher = CONV_ALARM.matcher(text)
        if (alarmMatcher.find()) {
            var hour = alarmMatcher.group(1)?.toIntOrNull()
            val minutes = alarmMatcher.group(2)?.toIntOrNull() ?: 0
            val amPm = alarmMatcher.group(3)?.lowercase(Locale.ROOT)
            val label = (alarmMatcher.group(4) ?: alarmMatcher.group(5) ?: "").trim()
            if (hour != null) {
                if (amPm == "pm" && hour < 12) hour += 12
                if (amPm == "am" && hour == 12) hour = 0
                if (hour in 0..23 && minutes in 0..59) {
                    try {
                        out.add(SystemAction.SetAlarm(hour, minutes, label))
                        return
                    } catch (_: IllegalArgumentException) {}
                }
            }
        }

        // Map navigation
        val mapMatcher = CONV_MAP.matcher(text)
        if (mapMatcher.find()) {
            val query = (mapMatcher.group(1) ?: mapMatcher.group(2) ?: "").trim()
            val isWebUrl = query.contains(Regex("""\.(com|org|net|edu|gov|io|app|html?)\b""", RegexOption.IGNORE_CASE)) ||
                query.startsWith("http://", ignoreCase = true) ||
                query.startsWith("https://", ignoreCase = true) ||
                query.startsWith("www.", ignoreCase = true)
            if (query.isNotBlank() && !isWebUrl) {
                try {
                    out.add(SystemAction.OpenMap(query = query))
                    return
                } catch (_: IllegalArgumentException) {}
            }
        }

        // Calendar event
        val calMatcher = CONV_CALENDAR.matcher(text)
        if (calMatcher.find()) {
            val title = (calMatcher.group(1) ?: calMatcher.group(2) ?: "").trim()
            val location = calMatcher.group(3)?.trim() ?: ""
            if (title.isNotBlank()) {
                try {
                    out.add(SystemAction.DraftCalendarEvent(title = title, location = location))
                    return
                } catch (_: IllegalArgumentException) {}
            }
        }

        // Note
        val noteMatcher = CONV_NOTE.matcher(text)
        if (noteMatcher.find()) {
            val content = (noteMatcher.group(1) ?: noteMatcher.group(2) ?: "").trim()
            if (content.isNotBlank()) {
                try {
                    out.add(SystemAction.CreateNote(text = content))
                    return
                } catch (_: IllegalArgumentException) {}
            }
        }
    }

    private fun parseKeyValueParams(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        val pattern = Pattern.compile("""(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)'|(\S+))""")
        val matcher = pattern.matcher(raw)
        while (matcher.find()) {
            val key = matcher.group(1)?.lowercase(Locale.ROOT) ?: continue
            val value = matcher.group(2) ?: matcher.group(3) ?: matcher.group(4) ?: ""
            result[key] = value
        }
        return result
    }

    private fun parseDurationString(str: String?): Int? {
        if (str.isNullOrBlank()) return null
        val trimmed = str.trim().lowercase(Locale.ROOT)
        val num = trimmed.filter { it.isDigit() }.toIntOrNull() ?: return null
        return when {
            trimmed.endsWith("s") || trimmed.endsWith("sec") -> num
            trimmed.endsWith("h") || trimmed.endsWith("hr") -> num * 3600
            else -> num * 60
        }
    }

    private fun formatSeconds(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            m > 0 && s > 0 -> "${m}m ${s}s"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }
}
