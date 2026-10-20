package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import build.raft.app.resources.MR
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource

private const val SLOCK_FILE_PREVIEW_EDITOR_VISUAL_PAGE = "compose_file_preview_editor_visual"

private const val SlockFilePreviewEditorVisualPreviewUri =
    "data:image/svg+xml;utf8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%20390%20520%22%3E%3Crect%20width%3D%22390%22%20height%3D%22520%22%20fill%3D%22%23FDE047%22%2F%3E%3Ccircle%20cx%3D%22290%22%20cy%3D%22120%22%20r%3D%2270%22%20fill%3D%22%23F472B6%22%2F%3E%3Cpath%20d%3D%22M30%20460L135%20280l75%2090%2070-65%2080%20155H30z%22%20fill%3D%22%23000000%22%2F%3E%3C%2Fsvg%3E"

@Page(SLOCK_FILE_PREVIEW_EDITOR_VISUAL_PAGE, supportInLocal = true)
internal class ComposeSlockFilePreviewEditorVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockFilePreviewEditorVisualContent() }
    }
}

@Composable
private fun SlockFilePreviewEditorVisualContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val visualFixture = pager.pageData.params.optString("visualFixture")
    val payload =
        remember {
            SlockFilePreviewPayload(
                kind = "image",
                title = "Design crop",
                name = "design-crop.png",
                imageUrl = SlockFilePreviewEditorVisualPreviewUri,
                composerImageActionId = "visual-image-editor",
                composerImageFileRef = "visual-image-editor-file",
                composerImageCanEdit = true,
                composerImageCanComplete = true,
            )
        }
    val editorState = remember(payload.imageUrl) { SlockComposerImageEditorState() }
    if (visualFixture == "action_bar") {
        SlockFilePreviewPageScaffold(
            title = payload.previewHeaderTitle(),
            subtitle = payload.previewHeaderSubtitle(),
            iconName = payload.previewIconName(),
            onBack = {},
            contentPadding = 0.dp,
        ) {
            SlockFilePreviewPayloadContent(
                payload = payload,
                onOpenUrl = {},
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            SlockFilePreviewComposerImageActionBar(
                payload = payload,
                enabled = true,
                onEdit = {},
                onComplete = {},
            )
        }
        return
    }
    SlockFilePreviewPageScaffold(
        title = stringResource(MR.strings.file_preview_editor_title),
        iconName = SlockLucideIconName.Pencil,
        onBack = {},
        contentPadding = 0.dp,
        trailingContent = {
            SlockFilePreviewImageEditorHistoryActions(
                state = editorState,
                saving = false,
            )
        },
    ) {
        SlockFilePreviewComposerImageEditor(
            payload = payload,
            state = editorState,
            saving = false,
            onCaptureRef = {},
            onDone = {},
            initialTool =
                if (visualFixture == "text_ime") {
                    SlockComposerImageEditorTool.Text
                } else {
                    SlockComposerImageEditorTool.Pencil
                },
            initialPencilOptionsExpanded = visualFixture == "pencil_options",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}
