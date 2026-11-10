package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.markdown.LocalRaftMarkdownLinkDragSelection
import build.raft.app.markdown.RaftMarkdownLinkDragSelection
import build.raft.app.markdown.RaftCleanCopyClipboardScope
import build.raft.app.markdown.raftCopyText
import build.raft.designsystem.components.RaftDismissOnPageCover
import build.raft.designsystem.components.RaftTextSelectionToolbar
import build.raft.designsystem.components.RaftTextSelectionToolbarButton
import build.raft.designsystem.components.RaftTextSelectionToolbarDefaults
import build.raft.designsystem.components.RaftTextSelectionToolbarSeparator
import kotlinx.coroutines.awaitCancellation

/**
 * Holds the page's selection menu behind one stable composable reference. The list republishes
 * the content on every recomposition (its captures change); only [menu]'s body reads the latest
 * content, so the write recomposes just an open selection menu, never the visible rows.
 */
internal class ThreadInPlaceSelectionMenuSlot {
    private var latest by mutableStateOf<(@Composable (ThreadInPlaceSelectionMenuRequest) -> Unit)?>(null)

    val menu: @Composable (ThreadInPlaceSelectionMenuRequest) -> Unit = { request ->
        // Explicit branch, not `latest?.invoke(...)`: a conditional composable call inside `?.`
        // is the shape behind the Kotlin 2.4.21 missing-group crash.
        val content = latest
        if (content != null) content(request)
    }

    fun publish(content: @Composable (ThreadInPlaceSelectionMenuRequest) -> Unit) {
        latest = content
    }
}

/** What the host needs from a message's selection; [ThreadInPlaceSelectionController] in production. */
internal interface ThreadInPlaceSelectionTarget {
    val messageId: String
    val hasSelection: Boolean

    fun clear()
}

/**
 * In-place text selection for chat-stream message bodies, behind the "New Message Menu" toggle.
 *
 * Gesture rule (toggle ON): a finger long-press anywhere on the row, the body included, opens the
 * Compact New Message Menu and starts no text selection ([fingerPressOpensMessageMenu], artin
 * dm:#99: on iOS the selection's own menu showed first and the message menu then opened over it).
 * Picking text with a finger goes through the menu's Select Text sheet. A mouse, trackpad or
 * stylus press on the body still belongs to Compose's [SelectionContainer] (press-drag,
 * double-click, long press), and the row-level long-press menu does not fire there. Toggle OFF:
 * no container, no deferral, the row behaves exactly as before.
 *
 * The platform text toolbar is replaced on both toolbar paths (Android's new text context menu,
 * [LocalTextContextMenuToolbarProvider], on by default there, and the legacy [LocalTextToolbar]
 * that skiko/iOS still uses) by the full Compact New Message Menu, WeChat style: reactions and
 * message tools, with Copy copying the selection and Select All added. Compose only asks for that
 * toolbar for a touch selection; a selection made with the mouse or trackpad (desktop, an iPad
 * with a pointer) gets the web's small selection toolbar instead, "Quote | Copy", shown when the
 * button is let go ([threadInPlaceSelectionToolbarKind], artin 2026-11-01).
 */
internal class ThreadInPlaceTextSelectionHost(
    val enabled: Boolean,
    val copyText: (String) -> Unit,
    /**
     * A finger press on a message body is the row's (long press: the message menu; taps), never a
     * text selection's. On with the New Message Menu toggle; the desktop's always-on pointer
     * selection keeps a touchscreen finger's long press selecting when the toggle is off.
     */
    val fingerPressOpensMessageMenu: Boolean = false,
) {
    private var active: ThreadInPlaceSelectionTarget? = null

    /**
     * The page's Compact New Message Menu for a selection; ThreadMessagesListContent, which owns
     * the message actions, publishes into it.
     */
    val selectionMenu = ThreadInPlaceSelectionMenuSlot()

    /**
     * The page composer's quote append (what Select Text > Quote does with its Markdown quote), or
     * null when the page shows no composer: the mouse selection toolbar then offers Copy only.
     */
    var quoteIntoComposer by mutableStateOf<((String) -> Unit)?>(null)

    /** The thread list's visible viewport in window space; the bar never leaves it. */
    var viewportInWindow by mutableStateOf<Rect?>(null)
        private set

    internal fun updateViewport(bounds: Rect) {
        if (bounds != viewportInWindow) viewportInWindow = bounds
    }

    /**
     * The text selected in a message when it was right-clicked, for its message menu's "Copy". Read
     * when the menu opens, not live: the menu takes focus, which can drop the visible selection.
     */
    var contextMenuSelectionCopy by mutableStateOf<ThreadContextMenuSelectionCopy?>(null)

    /** "Copy" text for the message menu of [messageId] opened at [anchor], when a right-click opened it over a selection. */
    internal fun contextMenuCopyFor(messageId: String, anchor: RaftPopoverAnchor): String? =
        contextMenuSelectionCopy?.takeIf { it.messageId == messageId && it.anchor === anchor }?.text

    internal fun activate(target: ThreadInPlaceSelectionTarget) {
        val previous = active
        if (previous != null && previous !== target) previous.clear()
        active = target
    }

    internal fun release(target: ThreadInPlaceSelectionTarget) {
        if (active === target) active = null
    }

    /** Whether text is selected in a message now (its handles own horizontal drags). */
    internal val selectionShowing: Boolean
        get() = active?.hasSelection == true

    /**
     * A pointer went down on a message row. Returns true when a selection was showing anywhere:
     * that gesture is a dismissal and its tap must not also run the row action (open thread).
     * A press inside the selected body is left to Compose (handles, adjusting the selection).
     */
    internal fun onRowPointerDown(messageId: String, insideBody: Boolean): Boolean {
        val current = active ?: return false
        if (!current.hasSelection) return false
        if (current.messageId == messageId && insideBody) return true
        current.clear()
        return true
    }
}

