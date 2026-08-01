package build.raft.app

import build.raft.app.data.InMemorySlockKeyValueStore
import build.raft.app.data.PlatformFile
import build.raft.app.data.SettingsHandsFeedbackDraftStore
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsHandsFeedbackImageEditTest {
    @Test
    fun requestCarriesExplicitScopedGroupAndEditableGalleryIntent() {
        val files =
            listOf(
                image("original-0", "file:///picked/zero.png"),
                image("original-1", "file:///picked/one.png")
            )

        val request =
            settingsHandsFeedbackImageEditRequest(
                accountId = "account-a",
                pageGeneration = "page-generation-a",
                files = files,
                groupId = "group-a"
            )!!

        assertEquals("group-a", request.session.groupId)
        assertEquals("feedback-group-a", request.session.threadKey)
        assertEquals("account-a", request.session.accountId)
        assertEquals("page-generation-a", request.session.pageGeneration)
        assertEquals(2, request.session.expectedCount)
        assertEquals("feedback-group-a", request.previewPayload.composerImageThreadKey)
        assertEquals("feedback-image-group-a", request.previewPayload.composerImageActionId)
        assertEquals("group-a", request.previewPayload.composerImageGroupId)
        assertEquals("file:///picked/zero.png", request.previewPayload.composerImageFileRef)
        assertEquals(
            listOf("file:///picked/zero.png", "file:///picked/one.png"),
            request.previewPayload.imageGallery.map { it.composerImageFileRef }
        )
        assertTrue(request.previewPayload.composerImageCanComplete)
        assertTrue(request.previewPayload.composerImageCanEdit)
    }

    @Test
    fun requestRejectsMissingSessionOrDurablePickerInputs() {
        val validFile = image("original", "file:///picked/original.png")

        assertNull(settingsHandsFeedbackImageEditRequest("account", "", listOf(validFile), "group"))
        assertNull(settingsHandsFeedbackImageEditRequest("account", "page", emptyList(), "group"))
        assertNull(settingsHandsFeedbackImageEditRequest("account", "page", listOf(validFile), ""))
        assertNull(
            settingsHandsFeedbackImageEditRequest(
                "account",
                "page",
                listOf(validFile.copy(fileRef = null)),
                "group"
            )
        )
    }

    @Test
    fun generatedEditIdsAreUniqueAcrossRequests() {
        var next = 0L

        val first = newSettingsHandsFeedbackImageEditId { ++next }
        val second = newSettingsHandsFeedbackImageEditId { ++next }

        assertNotEquals(first, second)
        assertEquals("feedback-image-1-2", first)
        assertEquals("feedback-image-3-4", second)
    }

    @Test
    fun groupedSingleImageCompletesOnlyAtOneOfOne() {
        val session = session(expectedCount = 1)
        val parsed = parseSettingsHandsFeedbackImageEditEvent(eventJson(index = 0, total = 1))!!

        val result =
            foldSettingsHandsFeedbackImageEditEvent(
                session = session,
                event = parsed,
                currentAccountId = session.accountId,
                currentPageGeneration = session.pageGeneration
            )

        val complete = assertIs<SettingsHandsFeedbackImageEditFoldResult.Complete>(result)
        assertEquals(listOf("file:///edited/0.png"), complete.files.map { it.fileRef })
    }

    @Test
    fun multiImageEventsMayArriveOutOfOrderButCompleteInOriginalIndexOrder() {
        val initial = session(expectedCount = 3)

        val afterTwo = assertPending(fold(initial, event(index = 2, total = 3)))
        val afterZero = assertPending(fold(afterTwo, event(index = 0, total = 3)))
        val complete =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Complete>(
                fold(afterZero, event(index = 1, total = 3))
            )

        assertEquals(
            listOf("file:///edited/0.png", "file:///edited/1.png", "file:///edited/2.png"),
            complete.files.map { it.fileRef }
        )
    }

    @Test
    fun missingGroupEventNeverProducesACommitCandidateOrMutatesDraft() {
        val preferences = InMemorySlockKeyValueStore()
        val store = SettingsHandsFeedbackDraftStore(preferences)
        val existing = image("existing", "file:///draft/existing.png")
        store.save("account-a", "Keep this draft", listOf(existing))

        val pending = assertPending(fold(session(expectedCount = 2), event(index = 0, total = 2)))

        assertFalse(pending.groupState.isCommittable)
        assertEquals(
            listOf("file:///draft/existing.png"),
            store.read("account-a").attachments.map { it.fileRef }
        )
    }

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
    fun conflictingReplayPoisonsTheGroupWithoutDraftMutation() {
        val preferences = InMemorySlockKeyValueStore()
        val store = SettingsHandsFeedbackDraftStore(preferences)
        store.save("account-a", "Draft before edit")
        val afterFirst = assertPending(fold(session(expectedCount = 2), event(index = 0, total = 2)))
        val conflict =
            event(index = 0, total = 2).copy(
                actionId = "action-conflict",
                file = image("edited-conflict", "file:///edited/conflict.png")
            )

        val failed =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Failed>(fold(afterFirst, conflict))

        assertEquals(SlockImageEditGroupReconcile.REJECT_DUPLICATE_CONFLICT, failed.reason)
        assertEquals("Draft before edit", store.read("account-a").text)
        assertTrue(store.read("account-a").attachments.isEmpty())
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
        assertEquals(SlockImageEditGroupReconcile.REJECT_WRONG_GROUP, wrongGroup.reason)
    }

    @Test
    fun malformedOrNonCompleteEventsCannotEnterAGroup() {
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson().put("action", "replace")))
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson().put("actionId", "")))
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson().put("groupId", "")))
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson().put("fileRef", "")))

        val invalidTotal = parseSettingsHandsFeedbackImageEditEvent(eventJson(total = 0))!!
        val failed =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Failed>(
                fold(session(expectedCount = 1), invalidTotal)
            )
        assertEquals(SlockImageEditGroupReconcile.REJECT_TOTAL_MISMATCH, failed.reason)
    }

    @Test
    fun fullResultIdentityMakesMetadataChangesConflicting() {
        val original = event(index = 0, total = 2)

        assertNotEquals(
            settingsHandsFeedbackImageEditItemKey(original),
            settingsHandsFeedbackImageEditItemKey(
                original.copy(file = original.file.copy(previewUri = "file:///different-preview.png"))
            )
        )
    }

    @Test
    fun limitsDedupeAndByteCapApplyAfterTheWholeGroupCompletes() {
        val existing =
            (0 until 8).map { index ->
                image("existing-$index", "file:///draft/existing-$index.png", sizeBytes = 100)
            }
        var pending = session(expectedCount = 4)
        val edited =
            listOf(
                image("edited-duplicate", "file:///draft/existing-0.png", sizeBytes = 100),
                image("edited-new-1", "file:///edited/new-1.png", sizeBytes = 100),
                image("edited-new-2", "file:///edited/new-2.png", sizeBytes = 100),
                image("edited-too-large", "file:///edited/large.png", sizeBytes = 2_000)
            )
        edited.dropLast(1).forEachIndexed { index, file ->
            pending = assertPending(fold(pending, event(index, 4).copy(file = file)))
        }
        val complete =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Complete>(
                fold(pending, event(index = 3, total = 4).copy(file = edited.last()))
            )

        val promotion =
            promoteSettingsHandsFeedbackEditedAttachments(
                existing = existing,
                edited = complete.files,
                attachmentMaxBytes = 1_000,
                attachmentLimit = 9
            )

        assertEquals(1, promotion.rejectedTooLargeCount)
        assertTrue(promotion.trimmedByLimit)
        assertEquals(9, promotion.attachments.size)
        assertEquals(1, promotion.attachments.count { it.fileRef == "file:///draft/existing-0.png" })
        assertEquals("file:///edited/new-1.png", promotion.attachments.last().fileRef)
    }

    @Test
    fun editedFileRefsPersistInOriginalOrderAcrossDraftStoreRecreation() {
        val preferences = InMemorySlockKeyValueStore()
        val initial = session(expectedCount = 2)
        val afterOne = assertPending(fold(initial, event(index = 1, total = 2)))
        val complete =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Complete>(
                fold(afterOne, event(index = 0, total = 2))
            )
        val promotion =
            promoteSettingsHandsFeedbackEditedAttachments(
                existing = emptyList(),
                edited = complete.files,
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
        assertEquals(listOf("image_annotation", "image_annotation"), restored.attachments.map { it.source })
    }

    @Test
    fun toggleOffAndAttachFileKeepTheDirectAttachmentPath() {
        assertEquals(
            ThreadPickedAttachmentComposerAction.UploadToComposer,
            resolveThreadPickedAttachmentComposerAction(
                action = "attachImage",
                fileCount = 1,
                imageAnnotationEnabled = false,
                allFilesAreImages = true
            )
        )
        assertEquals(
            ThreadPickedAttachmentComposerAction.UploadToComposer,
            resolveThreadPickedAttachmentComposerAction(
                action = "attachFile",
                fileCount = 1,
                imageAnnotationEnabled = true,
                allFilesAreImages = true
            )
        )
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
            file = image("edited-$index", "file:///edited/$index.png")
        )

    private fun eventJson(
        index: Int = 0,
        total: Int = 1
    ): JSONObject =
        JSONObject().apply {
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
            source = "image_annotation"
        )
}
