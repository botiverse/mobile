package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.data.ThreadAttachmentCommentsPage
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Page

@Page("compose_thread_comment_anchor_visual", supportInLocal = true)
internal class ComposeThreadCommentAnchorVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadCommentAnchorVisualScreen() }
    }
}

@Composable
internal fun ThreadCommentAnchorVisualScreen() {
    var state by
        remember {
            mutableStateOf(
                ThreadAttachmentCommentsDialogState(
                    attachment =
                        KuiklyThreadAttachmentItem(
                            id = "att-anchor-1",
                            name = "agent-tabs-full-page-review.md",
                            mimeType = "text/markdown",
                            sizeLabel = "23.0 KB"
                        ),
                    status = "loaded",
                    page =
                        ThreadAttachmentCommentsPage(
                            comments =
                                listOf(
                                    ThreadAttachmentCommentItem(
                                        id = "comment-anchor-1",
                                        channelId = "channel-anchor-1",
                                        senderType = "user",
                                        senderId = "user-anchor-2",
                                        senderName = "Artea",
                                        content = "Please keep the tag inset consistent with the field below.",
                                        createdAt = "18:32",
                                        reactions = emptyList(),
                                        anchorLabel = "agent-tabs-full-page-review.md"
                                    )
                                ),
                            threadChannelId = null,
                            viewer = null
                        ),
                    pendingAnchorLabel = "agent-tabs-full-page-review.md"
                )
            )
        }
    SlockFilePreviewPageScaffold(
        title = "FILE PREVIEW",
        subtitle = "agent-tabs-full-page-review.md",
        iconName = SlockLucideIconName.FileText,
        onBack = {},
        contentPadding = 0.dp
    ) {
        SlockFilePreviewPayloadContent(
            payload =
                SlockFilePreviewPayload(
                    kind = "markdown",
                    title = "FILE PREVIEW",
                    name = "agent-tabs-full-page-review.md",
                    content = "# Review\n\nKeep attachment comments on this standalone preview surface.",
                    markdown = true
                ),
            onOpenUrl = {},
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        SlockFilePreviewAttachmentCommentsPanel(
            state = state,
            onClose = {},
            onFilterChange = { state = state.copy(filter = it) },
            onDraftChange = { state = state.withAttachmentCommentEditorValue(it) },
            onRetry = {},
            onSend = {},
            onResolve = {},
            onQuote = {}
        )
    }
}
