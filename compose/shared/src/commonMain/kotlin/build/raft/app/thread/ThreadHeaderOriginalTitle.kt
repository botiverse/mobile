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
import androidx.compose.runtime.rememberUpdatedState
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
import build.raft.app.base.BridgeModule
import build.raft.app.data.AdaptiveParentFocusRequest
import build.raft.app.data.MessageContextHiddenTarget
import build.raft.app.data.MessageContextHistoryLimitedCode
import build.raft.app.data.RAFT_ADAPTIVE_DETAIL_FOCUS_MESSAGE_EVENT
import build.raft.app.data.RaftEventBus
import build.raft.app.data.ScreenError
import build.raft.app.data.extractQuoteReplyPreviewText
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
    /**
     * Loaded and readable: the swapped title names [sender] and [text]; the place name locates [messageId].
     * [behindHistory]: the original is behind the plan's history window (known only from the server's
     * glimpse of it). It is never a row of the list, so the title names it from the start (web
     * `parentOutOfView = !!glimpse`), and the place name opens the conversation alone, where it stops
     * at the paywall.
     */
    data class Shown(
        val messageId: String,
        val sender: String,
        val text: String,
        val behindHistory: Boolean = false,
    ) : ThreadHeaderOriginal

    /** Retracted or gone: the title stays "Thread"; the place name only opens the conversation. */
    data object Deleted : ThreadHeaderOriginal

    /** The person cannot read its conversation: the place name is plain text, View in channel is not offered. */
    data object Unavailable : ThreadHeaderOriginal
}

/**
 * The title's original, or null while nothing is known of it (the title stays "Thread", the place
 * name opens the conversation). A read refused for permission is [ThreadHeaderOriginal.Unavailable].
 * A parent behind the plan's history window ([hiddenTarget], from the `history_limited` refusal) is
 * named from the server's glimpse of it, as the web does (`resolveThreadHeaderOriginal` `glimpse`);
 * any other failed read is null, so the place name still opens the conversation.
 */
internal fun resolveThreadHeaderOriginal(
    originStatus: String,
    originErrorCode: String?,
    origin: RaftThreadMessageItem?,
    imageLabel: String,
    fileLabel: String,
    /** The localized "External user", shown for an External author the wire did not name. */
    externalFallbackName: String = "",
    /** The parent behind the history window ([ThreadTimelineSlice.originHiddenTarget]). */
    hiddenTarget: MessageContextHiddenTarget? = null,
    /** The localized "Unknown", for a hidden parent whose sender the server does not name. */
    unknownSenderLabel: String = "",
): ThreadHeaderOriginal? {
    if (originStatus == "error" &&
        threadUserFacingErrorCodeReason(originErrorCode) == ThreadUserFacingErrorReason.PermissionDenied
    ) {
        return ThreadHeaderOriginal.Unavailable
    }
    if (originStatus == "missing" || origin?.isRetracted == true) return ThreadHeaderOriginal.Deleted
    if (origin == null && originStatus == "error" && hiddenTarget != null) {
        return threadHeaderHiddenOriginal(hiddenTarget, unknownSenderLabel)
    }
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
 * A parent behind the history window, named from the server's glimpse: its sender ([unknownSenderLabel]
 * when unnamed) and the first line of its preview. Null when the glimpse has no words (web: the title
 * stays "Thread").
 */
internal fun threadHeaderHiddenOriginal(
    hiddenTarget: MessageContextHiddenTarget,
    unknownSenderLabel: String,
): ThreadHeaderOriginal.Shown? {
    val text = threadHeaderOriginalFirstLine(hiddenTarget.preview.orEmpty(), emptyList(), "", "") ?: return null
    val sender =
        threadHeaderSafeTitleOrNull(hiddenTarget.senderName, setOf(hiddenTarget.senderId))
            ?: unknownSenderLabel.takeIf { it.isNotBlank() }
            ?: return null
    return ThreadHeaderOriginal.Shown(messageId = hiddenTarget.messageId, sender = sender, text = text, behindHistory = true)
}

/**
 * The parent's glimpse in a failed `messages/context` read of a reply thread's parent: the
 * `history_limited` refusal's target when it is [parentMessageId]. A 404 that names a code this app
 * does not know, or a `history_limited` one without a readable target, is reported (AGENTS.md "No
 * silent drops") and read as not found.
 */
