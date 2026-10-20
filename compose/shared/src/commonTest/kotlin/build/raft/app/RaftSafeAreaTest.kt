package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftSafeAreaTest {
    @Test
    fun resolveRaftSafeAreaDpUsesLargestPositiveInset() {
        assertEquals(24f, resolveRaftSafeAreaDp(primaryDp = 0f, fallbackDp = 24f))
        assertEquals(32f, resolveRaftSafeAreaDp(primaryDp = 32f, fallbackDp = 24f))
    }

    @Test
    fun resolveRaftSafeAreaPxUsesLargestPositiveInset() {
        assertEquals(48f, resolveRaftSafeAreaPx(primaryPx = 0f, fallbackPx = 48f))
        assertEquals(72f, resolveRaftSafeAreaPx(primaryPx = 72f, fallbackPx = 48f))
    }

    @Test
    fun resolveRaftSafeAreaPxClampsNegativeValues() {
        assertEquals(0f, resolveRaftSafeAreaPx(primaryPx = -1f, fallbackPx = -4f))
    }

    @Test
    fun resolveRaftNativeSafeAreaDpConvertsIosPixelInsets() {
        assertEquals(
            34f,
            resolveRaftNativeSafeAreaDp(
                nativeInsetsArePixels = true,
                primary = 102f,
                fallback = 0f,
                density = 3f
            )
        )
        assertEquals(
            48f,
            resolveRaftNativeSafeAreaDp(
                nativeInsetsArePixels = false,
                primary = 48f,
                fallback = 0f,
                density = 3f
            )
        )
    }
}