/**
 * What a right-click on a message had selected in it: the menu it opened at [anchor] (identity:
 * a long press or the reaction chip opens with its own anchor, so it never picks this up) offers
 * "Copy" for [text].
 */
internal class ThreadContextMenuSelectionCopy(
    val messageId: String,
    val anchor: RaftPopoverAnchor,
    val text: String,
)

/**
 * The text a right-click menu's "Copy" copies: the message's current selection, as the selection
 * menu copies it ([threadInPlaceSelectionCopyText]), or null (no "Copy" item) when nothing in the
 * message is selected. Unlike the selection menu there is no whole-message fallback: the message
 * menu already has Copy Markdown for that.
 */
internal fun threadContextMenuSelectionCopyText(
    selectedTexts: List<String>,
    selectableTexts: List<String>,
    fullMessageText: String?,
): String? =
    threadInPlaceSelectionCopyText(
        selectedTexts = selectedTexts,
        selectableTexts = selectableTexts,
        fullMessageText = fullMessageText,
    ).takeIf { it.isNotEmpty() }

internal enum class ThreadInPlaceSelectionTapDecision {
    RunRowAction,
    DismissSelectionOnly
}

/** The row tap of a gesture that started while a selection was showing only dismisses it. */
internal fun threadInPlaceSelectionTapDecision(selectionShowingAtDown: Boolean): ThreadInPlaceSelectionTapDecision =
    if (selectionShowingAtDown) {
        ThreadInPlaceSelectionTapDecision.DismissSelectionOnly
    } else {
        ThreadInPlaceSelectionTapDecision.RunRowAction
    }

/**
 * Whether a selection that just appeared widens to the whole message (the long-press default).
 * Only one a finger started: a mouse or trackpad selection (desktop, an iPad with a pointer) is a
 * press-and-drag or a double-clicked word and stays exactly what was picked. Compose's mouse drag
 * extends the end of the current selection, so widening it mid-drag (it appears on the first move)
 * pinned the start to the message's first character: "selection always starts at the beginning".
 */
internal fun threadInPlaceSelectionWidensToWholeMessage(
    startedByTouch: Boolean,
    coversAll: Boolean,
): Boolean = startedByTouch && !coversAll

/** Which toolbar a message's in-place selection shows this frame. */
internal enum class ThreadInPlaceSelectionToolbarKind {
    None,

    /** The full Compact New Message Menu: a finger selection (Compose asked for its toolbar). */
    MessageMenu,

    /** The web's small "Quote | Copy" toolbar: a mouse or trackpad selection, once the button is up. */
    SelectionToolbar
}

/**
 * [mouseSelectionEnded]: the last press on the body was the primary mouse button and it has been
 * let go. [platformToolbarRequested]: Compose asked for its text toolbar, which it only does for a
 * touch selection. A mouse release wins: a touch press afterwards resets it.
 */
internal fun threadInPlaceSelectionToolbarKind(
    pageActive: Boolean,
    hasSelection: Boolean,
    mouseSelectionEnded: Boolean,
    platformToolbarRequested: Boolean,
): ThreadInPlaceSelectionToolbarKind =
    when {
        !pageActive || !hasSelection -> ThreadInPlaceSelectionToolbarKind.None
        mouseSelectionEnded -> ThreadInPlaceSelectionToolbarKind.SelectionToolbar
        platformToolbarRequested -> ThreadInPlaceSelectionToolbarKind.MessageMenu
        else -> ThreadInPlaceSelectionToolbarKind.None
    }

/** A press that can end a mouse selection: every pointer a mouse (trackpads report as one), primary button. */
internal fun threadInPlaceSelectionPressIsPrimaryMouse(
    pointerTypes: List<PointerType>,
    primaryPressed: Boolean,
): Boolean = primaryPressed && pointerTypes.isNotEmpty() && pointerTypes.all { it == PointerType.Mouse }

/**
 * Body-local anchor for the mouse selection toolbar: the line where the button was let go (the
 * selection's focus end, as on the web), [lineHeightPx] tall around [release], kept inside the
 * body (a drag may end past it).
 */
internal fun threadInPlaceSelectionMouseAnchorInBody(
    release: Offset,
    bodySize: IntSize,
    lineHeightPx: Float,
): Rect {
    val width = bodySize.width.toFloat()
    val height = bodySize.height.toFloat()
    val x = release.x.coerceIn(0f, (width - 1f).coerceAtLeast(0f))
    val y = release.y.coerceIn(0f, height)
    val half = lineHeightPx / 2f
    return Rect(
        left = x,
        top = (y - half).coerceAtLeast(0f),
        right = (x + 1f).coerceAtMost(width),
        bottom = (y + half).coerceAtMost(height),
    )
}

