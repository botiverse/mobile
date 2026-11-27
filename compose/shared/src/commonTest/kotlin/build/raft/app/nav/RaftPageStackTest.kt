package build.raft.app.nav

import build.raft.app.settings.RAFT_APP_SHELL_PAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    @Test fun `replace current page swaps the opener in place`() {
        stack.open("login", data(), from = null)
        val login = stack.pages.last()
        stack.open("other", data(), from = null)
        stack.open("code", data("slockReplaceCurrentPage" to true), from = login)
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, "code", "other"), names())
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

    @Test fun `thread opens compare the thread and anchor and not the per-open payload facts`() {
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

    @Test fun `open logs redact web URL paths past first segment and query`() {
        val logs = mutableListOf<String>()
        val loggedStack = RaftPageStack(log = { logs += it })
        loggedStack.open(
            build.raft.app.settings.SETTINGS_WEB_VIEW_PAGE,
            data(
                build.raft.app.settings.SETTINGS_WEB_VIEW_SURFACE to "web-page",
                build.raft.app.settings.SETTINGS_WEB_VIEW_TARGET_URL to "https://app.raft.build/integration-invites/secret-token-xyz?ticket=abc#frag",
            ),
            from = loggedStack.pages.single(),
        )
        assertTrue(logs.any { "https://app.raft.build/integration-invites" in it }, "expected redacted origin+first segment in $logs")
        assertTrue(logs.none { "secret-token-xyz" in it || "ticket=abc" in it }, "secret path/query leaked into logs: $logs")
    }
}
