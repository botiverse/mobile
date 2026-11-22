package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import build.raft.app.compat.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val ThreadShareCaptureMaxSegmentsPerMessage = 24

// The share image's live-list capture (ThreadPageContentContent), verbatim.
internal suspend fun captureThreadShareFragments(
    shareState: ThreadShareForwardState,
    selectedShareMessages: List<RaftThreadMessageItem>,
    timelineSlice: ThreadTimelineSlice,
    onExportShareImage: suspend (ThreadShareCaptureRect?) -> JSONObject?,
    diagnosticsLogger: (String) -> Unit,
): ThreadShareCaptureRect? {
    // Capture slices, per message: the share image frames each message on its own.
    val messageFragments = mutableListOf<MutableList<String>>()
    var completedMessages = 0
    // The click set shareCaptureMode, which drops the rows' viewer-only previews
    // (LocalThreadShareCaptureActive). This screenshot is of the live list, so let that
    // recomposition and the relayout it causes land before any bounds are read or pixels
    // taken: one frame recomposes, the next lays out and reports the rows' new bounds.
    // Fail closed: without a frame clock the recomposition can't be awaited, so the
    // screenshot could still hold the viewer-only previews. No capture, export fails.
    if (!awaitThreadShareCaptureFrames()) {
        diagnosticsLogger("thread_share_capture_aborted reason=no_frame_clock")
        return null
    }
    diagnosticsLogger(
        "thread_share_capture_start selected=${selectedShareMessages.size} " +
            "ids=${selectedShareMessages.joinToString(separator = ",") { it.id }}"
    )
    selectedShareMessages.forEach { message ->
        val targetIndex = timelineSlice.threadMessageListItemIndex(message.id)
        if (targetIndex < 0) {
            diagnosticsLogger("thread_share_capture_target_missing messageId=${message.id}")
            return@forEach
        }
        shareState.shareCaptureTargetMessageId = message.id
        shareState.shareCaptureAnchorBeforeTarget = true
        shareState.shareCaptureTailPaddingActive = true
        var messageScrollBaseOffsetPx = ThreadShareCaptureTopScrollOffsetPx
        shareState.shareCaptureScrollOffsetPx = messageScrollBaseOffsetPx
        delay(ThreadShareCaptureScrollSettleMs + 64L)
        // Keep the phase-one placement when the exact-target scroll is a
        // no-op. Kuikly does not re-run onGloballyPositioned for an item
        // whose placement did not change, so clearing here can discard the
        // only fresh bounds for a target that was already visible.
        shareState.shareCaptureAnchorBeforeTarget = false
        delay(ThreadShareCaptureScrollSettleMs + 64L)
        var attempts = 0
        while (attempts < 24 && shareState.selectedShareBounds[message.id] == null) {
            delay(32)
            attempts += 1
        }
        val initialRect =
            shareState.selectedShareBounds[message.id]
                ?: run {
                    diagnosticsLogger(
                        "thread_share_capture_bounds_missing messageId=${message.id} " +
                            "targetIndex=$targetIndex attempts=$attempts"
                    )
                    return@forEach
                }
        diagnosticsLogger(
            "thread_share_capture_bounds_ready messageId=${message.id} targetIndex=$targetIndex " +
                "x=${initialRect.x} y=${initialRect.y} width=${initialRect.width} height=${initialRect.height}"
        )
        val captureViewportTop = initialRect.y.coerceAtLeast(0f)
        val captureViewportBottom = shareState.shareCaptureViewportBottomPx
        if (captureViewportBottom <= captureViewportTop) {
            diagnosticsLogger(
                "thread_share_capture_viewport_missing messageId=${message.id} " +
                    "top=$captureViewportTop bottom=$captureViewportBottom"
            )
            return@forEach
        }
        var capturedHeight = 0
        var segmentHeight = 0
        var segmentCount = 0
        val fragments = mutableListOf<String>().also { messageFragments += it }
        while (capturedHeight < initialRect.height.roundToInt() && segmentCount < ThreadShareCaptureMaxSegmentsPerMessage) {
            val rect = shareState.selectedShareBounds[message.id] ?: break
            val slice =
                threadShareCaptureSlice(
                    messageHeight = initialRect.height,
                    currentMessageTop = rect.y,
                    captureViewportTop = captureViewportTop,
                    captureViewportBottom = captureViewportBottom,
                    capturedHeight = capturedHeight,
                    segmentHeight = segmentHeight
                ) ?: break
            val captureRect =
                ThreadShareCaptureRect(
                    x = rect.x,
                    y = slice.y,
                    width = rect.width,
                    height = slice.height,
                    frame = false
                )
            val result = runCatching { onExportShareImage(captureRect) }.getOrNull()
            val path = result?.optString("path").orEmpty()
            val exportedHeight = result?.optString("height")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (result?.optString("status") != "ok" || path.isBlank() || exportedHeight <= 0) {
                diagnosticsLogger(
                    "thread_share_capture_slice_failed messageId=${message.id} segment=$segmentCount " +
                        "capturedHeight=$capturedHeight y=${captureRect.y} height=${captureRect.height} " +
                        "status=${result?.optString("status").orEmpty()} " +
                        "message=${result?.optString("message").orEmpty()} exportedHeight=$exportedHeight"
                )
                break
            }
            diagnosticsLogger(
                "thread_share_capture_slice_ok messageId=${message.id} segment=$segmentCount " +
                    "capturedHeight=$capturedHeight exportedHeight=$exportedHeight"
            )
            fragments += path
            segmentHeight = maxOf(segmentHeight, exportedHeight)
            capturedHeight += exportedHeight
            segmentCount += 1
            if (capturedHeight >= initialRect.height.roundToInt()) {
                break
            }
            shareState.selectedShareBounds.clear()
            shareState.shareCaptureAnchorBeforeTarget = false
            shareState.shareCaptureTailPaddingActive = true
            shareState.shareCaptureScrollOffsetPx = capturedHeight + messageScrollBaseOffsetPx
            delay(ThreadShareCaptureScrollSettleMs + 64L)
            attempts = 0
            while (attempts < 24 && shareState.selectedShareBounds[message.id] == null) {
                delay(32)
                attempts += 1
            }
        }
        if (capturedHeight >= initialRect.height.roundToInt()) {
            completedMessages += 1
        }
    }
    val fragmentCount = messageFragments.sumOf { it.size }
    if (fragmentCount == 0 || completedMessages != selectedShareMessages.size) {
        diagnosticsLogger(
            "thread_share_capture_incomplete fragments=$fragmentCount " +
                "completed=$completedMessages selected=${selectedShareMessages.size}"
        )
        return null
    }
    diagnosticsLogger(
        "thread_share_capture_complete fragments=$fragmentCount completed=$completedMessages"
    )
    return ThreadShareCaptureRect(
        x = 0f,
        y = 0f,
        width = 0f,
        height = 0f,
        path = threadShareFragmentsParam(messageFragments)
    )
}

