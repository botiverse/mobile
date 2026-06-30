package build.raft.app

import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.SlockTimeFormat12Hour
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MembersDirectMessageLaunchTest {
    @Test
    fun agentDetailMessageActionBuildsDirectMessageLaunchPayload() {
        val timeContext = SlockTimeContext(
            localOffsetMinutes = 480,
            todayEpochDay = 20_275L,
            nowEpochMillis = 1_756_963_200_000L,
            timeFormat = SlockTimeFormat12Hour
        )

        val payload =
            buildMembersDirectMessageThreadLaunchPayload(
                memberId = MemberId("agent-product-ux"),
                conversation = HomeDirectMessageRow(
                    threadId = ThreadId("dm-agent-product-ux-artin"),
                    channelId = ChannelId("dm-agent-product-ux-artin"),
                    name = "Product UX Designer"
                ),
                timeContext = timeContext
            )

        assertNotNull(payload)
        assertEquals("members-message-agent-product-ux-dm-agent-product-ux-artin", payload.instanceId)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.channelKind)
        assertEquals("dm-agent-product-ux-artin", payload.threadId)
        assertEquals("dm-agent-product-ux-artin", payload.sourceThreadId)
        assertEquals("dm-agent-product-ux-artin", payload.streamChannelId)
        assertNull(payload.parentChannelId)
        assertNull(payload.parentMessageId)
        assertEquals(480, payload.localOffsetMinutes)
        assertEquals(20_275L, payload.todayEpochDay)
        assertEquals(1_756_963_200_000L, payload.nowEpochMillis)
        assertEquals(SlockTimeFormat12Hour, payload.timeFormat)
    }

    @Test
    fun agentDetailMessageActionFallsBackToChannelIdWhenThreadIdMissing() {
        val payload =
            buildMembersDirectMessageThreadLaunchPayload(
                memberId = MemberId("human-artin"),
                conversation = HomeDirectMessageRow(
                    threadId = ThreadId(""),
                    channelId = ChannelId("dm-human-artin"),
                    name = "artin"
                ),
                timeContext = SlockTimeContext(localOffsetMinutes = 0)
            )

        assertNotNull(payload)
        assertEquals("dm-human-artin", payload.threadId)
        assertEquals("dm-human-artin", payload.sourceThreadId)
        assertEquals("dm-human-artin", payload.streamChannelId)
    }
}
