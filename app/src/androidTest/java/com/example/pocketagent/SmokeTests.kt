package com.example.pocketagent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAssertion
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device-level smoke coverage for the offline-first flows that do not need a model fixture. */
@RunWith(AndroidJUnit4::class)
class SmokeTests {
    @get:Rule
    val recordAudioPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    @Ignore("No valid .litertlm fixture is checked in; recorded as Blocked in docs/EVIDENCE.md.")
    fun modelImport() {
        // A valid tiny LiteRT-LM model cannot be generated from checked-in text fixtures.
    }

    @Test
    @Ignore("Chat inference needs the blocked model fixture; recorded as Blocked in docs/EVIDENCE.md.")
    fun chatRoundTrip() {
        // Requires a valid .litertlm fixture and a native inference round trip.
    }

    @Test
    fun biometricLockGate() {
        launchActivity().use {
            openDrawer()
            clickText("Privacy & Theme")
            waitForText("Biometric App Lock")
            onView(withText("Biometric App Lock")).check(matches(isDisplayed()))
            onView(withText("Disabled")).check(matches(isDisplayed()))
        }
    }

    @Test
    @Ignore("Citations require the blocked chat model fixture; recorded as Blocked in docs/EVIDENCE.md.")
    fun pdfCitation() {
        // The synthetic PDF fixture is generated in CI, but citation needs model inference.
    }

    @Test
    fun voicePermissionFlow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO),
        )
        launchActivity().use {
            // The audio actions sit in the collapsed tools tray and stay disabled until a
            // Whisper model is loaded; the smoke check verifies the offline entry point exists.
            onView(withContentDescription("Show attachment and audio tools")).perform(click())
            waitForText("Record audio")
            onView(withText("Record audio")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun artifactSaveWithNullLanguage() {
        assertEquals("html", normalizeArtifactLanguage(null))
        launchActivity().use { scenario ->
            // Let the main window settle before adding a dialog root; on API 35 an immediate
            // dialog can be shown while the activity is being torn down and leak its window.
            waitForText("Ask")
            scenario.onActivity { activity ->
                activity.showSaveArtifactDialog(
                    code = "<p>offline draft</p>",
                    language = normalizeArtifactLanguage(null),
                )
            }
            waitForText("Save to Artifact Vault", timeoutMs = 10_000)
            onView(withText("Save to Artifact Vault")).check(matches(isDisplayed()))
            onView(withText("Cancel")).perform(click())
        }
    }

    @Test
    fun sessionSwitch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SessionStore(SessionStore.defaultDir(context.filesDir))
        store.saveSession(
            ConversationSession.createWithInitialPrompt(
                prompt = "Device smoke session",
                id = "device_smoke_session",
            ),
        )
        launchActivity().use { scenario ->
            openDrawer()
            waitForText("Device smoke session")
            onView(withText("Device smoke session")).perform(click())
            // Selecting a session closes the drawer, so assert the loaded session directly.
            scenario.onActivity { assertEquals("device_smoke_session", it.currentSession?.id) }
        }
    }

    @Test
    fun memoryFactPersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = UserMemoryStore(UserMemoryStore.defaultDir(context.filesDir))
        store.addFact("Prefers concise offline answers")
        assertTrue(
            "Memory fact was not persisted on device",
            store.listMemories().any { it.content == "Prefers concise offline answers" },
        )
        assertTrue(
            "Memory fact was not injected into the offline prompt context",
            store.buildPromptContext(120).contains("Prefers concise offline answers"),
        )
    }

    @Test
    fun notePin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PersonalNotesStore(PersonalNotesStore.defaultDir(context.filesDir))
            .createNote(title = "Smoke note", content = "Pinned locally", isPinned = true)
        launchActivity().use {
            openDrawer()
            clickText("Notes & Memory")
            waitForText("[Pinned] Smoke note")
            onView(withText("[Pinned] Smoke note")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun offlineAssertion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
        assertFalse("INTERNET must not be requested", permissions.contains(Manifest.permission.INTERNET))
    }

    @Test
    fun darkMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(MainActivity.PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putString(UiThemeResolver.PREF_THEME_MODE, ThemeMode.DARK.name)
            .apply()
        launchActivity().use { scenario ->
            val expected = UiThemeResolver.paletteFor(ThemeMode.DARK, systemNightActive = true).bg
            scenario.onActivity { activity ->
                val content = activity.findViewById<View>(android.R.id.content)
                assertTrue(
                    "Dark palette background was not rendered",
                    hasBackgroundColor(content, expected),
                )
            }
        }
    }

    @Test
    fun layoutTouchTargets() {
        launchActivity().use {
            waitForText("Ask")
            onView(withText("Ask")).check(hasMinimumHeight(UiTokens.TOUCH_TARGET_DP))
        }
    }

    private fun launchActivity(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java)

    private fun openDrawer() {
        waitForContentDescription("Open sidebar: chats, projects, vaults, and settings")
        onView(withContentDescription("Open sidebar: chats, projects, vaults, and settings")).perform(click())
        waitForText("WORKSPACE")
    }

    private fun clickText(text: String) {
        waitForText(text)
        onView(withText(text)).perform(click())
    }

    private fun waitForText(text: String, timeoutMs: Long = 3_000) {
        waitFor(timeoutMs) { onView(withText(text)).check(matches(isDisplayed())) }
    }

    private fun waitForContentDescription(description: String, timeoutMs: Long = 3_000) {
        waitFor(timeoutMs) {
            onView(withContentDescription(description)).check(matches(isDisplayed()))
        }
    }

    private fun waitFor(timeoutMs: Long, check: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                check()
                return
            } catch (error: Throwable) {
                lastError = error
                SystemClock.sleep(100)
            }
        }
        throw AssertionError("Condition was not met within ${timeoutMs}ms", lastError)
    }

    private fun hasBackgroundColor(view: View, color: Int): Boolean {
        if ((view.background as? ColorDrawable)?.color == color) return true
        return view is android.view.ViewGroup &&
            (0 until view.childCount).any { index -> hasBackgroundColor(view.getChildAt(index), color) }
    }

    private fun hasMinimumHeight(heightDp: Int): ViewAssertion = ViewAssertion { view, _ ->
        val context = view.context
        val expectedPx = heightDp * context.resources.displayMetrics.density
        assertTrue(
            "View height ${view.height}px is below ${heightDp}dp (${expectedPx}px)",
            view.height + 1 >= expectedPx,
        )
    }
}
