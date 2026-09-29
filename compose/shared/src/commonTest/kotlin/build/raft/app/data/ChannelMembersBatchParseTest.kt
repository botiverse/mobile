package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * task #352 (Web #7209): parse the atomic batch-add response. The contract is
 * whole-batch: `added` = newly added, `alreadyMembers` = requested-but-present.
 * A re-send is idempotent (added empty, everything under alreadyMembers), and a
 * malformed envelope (missing ok) is rejected.
 */
class ChannelMembersBatchParseTest {

    @Test
    fun parsesAddedAndAlreadyMembers() {
        val result = parseSharedChannelMembersBatchResult(
            """{"ok":true,"added":{"userIds":["u1"],"agentIds":["a1"]},"alreadyMembers":{"userIds":["u2"],"agentIds":["a2"]}}"""
        )!!
        assertEquals(listOf("u1"), result.addedUserIds)
        assertEquals(listOf("a1"), result.addedAgentIds)
        assertEquals(listOf("u2"), result.alreadyMemberUserIds)
        assertEquals(listOf("a2"), result.alreadyMemberAgentIds)
        assertTrue(!result.addedNothing)
    }

    @Test
    fun idempotentResendIsAlreadyMembersOnlyAndStillOk() {
        val result = parseSharedChannelMembersBatchResult(
            """{"ok":true,"added":{"userIds":[],"agentIds":[]},"alreadyMembers":{"userIds":["u1"],"agentIds":["a1"]}}"""
        )!!
        assertTrue(result.addedNothing, "alreadyMembers-only must be a success, not an error")
        assertEquals(listOf("u1"), result.alreadyMemberUserIds)
    }

    @Test
    fun missingOkOrMalformedIsRejected() {
        assertNull(parseSharedChannelMembersBatchResult("""{"added":{"userIds":["u1"]}}"""))
        assertNull(parseSharedChannelMembersBatchResult("""{"ok":false}"""))
        assertNull(parseSharedChannelMembersBatchResult("not json"))
    }

    @Test
    fun emptyBodiesDefaultToNoIds() {
        val result = parseSharedChannelMembersBatchResult("""{"ok":true}""")!!
        assertTrue(result.addedNothing)
        assertTrue(result.alreadyMemberUserIds.isEmpty())
        assertTrue(result.alreadyMemberAgentIds.isEmpty())
    }
}
