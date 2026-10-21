package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageDelegatedBy
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RFC 077 / web #10751: a known `delegatedBy` is never replaced by a copy of the same message
 * without it (a catch-up or re-fetched copy from a producer that omitted the field, slock #10752);
 * an explicit newer label, `deleted = true` included, still wins.
 */
class DelegatedLabelMergeTest {
    private val friday = MessageDelegatedBy(id = "agent-friday", name = "Friday")
    private val fridayDeleted = friday.copy(deleted = true)

    private fun row(id: String, minute: Int, delegatedBy: MessageDelegatedBy?) =
        RaftThreadMessageItem(
            id = id,
            authorId = "user-alice",
            authorName = "Alice",
            authorKind = "human",
            timestampLabel = "2026-10-21T16:${minute.toString().padStart(2, '0')}:00Z",
            seq = minute.toLong(),
            body = "body $id",
            delegatedBy = delegatedBy,
        )

    private fun payload(vararg messages: RaftThreadMessageItem) =
        RaftThreadScreenPayload(sourceThreadId = "channel-qa-x", status = "content", messages = messages.toList())

    /** (case, catch-up copy's label, expected label after the merge). */
    private val catchUpCases =
        listOf(
            Triple("catch-up copy without the field keeps the realtime label", null, friday),
            Triple("catch-up copy with a deleted label wins", fridayDeleted, fridayDeleted),
        )

    @Test
    fun catchUpRefreshKeepsTheRealtimeLabel() {
        catchUpCases.forEach { (name, incoming, expected) ->
            val realtime = payload(row("own", 40, null), row("sent", 41, friday))
            val catchUp = payload(row("own", 40, null), row("sent", 41, incoming))
            val merged = catchUp.preserveStableTimelineFrom(realtime)
            assertEquals(expected, merged.messages.single { it.id == "sent" }.delegatedBy, name)
            assertNull(merged.messages.single { it.id == "own" }.delegatedBy, name)
        }
    }

    @Test
    fun pagedMergeKeepsTheLoadedLabel() {
        catchUpCases.forEach { (name, incoming, expected) ->
            val merged =
                payload(row("sent", 41, friday))
                    .mergePagedMessages(listOf(row("sent", 41, incoming), row("next", 42, null)), before = false, pageSize = 50)
            assertEquals(expected, merged.messages.single { it.id == "sent" }.delegatedBy, name)
        }
    }

    @Test
    fun realtimeAndMessageCacheKeepTheLabel() {
        catchUpCases.forEach { (name, incoming, expected) ->
            assertEquals(expected, keepKnownDelegatedBy(incoming, friday), name)

            val store = SharedThreadMessageStore()
            val labelled = row("sent", 41, friday).toMessage(ThreadId("channel-qa-x"), ChannelId("channel-qa-x"))!!
            store.upsertScoped("server-1", ChannelId("channel-qa-x"), labelled)
            val stored = store.upsertScoped("server-1", ChannelId("channel-qa-x"), labelled.copy(delegatedBy = incoming))
            assertEquals(expected, stored.delegatedBy, name)
            assertEquals(expected, store.getScoped("server-1", ChannelId("channel-qa-x"), MessageId("sent"))?.delegatedBy, name)
        }
    }

    private fun activityRow(latestMessageId: String, delegatedBy: MessageDelegatedBy?) =
        InboxItem(
            key = "channel:channel-qa-x",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("channel-qa-x"),
            channelName = "qa-x",
            lastMessageId = MessageId(latestMessageId),
            latestDelegatedBy = delegatedBy,
        )

    @Test
    fun activityAndSearchRowsKeepTheLabelOfTheSameMessage() {
        catchUpCases.forEach { (name, incoming, expected) ->
            val merged = listOf(activityRow("sent", incoming)).withKnownLatestDelegatedLabelsFrom(listOf(activityRow("sent", friday)))
            assertEquals(expected, merged.single().latestDelegatedBy, name)
        }
        // The row moved on to another message: its own (absent) label is the truth.
        val movedOn = listOf(activityRow("newer", null)).withKnownLatestDelegatedLabelsFrom(listOf(activityRow("sent", friday)))
        assertNull(movedOn.single().latestDelegatedBy)
    }
}
