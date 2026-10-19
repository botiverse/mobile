package build.raft.app.compat.pager

import build.raft.app.compat.json.JSONObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class RaftPagerManagerTest {
    private val registered = mutableListOf<Pager>()

    private fun livePager(id: String): Pager =
        object : Pager() {}.also { pager ->
            pager.pagerId = id
            pager.pageData = PageData(JSONObject())
            PagerManager.register(pager)
            registered += pager
        }

    @AfterTest
    fun unregisterAll() {
        registered.forEach(PagerManager::unregister)
    }

    @Test
    fun explicitIdsNeverFallBackToAnotherPage() {
        val first = livePager("raft-test-pager-a")
        val second = livePager("raft-test-pager-b")
        assertSame(first, PagerManager.getPager("raft-test-pager-a"))
        assertSame(second, PagerManager.getPager("raft-test-pager-b"))

        PagerManager.unregister(first)
        registered -= first
        // A destroyed (or unknown) page's id must not be routed to the other live page.
        assertNull(PagerManager.getPagerOrNull("raft-test-pager-a"))
        assertFailsWith<IllegalStateException> { PagerManager.getPager("raft-test-pager-a") }
        assertFailsWith<IllegalStateException> { PagerManager.getPager("raft-test-pager-unknown") }
        assertSame(second, PagerManager.getCurrentPager())
    }
}
