package build.raft.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import build.raft.app.markdown.markdownToPreviewText
import build.raft.app.resources.*
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 * The reply thread head's title (raft-design conversation.md "Threads"; web botiverse/slock #15285,
 * `threadHeaderTitle.ts` / `ThreadPanelHeader.tsx`): "Thread" while the message the thread started
 * from is in view at the top of the replies; once it scrolls out of view, its sender and first line
 * ("Juno: Can we ship…") on one line, ellipsized. Only the words change (the head keeps its height
 * and its "#channel · N replies" line); the two faces cross-fade. No avatar and no Agent tag, as a
 * reply's author name. Its accessible name is the words shown with "Thread:" before them; the head
 * is not a live region, so the change is not announced.
 */

/** What the title can say about the thread's original message (web `ThreadHeaderOriginal`). */
internal sealed interface ThreadHeaderOriginal {
    /** Loaded and readable: the swapped title names [sender] and [text]; the place name locates [messageId]. */
    data class Shown(val messageId: String, val sender: String, val text: String) : ThreadHeaderOriginal

    /** Retracted or gone: the title stays "Thread"; the place name only opens the conversation. */
    data object Deleted : ThreadHeaderOriginal

    /** The person cannot read its conversation: the place name is plain text, View in channel is not offered. */
    data object Unavailable : ThreadHeaderOriginal
}

/**
 * The title's original, or null while nothing is known of it (the title stays "Thread", the place
 * name opens the conversation). A read refused for permission is [ThreadHeaderOriginal.Unavailable];
 * any other failed read (a parent behind the plan's history window answers 404 like a network
 * miss) is null, so the place name still opens the conversation, where it stops at the paywall.
 */
internal fun resolveThreadHeaderOriginal(
    originStatus: String,
    originErrorCode: String?,
    origin: RaftThreadMessageItem?,
    imageLabel: String,
    fileLabel: String,
    /** The localized "External user", shown for an External author the wire did not name. */
    externalFallbackName: String = "",
): ThreadHeaderOriginal? {
    if (originStatus == "error" &&
        threadUserFacingErrorCodeReason(originErrorCode) == ThreadUserFacingErrorReason.PermissionDenied
    ) {
        return ThreadHeaderOriginal.Unavailable
    }
    if (originStatus == "missing" || origin?.isRetracted == true) return ThreadHeaderOriginal.Deleted
    if (origin == null || originStatus == "error") return null
    val sender =
        threadHeaderSafeTitleOrNull(
            origin.externalAuthor
                ?.let { externalAuthorShownName(it, it.displayName, externalFallbackName) }
                ?.takeIf { it.isNotBlank() }
                ?: origin.authorName.takeIf { it.isNotBlank() }
                ?: origin.authorHandle,
            setOf(origin.authorId)
        ) ?: return null
    val text = threadHeaderOriginalFirstLine(origin.body, origin.attachments, imageLabel, fileLabel) ?: return null
    return ThreadHeaderOriginal.Shown(messageId = origin.id, sender = sender, text = text)
}

/**
 * Web `threadOriginalFirstLine`: the first non-empty line of the message as plain text (no Markdown
 * syntax; an @mention stays its words), or, with no text, "Image" when every file is an image and
 * "File" otherwise. Null when it has neither.
 */
