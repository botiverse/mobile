package build.raft.app.nav

import build.raft.app.RAFT_APP_SHELL_PAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Process death on Android: the stack saves its page keys (identity only) and a new stack puts them
 * back over its root, continuing the id serial above the highest restored id; anything unusable
 * falls back to the root page alone.
 */
class RaftPageStackSavedStateTest {
    private fun data(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    private val any: (RaftPageKey) -> Boolean = { true }

    private fun stackWith(vararg names: String): RaftPageStack =
        RaftPageStack().apply { names.forEach { open(it, data("id" to it), from = pages.last()) } }

    @Test fun `saved keys round trip into a new stack over its root`() {
        val before = stackWith("a", "b")
        val text = RaftPageStackSavedState.encode(before.savedKeys())!!

        val after = RaftPageStack()
        assertTrue(after.restore(RaftPageStackSavedState.decode(text, any)!!))
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, "a", "b"), after.pages.map { it.args.pageName })
        assertEquals(before.pages.map { it.contentKey }, after.pages.map { it.contentKey })
        assertEquals(data("id" to "b"), after.pages.last().key.pageData)
    }

    @Test fun `the id serial restarts above the highest restored id`() {
        val before = stackWith("a", "b", "c")
        // Close the middle page: the saved ids have a gap and the highest is not the last one opened.
        before.close(before.pages[2])
        val saved = before.savedKeys()
        val after = RaftPageStack()
        assertTrue(after.restore(saved))

        after.open("d", data(), from = after.pages.last())
        val restoredIds = saved.map { it.id }
        val newId = after.pages.last().key.id
        assertTrue(restoredIds.all { newId > it }, "new id $newId reuses or undercuts restored $restoredIds")
        assertEquals(after.pages.size, after.pages.map { it.contentKey }.toSet().size)
    }

    @Test fun `restore puts the host facts back into the args`() {
        val saved = stackWith("a").savedKeys()
        val after = RaftPageStack(hostPageData = { name, d -> JsonObject(d + ("host" to JsonPrimitive(name))) })
        assertTrue(after.restore(saved))
        assertEquals(JsonPrimitive("a"), after.pages.last().args.pageData["host"])
        assertEquals(null, after.pages.last().key.pageData["host"], "host facts must not enter the saved key")
    }

    @Test fun `restore refuses duplicate keys and a stack that already has pages`() {
        val saved = stackWith("a").savedKeys()
        val duplicate = RaftPageStack()
        assertFalse(duplicate.restore(saved + saved))
        assertEquals(1, duplicate.pages.size)

        val busy = stackWith("x")
        assertFalse(busy.restore(saved))
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, "x"), busy.pages.map { it.args.pageName })
    }

    @Test fun `size guard skips saving and decoding a stack that is too large`() {
        val many = RaftPageStack().apply { repeat(RAFT_PAGE_STACK_SAVED_MAX_PAGES + 1) { open("p", data(), from = pages.last()) } }
        assertNull(RaftPageStackSavedState.encode(many.savedKeys()))

        val big = RaftPageStack().apply { open("p", data("blob" to "x".repeat(RAFT_PAGE_STACK_SAVED_MAX_CHARS)), from = pages.last()) }
        assertNull(RaftPageStackSavedState.encode(big.savedKeys()))
        assertNull(RaftPageStackSavedState.decode("[" + " ".repeat(RAFT_PAGE_STACK_SAVED_MAX_CHARS) + "]", any))
    }

    @Test fun `unusable saved text falls back to the root page`() {
        assertNull(RaftPageStackSavedState.decode(null, any))
        assertNull(RaftPageStackSavedState.decode("", any))
        assertNull(RaftPageStackSavedState.decode("not json", any))
        // A key carrying product content is refused (route / pageData rule).
        assertNull(RaftPageStackSavedState.decode("""[{"pageName":"a","pageData":{"messages":[]},"id":1}]""", any))
        // A page no longer allowed on the stack refuses the whole list, not just itself.
        val text = RaftPageStackSavedState.encode(stackWith("a", "b").savedKeys())
        assertNull(RaftPageStackSavedState.decode(text) { it.pageName != "b" })
        assertNotEquals(null, RaftPageStackSavedState.decode(text, any))
    }
}
