package build.raft.app

import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadMessageDeliverySending
import build.raft.app.thread.ThreadTransientLocalSendIdPrefix
import build.raft.app.thread.mergePagedMessages
import build.raft.app.thread.withNewerServerCopiesFrom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * #proj-mobile:#198: a refreshed page (the resume catch-up, paging) carries the server's newer
 * copy of a message already on screen; it replaces the loaded copy like a live update (web
 * messageStore `mergeIncomingMessage`), so an App card rewritten while the app was in the
 * background shows its new revision. An older card revision never replaces a newer one held.
 */
class PagedMergeNewerServerCopyTest {
    private fun row(id: String, seq: Long, body: String = "body $id", revision: Int? = null) =
        RaftThreadMessageItem(
            id = id,
            authorId = "app-stamp",
            authorName = "Stamp",
            authorKind = "agent",
            timestampLabel = "2026-11-20T01:00:00Z",
            seq = seq,
            body = body,
            metadata =
                revision?.let {
                    mapOf("messageCard.card" to """{"header":"r$it"}""", "messageCard.revision" to "$it")
                }.orEmpty(),
        )

    private fun payload(vararg messages: RaftThreadMessageItem) =
        RaftThreadScreenPayload(sourceThreadId = "channel-qa-x", status = "content", messages = messages.toList())

    private fun RaftThreadScreenPayload.card(id: String) = messages.single { it.id == id }.metadata["messageCard.revision"]

    @Test
    fun aNewerCardRevisionOnTheCatchUpPageReplacesTheLoadedCard() {
        val merged =
            payload(row("card", 41, revision = 1), row("prev", 40))
                .mergePagedMessages(listOf(row("card", 41, revision = 2), row("next", 42)), before = false, pageSize = 50)
        assertEquals("2", merged.card("card"))
        assertEquals("""{"header":"r2"}""", merged.messages.single { it.id == "card" }.metadata["messageCard.card"])
        assertEquals(listOf("prev", "card", "next"), merged.messages.map { it.id })
    }

    @Test
    fun anOlderCardRevisionNeverReplacesTheNewerOneHeld() {
        val merged =
            payload(row("card", 41, revision = 3))
                .mergePagedMessages(listOf(row("card", 41, revision = 2)), before = false, pageSize = 50)
        assertEquals("3", merged.card("card"))
    }

    @Test
    fun aLocalSendKeepsItsOwnRow() {
        val sending =
            row("${ThreadTransientLocalSendIdPrefix}1", 0, body = "typed").copy(seq = null, deliveryState = ThreadMessageDeliverySending)
        val loaded = listOf(sending)
        assertSame(loaded, loaded.withNewerServerCopiesFrom(listOf(sending.copy(body = "other"))))
    }
}
