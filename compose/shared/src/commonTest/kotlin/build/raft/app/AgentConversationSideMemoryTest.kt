package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Web `slock#16248` parity (`#proj-mobile:#402`): per-`(userId, serverId, agentId)` memory of
 * whether the user last had an agent's Lounge or Private DM open, and merged sidebar row reopen
 * resolution.
 */
class AgentConversationSideMemoryTest {
    @Test
    fun defaultsToLoungeWhenUnsetCorruptOrMissingIdentity() {
        val store = InMemoryRaftKeyValueStore()
        assertEquals(
            AgentLoungeConversationSide.Lounge,
            readAgentConversationSide(store, "u1", "s1", "a1"),
        )
        assertEquals(
            AgentLoungeConversationSide.Lounge,
            resolveMergedAgentRowOpenSide(store, "u1", "s1", "a1", hasDirectMessage = true),
        )
        store.putString(agentConversationSideStorageKey("u1", "s1", "a1"), "garbage")
        assertEquals(
            AgentLoungeConversationSide.Lounge,
            readAgentConversationSide(store, "u1", "s1", "a1"),
        )
        assertEquals(
            AgentLoungeConversationSide.Lounge,
            readAgentConversationSide(store, null, "s1", "a1"),
        )
        assertEquals(
            AgentLoungeConversationSide.Lounge,
            readAgentConversationSide(store, "u1", "", "a1"),
        )
        assertEquals(
            AgentLoungeConversationSide.Lounge,
            readAgentConversationSide(store, "u1", "s1", "  "),
        )
    }

    @Test
    fun remembersPrivateAndLoungeIndependentlyPerUserServerAndAgent() {
        val store = InMemoryRaftKeyValueStore()
        writeAgentConversationSide(store, "u1", "s1", "a1", AgentLoungeConversationSide.Private)

        assertEquals(
            AgentLoungeConversationSide.Private,
            readAgentConversationSide(store, "u1", "s1", "a1"),
        )
        assertEquals(
            AgentLoungeConversationSide.Private,
            resolveMergedAgentRowOpenSide(store, "u1", "s1", "a1", hasDirectMessage = true),
        )
        // Without a DM row (e.g. standalone Lounge), still opens Lounge.
        assertEquals(
            AgentLoungeConversationSide.Lounge,
            resolveMergedAgentRowOpenSide(store, "u1", "s1", "a1", hasDirectMessage = false),
        )
        // Scoped strictly to (userId, serverId, agentId).
        assertEquals(
            AgentLoungeConversationSide.Lounge,
            readAgentConversationSide(store, "u2", "s1", "a1"),
        )
        assertEquals(
            AgentLoungeConversationSide.Lounge,
            readAgentConversationSide(store, "u1", "s2", "a1"),
        )
        assertEquals(
            AgentLoungeConversationSide.Lounge,
            readAgentConversationSide(store, "u1", "s1", "a2"),
        )

        // Switching back to Lounge overwrites Private.
        writeAgentConversationSide(store, "u1", "s1", "a1", AgentLoungeConversationSide.Lounge)
        assertEquals(
            AgentLoungeConversationSide.Lounge,
            resolveMergedAgentRowOpenSide(store, "u1", "s1", "a1", hasDirectMessage = true),
        )
    }

    @Test
    fun swallowsStorageExceptionsAndFallsBackToLounge() {
        val throwingStore =
            object : SharedPreferenceStore {
                override fun getString(key: String): String? = error("disk unavailable")
                override fun putString(key: String, value: String?) = error("disk full")
            }
        writeAgentConversationSide(throwingStore, "u1", "s1", "a1", AgentLoungeConversationSide.Private)
        assertEquals(
            AgentLoungeConversationSide.Lounge,
            readAgentConversationSide(throwingStore, "u1", "s1", "a1"),
        )
    }
}
