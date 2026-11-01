package build.raft.app.nav

import build.raft.app.RAFT_APP_SHELL_PAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The shared page back stack: shared pages push and pop it through the unchanged RaftCmpNavigator;
 * replace swaps the opener, the app shell starts a fresh stack, the root page never closes.
 */
class RaftPageStackTest {
    private val stack = RaftPageStack()

    private fun names() = stack.pages.map { it.args.pageName }

    private fun data(vararg pairs: Pair<String, Any>) =
        JsonObject(pairs.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })

    @Test fun `starts on the app shell`() {
        assertEquals(listOf(RAFT_APP_SHELL_PAGE), names())
    }

    @Test fun `openPage pushes and closePage pops through the shared navigator`() {
        stack.navigatorFor(stack.pages.single()).openPage("thread", data("id" to "t1"))
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, "thread"), names())
        assertEquals(data("id" to "t1"), stack.pages.last().key.pageData)

        stack.navigatorFor(stack.pages.last()).closePage()
        assertEquals(listOf(RAFT_APP_SHELL_PAGE), names())
    }

    @Test fun `back pops the top page first and never the root`() {
        stack.open("a", data(), from = stack.pages.last())
        stack.open("b", data(), from = stack.pages.last())
        assertTrue(stack.back())
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, "a"), names())
        assertTrue(stack.back())
        assertFalse(stack.back())
        assertEquals(listOf(RAFT_APP_SHELL_PAGE), names())
    }

    @Test fun `the root page never closes alone or covered`() {
        val shell = stack.pages.single()
        stack.navigatorFor(shell).closePage()
        assertEquals(listOf(shell), stack.pages.toList())

        stack.open("thread", data(), from = shell)
        stack.close(shell)
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, "thread"), names())
    }

    @Test fun `closing a page under the top keeps the order of the rest`() {
        stack.open("a", data(), from = null)
        val a = stack.pages.last()
        stack.open("b", data(), from = a)
        stack.close(a)
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, "b"), names())
    }

    @Test fun `replace current page swaps the opener in place`() {
        stack.open("login", data(), from = null)
        val login = stack.pages.last()
        stack.open("other", data(), from = null)
        stack.open("code", data("slockReplaceCurrentPage" to true), from = login)
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, "code", "other"), names())
    }

    @Test fun `replace from a page no longer on the stack pushes`() {
        stack.open("login", data(), from = null)
        val login = stack.pages.last()
        stack.close(login)
        stack.open("code", data("slockReplaceCurrentPage" to true), from = login)
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, "code"), names())
    }

    @Test fun `opening the app shell starts a fresh stack`() {
        stack.open("a", data(), from = null)
        val oldShell = stack.pages.first()
        stack.open(RAFT_APP_SHELL_PAGE, data(), from = stack.pages.last())
        assertEquals(listOf(RAFT_APP_SHELL_PAGE), names())
        assertTrue(stack.pages.single() !== oldShell)
    }

    @Test fun `every entry has its own content key even for the same page and data`() {
        stack.open("thread", data("id" to "t1"), from = null)
        stack.open("thread", data("id" to "t1"), from = null)
        val keys = stack.pages.map { it.contentKey }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun `keys hold identity only and args add the host facts`() {
        val hosted = RaftPageStack(hostPageData = { JsonObject(it + ("localOffsetMinutes" to JsonPrimitive(480))) })
        val opened = JsonObject(mapOf("channelId" to JsonPrimitive("c1"), "messages" to JsonArray(emptyList())))
        hosted.open("thread", opened, from = null)
        val page = hosted.pages.last()
        assertEquals(data("channelId" to "c1"), page.key.pageData)
        assertEquals(
            JsonObject(mapOf("channelId" to JsonPrimitive("c1"), "localOffsetMinutes" to JsonPrimitive(480))),
            page.args.pageData,
        )
    }
}
