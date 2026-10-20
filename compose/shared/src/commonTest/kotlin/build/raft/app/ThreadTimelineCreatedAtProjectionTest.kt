package build.raft.app

import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.WireMessage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadTimelineCreatedAtProjectionTest {
    @Test
    fun threadRestProjectionCarriesNumericCreatedAtIntoTimelineItems() {
        val payload =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload =
                    RaftThreadScreenPayload(
                        sourceThreadId = "channel-1",
                        streamChannelId = "channel-1",
                    ),
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "message-1",
                                    seq = null,
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "user-1",
                                    senderName = "User",
                                    messageType = "chat",
                                    content = "hello",
                                    createdAt = "2026-07-25T14:00:00.000Z",
                                ),
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = false,
                    ),
                session = null,
            )

        assertEquals(1_784_988_000_000L, payload.messages.single().createdAtEpochMillis)
    }

    @Test
    fun threadItemProjectionNeverDerivesOrderingTimeFromDisplayLabel() {
        val message =
            Message(
                id = MessageId("message-without-created-at"),
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                author =
                    MemberProfile(
                        id = MemberId("user-1"),
                        displayName = "User",
                        handle = "user",
                        kind = MemberKind.Human,
                    ),
                timestampLabel = "2026-07-28T20:00:00.000Z",
                body = "hello",
                createdAtEpochMillis = null,
            )

        assertNull(message.toRaftThreadMessageItem(currentUserId = null).createdAtEpochMillis)
    }
}
