package build.raft.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The desktop back stack the Navigation 3 `NavDisplay` draws: shared pages push and pop it through
 * the unchanged RaftCmpNavigator; Esc / Cmd+[ / mouse back and the window history route here.
 */
class RaftDesktopNavigatorTest {
    private var emptied = 0
    private val navigator = RaftDesktopNavigator(onEmpty = { emptied++ })

    private fun names() = navigator.pages.map { it.args.pageName }

    private fun data(vararg pairs: Pair<String, Any>) =
        JsonObject(pairs.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })

    @Test fun `starts on the app shell`() {
        assertEquals(listOf(RaftDesktopPages.APP_SHELL), names())
    }

    @Test fun `openPage pushes and closePage pops through the shared navigator`() {
        val shell = navigator.pages.single()
        navigator.navigatorFor(shell).openPage("thread", data("id" to "t1"))
        assertEquals(listOf(RaftDesktopPages.APP_SHELL, "thread"), names())
        assertEquals("t1", (navigator.pages.last().args.pageData["id"] as JsonPrimitive).content)

        navigator.navigatorFor(navigator.pages.last()).closePage()
        assertEquals(listOf(RaftDesktopPages.APP_SHELL), names())
        assertEquals(0, emptied)
    }

    @Test fun `back pops the top page first and never the last one`() {
        navigator.open("a", data(), from = navigator.pages.last())
        navigator.open("b", data(), from = navigator.pages.last())
        assertTrue(navigator.back())
        assertEquals(listOf(RaftDesktopPages.APP_SHELL, "a"), names())
        assertTrue(navigator.back())
        assertEquals(listOf(RaftDesktopPages.APP_SHELL), names())
        assertFalse(navigator.back())
        assertEquals(listOf(RaftDesktopPages.APP_SHELL), names())
        assertEquals(0, emptied)
    }

    @Test fun `closing a page under the top keeps the order of the rest`() {
        navigator.open("a", data(), from = null)
        val a = navigator.pages.last()
        navigator.open("b", data(), from = a)
        navigator.close(a)
        assertEquals(listOf(RaftDesktopPages.APP_SHELL, "b"), names())
    }

    @Test fun `closing the last page quits`() {
        navigator.close(navigator.pages.single())
        assertTrue(navigator.pages.isEmpty())
        assertEquals(1, emptied)
    }

    @Test fun `replace current page swaps the opener in place`() {
        navigator.open("login", data(), from = null)
        val login = navigator.pages.last()
        navigator.open("code", data("slockReplaceCurrentPage" to true), from = login)
        assertEquals(listOf(RaftDesktopPages.APP_SHELL, "code"), names())
    }

    @Test fun `opening the app shell starts a fresh stack`() {
        navigator.open("a", data(), from = null)
        navigator.open("b", data(), from = null)
        navigator.open(RaftDesktopPages.APP_SHELL, data(), from = navigator.pages.last())
        assertEquals(listOf(RaftDesktopPages.APP_SHELL), names())
    }

    @Test fun `every entry has its own content key, even for the same page and data`() {
        navigator.open("thread", data("id" to "t1"), from = null)
        navigator.open("thread", data("id" to "t1"), from = null)
        val keys = navigator.pages.map { it.contentKey }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.takeLast(2).all { it.startsWith("thread#") })
    }

    @Test fun `pages carry the host time context`() {
        navigator.open("activity", data(), from = null)
        assertTrue("localOffsetMinutes" in navigator.pages.last().args.pageData)
        assertTrue("localOffsetMinutes" in navigator.pages.first().args.pageData)
    }

    @Test fun `history on the lone shell goes to the shell`() {
        val received = mutableListOf<Pair<String, JsonObject>>()
        navigator.pages.single().events.subscribe { event, data -> received += event to data }
        assertTrue(navigator.history(RaftDesktopHistoryDirection.Back))
        assertTrue(navigator.history(RaftDesktopHistoryDirection.Forward))
        assertEquals(
            listOf(
                RaftDesktopHistoryInput.EVENT_NAME to RaftDesktopHistoryInput.eventData(RaftDesktopHistoryDirection.Back),
                RaftDesktopHistoryInput.EVENT_NAME to RaftDesktopHistoryInput.eventData(RaftDesktopHistoryDirection.Forward),
            ),
            received,
        )
        assertEquals(listOf(RaftDesktopPages.APP_SHELL), names())
    }

    @Test fun `history over a pushed page closes it on Back and ignores Forward`() {
        val received = mutableListOf<String>()
        navigator.pages.single().events.subscribe { event, _ -> received += event }
        navigator.open("a", data(), from = null)
        navigator.open("b", data(), from = null)

        assertFalse(navigator.history(RaftDesktopHistoryDirection.Forward))
        assertEquals(listOf(RaftDesktopPages.APP_SHELL, "a", "b"), names())

        assertTrue(navigator.history(RaftDesktopHistoryDirection.Back))
        assertEquals(listOf(RaftDesktopPages.APP_SHELL, "a"), names())
        assertTrue(navigator.history(RaftDesktopHistoryDirection.Back))
        assertEquals(listOf(RaftDesktopPages.APP_SHELL), names())
        // Back to the lone shell: the next step is the shell's own history again.
        assertTrue(received.isEmpty())
        assertTrue(navigator.history(RaftDesktopHistoryDirection.Back))
        assertEquals(listOf(RaftDesktopHistoryInput.EVENT_NAME), received)
    }
}
