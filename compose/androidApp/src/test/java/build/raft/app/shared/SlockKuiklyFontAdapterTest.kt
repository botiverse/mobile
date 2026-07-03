package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class SlockKuiklyFontAdapterTest {
    @Test
    fun hostDisplayDensityKeepsPhoneWidthDesignDensity() {
        val density =
            SlockKuiklyFontAdapter.hostDisplayDensity(
                widthPx = 1080,
                heightPx = 2400,
                sourceDensity = 3.0f
            )

        assertEquals(1080f / 393f, density!!, 0.001f)
    }

    @Test
    fun hostDisplayDensityCapsLargeScreenAtSourceDensity() {
        val density =
            SlockKuiklyFontAdapter.hostDisplayDensity(
                widthPx = 1344,
                heightPx = 2772,
                sourceDensity = 2.0f
            )

        assertEquals(2.0f, density!!)
    }

    @Test
    fun hostDisplayDensityFallsBackToDesignDensityWhenSourceDensityIsUnavailable() {
        val density =
            SlockKuiklyFontAdapter.hostDisplayDensity(
                widthPx = 1080,
                heightPx = 2400,
                sourceDensity = 0f
            )

        assertEquals(1080f / 393f, density!!, 0.001f)
    }
}
