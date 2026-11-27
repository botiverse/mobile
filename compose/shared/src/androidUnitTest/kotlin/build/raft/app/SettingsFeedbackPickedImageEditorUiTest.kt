package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.settings.SettingsHandsFeedbackImageEditRequest
import build.raft.app.settings.feedbackComposerAttachmentId
import build.raft.app.settings.normalizeSettingsHandsFeedbackPickerFiles
import build.raft.app.settings.settingsHandsFeedbackImageEditPreviewRequest
import build.raft.app.settings.settingsHandsFeedbackMergedAttachments
import build.raft.app.settings.settingsHandsFeedbackPickedImageToAnnotate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