/**
 * Whether a press on a message body, by [pointerType], is left to the row instead of the body's
 * text selection: a finger (or a down without a tool type, [raftIsTouchLike]) when
 * [fingerPressOpensMessageMenu]. A mouse, trackpad or stylus keeps selecting text in place.
 */
internal fun threadInPlaceSelectionYieldsPressToRow(
    fingerPressOpensMessageMenu: Boolean,
    pointerType: PointerType,
): Boolean = fingerPressOpensMessageMenu && pointerType.raftIsTouchLike()

/**
 * Whether the body's text selection owns a row press at a point: on the body, and not a press the
 * selection yields to the row ([threadInPlaceSelectionYieldsPressToRow]).
 */
internal fun threadInPlaceSelectionOwnsRowPress(
    pressOnBody: Boolean,
    pressYieldedToRow: Boolean,
): Boolean = pressOnBody && !pressYieldedToRow

/**
 * The long-press timeout the body's text selection sees: never reached for a press it yields to
 * the row, so a finger long press starts no selection (no highlight, handles, haptic or toolbar)
 * while the row's own detector opens the message menu.
 */
internal fun threadInPlaceSelectionLongPressTimeoutMillis(
    platformTimeoutMillis: Long,
    pressYieldedToRow: Boolean,
): Long = if (pressYieldedToRow) ThreadInPlaceSelectionYieldedLongPressTimeoutMillis else platformTimeoutMillis

/** Longer than any held press: the selection's long-press timeout never fires. */
internal const val ThreadInPlaceSelectionYieldedLongPressTimeoutMillis: Long = 24L * 60L * 60L * 1000L

/**
 * What a selection that just appeared does: a finger press the body yielded to the row still
 * started one (iOS selects a word on a double tap without a long press) → [Clear]; a finger's
 * own selection otherwise widens to the whole message → [WidenToWholeMessage]; else [Keep].
 */
internal enum class ThreadInPlaceSelectionAppearedAction {
    Keep,
    WidenToWholeMessage,
    Clear,
}

internal fun threadInPlaceSelectionAppearedAction(
    pressYieldedToRow: Boolean,
    startedByTouch: Boolean,
    coversAll: Boolean,
): ThreadInPlaceSelectionAppearedAction =
    when {
        pressYieldedToRow -> ThreadInPlaceSelectionAppearedAction.Clear
        threadInPlaceSelectionWidensToWholeMessage(startedByTouch, coversAll) ->
            ThreadInPlaceSelectionAppearedAction.WidenToWholeMessage
        else -> ThreadInPlaceSelectionAppearedAction.Keep
    }

internal enum class ThreadMessageLongPressRoute {
    MessageMenu,
    InPlaceSelection
}

/** Toggle off (or a press off the body) keeps today's long-press → message menu. */
internal fun threadMessageLongPressRoute(
    inPlaceSelectionEnabled: Boolean,
    pressOnSelectableBody: Boolean,
): ThreadMessageLongPressRoute =
    if (inPlaceSelectionEnabled && pressOnSelectableBody) {
        ThreadMessageLongPressRoute.InPlaceSelection
    } else {
        ThreadMessageLongPressRoute.MessageMenu
    }

internal enum class ThreadInPlaceSelectionBackOwner {
    None,
    MessageMenu,
    Selection
}

/** Back priority: an open message menu/popover first, then the in-place selection, then the page. */
internal fun threadInPlaceSelectionBackOwner(
    hasSelection: Boolean,
    messageMenuOpen: Boolean,
): ThreadInPlaceSelectionBackOwner =
    when {
        messageMenuOpen -> ThreadInPlaceSelectionBackOwner.MessageMenu
        hasSelection -> ThreadInPlaceSelectionBackOwner.Selection
        else -> ThreadInPlaceSelectionBackOwner.None
    }

/**
 * Where the bar may anchor this frame, all in window space: [visibleSelection] is the part of the
 * selection actually on screen (selection ∩ clipped body ∩ list viewport) and [area] is the list's
 * visible viewport the bar must stay inside (never over the composer or the top bar).
 */
internal data class ThreadInPlaceSelectionBarAnchor(
    val visibleSelection: Rect,
    val area: Rect?,
)

/** Null (hide the bar) only when no part of the selection is visible in the list viewport. */
internal fun threadInPlaceSelectionVisibleAnchor(
    selectionInWindow: Rect?,
    visibleBodyInWindow: Rect?,
    viewportInWindow: Rect?,
): ThreadInPlaceSelectionBarAnchor? {
    if (selectionInWindow == null || visibleBodyInWindow == null) return null
    var visible = selectionInWindow.intersectOrNull(visibleBodyInWindow) ?: return null
    if (viewportInWindow != null) {
        visible = visible.intersectOrNull(viewportInWindow) ?: return null
    }
    return ThreadInPlaceSelectionBarAnchor(visibleSelection = visible, area = viewportInWindow)
}

private fun Rect.intersectOrNull(other: Rect): Rect? {
    val clipped = intersect(other)
    return clipped.takeIf { it.width > 0f && it.height > 0f }
}

internal val LocalThreadInPlaceTextSelectionHost =
    staticCompositionLocalOf<ThreadInPlaceTextSelectionHost?> { null }

