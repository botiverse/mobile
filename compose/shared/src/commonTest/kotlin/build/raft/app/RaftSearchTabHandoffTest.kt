package build.raft.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftSearchTabHandoffTest {
    @AfterTest
    fun reset() = RaftSearchTabHandoff.resetForTest()

    @Test
    fun searchAllCarriesTheQueryToTheShellOnce() {
        assertFalse(RaftSearchTabHandoff.closesPushedPages())
        RaftSearchTabHandoff.request("  figma  ")
        // Pages between the conversation search and the shell close while it is pending.
        assertTrue(RaftSearchTabHandoff.closesPushedPages())
        assertEquals("figma", RaftSearchTabHandoff.consume()?.query)
        assertNull(RaftSearchTabHandoff.consume())
        assertFalse(RaftSearchTabHandoff.closesPushedPages())
    }

    @Test
    fun eachRequestHasANewNonce() {
        RaftSearchTabHandoff.request("a")
        val first = RaftSearchTabHandoff.consume()!!
        RaftSearchTabHandoff.request("a")
        val second = RaftSearchTabHandoff.consume()!!
        assertTrue(second.nonce > first.nonce)
    }

    @Test
    fun theOpenedRowIsMarkedOnlyWhenThePageShowsAgain() {
        assertNull(homeSearchReturnHighlightKey(openedKey = "row", pageVisible = false))
        assertEquals("row", homeSearchReturnHighlightKey(openedKey = "row", pageVisible = true))
        assertNull(homeSearchReturnHighlightKey(openedKey = null, pageVisible = true))
    }
}
