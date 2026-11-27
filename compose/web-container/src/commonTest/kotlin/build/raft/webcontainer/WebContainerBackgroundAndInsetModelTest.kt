package build.raft.webcontainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WebContainerBackgroundAndInsetModelTest {
    private val darkCanvas = 0xFF1B1B19.toInt()
    private val lightCanvas = 0xFFFAF9F7.toInt()
    private val opaqueWhite = 0xFFFFFFFF.toInt()

    @Test
    fun webContainerConfigDefaultsPreserveExistingBehavior() {
        val config = WebContainerConfig(url = "https://example.com")
        assertFalse(config.persistBackgroundAfterPaint)
        assertEquals(WebContainerAndroidInsetMode.HostHandled, config.androidInsetMode)
        assertEquals(0, config.backgroundArgb)
    }

    @Test
    fun resolveWebContainerBackgroundArgbRevertsToWhiteAfterPaintByDefault() {
        assertEquals(darkCanvas, resolveWebContainerBackgroundArgb(darkCanvas, painted = false))
        assertEquals(opaqueWhite, resolveWebContainerBackgroundArgb(darkCanvas, painted = true))
        assertEquals(opaqueWhite, resolveWebContainerBackgroundArgb(0, painted = false))
        assertEquals(opaqueWhite, resolveWebContainerBackgroundArgb(0, painted = true))
    }

    @Test
    fun resolveWebContainerBackgroundArgbPersistsAcrossPaintAndThemeSwitchWhenRequested() {
        assertEquals(
            darkCanvas,
            resolveWebContainerBackgroundArgb(darkCanvas, painted = false, persistAfterPaint = true),
        )
        assertEquals(
            darkCanvas,
            resolveWebContainerBackgroundArgb(darkCanvas, painted = true, persistAfterPaint = true),
        )
        assertEquals(
            lightCanvas,
            resolveWebContainerBackgroundArgb(lightCanvas, painted = true, persistAfterPaint = true),
        )
        assertEquals(
            opaqueWhite,
            resolveWebContainerBackgroundArgb(0, painted = true, persistAfterPaint = true),
        )
    }
}
