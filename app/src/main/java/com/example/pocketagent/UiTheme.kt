package com.example.pocketagent

import android.content.res.Configuration
import java.util.Locale

/**
 * User-selectable appearance mode for PocketAgent.
 */
enum class ThemeMode(val displayLabel: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");

    companion object {
        /**
         * Parses a persisted preference string into a [ThemeMode], defaulting safely to [SYSTEM].
         */
        fun fromString(raw: String?): ThemeMode {
            return when (raw?.trim()?.uppercase(Locale.ROOT)) {
                "LIGHT" -> LIGHT
                "DARK" -> DARK
                "SYSTEM", "AUTO", "DEFAULT" -> SYSTEM
                else -> SYSTEM
            }
        }
    }
}

/**
 * Immutable semantic color palette used to style PocketAgent views consistently across light and
 * dark modes without requiring AppCompat or Compose.
 */
data class UiPalette(
    val isDark: Boolean,
    val bg: Int,
    val surface: Int,
    val surfaceElevated: Int,
    val ink: Int,
    val muted: Int,
    val teal: Int,
    val tealDark: Int,
    val mint: Int,
    val border: Int,
    val rippleOnMint: Int,
    val rippleOnSurface: Int,
    val rippleOnAccent: Int,
    val stopCrimson: Int,
    val userBubbleBg: Int,
    val userBubbleBorder: Int,
    val assistantBubbleBg: Int,
    val assistantBubbleBorder: Int,
    val inlineCodeBg: Int,
    val inlineCodeInk: Int,
    val codeBg: Int,
    val codeHeaderBg: Int,
    val codeBorder: Int,
    val codeText: Int,
    val codeKeyword: Int,
    val codeString: Int,
    val codeComment: Int,
    val codeNumber: Int,
    val successBg: Int,
    val successBorder: Int,
    val successInk: Int,
    val dangerBg: Int,
    val dangerBorder: Int,
    val dangerInk: Int,
    val warningBg: Int,
    val warningBorder: Int,
    val warningInk: Int,
    val statusLoadedGpu: Int,
    val statusLoadedCpu: Int,
    val statusStandby: Int,
    val statusMissing: Int,
    val scrimOverlay: Int,
    val heroGradientStart: Int,
    val heroGradientEnd: Int,
    val heroSubtitleInk: Int,
)

/**
 * Semantic size and motion tokens consumed by [UiComponents]. Values map to the
 * Warm Editorial + Swiss spacing rhythm documented in `design-system/pocketagent/MASTER.md`.
 */
object UiTokens {
    const val TOUCH_TARGET_DP = 48
    const val ICON_CANVAS_DP = 24
    const val ICON_GLYPH_DP = 20
    const val PILL_RADIUS_DP = 30
    const val INPUT_RADIUS_DP = 14
    const val CARD_RADIUS_DP = 16
    const val DIALOG_RADIUS_DP = 20
    const val SHEET_RADIUS_DP = 22
    const val BORDER_DP = 1
    const val ELEVATION_DP = 2
    const val CURSOR_WIDTH_DP = 2
    const val SPACING_2_DP = 2
    const val SPACING_4_DP = 4
    const val SPACING_6_DP = 6
    const val SPACING_8_DP = 8
    const val SPACING_10_DP = 10
    const val SPACING_12_DP = 12
    const val SPACING_14_DP = 14
    const val SPACING_20_DP = 20
    const val DRAG_HANDLE_WIDTH_DP = 36
    const val DRAG_HANDLE_HEIGHT_DP = 4
    const val DRAG_HANDLE_RADIUS_DP = 2
    const val DRAG_HANDLE_BOTTOM_MARGIN_DP = 14
    const val SHEET_ENTRANCE_OFFSET_DP = 48
    const val SHEET_ENTRANCE_DURATION_MS = 220L
    const val SHEET_DIM_ALPHA = 0.45f
}

/** Returns the bottom-sheet entrance duration for the system animator duration scale. */
fun sheetEntranceDurationMs(animatorScale: Float): Long {
    if (animatorScale <= 0f) return 0L
    return maxOf(1L, (UiTokens.SHEET_ENTRANCE_DURATION_MS * animatorScale).toLong())
}

/**
 * Resolves the active [UiPalette] from [ThemeMode] and system night configuration.
 */
object UiThemeResolver {
    const val PREF_THEME_MODE = "pref_theme_mode"

