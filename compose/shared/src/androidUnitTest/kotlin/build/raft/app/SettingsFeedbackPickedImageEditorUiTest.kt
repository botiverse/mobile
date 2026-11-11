package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import build.raft.app.compat.json.JSONObject
import build.raft.app.data.DataResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftShellStoreScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * New feedback: an image added from the picker opens straight in the annotation editor. The
 * picked image is attached first; the editor's Cancel (Back) closes it and keeps that original,
 * its Done hands the edit over and it replaces the original in place.
 *
 * The page's editor/preview switch is composed through the same
 * [rememberRaftFilePreviewComposerImageEditFlow] the file preview page uses; the form side runs
 * the production pick merge, edit request, event parser, fold and commit.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsFeedbackPickedImageEditorUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val picked =
        PlatformFile(
            id = "picked-1",
            fileName = "shot.png",
            mimeType = "image/png",
            sizeBytes = 512L,
            previewUri = "file:///cache/picker/shot.png",
            fileRef = "file:///cache/picker/shot.png",
            source = "platform_picker"
        )

    @Test
    fun pickedImageOpensTheEditorAndCancelKeepsTheOriginal() = runRobolectricComposeUiTest {
        val (attachments, request) = pickIntoEmptyForm()
        // The form owns the attached file: closing the editor without Done must not delete it.
        assertTrue(request.previewPayload.composerImageOwnedTemporaryFileRefs.isEmpty())
        assertFalse(request.previewPayload.imageGallery.single().composerImageOwnedTemporary)

        var flow: RaftFilePreviewComposerImageEditFlow? = null
        setContent { EditFlowHost(request.previewPayload) { flow = it } }
        onNodeWithTag(EDITOR).assertExists()

        // Back while the edit is still saving does nothing.
        runOnIdle { assertFalse(requireNotNull(flow).back(saving = true)) }
        onNodeWithTag(EDITOR).assertExists()

        // Cancel closes the page with no result: the form still has the original.
        runOnIdle { assertTrue(requireNotNull(flow).back(saving = false)) }
        assertEquals(listOf(picked.fileRef), attachments.map { it.fileRef })
    }

    @Test
    fun pickedImageEditorDoneReplacesTheOriginalInPlace() = runRobolectricComposeUiTest {
        val (attachments, request) = pickIntoEmptyForm()
        var flow: RaftFilePreviewComposerImageEditFlow? = null
        setContent { EditFlowHost(request.previewPayload) { flow = it } }
        onNodeWithTag(EDITOR).assertExists()

        // Done saves and hands the edit over at once (no stop on the preview).
        runOnIdle { assertTrue(requireNotNull(flow).saved()) }
        onNodeWithTag(PREVIEW).assertExists()

        val payload = request.previewPayload
        val galleryImage = payload.imageGallery.single()
        val event =
            requireNotNull(
                parseSettingsHandsFeedbackImageEditEvent(
                    JSONObject().apply {
                        put("previewSessionId", "preview-session")
                        put("threadKey", payload.composerImageThreadKey)
                        put("actionId", payload.composerImageActionId)
                        put("action", "replace")
                        put("replaceAttachmentId", galleryImage.composerImageReplaceAttachmentId)
                        put("groupId", payload.composerImageGroupId)
                        put("groupIndex", 0)
                        put("groupTotal", 1)
                        put("fileRef", "file:///cache/editor/shot-edited.jpg")
                        put("previewUri", "file:///cache/editor/shot-edited.jpg")
                        put("fileName", "shot-edited.jpg")
                        put("mimeType", "image/jpeg")
                        put("sizeBytes", 300L)
                        put("source", raftFilePreviewComposerImageEventSource(compressed = true))
                        put("compressed", true)
                    }
                )
            )
        val folded =
            foldSettingsHandsFeedbackImageEditEvent(
                session = request.session,
                event = event,
                currentAccountId = request.session.accountId,
                currentPageGeneration = request.session.pageGeneration
            )
        val complete = assertIs<SettingsHandsFeedbackImageEditFoldResult.Complete>(folded)
        val committed =
            runBlocking {
                commitSettingsHandsFeedbackEditedGroup(
                    existing = attachments,
                    edited = complete.files,
                    attachmentMaxBytes = 1_000,
                    attachmentLimit = 3,
                    replaceAttachmentId = complete.replaceAttachmentId,
                    isCurrent = { true },
                    promote = { files -> DataResult.Content(files.map { it.copy(source = "settings_feedback_draft") }) },
                    persist = {}
                )
            }
        val promotion = assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Committed>(committed).promotion
        assertEquals(listOf("file:///cache/editor/shot-edited.jpg"), promotion.attachments.map { it.fileRef })
    }

    @Test
    fun tappedAttachedImageStillOpensOnThePreview() = runRobolectricComposeUiTest {
        val request =
            requireNotNull(
                settingsHandsFeedbackImageEditPreviewRequest(
                    accountId = "account-a",
                    pageGeneration = "page-a",
                    file = picked,
                    sourceStoreScope = RaftShellStoreScope.create("legacy", "server-a"),
                    groupId = "tap-group"
                )
            )
        var flow: RaftFilePreviewComposerImageEditFlow? = null
        setContent { EditFlowHost(request.previewPayload) { flow = it } }
        onNodeWithTag(PREVIEW).assertExists()

        // Edit, then Back: back to the preview, the page stays.
        runOnIdle { requireNotNull(flow).editMode = true }
        onNodeWithTag(EDITOR).assertExists()
        runOnIdle { assertFalse(requireNotNull(flow).back(saving = false)) }
        onNodeWithTag(PREVIEW).assertExists()

        // Edit, then Done: the result waits for the preview's own Done.
        runOnIdle { requireNotNull(flow).editMode = true }
        runOnIdle { assertFalse(requireNotNull(flow).saved()) }
        onNodeWithTag(PREVIEW).assertExists()
    }

    /** The form's pick: merge into an empty form, then the edit request for the new image. */
    private fun pickIntoEmptyForm(): Pair<List<PlatformFile>, SettingsHandsFeedbackImageEditRequest> {
        val merge =
            settingsHandsFeedbackMergedAttachments(
                existing = emptyList(),
                picked = normalizeSettingsHandsFeedbackPickerFiles(listOf(picked), imageOnly = true),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 3
            )
        val toAnnotate =
            requireNotNull(
                settingsHandsFeedbackPickedImageToAnnotate(existing = emptyList(), merged = merge.attachments)
            )
        val request =
            requireNotNull(
                settingsHandsFeedbackImageEditPreviewRequest(
                    accountId = "account-a",
                    pageGeneration = "page-a",
                    file = toAnnotate,
                    sourceStoreScope = RaftShellStoreScope.create("legacy", "server-a"),
                    groupId = "pick-group",
                    opensInEditor = true
                )
            )
        assertEquals(toAnnotate.feedbackComposerAttachmentId(), request.previewPayload.imageGallery.single().composerImageReplaceAttachmentId)
        return merge.attachments to request
    }

    @Composable
    private fun EditFlowHost(
        payload: RaftFilePreviewPayload,
        onFlow: (RaftFilePreviewComposerImageEditFlow) -> Unit,
    ) {
        val flow = rememberRaftFilePreviewComposerImageEditFlow(payload)
        SideEffect { onFlow(flow) }
        Box(Modifier.testTag(if (flow.editMode) EDITOR else PREVIEW))
    }

    private companion object {
        const val EDITOR = "feedback-image-editor"
        const val PREVIEW = "feedback-image-preview"
    }
}