internal fun ScreenError.threadParentHiddenTargetOrNull(
    parentMessageId: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): MessageContextHiddenTarget? {
    if (code != "http_404") return null
    val wireCode = serverCode ?: return null
    if (wireCode != MessageContextHistoryLimitedCode) {
        reportUnknownTypeDropped("thread_parent_context", "code", wireCode, fallback = "not_found", diagnostics = diagnostics)
        return null
    }
    val target = historyLimitedTarget?.takeIf { it.messageId == parentMessageId }
    if (target == null) {
        reportUnknownTypeDropped(
            "thread_parent_context",
            "hiddenTarget",
            if (historyLimitedTarget == null) "unreadable" else "other_message",
            fallback = "thread_title",
            diagnostics = diagnostics,
        )
    }
    return target
}

/** [RaftThreadScreenPayload.originHiddenTarget] while it is this reply thread's parent and its read failed. */
internal fun RaftThreadScreenPayload.threadOriginHiddenTargetOrNull(): MessageContextHiddenTarget? {
    val target = originHiddenTarget ?: return null
    if (surfaceKind != RaftThreadSurfaceKind.ReplyThread || originStatus != "error" || originMessage != null) return null
    val parentId = parentMessageId?.takeIf { it.isNotBlank() } ?: originParentMessageId
    return target.takeIf { it.messageId == parentId }
}

/** What "open the parent conversation" locates there. */
internal enum class ThreadParentLocate {
    /** The original message (anchor + highlight). */
    Message,

    /** Where the shown history starts, the history paywall card: the original is behind the plan's history window. */
    HistoryStart,

    /** Nothing: the original is deleted, so the conversation only opens. */
    None,
}

/**
 * What "open the parent conversation" locates (raft-design conversation.md "Threads"; web
 * `openThreadParentInConversation` `locate`): nothing for a deleted original; for one behind the
 * plan's history window, the history start where the paywall card is (design, Kirby: in place
 * beside the thread); else the original.
 */
internal fun RaftThreadScreenPayload.threadParentLocate(): ThreadParentLocate =
    when {
        surfaceKind != RaftThreadSurfaceKind.ReplyThread -> ThreadParentLocate.Message
        originStatus == "missing" || originMessage?.isRetracted == true -> ThreadParentLocate.None
        threadOriginHiddenTargetOrNull() != null -> ThreadParentLocate.HistoryStart
        else -> ThreadParentLocate.Message
    }

/**
 * The side column's "open parent conversation" (task #36), beside the conversation it came from:
 * asks the main detail to locate the original ([request]) or, for one behind the history window, to
 * scroll to its history paywall card; the thread stays open. A deleted original has nothing to
 * locate: the conversation, already open beside the thread, is left as it is (web: `if (!locate)
 * return`). The caller has already handed input focus away.
 */
internal fun postSideThreadParentFocus(
    request: AdaptiveParentFocusRequest,
    thread: RaftThreadScreenPayload,
    bridge: BridgeModule,
    eventBus: RaftEventBus,
) {
    val locate = thread.threadParentLocate()
    if (locate == ThreadParentLocate.None) {
        bridge.log("adaptive_side_thread_parent_focus result=open_only generation=${request.target.generation}")
        return
    }
    val historyStart = locate == ThreadParentLocate.HistoryStart
    bridge.log(
        "adaptive_side_thread_parent_focus result=requested historyStart=$historyStart generation=${request.target.generation}"
    )
    eventBus.post(
        eventName = RAFT_ADAPTIVE_DETAIL_FOCUS_MESSAGE_EVENT,
        eventData = request.copy(historyStart = historyStart).toJson(),
        crossProcess = true,
        source = "adaptive_side_thread_parent",
    )
}

/** The main detail's answer to a side thread's parent request: focus the message, or scroll to the history start. */
internal fun AdaptiveParentFocusRequest.toThreadFocusIntent(): SharedThreadIntent =
    if (historyStart) {
        SharedThreadIntent.FocusHistoryStart(switchToTab = RaftThreadTab.Chat.wireValue)
    } else {
        SharedThreadIntent.FocusMessage(messageId = messageId, switchToTab = RaftThreadTab.Chat.wireValue)
    }

