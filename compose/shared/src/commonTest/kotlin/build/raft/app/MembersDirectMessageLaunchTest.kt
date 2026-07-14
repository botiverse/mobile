package build.raft.app

import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.SlockTimeFormat12Hour
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembersDirectMessageLaunchTest {
    @Test
    fun agentDetailUsesIconOnlyHeaderMessageAction() {
        assertTrue(membersHeaderMessageUsesIconOnly(MemberKind.Agent))
        assertFalse(membersHeaderMessageUsesIconOnly(MemberKind.Human))
        assertFalse(membersHeaderMessageUsesIconOnly(MemberKind.System))
    }

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

    @Test
    fun membersReminderThreadPermalinkBuildsChannelReplyLaunchPayload() {
        val payload =
            requireNotNull(
                SlockThreadWebLinkTarget(
                    channelId = "channel-general",
                    messageId = "reply-message",
                    routeKind = KuiklyThreadSurfaceKind.Channel,
                    threadParentMessageId = "parent-message"
                ).toMembersReminderThreadLaunchPayload(
                    messageId = "reply-message",
                    server = ServerSummary(ServerId("server-1"), "Raft"),
                    conversationIdentityProvider = null,
                    conversationDirectory =
                        listOf(
                            SharedConversationDirectoryProjection(
                                serverId = "server-1",
                                canonicalChannelId = "channel-general",
                                aliases = setOf("channel-general"),
                                conversationKind = ConversationKind.Channel,
                                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                                channelKind = KuiklyThreadSurfaceKind.Channel,
                                title = "general"
                            )
                        ).toSharedConversationDirectoryState(),
                    timeContext =
                        SlockTimeContext(
                            localOffsetMinutes = 480,
                            todayEpochDay = 20_275L,
                            nowEpochMillis = 1_756_963_200_000L,
                            timeFormat = SlockTimeFormat12Hour
                        )
                )
            )

        assertEquals("members-reminder-channel-general-reply-message", payload.instanceId)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.channelKind)
        assertEquals("channel-general", payload.parentChannelId)
        assertEquals("parent-message", payload.parentMessageId)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.parentChannelKind)
        assertEquals("parent-message", payload.originParentMessageId)
        assertEquals("reply-message", payload.focusedMessageId)
        assertEquals(480, payload.localOffsetMinutes)
        assertEquals(20_275L, payload.todayEpochDay)
        assertEquals(1_756_963_200_000L, payload.nowEpochMillis)
        assertEquals(SlockTimeFormat12Hour, payload.timeFormat)
    }

    @Test
    fun humanMemberReminderChannelPermalinkUsesRouteServerDirectory() {
        val payload =
            requireNotNull(
                SlockThreadWebLinkTarget(
                    channelId = "channel-general",
                    messageId = "channel-message"
                ).toMembersReminderThreadLaunchPayload(
                    messageId = "channel-message",
                    server = ServerSummary(ServerId("server-1"), "Raft"),
                    conversationIdentityProvider = null,
                    conversationDirectory =
                        listOf(
                            SharedConversationDirectoryProjection(
                                serverId = "server-1",
                                canonicalChannelId = "channel-general",
                                aliases = setOf("channel-general"),
                                conversationKind = ConversationKind.Channel,
                                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                                channelKind = KuiklyThreadSurfaceKind.Channel,
                                title = "general"
                            )
                        ).toSharedConversationDirectoryState(),
                    timeContext = SlockTimeContext(localOffsetMinutes = 0)
                )
            )

        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("channel-general", payload.sourceThreadId)
        assertEquals("channel-general", payload.streamChannelId)
        assertEquals("channel-message", payload.focusedMessageId)
    }

    @Test
    fun membersReminderDmThreadPermalinkPreservesDirectMessageParentKind() {
        val payload =
            requireNotNull(
                SlockThreadWebLinkTarget(
                    channelId = "dm-agent-artin",
                    messageId = "dm-reply",
                    routeKind = KuiklyThreadSurfaceKind.DirectMessage,
                    threadParentMessageId = "dm-parent-message"
                ).toMembersReminderThreadLaunchPayload(
                    messageId = "dm-reply",
                    server = ServerSummary(ServerId("server-1"), "Raft"),
                    conversationIdentityProvider = null,
                    timeContext = SlockTimeContext(localOffsetMinutes = 0)
                )
            )

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.channelKind)
        assertEquals("dm-agent-artin", payload.parentChannelId)
        assertEquals("dm-parent-message", payload.parentMessageId)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.parentChannelKind)
        assertEquals("dm-parent-message", payload.originParentMessageId)
        assertEquals("dm-reply", payload.focusedMessageId)
    }

    @Test
    fun membersReminderDmPermalinkBuildsDirectMessageLaunchPayload() {
        val payload =
            requireNotNull(
                SlockThreadWebLinkTarget(
                    channelId = "dm-agent-artin",
                    messageId = "dm-message",
                    routeKind = KuiklyThreadSurfaceKind.DirectMessage
                ).toMembersReminderThreadLaunchPayload(
                    messageId = "dm-message",
                    server = ServerSummary(ServerId("server-1"), "Raft"),
                    conversationIdentityProvider = null,
                    timeContext = SlockTimeContext(localOffsetMinutes = 0)
                )
            )

        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.channelKind)
        assertEquals("dm-agent-artin", payload.threadId)
        assertEquals("dm-agent-artin", payload.sourceThreadId)
        assertEquals("dm-agent-artin", payload.streamChannelId)
        assertNull(payload.parentChannelId)
        assertNull(payload.parentMessageId)
        assertEquals("dm-message", payload.focusedMessageId)
    }
}
