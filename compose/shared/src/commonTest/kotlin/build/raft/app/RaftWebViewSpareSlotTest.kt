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
    fun pageThemeProbeIsDecodedAndOnlyABackgroundlessPageGoesWhite() {
        val canvas = 0xFF1C1B1A.toInt()
        val dark = raftWebViewDecodePageTheme("1|rgb(23, 25, 28)|rgb(23, 25, 28)")!!
        assertTrue(dark.prefersDark)
        assertEquals("prefersDark=true root=rgb(23,25,28) body=rgb(23,25,28)", dark.logFields())
        assertTrue(raftWebViewPageHasOwnBackground(dark, canvas))
        // Root painted only by the first-paint script (the canvas), body transparent: none of its own.
        val bare = raftWebViewDecodePageTheme("0|rgb(28, 27, 26)|rgba(0, 0, 0, 0)")!!
        assertFalse(bare.prefersDark)
        assertFalse(raftWebViewPageHasOwnBackground(bare, canvas))
        assertTrue(raftWebViewPageHasOwnBackground(raftWebViewDecodePageTheme("1|rgb(245, 245, 242)|rgba(0, 0, 0, 0)")!!, canvas))
        assertFalse(raftWebViewPageHasOwnBackground(raftWebViewDecodePageTheme("1|rgba(0, 0, 0, 0)|transparent")!!, canvas))
        assertNull(raftWebViewDecodePageTheme(""))
        assertNull(raftWebViewDecodePageTheme(null))
        assertNull(raftWebViewDecodePageTheme("x|a|b"))
        assertTrue(raftWebViewCssColorIsTransparent("rgba(1, 2, 3, 0)"))
        assertFalse(raftWebViewCssColorIsTransparent("rgba(1, 2, 3, 0.5)"))
    }
}
