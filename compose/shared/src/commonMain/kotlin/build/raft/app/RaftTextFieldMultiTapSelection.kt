package build.raft.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Touch double-tap (word) / triple-tap (paragraph) selection for the value-based `BasicTextField`.
 *
 * Upstream status (checked against the Compose sources):
 * - androidx.compose.foundation 1.8.2 (what CMP 1.8.2 resolves to on Android): the
 *   `BasicTextField(value, onValueChange)` touch path (`selectionGestureInput` -> `touchSelection`)
 *   only handles long-press; double-tap word selection exists only in the `TextFieldState`
 *   overload, and there is no touch triple-tap at all.
 * - androidx 1.10.0 unified the gesture handler (`awaitSelectionGestures` + `ClicksCounter`) for
 *   both overloads: double-tap selects a Word and triple-tap a Paragraph ("Support double tap to
 *   select word in touch mode" 7a284caa2a, "Enable triple-tap support to select Paragraph"
 *   b6f77c00ec). That arrives with CMP 1.10; until the composer can move there, Android uses this
 *   backport.
 * - CMP 1.8.2 iOS already ships the same behaviour (`CupertinoTextFieldPointerModifier`,
 *   `detectRepeatingTapGestures`: double tap = Word, triple tap = Paragraph), so iOS does not
 *   install this modifier ([raftPlatformTextFieldNeedsMultiTapBackport]).
 *
 * The modifier is a non-consuming observer that runs after the text field's own pointer handling
 * (it sits outside it in the modifier chain, and the Main pass reaches it last). Single taps,
 * long-press selection, handles and IME focus therefore keep the upstream behaviour: on every tap
 * the field itself first places the cursor at the tapped offset, and only on the second/third tap
 * of a sequence this observer widens that cursor to the word/paragraph around it.
 */
internal fun Modifier.raftTextFieldMultiTapSelection(
    enabled: Boolean,
    currentValue: () -> TextFieldValue,
    currentTextLayout: () -> TextLayoutResult?,
    onSelect: (TextFieldValue) -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(Unit) {
        val tapCounter =
            RaftTextFieldTapCounter(
                doubleTapTimeoutMillis = viewConfiguration.doubleTapTimeoutMillis,
                slopPx = viewConfiguration.touchSlop,
            )
        awaitEachGesture {
            // The field consumes its own down (tap detector), so consumed downs must count here.
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type == PointerType.Mouse) {
                // Mouse multi-click selection is handled upstream (mouseSelection); every other
                // pointer takes the touch path, like upstream's `!isMouseOrTouchPad()`.
                tapCounter.reset()
                return@awaitEachGesture
            }
            val clicks = tapCounter.onDown(down.uptimeMillis, down.position)
            val up =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    awaitRaftTextFieldTapUp(down, viewConfiguration.touchSlop)
                }
            if (up == null) {
                // A long press or drag belongs to the field's own selection gestures and ends
                // the tap sequence, as in upstream.
                tapCounter.reset()
                return@awaitEachGesture
            }
            if (clicks < 2) return@awaitEachGesture
            val layout = currentTextLayout() ?: return@awaitEachGesture
            // The field's tap handler already ran for this up and moved the cursor to the tapped
            // offset (it maps the position through padding/scroll), so widen from that cursor.
            val value = currentValue()
            val range =
                raftTextFieldMultiTapSelectionRange(
                    text = value.text,
                    cursor = value.selection.end,
                    clicks = clicks,
                    wordBoundary = { offset -> layout.getWordBoundary(offset) },
                ) ?: return@awaitEachGesture
            if (range != value.selection) {
                onSelect(TextFieldValue(value.annotatedString, range))
            }
        }
    }
}

/** Returns the up of [down] when it completes as a tap, or null for drag/cancel/multi-touch. */
private suspend fun AwaitPointerEventScope.awaitRaftTextFieldTapUp(
    down: PointerInputChange,
    slopPx: Float,
): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        if (event.changes.count { it.pressed } > 1) return null
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (change.changedToUpIgnoreConsumed()) return change
        if (!change.pressed) return null
        if ((change.position - down.position).getDistance() > slopPx) return null
    }
}

/**
 * Counts consecutive taps the way upstream `ClicksCounter` does: a down continues the sequence
 * when it arrives within `doubleTapTimeout` of the previous down and within touch slop of it.
 */
