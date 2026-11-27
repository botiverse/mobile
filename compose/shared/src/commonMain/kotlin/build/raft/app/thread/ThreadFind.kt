package build.raft.app.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.RaftAppStroke
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftType
import build.raft.app.components.RaftSlotIcon
import build.raft.app.components.rememberRaftDesignSystemEditor
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedThreadFindStore
import build.raft.app.data.ThreadFindEvent
import build.raft.app.data.ThreadFindIntent
import build.raft.app.data.ThreadFindMessage
import build.raft.app.data.ThreadFindPageSource
import build.raft.app.data.ThreadFindState
import build.raft.app.data.ThreadFindWindow
import build.raft.app.data.normalizeThreadFindQuery
import build.raft.app.messages
import build.raft.app.origin
import build.raft.app.raftLogInfo
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldSize
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.resources.*
import build.raft.app.shownAuthorName
import build.raft.app.thread.shownAuthorName
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding

/**
 * Search in this thread (web `useThreadSearch` / `ThreadSearchBar`): what the page needs to read the
 * whole thread ([source]; null in fixtures and screenshots: only the loaded messages are searched)
 * and to say a failed read ([onLoadFailed]: an error toast with its trace id).
 */
internal class ThreadFindEnvironment(
    val source: ThreadFindPageSource?,
    val onLoadFailed: (ScreenError) -> Unit,
)

internal val LocalThreadFindEnvironment = compositionLocalOf<ThreadFindEnvironment?> { null }

/** The page's find bar: its store, and whether the conversation is a task's (the placeholder says so). */
@Stable
internal class ThreadFindController(
    val store: SharedThreadFindStore,
    val taskSurface: Boolean,
)

/** The page's find bar for its message list; null where the page has no thread search. */
internal val LocalThreadFindController = compositionLocalOf<ThreadFindController?> { null }

/**
 * The open match's words, for its message body (web `threadSearchHighlightQuery`, given to the
 * active match only); null elsewhere.
 */
internal val LocalThreadFindHighlightQuery = compositionLocalOf<String?> { null }

/** The thread event that brings a match the page hasn't loaded into it (the jump the thread timeline makes). */
internal const val ThreadFindJumpAction = "focusThreadFindMatch"

internal object ThreadFindTestTags {
    const val Bar = "thread-find-bar"
    const val Input = "thread-find-input"
    const val Count = "thread-find-count"
    const val Previous = "thread-find-previous"
    const val Next = "thread-find-next"
    const val Close = "thread-find-close"
}

/** One store per conversation, cleared when it leaves. */
@Composable
internal fun rememberThreadFindController(threadInstanceKey: String, taskSurface: Boolean): ThreadFindController {
    val environment = LocalThreadFindEnvironment.current
    val scope = rememberCoroutineScope()
    val store =
        remember(threadInstanceKey, environment?.source) {
            SharedThreadFindStore(
                source = environment?.source,
                scope = scope,
                logger = { line -> raftLogInfo("ThreadFind", line) },
            )
        }
    DisposableEffect(store) { onDispose { store.clear() } }
    val onLoadFailed = rememberUpdatedState(environment?.onLoadFailed)
    LaunchedEffect(store) {
        store.events.collect { event ->
            when (event) {
                is ThreadFindEvent.LoadFailed -> onLoadFailed.value?.invoke(event.error)
            }
        }
    }
    return remember(store, taskSurface) { ThreadFindController(store, taskSurface) }
}

internal fun RaftThreadMessageItem.toThreadFindMessage(): ThreadFindMessage =
    ThreadFindMessage(id = id, seq = seq, senderName = shownAuthorName(), content = body)

/** What the page holds of the thread, for the find bar; null without a thread channel. */
internal fun ThreadTimelineSlice.threadFindWindow(): ThreadFindWindow? {
    val threadChannelId = (historyChannelId ?: sourceThreadId)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return ThreadFindWindow(
        threadChannelId = threadChannelId,
        origin = originMessage?.toThreadFindMessage(),
        messages = messages.map { it.toThreadFindMessage() },
        hasOlder = canLoadMoreBefore,
        hasNewer = canLoadMoreAfter,
    )
}

/** web ThreadSearchBar's count: none without words, "Loading" while the thread is read, else "n/N". */
internal fun threadFindCountText(state: ThreadFindState, loadingLabel: String): String =
    when {
        normalizeThreadFindQuery(state.query).isEmpty() -> ""
        state.loading -> loadingLabel
        state.matches.isEmpty() -> "0/0"
        else -> "${state.activeIndex + 1}/${state.matches.size}"
    }

