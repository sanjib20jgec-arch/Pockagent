package com.example.pocketagent

import android.content.res.Configuration
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
}
