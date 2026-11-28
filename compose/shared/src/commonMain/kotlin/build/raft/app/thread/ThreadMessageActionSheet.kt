package build.raft.app.thread

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftNativeSelectableText
import build.raft.app.RaftNativeTextSelection
import build.raft.app.ThreadComposerQuoteInsert
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.AgentContextViewMenuEntryState
import build.raft.app.emoji.RaftEmojiCatalog
import build.raft.app.currentRaftDateTimeContext
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCloseButton
import build.raft.designsystem.components.RaftDrawerDefaults
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.components.RaftWindowDialogProperties
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * #proj-mobile:#425: Lark-style multi-detent resizable bottom sheet for touch message actions.
 *
 * - Built in :shared on top of public [RaftWindowDialog] (zero edits to compose/designsystem).
 * - Opens smoothly at [ThreadMessageSheetDetent.Compact] (~48% viewport), drags seamlessly to
 *   [ThreadMessageSheetDetent.Expanded] (~72%) and [ThreadMessageSheetDetent.Full] (~94%), or
 *   dismisses downward with synced scrim fade and NestedScroll handoff.
 * - Morphs in-place between [ThreadMessageSheetMode.Actions], [ThreadMessageSheetMode.SelectText]
 *   (native TextView/UITextView handles with reactive selection buttons), and
 *   [ThreadMessageSheetMode.EmojiPicker] without stacking a second sheet.
 * - Uses exclusively standard design-system icons ([threadMessageMenuActions] + [RaftSlotIcon])
 *   and the existing quote-reply insertion pipeline ([LocalThreadInPlaceTextSelectionHost]).
 */

internal const val ThreadMessageActionSheetTag = "thread_message_action_sheet"
internal const val ThreadMessageActionSheetSelectTextTag = "thread_message_action_sheet_select_text"
internal const val ThreadMessageActionSheetEmojiPickerTag = "thread_message_action_sheet_emoji_picker"

/** Whether the message action menu should present as the touch multi-detent bottom sheet. */
internal fun threadMessageUsesBottomSheet(
    wide: Boolean,
    coarsePointer: Boolean,
    fromLongPress: Boolean,
): Boolean = !wide || coarsePointer || fromLongPress

internal enum class ThreadMessageSheetDetent {
    Compact,
    Expanded,
    Full,
}

internal enum class ThreadMessageSheetSettle {
    Dismiss,
    Compact,
    Expanded,
    Full,
}

internal enum class ThreadMessageSheetMode {
    Actions,
    SelectText,
    EmojiPicker,
}

private const val SheetCompactFraction = 0.48f
private const val SheetExpandedFraction = 0.72f
private const val SheetFullFraction = 0.94f
private val SheetFlingVelocity = 400.dp
private const val SheetSlideInMillis = 240
private const val SheetSlideOutMillis = 180

/**
 * Pure 3-detent settle math for [ThreadMessageMultiDetentDrawer]:
 * - Fling down (`velocityDown > flingPx`) steps to the next lower stop (Full -> Expanded -> Compact -> Dismiss).
 * - Fling up (`velocityDown < -flingPx`) steps to the next higher stop (Compact -> Expanded -> Full).
 * - Slower release snaps to the nearest of Dismiss (0), Compact, Expanded, and Full.
 */
internal fun threadMessageSheetSettle(
    extent: Float,
    velocityDown: Float,
    compactPx: Float,
    expandedPx: Float,
    fullPx: Float,
    flingPx: Float,
): ThreadMessageSheetSettle {
    val stops =
        listOf(
            ThreadMessageSheetSettle.Dismiss to 0f,
            ThreadMessageSheetSettle.Compact to compactPx,
            ThreadMessageSheetSettle.Expanded to expandedPx,
            ThreadMessageSheetSettle.Full to fullPx,
        )
    return when {
        velocityDown > flingPx -> stops.lastOrNull { it.second < extent - 1f }?.first ?: ThreadMessageSheetSettle.Dismiss
        velocityDown < -flingPx -> stops.firstOrNull { it.second > extent + 1f }?.first ?: ThreadMessageSheetSettle.Full
        else -> stops.minBy { abs(it.second - extent) }.first
    }
}

