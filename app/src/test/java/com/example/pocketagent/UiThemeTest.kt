package com.example.pocketagent

import android.content.res.Configuration
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiThemeTest {

    @Test
    fun themeModeFromStringHandlesNullBlankMixedCaseAndInvalid() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromString(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromString(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromString("   "))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromString("unknown"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromString("system"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromString("  SyStEm "))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromString("light"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromString("LIGHT"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromString("  LiGhT  "))
        assertEquals(ThemeMode.DARK, ThemeMode.fromString("dark"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromString("DARK"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromString("\tDaRk\n"))
    }

    @Test
    fun themeModeExposesNonBlankDisplayLabels() {
        for (mode in ThemeMode.values()) {
            assertTrue("Expected non-blank displayLabel for $mode", mode.displayLabel.isNotBlank())
        }
        assertEquals("pref_theme_mode", UiThemeResolver.PREF_THEME_MODE)
    }

    @Test
    fun isDarkActiveRespectsModeAndSystemNightState() {
        assertFalse(UiThemeResolver.isDarkActive(ThemeMode.SYSTEM, systemNightActive = false))
        assertTrue(UiThemeResolver.isDarkActive(ThemeMode.SYSTEM, systemNightActive = true))

        assertFalse(UiThemeResolver.isDarkActive(ThemeMode.LIGHT, systemNightActive = false))
        assertFalse(UiThemeResolver.isDarkActive(ThemeMode.LIGHT, systemNightActive = true))

        assertTrue(UiThemeResolver.isDarkActive(ThemeMode.DARK, systemNightActive = false))
        assertTrue(UiThemeResolver.isDarkActive(ThemeMode.DARK, systemNightActive = true))
    }

    @Test
    fun isDarkFromConfigurationDetectsNightMask() {
        assertTrue(UiThemeResolver.isDarkFromConfiguration(Configuration.UI_MODE_NIGHT_YES))
        assertTrue(
            UiThemeResolver.isDarkFromConfiguration(
                Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
            )
        )
        assertFalse(UiThemeResolver.isDarkFromConfiguration(Configuration.UI_MODE_NIGHT_NO))
        assertFalse(UiThemeResolver.isDarkFromConfiguration(Configuration.UI_MODE_NIGHT_UNDEFINED))
    }

    @Test
    fun paletteForProvidesDistinctHighContrastLightAndDarkTokens() {
        val light = UiThemeResolver.paletteFor(ThemeMode.LIGHT, systemNightActive = true)
        val dark = UiThemeResolver.paletteFor(ThemeMode.DARK, systemNightActive = false)

        assertFalse(light.isDark)
        assertTrue(dark.isDark)

        assertNotEquals(light.bg, dark.bg)
        assertNotEquals(light.surface, dark.surface)
        assertNotEquals(light.surfaceElevated, dark.surfaceElevated)
        assertNotEquals(light.ink, dark.ink)
        assertNotEquals(light.teal, dark.teal)
        assertNotEquals(light.border, dark.border)

        assertEquals(0xFF0E1412.toInt(), dark.bg)
        assertEquals(0xFF17211E.toInt(), dark.surface)
        assertEquals(0xFF1F2D29.toInt(), dark.surfaceElevated)
        assertEquals(0xFF2B3E39.toInt(), dark.border)
        assertEquals(0xFFE9F1EF.toInt(), dark.ink)
        assertEquals(0xFFA0B2AD.toInt(), dark.muted)
        assertEquals(0xFF4EC4B2.toInt(), dark.teal)
        assertEquals(0xFF1B3530.toInt(), dark.mint)
        assertEquals(0xFF0B1110.toInt(), dark.codeBg)

        for (palette in listOf(light, dark)) {
            assertAllPaletteColorsHaveNonZeroAlpha(palette)
            assertNotEquals(palette.userBubbleBg, palette.assistantBubbleBg)
            assertTrue("codeBg must be dark", isDarkColor(palette.codeBg))
            assertTrue("codeText must be light on codeBg", !isDarkColor(palette.codeText))
            assertEquals(
                4,
                setOf(
                    palette.statusLoadedGpu,
                    palette.statusLoadedCpu,
                    palette.statusStandby,
                    palette.statusMissing,
                ).size,
            )
        }
    }

    private fun assertAllPaletteColorsHaveNonZeroAlpha(palette: UiPalette) {
        val colors = listOf(
            palette.bg,
            palette.surface,
            palette.surfaceElevated,
            palette.ink,
            palette.muted,
            palette.teal,
            palette.tealDark,
            palette.mint,
            palette.border,
            palette.rippleOnMint,
            palette.rippleOnSurface,
            palette.rippleOnAccent,
            palette.stopCrimson,
            palette.userBubbleBg,
            palette.userBubbleBorder,
            palette.assistantBubbleBg,
            palette.assistantBubbleBorder,
            palette.inlineCodeBg,
            palette.inlineCodeInk,
            palette.codeBg,
            palette.codeHeaderBg,
            palette.codeBorder,
            palette.codeText,
            palette.codeKeyword,
            palette.codeString,
            palette.codeComment,
            palette.codeNumber,
            palette.successBg,
            palette.successBorder,
            palette.successInk,
            palette.dangerBg,
            palette.dangerBorder,
            palette.dangerInk,
            palette.warningBg,
            palette.warningBorder,
            palette.warningInk,
            palette.statusLoadedGpu,
            palette.statusLoadedCpu,
            palette.statusStandby,
            palette.statusMissing,
            palette.scrimOverlay,
            palette.heroGradientStart,
            palette.heroGradientEnd,
            palette.heroSubtitleInk,
        )
        for (color in colors) {
            assertTrue("Expected non-zero alpha for color 0x${color.toUInt().toString(16)}", (color ushr 24) != 0)
        }
    }

    private fun isDarkColor(argb: Int): Boolean {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val perceivedLuma = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        return perceivedLuma < 0.2
    }

    @Test
    fun lightTextOnSurfaceContrastAtLeast4_5() {
        assertContrastPairs(
            listOf(
                "ink on bg" to ("#1C1917" to "#FAF9F6"),
                "ink on surface" to ("#1C1917" to "#FFFFFF"),
                "ink on elevated surface" to ("#1C1917" to "#F4F1EA"),
                "muted on bg" to ("#6B655E" to "#FAF9F6"),
                "muted on surface" to ("#6B655E" to "#FFFFFF"),
                "muted on elevated surface" to ("#6B655E" to "#F4F1EA"),
                "success ink on success bg" to ("#1B5E20" to "#E8F5E9"),
                "danger ink on danger bg" to ("#B91C1C" to "#FFEBEE"),
                "warning ink on warning bg" to ("#8D5700" to "#FFF8E1"),
            ),
            minimum = 4.5,
        )
    }

    @Test
    fun darkTextOnSurfaceContrastAtLeast4_5() {
        assertContrastPairs(
            listOf(
                "ink on bg" to ("#E9F1EF" to "#0E1412"),
                "ink on surface" to ("#E9F1EF" to "#17211E"),
                "ink on elevated surface" to ("#E9F1EF" to "#1F2D29"),
                "muted on bg" to ("#A0B2AD" to "#0E1412"),
                "muted on surface" to ("#A0B2AD" to "#17211E"),
                "muted on elevated surface" to ("#A0B2AD" to "#1F2D29"),
                "success ink on success bg" to ("#86EFAC" to "#152E24"),
                "danger ink on danger bg" to ("#FF8A80" to "#331B1B"),
                "warning ink on warning bg" to ("#FCD34D" to "#312612"),
            ),
            minimum = 4.5,
        )
    }

    @Test
    fun accentOnSurfaceContrastAtLeast4_5() {
        assertContrastPairs(
            listOf(
                "light teal on surface" to ("#0F6B5E" to "#FFFFFF"),
                "light teal on mint" to ("#0F6B5E" to "#E5F0EC"),
                "light hero subtitle on gradient start" to ("#D9E5E2" to "#1F2927"),
                "light hero subtitle on gradient end" to ("#D9E5E2" to "#174F47"),
                "dark teal on surface" to ("#4EC4B2" to "#17211E"),
                "dark teal on mint" to ("#4EC4B2" to "#1B3530"),
                "dark hero subtitle on gradient start" to ("#CDE5DF" to "#12302C"),
                "dark hero subtitle on gradient end" to ("#CDE5DF" to "#19584F"),
            ),
            minimum = 4.5,
        )
    }

    @Test
    fun semanticUiTokensMatchDesignSystem() {
        assertEquals(48, UiTokens.TOUCH_TARGET_DP)
        assertEquals(24, UiTokens.ICON_CANVAS_DP)
        assertEquals(20, UiTokens.ICON_GLYPH_DP)
        assertEquals(30, UiTokens.PILL_RADIUS_DP)
        assertEquals(14, UiTokens.INPUT_RADIUS_DP)
        assertEquals(16, UiTokens.CARD_RADIUS_DP)
        assertEquals(20, UiTokens.DIALOG_RADIUS_DP)
        assertEquals(22, UiTokens.SHEET_RADIUS_DP)
        assertEquals(1, UiTokens.BORDER_DP)
        assertEquals(36, UiTokens.DRAG_HANDLE_WIDTH_DP)
        assertEquals(4, UiTokens.DRAG_HANDLE_HEIGHT_DP)
        assertEquals(220L, UiTokens.SHEET_ENTRANCE_DURATION_MS)
    }

    @Test
    fun reducedMotionDisablesSheetEntrance() {
        assertEquals(0L, sheetEntranceDurationMs(0f))
        assertEquals(110L, sheetEntranceDurationMs(0.5f))
        assertEquals(220L, sheetEntranceDurationMs(1f))
    }

    private fun assertContrastPairs(
        pairs: List<Pair<String, Pair<String, String>>>,
        minimum: Double,
    ) {
        for ((label, colors) in pairs) {
            val ratio = contrastRatio(colors.first, colors.second)
            val formattedRatio = "%.2f".format(ratio)
            assertTrue(
                "$label contrast $formattedRatio is below $minimum",
                ratio >= minimum,
            )
        }
    }

    private fun contrastRatio(foreground: String, background: String): Double {
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(hex: String): Double {
        val value = hex.removePrefix("#")
        require(value.length == 6) { "Expected RRGGBB hex, got $hex" }
        val channels = listOf(
            value.substring(0, 2).toInt(16) / 255.0,
            value.substring(2, 4).toInt(16) / 255.0,
            value.substring(4, 6).toInt(16) / 255.0,
        ).map { channel ->
            if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
    }
}