internal fun threadHeaderOriginalFirstLine(
    body: String,
    attachments: List<RaftThreadAttachmentItem>,
    imageLabel: String,
    fileLabel: String,
): String? {
    body.lineSequence()
        .map { line -> markdownToPreviewText(line).trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.let { return it }
    if (attachments.isEmpty()) return null
    val allImages = attachments.all { it.mimeType.startsWith("image/", ignoreCase = true) }
    return if (allImages) imageLabel else fileLabel
}

/**
 * Whether the original's row (the list's [originKey] item) has scrolled fully out of the list's
 * viewport. A row the list no longer lays out is out of view; nothing laid out yet is not.
 */
internal fun threadOriginRowOutOfView(layoutInfo: LazyListLayoutInfo, originKey: Any = ThreadOriginListItemKey): Boolean {
    val visible = layoutInfo.visibleItemsInfo
    val row = visible.firstOrNull { it.key == originKey }
    return threadOriginRowOutOfView(
        anyRowLaidOut = visible.isNotEmpty(),
        rowOffset = row?.offset,
        rowSize = row?.size ?: 0,
        viewportStart = layoutInfo.viewportStartOffset,
        viewportEnd = layoutInfo.viewportEndOffset,
    )
}

/** [threadOriginRowOutOfView] on the row's laid-out geometry ([rowOffset] null: not laid out). */
internal fun threadOriginRowOutOfView(
    anyRowLaidOut: Boolean,
    rowOffset: Int?,
    rowSize: Int,
    viewportStart: Int,
    viewportEnd: Int,
): Boolean {
    if (!anyRowLaidOut) return false
    if (rowOffset == null) return true
    return rowOffset + rowSize <= viewportStart || rowOffset >= viewportEnd
}

/**
 * The thread page's title state: the list writes it ([ThreadHeaderOriginalReporter]), the head reads
 * it ([ThreadHeaderTitleText]), and the page's "open the parent conversation" reads which message to
 * locate ([locateMessageId]). One per thread page.
 */
@Stable
internal class ThreadHeaderOriginalState {
    var original: ThreadHeaderOriginal? by mutableStateOf(null)
    var originOutOfView: Boolean by mutableStateOf(false)

    /** The original names the title now. */
    val swapped: Boolean get() = original is ThreadHeaderOriginal.Shown && originOutOfView

    /** False where the person can't open the parent conversation: no link, no View in channel. */
    val parentReachable: Boolean get() = original !is ThreadHeaderOriginal.Unavailable

    /** The message the place name and View in channel locate; null opens the conversation alone. */
    fun locateMessageId(): String? = (original as? ThreadHeaderOriginal.Shown)?.messageId
}

internal val LocalThreadHeaderOriginalState = staticCompositionLocalOf<ThreadHeaderOriginalState?> { null }

/** The thread's replies list: reports what the head's title can say and whether the original is in view. */
@Composable
internal fun ThreadHeaderOriginalReporter(
    listState: LazyListState,
    timelineSlice: ThreadTimelineSlice,
    originErrorCode: String?,
) {
    val state = LocalThreadHeaderOriginalState.current ?: return
    val replyThread = timelineSlice.replyThreadSurface
    val imageLabel = stringResource(Res.string.thread_reply_title_preview_image)
    val fileLabel = stringResource(Res.string.thread_reply_title_preview_file)
    val externalFallbackName = rememberExternalAuthorFallbackName()
    val originStatus = timelineSlice.originStatus
    val origin = timelineSlice.originMessage
    val original =
        remember(replyThread, originStatus, originErrorCode, origin, imageLabel, fileLabel, externalFallbackName) {
            if (!replyThread) {
                null
            } else {
                resolveThreadHeaderOriginal(originStatus, originErrorCode, origin, imageLabel, fileLabel, externalFallbackName)
            }
        }
    SideEffect {
        if (state.original != original) state.original = original
    }
    val originRowShown = replyThread && timelineSlice.hasThreadOriginItem()
    LaunchedEffect(state, listState, originRowShown) {
        if (!originRowShown) {
            if (state.originOutOfView) state.originOutOfView = false
            return@LaunchedEffect
        }
        snapshotFlow { threadOriginRowOutOfView(listState.layoutInfo) }
            .distinctUntilChanged()
            .collect { out -> if (state.originOutOfView != out) state.originOutOfView = out }
    }
    // The list left (another tab): the title is "Thread" again until it is back.
    DisposableEffect(state) {
        onDispose { if (state.originOutOfView) state.originOutOfView = false }
    }
}

internal const val ThreadHeaderTitleTestTag = "thread-header-title"

/** web TITLE_FACE_FADE: 140 ms, no movement. */
private const val ThreadHeaderTitleFadeMillis = 140

/** The words the swapped title shows: "<sender><separator><first line>". */
internal fun threadHeaderOriginalTitleText(original: ThreadHeaderOriginal.Shown, separator: String): String =
    original.sender + separator + original.text

/**
 * The thread head's title text: [normalTitle], or, when [state] says the original has scrolled away,
 * its sender (the title's weight) and first line (medium), ellipsized on one line. The faces
 * cross-fade (Compose follows the system's "remove animations" on Android). [state] null: [normalTitle].
 */
@Composable
internal fun ThreadHeaderTitleText(
    normalTitle: String,
    state: ThreadHeaderOriginalState?,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val original = state?.original
    val shown = (original as? ThreadHeaderOriginal.Shown)?.takeIf { state.originOutOfView }
    val accessibleName =
        when {
            original is ThreadHeaderOriginal.Deleted -> stringResource(Res.string.thread_title_original_deleted_a11y)
            original is ThreadHeaderOriginal.Unavailable -> stringResource(Res.string.thread_title_original_unavailable_a11y)
            shown != null -> stringResource(Res.string.thread_title_original_a11y, shown.sender, shown.text)
            else -> normalTitle
        }
    val separator = stringResource(Res.string.thread_title_original_separator)
    val visibleText = shown?.let { threadHeaderOriginalTitleText(it, separator) } ?: normalTitle
    Crossfade(
        targetState = shown,
        animationSpec = tween(ThreadHeaderTitleFadeMillis),
        label = "threadHeaderTitle",
        modifier =
            modifier
                .testTag(ThreadHeaderTitleTestTag)
                // One node for both faces: the words shown, named with "Thread:" before them. Not a
                // live region, so the change is not announced while the replies scroll.
                .clearAndSetSemantics {
                    contentDescription = accessibleName
                    text = AnnotatedString(visibleText)
                },
    ) { face ->
        Text(
            text = face?.let { threadHeaderOriginalTitleAnnotated(it, separator) } ?: AnnotatedString(normalTitle),
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun threadHeaderOriginalTitleAnnotated(original: ThreadHeaderOriginal.Shown, separator: String): AnnotatedString =
    buildAnnotatedString {
        append(original.sender)
        append(separator)
        // web: the message's words are medium, the sender keeps the title's weight.
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(original.text) }
    }