@Stable
internal class ThreadMessageMultiDetentDrawerState(
    initialDetent: ThreadMessageSheetDetent = ThreadMessageSheetDetent.Compact,
) {
    var detent: ThreadMessageSheetDetent by mutableStateOf(initialDetent)
        private set

    private var heldExtent: Float? by mutableStateOf(null)

    internal var compactPx: Float = 0f
    internal var expandedPx: Float = 0f
    internal var fullPx: Float = 0f

    internal fun anchorPx(target: ThreadMessageSheetDetent): Float =
        when (target) {
            ThreadMessageSheetDetent.Compact -> compactPx
            ThreadMessageSheetDetent.Expanded -> expandedPx
            ThreadMessageSheetDetent.Full -> fullPx
        }

    internal val extent: Float
        get() = heldExtent ?: anchorPx(detent)

    internal val dismissProgress: Float
        get() = if (compactPx <= 0f) 1f else (extent / compactPx).coerceIn(0f, 1f)

    internal var dismissedByDrag: Boolean by mutableStateOf(false)
        private set

    internal val draggable = DraggableState { deltaDown -> moveBy(deltaDown) }

    internal fun moveBy(deltaDown: Float) {
        dismissedByDrag = false
        val maxHeight = max(fullPx, compactPx)
        heldExtent = (extent - deltaDown).coerceIn(0f, maxHeight)
    }

    internal suspend fun animateTo(target: ThreadMessageSheetDetent) {
        dismissedByDrag = false
        if (fullPx > 0f) animateExtentTo(anchorPx(target))
        detent = target
        heldExtent = null
    }

    internal suspend fun animateExtentTo(px: Float) {
        draggable.drag(MutatePriority.Default) {
            var previous = extent
            animate(
                initialValue = previous,
                targetValue = px,
                animationSpec = tween(SheetSlideInMillis, easing = RaftMotion.EaseSlide),
            ) { value, _ ->
                dragBy(previous - value)
                previous = value
            }
        }
    }

    internal suspend fun settle(velocityDown: Float, flingPx: Float): Boolean =
        when (threadMessageSheetSettle(extent, velocityDown, compactPx, expandedPx, fullPx, flingPx)) {
            ThreadMessageSheetSettle.Dismiss -> {
                animateExtentTo(0f)
                dismissedByDrag = true
                true
            }
            ThreadMessageSheetSettle.Compact -> {
                animateTo(ThreadMessageSheetDetent.Compact)
                false
            }
            ThreadMessageSheetSettle.Expanded -> {
                animateTo(ThreadMessageSheetDetent.Expanded)
                false
            }
            ThreadMessageSheetSettle.Full -> {
                animateTo(ThreadMessageSheetDetent.Full)
                false
            }
        }
}