// Share preview (ThreadPageContentContent), verbatim and at the same place.
@Composable
@NonRestartableComposable
internal fun ThreadSharePreviewOverlay(
    shareState: ThreadShareForwardState,
    activeModalState: MutableState<String?>,
    selectedShareMessages: List<RaftThreadMessageItem>,
    selectedShareMarkdown: String,
    sharePngWriter: RaftPngFileWriter,
    shareExportScope: CoroutineScope,
    onSaveShareImage: suspend (String) -> JSONObject?,
    onThreadEvent: ThreadEventHandler,
    /** Share loop (share_loop_v0): the preview's Share sends the image through the system sheet; null: off. */
    onShareImage: (() -> Unit)? = null,
) {
    var activeModal by activeModalState
    ThreadSharePreviewModal(
        messages = selectedShareMessages,
        image = shareState.sharePreviewImage,
        onDismiss = { activeModal = null },
        onCopyMarkdown = {
            if (selectedShareMarkdown.isNotBlank()) {
                onThreadEvent("copyShareSelectionMarkdown", selectedShareMarkdown, null, null)
            }
        },
        onShareImage = onShareImage,
        offscreen =
            if (threadShareImageRendersOffscreen) {
                ThreadSharePreviewOffscreen(
                    image = shareState.shareImageBitmap,
                    rendering = shareState.shareImageRendering || shareState.shareImageSaving,
                    choice = shareState.shareImageWidthChoice,
                    onChoiceChange = { shareState.shareImageWidthChoice = it },
                )
            } else {
                null
            },
        onDownloadImage = {
            val offscreenImage = shareState.shareImageBitmap?.image
            if (threadShareImageRendersOffscreen) {
                if (offscreenImage != null && !shareState.shareImageSaving && !shareState.shareImageRendering) {
                    shareState.shareImageSaving = true
                    shareExportScope.launch {
                        val result =
                            try {
                                val fileRef =
                                    withContext(Dispatchers.Default) {
                                        runCatching { sharePngWriter.writePng(offscreenImage, ThreadShareImageFilePrefix) }.getOrNull()
                                    }
                                val path = fileRef?.let(::threadShareImageFilePath)
                                if (path.isNullOrBlank()) null else runCatching { onSaveShareImage(path) }.getOrNull()
                            } finally {
                                shareState.shareImageSaving = false
                            }
                        val status = result?.optString("status")
                        if (status == "cancelled") return@launch
                        val saved = status == "ok"
                        if (saved) {
                            activeModal = null
                        }
                        onThreadEvent(
                            if (saved) "shareSelectionImageSaved" else "shareSelectionExportFailed",
                            result?.optString("message")?.takeIf { it.isNotBlank() },
                            null,
                            null
                        )
                    }
                }
                return@ThreadSharePreviewModal
            }
            val imagePath = shareState.sharePreviewImage?.path
            if (!imagePath.isNullOrBlank()) {
                shareExportScope.launch {
                    val result = runCatching { onSaveShareImage(imagePath) }.getOrNull()
                    val saved = result?.optString("status") == "ok"
                    if (saved) {
                        activeModal = null
                    }
                    onThreadEvent(
                        if (saved) {
                            "shareSelectionImageSaved"
                        } else {
                            "shareSelectionExportFailed"
                        },
                        result?.optString("message")?.takeIf { it.isNotBlank() },
                        null,
                        null
                    )
                }
            }
        }
    )
}
