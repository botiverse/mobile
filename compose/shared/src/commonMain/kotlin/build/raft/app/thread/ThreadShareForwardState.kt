package build.raft.app.thread

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetDirectoryCursor
import build.raft.app.model.ServerId

/**
 * The thread page's share / forward state (ThreadPageContentContent): select mode, the share
 * preview and image, the live-list capture, and the forward picker. One instance per
 * `threadInstanceKey` (`remember(threadInstanceKey) { ThreadShareForwardState() }`), so all of
 * it resets together on another thread, as each `remember(threadInstanceKey)` it replaces did.
 *
 * The `*State` holders stay [MutableState]s: ThreadTabContent reads and writes them.
 */
@Stable
internal class ThreadShareForwardState {
    var activeForwardSelectionMode by mutableStateOf(ThreadForwardSelectionMode.Merged)
    val selectedShareMessageIdsState: MutableState<Set<String>> = mutableStateOf(emptySet())
    var selectedShareMessageIds by selectedShareMessageIdsState
    // The anchor of Select to here (web selectionStore `anchorId`): the message last added while it
    // stays selected; null once it is deselected or the selection is cleared.
    val shareSelectionAnchorIdState: MutableState<String?> = mutableStateOf(null)
    var shareSelectionAnchorId by shareSelectionAnchorIdState
    // Select mode itself, apart from what is selected: as on the web (selectionStore `isActive`), it
    // stays on with nothing selected (the select-all checkbox clears) until × ends it.
    val shareSelectionModeState: MutableState<Boolean> = mutableStateOf(false)
    var shareSelectionMode by shareSelectionModeState
    var forwardMessageIds by mutableStateOf<Set<String>>(emptySet())
    val selectedShareBounds = mutableMapOf<String, ThreadShareCaptureRect>()
    var sharePreviewImage by mutableStateOf<ThreadSharePreviewImage?>(null)
    var sharePreviewLoading by mutableStateOf(false)
    // Off-screen share image laid out at the picked width (threadShareImageRendersOffscreen).
    var shareImageWidthChoice by mutableStateOf(ThreadShareImageWidthChoice())
    var shareImageRenderWidthDp by mutableStateOf(ThreadShareImageDefaultWidthDp)
    var shareImageBitmap by mutableStateOf<ThreadShareRenderedImage?>(null)
    var shareImageRendering by mutableStateOf(false)
    var shareImageSaving by mutableStateOf(false)
    val shareCaptureModeState: MutableState<Boolean> = mutableStateOf(false)
    var shareCaptureMode by shareCaptureModeState
    val shareCaptureTargetMessageIdState: MutableState<String?> = mutableStateOf(null)
    var shareCaptureTargetMessageId by shareCaptureTargetMessageIdState
    val shareCaptureAnchorBeforeTargetState: MutableState<Boolean> = mutableStateOf(false)
    var shareCaptureAnchorBeforeTarget by shareCaptureAnchorBeforeTargetState
    val shareCaptureTailPaddingActiveState: MutableState<Boolean> = mutableStateOf(false)
    var shareCaptureTailPaddingActive by shareCaptureTailPaddingActiveState
    var shareCaptureViewportBottomPx by mutableStateOf(0f)
    val shareCaptureScrollOffsetPxState: MutableState<Int> = mutableStateOf(ThreadShareCaptureTopScrollOffsetPx)
    var shareCaptureScrollOffsetPx by shareCaptureScrollOffsetPxState
    var forwardTargets by mutableStateOf<List<ForwardMessageTarget>>(emptyList())
    var forwardPickerFlow by mutableStateOf(RaftForwardPickerFlowState())
    var forwardSending by mutableStateOf(false)
    var forwardSourceServerId by mutableStateOf<ServerId?>(null)
    var forwardSelectedReceipts by mutableStateOf<List<ThreadForwardDestinationReceipt>>(emptyList())
    var forwardRequestId by mutableStateOf<String?>(null)
    var forwardDirectoryGeneration by mutableStateOf(0)
    var forwardDirectoryNextCursor by mutableStateOf<ForwardMessageTargetDirectoryCursor?>(null)
    var forwardOperationNonce by mutableStateOf(0)
    var forwardNote by mutableStateOf(TextFieldValue())
    var forwardSuccessBanner by mutableStateOf<ThreadForwardSuccessBanner?>(null)
    var forwardConversationTargets by mutableStateOf<List<ForwardMessageTarget>>(emptyList())
}