@Composable
internal fun ThreadMessageMultiDetentDrawer(
    state: ThreadMessageMultiDetentDrawerState,
    keepsHostKeyboard: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.(dragModifier: Modifier, nestedScrollConnection: NestedScrollConnection) -> Unit,
) {
    val reducedMotion = rememberRaftReducedMotion()
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismissRequest)
    val flingPx = with(LocalDensity.current) { SheetFlingVelocity.toPx() }
    val slideProgress = remember { Animatable(if (reducedMotion) 1f else 0f) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(reducedMotion) {
        if (!reducedMotion && slideProgress.value < 1f) {
            slideProgress.animateTo(1f, tween(SheetSlideInMillis, easing = RaftMotion.EaseSlide))
        }
    }

    fun requestAnimatedDismiss() {
        if (closing) return
        closing = true
        if (reducedMotion || state.dismissedByDrag) {
            dismiss()
            return
        }
        scope.launch {
            slideProgress.animateTo(0f, tween(SheetSlideOutMillis, easing = RaftMotion.EaseSlide))
            dismiss()
        }
    }

    val dragModifier =
        Modifier.draggable(
            state = state.draggable,
            orientation = Orientation.Vertical,
            onDragStopped = { velocity ->
                if (state.settle(velocity, flingPx)) {
                    dismiss()
                }
            },
        )

    val nestedScrollConnection =
        remember(state, flingPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // Dragging upward while sheet is shorter than Full grows the sheet before scrolling content.
                    if (source == NestedScrollSource.UserInput && available.y < 0f && state.extent < state.fullPx - 0.5f) {
                        val before = state.extent
                        state.moveBy(available.y)
                        val consumedUp = state.extent - before
                        return Offset(0f, -consumedUp)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Dragging downward once inner content hits top (scroll == 0) shrinks/dismisses the sheet.
                    if (source == NestedScrollSource.UserInput && available.y > 0f) {
                        val before = state.extent
                        state.moveBy(available.y)
                        val consumedDown = before - state.extent
                        return Offset(0f, consumedDown)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    // If user is mid-resize drag (between detents or below Compact), settle the sheet on release.
                    val atFull = abs(state.extent - state.fullPx) <= 1f
                    if (!atFull && available.y < -flingPx) {
                        state.settle(available.y, flingPx)
                        return available
                    }
                    return Velocity.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    val atRestDetent =
                        abs(state.extent - state.anchorPx(state.detent)) <= 1f
                    // Only settle if the user actually dragged the sheet off its resting detent;
                    // residual velocity from flinging inner scroll back to top must not collapse the sheet.
                    if (!atRestDetent) {
                        if (state.settle(available.y, flingPx)) {
                            dismiss()
                        }
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }

    RaftWindowDialog(
        onDismissRequest = ::requestAnimatedDismiss,
        properties =
            RaftWindowDialogProperties(
                drawScrim = false,
                platformTransition = false,
                keepsHostKeyboard = keepsHostKeyboard,
            ),
    ) {
        ThreadSheetWindowImePolicy(keepsHostKeyboard = keepsHostKeyboard)
        val colors = RaftTheme.colors
        val scrimColor = RaftTheme.scrim
        val topShape = RoundedCornerShape(topStart = RaftRadius.Dialog, topEnd = RaftRadius.Dialog)
        val navInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
        val topInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val alpha = (slideProgress.value * state.dismissProgress).coerceIn(0f, 1f)
                        drawRect(scrimColor.copy(alpha = scrimColor.alpha * alpha))
                    }
                    // raw-click: scrim backdrop dismiss
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = ::requestAnimatedDismiss,
                    ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            CompositionLocalProvider(
                LocalRaftContentColor provides colors.foreground,
                LocalRaftSurfaceColor provides colors.layerPopover,
            ) {
                Column(
                    modifier =
                        Modifier
                            .windowInsetsPadding(topInsets)
                            .threadMessageSheetSnapLayout(state, slideProgress::value)
                            .widthIn(max = RaftDrawerDefaults.MaxWidth)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .clip(topShape)
                            .background(colors.layerPopover, topShape)
                            .border(RaftStroke.Hairline, colors.lineSeam, topShape)
                            // raw-click: tap sink keeps sheet surface taps off scrim
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            )
                            .windowInsetsPadding(navInsets),
                ) {
                    // Standard 16dp grab handle strip (`RaftDrawer` chrome).
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(RaftDrawerDefaults.GrabHandleHeight)
                                .then(dragModifier),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            Modifier
                                .padding(top = 6.dp)
                                .size(width = 36.dp, height = 4.dp)
                                .background(colors.lineSeam, CircleShape),
                        )
                    }
                    content(dragModifier, nestedScrollConnection)
                }
            }
        }
    }
}

