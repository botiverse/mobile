package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ThreadFollowersUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadAgentFollowersContractTest {
    // Exact field shape from slock 94d8ec1da8680ff3c94b0ae7200beb8fe933300e,
    // routes/channels.threads.api.test.ts and channelService.getManagedThreadAgentFollowers.
    private val body = """{"threads":[{"threadChannelId":"thread","canManage":true,"agents":[
        {"id":"local","name":"local-agent","displayName":null,"status":"idle","avatarUrl":null,
         "serverId":"ours","serverName":"Our server","serverSlug":"ours","isCurrentServer":true,"canRemove":true},
        {"id":"peer","name":"peer-agent","displayName":"Peer","status":"working","avatarUrl":"https://example.test/a.png",
         "serverId":"theirs","serverName":"Partner server","serverSlug":"partner","isCurrentServer":false,"canRemove":false}
    ]}]}"""

    @Test fun rosterPreservesJointIdentityAndPermissionsAndRejectsAnotherThread() {
        val roster = parseSharedThreadAgentFollowers(body, "thread")!!
        assertEquals("local-agent", roster.agents.first().label)
        assertEquals("Partner server", roster.agents.last().serverName)
        assertFalse(roster.agents.last().isCurrentServer)
        assertFalse(roster.agents.last().canRemove)
        assertTrue(roster.agents.first().canRemove)
        assertNull(parseSharedThreadAgentFollowers(body, "different-thread"))
        assertNull(parseSharedThreadAgentFollowers(body.replace("\"canManage\":true,", ""), "thread"))
        assertNull(parseSharedThreadAgentFollowers("""{"threads":[]}""", "thread"))
    }

    @Test fun removalRequiresSuccessAndATokenOnlyWhenActuallyRemoved() {
        assertEquals("2026-09-29T08:00:00Z", parseSharedThreadAgentFollowerRemoval(
            """{"ok":true,"removed":true,"undoToken":"2026-09-29T08:00:00Z"}"""
        )!!.undoToken)
        assertNull(parseSharedThreadAgentFollowerRemoval("""{"ok":true,"removed":true,"undoToken":null}"""))
        assertNull(parseSharedThreadAgentFollowerRemoval("""{"ok":false,"removed":false}"""))
        assertFalse(parseSharedThreadAgentFollowerRemoval("""{"ok":true,"removed":false,"undoToken":null}""")!!.removed)
    }

    @Test fun followerUpdatesAreSubscribedAndMapOnlyCanonicalThreadIdentity() {
        assertTrue(RealtimeSocketEvents.ThreadFollowersUpdated in RealtimeSocketEvents.ServerWireEvents)
        val mapper = RealtimeEventMapper(diagnosticsLogger = {})
        val event = assertIs<ThreadFollowersUpdated>(mapper.map(
            RealtimeSocketEvents.ThreadFollowersUpdated, """{"threadChannelId":"thread"}""",
        ))
        assertEquals("thread", event.threadChannelId.value)
        assertNull(mapper.map(RealtimeSocketEvents.ThreadFollowersUpdated, """{"channelId":"parent"}"""))
        assertNull(mapper.map(RealtimeSocketEvents.ThreadFollowersUpdated, """{"threadChannelId":""}"""))
        // A roster update does not dirty the unrelated Home or unread projection.
        assertFalse(event.updatesHomeSnapshot())
        assertFalse(event.affectsActivityUnreadSummary())
    }
}