/**
 * The text the Select Text sheet offers for a message (`ThreadMessageSelectTextSheet` binds
 * `message.body`) and the menu's Copy action copies. A whole-message in-place copy must match it.
 */
internal fun threadMessageSelectionSourceText(message: RaftThreadMessageItem): String = message.body

internal fun threadInPlaceSelectionCoversAll(
    selectedTexts: List<String>,
    selectableTexts: List<String>,
): Boolean {
    val selectable = selectableTexts.filter { it.isNotEmpty() }
    val selected = selectedTexts.filter { it.isNotEmpty() }
    return selectable.isNotEmpty() && selected == selectable
}

/**
 * Copy payload for an in-place selection. Compose returns one entry per selectable text node
 * (paragraph, list item, code block…), so partial selections are joined with "\n" to keep the
 * block boundaries; a selection covering every node copies [fullMessageText], i.e. exactly what
 * the Select Text page and the menu's Copy action produce.
 */
internal fun threadInPlaceSelectionCopyText(
    selectedTexts: List<String>,
    selectableTexts: List<String>,
    fullMessageText: String?,
    emptySelectionFallback: String? = null,
): String {
    val selected = selectedTexts.filter { it.isNotEmpty() }
    if (selected.isEmpty()) return emptySelectionFallback.orEmpty()
    if (!fullMessageText.isNullOrBlank() && threadInPlaceSelectionCoversAll(selectedTexts, selectableTexts)) {
        return fullMessageText
    }
    // Rendered text carries layout-only characters (inline code wrap marks, chip gaps); a copy has none.
    return selected.joinToString(separator = "\n").raftCopyText()
}

/**
 * What the page-level Compact menu needs from a selection: Copy copies the selection, Select All
 * extends it, and every other message action calls [dismiss] (clear selection + menu) first.
 */
internal class ThreadInPlaceSelectionMenuRequest(
    val messageId: String,
    val copySelection: () -> Unit,
    val selectAll: () -> Unit,
    val dismiss: () -> Unit,
    /** Read inside the menu so it switches between full and partial as the handles move. */
    val coversAll: () -> Boolean = { true },
    /** Quotes the selection into the composer (the mouse selection toolbar's Quote); null: no composer. */
    val quoteSelection: (() -> Unit)? = null,
)

/**
 * The Compact menu in selection context (artin, 2026-10-21):
 * - the whole message selected (the long-press default): every message action, reactions
 *   included, and Copy copies the message;
 * - part of the message selected: only Copy (the selected text), Reply and Select All, since
 *   reactions and the other tools act on the whole message, not on the picked text. Reply only
 *   opens the thread (mobile DRI, 2026-10-27); quoting goes through Select Text > Quote.
 * The Select tool and the More chevron are hidden in both.
 */
internal fun threadInPlaceSelectionMenuAvailability(
    messageMenu: ThreadNewMessageMenuActionAvailability,
    coversAll: Boolean = true,
): ThreadNewMessageMenuActionAvailability =
    if (coversAll) {
        messageMenu.copy(copy = true, more = false, selectionContext = true, partialSelection = false)
    } else {
        ThreadNewMessageMenuActionAvailability(
            reactions = false,
            copy = true,
            reply = messageMenu.reply,
            forward = false,
            save = false,
            translate = false,
            select = false,
            more = false,
            selectionContext = true,
            partialSelection = true,
        )
    }

