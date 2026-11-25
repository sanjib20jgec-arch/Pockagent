package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerInputGuardTest {

    @Test
    fun `preserves default multiline constants alongside dictation review cap`() {
        assertEquals(4, ComposerInputGuard.DEFAULT_MAX_LINES)
        assertEquals(124, ComposerInputGuard.DEFAULT_MAX_HEIGHT_DP)
        assertEquals(6, ComposerInputGuard.DICTATION_REVIEW_MAX_LINES)
    }

    @Test
    fun `resolveDockBottomPadding adds only residual ime inset`() {
        assertEquals(24, ComposerInputGuard.resolveDockBottomPadding(24, 0, 0))
        assertEquals(24, ComposerInputGuard.resolveDockBottomPadding(24, 300, 300))
        assertEquals(124, ComposerInputGuard.resolveDockBottomPadding(24, 300, 200))
        assertEquals(24, ComposerInputGuard.resolveDockBottomPadding(24, 100, 200))
    }

    @Test
    fun `resolveReviewMaxLines expands bounded draft lines and guards Int MAX_VALUE`() {
        assertEquals(5, ComposerInputGuard.resolveReviewMaxLines(ComposerInputGuard.DEFAULT_MAX_LINES, 5))
        assertEquals(6, ComposerInputGuard.resolveReviewMaxLines(ComposerInputGuard.DEFAULT_MAX_LINES))
        assertEquals(8, ComposerInputGuard.resolveReviewMaxLines(8, 5))
        assertEquals(5, ComposerInputGuard.resolveReviewMaxLines(Int.MAX_VALUE, 5))
        assertEquals(5, ComposerInputGuard.resolveReviewMaxLines(0, 5))
        assertEquals(2, ComposerInputGuard.resolveReviewMaxLines(2, 0))
    }
}