/**
 * Whether the list's start row is shown, so a jump to the start can pin the top: a reply thread's
 * original row (loaded), or in any other conversation its first row (where a Free plan's history
 * paywall card is, once the oldest page is loaded).
 */
internal fun ThreadTimelineSlice.threadStartRowShown(): Boolean =
    if (replyThreadSurface) hasThreadOriginItem() && originMessage != null else true

/** Calls [onRequest] once for each new [request] value after the first composition (a page's history-start focus). */
@Composable
internal fun ThreadHistoryStartFocusEffect(threadInstanceKey: Any, request: Int, onRequest: () -> Unit) {
    var consumed by remember(threadInstanceKey) { mutableStateOf(request) }
    val latestOnRequest by rememberUpdatedState(onRequest)
    LaunchedEffect(threadInstanceKey, request) {
        if (request > consumed) {
            consumed = request
            latestOnRequest()
        }
    }
}

/**
 * Web `threadOriginalFirstLine`: the first non-empty line of the message as plain text (no Markdown
 * syntax; an @mention stays its words; a quote reply's own words, not its quote), or, with no text,
 * "Image" when every file is an image and "File" otherwise. Null when it has neither.
 */
internal fun threadHeaderOriginalFirstLine(
    body: String,
    attachments: List<RaftThreadAttachmentItem>,
    imageLabel: String,
    fileLabel: String,
): String? {
    // web `notificationPlainText` leads a quote reply with its reply before the first line is taken
    // (botiverse/slock #16158): the whole body, as the per-line preview below can't see its quotes.
    extractQuoteReplyPreviewText(body).lineSequence()
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

    /** The original names the title now (one behind the history window is never in view). */
    val swapped: Boolean
        get() = (original as? ThreadHeaderOriginal.Shown)?.let { it.behindHistory || originOutOfView } == true

    /** False where the person can't open the parent conversation: no link, no View in channel. */
    val parentReachable: Boolean get() = original !is ThreadHeaderOriginal.Unavailable

    /** The message the place name and View in channel locate; null opens the conversation alone. */
    fun locateMessageId(): String? = (original as? ThreadHeaderOriginal.Shown)?.takeUnless { it.behindHistory }?.messageId
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
    val unknownSenderLabel = stringResource(Res.string.thread_history_paywall_unknown_sender)
    val originStatus = timelineSlice.originStatus
    val origin = timelineSlice.originMessage
    val hiddenTarget = timelineSlice.originHiddenTarget
    val original =
        remember(replyThread, originStatus, originErrorCode, origin, hiddenTarget, imageLabel, fileLabel, externalFallbackName, unknownSenderLabel) {
            if (!replyThread) {
                null
            } else {
                resolveThreadHeaderOriginal(
                    originStatus, originErrorCode, origin, imageLabel, fileLabel, externalFallbackName, hiddenTarget, unknownSenderLabel
                )
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
    val shown = (original as? ThreadHeaderOriginal.Shown)?.takeIf { state.swapped }
    val accessibleName =
        when {
            original is ThreadHeaderOriginal.Deleted -> stringResource(Res.string.thread_title_original_deleted_a11y)
            original is ThreadHeaderOriginal.Unavailable -> stringResource(Res.string.thread_title_original_unavailable_a11y)
            shown != null -> stringResource(Res.string.thread_title_original_a11y, shown.sender, shown.text)
            else -> normalTitle
        }
    val separator = stringResource(Res.string.thread_title_original_separator)
    val visibleText = shown?.let { threadHeaderOriginalTitleText(it, separator) } ?: normalTitle
    val titleModifier =
        modifier
            .testTag(ThreadHeaderTitleTestTag)
            // One node for both faces: the words shown, named with "Thread:" before them. Not a
            // live region, so the change is not announced while the replies scroll.
            .clearAndSetSemantics {
                contentDescription = accessibleName
                text = AnnotatedString(visibleText)
            }
    if (state == null) {
        // Channels and DMs never crossfade; drawing Text directly preserves FirstBaseline so wide
        // headers align the subtitle on the title's exact typographic baseline.
        Text(
            text = normalTitle,
            modifier = titleModifier,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Crossfade(
        targetState = shown,
        animationSpec = tween(ThreadHeaderTitleFadeMillis),
        label = "threadHeaderTitle",
        modifier = titleModifier,
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