@Stable
internal class ThreadInPlaceSelectionController(
    override val messageId: String,
    private val host: ThreadInPlaceTextSelectionHost,
) : ThreadInPlaceSelectionTarget {
    val selectionState = SelectionState()

    // The toolbar request, kept relative to the body so the bar follows scroll, new messages and
    // keyboard insets: the legacy path's rect in body-local space, or the live context-menu data.
    private var menuRectInBody by mutableStateOf<Rect?>(null)
    private var menuDataProvider by mutableStateOf<TextContextMenuDataProvider?>(null)

    // Bumped from onGloballyPositioned only while a menu is requested, so idle rows never
    // recompose on scroll.
    private var bodyLayoutTick by mutableStateOf(0)
    internal var bodyCoordinates: LayoutCoordinates? = null
        private set
    internal var rowCoordinates: LayoutCoordinates? = null

    /** Set at pointer DOWN: this gesture started while a selection was showing. */
    internal var dismissalGestureInFlight = false

    /**
     * Set at every pointer DOWN on the row: whether it came from a finger. A selection a finger
     * starts (long press) widens to the whole message; one a mouse starts (press and drag, double
     * click) is the user's own range and is kept ([threadInPlaceSelectionWidensToWholeMessage]).
     * True until the first down so a selection made without one keeps the default.
     */
    internal var lastPointerDownFromTouch = true

    val menuRequested: Boolean
        get() = menuRectInBody != null || menuDataProvider != null

    // Where the primary mouse button was let go after a press on the body (body-local), or null:
    // the mouse selection toolbar's anchor. Any press on the body resets it.
    private var mouseReleaseInBody by mutableStateOf<Offset?>(null)

    val mouseSelectionEnded: Boolean
        get() = mouseReleaseInBody != null

    /**
     * Set at every press on the body, in the Initial pass (before the text's own selection
     * gestures and the row's detectors read it): this press is the row's, not the selection's
     * ([threadInPlaceSelectionYieldsPressToRow]).
     */
    internal var bodyPressYieldedToRow = false
        private set

    internal fun onBodyPointerPress(pointerType: PointerType) {
        mouseReleaseInBody = null
        bodyPressYieldedToRow =
            threadInPlaceSelectionYieldsPressToRow(host.fingerPressOpensMessageMenu, pointerType)
    }

    internal fun onBodyMouseRelease(position: Offset) {
        mouseReleaseInBody = position
    }

    override val hasSelection: Boolean
        get() = selectionState.selectedTexts.any { it.isNotEmpty() }

    internal fun onBodyPositioned(coordinates: LayoutCoordinates) {
        bodyCoordinates = coordinates
        if (menuRequested || mouseSelectionEnded) bodyLayoutTick++
    }

    /** Window-space bar anchor for the current frame (snapshot reads keep it live), or null to hide. */
    fun currentMenuAnchorInWindow(): ThreadInPlaceSelectionBarAnchor? {
        @Suppress("UNUSED_VARIABLE")
        val tick = bodyLayoutTick
        val body = bodyCoordinates?.takeIf { it.isAttached } ?: return null
        val local =
            menuDataProvider?.contentBounds(body)
                ?: menuRectInBody
                ?: return null
        val selection = Rect(body.localToWindow(local.topLeft), local.size)
        return threadInPlaceSelectionVisibleAnchor(selection, body.boundsInWindow(), host.viewportInWindow)
    }

    /** Window-space anchor for the mouse selection toolbar (the release line), or null to hide. */
    fun currentMouseToolbarAnchorInWindow(lineHeightPx: Float): ThreadInPlaceSelectionBarAnchor? {
        @Suppress("UNUSED_VARIABLE")
        val tick = bodyLayoutTick
        val body = bodyCoordinates?.takeIf { it.isAttached } ?: return null
        val release = mouseReleaseInBody ?: return null
        val local = threadInPlaceSelectionMouseAnchorInBody(release, body.size, lineHeightPx)
        val selection = Rect(body.localToWindow(local.topLeft), local.size)
        return threadInPlaceSelectionVisibleAnchor(selection, body.boundsInWindow(), host.viewportInWindow)
    }

    fun selectedTexts(): List<String> = selectionState.selectedTexts.map { it.text }

    fun selectableTexts(): List<String> = selectionState.getSelectableTexts().map { it.text }

    override fun clear() {
        selectionState.clear()
        hideMenu()
        mouseReleaseInBody = null
    }

    fun selectAll() {
        host.activate(this)
        selectionState.selectAll()
    }

    /**
     * The row's detectors leave a press at row-local [offset] to the selection: it is on the body
     * and not a finger press the body yields to the row (that one opens the message menu).
     */
    fun ownsRowPressAt(offset: Offset): Boolean =
        threadInPlaceSelectionOwnsRowPress(
            pressOnBody = bodyContainsRowOffset(offset),
            pressYieldedToRow = bodyPressYieldedToRow,
        )

    /** Row-local [offset] (the row long-press detector's space) lies on the selectable body. */
    fun bodyContainsRowOffset(offset: Offset): Boolean {
        val row = rowCoordinates?.takeIf { it.isAttached } ?: return false
        val body = bodyCoordinates?.takeIf { it.isAttached } ?: return false
        return row.localBoundingBoxOf(body, clipBounds = false).contains(offset)
    }

    /** Legacy TextToolbar path: [rootRect] is in root coordinates; store it body-relative. */
    internal fun showMenuAtRootRect(rootRect: Rect): Boolean {
        if (bodyPressYieldedToRow) return false
        val body = bodyCoordinates?.takeIf { it.isAttached } ?: return false
        val topLeft = body.localPositionOf(body.findRootCoordinates(), rootRect.topLeft)
        menuDataProvider = null
        menuRectInBody = Rect(topLeft, rootRect.size)
        host.activate(this)
        return true
    }

    /** New context-menu path (Android default): keep the live provider and re-query each frame. */
    internal fun showMenuFromProvider(dataProvider: TextContextMenuDataProvider): Boolean {
        // A finger press the body yielded to the row opens the message menu, never a second one.
        if (bodyPressYieldedToRow || bodyCoordinates?.isAttached != true) return false
        menuRectInBody = null
        menuDataProvider = dataProvider
        host.activate(this)
        return true
    }

    internal fun hideMenu() {
        menuRectInBody = null
        menuDataProvider = null
    }

    internal fun dispose() {
        host.release(this)
    }
}

private class ThreadInPlaceTextToolbar(
    private val controller: ThreadInPlaceSelectionController,
) : TextToolbar {
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        status = if (controller.showMenuAtRootRect(rect)) TextToolbarStatus.Shown else TextToolbarStatus.Hidden
    }

    override fun hide() {
        controller.hideMenu()
        status = TextToolbarStatus.Hidden
    }
}

/**
 * The platform's view configuration, except the long-press timeout the body's text selection
 * sees for a press it yields to the row ([threadInPlaceSelectionLongPressTimeoutMillis]). Read per
 * gesture: the body's Initial-pass press watcher records the press type before the text's
 * selection gesture asks for the timeout.
 */