private fun Modifier.threadMessageSheetSnapLayout(
    state: ThreadMessageMultiDetentDrawerState,
    slideProgress: () -> Float,
): Modifier =
    layout { measurable, constraints ->
        if (!constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val maxH = constraints.maxHeight.toFloat()
        val full = (maxH * SheetFullFraction).coerceAtLeast(1f)
        val expanded = (maxH * SheetExpandedFraction).coerceIn(1f, full)
        val compact = (maxH * SheetCompactFraction).coerceIn(1f, expanded)
        state.fullPx = full
        state.expandedPx = expanded
        state.compactPx = compact

        val extent = state.extent
        val height = max(extent, compact).coerceAtMost(full).roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        val dragOffsetY = (compact - extent).coerceAtLeast(0f)
        val enterOffsetY = (1f - slideProgress().coerceIn(0f, 1f)) * height
        layout(placeable.width, height) {
            placeable.place(0, (dragOffsetY + enterOffsetY).roundToInt())
        }
    }

/** Snapshots [selection] on pointer down before buttons take focus, matching [ThreadMessageSelectTextSheet]. */
private fun Modifier.sheetSelectTextPressSnapshot(
    press: ThreadSelectTextPressSelection,
    selection: RaftNativeTextSelection,
): Modifier =
    pointerInput(press, selection) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            press.onPress(selection.selectedText())
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
            } while (event.changes.any { it.pressed })
            press.onGestureEnd()
        }
    }

