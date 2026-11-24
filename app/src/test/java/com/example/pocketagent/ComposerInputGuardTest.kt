package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerInputGuardTest {

    @Test
    fun `resolveDockBottomPadding adds only residual ime inset`() {
        assertEquals(24, ComposerInputGuard.resolveDockBottomPadding(24, 0, 0))
        assertEquals(24, ComposerInputGuard.resolveDockBottomPadding(24, 300, 300))
        assertEquals(124, ComposerInputGuard.resolveDockBottomPadding(24, 300, 200))
        assertEquals(24, ComposerInputGuard.resolveDockBottomPadding(24, 100, 200))
    }
}
