package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemActionToolsTest {

    // =========================================================================
    // 1. Intent Generation and Descriptor Verification
    // =========================================================================

    @Test
    fun timerActionBuildsValidDescriptorWithForcedUiReview() {
        val timer = SystemActionTools.SystemAction.SetTimer(
            seconds = 300,
            message = "Boil eggs",
        )

        val descriptor = timer.toDescriptor()
        assertEquals(SystemActionTools.ACTION_SET_TIMER, descriptor.action)
        assertEquals(300, descriptor.extras[SystemActionTools.EXTRA_TIMER_LENGTH])
        assertEquals("Boil eggs", descriptor.extras[SystemActionTools.EXTRA_MESSAGE])
        // CRITICAL: EXTRA_SKIP_UI must be false so user reviews in Clock app
        assertEquals(false, descriptor.extras[SystemActionTools.EXTRA_SKIP_UI])
        assertTrue(timer.requiresUserConfirmation)
        assertFalse(timer.autoExecute)
        assertEquals("Timer for Boil eggs (5m)", timer.userSummary)
    }

    @Test
    fun alarmActionBuildsValidDescriptor() {
        val alarm = SystemActionTools.SystemAction.SetAlarm(
            hour = 7,
            minutes = 30,
            message = "Morning run",
        )

        val descriptor = alarm.toDescriptor()
        assertEquals(SystemActionTools.ACTION_SET_ALARM, descriptor.action)
        assertEquals(7, descriptor.extras[SystemActionTools.EXTRA_ALARM_HOUR])
        assertEquals(30, descriptor.extras[SystemActionTools.EXTRA_ALARM_MINUTES])
        assertEquals("Morning run", descriptor.extras[SystemActionTools.EXTRA_MESSAGE])
        assertEquals(false, descriptor.extras[SystemActionTools.EXTRA_SKIP_UI])
        assertTrue(alarm.requiresUserConfirmation)
        assertFalse(alarm.autoExecute)
    }

    @Test
    fun calendarActionBuildsValidDescriptor() {
        val event = SystemActionTools.SystemAction.DraftCalendarEvent(
            title = "Project Sync",
            description = "Quarterly roadmap review",
            location = "Conference Room B",
            beginTimeMillis = 1700000000000L,
            endTimeMillis = 1700003600000L,
            allDay = false,
        )

        val descriptor = event.toDescriptor()
        assertEquals(SystemActionTools.ACTION_INSERT_CALENDAR, descriptor.action)
        assertEquals(SystemActionTools.CALENDAR_EVENTS_URI, descriptor.dataUri)
        assertEquals("Project Sync", descriptor.extras[SystemActionTools.EXTRA_CALENDAR_TITLE])
        assertEquals("Quarterly roadmap review", descriptor.extras[SystemActionTools.EXTRA_CALENDAR_DESCRIPTION])
        assertEquals("Conference Room B", descriptor.extras[SystemActionTools.EXTRA_CALENDAR_LOCATION])
        assertEquals(1700000000000L, descriptor.extras[SystemActionTools.EXTRA_CALENDAR_BEGIN_TIME])
        assertEquals(1700003600000L, descriptor.extras[SystemActionTools.EXTRA_CALENDAR_END_TIME])
    }

    @Test
    fun mapActionBuildsValidGeoUriFromQuery() {
        val map = SystemActionTools.SystemAction.OpenMap(
            query = "1600 Amphitheatre Pkwy, Mountain View",
        )

        val descriptor = map.toDescriptor()
        assertEquals(SystemActionTools.ACTION_VIEW_MAP, descriptor.action)
        assertEquals("geo:0,0?q=1600%20Amphitheatre%20Pkwy%2C%20Mountain%20View", descriptor.dataUri)
    }

    @Test
    fun mapActionBuildsValidGeoUriFromCoordinates() {
        val map = SystemActionTools.SystemAction.OpenMap(
            latitude = 37.422,
            longitude = -122.084,
            query = "Googleplex",
        )

        val descriptor = map.toDescriptor()
        assertEquals(SystemActionTools.ACTION_VIEW_MAP, descriptor.action)
        assertEquals("geo:37.422,-122.084?q=Googleplex", descriptor.dataUri)
    }

    @Test
    fun noteActionBuildsValidDescriptor() {
        val note = SystemActionTools.SystemAction.CreateNote(
            text = "Buy almond milk and coffee beans",
            noteTitle = "Groceries",
        )

        val descriptor = note.toDescriptor()
        assertEquals(SystemActionTools.ACTION_CREATE_NOTE, descriptor.action)
        assertEquals("text/plain", descriptor.mimeType)
        assertEquals("Buy almond milk and coffee beans", descriptor.extras[SystemActionTools.EXTRA_NOTE_TEXT])
        assertEquals("Groceries", descriptor.extras[SystemActionTools.EXTRA_NOTE_SUBJECT])
    }

    // =========================================================================
    // 2. Safety Validation Invariants
    // =========================================================================

    @Test
    fun rejectsNegativeOrZeroTimer() {
        assertThrows(IllegalArgumentException::class.java) {
            SystemActionTools.SystemAction.SetTimer(seconds = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SystemActionTools.SystemAction.SetTimer(seconds = -5)
        }
    }

    @Test
    fun rejectsExcessiveTimerOverSevenDays() {
        assertThrows(IllegalArgumentException::class.java) {
            SystemActionTools.SystemAction.SetTimer(seconds = 86400 * 8)
        }
    }

    @Test
    fun rejectsInvalidAlarmHoursAndMinutes() {
        assertThrows(IllegalArgumentException::class.java) {
            SystemActionTools.SystemAction.SetAlarm(hour = 24, minutes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SystemActionTools.SystemAction.SetAlarm(hour = -1, minutes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SystemActionTools.SystemAction.SetAlarm(hour = 12, minutes = 60)
        }
    }

    @Test
    fun rejectsBlankCalendarTitleAndInvertedTimes() {
        assertThrows(IllegalArgumentException::class.java) {
            SystemActionTools.SystemAction.DraftCalendarEvent(title = "   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SystemActionTools.SystemAction.DraftCalendarEvent(
                title = "Meeting",
                beginTimeMillis = 1000L,
                endTimeMillis = 500L,
            )
        }
    }

    @Test
    fun rejectsInvalidMapCoordinatesAndEmptyTarget() {
        assertThrows(IllegalArgumentException::class.java) {
            SystemActionTools.SystemAction.OpenMap(query = "", latitude = null, longitude = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SystemActionTools.SystemAction.OpenMap(latitude = 95.0, longitude = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SystemActionTools.SystemAction.OpenMap(latitude = 0.0, longitude = 190.0)
        }
    }

    @Test
    fun rejectsBlankNoteText() {
        assertThrows(IllegalArgumentException::class.java) {
            SystemActionTools.SystemAction.CreateNote(text = "")
        }
    }

    @Test
    fun validateSafetyInvariantPassesForValidActions() {
        val timer = SystemActionTools.SystemAction.SetTimer(60)
        SystemActionTools.validateSafetyInvariant(timer)

        val alarm = SystemActionTools.SystemAction.SetAlarm(8, 0)
        SystemActionTools.validateSafetyInvariant(alarm)
    }

    // =========================================================================
    // 3. Structured Directive Parsing
    // =========================================================================

    @Test
    fun parsesStructuredTimerDirective() {
        val text = """
            Here are the steps to cook pasta:
            1. Boil water in a large pot.
            2. Add pasta and cook for 10 minutes.
            [ACTION:TIMER seconds=600 message="Pasta Timer"]
            3. Drain and serve with sauce.
        """.trimIndent()

        val actions = SystemActionTools.parseActions(text)
        assertEquals(1, actions.size)
        val timer = actions[0] as SystemActionTools.SystemAction.SetTimer
        assertEquals(600, timer.seconds)
        assertEquals("Pasta Timer", timer.message)
    }

    @Test
    fun parsesStructuredAlarmDirective() {
        val text = """
            Make sure you wake up early tomorrow.
            [ACTION:ALARM hour=6 minutes=45 message="Morning Standup"]
        """.trimIndent()

        val action = SystemActionTools.parseFirstAction(text)
        assertNotNull(action)
        assertTrue(action is SystemActionTools.SystemAction.SetAlarm)
        val alarm = action as SystemActionTools.SystemAction.SetAlarm
        assertEquals(6, alarm.hour)
        assertEquals(45, alarm.minutes)
        assertEquals("Morning Standup", alarm.message)
    }

    @Test
    fun parsesStructuredCalendarDirective() {
        val text = """
            I've prepared the event details:
            [ACTION:CALENDAR title="Team Offsite" location="HQ Room 4" description="Planning session"]
        """.trimIndent()

        val action = SystemActionTools.parseFirstAction(text) as SystemActionTools.SystemAction.DraftCalendarEvent
        assertEquals("Team Offsite", action.title)
        assertEquals("HQ Room 4", action.location)
        assertEquals("Planning session", action.description)
    }

    @Test
    fun parsesStructuredMapDirective() {
        val text = """
            You can visit the museum here:
            [ACTION:MAP query="Louvre Museum, Paris"]
        """.trimIndent()

        val action = SystemActionTools.parseFirstAction(text) as SystemActionTools.SystemAction.OpenMap
        assertEquals("Louvre Museum, Paris", action.query)
        assertEquals("geo:0,0?q=Louvre%20Museum%2C%20Paris", action.geoUriString)
    }

    @Test
    fun parsesStructuredNoteDirective() {
        val text = """
            Here is your checklist:
            [ACTION:NOTE title="Packing List" text="Passport, charger, sunscreen"]
        """.trimIndent()

        val action = SystemActionTools.parseFirstAction(text) as SystemActionTools.SystemAction.CreateNote
        assertEquals("Packing List", action.noteTitle)
        assertEquals("Passport, charger, sunscreen", action.text)
    }

    // =========================================================================
    // 4. Conversational / Natural Language Parsing
    // =========================================================================

    @Test
    fun parsesConversationalTimerSuggestion() {
        val text = "Step 2: Set a timer for 15 minutes called Pizza Oven."
        val action = SystemActionTools.parseFirstAction(text) as SystemActionTools.SystemAction.SetTimer
        assertEquals(900, action.seconds)
        assertEquals("Pizza Oven", action.message)
    }

    @Test
    fun parsesConversationalAlarmSuggestion() {
        val text = "Remember to set an alarm for 7:30 AM named Flight Check."
        val action = SystemActionTools.parseFirstAction(text) as SystemActionTools.SystemAction.SetAlarm
        assertEquals(7, action.hour)
        assertEquals(30, action.minutes)
        assertEquals("Flight Check", action.message)
    }

    @Test
    fun parsesConversationalCalendarSuggestion() {
        val text = "You should add calendar event: Doctor appointment at Clinic Center."
        val action = SystemActionTools.parseFirstAction(text) as SystemActionTools.SystemAction.DraftCalendarEvent
        assertEquals("Doctor appointment", action.title)
        assertEquals("Clinic Center", action.location)
    }

    @Test
    fun parsesConversationalMapSuggestion() {
        val text = "Please navigate to Golden Gate Bridge."
        val action = SystemActionTools.parseFirstAction(text) as SystemActionTools.SystemAction.OpenMap
        assertEquals("Golden Gate Bridge", action.query)
    }

    @Test
    fun parsesConversationalGeoUriSuggestion() {
        val text = "Location coordinate: geo:37.7749,-122.4194."
        val action = SystemActionTools.parseFirstAction(text) as SystemActionTools.SystemAction.OpenMap
        assertEquals(37.7749, action.latitude!!, 0.0001)
        assertEquals(-122.4194, action.longitude!!, 0.0001)
    }

    @Test
    fun parsesConversationalNoteSuggestion() {
        val text = "Create a note: Submit tax forms before Friday."
        val action = SystemActionTools.parseFirstAction(text) as SystemActionTools.SystemAction.CreateNote
        assertEquals("Submit tax forms before Friday", action.text)
    }

    @Test
    fun returnsEmptyListForTextWithNoActionableIntents() {
        val text = "1. Open your browser. 2. Navigate to google.com. 3. Sign in to your account."
        val actions = SystemActionTools.parseActions(text)
        assertTrue(actions.isEmpty())
        assertNull(SystemActionTools.parseFirstAction(text))
    }

    @Test
    fun returnsEmptyListForBlankText() {
        assertTrue(SystemActionTools.parseActions("").isEmpty())
        assertTrue(SystemActionTools.parseActions("   \n\t  ").isEmpty())
    }

    // =========================================================================
    // 5. UI Review Model Verification
    // =========================================================================

    @Test
    fun reviewUiPresentsClearActionLabelsAndPrompts() {
        val timer = SystemActionTools.SystemAction.SetTimer(seconds = 180, message = "Tea")
        val ui = SystemActionTools.getReviewUi(timer)

        assertEquals("Review & Set Timer (3m)", ui.buttonLabel)
        assertEquals("Open Clock to Review Timer?", ui.dialogTitle)
        assertTrue(ui.dialogMessage.contains("Duration: 3m"))
        assertTrue(ui.dialogMessage.contains("Tea"))
        assertEquals("Clock", ui.targetAppCategory)
    }

    @Test
    fun emailSmsAndDialerActionsSatisfySafetyAndParseCleanly() {
        val emailAction = SystemActionTools.parseFirstAction(
            """I drafted that email: [ACTION:EMAIL to="team@example.com" subject="Weekly Update" body="All tests pass."]"""
        ) as SystemActionTools.SystemAction.EmailDraft
        assertEquals("team@example.com", emailAction.to)
        assertEquals("Weekly Update", emailAction.subject)
        assertTrue(emailAction.toDescriptor().dataUri!!.startsWith("mailto:"))
        assertEquals("Email", SystemActionTools.getReviewUi(emailAction).targetAppCategory)

        val smsAction = SystemActionTools.parseFirstAction(
            """[ACTION:SMS to="+15551234567" body="Running 5 mins late"]"""
        ) as SystemActionTools.SystemAction.SmsDraft
        assertEquals("+15551234567", smsAction.to)
        assertEquals("Running 5 mins late", smsAction.toDescriptor().extras["sms_body"])
        assertEquals("Messages", SystemActionTools.getReviewUi(smsAction).targetAppCategory)

        val dialAction = SystemActionTools.parseFirstAction(
            """You can call the support line: dial +1 555-987-6543."""
        ) as SystemActionTools.SystemAction.DialerPreview
        assertTrue(dialAction.requiresUserConfirmation)
        assertFalse(dialAction.autoExecute)
        assertTrue(dialAction.toDescriptor().dataUri!!.startsWith("tel:"))
        assertEquals("Phone Dialer", SystemActionTools.getReviewUi(dialAction).targetAppCategory)
    }
}
