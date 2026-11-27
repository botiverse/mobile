package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.dispatch
import build.raft.app.thread.withThreadComposerDraftState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The composer tray's drag reorder (web MessageInput `handleAttachmentDragEnd`: `arrayMove` of
 * `pendingFiles`, whose order is the send's `attachmentIds`): the store keeps the new order, the
 * draft persists it, and the send's ids follow it.
 */
class SharedThreadComposerDraftStoreReorderTest {
    private val draftKey = "send=channel-1|parent=_|mention=channel-1"

    private fun ready(id: String) = SharedThreadComposerAttachment(id, "$id.png", mimeType = "image/png", status = "ready")

    private fun uploading(id: String) =
        SharedThreadComposerAttachment(id, "$id.pdf", mimeType = "application/pdf", status = "uploading", fileRef = "content://$id")

    private fun SharedThreadComposerDraftStore.ids() = state.value.uploadedAttachments.map { it.id }

    private fun startedStore(
        persistence: SharedThreadComposerDraftPersistence? = null,
        vararg attachments: SharedThreadComposerAttachment,
    ): SharedThreadComposerDraftStore {
        val store =
            SharedThreadComposerDraftStore(
                persistSlices = listOfNotNull(persistence?.let(::SharedThreadComposerDraftPersistSlice))
            )
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        if (attachments.isNotEmpty()) store.dispatch(SharedThreadComposerDraftIntent.AddUploadedAttachments(attachments.toList()))
        return store
    }

    @Test
    fun reorderTakesTheTraysOrderAndAnUploadingTileMovesToo() {
        val store = startedStore(null, ready("a"), ready("b"))
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(uploading("picked-c"))))
        val syncBefore = store.state.value.syncNonce

        store.dispatch(SharedThreadComposerDraftIntent.ReorderAttachments(listOf("picked-c", "a", "b")))

        assertEquals(listOf("picked-c", "a", "b"), store.ids())
        assertEquals(syncBefore + 1, store.state.value.syncNonce, "the host re-syncs to the new order")
    }

    @Test
    fun removedUnknownAndUnnamedRowsKeepTheirSlots() {
        val store = startedStore(null, ready("a"), ready("gone"), ready("b"), ready("c"))
        store.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment("gone"))

        // A tray a frame behind: it never saw "c", and names an id the draft doesn't hold.
        store.dispatch(SharedThreadComposerDraftIntent.ReorderAttachments(listOf("b", "ghost", "a", "gone")))

        assertEquals(listOf("b", "gone", "a", "c"), store.ids())
        assertEquals(setOf("gone"), store.state.value.removedAttachmentIds)
    }

    @Test
    fun aMovedUploadKeepsItsNewSlotWhenItFinishes() {
        val store = startedStore(null, ready("a"))
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(uploading("picked-b"))))
        store.dispatch(SharedThreadComposerDraftIntent.ReorderAttachments(listOf("picked-b", "a")))

        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(ready("remote-b")),
                replacedAttachmentIds = setOf("picked-b"),
                replacedAttachmentIdsByUploadedId = mapOf("remote-b" to "picked-b")
            )
        )

        assertEquals(listOf("remote-b", "a"), store.ids())
    }

    @Test
    fun theNewOrderPersistsWithTheDraft() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val store = startedStore(persistence, ready("a"), ready("b"), ready("c"))

        store.dispatch(SharedThreadComposerDraftIntent.ReorderAttachments(listOf("c", "a", "b")))

        assertEquals(listOf("c", "a", "b"), persistence.read(draftKey)?.uploadedAttachments?.map { it.id })
        val reopened = startedStore(persistence)
        assertEquals(listOf("c", "a", "b"), reopened.ids())
    }

    @Test
    fun theSendCarriesTheAttachmentIdsInTheNewOrder() {
        val store = startedStore(null, ready("a"), ready("b"), ready("c"))
        store.dispatch(SharedThreadComposerDraftIntent.ReorderAttachments(listOf("b", "c", "a")))

        // performComposerSend reads the composer payload projected from the draft store.
        val composer =
            RaftThreadComposerPayload(instanceId = "composer-1").withThreadComposerDraftState(
                draftState = store.state.value,
                renderedPayload = RaftThreadScreenPayload(),
                resolvedAuxiliaryThreadId = null
            )

        assertEquals(listOf("b", "c", "a"), composer.pendingAttachments.map { it.id })
        assertEquals(listOf("b", "c", "a"), composer.pendingAttachments.readyThreadAttachmentIdsForSend())
    }
}
