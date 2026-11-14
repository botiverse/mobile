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
        stack.open("thread", data("id" to "t2"), from = null)
        stack.open("thread", data("id" to "t1"), from = null)
        val keys = stack.pages.map { it.contentKey }
        assertEquals(4, keys.size)
        assertEquals(keys.size, keys.toSet().size)
    }

    // task #151: a double tap on a replies row stacked the same thread twice.
    @Test fun `opening the route already on top is a no-op`() {
        val shell = stack.pages.single()
        var changes = 0
        stack.onChangeRequested = { changes++ }
        stack.navigatorFor(shell).openPage("thread", data("id" to "t1"))
        val thread = stack.pages.last()
        stack.navigatorFor(shell).openPage("thread", data("id" to "t1"))
        stack.navigatorFor(thread).openPage("thread", data("id" to "t1", "slockOpenWithoutAnimation" to true))
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, "thread"), names())
        assertTrue(stack.pages.last() === thread)
        assertEquals(1, changes)
    }

    @Test fun `a different route, or the same one after Back, still opens`() {
        val shell = stack.pages.single()
        stack.navigatorFor(shell).openPage("thread", data("id" to "t1"))
        stack.navigatorFor(shell).openPage("thread", data("id" to "t2"))
        assertEquals(listOf("t1", "t2"), stack.pages.drop(1).map { (it.key.pageData["id"] as JsonPrimitive).content })

        assertTrue(stack.back())
        assertTrue(stack.back())
        stack.navigatorFor(shell).openPage("thread", data("id" to "t1"))
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, "thread"), names())
    }

    @Test fun `thread opens compare the thread and anchor, not the per-open payload facts`() {
        fun thread(threadId: String, focused: String?, now: Long) =
            JsonObject(
                mapOf(
                    "pageName" to JsonPrimitive("compose_thread"),
                    "instanceId" to JsonPrimitive("i-$now"),
                    "payload" to
                        JsonPrimitive(
                            """{"instanceId":"i-$now","threadId":"$threadId","parentChannelId":"c1",""" +
                                (focused?.let { "\"focusedMessageId\":\"$it\"," } ?: "") +
                                """"nowEpochMillis":$now}""",
                        ),
                ),
            )
        val shell = stack.pages.single()
        stack.open("compose_thread", thread("t1", null, now = 1), from = shell)
        stack.open("compose_thread", thread("t1", null, now = 2), from = shell)
        assertEquals(2, stack.pages.size)
        stack.open("compose_thread", thread("t1", "m9", now = 3), from = shell)
        assertEquals(3, stack.pages.size, "a new message anchor is a new page")
    }

    @Test fun `replace and the app shell are not deduplicated`() {
        stack.open("login", data(), from = null)
        val login = stack.pages.last()
        stack.open("login", data("slockReplaceCurrentPage" to true), from = login)
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, "login"), names())
        assertTrue(stack.pages.last() !== login)

        val shell = stack.pages.first()
        stack.open(RAFT_APP_SHELL_PAGE, data(), from = stack.pages.last())
        stack.open(RAFT_APP_SHELL_PAGE, data(), from = stack.pages.last())
        assertEquals(listOf(RAFT_APP_SHELL_PAGE), names())
        assertTrue(stack.pages.single() !== shell)
    }

    @Test fun `keys hold identity only and args add the host facts`() {
        val hosted = RaftPageStack(hostPageData = { _, data -> JsonObject(data + ("localOffsetMinutes" to JsonPrimitive(480))) })
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
