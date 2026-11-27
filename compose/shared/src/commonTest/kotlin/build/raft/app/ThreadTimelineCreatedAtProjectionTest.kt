package build.raft.app

import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.WireMessage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.thread.RaftThreadScreenPayload
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
