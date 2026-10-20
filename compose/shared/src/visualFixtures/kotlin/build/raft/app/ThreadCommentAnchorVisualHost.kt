package build.raft.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.Page
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.data.ThreadAttachmentCommentViewer
import build.raft.app.data.ThreadAttachmentCommentsPage

@Page("compose_thread_comment_anchor_visual", supportInLocal = true)
internal class ComposeThreadCommentAnchorVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadCommentAnchorVisualScreen() }
    }
}

/**
 * components.thread.comment-anchor: the web reference renders `AttachmentCommentsPanel` alone on
 * a 390x844 phone viewport (VisualTestingCases.tsx, `thread-comment-anchor`), fed by the
 * react-provider spec's `/attachments/att-anchor-1/comments` stub: one comment anchored to the
 * `agent-tabs-full-page-review.mp4` section plus the same pending anchor above the composer.
 * The time is the web's rendering of the stub's 2026-06-25T10:32Z in the capture's UTC+8.
 */
@Composable
internal fun ThreadCommentAnchorVisualScreen() {
    var state by
        remember {
            mutableStateOf(
                ThreadAttachmentCommentsDialogState(
                    attachment =
                        RaftThreadAttachmentItem(
                            id = "att-anchor-1",
                            name = "agent-tabs-full-page-review.mp4",
                            mimeType = "video/mp4",
                            sizeLabel = ""
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
                                        createdAt = "06/25 18:32",
                                        reactions = emptyList(),
                                        anchorLabel = "§ agent-tabs-full-page-review.mp4"
                                    )
                                ),
                            threadChannelId = "channel-anchor-1",
                            viewer = ThreadAttachmentCommentViewer(canComment = true, reason = "ok")
                        ),
                    pendingAnchorLabel = "§ agent-tabs-full-page-review.mp4"
                )
            )
        }
    RaftAttachmentCommentsPanel(
        state = state,
        onDraftChange = { state = state.withAttachmentCommentEditorValue(it) },
        onRetry = {},
        onSend = {},
        onClearAnchor = { state = state.copy(pendingAnchorLabel = null) },
        modifier = Modifier.fillMaxSize()
    )
}