@Composable
internal fun ThreadMessageActionSheet(
    message: RaftThreadMessageItem,
    replyMetadata: ThreadReplyMetadata,
    messageBodyTextStyle: TextStyle,
    showReplyAction: Boolean,
    showSaveAction: Boolean,
    showFollowAction: Boolean = true,
    showTaskStatusAction: Boolean,
    showConvertToTaskAction: Boolean,
    createTaskFromMessage: Boolean = false,
    showReactionActions: Boolean,
    showCopyLinkAction: Boolean,
    showShareMessagesAction: Boolean,
    showCancelAction: Boolean,
    showDeleteAction: Boolean,
    showTranslateAction: Boolean = false,
    translatePending: Boolean = false,
    showForwardAction: Boolean = false,
    showAddReactionAction: Boolean = true,
    showMarkThreadUnreadAction: Boolean = false,
    onDismiss: () -> Unit,
    onCopyLink: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onCopyCustomText: (String) -> Unit,
    onShareMessages: () -> Unit,
    onSave: () -> Unit,
    onFollowThread: () -> Unit,
    onUnfollowThread: (String) -> Unit,
    onReact: (String) -> Unit,
    onTaskStatusChange: (String) -> Unit,
    onConvertToTask: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onTranslate: () -> Unit = {},
    onForward: () -> Unit = {},
    onMarkThreadUnread: () -> Unit = {},
    onCopySelection: (() -> Unit)? = null,
    onDownloadVideo: (() -> Unit)? = null,
    onQuoteChosen: () -> Unit = {},
    onSelectTextOpened: () -> Unit = {},
    onEmojiPickerOpened: () -> Unit = {},
    link: ThreadMessageLinkMenuSection? = null,
    viewContextState: AgentContextViewMenuEntryState = AgentContextViewMenuEntryState.Hidden,
    onViewContext: () -> Unit = {},
    onRetryViewContext: () -> Unit = {},
    followTarget: ThreadMessageMenuFollowTarget? = null,
) {
    val reactionAtlasImage = LocalThreadReactionAtlasImage.current
    val selectionHost = LocalThreadInPlaceTextSelectionHost.current
    val quoteIntoComposer = selectionHost?.quoteIntoComposer
    val insertQuoteIntoComposer = selectionHost?.insertQuoteIntoComposer
    val authorDisplayName = message.localizedAuthorName()
    val menuPendingQuote =
        if (insertQuoteIntoComposer != null || quoteIntoComposer != null) {
            threadMessageMenuPendingQuote(message, authorDisplayName)
        } else {
            null
        }
    val emojiPickerPreferences = rememberRaftEmojiPickerPreferences()
    val sheetState = remember(message.id) { ThreadMessageMultiDetentDrawerState(ThreadMessageSheetDetent.Compact) }
    val scope = rememberCoroutineScope()
    var mode by remember(message.id) { mutableStateOf(ThreadMessageSheetMode.Actions) }
    var confirmDeleteLocal by remember(message.id) { mutableStateOf(false) }

    val openSelectText: () -> Unit = {
        onSelectTextOpened()
        mode = ThreadMessageSheetMode.SelectText
        scope.launch { sheetState.animateTo(ThreadMessageSheetDetent.Expanded) }
    }
    val openEmojiPicker: () -> Unit = {
        onEmojiPickerOpened()
        mode = ThreadMessageSheetMode.EmojiPicker
        scope.launch { sheetState.animateTo(ThreadMessageSheetDetent.Full) }
    }
    val returnToActions: () -> Unit = {
        mode = ThreadMessageSheetMode.Actions
        scope.launch { sheetState.animateTo(ThreadMessageSheetDetent.Compact) }
    }

    val actions =
        remember(
            message,
            replyMetadata,
            showReplyAction,
            showSaveAction,
            showFollowAction,
            showTaskStatusAction,
            showConvertToTaskAction,
            createTaskFromMessage,
            showCopyLinkAction,
            showShareMessagesAction,
            showCancelAction,
            showDeleteAction,
            showTranslateAction,
            translatePending,
            showForwardAction,
            showMarkThreadUnreadAction,
            onReply,
            onCopyLink,
            onCopy,
            onShareMessages,
            onSave,
            onFollowThread,
            onUnfollowThread,
            onTaskStatusChange,
            onConvertToTask,
            onCancel,
            onTranslate,
            onForward,
            onMarkThreadUnread,
            onCopySelection,
            onDownloadVideo,
            link != null,
            menuPendingQuote,
            insertQuoteIntoComposer,
            quoteIntoComposer,
            onQuoteChosen,
            onDismiss,
            viewContextState,
            onViewContext,
            onRetryViewContext,
            followTarget,
        ) {
            threadMessageMenuActions(
                message = message,
                replyMetadata = replyMetadata,
                showReplyAction = showReplyAction,
                showSaveAction = showSaveAction,
                showFollowAction = showFollowAction,
                showTaskStatusAction = showTaskStatusAction,
                showConvertToTaskAction = showConvertToTaskAction,
                createTaskFromMessage = createTaskFromMessage,
                showCopyLinkAction = showCopyLinkAction,
                showShareMessagesAction = showShareMessagesAction,
                showCancelAction = showCancelAction,
                showDeleteAction = showDeleteAction,
                showTranslateAction = showTranslateAction,
                translatePending = translatePending,
                showForwardAction = showForwardAction,
                showMarkThreadUnreadAction = showMarkThreadUnreadAction,
                onCopySelection = onCopySelection,
                linkSection = link != null,
                onReply = onReply,
                onCopyLink = onCopyLink,
                onCopy = onCopy,
                // Morph in-place inside the same bottom sheet (zero double-sheet stacking).
                onSelectText = openSelectText,
                onShareMessages = onShareMessages,
                onSave = onSave,
                onFollowThread = onFollowThread,
                onUnfollowThread = onUnfollowThread,
                onTaskStatusChange = onTaskStatusChange,
                onConvertToTask = onConvertToTask,
                onCancel = onCancel,
                // Local failed send delete asks inline inside this sheet before removing.
                onDelete = { confirmDeleteLocal = true },
                onTranslate = onTranslate,
                onForward = onForward,
                onMarkThreadUnread = onMarkThreadUnread,
                onDownloadVideo = onDownloadVideo,
                showQuoteAction = menuPendingQuote != null,
                onQuote = {
                    if (menuPendingQuote != null) {
                        onQuoteChosen()
                        onDismiss()
                        val insert =
                            ThreadComposerQuoteInsert(
                                quote = menuPendingQuote,
                                fallbackMarkdown = formatThreadMessageQuote(menuPendingQuote.excerpt),
                            )
                        if (insertQuoteIntoComposer != null) {
                            insertQuoteIntoComposer(insert)
                        } else {
                            threadMessageMenuQuote(message)?.let { quoteIntoComposer?.invoke(it) }
                        }
                    }
                },
                viewContextState = viewContextState,
                onViewContext = onViewContext,
                onRetryViewContext = onRetryViewContext,
                followTarget = followTarget,
            )
        }

    val showAddReaction = showReactionActions && showAddReactionAction

    RaftDesignSystemTheme {
        ThreadMessageMultiDetentDrawer(
            state = sheetState,
            keepsHostKeyboard = mode == ThreadMessageSheetMode.Actions,
            onDismissRequest = onDismiss,
        ) { dragModifier, nestedScrollConnection ->
            when (mode) {
                ThreadMessageSheetMode.Actions -> {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .testTag(ThreadMessageActionSheetTag),
                    ) {
                        if (showReactionActions) {
                            Box(modifier = Modifier.fillMaxWidth().then(dragModifier).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                ThreadMessageReactionRow(
                                    reactionAtlasImage = reactionAtlasImage,
                                    onReact = onReact,
                                    rowHeight = 44.dp,
                                    buttonSize = 36.dp,
                                    glyphSize = 22.dp,
                                    horizontalPadding = 4.dp,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    onAddReaction = if (showAddReaction) openEmojiPicker else null,
                                )
                            }
                            RaftMenuSeparator(Modifier.padding(horizontal = 12.dp))
                        }
                        if (confirmDeleteLocal) {
                            ThreadMessageSheetInlineDeleteConfirm(
                                onCancel = { confirmDeleteLocal = false },
                                onConfirmDelete = onDelete,
                            )
                            RaftMenuSeparator(Modifier.padding(horizontal = 12.dp))
                        }
                        val scrollState = rememberScrollState()
                        Column(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .nestedScroll(nestedScrollConnection)
                                    .verticalScroll(scrollState)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            if (link != null) {
                                ThreadMessageLinkMenuRows(link)
                                if (actions.isNotEmpty()) {
                                    RaftMenuSeparator()
                                }
                            }
                            actions.forEachIndexed { index, action ->
                                val showDivider = index > 0 && actions[index - 1].group != action.group
                                if (showDivider) {
                                    RaftMenuSeparator()
                                }
                                RaftMenuItem(
                                    text = action.label.localized(),
                                    onClick = { if (action.enabled) action.onClick() },
                                    modifier =
                                        if (action.testTag != null) {
                                            Modifier.testTag(action.testTag)
                                        } else {
                                            Modifier
                                        },
                                    enabled = action.enabled,
                                    leadingIcon = { RaftSlotIcon(action.icon) },
                                    trailingIcon =
                                        if (action.trailingLoading) {
                                            { RaftSpinner(size = RaftSpinnerSize.Xs) }
                                        } else {
                                            null
                                        },
                                    description = action.description?.localized(),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }

                ThreadMessageSheetMode.SelectText -> {
                    val selection = remember(message.id) { RaftNativeTextSelection() }
                    val press = remember(message.id) { ThreadSelectTextPressSelection() }
                    val shownText = remember(message.body) { threadSelectTextSheetText(message.body) }
                    val liveSelectedText = selection.selectedTextState
                    val hasSelection = liveSelectedText.isNotBlank()
                    val colors = RaftTheme.colors
                    val typography = RaftTheme.typography
                    val scrollState = rememberScrollState()
                    val canQuote = insertQuoteIntoComposer != null || quoteIntoComposer != null

                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .testTag(ThreadMessageActionSheetSelectTextTag),
                    ) {
                        ThreadMessageSheetSubHeader(
                            title = stringResource(Res.string.thread_action_select_text),
                            onBack = returnToActions,
                            onClose = onDismiss,
                            dragModifier = dragModifier,
                        )
                        RaftText(
                            text =
                                if (hasSelection) {
                                    stringResource(Res.string.thread_select_text_selected_chars, liveSelectedText.length)
                                } else {
                                    stringResource(Res.string.thread_select_text_hint)
                                },
                            style = typography.footnote,
                            color = colors.foregroundMuted,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        Column(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .nestedScroll(nestedScrollConnection)
                                    .verticalScroll(scrollState)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            val formattedTime =
                                currentRaftDateTimeContext(null)?.let { ctx ->
                                    formatThreadCompactTimestampLabel(message.timestampLabel, ctx)
                                } ?: ""
                            val byline =
                                listOf(authorDisplayName, formattedTime)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                            if (byline.isNotEmpty()) {
                                RaftText(byline, style = typography.footnote, color = colors.foregroundMuted)
                            }
                            RaftNativeSelectableText(
                                text = shownText,
                                style = messageBodyTextStyle.copy(color = colors.foreground),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("thread_message_selection_text"),
                                selection = selection,
                            )
                        }
                        RaftMenuSeparator(Modifier.padding(horizontal = 12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RaftButton(
                                text =
                                    stringResource(
                                        if (hasSelection) {
                                            Res.string.thread_text_selection_copy_selection
                                        } else {
                                            Res.string.common_copy
                                        }
                                    ),
                                onClick = {
                                    val picked = press.take(selection::selectedText)
                                    onCopyCustomText(threadSelectTextSheetSource(picked, message.body))
                                },
                                modifier = Modifier.weight(1f).sheetSelectTextPressSnapshot(press, selection),
                                variant = if (hasSelection) RaftButtonVariant.Primary else RaftButtonVariant.Default,
                                size = RaftButtonSize.Lg,
                                leadingIcon = { RaftSlotIcon(RaftLucideIconName.Copy) },
                            )
                            if (canQuote) {
                                RaftButton(
                                    text =
                                        stringResource(
                                            if (hasSelection) {
                                                Res.string.thread_select_text_quote_selection
                                            } else {
                                                Res.string.thread_action_quote
                                            }
                                        ),
                                    onClick = {
                                        val picked = press.take(selection::selectedText)
                                        onQuoteChosen()
                                        onDismiss()
                                        if (insertQuoteIntoComposer != null) {
                                            insertQuoteIntoComposer(
                                                threadSelectionQuoteInsert(
                                                    selectedText = picked,
                                                    markdown = message.body,
                                                    message = message,
                                                    authorName = authorDisplayName,
                                                )
                                            )
                                        } else {
                                            val quoteMd =
                                                threadSelectTextSheetQuote(
                                                    selectedText = picked,
                                                    markdown = message.body,
                                                    quoteMessageId = message.id.takeIf { !message.hasLocalSendState() },
                                                )
                                            quoteIntoComposer?.invoke(quoteMd)
                                        }
                                    },
                                    modifier = Modifier.weight(1f).sheetSelectTextPressSnapshot(press, selection),
                                    variant = RaftButtonVariant.Default,
                                    size = RaftButtonSize.Lg,
                                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.TextQuote) },
                                )
                            }
                        }
                    }
                }

                ThreadMessageSheetMode.EmojiPicker -> {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .testTag(ThreadMessageActionSheetEmojiPickerTag),
                    ) {
                        ThreadMessageSheetSubHeader(
                            title = stringResource(Res.string.thread_action_add_reaction),
                            onBack = returnToActions,
                            onClose = onDismiss,
                            dragModifier = dragModifier,
                        )
                        RaftEmojiPickerPanel(
                            preferences = emojiPickerPreferences,
                            catalog = RaftEmojiCatalog.Default,
                            onSelect = onReact,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            gridHeight = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadMessageSheetSubHeader(
    title: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
    dragModifier: Modifier,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(dragModifier)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RaftBackButton(
            onClick = onBack,
            label = stringResource(Res.string.common_back),
        )
        RaftText(
            text = title,
            style = RaftTheme.typography.headline,
            color = RaftTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RaftCloseButton(
            onClick = onClose,
            label = stringResource(Res.string.common_close),
        )
    }
}

@Composable
private fun ThreadMessageSheetInlineDeleteConfirm(
    onCancel: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val colors = RaftTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(RaftRadius.Control))
                .background(colors.dangerSoft)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RaftText(
            text = stringResource(Res.string.thread_message_delete_confirm_prompt),
            style = RaftTheme.typography.callout,
            color = colors.dangerStrong,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md,
            )
            RaftButton(
                text = stringResource(Res.string.common_delete),
                onClick = onConfirmDelete,
                modifier = Modifier.weight(1f),
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Md,
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.Trash2) },
            )
        }
    }
}
