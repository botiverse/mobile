package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task #77: the "Not notified" notice of web MessageInput (`message.composer.unresolvedMentions`).
 * The handles come from a send's `unresolvedMentionHandles` and live until the ×, the next send
 * or a composer switch.
 */
class ThreadComposerUnresolvedMentionsTest {
    private fun startedStore(instanceId: String = "composer-1", draftKey: String = "send=c1|parent=_|mention=c1") =
        SharedThreadComposerDraftStore().also { store ->
            store.dispatch(
                SharedThreadComposerDraftIntent.Start(
                    composer = RaftThreadComposerPayload(instanceId = instanceId),
                    draftKey = draftKey
                )
            )
        }

    @Test
    fun aCompletedSendShowsItsHandlesUntilDismissed() {
        val store = startedStore()
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "hi @ghost", seedAsTask = false))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend(unresolvedMentionHandles = listOf("@ghost", "@outside")))

        assertEquals(listOf("@ghost", "@outside"), store.state.value.unresolvedMentionHandles)

        store.dispatch(SharedThreadComposerDraftIntent.DismissUnresolvedMentions)

        assertEquals(emptyList(), store.state.value.unresolvedMentionHandles)
    }

    @Test
    fun theNextSendClearsTheNoticeAndACleanSendLeavesItCleared() {
        val store = startedStore()
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend(unresolvedMentionHandles = listOf("@ghost")))

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "again", seedAsTask = false))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        assertEquals(emptyList(), store.state.value.unresolvedMentionHandles, "web clears it as a send starts")

        // The admission-time CompleteSend (journal owns the draft) and the response's own.
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend(unresolvedMentionHandles = emptyList()))
        assertEquals(emptyList(), store.state.value.unresolvedMentionHandles)
    }

    @Test
    fun aRetriedBubbleSendAppliesItsHandlesToo() {
        val store = startedStore()

        store.dispatch(SharedThreadComposerDraftIntent.ApplyPendingMentionActions(unresolvedMentionHandles = listOf("@ghost")))

        assertEquals(listOf("@ghost"), store.state.value.unresolvedMentionHandles)
    }

    @Test
    fun switchingConversationsDropsTheNotice() {
        val store = startedStore()
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend(unresolvedMentionHandles = listOf("@ghost")))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-2"),
                draftKey = "send=c2|parent=_|mention=c2"
            )
        )

        assertEquals(emptyList(), store.state.value.unresolvedMentionHandles)
    }

    @Test
    fun theNoticeIsNeverPersistedWithTheDraft() {
        val store = startedStore()
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "draft", seedAsTask = false))
        store.dispatch(SharedThreadComposerDraftIntent.ApplyPendingMentionActions(unresolvedMentionHandles = listOf("@ghost")))
        store.dispatch(SharedThreadComposerDraftIntent.Flush)

        // A remount of the same conversation restores the text, not the last send's notice.
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-1b"),
                draftKey = "send=c1|parent=_|mention=c1"
            )
        )
        assertEquals(emptyList(), store.state.value.unresolvedMentionHandles)
    }

    @Test
    fun theHandlesReachTheComposerUiStateAndKeepItOffRest() {
        val payload = RaftThreadComposerPayload(instanceId = "c", unresolvedMentionHandles = listOf("@ghost"))
        val ui = payload.toThreadComposerUiState()

        assertEquals(listOf("@ghost"), ui.unresolvedMentionHandles)
        assertFalse(ui.isThreadComposerAtRest())
        assertTrue(ui.copy(unresolvedMentionHandles = emptyList()).isThreadComposerAtRest())
    }

    @Test
    fun theNoticeListsTheServerHandlesJoinedAsWebDoesAndIsHiddenInADm() {
        assertEquals("@ghost, @outside", threadComposerUnresolvedMentionHandlesLabel(listOf("@ghost", "@outside")))
        assertTrue(ThreadComposerSurface(channelId = "c1").showsUnresolvedMentionNotice())
        assertTrue(ThreadComposerSurface(kind = RaftThreadSurfaceKind.ReplyThread, channelId = "t1").showsUnresolvedMentionNotice())
        assertFalse(ThreadComposerSurface(kind = RaftThreadSurfaceKind.DirectMessage, channelId = "dm1").showsUnresolvedMentionNotice())
    }
}
