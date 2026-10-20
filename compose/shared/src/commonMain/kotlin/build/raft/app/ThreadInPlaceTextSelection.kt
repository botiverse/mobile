package build.raft.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import build.raft.app.components.raftLookOverlaySurface
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.awaitCancellation

/**
 * In-place text selection for chat-stream message bodies, behind the "New Message Menu" toggle.
 *
 * Gesture rule (toggle ON): a long-press that lands on the message body belongs to Compose's
 * [SelectionContainer] (select word, drag handles); the row-level long-press menu does not fire
 * there. A long-press on the rest of the row (avatar, author line, attachments, blank row space)
 * still opens the message menu, and the selection bar's More item opens it too. Toggle OFF: no
 * container, no deferral, the row behaves exactly as before.
 *
 * The platform text toolbar is replaced by our own bar on both toolbar paths: Android's new text
 * context menu ([LocalTextContextMenuToolbarProvider], on by default there) and the legacy
 * [LocalTextToolbar] that skiko/iOS still uses.
 */
internal class ThreadInPlaceTextSelectionHost(
    val enabled: Boolean,
    val copyText: (String) -> Unit,
) {
    private var active: ThreadInPlaceSelectionController? = null

    internal fun activate(controller: ThreadInPlaceSelectionController) {
        val previous = active
        if (previous != null && previous !== controller) previous.clear()
        active = controller
    }

    internal fun release(controller: ThreadInPlaceSelectionController) {
        if (active === controller) active = null
    }

    /** A pointer went down on a message row; clear a selection the press is not inside. */
    internal fun onRowPointerDown(messageId: String, insideBody: Boolean) {
        val current = active ?: return
        if (current.messageId == messageId && insideBody) return
        if (current.hasSelection) current.clear()
    }
}

internal val LocalThreadInPlaceTextSelectionHost =
    staticCompositionLocalOf<ThreadInPlaceTextSelectionHost?> { null }