private class ThreadInPlaceSelectionViewConfiguration(
    private val platform: ViewConfiguration,
    private val controller: ThreadInPlaceSelectionController,
) : ViewConfiguration by platform {
    override val longPressTimeoutMillis: Long
        get() =
            threadInPlaceSelectionLongPressTimeoutMillis(
                platformTimeoutMillis = platform.longPressTimeoutMillis,
                pressYieldedToRow = controller.bodyPressYieldedToRow,
            )
}

private class ThreadInPlaceTextContextMenuProvider(
    private val controller: ThreadInPlaceSelectionController,
) : TextContextMenuProvider {
    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        if (!controller.showMenuFromProvider(dataProvider)) return
        try {
            awaitCancellation()
        } finally {
            controller.hideMenu()
        }
    }
}

@Composable
internal fun rememberThreadInPlaceSelectionController(
    messageId: String,
    enabled: Boolean,
): ThreadInPlaceSelectionController? {
    val host = LocalThreadInPlaceTextSelectionHost.current
    if (host == null || !host.enabled || !enabled) return null
    val controller = remember(host, messageId) { ThreadInPlaceSelectionController(messageId, host) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    return controller
}

/** Wraps a message body so it can be selected in place; renders [content] untouched when [controller] is null. */
@Composable
internal fun ThreadInPlaceSelectableBody(
    controller: ThreadInPlaceSelectionController?,
    fullMessageText: String?,
    wholeMessageText: String,
    messageMenuOpen: Boolean,
    diagnosticsLogger: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    if (controller == null) {
        content()
        return
    }
    val host = LocalThreadInPlaceTextSelectionHost.current ?: return content()
    val textToolbar = remember(controller) { ThreadInPlaceTextToolbar(controller) }
    val contextMenuProvider = remember(controller) { ThreadInPlaceTextContextMenuProvider(controller) }
    // A mouse drag that starts on a link selects from the press point (RaftMarkdownLinkDragSelect.kt);
    // it is the mouse's own range, never widened to the whole message.
    val linkDragSelection =
        remember(controller) {
            RaftMarkdownLinkDragSelection(controller.selectionState) { controller.lastPointerDownFromTouch = false }
        }
    // raft-ui MessageItem text `selection:bg-selection` (0.23 #730): brand-soft in light, brand at
    // 22% in dark; the handles take the caret's ink, as in the designsystem RaftTheme.
    val themeColors = RaftAppThemeState.colors
    val selectionColors =
        remember(themeColors) {
            TextSelectionColors(handleColor = themeColors.foreground, backgroundColor = themeColors.selection)
        }
    val platformViewConfiguration = LocalViewConfiguration.current
    val selectionViewConfiguration =
        remember(platformViewConfiguration, controller) {
            ThreadInPlaceSelectionViewConfiguration(platformViewConfiguration, controller)
        }
    val pageActive = LocalRaftPageActive.current
    // Selectable only on the active page. Navigation 3 moves a page between scenes as movable content
    // while it is covered, revealed or in motion (never active then), and a selectable text whose row
    // recomposes in that frame throws: the list's own composition updates the detached node, and
    // foundation's SelectableTextAnnotatedStringNode.update then reads a CompositionLocal on re-attach,
    // before the node counts as attached (Compose 1.12.1, 1.13.0-rc01). Plain text reads none. A selection
    // keeps its selection registrar until the effect below has cleared it, since clearing goes through it.
    //
    // The switch never moves the body: the container stays in the tree and an inactive page gives the
    // body the locals from outside it (no selection registrar, so its text is plain). Moving the body
    // between the container and its parent (movable content) in the frame Navigation 3 moves the page
    // left the body's layout node in the list's measure pass without an owner: "LayoutNode should be
    // attached to an owner" (Hands c561c66a, b40c0cf8: a selection dropped as a reply thread opened, and
    // a channel page settling after its push).
    val selectable = pageActive || controller.hasSelection
    CompositionLocalProvider(
        LocalTextToolbar provides textToolbar,
        LocalTextContextMenuToolbarProvider provides contextMenuProvider,
        LocalTextSelectionColors provides selectionColors,
        LocalRaftMarkdownLinkDragSelection provides linkDragSelection,
    ) {
        // Ctrl/Cmd+C and the system text menu copy through the clipboard, not copySelection: clean them too.
        RaftCleanCopyClipboardScope {
            val plainBodyLocals = currentCompositionLocalContext
            // The text nodes' selection gestures read the long-press timeout from here; it only
            // differs for a finger press the body yields to the row (nothing in a body but the
            // selection waits on a long press: the row's detector lives outside).
            CompositionLocalProvider(LocalViewConfiguration provides selectionViewConfiguration) {
                SelectionContainer(
                    controller.selectionState,
                    Modifier
                        .onGloballyPositioned(controller::onBodyPositioned)
                        .threadInPlaceMouseSelectionEnd(controller),
                ) {
                    val selectableBodyLocals = currentCompositionLocalContext
                    // One call site in both states, so the body keeps its nodes and state.
                    CompositionLocalProvider(
                        context = if (selectable) selectableBodyLocals else plainBodyLocals,
                        content = content,
                    )
                }
            }
        }
    }
    // A page pushed over this one drops the selection first, so its popup bar goes while this
    // page still composes (once covered it may not). Only while there is one: every message has a
    // controller.
    if (controller.hasSelection) RaftDismissOnPageCover { controller.clear() }
    // A kept-alive page that goes inactive (hidden shell tab) drops its selection so the popup
    // bar cannot linger over the visible page.
    LaunchedEffect(pageActive, controller) {
        if (!pageActive && controller.hasSelection) {
            diagnosticsLogger("thread_in_place_selection_page_inactive_clear messageId=${controller.messageId}")
            controller.clear()
        }
    }
    // Long-press starts on a word; the default is the whole message, so widen it once when a
    // selection appears. Later handle drags are left alone (that is how a partial pick is made).
    // Only for a finger: a mouse selection appears on the first drag move, and widening it there
    // moved its anchor to the message start for the rest of the drag (artin, 2026-10-29).
    val hasSelection = controller.hasSelection
    // A finger press the body yielded to the row (the message menu's) that still started one is
    // dropped: no selection, and no selection menu, under the message menu.
    LaunchedEffect(controller, hasSelection) {
        if (!hasSelection) return@LaunchedEffect
        when (
            threadInPlaceSelectionAppearedAction(
                pressYieldedToRow = controller.bodyPressYieldedToRow,
                startedByTouch = controller.lastPointerDownFromTouch,
                coversAll = threadInPlaceSelectionCoversAll(controller.selectedTexts(), controller.selectableTexts())
            )
        ) {
            ThreadInPlaceSelectionAppearedAction.Keep -> Unit
            ThreadInPlaceSelectionAppearedAction.WidenToWholeMessage -> {
                diagnosticsLogger("thread_in_place_selection_default_select_all messageId=${controller.messageId}")
                controller.selectAll()
            }
            ThreadInPlaceSelectionAppearedAction.Clear -> {
                diagnosticsLogger("thread_in_place_selection_finger_press_cleared messageId=${controller.messageId}")
                controller.clear()
            }
        }
    }
    val backOwner =
        threadInPlaceSelectionBackOwner(
            hasSelection = controller.hasSelection,
            messageMenuOpen = messageMenuOpen
        )
    RaftBackHandler(
        enabled = backOwner == ThreadInPlaceSelectionBackOwner.Selection,
        label = "thread_in_place_selection"
    ) {
        diagnosticsLogger("thread_in_place_selection_back_clear messageId=${controller.messageId}")
        controller.clear()
    }
    val toolbarKind =
        threadInPlaceSelectionToolbarKind(
            pageActive = pageActive,
            hasSelection = controller.hasSelection,
            mouseSelectionEnded = controller.mouseSelectionEnded,
            platformToolbarRequested = controller.menuRequested,
        )
    if (toolbarKind != ThreadInPlaceSelectionToolbarKind.None) {
        val quoteIntoComposer = host.quoteIntoComposer
        val request =
            remember(controller, fullMessageText, wholeMessageText, host, quoteIntoComposer) {
                // The text Copy copies, and Quote quotes: the selection, or the whole message.
                val selectionText = {
                    threadInPlaceSelectionCopyText(
                        selectedTexts = controller.selectedTexts(),
                        selectableTexts = controller.selectableTexts(),
                        fullMessageText = fullMessageText,
                        emptySelectionFallback = wholeMessageText
                    )
                }
                ThreadInPlaceSelectionMenuRequest(
                    messageId = controller.messageId,
                    copySelection = {
                        val selected = controller.selectedTexts()
                        val selectable = controller.selectableTexts()
                        val text = selectionText()
                        diagnosticsLogger(
                            "thread_in_place_selection_copy messageId=${controller.messageId} " +
                                "chars=${text.length} lines=${text.lines().size} " +
                                "full=${threadInPlaceSelectionCoversAll(selected, selectable)} " +
                                "nodes=${selected.count { it.isNotEmpty() }}"
                        )
                        if (text.isNotEmpty()) host.copyText(text)
                        controller.clear()
                    },
                    selectAll = {
                        diagnosticsLogger("thread_in_place_selection_select_all messageId=${controller.messageId}")
                        controller.selectAll()
                    },
                    dismiss = {
                        diagnosticsLogger("thread_in_place_selection_menu_action messageId=${controller.messageId}")
                        controller.clear()
                    },
                    coversAll = {
                        threadInPlaceSelectionCoversAll(controller.selectedTexts(), controller.selectableTexts())
                    },
                    quoteSelection =
                        quoteIntoComposer?.let { quote ->
                            {
                                // Select Text > Quote's formatter and composer append.
                                val text = formatThreadMessageQuote(selectionText())
                                diagnosticsLogger(
                                    "thread_in_place_selection_quote messageId=${controller.messageId} chars=${text.length}"
                                )
                                if (text.isNotEmpty()) quote(text)
                                controller.clear()
                            }
                        }
                )
            }
        if (toolbarKind == ThreadInPlaceSelectionToolbarKind.SelectionToolbar) {
            val lineHeightPx = with(LocalDensity.current) { ThreadInPlaceSelectionMouseAnchorLineHeight.toPx() }
            ThreadInPlaceSelectionMenuHost(
                anchorFor = { controller.currentMouseToolbarAnchorInWindow(lineHeightPx) },
                gap = RaftTextSelectionToolbarDefaults.AnchorGap,
            ) {
                ThreadInPlaceSelectionToolbar(onQuote = request.quoteSelection, onCopy = request.copySelection)
            }
        } else {
            ThreadInPlaceSelectionMenuHost(anchorFor = controller::currentMenuAnchorInWindow) {
                host.selectionMenu.menu(request)
            }
        }
    }
}

/** The line the mouse selection toolbar sits above: about one message body line (15/22). */
private val ThreadInPlaceSelectionMouseAnchorLineHeight = 22.dp

internal const val ThreadInPlaceSelectionToolbarTag = "thread_in_place_selection_toolbar"

/**
 * The web's text selection toolbar for a mouse selection: "Quote | Copy" (Quote only when the
 * page has a composer). Both act on the selection and then clear it.
 */
@Composable
internal fun ThreadInPlaceSelectionToolbar(
    onQuote: (() -> Unit)?,
    onCopy: () -> Unit,
) {
    RaftDesignSystemTheme {
        RaftTextSelectionToolbar(modifier = Modifier.testTag(ThreadInPlaceSelectionToolbarTag)) {
            if (onQuote != null) {
                RaftTextSelectionToolbarButton(
                    label = stringResource(Res.string.thread_action_quote),
                    onClick = onQuote,
                    icon = { RaftSlotIcon(RaftLucideIconName.TextQuote) },
                )
                RaftTextSelectionToolbarSeparator()
            }
            RaftTextSelectionToolbarButton(
                label = stringResource(Res.string.common_copy),
                onClick = onCopy,
                icon = { RaftSlotIcon(RaftLucideIconName.Copy) },
            )
        }
    }
}

/**
 * Watches the body's presses without consuming them (selection keeps its gestures): any press
 * records whether it is yielded to the row and resets the mouse toolbar, and letting go of a
 * primary mouse press records where, which shows the toolbar if that gesture left a selection.
 */
private fun Modifier.threadInPlaceMouseSelectionEnd(controller: ThreadInPlaceSelectionController): Modifier =
    pointerInput(controller) {
        awaitPointerEventScope {
            var primaryMousePress = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Press -> {
                        primaryMousePress =
                            threadInPlaceSelectionPressIsPrimaryMouse(
                                pointerTypes = event.changes.map { it.type },
                                primaryPressed = event.buttons.isPrimaryPressed
                            )
                        val pressed = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: event.changes.firstOrNull()
                        controller.onBodyPointerPress(pressed?.type ?: PointerType.Unknown)
                    }
                    PointerEventType.Release ->
                        if (primaryMousePress && !event.buttons.isPrimaryPressed) {
                            primaryMousePress = false
                            val change = event.changes.firstOrNull()
                            if (change != null) controller.onBodyMouseRelease(change.position)
                        }
                }
            }
        }
    }

