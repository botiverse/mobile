package build.raft.app.thread

import build.raft.app.resources.*
import build.raft.app.shownAuthorName
import build.raft.app.thread.shownAuthorName
import org.jetbrains.compose.resources.StringResource
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.app.components.RaftUiControlledMenu
import build.raft.app.components.RaftUiMenuItem
import build.raft.app.components.raftCardSurface
import build.raft.app.data.map
import build.raft.app.data.messageMetadataHasActionCard
import build.raft.app.fill
import build.raft.app.get
import build.raft.app.messages
import build.raft.app.raftArgbHex
import build.raft.app.shell.raftBottomSafeAreaPadding
import build.raft.app.raftCaptureLiveRootAnchor
import build.raft.app.resolve
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.RaftCachedImage
import build.raft.app.RaftDialogFooter
import build.raft.app.RaftFloatingVerticalPlacement
import build.raft.app.RaftLiveRootAnchorRef
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftModalActionButton
import build.raft.app.RaftPopoverAnchor
import build.raft.app.RaftPopoverLocalPoint
import build.raft.app.RaftUnifiedDialog
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

internal data class ThreadShareCaptureRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val path: String = "",
    val frame: Boolean = true
)

/**
 * `exportThreadShareImage`'s `fragments`: each message's capture slices one path per line, a
 * blank line between messages (Android frames each message as its own row of the share image;
 * iOS reads the lines and skips the blanks).
 */
internal fun threadShareFragmentsParam(messageFragments: List<List<String>>): String =
    messageFragments.filter { it.isNotEmpty() }.joinToString(separator = "\n\n") { it.joinToString(separator = "\n") }

internal data class ThreadShareCaptureSlice(
    val y: Float,
    val height: Float
)

internal fun threadShareCaptureSlice(
    messageHeight: Float,
    currentMessageTop: Float,
    captureViewportTop: Float,
    captureViewportBottom: Float,
    capturedHeight: Int,
    segmentHeight: Int
): ThreadShareCaptureSlice? {
    val visibleContentStart = (captureViewportTop - currentMessageTop).coerceAtLeast(0f)
    if (visibleContentStart > capturedHeight + ThreadShareCaptureGeometryTolerancePx) return null
    val overlap = (capturedHeight - visibleContentStart).coerceAtLeast(0f)
    val remainingHeight = messageHeight - capturedHeight
    if (remainingHeight <= 0f) return null
    val sliceTop = captureViewportTop + overlap
    val visibleViewportHeight = captureViewportBottom - sliceTop
    if (visibleViewportHeight <= 0f) return null
    return ThreadShareCaptureSlice(
        y = sliceTop,
        height =
            minOf(
                if (segmentHeight > 0) segmentHeight.toFloat() else remainingHeight,
                remainingHeight,
                visibleViewportHeight
            )
    )
}

internal fun threadShareCaptureAnchorListItemIndex(
    targetIndex: Int,
    anchorBeforeTarget: Boolean
): Int =
    if (anchorBeforeTarget) {
        (targetIndex - 1).coerceAtLeast(0)
    } else {
        targetIndex
    }

internal fun threadShareCaptureAnchorTarget(
    targetIndex: Int,
    anchorBeforeTarget: Boolean,
    originVisible: Boolean,
    historyBoundaryVisible: Boolean,
    messageIds: List<String>
): LazyViewportAnchorTarget? {
    if (targetIndex < 0) return null
    val scrollIndex =
        threadShareCaptureAnchorListItemIndex(
            targetIndex = targetIndex,
            anchorBeforeTarget = anchorBeforeTarget
        )
    var nextIndex = 0
    if (originVisible) {
        if (scrollIndex == nextIndex) {
            return LazyViewportAnchorTarget.StableItem(ThreadOriginListItemKey)
        }
        nextIndex += 1
    }
    if (historyBoundaryVisible) {
        if (scrollIndex == nextIndex) {
            return LazyViewportAnchorTarget.StableItem(ThreadOlderMessagesListItemKey)
        }
        nextIndex += 1
    }
    return messageIds
        .getOrNull(scrollIndex - nextIndex)
        ?.let { messageId -> LazyViewportAnchorTarget.Message(messageId) }
}

