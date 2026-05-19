package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockSafeAreaTest {
    @Test
    fun resolveSlockSafeAreaPxUsesLargestPositiveInset() {
        assertEquals(48f, resolveSlockSafeAreaPx(primaryPx = 0f, fallbackPx = 48f))
        assertEquals(72f, resolveSlockSafeAreaPx(primaryPx = 72f, fallbackPx = 48f))
    }

    @Test
    fun resolveSlockSafeAreaPxClampsNegativeValues() {
        assertEquals(0f, resolveSlockSafeAreaPx(primaryPx = -1f, fallbackPx = -4f))
    }
}
