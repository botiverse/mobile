package build.raft.app

import build.raft.app.resources.*
import build.raft.app.thread.DelegatedLabelPlacement
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.delegatedLabelResource
import build.raft.app.thread.delegatedPreviewSenderResource
import build.raft.app.thread.shouldSuppressCompactThreadAuthor
import build.raft.app.model.MessageDelegatedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RFC 077 (assistant launch, task #6): grouping and the "sent by" words for messages a person's
 * assistant sent for them. Web source (botiverse/slock staging dcb95d0ac330414b77e8c6421cf96be114c9c52d):
 * `packages/web/src/components/message/messageGrouping.ts` `isSameSender` (sender type + id +
 * `delegatedGroupKey`), `delegatedMessage.ts`, `MessageItem.tsx` `DelegatedByLabel`.
 */
class ThreadDelegatedMessageTest {
    private val friday = MessageDelegatedBy(id = "agent-friday", name = "Friday")
    private val jarvis = MessageDelegatedBy(id = "agent-jarvis", name = "Jarvis")
    private val fridayDeleted = friday.copy(deleted = true)

    private fun alice(id: String, minute: Int, delegatedBy: MessageDelegatedBy? = null) =
        RaftThreadMessageItem(
            id = id,
            authorId = "user-alice",
            authorName = "Alice",
            authorKind = "human",
            timestampLabel = "2026-10-21T16:${minute.toString().padStart(2, '0')}:00Z",
            body = "body $id",
            delegatedBy = delegatedBy,
        )

    /** For each message after the first: does it continue the previous row (no avatar/name)? */
    private fun continuations(vararg messages: RaftThreadMessageItem): List<Boolean> =
        messages.toList().zipWithNext { previous, next ->
            shouldSuppressCompactThreadAuthor(previous, next, localOffsetMinutes = 0)
        }

    @Test
    fun delegatedMessagesNeverContinueTheOwnersOwnRun() {
        assertEquals(
            listOf(false, false),
            continuations(alice("own-1", 40), alice("sent-1", 41, friday), alice("own-2", 42)),
        )
    }

    @Test
    fun theWordsFollowWebDelegatedByLabel() {
        // (assistant, placement, catalog entry): web `message.delegated.sentBy[Deleted][InBody]`.
        val cases =
            listOf(
                Triple(friday, DelegatedLabelPlacement.Header, Res.string.message_delegated_sent_by),
                Triple(friday, DelegatedLabelPlacement.InBody, Res.string.message_delegated_sent_by_in_body),
                Triple(fridayDeleted, DelegatedLabelPlacement.Header, Res.string.message_delegated_sent_by_deleted),
                Triple(fridayDeleted, DelegatedLabelPlacement.InBody, Res.string.message_delegated_sent_by_deleted_in_body),
            )
        cases.forEach { (delegatedBy, placement, expected) ->
            assertEquals(expected, delegatedLabelResource(delegatedBy, placement), "$delegatedBy $placement")
        }
        // Previews (web `formatDelegatedSender`): the sender alone without an assistant.
        assertNull(delegatedPreviewSenderResource(null))
        assertEquals(Res.string.message_delegated_preview_sender, delegatedPreviewSenderResource(friday))
        assertEquals(Res.string.message_delegated_preview_sender_deleted, delegatedPreviewSenderResource(fridayDeleted))
    }
}
