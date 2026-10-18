package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #188: "desktop mode" for HTML attachments is a viewport pin at document start, not a zoom
 * factor -- the page's own responsive breakpoints are what select the wide layout, so pinning the
 * width is what actually produces the desktop arrangement.
 *
 * These assert the contract the WebView depends on: the script is valid and idempotent enough to run
 * on a page that already has a viewport meta, and it does not touch anything else.
 */
class SlockFilePreviewDesktopModeTest {
    @Test
    fun theScriptPinsADesktopWidthAndIsSelfContained() {
        val script = slockFilePreviewDesktopViewportScript()

        // Pins a desktop-width viewport (what selects the wide layout).
        assertTrue(script.contains("width=1280"), script)
        // Reuses an existing meta rather than adding a second one (two metas = undefined behaviour).
        assertTrue(script.contains("querySelector"), "must reuse the existing viewport meta")
        assertTrue(script.contains("createElement"), "must create one only when absent")
        // Self-invoking so it runs at document start without leaking globals.
        assertTrue(script.trimStart().startsWith("(function()"), script)
        assertTrue(script.trimEnd().endsWith("})();"), script)
    }

    @Test
    fun theDefaultWidthIsADesktopViewportNotATabletOne() {
        // Below ~1000 many responsive sites still serve the compact layout.
        assertTrue(SlockFilePreviewDesktopViewportWidth >= 1000)
    }

    @Test
    fun theWidthIsOverridableSoItIsNotWiredToOneMagicNumber() {
        val narrow = slockFilePreviewDesktopViewportScript(width = 980)

        assertTrue(narrow.contains("width=980"), narrow)
        assertFalse(narrow.contains("width=1280"), "the override must replace the default")
    }

    @Test
    fun theScriptNeverDisablesUserZoom() {
        // Accessibility: pinning width is fine, forbidding pinch-zoom is not.
        val script = slockFilePreviewDesktopViewportScript()

        assertFalse(script.contains("user-scalable=no"), "must not block pinch zoom")
        assertFalse(script.contains("maximum-scale=1"), "must not cap zoom for readability")
    }
}
