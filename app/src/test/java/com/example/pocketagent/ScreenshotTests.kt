package com.example.pocketagent

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Deterministic 375dp screen fixtures built from the production [UiPalette] and [UiComponents].
 *
 * These are deliberately component-level captures: no model, network, or native runtime is needed,
 * so the same pixels are produced on every Linux CI runner. The matching device-level smoke tests
 * live in `androidTest/SmokeTests.kt`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w375dp-normal")
class ScreenshotTests {
    @Test
    fun chatLight375() {
        render(Screen.CHAT, dark = false).captureRoboImage("chat_light_375.png")
    }

    @Test
    @Config(qualifiers = "w375dp-normal-night")
    fun chatDark375() {
        render(Screen.CHAT, dark = true).captureRoboImage("chat_dark_375.png")
    }

    @Test
    fun vaultLight375() {
        render(Screen.VAULT, dark = false).captureRoboImage("vault_light_375.png")
    }

    @Test
    @Config(qualifiers = "w375dp-normal-night")
    fun vaultDark375() {
        render(Screen.VAULT, dark = true).captureRoboImage("vault_dark_375.png")
    }

    @Test
    fun settingsLight375() {
        render(Screen.SETTINGS, dark = false).captureRoboImage("settings_light_375.png")
    }

    @Test
    @Config(qualifiers = "w375dp-normal-night")
    fun settingsDark375() {
        render(Screen.SETTINGS, dark = true).captureRoboImage("settings_dark_375.png")
    }

    private enum class Screen { CHAT, VAULT, SETTINGS }

    private fun render(screen: Screen, dark: Boolean): View {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val palette = UiThemeResolver.paletteFor(
            if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
            systemNightActive = dark,
        )
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bg)
            setPadding(
                dp(context, 16),
                dp(context, 16),
                dp(context, 16),
                dp(context, 16),
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        when (screen) {
            Screen.CHAT -> buildChat(activity, palette, root)
            Screen.VAULT -> buildVault(activity, palette, root)
            Screen.SETTINGS -> buildSettings(activity, palette, root)
        }

        activity.setContentView(root)
        val width = activity.resources.displayMetrics.widthPixels
        val height = activity.resources.displayMetrics.heightPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        return root
    }

    private fun buildChat(activity: Activity, palette: UiPalette, root: LinearLayout) {
        root.addView(styledText(activity, "PocketAgent", 20f, palette.ink, bold = true))
        root.addView(styledText(activity, "100% Offline · On-device", 12f, palette.muted))

        val transcript = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val messages = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        messages.addView(
            messageCard(
                activity,
                palette,
                label = "YOU",
                body = "Summarize the notes I saved this morning.",
                background = palette.userBubbleBg,
                ink = palette.ink,
            ),
        )
        messages.addView(
            messageCard(
                activity,
                palette,
                label = "POCKETAGENT",
                body = "Three notes were saved locally. No cloud sync was used.",
                background = palette.assistantBubbleBg,
                ink = palette.ink,
            ),
        )
        transcript.addView(messages)
        root.addView(transcript)

        val composer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 12), 0, 0)
        }
        val input = EditText(activity).apply { hint = "Ask locally…" }
        styleEditText(input, palette)
        composer.addView(
            input,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        composer.addView(
            pillButton(activity, palette, "Ask", onClick = {}),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(activity, UiTokens.TOUCH_TARGET_DP),
            ),
        )
        root.addView(composer)
    }

    private fun buildVault(activity: Activity, palette: UiPalette, root: LinearLayout) {
        root.addView(styledText(activity, "Vault", 20f, palette.ink, bold = true))
        root.addView(styledText(activity, "Everything stays on this device", 12f, palette.muted))
        listOf("meeting-notes.txt" to "12 KB", "design.pdf" to "84 KB", "answer-artifact.html" to "4 KB")
            .forEach { (name, size) ->
                val card = cardContainer(activity, palette)
                card.addView(styledText(activity, name, 15f, palette.ink, bold = true))
                card.addView(styledText(activity, "Local · $size", 12f, palette.muted))
                root.addView(
                    card,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(activity, UiTokens.SPACING_8_DP) },
                )
            }
    }

    private fun buildSettings(activity: Activity, palette: UiPalette, root: LinearLayout) {
        root.addView(styledText(activity, "Privacy & Theme", 20f, palette.ink, bold = true))
        root.addView(dividerView(activity, palette))
        listOf(
            "Theme mode" to "System",
            "Model storage" to "On-device only",
            "Telemetry" to "Disabled",
            "App lock" to "Off",
        ).forEach { (label, value) ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(activity, UiTokens.SPACING_12_DP), 0, dp(activity, UiTokens.SPACING_12_DP))
            }
            row.addView(
                styledText(activity, label, 14f, palette.ink),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(styledText(activity, value, 13f, palette.teal))
            root.addView(row)
            root.addView(dividerView(activity, palette))
        }
        root.addView(
            secondaryOutlineButton(activity, palette, "Export local data", onClick = {}),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(activity, UiTokens.TOUCH_TARGET_DP),
            ).apply { topMargin = dp(activity, UiTokens.SPACING_20_DP) },
        )
    }

    private fun messageCard(
        activity: Activity,
        palette: UiPalette,
        label: String,
        body: String,
        background: Int,
        ink: Int,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(background)
        setPadding(
            dp(activity, UiTokens.SPACING_14_DP),
            dp(activity, UiTokens.SPACING_12_DP),
            dp(activity, UiTokens.SPACING_14_DP),
            dp(activity, UiTokens.SPACING_12_DP),
        )
        addView(styledText(activity, label, 10f, palette.muted, bold = true))
        addView(styledText(activity, body, 14f, ink))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(activity, UiTokens.SPACING_8_DP) }
    }
}
