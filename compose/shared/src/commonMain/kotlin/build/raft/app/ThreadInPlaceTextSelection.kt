package build.raft.app

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import build.raft.app.compat.ui.LocalRaftPageActive
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
 * Gesture rule (toggle ON): a long-press that lands on the message body belongs to Compose's
 * [SelectionContainer] (select word, drag handles); the row-level long-press menu does not fire
 * there. A long-press on the rest of the row (avatar, author line, attachments, blank row space)
 * still opens the message menu. Toggle OFF: no container, no deferral, the row behaves exactly as
 * before.
 *
 * The platform text toolbar is replaced on both toolbar paths (Android's new text context menu,
 * [LocalTextContextMenuToolbarProvider], on by default there, and the legacy [LocalTextToolbar]
 * that skiko/iOS still uses) by the full Compact New Message Menu, WeChat style: reactions and
 * message tools, with Copy copying the selection and Select All added.
 */
internal class ThreadInPlaceTextSelectionHost(
    val enabled: Boolean,
    val copyText: (String) -> Unit,
) {
    private var active: ThreadInPlaceSelectionTarget? = null

    /**
     * The page's Compact New Message Menu for a selection; ThreadMessagesListContent, which owns
     * the message actions, publishes into it.
     */
    val selectionMenu = ThreadInPlaceSelectionMenuSlot()

    /** The thread list's visible viewport in window space; the bar never leaves it. */
    var viewportInWindow by mutableStateOf<Rect?>(null)
        private set

    internal fun updateViewport(bounds: Rect) {
        if (bounds != viewportInWindow) viewportInWindow = bounds
    }

    internal fun activate(target: ThreadInPlaceSelectionTarget) {
        val previous = active
        if (previous != null && previous !== target) previous.clear()
        active = target
    }

    internal fun release(target: ThreadInPlaceSelectionTarget) {
        if (active === target) active = null
    }

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
    return selected.joinToString(separator = "\n")
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

    override val hasSelection: Boolean
        get() = selectionState.selectedTexts.any { it.isNotEmpty() }

    internal fun onBodyPositioned(coordinates: LayoutCoordinates) {
        bodyCoordinates = coordinates
        if (menuRequested) bodyLayoutTick++
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

    fun selectedTexts(): List<String> = selectionState.selectedTexts.map { it.text }

    fun selectableTexts(): List<String> = selectionState.getSelectableTexts().map { it.text }

    override fun clear() {
        selectionState.clear()
        hideMenu()
    }

    fun selectAll() {
        host.activate(this)
        selectionState.selectAll()
    }

    /** Row-local [offset] (the row long-press detector's space) lies on the selectable body. */
    fun bodyContainsRowOffset(offset: Offset): Boolean {
        val row = rowCoordinates?.takeIf { it.isAttached } ?: return false
        val body = bodyCoordinates?.takeIf { it.isAttached } ?: return false
        return row.localBoundingBoxOf(body, clipBounds = false).contains(offset)
    }

    /** Legacy TextToolbar path: [rootRect] is in root coordinates; store it body-relative. */
    internal fun showMenuAtRootRect(rootRect: Rect) {
        val body = bodyCoordinates?.takeIf { it.isAttached } ?: return
        val topLeft = body.localPositionOf(body.findRootCoordinates(), rootRect.topLeft)
        menuDataProvider = null
        menuRectInBody = Rect(topLeft, rootRect.size)
        host.activate(this)
    }

    /** New context-menu path (Android default): keep the live provider and re-query each frame. */
    internal fun showMenuFromProvider(dataProvider: TextContextMenuDataProvider): Boolean {
        if (bodyCoordinates?.isAttached != true) return false
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
        controller.showMenuAtRootRect(rect)
        status = TextToolbarStatus.Shown
    }

    override fun hide() {
        controller.hideMenu()
        status = TextToolbarStatus.Hidden
    }
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
    // raft-ui MessageItem text `selection:bg-selection` (0.23 #730): brand-soft in light, brand at
    // 22% in dark; the handles take the caret's ink, as in the designsystem RaftTheme.
    val themeColors = RaftAppThemeState.colors
    val selectionColors =
        remember(themeColors) {
            TextSelectionColors(handleColor = themeColors.foreground, backgroundColor = themeColors.selection)
        }
    CompositionLocalProvider(
        LocalTextToolbar provides textToolbar,
        LocalTextContextMenuToolbarProvider provides contextMenuProvider,
        LocalTextSelectionColors provides selectionColors,
    ) {
        SelectionContainer(
            controller.selectionState,
            Modifier.onGloballyPositioned(controller::onBodyPositioned),
        ) {
            content()
        }
    }
    // A kept-alive page that goes inactive (hidden shell tab) drops its selection so the popup
    // bar cannot linger over the visible page.
    val pageActive = LocalRaftPageActive.current
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
    LaunchedEffect(controller, hasSelection) {
        if (
            hasSelection &&
                threadInPlaceSelectionWidensToWholeMessage(
                    startedByTouch = controller.lastPointerDownFromTouch,
                    coversAll =
                        threadInPlaceSelectionCoversAll(controller.selectedTexts(), controller.selectableTexts())
                )
        ) {
            diagnosticsLogger("thread_in_place_selection_default_select_all messageId=${controller.messageId}")
            controller.selectAll()
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
    if (pageActive && controller.menuRequested && controller.hasSelection) {
        val request =
            remember(controller, fullMessageText, wholeMessageText, host) {
                ThreadInPlaceSelectionMenuRequest(
                    messageId = controller.messageId,
                    copySelection = {
                        val selected = controller.selectedTexts()
                        val selectable = controller.selectableTexts()
                        val text =
                            threadInPlaceSelectionCopyText(
                                selectedTexts = selected,
                                selectableTexts = selectable,
                                fullMessageText = fullMessageText,
                                emptySelectionFallback = wholeMessageText
                            )
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
                    }
                )
            }
        ThreadInPlaceSelectionMenuHost(controller = controller) {
            host.selectionMenu.menu(request)
        }
    }
}

/** Separate scope: only the menu recomposes as the body moves; hidden while the selection is off-screen. */
@Composable
private fun ThreadInPlaceSelectionMenuHost(
    controller: ThreadInPlaceSelectionController,
    menu: @Composable () -> Unit,
) {
    val anchor = controller.currentMenuAnchorInWindow() ?: return
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.roundToPx() }
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