internal fun threadStableViewportListItemIndex(
    key: String,
    originVisible: Boolean,
    historyBoundaryVisible: Boolean
): Int =
    when (key) {
        ThreadOriginListItemKey -> if (originVisible) 0 else -1
        ThreadOlderMessagesListItemKey ->
            if (historyBoundaryVisible) {
                if (originVisible) 1 else 0
            } else {
                -1
            }
        else -> -1
    }

internal data class ThreadSharePreviewImage(
    val uri: String,
    val path: String,
    val width: Int,
    val height: Int
)

internal fun ThreadTimelineSlice.threadSelectedShareMessages(
    selectedIds: Set<String>
): List<RaftThreadMessageItem> {
    if (selectedIds.isEmpty()) return emptyList()
    return (listOfNotNull(originMessage) + messages)
        .filter { message -> message.id in selectedIds && !message.isSystemMessage() }
        .distinctBy { message -> message.id }
}

internal data class ThreadForwardMessageSelection(
    val forwardableMessages: List<RaftThreadMessageItem>,
    val skippedCount: Int,
    val nestedForwardCount: Int
)

internal fun List<RaftThreadMessageItem>.threadForwardMessageSelection(): ThreadForwardMessageSelection {
    val forwardableMessages = filter { message -> message.isForwardableOrdinaryChatMessage() }
    return ThreadForwardMessageSelection(
        forwardableMessages = forwardableMessages,
        skippedCount = size - forwardableMessages.size,
        nestedForwardCount = count { message -> message.forwardBundle != null }
    )
}

/**
 * Web `getForwardableMessages` parity: system messages, action cards and forwarded bundles are
 * not forwardable; everything else is, including task-linked messages (the server's
 * `POST /messages/forward` only rejects non-chat rows, rows with action metadata and bundles).
 * Local sends that have not reached the server have no server id to forward yet.
 */
internal fun RaftThreadMessageItem.isForwardableOrdinaryChatMessage(): Boolean =
    messageType.equals("Chat", ignoreCase = true) &&
        deliveryState == ThreadMessageDeliverySent &&
        forwardBundle == null &&
        !isThreadActionCardMessage()

internal fun RaftThreadMessageItem.isThreadActionCardMessage(): Boolean =
    messageMetadataHasActionCard(metadata) || metadata["kind"] == "action-card"

/**
 * Why a forward did not go out, as its toast says it. Codes from the server (web
 * `forwardRequestFailureMessage`) and from this client's own gates map to one; any other code
 * reads as the generic "Unable to forward" (the code itself only goes to the log).
 */
internal enum class ThreadForwardFailure {
    NestedForward,
    UnsupportedSource,
    CrossSource,
    SourceNotFound,
    CrossServerSource,
    SourceTargetForbidden,
    CrossServerUnavailable,
    ServerUnauthorized,
    TargetChanged,
    DestinationsUnavailable,
    PartialFailure,
    Generic
}

/** The forward repository was gone when Send was tapped (a generic toast; the code is logged). */
internal const val ThreadForwardFailureCodeUnavailable = "forward_api_unavailable"
/** The source conversation was gone when Send was tapped (a generic toast; the code is logged). */
internal const val ThreadForwardFailureCodeSourceUnavailable = "forward_source_unavailable"
/** No destination was selected when Send was tapped. */
internal const val ThreadForwardFailureCodeTargetUnavailable = "forward_target_unavailable"
/** Some destinations took the forward and others did not. */
internal const val ThreadForwardFailureCodePartial = "forward_partial_failure"

