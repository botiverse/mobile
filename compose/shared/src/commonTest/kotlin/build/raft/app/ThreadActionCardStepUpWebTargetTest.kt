package build.raft.app

import build.raft.app.data.SecondFactorStepUpWebTarget
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.actionCardStepUpWebTarget
import kotlin.test.Test
import kotlin.test.assertEquals

/** An action card's "Verify it's you" Open on the web: the card's conversation (web `/channel/<id>`, `/dm/<id>`). */
class ThreadActionCardStepUpWebTargetTest {
    @Test
    fun theCardsConversationIsTheChannelTheDmOrAReplysParent() {
        val cases =
            listOf(
                RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel, streamChannelId = "c-1") to
                    SecondFactorStepUpWebTarget.Conversation("c-1", directMessage = false),
                RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.DirectMessage, sourceThreadId = "d-1") to
                    SecondFactorStepUpWebTarget.Conversation("d-1", directMessage = true),
                RaftThreadScreenPayload(
                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                    streamChannelId = "thread-9",
                    parentChannelId = "d-2",
                    parentSurfaceKind = RaftThreadSurfaceKind.DirectMessage,
                ) to SecondFactorStepUpWebTarget.Conversation("d-2", directMessage = true),
                RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.ReplyThread, streamChannelId = "thread-9") to null,
                RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel) to null,
            )
        cases.forEach { (payload, expected) ->
            assertEquals(expected, payload.actionCardStepUpWebTarget(), "${payload.surfaceKind}")
        }
    }
}
