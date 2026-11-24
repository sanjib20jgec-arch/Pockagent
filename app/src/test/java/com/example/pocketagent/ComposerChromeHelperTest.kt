package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerChromeHelperTest {

    @Test
    fun formatModelBadgeHandlesMissingModel() {
        val state = ComposerChromeHelper.formatModelBadge(
            displayName = "gemma-2b.litertlm",
            sizeBytes = 0L,
            activeBackend = ActiveBackend.GPU,
            preference = BackendPreference.GPU_PREFERRED,
            formattedSize = "0 B",
        )
        assertEquals("Choose model", state.label)
        assertEquals("No active model. Tap to choose a LiteRT-LM model", state.contentDescription)
        assertEquals(ModelStatusTone.MISSING, state.tone)

        val negativeSizeState = ComposerChromeHelper.formatModelBadge(
            displayName = null,
            sizeBytes = -5L,
            activeBackend = null,
            preference = BackendPreference.CPU_ONLY,
            formattedSize = "",
        )
        assertEquals(ModelStatusTone.MISSING, negativeSizeState.tone)
        assertEquals("Choose model", negativeSizeState.label)
    }

    @Test
    fun formatModelBadgeHandlesLoadedGpuAndCpu() {
        val gpuState = ComposerChromeHelper.formatModelBadge(
            displayName = "gemma-2b.litertlm",
            sizeBytes = 1_400_000_000L,
            activeBackend = ActiveBackend.GPU,
            preference = BackendPreference.GPU_PREFERRED,
            formattedSize = "1.4 GB",
        )
        assertEquals("gemma-2b · GPU", gpuState.label)
        assertEquals(ModelStatusTone.LOADED_GPU, gpuState.tone)
        assertTrue(gpuState.contentDescription.contains("GPU"))
        assertTrue(gpuState.contentDescription.contains("1.4 GB"))

        val cpuState = ComposerChromeHelper.formatModelBadge(
            displayName = "qwen-1.5b.litertlm",
            sizeBytes = 900_000_000L,
            activeBackend = ActiveBackend.CPU,
            preference = BackendPreference.CPU_ONLY,
            formattedSize = "900 MB",
        )
        assertEquals("qwen-1.5b · CPU", cpuState.label)
        assertEquals(ModelStatusTone.LOADED_CPU, cpuState.tone)
        assertTrue(cpuState.contentDescription.contains("CPU"))
        assertTrue(cpuState.contentDescription.contains("900 MB"))
    }

    @Test
    fun formatModelBadgeHandlesStandbyWithFormattedSizeAndPreference() {
        val standbyGpuPref = ComposerChromeHelper.formatModelBadge(
            displayName = "phi-3-mini.litertlm",
            sizeBytes = 2_100_000_000L,
            activeBackend = null,
            preference = BackendPreference.GPU_PREFERRED,
            formattedSize = "2.1 GB",
        )
        assertEquals("phi-3-mini · Standby", standbyGpuPref.label)
        assertEquals(ModelStatusTone.STANDBY, standbyGpuPref.tone)
        assertTrue(standbyGpuPref.contentDescription.contains("2.1 GB"))
        assertTrue(standbyGpuPref.contentDescription.contains("GPU preferred"))

        val standbyCpuPref = ComposerChromeHelper.formatModelBadge(
            displayName = "phi-3-mini",
            sizeBytes = 2_100_000_000L,
            activeBackend = null,
            preference = BackendPreference.CPU_ONLY,
            formattedSize = "2.1 GB",
        )
        assertEquals("phi-3-mini · Standby", standbyCpuPref.label)
        assertEquals(ModelStatusTone.STANDBY, standbyCpuPref.tone)
        assertTrue(standbyCpuPref.contentDescription.contains("2.1 GB"))
        assertTrue(standbyCpuPref.contentDescription.contains("CPU only"))
    }

    @Test
    fun formatModelBadgeTruncatesLongFilenamesWithoutExtension() {
        val state = ComposerChromeHelper.formatModelBadge(
            displayName = "gemma-2b-it-gpu-int4-quantized.LITERTLM",
            sizeBytes = 1_500_000_000L,
            activeBackend = ActiveBackend.GPU,
            preference = BackendPreference.GPU_PREFERRED,
            formattedSize = "1.5 GB",
        )
        val shortName = state.label.substringBefore(" · ")
        assertEquals(14, shortName.length)
        assertEquals("gemma-2b-it-g…", shortName)
        assertEquals("gemma-2b-it-g… · GPU", state.label)
        assertTrue(!state.label.contains(".litertlm", ignoreCase = true))

        val blankName = ComposerChromeHelper.formatModelBadge(
            displayName = "   .litertlm  ",
            sizeBytes = 100L,
            activeBackend = null,
            preference = BackendPreference.GPU_PREFERRED,
            formattedSize = "100 B",
        )
        assertEquals("Model · Standby", blankName.label)
    }

    @Test
    fun formatElapsedDurationCoversNegativeSubMinuteAndMultiMinute() {
        assertEquals("0.0s", ComposerChromeHelper.formatElapsedDuration(-1_500L))
        assertEquals("0.0s", ComposerChromeHelper.formatElapsedDuration(0L))
        assertEquals("4.2s", ComposerChromeHelper.formatElapsedDuration(4_200L))
        assertEquals("59.9s", ComposerChromeHelper.formatElapsedDuration(59_900L))
        assertEquals("1m 00s", ComposerChromeHelper.formatElapsedDuration(60_000L))
        assertEquals("1m 05s", ComposerChromeHelper.formatElapsedDuration(65_400L))
        assertEquals("12m 09s", ComposerChromeHelper.formatElapsedDuration(729_000L))
    }

    @Test
    fun formatCharBudgetLabelHandlesSub1kMultiKAndNegativeClamping() {
        assertEquals("420 / 12k chars", ComposerChromeHelper.formatCharBudgetLabel(420, 12_000))
        assertEquals("3.4k / 12k chars", ComposerChromeHelper.formatCharBudgetLabel(3_400, 12_000))
        assertEquals("1k / 12.5k chars", ComposerChromeHelper.formatCharBudgetLabel(1_000, 12_500))
        assertEquals("0 / 0 chars", ComposerChromeHelper.formatCharBudgetLabel(-200, -1))
    }

    @Test
    fun formatIndividualChipLabelsFiltersBlanksAndTruncatesPreservingOrder() {
        val input = listOf(
            "notes.txt",
            "   ",
            "",
            "  architecture_review_notes_v2.md  ",
            "exact_22_chars_name.md",
            "short.pdf",
        )
        val result = ComposerChromeHelper.formatIndividualChipLabels(input, maxChipLen = 22)
        assertEquals(
            listOf(
                "notes.txt",
                "architecture_review_n…",
                "exact_22_chars_name.md",
                "short.pdf",
            ),
            result,
        )
        assertEquals(22, result[1].length)
    }
}