internal class RaftTextFieldTapCounter(
    private val doubleTapTimeoutMillis: Long,
    private val slopPx: Float,
) {
    var clicks: Int = 0
        private set

    private var previousUptimeMillis: Long = 0L
    private var previousPosition: Offset? = null

    fun onDown(uptimeMillis: Long, position: Offset): Int {
        val previous = previousPosition
        clicks =
            if (
                previous != null &&
                    uptimeMillis - previousUptimeMillis < doubleTapTimeoutMillis &&
                    (position - previous).getDistance() < slopPx
            ) {
                clicks + 1
            } else {
                1
            }
        previousUptimeMillis = uptimeMillis
        previousPosition = position
        return clicks
    }

    fun reset() {
        clicks = 0
        previousPosition = null
    }
}

/**
 * Selection for the [clicks]-th tap at [cursor]: Word for a double tap, Paragraph (the text
 * between line feeds, upstream `SelectionAdjustment.Paragraph`) for a triple or later tap. Returns
 * null when nothing selectable is there (single tap, empty text, or a word boundary that collapses).
 */
internal fun raftTextFieldMultiTapSelectionRange(
    text: String,
    cursor: Int,
    clicks: Int,
    wordBoundary: (Int) -> TextRange,
): TextRange? {
    if (clicks < 2 || text.isEmpty()) return null
    val offset = cursor.coerceIn(0, text.length)
    val range =
        if (clicks == 2) {
            wordBoundary(offset)
        } else {
            raftTextFieldParagraphRange(text, offset)
        }
    return range.takeUnless { it.collapsed }
}

internal fun raftTextFieldParagraphRange(text: String, offset: Int): TextRange {
    val at = offset.coerceIn(0, text.length)
    val start = text.lastIndexOf('\n', startIndex = at - 1).let { if (it < 0) 0 else it + 1 }
    val end = text.indexOf('\n', startIndex = at).let { if (it < 0) text.length else it }
    return TextRange(start, end.coerceAtLeast(start))
}

/**
 * Field-level wiring of [raftTextFieldMultiTapSelection]: tracks the latest text layout and shows
 * the platform text toolbar (Copy / Cut / Paste / Select all) for a multi-tap selection.
 *
 * TODO(cmp-switch): delete this backport once the app is on CMP >= 1.10 (androidx foundation
 * 1.10.0), whose `BasicTextField(value)` does word/paragraph multi-tap natively. Known limitation
 * until then: foundation 1.8.2 only enters its selection-handle mode from its own gestures, so on
 * Android the multi-tap selection is highlighted with the toolbar, but the field keeps showing its
 * single cursor handle (at the selection start) instead of the two selection handles. Long-press
 * selection is untouched and still shows both handles.
 */
internal class RaftTextFieldMultiTapSelectionController(private val platformToolbar: TextToolbar) {
    var textLayout: TextLayoutResult? = null
    var fieldBoundsInRoot: Rect = Rect.Zero

    /** Selection our toolbar is showing for; null when the field (or nobody) owns the toolbar. */
    var toolbarSelection: TextRange? = null
        private set

    /**
     * The toolbar handed to the text field. Foundation 1.8.2's field calls `hide()` whenever it
     * recomposes outside its own selection mode, which would immediately close the menu shown for
     * a multi-tap selection; those hides are ignored while that menu is up. Every `showMenu` from
     * the field (long press, cursor handle tap) passes through and takes the toolbar back.
     */
    val fieldToolbar: TextToolbar =
        object : TextToolbar {
            override val status: TextToolbarStatus
                get() = platformToolbar.status

            override fun hide() {
                if (toolbarSelection == null) platformToolbar.hide()
            }

            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?,
            ) {
                toolbarSelection = null
                platformToolbar.showMenu(
                    rect,
                    onCopyRequested,
                    onPasteRequested,
                    onCutRequested,
                    onSelectAllRequested,
                )
            }

            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?,
                onAutofillRequested: (() -> Unit)?,
            ) {
                toolbarSelection = null
                platformToolbar.showMenu(
                    rect,
                    onCopyRequested,
                    onPasteRequested,
                    onCutRequested,
                    onSelectAllRequested,
                    onAutofillRequested,
                )
            }
        }

    fun showFor(
        selection: TextRange,
        onCopy: () -> Unit,
        onPaste: (() -> Unit)?,
        onCut: () -> Unit,
        onSelectAll: (() -> Unit)?,
    ) {
        toolbarSelection = selection
        platformToolbar.showMenu(fieldBoundsInRoot, onCopy, onPaste, onCut, onSelectAll)
    }

    fun retire() {
        if (toolbarSelection == null) return
        toolbarSelection = null
        if (platformToolbar.status == TextToolbarStatus.Shown) platformToolbar.hide()
    }
}

