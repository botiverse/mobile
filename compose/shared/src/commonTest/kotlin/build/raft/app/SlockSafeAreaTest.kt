package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockSafeAreaTest {
    @Test
    fun resolveSlockSafeAreaDpUsesLargestPositiveInset() {
        assertEquals(24f, resolveSlockSafeAreaDp(primaryDp = 0f, fallbackDp = 24f))
        assertEquals(32f, resolveSlockSafeAreaDp(primaryDp = 32f, fallbackDp = 24f))
    }

    @Test
    fun resolveSlockSafeAreaPxUsesLargestPositiveInset() {
        assertEquals(48f, resolveSlockSafeAreaPx(primaryPx = 0f, fallbackPx = 48f))
        assertEquals(72f, resolveSlockSafeAreaPx(primaryPx = 72f, fallbackPx = 48f))
    }

    @Test
    fun resolveSlockSafeAreaPxClampsNegativeValues() {
        assertEquals(0f, resolveSlockSafeAreaPx(primaryPx = -1f, fallbackPx = -4f))
    }

    @Test
    fun resolveSlockNativeSafeAreaDpConvertsIosPixelInsets() {
        assertEquals(
            34f,
            resolveSlockNativeSafeAreaDp(
                nativeInsetsArePixels = true,
                primary = 102f,
                fallback = 0f,
                density = 3f
            )
        )
        assertEquals(
            48f,
            resolveSlockNativeSafeAreaDp(
                nativeInsetsArePixels = false,
                primary = 48f,
                fallback = 0f,
                density = 3f
            )
        )
    }
}