    private val LIGHT_PALETTE = UiPalette(
        isDark = false,
        bg = 0xFFFAF9F6.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceElevated = 0xFFF4F1EA.toInt(),
        ink = 0xFF1C1917.toInt(),
        muted = 0xFF6B655E.toInt(),
        teal = 0xFF0F6B5E.toInt(),
        tealDark = 0xFF0B5046.toInt(),
        mint = 0xFFE5F0EC.toInt(),
        border = 0xFFE6E1D6.toInt(),
        rippleOnMint = 0x330F6B5E,
        rippleOnSurface = 0x1A1C1917,
        rippleOnAccent = 0x33FFFFFF,
        stopCrimson = 0xFFB91C1C.toInt(),
        userBubbleBg = 0xFFEFECE4.toInt(),
        userBubbleBorder = 0xFFD9D3C7.toInt(),
        assistantBubbleBg = 0xFFFFFFFF.toInt(),
        assistantBubbleBorder = 0xFFE6E1D6.toInt(),
        inlineCodeBg = 0xFFEEECE6.toInt(),
        inlineCodeInk = 0xFF0F4C43.toInt(),
        codeBg = 0xFF141716.toInt(),
        codeHeaderBg = 0xFF1E2321.toInt(),
        codeBorder = 0xFF2C3330.toInt(),
        codeText = 0xFFE9EFEA.toInt(),
        codeKeyword = 0xFF7DD3FC.toInt(),
        codeString = 0xFF86EFAC.toInt(),
        codeComment = 0xFF8C9692.toInt(),
        codeNumber = 0xFFFDBA74.toInt(),
        successBg = 0xFFE8F5E9.toInt(),
        successBorder = 0xFFC8E6C9.toInt(),
        successInk = 0xFF1B5E20.toInt(),
        dangerBg = 0xFFFFEBEE.toInt(),
        dangerBorder = 0xFFFFCDD2.toInt(),
        dangerInk = 0xFFB91C1C.toInt(),
        warningBg = 0xFFFFF8E1.toInt(),
        warningBorder = 0xFFFFE082.toInt(),
        warningInk = 0xFF8D5700.toInt(),
        statusLoadedGpu = 0xFF10B981.toInt(),
        statusLoadedCpu = 0xFF0EA5E9.toInt(),
        statusStandby = 0xFFF59E0B.toInt(),
        statusMissing = 0xFFDC2626.toInt(),
        scrimOverlay = 0x8012110F.toInt(),
        heroGradientStart = 0xFF1F2927.toInt(),
        heroGradientEnd = 0xFF174F47.toInt(),
        heroSubtitleInk = 0xFFD9E5E2.toInt(),
    )

    private val DARK_PALETTE = UiPalette(
        isDark = true,
        bg = 0xFF0E1412.toInt(),
        surface = 0xFF17211E.toInt(),
        surfaceElevated = 0xFF1F2D29.toInt(),
        ink = 0xFFE9F1EF.toInt(),
        muted = 0xFFA0B2AD.toInt(),
        teal = 0xFF4EC4B2.toInt(),
        tealDark = 0xFF2E9A8A.toInt(),
        mint = 0xFF1B3530.toInt(),
        border = 0xFF2B3E39.toInt(),
        rippleOnMint = 0x334EC4B2,
        rippleOnSurface = 0x24E9F1EF,
        rippleOnAccent = 0x33FFFFFF,
        stopCrimson = 0xFFEF5350.toInt(),
        userBubbleBg = 0xFF1A342F.toInt(),
        userBubbleBorder = 0xFF2D5950.toInt(),
        assistantBubbleBg = 0xFF17211E.toInt(),
        assistantBubbleBorder = 0xFF2B3E39.toInt(),
        inlineCodeBg = 0xFF22332E.toInt(),
        inlineCodeInk = 0xFF7BE0D0.toInt(),
        codeBg = 0xFF0B1110.toInt(),
        codeHeaderBg = 0xFF131D1B.toInt(),
        codeBorder = 0xFF263834.toInt(),
        codeText = 0xFFE4EFEA.toInt(),
        codeKeyword = 0xFF7DD3FC.toInt(),
        codeString = 0xFF86EFAC.toInt(),
        codeComment = 0xFF7D938D.toInt(),
        codeNumber = 0xFFFDBA74.toInt(),
        successBg = 0xFF152E24.toInt(),
        successBorder = 0xFF2A5845.toInt(),
        successInk = 0xFF86EFAC.toInt(),
        dangerBg = 0xFF331B1B.toInt(),
        dangerBorder = 0xFF5E2B2B.toInt(),
        dangerInk = 0xFFFF8A80.toInt(),
        warningBg = 0xFF312612.toInt(),
        warningBorder = 0xFF5C4720.toInt(),
        warningInk = 0xFFFCD34D.toInt(),
        statusLoadedGpu = 0xFF34D399.toInt(),
        statusLoadedCpu = 0xFF38BDF8.toInt(),
        statusStandby = 0xFFFBBF24.toInt(),
        statusMissing = 0xFFF87171.toInt(),
        scrimOverlay = 0xB3050908.toInt(),
        heroGradientStart = 0xFF12302C.toInt(),
        heroGradientEnd = 0xFF19584F.toInt(),
        heroSubtitleInk = 0xFFCDE5DF.toInt(),
    )

    /** Returns true when dark mode should be rendered for [mode] and [systemNightActive]. */
    fun isDarkActive(mode: ThemeMode, systemNightActive: Boolean): Boolean {
        return when (mode) {
            ThemeMode.SYSTEM -> systemNightActive
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    }

    /** Returns the [UiPalette] corresponding to [mode] and [systemNightActive]. */
    fun paletteFor(mode: ThemeMode, systemNightActive: Boolean): UiPalette {
        return if (isDarkActive(mode, systemNightActive)) DARK_PALETTE else LIGHT_PALETTE
    }

    /** Returns true if Android configuration [uiMode] has night mode enabled. */
    fun isDarkFromConfiguration(uiMode: Int): Boolean {
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
