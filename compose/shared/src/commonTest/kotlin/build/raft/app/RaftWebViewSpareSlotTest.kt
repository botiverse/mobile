package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftWebViewSpareSlotTest {
    private class View(val name: String)

    @Test
    fun aSpareIsHandedOutOnlyAfterItsWarmUpLoadEnded() {
        val released = mutableListOf<String>()
        val slot = RaftWebViewSpareSlot<View> { released += it.name }
        val spare = View("a")
        val generation = slot.put(spare)

        assertNull(slot.take(), "still warming up: the page makes its own view")
        assertFalse(slot.isEmpty, "a warming spare stays for later")

        slot.markReady(generation)
        assertSame(spare, slot.take())
        assertTrue(slot.isEmpty)
        assertNull(slot.take(), "one spare, handed out once")
        assertEquals(emptyList(), released, "a taken spare is the page's, never released")
    }

    @Test
    fun aDroppedOrReplacedSpareIsReleasedAndItsLateReadyIsIgnored() {
        val released = mutableListOf<String>()
        val slot = RaftWebViewSpareSlot<View> { released += it.name }
        val first = slot.put(View("a"))
        val second = slot.put(View("b"))
        assertEquals(listOf("a"), released, "at most one spare: the previous one is released")

        slot.markReady(first)
        assertNull(slot.take(), "the replaced spare's warm-up end does not make the new one ready")

        assertTrue(slot.drop())
        assertEquals(listOf("a", "b"), released)
        slot.markReady(second)
        assertNull(slot.take(), "a dropped spare's late warm-up end hands nothing out")
        assertFalse(slot.drop())
    }

    @Test
    fun theBackgroundIsTheCanvasUntilTheFirstPaintThenWhite() {
        val canvas = 0xFF1C1B1A.toInt()
        assertEquals(canvas, raftWebViewBackgroundArgb(canvas, painted = false))
        assertEquals(RAFT_WEB_VIEW_DEFAULT_BACKGROUND_ARGB, raftWebViewBackgroundArgb(canvas, painted = true))
        assertEquals(RAFT_WEB_VIEW_DEFAULT_BACKGROUND_ARGB, raftWebViewBackgroundArgb(0, painted = false))
        assertEquals(0xFFFFFFFF.toInt(), RAFT_WEB_VIEW_DEFAULT_BACKGROUND_ARGB)
    }

    @Test
    fun firstPaintThemeScriptPaintsTheCanvasAtLowestPriorityUntilTheLoadEnds() {
        for ((dark, scheme) in listOf(true to "dark", false to "light")) {
            val script = raftWebViewFirstPaintThemeScript(0xFF1C1B1A.toInt(), dark)
            // The canvas colour (alpha dropped) and the app's scheme ...
            assertTrue("background-color:rgb(28,27,26)" in script, script)
            assertTrue("color-scheme:$scheme" in script, script)
            // ... in a zero-specificity rule, so the page's own background and scheme win ...
            assertTrue(":where(html){" in script, script)
            // ... top-level document only, and gone once the page loaded.
            assertTrue("window.top!==window" in script, script)
            assertTrue("addEventListener('load'" in script, script)
            assertTrue("removeProperty('background-color')" in script, script)
        }
    }

    @Test
    fun spareBlankCanvasScriptPaintsTheBlankDocumentInTheCanvasAndScheme() {
        for ((dark, scheme) in listOf(true to "dark", false to "light")) {
            val script = raftWebViewSpareBlankCanvasScript(0xFF1C1B1A.toInt(), dark)
            assertTrue("setProperty('background-color','rgb(28,27,26)')" in script, script)
            assertTrue("setProperty('color-scheme','$scheme')" in script, script)
        }
    }

    @Test
    fun theWebViewIsHiddenUntilItsDocumentCommitsWhenACanvasIsSet() {
        val canvas = 0xFF1C1B1A.toInt()
        assertFalse(raftWebViewShownBeforeCommit(canvas, committed = false))
        assertTrue(raftWebViewShownBeforeCommit(canvas, committed = true))
        assertTrue(raftWebViewShownBeforeCommit(0, committed = false))
    }

    @Test
    fun onlyTheSparesBlankDocumentIsIgnored() {
        val page = "https://app.raft.build/changelog"
        // (usingSpare, navigationUrl, src) -> ignored
        val cases =
            listOf(
                Triple(true, "about:blank", page) to true,
                Triple(true, page, page) to false,
                Triple(false, "about:blank", page) to false,
                Triple(true, "about:blank", "") to false,
                Triple(true, "about:blank", "about:blank") to false,
                Triple(true, null, page) to false,
            )
        for ((input, expected) in cases) {
            val (usingSpare, url, src) = input
            assertEquals(expected, raftWebViewIgnoresSpareBlankNavigation(usingSpare, url, src), "for $input")
        }
    }
}
