package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.PlatformFile
import build.raft.app.data.SettingsHandsFeedbackDraftStore
import build.raft.app.settings.SettingsHandsFeedbackEditedGroupCommitResult
import build.raft.app.settings.SettingsHandsFeedbackImageEditEvent
import build.raft.app.settings.SettingsHandsFeedbackImageEditFoldResult
import build.raft.app.settings.SettingsHandsFeedbackImageEditSession
import build.raft.app.settings.commitSettingsHandsFeedbackEditedGroup
import build.raft.app.settings.foldSettingsHandsFeedbackImageEditEvent
import build.raft.app.settings.parseSettingsHandsFeedbackImageEditEvent
import build.raft.app.settings.promoteSettingsHandsFeedbackEditedAttachments
import build.raft.app.settings.settingsHandsFeedbackMergedAttachments
import build.raft.app.settings.settingsHandsFeedbackPickedImageToAnnotate
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class SettingsHandsFeedbackImageEditTest {
    @Test
    fun identicalReplayIsIdempotentAndStillAllowsOneCompleteGroup() {
        val firstEvent = event(index = 0, total = 2)
        val afterFirst = assertPending(fold(session(expectedCount = 2), firstEvent))
        val afterReplay = assertPending(fold(afterFirst, firstEvent))

        assertEquals(afterFirst.filesByIndex, afterReplay.filesByIndex)
        assertTrue(afterReplay.groupState.rejects.isEmpty())
        val complete =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Complete>(
                fold(afterReplay, event(index = 1, total = 2))
            )
        assertEquals(2, complete.files.size)
    }

    @Test
    fun crossScopeEventsAreIgnoredAndWrongGroupIsRejected() {
        val session = session(expectedCount = 2)
        val validEvent = event(index = 0, total = 2)

        assertIs<SettingsHandsFeedbackImageEditFoldResult.Ignored>(
            foldSettingsHandsFeedbackImageEditEvent(
                session,
                validEvent,
                currentAccountId = "different-account",
                currentPageGeneration = session.pageGeneration
            )
        )
        assertIs<SettingsHandsFeedbackImageEditFoldResult.Ignored>(
            foldSettingsHandsFeedbackImageEditEvent(
                session,
                validEvent,
                currentAccountId = session.accountId,
                currentPageGeneration = "different-page"
            )
        )
        assertIs<SettingsHandsFeedbackImageEditFoldResult.Ignored>(
            fold(session, validEvent.copy(threadKey = "feedback-other-group"))
        )
        val wrongGroup =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Failed>(
                fold(session, validEvent.copy(groupId = "other-group"))
            )
        assertEquals(RaftImageEditGroupReconcile.REJECT_WRONG_GROUP, wrongGroup.reason)
    }

    @Test
    fun malformedOrNonCompleteEventsCannotEnterAGroup() {
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson().put("action", "replace")))
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson().put("actionId", "")))
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson().put("groupId", "")))
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson().put("fileRef", "")))

        // Finalization metadata is a strict wire contract. Missing, non-Boolean, unknown, and
        // source/MIME-mismatched markers must never be normalized into a promotable PNG/JPEG.
        assertNull(
            parseSettingsHandsFeedbackImageEditEvent(
                eventJson(includeSource = false)
            )
        )
        assertNull(
            parseSettingsHandsFeedbackImageEditEvent(
                eventJson(includeCompressed = false)
            )
        )
        assertNull(
            parseSettingsHandsFeedbackImageEditEvent(
                eventJson().apply { put("compressed", "false") }
            )
        )
        assertNull(
            parseSettingsHandsFeedbackImageEditEvent(
                eventJson().apply { put("source", "unknown-editor") }
            )
        )
        assertNull(
            parseSettingsHandsFeedbackImageEditEvent(
                eventJson().apply {
                    put("source", RAFT_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE)
                    put("compressed", true)
                }
            )
        )

        // The shared parser owns group bounds now; malformed totals are rejected before the
        // Settings fold/reconciliation layer can observe them.
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson(total = 0)))
    }

    @Test
    fun editedFileRefsPersistInOriginalOrderAcrossDraftStoreRecreation() {
        val preferences = InMemoryRaftKeyValueStore()
        val initial = session(expectedCount = 2)
        val afterOne = assertPending(fold(initial, event(index = 1, total = 2)))
        val complete =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Complete>(
                fold(afterOne, event(index = 0, total = 2))
            )
        val promotion =
            promoteSettingsHandsFeedbackEditedAttachments(
                existing = emptyList(),
                edited =
                    complete.files.map { file ->
                        file.copy(source = "settings_feedback_draft")
                    },
                attachmentMaxBytes = 10_000,
                attachmentLimit = 9
            )
        SettingsHandsFeedbackDraftStore(preferences).save(
            accountId = "account-a",
            text = "Edited screenshots attached",
            attachments = promotion.attachments
        )

        val restored = SettingsHandsFeedbackDraftStore(preferences).read("account-a")

        assertEquals(
            listOf("file:///edited/0.png", "file:///edited/1.png"),
            restored.attachments.map { it.fileRef }
        )
        assertEquals(
            listOf("settings_feedback_draft", "settings_feedback_draft"),
            restored.attachments.map { it.source }
        )
    }

    @Test
    fun attachmentAddedWhilePromotionIsSuspendedIsMergedFromCommitTimeDraftState() = runBlocking {
        val existing = image("existing", "file:///draft/existing.png")
        val concurrent = image("concurrent", "file:///draft/concurrent.pdf")
        val durable =
            image("edited", "file:///durable/edited.png")
                .copy(source = "settings_feedback_draft")
        val promotionStarted = CompletableDeferred<Unit>()
        val finishPromotion = CompletableDeferred<Unit>()
        var currentAttachments = listOf(existing)
        var persisted = emptyList<PlatformFile>()

        val pendingCommit =
            async {
                commitSettingsHandsFeedbackEditedGroup(
                    existing = currentAttachments,
                    edited = listOf(image("edited", "file:///editor-temp.png")),
                    attachmentMaxBytes = 1_000,
                    attachmentLimit = 9,
                    isCurrent = { true },
                    promote = {
                        promotionStarted.complete(Unit)
                        finishPromotion.await()
                        DataResult.Content(listOf(durable))
                    },
                    persist = { persisted = it },
                    currentExisting = { currentAttachments }
                )
            }

        promotionStarted.await()
        currentAttachments = currentAttachments + concurrent
        finishPromotion.complete(Unit)

        val committed =
            assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Committed>(pendingCommit.await())
        val expected = listOf(existing, concurrent, durable)
        assertEquals(expected, committed.promotion.attachments)
        assertEquals(expected, persisted)
    }

    @Test
    fun attachmentRemovedWhilePromotionIsSuspendedIsNotRestoredByTheCommit() = runBlocking {
        val kept = image("kept", "file:///draft/kept.png")
        val removed = image("removed", "file:///draft/removed.png")
        val durable =
            image("edited", "file:///durable/edited.png")
                .copy(source = "settings_feedback_draft")
        val promotionStarted = CompletableDeferred<Unit>()
        val finishPromotion = CompletableDeferred<Unit>()
        var currentAttachments = listOf(kept, removed)
        var persisted = emptyList<PlatformFile>()

        val pendingCommit =
            async {
                commitSettingsHandsFeedbackEditedGroup(
                    existing = currentAttachments,
                    edited = listOf(image("edited", "file:///editor-temp.png")),
                    attachmentMaxBytes = 1_000,
                    attachmentLimit = 9,
                    isCurrent = { true },
                    promote = {
                        promotionStarted.complete(Unit)
                        finishPromotion.await()
                        DataResult.Content(listOf(durable))
                    },
                    persist = { persisted = it },
                    currentExisting = { currentAttachments }
                )
            }

        promotionStarted.await()
        currentAttachments = listOf(kept)
        finishPromotion.complete(Unit)

        val committed =
            assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Committed>(pendingCommit.await())
        assertEquals(listOf(kept, durable), committed.promotion.attachments)
        assertEquals(listOf(kept, durable), persisted)
        assertFalse(persisted.contains(removed))
    }

    @Test
    fun draftClearedWhilePromotionIsSuspendedFailsStaleAndNeverOverwritesTheClear() = runBlocking {
        val existing = image("existing", "file:///draft/existing.png")
        val durable =
            image("edited", "file:///durable/edited.png")
                .copy(source = "settings_feedback_draft")
        val promotionStarted = CompletableDeferred<Unit>()
        val finishPromotion = CompletableDeferred<Unit>()
        var currentAttachments = listOf(existing)
        var draftResetGeneration = 7L
        val initialDraftResetGeneration = draftResetGeneration
        var persistCount = 0
        var discarded = emptyList<PlatformFile>()

        val pendingCommit =
            async {
                commitSettingsHandsFeedbackEditedGroup(
                    existing = currentAttachments,
                    edited = listOf(image("edited", "file:///editor-temp.png")),
                    attachmentMaxBytes = 1_000,
                    attachmentLimit = 9,
                    isCurrent = { true },
                    promote = {
                        promotionStarted.complete(Unit)
                        finishPromotion.await()
                        DataResult.Content(listOf(durable))
                    },
                    persist = { persistCount += 1 },
                    currentExisting = { currentAttachments },
                    initialDraftResetGeneration = initialDraftResetGeneration,
                    currentDraftResetGeneration = { draftResetGeneration },
                    discard = { discarded = it }
                )
            }

        promotionStarted.await()
        currentAttachments = emptyList()
        draftResetGeneration += 1L
        finishPromotion.complete(Unit)

        assertEquals(
            "settings_feedback_attachment_session_stale",
            assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Failed>(pendingCommit.await()).code
        )
        assertEquals(0, persistCount)
        assertEquals(listOf(durable), discarded)
        assertTrue(currentAttachments.isEmpty())
    }

    @Test
    fun feedbackImagePickMountsThenOpensTheFirstNewImageInTheEditor() {
        // The pick is attached first (so cancelling the editor keeps it), then its first new image
        // opens in the annotation editor; images already in the form are not reopened.
        val existing = image("existing", "file:///draft/existing.jpg")
        val picked =
            listOf(
                image("photo-1", "file:///picked/1.jpg"),
                image("photo-2", "file:///picked/2.jpg")
            )
        val merge =
            settingsHandsFeedbackMergedAttachments(
                existing = listOf(existing),
                picked = picked,
                attachmentMaxBytes = 1_000,
                attachmentLimit = 3
            )
        assertEquals(
            listOf("file:///draft/existing.jpg") + picked.map { it.fileRef },
            merge.attachments.map { it.fileRef }
        )
        assertEquals(
            "file:///picked/1.jpg",
            settingsHandsFeedbackPickedImageToAnnotate(
                existing = listOf(existing),
                merged = merge.attachments
            )?.fileRef
        )
    }

    @Test
    fun feedbackAttachmentMergeFiltersOversizeAndTrimsToLimit() {
        val ok = image("ok", "file:///picked/ok.jpg").copy(sizeBytes = 100L)
        val tooBig = image("big", "file:///picked/big.jpg").copy(sizeBytes = 2_000L)
        val merge =
            settingsHandsFeedbackMergedAttachments(
                existing = emptyList(),
                picked = listOf(ok, tooBig),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 3
            )
        assertEquals(listOf("file:///picked/ok.jpg"), merge.attachments.map { it.fileRef })
        assertEquals(1, merge.rejectedCount)
        assertFalse(merge.limitTrimmed)

        val existing = image("existing", "file:///draft/existing.jpg")
        val trimmed =
            settingsHandsFeedbackMergedAttachments(
                existing = listOf(existing),
                picked = (1..4).map { image("p$it", "file:///picked/$it.jpg") },
                attachmentMaxBytes = 1_000,
                attachmentLimit = 3
            )
        assertEquals(3, trimmed.attachments.size)
        assertEquals("file:///draft/existing.jpg", trimmed.attachments.first().fileRef)
        assertTrue(trimmed.limitTrimmed)

        val dup = existing.copy(id = "different-id")
        val deduped =
            settingsHandsFeedbackMergedAttachments(
                existing = listOf(existing),
                picked = listOf(dup, image("new", "file:///picked/new.jpg")),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 3
            )
        assertEquals(
            listOf("file:///draft/existing.jpg", "file:///picked/new.jpg"),
            deduped.attachments.map { it.fileRef }
        )
        assertFalse(deduped.limitTrimmed)
    }

    @Test
    fun replaceCommitSwapsTargetInPlaceAndKeepsOrder() = runBlocking {
        val before = image("before", "file:///draft/before.png")
        val target = image("target", "file:///draft/target.png")
        val after = image("after", "file:///draft/after.png")
        val edited =
            image("edited", "file:///editor-temp/edited.png")
                .copy(source = "settings_feedback_draft")
        var currentAttachments = listOf(before, target, after)
        var persisted = emptyList<PlatformFile>()

        val result =
            commitSettingsHandsFeedbackEditedGroup(
                existing = currentAttachments,
                edited = listOf(edited),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 3,
                replaceAttachmentId = "file:///draft/target.png",
                isCurrent = { true },
                promote = { files -> DataResult.Content(files.map { it.copy(source = "settings_feedback_draft") }) },
                persist = { persisted = it },
                currentExisting = { currentAttachments }
            )

        val committed = assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Committed>(result)
        assertEquals(
            listOf("file:///draft/before.png", "file:///editor-temp/edited.png", "file:///draft/after.png"),
            committed.promotion.attachments.map { it.fileRef }
        )
        assertEquals(committed.promotion.attachments, persisted)
    }

    private fun session(expectedCount: Int): SettingsHandsFeedbackImageEditSession =
        SettingsHandsFeedbackImageEditSession(
            groupId = "group-a",
            threadKey = "feedback-group-a",
            accountId = "account-a",
            pageGeneration = "page-generation-a",
            expectedCount = expectedCount
        )

    private fun event(
        index: Int,
        total: Int
    ): SettingsHandsFeedbackImageEditEvent =
        SettingsHandsFeedbackImageEditEvent(
            threadKey = "feedback-group-a",
            actionId = "action-$index",
            groupId = "group-a",
            index = index,
            total = total,
            source = RAFT_FILE_PREVIEW_IMAGE_EDITOR_SOURCE,
            compressed = false,
            file = image("edited-$index", "file:///edited/$index.png")
        )

    private fun eventJson(
        index: Int = 0,
        total: Int = 1,
        includeSource: Boolean = true,
        includeCompressed: Boolean = true
    ): JSONObject =
        JSONObject().apply {
            put("previewSessionId", "feedback-preview-group-a")
            put("threadKey", "feedback-group-a")
            put("actionId", "action-$index")
            put("action", "complete")
            put("groupId", "group-a")
            put("groupIndex", index)
            put("groupTotal", total)
            put("fileRef", "file:///edited/$index.png")
            put("fileName", "edited-$index.png")
            put("mimeType", "image/png")
            put("sizeBytes", 100L)
            put("previewUri", "file:///edited/$index-preview.png")
            if (includeSource) put("source", RAFT_FILE_PREVIEW_IMAGE_EDITOR_SOURCE)
            if (includeCompressed) put("compressed", false)
        }

    private fun fold(
        session: SettingsHandsFeedbackImageEditSession,
        event: SettingsHandsFeedbackImageEditEvent
    ): SettingsHandsFeedbackImageEditFoldResult =
        foldSettingsHandsFeedbackImageEditEvent(
            session = session,
            event = event,
            currentAccountId = session.accountId,
            currentPageGeneration = session.pageGeneration
        )

    private fun assertPending(
        result: SettingsHandsFeedbackImageEditFoldResult
    ): SettingsHandsFeedbackImageEditSession =
        assertIs<SettingsHandsFeedbackImageEditFoldResult.Pending>(result).session

    private fun image(
        id: String,
        fileRef: String,
        sizeBytes: Long = 100
    ): PlatformFile =
        PlatformFile(
            id = id,
            fileName = "$id.png",
            mimeType = "image/png",
            sizeBytes = sizeBytes,
            previewUri = "$fileRef-preview",
            fileRef = fileRef,
            source = RAFT_FILE_PREVIEW_IMAGE_EDITOR_SOURCE
        )
}
