package build.raft.app

import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.LinkOpenMode
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.members.MembersDirectMessageEligibility
import build.raft.app.members.buildMembersDirectMessageThreadLaunchPayload
import build.raft.app.members.membersDirectMessageEligibility
import build.raft.app.members.openMembersReminderForceExternalFallback
import build.raft.app.members.toMembersReminderThreadLaunchPayload
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.shell.RAFT_SHELL_INSTANCE_ID
import build.raft.app.shell.RAFT_SHELL_SERVER_ID
import build.raft.app.shell.RaftLinkNavigator
import build.raft.app.shell.RaftLinkOpenTarget
import build.raft.app.shell.RaftLinkRejectReason
import build.raft.app.thread.RaftThreadWebLinkTarget
import build.raft.app.thread.toRaftThreadWebLinkTarget
import build.raft.app.thread.toThreadPageData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MembersDirectMessageLaunchTest {
    @Test
    fun humanSelfProfileKeepsMessageActionAvailable() {
        val member =
            MemberProfile(
                id = MemberId("user-self"),
                displayName = "Artin",
                handle = "artin",
                kind = MemberKind.Human,
                membershipStatus = "active",
                serverId = ServerId("server-1")
            )

        assertEquals(
            MembersDirectMessageEligibility.Self,
            membersDirectMessageEligibility(
                member = member,
                currentUserId = "user-self",
                currentServerId = "server-1"
            )
        )
    }

    @Test
    fun remoteOrRemovedHumanProfileDoesNotExposeMessageAction() {
        val member =
            MemberProfile(
                id = MemberId("user-partner"),
                displayName = "Partner",
                handle = "partner",
                kind = MemberKind.Human,
                membershipStatus = "active",
                serverId = ServerId("server-partner")
            )

        assertEquals(
            MembersDirectMessageEligibility.Unavailable,
            membersDirectMessageEligibility(
                member = member,
                currentUserId = "user-self",
                currentServerId = "server-1"
            )
        )
        assertEquals(
            MembersDirectMessageEligibility.Unavailable,
            membersDirectMessageEligibility(
                member = member.copy(
                    serverId = ServerId("server-1"),
                    membershipStatus = "removed"
                ),
                currentUserId = "user-self",
                currentServerId = "server-1"
            )
        )
    }

    @Test
    fun agentDetailMessageActionBuildsDirectMessageLaunchPayload() {
        val timeContext = RaftTimeContext(
            localOffsetMinutes = 480,
            todayEpochDay = 20_275L,
            nowEpochMillis = 1_756_963_200_000L,
            timeFormat = RaftTimeFormat12Hour
        )

        val payload =
            buildMembersDirectMessageThreadLaunchPayload(
                member =
                    MemberProfile(
                        id = MemberId("agent-product-ux"),
                        displayName = "Product UX Designer",
                        handle = "product-ux",
                        kind = MemberKind.Agent,
                        serverId = ServerId("server-1")
                    ),
                conversation = HomeDirectMessageRow(
                    threadId = ThreadId("dm-agent-product-ux-artin"),
                    channelId = ChannelId("dm-agent-product-ux-artin"),
                    name = "Product UX Designer"
                ),
                timeContext = timeContext
            )

        assertNotNull(payload)
        assertEquals("members-message-agent-product-ux-dm-agent-product-ux-artin", payload.instanceId)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.channelKind)
        assertEquals("dm-agent-product-ux-artin", payload.threadId)
        assertEquals("dm-agent-product-ux-artin", payload.sourceThreadId)
        assertEquals("dm-agent-product-ux-artin", payload.streamChannelId)
        assertEquals(MemberId("agent-product-ux"), payload.dmPeerIdentity?.memberId)
        assertEquals(MemberKind.Agent, payload.dmPeerIdentity?.kind)
        assertEquals(ServerId("server-1"), payload.dmPeerIdentity?.sourceServerId)
        assertNull(payload.parentChannelId)
        assertNull(payload.parentMessageId)
        assertEquals(480, payload.localOffsetMinutes)
        assertEquals(20_275L, payload.todayEpochDay)
        assertEquals(1_756_963_200_000L, payload.nowEpochMillis)
        assertEquals(RaftTimeFormat12Hour, payload.timeFormat)

        val pageData = payload.toThreadPageData()
        assertEquals("", pageData.optString(RAFT_SHELL_INSTANCE_ID))
        assertEquals("", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertEquals("", pageData.optString("dmPeerName"))
        assertEquals("", pageData.optString("dmPeerAvatar"))
    }

    @Test
    fun unrecognizedHttpReminderFallbackStaysExternalWhenInAppBrowserIsPreferred() {
        val url = "https://custom.raft.example/s/workspace/channel/channel-1?msg=message-1"
        var inAppOpenCount = 0
        var externallyOpenedUrl: String? = null
        var rejectedReason: RaftLinkRejectReason? = null
        val navigator =
            RaftLinkNavigator(
                modeProvider = { LinkOpenMode.InAppBrowser },
                openInAppBrowser = {
                    inAppOpenCount += 1
                    true
                },
                openExternalBrowser = { externallyOpenedUrl = it },
                onRejected = { rejectedReason = it },
            )

        assertNull(url.toRaftThreadWebLinkTarget())
        val decision = navigator.openMembersReminderForceExternalFallback(url)

        assertEquals(RaftLinkOpenTarget.ExternalBrowser, decision.target)
        assertEquals(url, externallyOpenedUrl)
        assertEquals(0, inAppOpenCount)
        assertNull(rejectedReason)
    }

    @Test
    fun membersReminderThreadPermalinkBuildsChannelReplyLaunchPayload() {
        val payload =
            requireNotNull(
                RaftThreadWebLinkTarget(
                    channelId = "channel-general",
                    messageId = "reply-message",
                    routeKind = RaftThreadSurfaceKind.Channel,
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
                                surfaceKind = RaftThreadSurfaceKind.Channel,
                                channelKind = RaftThreadSurfaceKind.Channel,
                                title = "general"
                            )
                        ).toSharedConversationDirectoryState(),
                    timeContext =
                        RaftTimeContext(
                            localOffsetMinutes = 480,
                            todayEpochDay = 20_275L,
                            nowEpochMillis = 1_756_963_200_000L,
                            timeFormat = RaftTimeFormat12Hour
                        )
                )
            )

        assertEquals("members-reminder-channel-general-reply-message", payload.instanceId)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.channelKind)
        assertEquals("channel-general", payload.parentChannelId)
        assertEquals("parent-message", payload.parentMessageId)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.parentChannelKind)
        assertEquals("parent-message", payload.originParentMessageId)
        assertEquals("reply-message", payload.focusedMessageId)
        assertEquals(480, payload.localOffsetMinutes)
        assertEquals(20_275L, payload.todayEpochDay)
        assertEquals(1_756_963_200_000L, payload.nowEpochMillis)
        assertEquals(RaftTimeFormat12Hour, payload.timeFormat)
    }

    @Test
    fun membersReminderDmThreadPermalinkPreservesDirectMessageParentKind() {
        val payload =
            requireNotNull(
                RaftThreadWebLinkTarget(
                    channelId = "dm-agent-artin",
                    messageId = "dm-reply",
                    routeKind = RaftThreadSurfaceKind.DirectMessage,
                    threadParentMessageId = "dm-parent-message"
                ).toMembersReminderThreadLaunchPayload(
                    messageId = "dm-reply",
                    server = ServerSummary(ServerId("server-1"), "Raft"),
                    conversationIdentityProvider = null,
                    timeContext = RaftTimeContext(localOffsetMinutes = 0)
                )
            )

        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.channelKind)
        assertEquals("dm-agent-artin", payload.parentChannelId)
        assertEquals("dm-parent-message", payload.parentMessageId)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.parentChannelKind)
        assertEquals("dm-parent-message", payload.originParentMessageId)
        assertEquals("dm-reply", payload.focusedMessageId)
    }
}