internal fun threadForwardFailure(code: String?): ThreadForwardFailure =
    when (code?.trim()?.lowercase()) {
        "unsupport",
        "unsupported",
        "forwarded_source_not_supported" -> ThreadForwardFailure.NestedForward
        "unsupported_source_message",
        "unsupported_source" -> ThreadForwardFailure.UnsupportedSource
        "cross_source_bundle" -> ThreadForwardFailure.CrossSource
        "source_not_found" -> ThreadForwardFailure.SourceNotFound
        "cross_server_source" -> ThreadForwardFailure.CrossServerSource
        "forward_source_target_forbidden" -> ThreadForwardFailure.SourceTargetForbidden
        "forward_cross_server_target_unsupported",
        "forward_cross_server_batch_unsupported",
        "forward_joint_projection_unavailable" -> ThreadForwardFailure.CrossServerUnavailable
        "forward_source_server_unauthorized",
        "forward_target_server_unauthorized",
        "forward_target_server_not_selected" -> ThreadForwardFailure.ServerUnauthorized
        // task #803: identity-drift gates fail closed; the user re-selects.
        "forward_destination_identity_mismatch",
        "forward_source_server_changed",
        "forward_directory_refreshed" -> ThreadForwardFailure.TargetChanged
        ThreadForwardFailureCodeTargetUnavailable -> ThreadForwardFailure.DestinationsUnavailable
        ThreadForwardFailureCodePartial -> ThreadForwardFailure.PartialFailure
        else -> ThreadForwardFailure.Generic
    }

/** The toast for [ThreadForwardFailure], in the catalog (web `message.forwardComposer.*` where web has it). */
internal val ThreadForwardFailure.messageRes: StringResource
    get() =
        when (this) {
            ThreadForwardFailure.NestedForward -> Res.string.thread_forward_error_nested
            ThreadForwardFailure.UnsupportedSource -> Res.string.thread_forward_error_unsupported_source
            ThreadForwardFailure.CrossSource -> Res.string.thread_forward_error_cross_source
            ThreadForwardFailure.SourceNotFound -> Res.string.thread_forward_error_source_not_found
            ThreadForwardFailure.CrossServerSource -> Res.string.thread_forward_error_cross_server_source
            ThreadForwardFailure.SourceTargetForbidden -> Res.string.thread_forward_error_source_target_forbidden
            ThreadForwardFailure.CrossServerUnavailable -> Res.string.thread_forward_error_cross_server_unavailable
            ThreadForwardFailure.ServerUnauthorized -> Res.string.thread_forward_error_server_unauthorized
            ThreadForwardFailure.TargetChanged -> Res.string.thread_forward_error_target_changed
            ThreadForwardFailure.DestinationsUnavailable -> Res.string.thread_forward_error_destinations_unavailable
            ThreadForwardFailure.PartialFailure -> Res.string.thread_forward_error_retry_remaining
            ThreadForwardFailure.Generic -> Res.string.thread_error_forward
        }

/** The failure code when nothing selected can be forwarded: only bundles, or anything else. */
internal fun ThreadForwardMessageSelection.threadForwardSelectionFailureCode(): String =
    when {
        nestedForwardCount == skippedCount -> "forwarded_source_not_supported"
        else -> "unsupported_source_message"
    }

/**
 * Web `message.forwardComposer.skippedUnsupported`: the picker's note that some selected messages
 * were left out, as the `_one` / `_other` catalog string for [skippedCount]; null when none were.
 */
internal fun threadForwardSkippedWarningRes(skippedCount: Int): StringResource? =
    when {
        skippedCount <= 0 -> null
        skippedCount == 1 -> Res.string.thread_forward_skipped_one
        else -> Res.string.thread_forward_skipped_other
    }

/**
 * What a thread's select-all checkbox selects (web `threadSelectableIds`, botiverse/slock #12681
 * useThreadSelectionActions.ts): its parent and every loaded reply that can be forwarded (not
 * system updates, action cards or forwarded bundles), and here also nothing retracted, which a
 * row tap never selects either.
 */
internal fun ThreadTimelineSlice.threadSelectableShareMessageIds(): Set<String> =
    (listOfNotNull(originMessage) + messages)
        .filter { it.isForwardableOrdinaryChatMessage() && !it.isRetracted }
        .map { it.id }
        .toSet()

/** The thread select-all checkbox's three looks (web SelectModeToolbar). */
internal enum class ThreadShareSelectAllCheck {
    /** Nothing is selected. */
    Unchecked,

    /** Something else is selected: some of the selectable messages, or messages outside them. */
    Indeterminate,

    /** The selection is exactly the selectable messages, not a message more or less. */
    Checked,
}

