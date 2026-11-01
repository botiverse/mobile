package build.raft.app.shared

import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.nav.RaftPageStack
import build.raft.app.nav.RaftPageStackSavedState
import build.raft.app.nav.RaftStackOpenRoute
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Navigation 3 step 1 in the Android shell: the switch off (no stack) keeps every open on the
 * Activity path; on, only the allowlist goes onto the stack; restore takes only allowlisted keys.
 */
class RaftAndroidNav3StackTest {
    private val about =
        JsonObject(mapOf("settingsPage" to JsonPrimitive("about"), "slockShellInstanceId" to JsonPrimitive("shell-1")))
    private val thread = JsonObject(mapOf("channelId" to JsonPrimitive("c1")))

    @Test
    fun switchOffKeepsEveryPageOnTheActivityPath() {
        listOf(SETTINGS_DETAIL to about, RaftPages.THREAD to thread, RaftPages.APP_SHELL to JsonObject(emptyMap()))
            .forEach { (name, data) ->
                assertEquals(name, RaftStackOpenRoute.Host, raftAndroidNav3OpenRoute(stack = null, name, data, from = null))
            }
    }

    @Test
    fun onlyTheAppShellWindowHostsTheStack() {
        // Short-circuits before the process decision: no other page ever draws the stack.
        assertFalse(raftAndroidNav3StackEnabled(RaftPages.THREAD) { InMemoryRaftKeyValueStore() })
        assertFalse(raftAndroidNav3StackEnabled(RaftPages.LOGIN) { InMemoryRaftKeyValueStore() })
    }

    @Test
    fun switchOnRoutesTheAllowlistToTheStackAndTheRestToActivities() {
        val stack = RaftPageStack()
        assertEquals(RaftStackOpenRoute.Stack, raftAndroidNav3OpenRoute(stack, SETTINGS_DETAIL, about, from = null))
        stack.open(SETTINGS_DETAIL, about, from = stack.pages.first())
        val aboutPage = stack.pages.last()
        assertEquals(RaftStackOpenRoute.Stack, raftAndroidNav3OpenRoute(stack, SETTINGS_DETAIL, about, from = aboutPage))
        // Mixing: a page not on the allowlist opened from a stack page still gets an Activity.
        assertEquals(RaftStackOpenRoute.Host, raftAndroidNav3OpenRoute(stack, RaftPages.THREAD, thread, from = aboutPage))
        assertEquals(RaftStackOpenRoute.Host, raftAndroidNav3OpenRoute(stack, RaftPages.APP_SHELL, about, from = aboutPage))
    }

    @Test
    fun restoreTakesOnlyAllowlistedPages() {
        val stack = RaftPageStack().apply { open(SETTINGS_DETAIL, about, from = pages.first()) }
        val text = RaftPageStackSavedState.encode(stack.savedKeys())
        assertEquals(stack.savedKeys(), raftAndroidNav3RestoredKeys(text))

        val dropped = RaftPageStack().apply { open(RaftPages.THREAD, thread, from = pages.first()) }
        assertNull(raftAndroidNav3RestoredKeys(RaftPageStackSavedState.encode(dropped.savedKeys())))
        assertNull(raftAndroidNav3RestoredKeys(null))
    }

    @Test
    fun aStackPageClosePageClosesThePageNotTheWindow() {
        val calls = mutableListOf<String>()
        var closed = 0
        val transport =
            RaftStackPageBridgeTransport(
                delegate = HostBridgeTransport { method, _, _ -> calls += method },
                onClosePage = { closed++ },
            )
        transport.call("closePage", "{}", null)
        transport.call("toast", "{}", null)
        assertEquals(1, closed)
        assertEquals(listOf("toast"), calls)
    }

    private companion object {
        const val SETTINGS_DETAIL = "compose_settings_native_detail"
    }
}
