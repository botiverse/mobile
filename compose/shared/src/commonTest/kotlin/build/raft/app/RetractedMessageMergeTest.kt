package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageDelegatedBy
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
import build.raft.app.thread.RaftThreadAttachmentItem
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadReactionItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadMessageStore
import build.raft.app.thread.mergePagedMessages
import build.raft.app.thread.mergeRealtimeUpdate
import build.raft.app.thread.preserveStableTimelineFrom
import build.raft.app.thread.toMessage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RFC 077 PR 2b (botiverse/slock `retractDelegatedMessage`): a retraction arrives as the full row
 * on `message:updated` with `deletedAt` set, `content` replaced by the placeholder, attachments
 * revoked and reactions deleted. It is authoritative over the sparse-patch fallbacks that keep a
 * loaded copy's attachments, and an older copy of the message never brings them back.
 */
class RetractedMessageMergeTest {
    private val friday = MessageDelegatedBy(id = "agent-friday", name = "Friday")
    private val placeholder = "This message was retracted by its sender."
    private val retractedAt = "2026-10-25T10:05:00Z"
    private val attachment = RaftThreadAttachmentItem(id = "att-1", name = "plan.pdf", mimeType = "application/pdf", commentCount = 2)
    private val reaction = RaftThreadReactionItem(emoji = "👍", count = 2, reactorIds = listOf("u-2", "u-3"))

    private fun row(id: String = "sent", seq: Long = 41, body: String = "body $id", deletedAt: String? = null) =
        RaftThreadMessageItem(
            id = id,
            authorId = "user-alice",
            authorName = "Alice",
            authorKind = "human",
            timestampLabel = "2026-10-25T10:00:00Z",
            seq = seq,
            body = body,
            delegatedBy = friday,
            deletedAt = deletedAt,
        )

    private fun loaded(id: String = "sent", seq: Long = 41) = row(id, seq).copy(attachments = listOf(attachment), reactions = listOf(reaction))

    private fun retracted(id: String = "sent", seq: Long = 41) = row(id, seq, body = placeholder, deletedAt = retractedAt)

    private fun payload(vararg messages: RaftThreadMessageItem) =
        RaftThreadScreenPayload(sourceThreadId = "channel-qa-x", status = "content", messages = messages.toList())

    private fun RaftThreadMessageItem.assertRetracted(case: String) {
        assertEquals(retractedAt, deletedAt, case)
        assertEquals(placeholder, body, case)
        assertEquals(emptyList(), attachments, "$case: attachments")
        assertEquals(emptyList(), reactions, "$case: reactions")
    }

    @Test
    fun realtimeRetractionDropsAttachmentsAndReactionsAnEmptyListOtherwiseKeepsThem() {
        // The retraction's empty lists are authoritative...
        loaded().mergeRealtimeUpdate(retracted()).assertRetracted("retracted update")
        // ...also over a reaction patch the store computed from the frame.
        loaded().mergeRealtimeUpdate(retracted(), reactionUpdate = listOf(reaction)).assertRetracted("with a reaction update")
        loaded().mergeRealtimeUpdate(retracted(), preserveExistingReactions = true).assertRetracted("preserving reactions")

        // A non-retracted sparse update with an empty list keeps the current merge behaviour.
        val edited = loaded().mergeRealtimeUpdate(row(body = "edited"))
        assertEquals("edited", edited.body)
        assertEquals(listOf(attachment), edited.attachments)
        assertEquals(listOf(reaction), edited.reactions)
        assertEquals(null, edited.deletedAt)
    }

    @Test
    fun aLaterSparseUpdateNeverUnRetracts() {
        val merged = retracted().mergeRealtimeUpdate(row(body = "").copy(attachments = listOf(attachment)))
        merged.assertRetracted("sparse update after the retraction")
    }

    @Test
    fun refreshPagingAndResumeNeverResurrectTheOldCopy() {
        // A refresh / restored copy from before the retraction (cache, durable last-known frame).
        payload(loaded(), row("next", 42))
            .preserveStableTimelineFrom(payload(retracted(), row("next", 42)))
            .messages.single { it.id == "sent" }.assertRetracted("refresh keeps the known retraction")
        // The refresh itself carries the retraction; the previous copy had attachments.
        payload(retracted(), row("next", 42))
            .preserveStableTimelineFrom(payload(loaded(), row("next", 42)))
            .messages.single { it.id == "sent" }.assertRetracted("refresh brings the retraction")
        // Paging keeps the loaded copy, but a retracted page copy replaces its content.
        payload(loaded())
            .mergePagedMessages(listOf(retracted(), row("next", 42)), before = false, pageSize = 50)
            .messages.single { it.id == "sent" }.assertRetracted("paged retraction")
    }

    @Test
    fun messageCacheNeverResurrectsTheOldCopy() {
        val channel = ChannelId("channel-qa-x")
        val store = SharedThreadMessageStore()
        val retractedMessage = retracted().toMessage(ThreadId("channel-qa-x"), channel)!!
        val oldMessage = loaded().toMessage(ThreadId("channel-qa-x"), channel)!!
        store.upsertScoped("server-1", channel, oldMessage)
        assertEquals(1, store.getScoped("server-1", channel, MessageId("sent"))?.attachments?.size)

        store.upsertScoped("server-1", channel, retractedMessage)
        val stale = store.upsertScoped("server-1", channel, oldMessage)

        assertEquals(retractedAt, stale.deletedAt)
        assertEquals(placeholder, stale.body)
        assertEquals(emptyList(), stale.attachments)
        assertEquals(emptyList(), stale.reactions.orEmpty())
    }
}