/**
 * The page's find bar while it is open: the message list draws it over its top, right under the
 * header (web ThreadPanel puts ThreadSearchBar first in its content), and keeps its first rows clear
 * of it.
 */
@Composable
internal fun ThreadFindBarHost(controller: ThreadFindController, modifier: Modifier = Modifier) {
    val state by controller.store.state.collectAsState()
    if (state.open) {
        ThreadFindBar(state = state, taskSurface = controller.taskSurface, onIntent = controller.store::dispatch, modifier = modifier)
    }
}

/**
 * The bar of Search in this thread (web `ThreadSearchBar`): the words, the match count, Previous /
 * Next and Close, over a seam at its bottom (web `border-b border-line-seam`). Enter goes to the
 * next match, Shift+Enter to the previous, Esc closes.
 */
@Composable
internal fun ThreadFindBar(
    state: ThreadFindState,
    taskSurface: Boolean,
    onIntent: (ThreadFindIntent) -> Unit,
    modifier: Modifier = Modifier,
    // web: opening the bar focuses its field (screenshots hold the caret still).
    autoFocus: Boolean = true,
) {
    val colors = RaftTheme.colors
    val placeholder = stringResource(if (taskSurface) Res.string.thread_placeholder_search_task else Res.string.thread_placeholder_search)
    val previousLabel = stringResource(Res.string.thread_find_previous_match)
    val nextLabel = stringResource(Res.string.thread_find_next_match)
    val closeLabel = stringResource(Res.string.thread_find_close)
    val countText = threadFindCountText(state, stringResource(Res.string.thread_find_loading))
    val hasMatches = state.matches.isNotEmpty()
    val focusRequester = remember { FocusRequester() }
    val editor =
        rememberRaftDesignSystemEditor(
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onIntent(ThreadFindIntent.Next) }, onDone = { onIntent(ThreadFindIntent.Next) }),
            keyboardHeightChange = {},
            focusRequester = focusRequester,
            autoFocus = autoFocus,
            onFocus = {},
            onBlur = {},
        )
    Column(modifier = modifier.fillMaxWidth().background(colors.layerCanvas).testTag(ThreadFindTestTags.Bar)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .raftSafeAreaHorizontalPadding()
                    // web `px-3 py-2`, `gap-1`.
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RaftTextField(
                value = state.query,
                onValueChange = { onIntent(ThreadFindIntent.Query(it)) },
                modifier =
                    Modifier
                        .weight(1f)
                        .then(editor.modifier)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Escape -> {
                                    onIntent(ThreadFindIntent.Close)
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    onIntent(if (event.isShiftPressed) ThreadFindIntent.Previous else ThreadFindIntent.Next)
                                    true
                                }
                                else -> false
                            }
                        }
                        .testTag(ThreadFindTestTags.Input),
                placeholder = placeholder,
                leading = { RaftSlotIcon(RaftLucideIconName.Search) },
                keyboardOptions = editor.keyboardOptions,
                keyboardActions = KeyboardActions(onSearch = { onIntent(ThreadFindIntent.Next) }, onDone = { onIntent(ThreadFindIntent.Next) }),
                size = RaftTextFieldSize.Md,
            )
            // web `w-16 text-center font-mono text-mono-meta text-foreground-hint`.
            Text(
                text = countText,
                modifier = Modifier.width(64.dp).testTag(ThreadFindTestTags.Count),
                style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontWeight = FontWeight.Normal),
                color = colors.foregroundHint,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RaftIconButton(
                onClick = { onIntent(ThreadFindIntent.Previous) },
                tooltip = previousLabel,
                modifier = Modifier.testTag(ThreadFindTestTags.Previous),
                variant = RaftButtonVariant.Ghost,
                enabled = hasMatches,
            ) {
                RaftSlotIcon(RaftLucideIconName.ChevronUp, size = 14.dp)
            }
            RaftIconButton(
                onClick = { onIntent(ThreadFindIntent.Next) },
                tooltip = nextLabel,
                modifier = Modifier.testTag(ThreadFindTestTags.Next),
                variant = RaftButtonVariant.Ghost,
                enabled = hasMatches,
            ) {
                RaftSlotIcon(RaftLucideIconName.ChevronDown, size = 14.dp)
            }
            RaftIconButton(
                onClick = { onIntent(ThreadFindIntent.Close) },
                tooltip = closeLabel,
                modifier = Modifier.testTag(ThreadFindTestTags.Close),
                variant = RaftButtonVariant.Ghost,
            ) {
                RaftSlotIcon(RaftLucideIconName.X, size = 14.dp)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderSeam))
    }
}
