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
        launchActivity().use {
            waitForText("Record audio")
            onView(withText("Record audio")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun artifactSaveWithNullLanguage() {
        assertEquals("html", normalizeArtifactLanguage(null))
        launchActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.showSaveArtifactDialog(
                    code = "<p>offline draft</p>",
                    language = null,
                )
            }
            waitForText("Save to Artifact Vault")
            onView(withText("Save to Artifact Vault")).check(matches(isDisplayed()))
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
        launchActivity().use {
            openDrawer()
            waitForText("Device smoke session")
            onView(withText("Device smoke session")).perform(click())
            waitForText("WORKSPACE")
        }
    }

    @Test
    fun memoryAdd() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UserMemoryStore(UserMemoryStore.defaultDir(context.filesDir))
            .addFact("Prefers concise offline answers")
        launchActivity().use {
            openDrawer()
            clickText("Notes & Memory")
            waitForText("Prefers concise offline answers")
            onView(withText("Prefers concise offline answers")).check(matches(isDisplayed()))
        }
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
