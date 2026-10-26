package build.raft.app

import build.raft.app.resources.*
import build.raft.app.model.MessageDelegatedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    fun consecutiveMessagesFromTheSameAssistantGroup() {
        assertEquals(
            listOf(false, true, true),
            continuations(alice("own-1", 40), alice("sent-1", 41, friday), alice("sent-2", 42, friday), alice("sent-3", 43, friday)),
        )
        // The owner's own messages still group with each other.
        assertEquals(listOf(true), continuations(alice("own-1", 40), alice("own-2", 41)))
    }

    @Test
    fun differentAssistantsStartTheirOwnRuns() {
        assertEquals(listOf(false, false), continuations(alice("f", 40, friday), alice("j", 41, jarvis), alice("f2", 42, friday)))
    }

    @Test
    fun aDeletedAssistantKeepsItsRunByIdentity() {
        // The key is the assistant's id (web `delegatedGroupKey`), not its name or deleted state.
        assertEquals(listOf(true), continuations(alice("f", 40, friday), alice("f-gone", 41, fridayDeleted)))
        assertEquals(listOf(false), continuations(alice("own", 40), alice("f-gone", 41, fridayDeleted)))
    }

    @Test
    fun compactSignatureChangesWithTheAssistant() {
        assertNotEquals(alice("a", 40).threadCompactAuthorSignature(), alice("a", 40, friday).threadCompactAuthorSignature())
        assertEquals(alice("a", 40, friday).threadCompactAuthorSignature(), alice("a", 40, fridayDeleted).threadCompactAuthorSignature())
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

    @Test
    fun onlyTheNameIsSplitOutOfTheLocalizedWords() {
        val marker = "\uE000"
        assertEquals(DelegatedLabelParts("sent by ", ""), splitDelegatedLabel("sent by $marker", marker))
        assertEquals(DelegatedLabelParts("（由 ", " 代发）"), splitDelegatedLabel("（由 $marker 代发）", marker))
        assertEquals(DelegatedLabelParts("", " が代理送信"), splitDelegatedLabel("$marker が代理送信", marker))
        assertEquals(DelegatedLabelParts("sent by a deleted assistant", ""), splitDelegatedLabel("sent by a deleted assistant", marker))
    }
}
