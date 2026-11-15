package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** The window's drafts for tasks without a conversation follow the sign-in ([TaskPendingDraftStore.followSession]). */
class TaskPendingDraftStoreTest {
    @Test
    fun draftsAreKeptPerServerAndTask() {
        val store = TaskPendingDraftStore()
        store.update("server-1", "t-1", "one")
        store.update("server-2", "t-1", "two")
        store.update("server-1", "t-2", "three")
        assertEquals("one", store.draft("server-1", "t-1"))
        assertEquals("two", store.draft("server-2", "t-1"))
        assertEquals("three", store.draft("server-1", "t-2"))
        store.clear("server-1", "t-1")
        assertEquals("", store.draft("server-1", "t-1"))
        assertEquals("two", store.draft("server-2", "t-1"), "only that server's task is cleared")
    }

    @Test
    fun theRandomIdIsReusedUntilTheDraftIsCleared() {
        val store = TaskPendingDraftStore()
        var ids = 0
        assertEquals("rid-1", store.randomId("server-1", "t-1") { "rid-${++ids}" })
        assertEquals("rid-1", store.randomId("server-1", "t-1") { "rid-${++ids}" })
        store.clear("server-1", "t-1")
        assertEquals("rid-2", store.randomId("server-1", "t-1") { "rid-${++ids}" })
    }

    @Test
    fun draftsGoWithSignOutAnotherAccountAndAServerLeft() {
        val store = TaskPendingDraftStore()
        store.followSession(signedOut = false, accountId = "user-a", serverIds = listOf("server-1", "server-2"))
        store.update("server-1", "t-1", "one")
        store.update("server-2", "t-1", "two")

        // Still restoring: nothing changes.
        store.followSession(signedOut = false, accountId = null, serverIds = null)
        assertEquals("one", store.draft("server-1", "t-1"))

        // The same account, server-2 left (or removed): only its drafts go.
        store.followSession(signedOut = false, accountId = "user-a", serverIds = listOf("server-1"))
        assertEquals("one", store.draft("server-1", "t-1"))
        assertEquals("", store.draft("server-2", "t-1"))

        // An unknown server list (empty) keeps what there is.
        store.followSession(signedOut = false, accountId = "user-a", serverIds = emptyList())
        assertEquals("one", store.draft("server-1", "t-1"))

        // Another account: the previous one's drafts go.
        store.followSession(signedOut = false, accountId = "user-b", serverIds = listOf("server-1"))
        assertEquals("", store.draft("server-1", "t-1"))

        // Sign-out: every draft goes.
        store.update("server-1", "t-1", "b's")
        store.followSession(signedOut = true, accountId = null, serverIds = null)
        assertEquals("", store.draft("server-1", "t-1"))
    }
}