internal fun threadShareSelectAllCheck(selected: Set<String>, selectable: Set<String>): ThreadShareSelectAllCheck =
    when {
        selectable.isNotEmpty() && selected == selectable -> ThreadShareSelectAllCheck.Checked
        selected.isNotEmpty() -> ThreadShareSelectAllCheck.Indeterminate
        else -> ThreadShareSelectAllCheck.Unchecked
    }

/** A tap on the checkbox: [-] and [✓] clear the selection, [ ] selects every selectable message. */
internal fun threadShareSelectAllNext(selected: Set<String>, selectable: Set<String>): Set<String> =
    if (selected.isNotEmpty()) emptySet() else selectable

/**
 * The count says "All N selected" only when the selection is exactly the selectable messages and
 * the whole thread is loaded ([complete]); otherwise the plain "N selected".
 */
internal fun threadShareSelectionSaysAll(check: ThreadShareSelectAllCheck, complete: Boolean): Boolean =
    check == ThreadShareSelectAllCheck.Checked && complete

/** The thread toolbar's select-all checkbox, before the count. */
internal data class ThreadShareSelectAll(
    val check: ThreadShareSelectAllCheck,
    val complete: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

private fun ThreadTimelineSlice.threadSelectedShareMarkdown(selectedIds: Set<String>): String =
    threadSelectedShareMessages(selectedIds).threadShareMarkdown()

internal fun List<RaftThreadMessageItem>.threadShareMarkdown(): String =
    joinToString("\n\n") { message ->
        "**${message.shownAuthorName().ifBlank { "Unknown" }}**: ${message.body.trim()}"
    }

private const val ThreadShareCaptureGeometryTolerancePx = 2f
/**
 * web SelectModeToolbar (packages/web/src/components/message/SelectModeToolbar.tsx, #11761 /
 * #12152; raft-ui 0.101, slock #14936): the toolbar for selected messages is the same toolbar as
 * selected tasks, raft-ui SelectionToolbar ([build.raft.designsystem.components.RaftSelectionToolbar]),
 * floating, centred, over the bottom of the conversation in the composer's place
 * ([build.raft.designsystem.components.RaftSelectionToolbarDock]: 20dp from the sides, 12dp above
 * the bottom plus the safe area). Nothing behind it: no full-width bar, no top divider.
 *
 * Order, as the web's: (in a thread, the select-all checkbox, botiverse/slock #12681) · "N
 * selected" · Forward · Generate image · Copy Link · Copy MD · (More) · × (Cancel, last), each a
 * 44dp bar button showing its icon alone, its name the label and the long-press tooltip. The last
 * three are secondary: when the row doesn't fit, they give way into More first, the last first,
 * then Forward; More shows only then, its menu opening upward. With 0 selected the toolbar stays,
 * every action is disabled, and only × ends the selection.
 */
@Composable
internal fun ThreadShareSelectionToolbar(
    selectedCount: Int,
    onCancel: () -> Unit,
    selectAll: ThreadShareSelectAll? = null,
    onForward: () -> Unit,
    forwardEnabled: Boolean = true,
    onExportImage: () -> Unit,
    onCopyLinks: () -> Unit,
    onCopyMarkdown: (() -> Unit)? = null,
    bottomSafeArea: Dp = raftBottomSafeAreaPadding()
) {
    val hasSelection = selectedCount > 0
    var moreMenuAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    // The actions in More while it shows (the ones that don't fit the row), for its menu.
    var moreFoldedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    val moreAnchor = remember { RaftLiveRootAnchorRef(identity = "thread_share_selection_more") }
    val iconSize = build.raft.designsystem.components.RaftSelectionToolbarDefaults.IconSize
    val icons =
        mapOf(
            "forward" to RaftLucideIconName.Forward,
            "generate-image" to RaftLucideIconName.Image,
            "copy-link" to RaftLucideIconName.Link,
            "copy-md" to RaftLucideIconName.Copy
        )
    fun spec(id: String, label: String, onClick: () -> Unit, enabled: Boolean, secondary: Boolean) =
        build.raft.designsystem.components.RaftSelectionToolbarActionSpec(
            id = id,
            label = label,
            onClick = onClick,
            enabled = enabled,
            secondary = secondary,
            icon = { ThreadShareSelectionGlyph(icons.getValue(id), iconSize) }
        )
    val actions =
        listOfNotNull(
            spec("forward", stringResource(Res.string.thread_action_forward), onForward, hasSelection && forwardEnabled, secondary = false),
            spec("generate-image", stringResource(Res.string.thread_share_generate_image), onExportImage, hasSelection, secondary = true),
            spec("copy-link", stringResource(Res.string.thread_action_copy_link), onCopyLinks, hasSelection, secondary = true),
            onCopyMarkdown?.let {
                spec("copy-md", stringResource(Res.string.thread_share_copy_markdown), it, hasSelection, secondary = true)
            }
        )
    build.raft.designsystem.components.RaftSelectionToolbarDock(bottomSafeArea = bottomSafeArea) {
        val saysAll = selectAll != null && threadShareSelectionSaysAll(selectAll.check, selectAll.complete)
        val selectAllLabel = stringResource(Res.string.thread_share_selection_select_all_in_thread)
        val moreLabel = stringResource(Res.string.thread_share_selection_more_actions)
        val moreTooltip = stringResource(Res.string.thread_new_message_menu_more)
        val cancelLabel = stringResource(Res.string.common_cancel)
        build.raft.designsystem.components.RaftSelectionToolbar(
            count =
                when {
                    !saysAll -> stringResource(Res.string.thread_forward_selected, selectedCount)
                    selectedCount == 1 -> stringResource(Res.string.thread_share_selection_all_selected_one, selectedCount)
                    else -> stringResource(Res.string.thread_share_selection_all_selected, selectedCount)
                },
            countValue = selectedCount.toString(),
            label = stringResource(Res.string.thread_share_selection_toolbar_label),
            leading =
                selectAll?.let { state ->
                    {
                        build.raft.designsystem.components.RaftCheckbox(
                            checked = state.check == ThreadShareSelectAllCheck.Checked,
                            indeterminate = state.check == ThreadShareSelectAllCheck.Indeterminate,
                            onCheckedChange = { state.onClick() },
                            enabled = state.enabled,
                            modifier = Modifier.semantics { contentDescription = selectAllLabel }
                        )
                    }
                },
            actions = actions,
            more = { folded ->
                build.raft.designsystem.components.RaftSelectionToolbarMore(
                    onClick = {
                        moreFoldedIds = folded.map { it.id }
                        moreAnchor.resolve(RaftPopoverLocalPoint.BottomEnd)?.let { moreMenuAnchor = it }
                    },
                    label = moreLabel,
                    tooltip = moreTooltip,
                    open = moreMenuAnchor != null,
                    modifier = Modifier.raftCaptureLiveRootAnchor(moreAnchor)
                ) {
                    ThreadShareSelectionGlyph(RaftLucideIconName.Ellipsis, iconSize)
                }
            },
            close = {
                build.raft.designsystem.components.RaftSelectionToolbarClose(onClick = onCancel, label = cancelLabel)
            }
        )
    }
    moreMenuAnchor?.let { anchor ->
        // The folded actions as they are now (labels, enabled), in their order.
        val menuActions = actions.filter { it.id in moreFoldedIds }
        RaftUiControlledMenu(
            expanded = true,
            anchor = anchor,
            items = menuActions.map { RaftUiMenuItem(id = it.id, label = it.label, icon = icons.getValue(it.id), enabled = it.enabled) },
            onSelect = { id ->
                moreMenuAnchor = null
                menuActions.firstOrNull { it.id == id }?.onClick?.invoke()
            },
            onDismiss = { moreMenuAnchor = null },
            // web SelectModeToolbar's DropdownMenuContent `side="top" align="end" sideOffset={8}`.
            verticalOffset = 8.dp,
            verticalPlacement = RaftFloatingVerticalPlacement.Above
        )
    }
}

@Composable
private fun ThreadShareSelectionGlyph(icon: RaftLucideIconName, size: Dp) {
    val ink =
        build.raft.designsystem.theme.LocalRaftContentColor.current.takeIf { it != Color.Unspecified }
            ?: build.raft.designsystem.theme.RaftTheme.colors.foreground
    RaftLucideIcon(icon, modifier = Modifier.size(size), color = raftArgbHex(ink))
}

/**
 * Share preview's off-screen image (Android, iOS, desktop; see [ThreadShareImageOffscreenRenderer]):
 * the image laid out at [choice]'s width ([image] null until the first one is ready, or when it
 * failed), whether a new layout is [rendering], and the width picker's change.
 */
internal data class ThreadSharePreviewOffscreen(
    val image: ThreadShareRenderedImage?,
    val rendering: Boolean,
    val choice: ThreadShareImageWidthChoice,
    val onChoiceChange: (ThreadShareImageWidthChoice) -> Unit,
)

@Composable
internal fun ThreadSharePreviewModal(
    messages: List<RaftThreadMessageItem>,
    image: ThreadSharePreviewImage?,
    onDismiss: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onDownloadImage: () -> Unit,
    /** Non-null: the image is laid out off screen at a width the user picks (null: legacy native export). */
    offscreen: ThreadSharePreviewOffscreen? = null,
) {
    RaftUnifiedDialog(
        title = stringResource(Res.string.thread_share_preview_title),
        uppercaseTitle = false,
        onDismiss = onDismiss,
        minWidth = 320.dp,
        maxWidth = 360.dp,
        contentPadding = 16.dp,
        contentSpacing = 12.dp
    ) {
        // #proj-mobile:#359: give the preview/empty body `weight(1f, fill = false)` so the
        // dialog footer ("Copy MD" / "Download") is always measured first and stays inside the
        // dialog bounds on compact phones, with the Custom width slider open, or in landscape.
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (offscreen != null) {
                ThreadShareImageWidthPicker(choice = offscreen.choice, onChoiceChange = offscreen.onChoiceChange)
                if (offscreen.image == null && !offscreen.rendering) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        ThreadModalEmptyState(
                            title = stringResource(Res.string.thread_share_export_failed),
                            detail = stringResource(Res.string.thread_share_export_failed_detail)
                        )
                    }
                } else {
                    ThreadShareImagePreview(
                        image = offscreen.image?.preview,
                        rendering = offscreen.rendering,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                RaftDialogFooter {
                    RaftModalActionButton(
                        label = stringResource(Res.string.thread_share_copy_markdown),
                        icon = RaftLucideIconName.Copy,
                        onClick = onCopyMarkdown,
                        enabled = messages.isNotEmpty()
                    )
                    RaftModalActionButton(
                        label = stringResource(Res.string.thread_share_download),
                        icon = RaftLucideIconName.Download,
                        onClick = onDownloadImage,
                        enabled = offscreen.image != null && !offscreen.rendering
                    )
                }
                return@Column
            }
            if (image?.uri.isNullOrBlank()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                ) {
                    ThreadModalEmptyState(
                        title = stringResource(Res.string.thread_share_export_failed),
                        detail = stringResource(Res.string.thread_share_export_failed_detail)
                    )
                }
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .height(threadSharePreviewImageHeight(image).dp)
                            .raftCardSurface()
                            .padding(8.dp)
                ) {
                    RaftCachedImage(
                        source = image.uri,
                        contentDescription = stringResource(Res.string.thread_share_image_description),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            RaftDialogFooter {
                RaftModalActionButton(
                    label = stringResource(Res.string.thread_share_copy_markdown),
                    icon = RaftLucideIconName.Copy,
                    onClick = onCopyMarkdown,
                    enabled = messages.isNotEmpty()
                )
                RaftModalActionButton(
                    label = stringResource(Res.string.thread_share_download),
                    icon = RaftLucideIconName.Download,
                    onClick = onDownloadImage,
                    enabled = !image?.path.isNullOrBlank()
                )
            }
        }
    }
}

private fun threadSharePreviewImageHeight(image: ThreadSharePreviewImage?): Int {
    val width = image?.width?.takeIf { it > 0 } ?: return 280
    val height = image.height.takeIf { it > 0 } ?: return 280
    return ((height.toFloat() / width.toFloat()) * 320f).roundToInt().coerceIn(180, 440)
}