/** Separate scope: only the menu recomposes as the body moves; hidden while the selection is off-screen. */
@Composable
private fun ThreadInPlaceSelectionMenuHost(
    anchorFor: () -> ThreadInPlaceSelectionBarAnchor?,
    gap: Dp = 8.dp,
    menu: @Composable () -> Unit,
) {
    val anchor = anchorFor() ?: return
    val density = LocalDensity.current
    val gapPx = with(density) { gap.roundToPx() }
    val edgePx = with(density) { 8.dp.roundToPx() }
    val positionProvider =
        remember(anchor, gapPx, edgePx) {
            ThreadInPlaceSelectionBarPositionProvider(anchor, gapPx, edgePx)
        }
    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = false)
    ) {
        menu()
    }
}

/** Positions the bar against the visible selection, inside the list viewport (window as fallback). */
internal class ThreadInPlaceSelectionBarPositionProvider(
    private val anchor: ThreadInPlaceSelectionBarAnchor,
    private val gapPx: Int,
    private val edgePx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset =
        threadInPlaceSelectionBarOffset(
            visibleSelection = anchor.visibleSelection,
            area = anchor.area ?: Rect(0f, 0f, windowSize.width.toFloat(), windowSize.height.toFloat()),
            popupSize = popupContentSize,
            gapPx = gapPx,
            edgePx = edgePx
        )
}

/**
 * Above the visible selection when it fits inside [area], else below it, else (the selection
 * fills the viewport, e.g. Select All on a tall message) pinned to the top of [area]. The bar is
 * always clamped inside [area], the list's visible viewport.
 */
internal fun threadInPlaceSelectionBarOffset(
    visibleSelection: Rect,
    area: Rect,
    popupSize: IntSize,
    gapPx: Int,
    edgePx: Int,
): IntOffset {
    val minX = area.left.toInt() + edgePx
    val maxX = (area.right.toInt() - edgePx - popupSize.width).coerceAtLeast(minX)
    val x = (visibleSelection.center.x - popupSize.width / 2f).toInt().coerceIn(minX, maxX)
    val minY = area.top.toInt() + edgePx
    val maxY = (area.bottom.toInt() - edgePx - popupSize.height).coerceAtLeast(minY)
    val above = visibleSelection.top.toInt() - gapPx - popupSize.height
    val below = visibleSelection.bottom.toInt() + gapPx
    val y =
        when {
            above >= minY -> above
            below <= maxY -> below
            else -> minY
        }
    return IntOffset(x, y.coerceIn(minY, maxY))
}