/**
 * Field-level wiring of [raftTextFieldMultiTapSelection]: tracks the latest text layout and shows
 * the platform text toolbar (Copy / Cut / Paste / Select all) for a multi-tap selection. The
 * caller must provide [RaftTextFieldMultiTapSelectionController.fieldToolbar] as the field's
 * `LocalTextToolbar`.
 *
 * TODO(cmp-switch): delete this backport once the app is on CMP >= 1.10 (androidx foundation
 * 1.10.0), whose `BasicTextField(value)` does word/paragraph multi-tap natively. Known limitation
 * until then: foundation 1.8.2 only enters its selection-handle mode from its own gestures, so on
 * Android the multi-tap selection is highlighted with the toolbar, but the field keeps showing its
 * single cursor handle (at the selection start) instead of the two selection handles. Long-press
 * selection is untouched and still shows both handles.
 */
@Composable
internal fun rememberRaftTextFieldMultiTapSelection(
    enabled: Boolean,
    composedValue: TextFieldValue,
    currentValue: () -> TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
): Pair<Modifier, RaftTextFieldMultiTapSelectionController> {
    val platformToolbar = LocalTextToolbar.current
    val controller = remember(platformToolbar) { RaftTextFieldMultiTapSelectionController(platformToolbar) }
    // Leaving composition (navigation without a blur) must not strand our floating toolbar.
    DisposableEffect(controller) { onDispose { controller.retire() } }
    @Suppress("DEPRECATION") // LocalClipboard (suspend) is not needed for plain text.
    val clipboard = LocalClipboardManager.current
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestCurrentValue by rememberUpdatedState(currentValue)
    // Any change away from the selection the toolbar was shown for (a tap moving the cursor, a
    // long press, typing, Send clearing the field) retires our toolbar.
    SideEffect {
        val shownFor = controller.toolbarSelection ?: return@SideEffect
        if (!enabled || composedValue.selection != shownFor) controller.retire()
    }
    if (!enabled) return Modifier to controller
    // pointerInput(Unit) keeps its first handler, so it reaches the current clipboard and
    // callbacks through updated state.
    val onSelect by rememberUpdatedState<(TextFieldValue) -> Unit> { selected ->
        latestOnValueChange(selected)
        showRaftMultiTapToolbar(
            clipboard = clipboard,
            controller = controller,
            selected = selected,
            currentValue = { latestCurrentValue() },
            onValueChange = { latestOnValueChange(it) },
        )
    }
    val modifier =
        Modifier
            .onGloballyPositioned { controller.fieldBoundsInRoot = it.boundsInRoot() }
            .onFocusChanged { if (!it.hasFocus) controller.retire() }
            .raftTextFieldMultiTapSelection(
                enabled = true,
                currentValue = { latestCurrentValue() },
                currentTextLayout = { controller.textLayout },
                onSelect = { onSelect(it) },
            )
    return modifier to controller
}

private fun showRaftMultiTapToolbar(
    clipboard: ClipboardManager,
    controller: RaftTextFieldMultiTapSelectionController,
    selected: TextFieldValue,
    currentValue: () -> TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
) {
    controller.showFor(
        selection = selected.selection,
        onCopy = {
            val value = currentValue()
            clipboard.setText(raftSelectedText(value))
            // Upstream copy() ends the selection at its end.
            onValueChange(value.copy(selection = TextRange(value.selection.max)))
            controller.retire()
        },
        onPaste =
            if (clipboard.hasText()) {
                {
                    val value = currentValue()
                    onValueChange(raftReplaceSelection(value, clipboard.getText()?.text.orEmpty()))
                    controller.retire()
                }
            } else {
                null
            },
        onCut = {
            val value = currentValue()
            clipboard.setText(raftSelectedText(value))
            onValueChange(raftReplaceSelection(value, ""))
            controller.retire()
        },
        onSelectAll =
            if (selected.selection.length < selected.text.length) {
                {
                    val value = currentValue()
                    val all = TextFieldValue(value.annotatedString, TextRange(0, value.text.length))
                    onValueChange(all)
                    showRaftMultiTapToolbar(clipboard, controller, all, currentValue, onValueChange)
                }
            } else {
                null
            },
    )
}

private fun raftSelectedText(value: TextFieldValue): AnnotatedString =
    value.annotatedString.subSequence(value.selection.min, value.selection.max)

internal fun raftReplaceSelection(value: TextFieldValue, replacement: String): TextFieldValue {
    val text = value.text
    val start = value.selection.min.coerceIn(0, text.length)
    val end = value.selection.max.coerceIn(start, text.length)
    val next = text.substring(0, start) + replacement + text.substring(end)
    return TextFieldValue(next, TextRange(start + replacement.length))
}