/**
 * The text the "Select Text" page offers for a message (`ThreadMessageTextSelectionDialog` binds
 * `message.body`, locked by scripts/ci/check_source_constraints.py) and the menu's Copy action
 * copies. A whole-message in-place copy must match it.
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
): String {
    val selected = selectedTexts.filter { it.isNotEmpty() }
    if (selected.isEmpty()) return ""
    if (!fullMessageText.isNullOrBlank() && threadInPlaceSelectionCoversAll(selectedTexts, selectableTexts)) {
        return fullMessageText
    }
    return selected.joinToString(separator = "\n")
}

internal enum class ThreadInPlaceSelectionBarAction {
    Copy,
    SelectAll,
    More
}

internal val ThreadInPlaceSelectionBarActions =
    listOf(
        ThreadInPlaceSelectionBarAction.Copy,
        ThreadInPlaceSelectionBarAction.SelectAll,
        ThreadInPlaceSelectionBarAction.More
    )

@Stable
internal class ThreadInPlaceSelectionController(
    val messageId: String,
    private val host: ThreadInPlaceTextSelectionHost,
) {
    val selectionState = SelectionState()
    var menuRectInWindow by mutableStateOf<Rect?>(null)
        private set
    internal var bodyCoordinates: LayoutCoordinates? = null
    internal var rowCoordinates: LayoutCoordinates? = null

    val hasSelection: Boolean
        get() = selectionState.selectedTexts.any { it.isNotEmpty() }

    fun selectedTexts(): List<String> = selectionState.selectedTexts.map { it.text }

    fun selectableTexts(): List<String> = selectionState.getSelectableTexts().map { it.text }

    fun clear() {
        selectionState.clear()
        menuRectInWindow = null
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

    fun rowLocalOffsetOf(windowOffset: Offset): Offset? =
        rowCoordinates?.takeIf { it.isAttached }?.windowToLocal(windowOffset)

    internal fun showMenuAtRootRect(rootRect: Rect) {
        val body = bodyCoordinates?.takeIf { it.isAttached }
        val root = body?.findRootCoordinates()
        menuRectInWindow =
            if (root != null) {
                Rect(root.localToWindow(rootRect.topLeft), rootRect.size)
            } else {
                rootRect
            }
        host.activate(this)
    }

    internal fun showMenuFromProvider(dataProvider: TextContextMenuDataProvider): Boolean {
        val body = bodyCoordinates?.takeIf { it.isAttached } ?: return false
        val local = dataProvider.contentBounds(body)
        menuRectInWindow = Rect(body.localToWindow(local.topLeft), local.size)
        host.activate(this)
        return true
    }

    internal fun hideMenu() {
        menuRectInWindow = null
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
    diagnosticsLogger: (String) -> Unit,
    onShowMessageMenu: (rowLocalOffset: Offset?) -> Unit,
    content: @Composable () -> Unit,
) {
    if (controller == null) {
        content()
        return
    }
    val host = LocalThreadInPlaceTextSelectionHost.current ?: return content()
    val textToolbar = remember(controller) { ThreadInPlaceTextToolbar(controller) }
    val contextMenuProvider = remember(controller) { ThreadInPlaceTextContextMenuProvider(controller) }
    CompositionLocalProvider(
        LocalTextToolbar provides textToolbar,
        LocalTextContextMenuToolbarProvider provides contextMenuProvider,
    ) {
        SelectionContainer(
            controller.selectionState,
            Modifier.onGloballyPositioned { controller.bodyCoordinates = it },
        ) {
            content()
        }
    }
    val menuRect = controller.menuRectInWindow
    if (menuRect != null && controller.hasSelection) {
        ThreadInPlaceSelectionBar(
            anchorInWindow = menuRect,
            onAction = { action ->
                when (action) {
                    ThreadInPlaceSelectionBarAction.Copy -> {
                        val selected = controller.selectedTexts()
                        val selectable = controller.selectableTexts()
                        val text = threadInPlaceSelectionCopyText(selected, selectable, fullMessageText)
                        diagnosticsLogger(
                            "thread_in_place_selection_copy messageId=${controller.messageId} " +
                                "chars=${text.length} lines=${text.lines().size} " +
                                "full=${threadInPlaceSelectionCoversAll(selected, selectable)} nodes=${selected.count { it.isNotEmpty() }}"
                        )
                        if (text.isNotEmpty()) host.copyText(text)
                        controller.clear()
                    }
                    ThreadInPlaceSelectionBarAction.SelectAll -> {
                        diagnosticsLogger("thread_in_place_selection_select_all messageId=${controller.messageId}")
                        controller.selectAll()
                    }
                    ThreadInPlaceSelectionBarAction.More -> {
                        diagnosticsLogger("thread_in_place_selection_more messageId=${controller.messageId}")
                        val rowLocal = controller.rowLocalOffsetOf(menuRect.bottomCenter)
                        controller.clear()
                        onShowMessageMenu(rowLocal)
                    }
                }
            }
        )
    }
}

@Composable
private fun ThreadInPlaceSelectionBar(
    anchorInWindow: Rect,
    onAction: (ThreadInPlaceSelectionBarAction) -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.roundToPx() }
    val edgePx = with(density) { 8.dp.roundToPx() }
    val positionProvider =
        remember(anchorInWindow, gapPx, edgePx) {
            ThreadInPlaceSelectionBarPositionProvider(anchorInWindow, gapPx, edgePx)
        }
    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = false)
    ) {
        Row(
            modifier =
                Modifier
                    .raftLookOverlaySurface()
                    .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            ThreadInPlaceSelectionBarActions.forEach { action ->
                ThreadNewMessageMenuToolButton(
                    label = action.label(),
                    icon = action.icon(),
                    enabled = true,
                    onClick = { onAction(action) },
                    modifier = Modifier.width(56.dp).height(52.dp)
                )
            }
        }
    }
}

@Composable
private fun ThreadInPlaceSelectionBarAction.label(): String =
    when (this) {
        ThreadInPlaceSelectionBarAction.Copy -> stringResource(MR.strings.thread_text_selection_copy)
        ThreadInPlaceSelectionBarAction.SelectAll -> stringResource(MR.strings.thread_text_selection_select_all)
        ThreadInPlaceSelectionBarAction.More -> stringResource(MR.strings.thread_new_message_menu_more)
    }

private fun ThreadInPlaceSelectionBarAction.icon(): RaftLucideIconName =
    when (this) {
        ThreadInPlaceSelectionBarAction.Copy -> RaftLucideIconName.Copy
        ThreadInPlaceSelectionBarAction.SelectAll -> RaftLucideIconName.SquareCheckBig
        ThreadInPlaceSelectionBarAction.More -> RaftLucideIconName.Ellipsis
    }

/** Above the selection when there is room, otherwise below it; always inside the window. */
internal class ThreadInPlaceSelectionBarPositionProvider(
    private val anchorInWindow: Rect,
    private val gapPx: Int,
    private val edgePx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset =
        threadInPlaceSelectionBarOffset(anchorInWindow, windowSize, popupContentSize, gapPx, edgePx)
}

internal fun threadInPlaceSelectionBarOffset(
    anchorInWindow: Rect,
    windowSize: IntSize,
    popupSize: IntSize,
    gapPx: Int,
    edgePx: Int,
): IntOffset {
    val maxX = (windowSize.width - popupSize.width - edgePx).coerceAtLeast(edgePx)
    val x = (anchorInWindow.center.x - popupSize.width / 2f).toInt().coerceIn(edgePx, maxX)
    val above = anchorInWindow.top.toInt() - gapPx - popupSize.height
    val below = anchorInWindow.bottom.toInt() + gapPx
    val maxY = (windowSize.height - popupSize.height - edgePx).coerceAtLeast(edgePx)
    val y = if (above >= edgePx) above else below.coerceIn(edgePx, maxY)
    return IntOffset(x, y)
}
