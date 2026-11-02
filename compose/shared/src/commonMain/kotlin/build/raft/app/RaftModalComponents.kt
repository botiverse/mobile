package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.LocalRaftCopyHandler
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.app.components.raftLookOverlaySurface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import build.raft.app.compat.ui.KeyboardParams
import build.raft.app.compat.ui.keyboardHeightChange
import build.raft.app.compat.ui.raftKeyboardHeightExcludesNavigationBars
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftDialogSheetDefaults
import build.raft.designsystem.components.RaftDialogSheetSurface
import build.raft.designsystem.components.raftDialogSheetCentered
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.app.components.RaftSlotIcon
import build.raft.app.components.raftButtonVariantFor
import build.raft.app.components.raftIconButtonVariantFor
import build.raft.app.components.LocalRaftDialogFooter
import build.raft.app.components.RaftLegacyIconInkArgb

// The brutal dialog defaults (p-6, 18sp title); raft swaps them for p-5 and the 17/22 headline.
private val RaftUnifiedDialogLegacyPadding = 24.dp
private const val RaftUnifiedDialogLegacyTitleSize = 18

/**
 * Keeps the dialog card on Compose's pointer hit path without advertising the card itself as an
 * accessibility action.
 *
 * A no-op `clickable` used to provide this barrier, but `clickable` also creates a merging
 * semantics node. UIKit treats an accessible parent view as a leaf, so that wrapper hid every
 * independently accessible action below it. Merely observing the gesture is sufficient here:
 * the topmost card stays in the hit path while descendant controls remain free to consume it.
 */
internal fun Modifier.raftDialogContentPointerBarrier(): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.all { !it.pressed }) {
                    break
                }
            }
        }
    }

/**
 * Handles taps on the scrim without creating a full-screen accessibility button.
 *
 * The dialog always exposes an explicit close action, so the visual scrim is a pointer affordance,
 * not a focusable control. Keeping it out of the semantics tree prevents it from shadowing the
 * dialog's tight child accessibility bounds on iOS.
 */
internal fun Modifier.raftModalDismissPointerBarrier(
    enabled: Boolean,
    onDismiss: () -> Unit
): Modifier =
    pointerInput(enabled) {
        detectTapGestures {
            if (enabled) {
                onDismiss()
            }
        }
    }

internal class RaftUnifiedDialogHostKey

internal const val RaftUnifiedDialogGenericHostRoute = "generic"

internal data class RaftUnifiedDialogHostEntry(
    val key: RaftUnifiedDialogHostKey,
    val route: String,
    val content: @Composable () -> Unit
)

internal class RaftUnifiedDialogHostState {
    var entries by mutableStateOf<List<RaftUnifiedDialogHostEntry>>(emptyList())
        private set

    fun upsert(key: RaftUnifiedDialogHostKey, route: String, content: @Composable () -> Unit) {
        val nextEntry = RaftUnifiedDialogHostEntry(key, route, content)
        val currentEntries = entries
        val existingIndex = currentEntries.indexOfFirst { it.key === key }
        entries =
            if (existingIndex >= 0) {
                currentEntries.toMutableList().also { it[existingIndex] = nextEntry }
            } else {
                currentEntries + nextEntry
            }
    }

    fun remove(key: RaftUnifiedDialogHostKey) {
        entries = entries.filterNot { it.key === key }
    }
}

private val LocalRaftUnifiedDialogHostState = compositionLocalOf<RaftUnifiedDialogHostState?> { null }
private val LocalRaftUnifiedDialogHostBottomInset = compositionLocalOf { 0.dp }

internal data class RaftUnifiedDialogOuterClearance(
    val start: Dp,
    val top: Dp,
    val end: Dp,
    val bottom: Dp
)

internal fun raftUnifiedDialogOuterClearance(
    outerPadding: Dp,
    topInset: Dp,
    bottomInset: Dp
): RaftUnifiedDialogOuterClearance =
    RaftUnifiedDialogOuterClearance(
        start = outerPadding,
        top = outerPadding + topInset,
        end = outerPadding,
        bottom = outerPadding + bottomInset
    )

/**
 * task #177: the shell's dialog host inset is the bottom tab bar height, which is
 * 0 when tabs are hidden (and there is no host at all on standalone pages). A
 * tall dialog then reached under the system navigation bar, so never reserve
 * less than the window's bottom safe area.
 */
internal fun raftUnifiedDialogHostBottomInset(hostBottomInset: Dp, bottomSafeArea: Dp): Dp =
    maxSafeAreaDp(hostBottomInset, bottomSafeArea)

internal fun raftUnifiedDialogEffectiveBottomInset(
    hostBottomInset: Dp,
    keyboardHeightDp: Float,
    keyboardAvoidanceEnabled: Boolean
): Dp {
    if (!keyboardAvoidanceEnabled) return hostBottomInset
    val keyboardInset = keyboardHeightDp.coerceAtLeast(0f).dp
    return if (keyboardInset > hostBottomInset) keyboardInset else hostBottomInset
}

/**
 * How a [RaftUnifiedDialog] shows on a phone: the web's `DialogCard narrow` / `ConfirmDialog
 * narrow`, below the window width where the app goes wide
 * ([RaftDialogSheetDefaults.CenteredMinWindowWidth]). A wide window shows every one of them as the
 * centred card.
 */
internal enum class RaftDialogPresentation {
    /** The centred card everywhere (`narrow="centered"`): confirmations, pickers, read-only dialogs. */
    Centered,

    /**
     * Centred, but while the keyboard is up the card moves to [RaftUnifiedDialogTopKeyboardOffset]
     * below the status bar (`ConfirmDialog narrow="top"`, a confirmation with a typed field).
     */
    Top,

    /**
     * On a phone, the web's bottom sheet (`DialogCard narrow="sheet"`): the design system's
     * [RaftDialogSheetSurface] at 90% of the window, × at the start, the commit at the end of the
     * head, no footer. For dialogs with several fields.
     */
    Sheet,
}

/** How far below the status bar a [RaftDialogPresentation.Top] card sits while the keyboard is up. */
internal val RaftUnifiedDialogTopKeyboardOffset = 56.dp

/** The phone sheet's slide in (web `DialogCard` sheet, 240ms) and out (180ms). */
internal const val RaftUnifiedDialogSheetEnterMillis = 240
internal const val RaftUnifiedDialogSheetExitMillis = 180

/**
 * After × or Back slid the sheet out and called `onDismiss`, how long the sheet waits for its
 * caller to remove it before it slides back (a caller that kept it open: a discard check).
 */
internal const val RaftUnifiedDialogSheetRestoreDelayMillis = 400L

/**
 * True inside the content of a [RaftUnifiedDialog] drawn as the phone sheet: the sheet's head
 * carries the commit and × is Cancel, so [RaftDialogFooter] renders nothing there.
 */
internal val LocalRaftDialogSheet = staticCompositionLocalOf { false }

/** Whether a dialog presented as [presentation] in a window [windowWidth] wide draws the phone sheet. */
internal fun raftUnifiedDialogDrawsSheet(presentation: RaftDialogPresentation, windowWidth: Dp): Boolean =
    presentation == RaftDialogPresentation.Sheet && !raftDialogSheetCentered(windowWidth)

/** Whether a [RaftDialogPresentation.Top] card is moved up under the status bar for the keyboard. */
internal fun raftUnifiedDialogTopAnchored(
    presentation: RaftDialogPresentation,
    windowWidth: Dp,
    keyboardHeightDp: Float
): Boolean =
    presentation == RaftDialogPresentation.Top && !raftDialogSheetCentered(windowWidth) && keyboardHeightDp > 0f

/**
 * How far the sheet's bottom edge sits above the window's bottom: the keyboard's top, or 0 while
 * it is down. [keyboardHeightDp] is the editors' `keyboardHeightChange` height, which excludes
 * the navigation bar on Android ([keyboardExcludesNavigationBars]); the navigation bar
 * ([bottomSafeArea]) is added back there.
 */
internal fun raftUnifiedDialogSheetKeyboardOffset(
    keyboardHeightDp: Float,
    bottomSafeArea: Dp,
    keyboardExcludesNavigationBars: Boolean
): Dp =
    if (keyboardHeightDp <= 0f) {
        0.dp
    } else {
        keyboardHeightDp.dp + if (keyboardExcludesNavigationBars) bottomSafeArea.coerceAtLeast(0.dp) else 0.dp
    }

/**
 * The sheet's height: 90% of the window, shrunk while the keyboard is up so the head stays below
 * the status bar ([topInset]) and the sheet sits on the keyboard ([keyboardOffset]).
 */
internal fun raftUnifiedDialogSheetHeight(windowHeight: Dp, topInset: Dp, keyboardOffset: Dp): Dp =
    minOf(
        windowHeight * RaftDialogSheetDefaults.HeightFraction,
        windowHeight - topInset.coerceAtLeast(0.dp) - keyboardOffset.coerceAtLeast(0.dp)
    ).coerceAtLeast(0.dp)

/**
 * Lets the dialog window's Back reach the phone sheet, so the sheet slides out before
 * `onDismiss` runs, as its × does. Null while the dialog is drawn as the centred card.
 */
internal class RaftUnifiedDialogCloseRelay {
    var animatedClose: (() -> Unit)? = null

    fun close(onDismiss: () -> Unit) {
        animatedClose?.invoke() ?: onDismiss()
    }
}

/**
 * Scroll delta needed to keep a focused editor inside a keyboard-reduced viewport.
 * Positive values move the list content upward; negative values move it downward.
 */
internal fun raftUnifiedDialogFocusedEditorScrollDelta(
    focusedTop: Float,
    focusedBottom: Float,
    viewportTop: Float,
    viewportBottom: Float
): Float =
    when {
        focusedBottom > viewportBottom -> focusedBottom - viewportBottom
        focusedTop < viewportTop -> focusedTop - viewportTop
        else -> 0f
    }

internal data class RaftUnifiedDialogListScrollPosition(
    val index: Int,
    val scrollOffset: Int
) {
    init {
        require(index >= 0)
        require(scrollOffset >= 0)
    }
}

/**
 * Owns one focused-editor scroll intent. Cancellation avoids redundant work; the generation
 * check keeps a late completion from mutating a superseded or disposed list.
 */
internal class RaftFocusedEditorScrollRequestCoordinator {
    private var generation = 0L
    private var job: Job? = null

    fun begin(): Long {
        generation += 1L
        job?.cancel()
        job = null
        return generation
    }

    fun attach(job: Job) {
        this.job = job
    }

    fun isCurrent(requestGeneration: Long): Boolean = generation == requestGeneration

    fun invalidate() {
        generation += 1L
        job?.cancel()
        job = null
    }
}

/**
 * Waits for the keyboard-driven relayout, then samples the current focused editor and viewport.
 * Geometry is deliberately read after both frames so focus changes and dialog disposal cannot
 * replay the bounds captured by an earlier keyboard callback.
 */
internal suspend fun raftUnifiedDialogKeepFocusedEditorVisible(
    keyboardHeight: Float,
    awaitRelayoutFrame: suspend () -> Unit,
    focusedBounds: () -> Rect?,
    viewportBounds: () -> Rect?,
    currentScrollPosition: () -> RaftUnifiedDialogListScrollPosition,
    scrollToItem: suspend (index: Int, scrollOffset: Int) -> Unit,
    isScrollInProgress: () -> Boolean,
    isRequestCurrent: () -> Boolean,
    isTargetActive: () -> Boolean = { true }
) {
    if (keyboardHeight <= 0f || !isRequestCurrent() || !isTargetActive() || isScrollInProgress()) return
    awaitRelayoutFrame()
    if (!isRequestCurrent() || !isTargetActive() || isScrollInProgress()) return
    awaitRelayoutFrame()
    if (!isRequestCurrent() || !isTargetActive() || isScrollInProgress()) return

    val focused = focusedBounds() ?: return
    val viewport = viewportBounds() ?: return
    val delta =
        raftUnifiedDialogFocusedEditorScrollDelta(
            focusedTop = focused.top,
            focusedBottom = focused.bottom,
            viewportTop = viewport.top,
            viewportBottom = viewport.bottom
        )
    if (delta == 0f || !isRequestCurrent() || !isTargetActive() || isScrollInProgress()) return

    // Root bounds and LazyList offsets are pixels. Round away from zero so a fractional edge
    // overlap cannot leave the editor one sub-pixel outside the reduced viewport.
    val pixelDelta =
        if (delta > 0f) {
            ceil(delta.toDouble()).toInt()
        } else {
            floor(delta.toDouble()).toInt()
        }
    val current = currentScrollPosition()
    val targetOffset = (current.scrollOffset + pixelDelta).coerceAtLeast(0)
    if (targetOffset == current.scrollOffset) return
    if (!isRequestCurrent() || !isTargetActive() || isScrollInProgress()) return
    scrollToItem(current.index, targetOffset)
}

/**
 * Scrolls the dialog's list directly to a specific target item when that section is focused
 * and requires full visibility above the keyboard (e.g. member search field and its results).
 */
internal suspend fun raftUnifiedDialogScrollToTargetItem(
    keyboardHeight: Float,
    targetItemIndex: Int,
    targetItemScrollOffset: Int = 0,
    awaitRelayoutFrame: suspend () -> Unit,
    scrollToItem: suspend (index: Int, scrollOffset: Int) -> Unit,
    isScrollInProgress: () -> Boolean,
    isRequestCurrent: () -> Boolean,
    isTargetActive: () -> Boolean = { true }
) {
    if (keyboardHeight <= 0f || !isRequestCurrent() || !isTargetActive() || isScrollInProgress()) return
    awaitRelayoutFrame()
    if (!isRequestCurrent() || !isTargetActive() || isScrollInProgress()) return
    awaitRelayoutFrame()
    if (!isRequestCurrent() || !isTargetActive() || isScrollInProgress()) return
    scrollToItem(targetItemIndex, targetItemScrollOffset)
}

@Composable
internal fun rememberRaftUnifiedDialogHostState(): RaftUnifiedDialogHostState =
    remember { RaftUnifiedDialogHostState() }

@Composable
internal fun RaftUnifiedDialogHostProvider(
    state: RaftUnifiedDialogHostState,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalRaftUnifiedDialogHostState provides state, content = content)
}

@Composable
internal fun RaftUnifiedDialogHost(
    state: RaftUnifiedDialogHostState,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp
) {
    if (state.entries.isEmpty()) {
        return
    }
    Box(
        modifier =
            modifier
                .zIndex(RaftGlobalModalHostZIndex)
                .fillMaxSize()
    ) {
        CompositionLocalProvider(LocalRaftUnifiedDialogHostBottomInset provides bottomInset) {
            state.entries.forEach { entry ->
                key(entry.key) {
                    entry.content()
                }
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftUnifiedDialog(
    title: String,
    onDismiss: () -> Unit,
    // Task #258 successor (artin msg=a493a371/7cfc6fb7): small muted identifier
    // line rendered above the title (e.g. "#all · 任务 #5") so the channel/task
    // label never renders as the oversized title. Null/blank = no overline.
    overline: String? = null,
    modifier: Modifier = Modifier,
    dismissEnabled: Boolean = true,
    // Web ConfirmDialog / FormDialog: false for destructive confirms and dialogs with typed
    // input, so a stray tap on the scrim can't close them. Only the scrim tap is disabled:
    // Back, the header close and the dialog's own Cancel still call onDismiss (gated, as
    // always, by dismissEnabled).
    dismissOnOutsideTap: Boolean = true,
    overlayAlpha: Float = 0.60f,
    minWidth: Dp = 312.dp,
    maxWidth: Dp = 408.dp,
    outerPadding: Dp = RaftAppSpacing.Md,
    // Web dialogs (raft-ui Dialog / DialogCard) use p-6 and an 18px bold uppercase
    // title; these defaults follow them so call sites only override deliberately.
    contentPadding: Dp = 24.dp,
    // Horizontal inset for the title/close header. Defaults to contentPadding so
    // ordinary dialogs are unchanged; dialogs that set contentPadding = 0 to let
    // an edge-to-edge list bleed (e.g. Forward) still keep the header aligned
    // with their own 16dp-inset content instead of flush against the border.
    headerPadding: Dp = contentPadding,
    titleFontSize: Int = 18,
    titleLineHeight: Int = 22,
    titleMaxLines: Int = 1,
    // Uppercased through raftDisplayUppercase, so CJK titles keep their case.
    uppercaseTitle: Boolean = true,
    contentSpacing: Dp = 16.dp,
    keyboardAvoidanceEnabled: Boolean = false,
    hostRoute: String = RaftUnifiedDialogGenericHostRoute,
    // Long-press copy (raftCopyable) of the title / overline when they show an identifier
    // (a task number, a channel); null (default): not copyable.
    titleCopyText: String? = null,
    overlineCopyText: String? = null,
    // How it shows on a phone (web `DialogCard narrow` / `ConfirmDialog narrow`); a wide window
    // always shows the centred card. [RaftDialogPresentation.Sheet] draws the phone sheet with the
    // commit below in its head and drops [RaftDialogFooter], so pass the footer's commit here; a
    // tap on the sheet's scrim never closes it (× and Back do).
    presentation: RaftDialogPresentation = RaftDialogPresentation.Centered,
    // The sheet head's commit button (the footer's commit, same words and state); null: none.
    // Read only while the phone sheet is drawn; the centred card keeps its own footer.
    commitLabel: String? = null,
    onCommit: (() -> Unit)? = null,
    commitEnabled: Boolean = true,
    commitLoading: Boolean = false,
    commitVariant: RaftButtonVariant = RaftButtonVariant.Primary,
    commitIcon: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // raft-ui dialog titles keep the source case (never ALL CAPS).
    val displayTitle = title
    // Read here, like topInset below: ModalView rehosts the content in a separate native window,
    // so the page's copy handler (host pasteboard + "Copied") is provided again inside it.
    val copyHandler = LocalRaftCopyHandler.current
    // Resolve and capture the pager inset before entering Kuikly's native ModalView. Reading a
    // composition local from inside the hosted Modal content is too late: ModalView rehosts that
    // content in a separate native window, where the page host's top-inset local is not retained.
    val topInset = raftWindowTopSafeAreaPadding()
    // Read here for the same reason as topInset (ModalView rehosts the content).
    val bottomSafeArea = raftBottomSafeAreaPadding()
    val hostState = LocalRaftUnifiedDialogHostState.current
    // Back reaches the phone sheet through this, so it slides out like its ×.
    val closeRelay = remember { RaftUnifiedDialogCloseRelay() }
    val commit =
        RaftUnifiedDialogCommit(
            label = commitLabel,
            onCommit = onCommit,
            enabled = commitEnabled,
            loading = commitLoading,
            variant = commitVariant,
            icon = commitIcon
        )
    if (hostState == null) {
        Modal(onWillDismiss = { if (dismissEnabled) closeRelay.close(onDismiss) }) {
            CompositionLocalProvider(LocalRaftCopyHandler provides copyHandler) {
                RaftUnifiedDialogSurface(
                    title = displayTitle,
                    overline = overline,
                    titleCopyText = titleCopyText,
                    overlineCopyText = overlineCopyText,
                    onDismiss = onDismiss,
                    modifier = modifier,
                    dismissEnabled = dismissEnabled,
                    dismissOnOutsideTap = dismissOnOutsideTap,
                    overlayAlpha = overlayAlpha,
                    minWidth = minWidth,
                    maxWidth = maxWidth,
                    outerPadding = outerPadding,
                    contentPadding = contentPadding,
                    headerPadding = headerPadding,
                    titleFontSize = titleFontSize,
                    titleLineHeight = titleLineHeight,
                    titleMaxLines = titleMaxLines,
                    contentSpacing = contentSpacing,
                    keyboardAvoidanceEnabled = keyboardAvoidanceEnabled,
                    topInset = topInset,
                    hostBottomInset = raftUnifiedDialogHostBottomInset(0.dp, bottomSafeArea),
                    bottomSafeArea = bottomSafeArea,
                    handleBack = false,
                    presentation = presentation,
                    commit = commit,
                    closeRelay = closeRelay,
                    content = content
                )
            }
        }
        return
    }
    val currentDialogContent =
        rememberUpdatedState<@Composable () -> Unit> {
            // The app-shell host decides global ordering above child pages and tabs, but the
            // dialog must still enter Kuikly's native ModalView. Rendering the surface directly
            // in the host leaves native editors/blank regions in the same render tree as the
            // underlay; fixing those holes with parent nativeDispatchCapture also blocks precise
            // TextField cursor gestures. ModalView provides the intended two-layer boundary: a
            // full-window native touch barrier below independently interactive dialog content.
            Modal(onWillDismiss = { if (dismissEnabled) closeRelay.close(onDismiss) }) {
                CompositionLocalProvider(LocalRaftCopyHandler provides copyHandler) {
                    RaftUnifiedDialogSurface(
                        title = displayTitle,
                        overline = overline,
                        titleCopyText = titleCopyText,
                        overlineCopyText = overlineCopyText,
                        onDismiss = onDismiss,
                        modifier = modifier,
                        dismissEnabled = dismissEnabled,
                        dismissOnOutsideTap = dismissOnOutsideTap,
                        overlayAlpha = overlayAlpha,
                        minWidth = minWidth,
                        maxWidth = maxWidth,
                        outerPadding = outerPadding,
                        contentPadding = contentPadding,
                        headerPadding = headerPadding,
                        titleFontSize = titleFontSize,
                        titleLineHeight = titleLineHeight,
                        titleMaxLines = titleMaxLines,
                        contentSpacing = contentSpacing,
                        keyboardAvoidanceEnabled = keyboardAvoidanceEnabled,
                        topInset = topInset,
                        hostBottomInset =
                            raftUnifiedDialogHostBottomInset(
                                LocalRaftUnifiedDialogHostBottomInset.current,
                                bottomSafeArea
                            ),
                        bottomSafeArea = bottomSafeArea,
                        handleBack = false,
                        presentation = presentation,
                        commit = commit,
                        closeRelay = closeRelay,
                        content = content
                    )
                }
            }
        }
    val hostKey = remember { RaftUnifiedDialogHostKey() }
    DisposableEffect(hostState, hostKey, hostRoute) {
        hostState.upsert(hostKey, hostRoute) {
            currentDialogContent.value()
        }
        onDispose {
            hostState.remove(hostKey)
        }
    }
}

/** Gap between dialog footer buttons; web dialogs use gap-3 (12px). */
internal val RaftDialogFooterGap = 12.dp

/**
 * The one footer layout for dialogs: buttons right-aligned at their natural width,
 * like web ConfirmDialog / DialogCard footers (`flex justify-end gap-3`). Inside a
 * [RaftUnifiedDialog] drawn as the phone sheet ([LocalRaftDialogSheet]) it draws nothing: pass
 * its commit to the dialog's `commitLabel` / `onCommit` instead.
 */
@Composable
internal fun RaftDialogFooter(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    // The phone sheet has no footer: its head carries the commit and × is Cancel.
    if (LocalRaftDialogSheet.current) return
    CompositionLocalProvider(build.raft.app.components.LocalRaftDialogFooter provides true) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RaftDialogFooterGap, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
private fun RaftUnifiedDialogSurface(
    title: String,
    overline: String?,
    titleCopyText: String?,
    overlineCopyText: String?,
    onDismiss: () -> Unit,
    modifier: Modifier,
    dismissEnabled: Boolean,
    dismissOnOutsideTap: Boolean,
    overlayAlpha: Float,
    minWidth: Dp,
    maxWidth: Dp,
    outerPadding: Dp,
    contentPadding: Dp,
    headerPadding: Dp,
    titleFontSize: Int,
    titleLineHeight: Int,
    titleMaxLines: Int,
    contentSpacing: Dp,
    keyboardAvoidanceEnabled: Boolean,
    topInset: Dp,
    hostBottomInset: Dp,
    bottomSafeArea: Dp,
    handleBack: Boolean,
    presentation: RaftDialogPresentation,
    commit: RaftUnifiedDialogCommit,
    closeRelay: RaftUnifiedDialogCloseRelay,
    content: @Composable ColumnScope.() -> Unit
) {
    if (handleBack) {
        RaftBackHandler(enabled = dismissEnabled, label = "unified_dialog", onBack = { closeRelay.close(onDismiss) })
    }
    var keyboardHeightDp by remember { mutableStateOf(0f) }
    // The sheet and the Top card follow the keyboard whatever keyboardAvoidanceEnabled says; the
    // centred card's bottom inset still reads only keyboardAvoidanceEnabled (below).
    val tracksKeyboard = keyboardAvoidanceEnabled || presentation != RaftDialogPresentation.Centered
    val onKeyboardHeightChange: (KeyboardParams) -> Unit =
        remember(tracksKeyboard) {
            { params: KeyboardParams ->
                if (tracksKeyboard) {
                    keyboardHeightDp = params.height.coerceAtLeast(0f)
                }
            }
        }
    val effectiveBottomInset =
        raftUnifiedDialogEffectiveBottomInset(
            hostBottomInset = hostBottomInset,
            keyboardHeightDp = keyboardHeightDp,
            keyboardAvoidanceEnabled = keyboardAvoidanceEnabled
        )
    val outerClearance =
        raftUnifiedDialogOuterClearance(
            outerPadding = outerPadding,
            topInset = topInset,
            bottomInset = effectiveBottomInset
        )
    // raft-ui 0.6.0 Dialog: 16dp dialog radius, `--layer-popover`, 1dp seam ring and the
    // overlay float shadow (which takes no layout space); 20dp (`p-5`) insets, a 17/22 bold
    // source-case title and a ghost 32dp close.
    val resolvedContentPadding = if (contentPadding == RaftUnifiedDialogLegacyPadding) 20.dp else contentPadding
    val resolvedHeaderPadding = if (headerPadding == RaftUnifiedDialogLegacyPadding) 20.dp else headerPadding
    val raftDefaultTitle = titleFontSize == RaftUnifiedDialogLegacyTitleSize
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
    ) {
        if (raftUnifiedDialogDrawsSheet(presentation, maxWidth)) {
            RaftUnifiedDialogPhoneSheet(
                title = title,
                onDismiss = onDismiss,
                modifier = modifier,
                dismissEnabled = dismissEnabled,
                overlayAlpha = overlayAlpha,
                windowHeight = maxHeight,
                topInset = topInset,
                bottomSafeArea = bottomSafeArea,
                keyboardHeightDp = keyboardHeightDp,
                onKeyboardHeightChange = onKeyboardHeightChange,
                contentSpacing = contentSpacing,
                commit = commit,
                closeRelay = closeRelay,
                content = content
            )
        } else {
            // web ConfirmDialog narrow="top": while the keyboard is up the card leaves the centre for
            // a fixed place under the status bar, so it doesn't jump as the keyboard animates.
            val topAnchored = raftUnifiedDialogTopAnchored(presentation, maxWidth, keyboardHeightDp)
            RaftModalDismissBarrier(
                overlayAlpha = overlayAlpha,
                onDismiss = onDismiss,
                dismissEnabled = dismissEnabled && dismissOnOutsideTap
            )
            Box(
                modifier =
                    modifier
                        .align(if (topAnchored) Alignment.TopCenter else Alignment.Center)
                        .padding(
                            start = outerClearance.start,
                            top = if (topAnchored) topInset + RaftUnifiedDialogTopKeyboardOffset else outerClearance.top,
                            end = outerClearance.end,
                            bottom = outerClearance.bottom
                        )
                        .widthIn(min = minWidth, max = maxWidth)
                        .raftDialogContentPointerBarrier()
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .raftLookOverlaySurface(radius = RaftAppRadius.Dialog)
                            .padding(resolvedContentPadding),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing)
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    // When a dialog sets contentPadding = 0 for an
                                    // edge-bleed body, the header would otherwise sit
                                    // flush against the top and left borders; restore
                                    // its inset to headerPadding on both axes.
                                    start = (resolvedHeaderPadding - resolvedContentPadding).coerceAtLeast(0.dp),
                                    end = (resolvedHeaderPadding - resolvedContentPadding).coerceAtLeast(0.dp),
                                    top = (resolvedHeaderPadding - resolvedContentPadding).coerceAtLeast(0.dp)
                                ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            overline?.takeIf { it.isNotBlank() }?.let { overlineText ->
                                Text(
                                    text = overlineText,
                                    style = RaftType.Caption.copy(
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        letterSpacing = 0.sp
                                    ),
                                    color = RaftColor.ForegroundHint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(bottom = 4.dp).fillMaxWidth().raftCopyable(overlineCopyText)
                                )
                            }
                            Text(
                                text = title,
                                style =
                                    if (raftDefaultTitle) {
                                        RaftType.PageTitle
                                            .copy(fontSize = 17.sp, lineHeight = 22.sp, color = RaftColor.TextBlack)
                                            .withRaftFontWeight(FontWeight.Bold)
                                    } else {
                                        RaftType.PageTitle.copy(
                                            fontSize = titleFontSize.sp,
                                            lineHeight = titleLineHeight.sp,
                                            color = RaftColor.TextBlack
                                        )
                                    },
                                color = RaftColor.TextBlack,
                                modifier = Modifier.fillMaxWidth().raftCopyable(titleCopyText),
                                maxLines = titleMaxLines,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        RaftBackButton(
                            onClick = onDismiss,
                            label = stringResource(Res.string.common_close),
                            glyph = RaftGlyph.X,
                            enabled = dismissEnabled,
                        )
                    }
                    CompositionLocalProvider(
                        LocalRaftDialogKeyboardHeightChange provides onKeyboardHeightChange
                    ) {
                        this@Column.content()
                    }
                }
            }
        }
    }
}

/** The phone sheet's head commit ([RaftUnifiedDialog]'s `commit*` parameters). */
internal class RaftUnifiedDialogCommit(
    val label: String?,
    val onCommit: (() -> Unit)?,
    val enabled: Boolean,
    val loading: Boolean,
    val variant: RaftButtonVariant,
    val icon: (@Composable () -> Unit)?
)

/**
 * [RaftUnifiedDialog] as the web's phone sheet (`DialogCard narrow="sheet"`), inside the dialog's
 * own [Modal] window: the scrim (which never closes it), and the design system's
 * [RaftDialogSheetSurface] docked to the bottom at 90% of the window: × at the start, the one-line
 * title, the commit at the end, no footer ([LocalRaftDialogSheet]), the body in the phone's 16dp
 * margins clear of the home indicator.
 *
 * It slides up in 240ms as the scrim fades in; × and Back slide it out (180ms) before
 * [onDismiss] runs. While the keyboard is up the sheet sits on it and shrinks, never above the
 * status bar ([raftUnifiedDialogSheetHeight]), so the head stays in view.
 */
@Composable
private fun RaftUnifiedDialogPhoneSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier,
    dismissEnabled: Boolean,
    overlayAlpha: Float,
    windowHeight: Dp,
    topInset: Dp,
    bottomSafeArea: Dp,
    keyboardHeightDp: Float,
    onKeyboardHeightChange: (KeyboardParams) -> Unit,
    contentSpacing: Dp,
    commit: RaftUnifiedDialogCommit,
    closeRelay: RaftUnifiedDialogCloseRelay,
    content: @Composable ColumnScope.() -> Unit
) {
    val reducedMotion = rememberRaftReducedMotion()
    // 1: off screen below the window (and the scrim clear); 0: in place.
    val slide = remember { Animatable(if (reducedMotion) 0f else 1f) }
    LaunchedEffect(Unit) {
        slide.animateTo(0f, tween(RaftUnifiedDialogSheetEnterMillis, easing = RaftMotion.EaseSlide))
    }
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentDismissEnabled by rememberUpdatedState(dismissEnabled)
    val currentReducedMotion by rememberUpdatedState(reducedMotion)
    var closing by remember { mutableStateOf(false) }
    val animatedClose: () -> Unit =
        remember {
            {
                if (currentDismissEnabled && !closing) {
                    closing = true
                    scope.launch {
                        try {
                            if (!currentReducedMotion) {
                                slide.animateTo(1f, tween(RaftUnifiedDialogSheetExitMillis, easing = RaftMotion.EaseSlide))
                            }
                            currentOnDismiss()
                            // Removing the dialog cancels this scope. A caller that kept it open
                            // (a discard check) gets the sheet back.
                            withFrameNanos { }
                            delay(RaftUnifiedDialogSheetRestoreDelayMillis)
                            slide.animateTo(0f, tween(RaftUnifiedDialogSheetEnterMillis, easing = RaftMotion.EaseSlide))
                        } finally {
                            closing = false
                        }
                    }
                }
            }
        }
    DisposableEffect(closeRelay, animatedClose) {
        closeRelay.animatedClose = animatedClose
        onDispose {
            if (closeRelay.animatedClose === animatedClose) {
                closeRelay.animatedClose = null
            }
        }
    }
    val keyboardOffset =
        raftUnifiedDialogSheetKeyboardOffset(
            keyboardHeightDp = keyboardHeightDp,
            bottomSafeArea = bottomSafeArea,
            keyboardExcludesNavigationBars = raftKeyboardHeightExcludesNavigationBars
        )
    val sheetHeight = raftUnifiedDialogSheetHeight(windowHeight, topInset, keyboardOffset)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                // The keyboard as the editors see it, also when no editor reports it (a list
                // without one): the sheet sits on it.
                .keyboardHeightChange(onKeyboardHeightChange)
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - slide.value }) {
            // A stray tap on the scrim loses nothing: only × and Back close the sheet.
            RaftModalDismissBarrier(
                overlayAlpha = overlayAlpha,
                onDismiss = {},
                dismissEnabled = false
            )
        }
        RaftDialogSheetSurface(
            title = title,
            commitLabel = commit.label,
            onCommit = { commit.onCommit?.invoke() },
            onClose = animatedClose,
            modifier =
                modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = keyboardOffset)
                    .graphicsLayer { translationY = (size.height + keyboardOffset.toPx()) * slide.value }
                    .height(sheetHeight)
                    .raftDialogContentPointerBarrier(),
            commitEnabled = commit.enabled && commit.onCommit != null,
            commitLoading = commit.loading,
            commitVariant = commit.variant,
            commitIcon = commit.icon,
            closeLabel = stringResource(Res.string.common_close),
            closeEnabled = dismissEnabled
        ) {
            CompositionLocalProvider(
                LocalRaftDialogSheet provides true,
                LocalRaftDialogKeyboardHeightChange provides onKeyboardHeightChange
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(
                                start = RaftDialogSheetBodyMargin,
                                top = RaftDialogSheetBodyMargin,
                                end = RaftDialogSheetBodyMargin,
                                // Clear of the home indicator; on the keyboard there is none.
                                bottom = RaftDialogSheetBodyMargin + if (keyboardOffset > 0.dp) 0.dp else bottomSafeArea
                            ),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing)
                ) {
                    content()
                }
            }
        }
    }
}

/** The phone sheet body's margin (raft-ui: a phone's 16dp page margin). */
private val RaftDialogSheetBodyMargin = 16.dp

@Composable
internal fun RaftModalActionButton(
    label: String,
    icon: RaftLucideIconName? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = RaftColor.SurfaceWhite,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    // raft: an `lg` Button (44dp); in a dialog footer the paper secondary is the ghost Cancel.
    RaftButton(
        text = label,
        onClick = onClick,
        modifier = modifier,
        variant = raftButtonVariantFor(color, inDialogFooter = LocalRaftDialogFooter.current),
        size = RaftButtonSize.Lg,
        enabled = enabled,
        loading = loading,
        leadingIcon = icon?.let { name -> { RaftSlotIcon(name) } }
    )
}

@Composable
internal fun RaftModalFullWidthActionButton(
    label: String,
    icon: RaftLucideIconName,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = RaftColor.SurfaceWhite,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    // raft: a full-width `lg` Button (44dp) with the variant mapped from [color].
    RaftButton(
        text = label,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        variant = raftButtonVariantFor(color, inDialogFooter = LocalRaftDialogFooter.current),
        size = RaftButtonSize.Lg,
        enabled = enabled,
        loading = loading,
        leadingIcon = { RaftSlotIcon(icon) }
    )
}

@Composable
internal fun RaftModalSquareActionButton(
    icon: RaftLucideIconName,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = RaftColor.SurfaceWhite,
    enabled: Boolean = true,
    iconColor: String = RaftLegacyIconInkArgb,
    loading: Boolean = false
) {
    RaftIconButton(
        onClick = onClick,
        tooltip = contentDescription.takeIf { it.isNotBlank() },
        modifier = modifier,
        variant = raftIconButtonVariantFor(color),
        size = RaftIconButtonSize.Md,
        enabled = enabled,
        loading = loading
    ) {
        // An explicit ink stays (a muted remove); the legacy black follows the variant.
        if (enabled && iconColor != RaftLegacyIconInkArgb) {
            RaftLucideIcon(icon, color = iconColor, modifier = Modifier.fillMaxSize())
        } else {
            RaftSlotIcon(icon)
        }
    }
}

@Composable
internal fun RaftModalListShell(
    height: Dp,
    modifier: Modifier = Modifier,
    listModifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    // A lazy list on the design-system card surface.
    RaftListRowGroup(modifier = modifier.fillMaxWidth().height(height)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(height).then(listModifier),
            content = content
        )
    }
}

@Composable
internal fun RaftModalSectionHeader(label: String) {
    RaftSectionEyebrow(text = label, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
}

/**
 * A member row's children (avatar + texts + trailing), embedded in the caller's row
 * shell (e.g. a native Button for the task detail assignee popover, where plain-`clickable`
 * rows lose to the outer dismiss barrier). A selected row sits on the design-system
 * selected fill (`info-soft`), so its ink stays the theme's foreground.
 */
@Composable
internal fun RowScope.RaftModalCompactMemberRowContent(
    title: String,
    subtitle: String,
    avatarWireValue: String?,
    avatarLabel: String,
    statusColor: Color?,
    statusPulsing: Boolean,
    avatarSize: Dp,
    sourceBadgeLabel: String?,
    enabled: Boolean,
    selected: Boolean,
    trailingText: String,
    trailingIcon: RaftLucideIconName?,
    onTrailingClick: (() -> Unit)?,
    trailingContentDescription: String? = null,
    trailingMuted: Boolean = false,
    roleBadgeLabel: String? = null,
    cacheSubject: build.raft.app.data.RaftAvatarCacheSubject?,
    cacheServerId: String?,
    avatarFill: Color = RaftColor.AccentLavender,
    titleBadge: (@Composable () -> Unit)? = null
) {
    val mutedInk = RaftColor.TextMutedGray
    RaftAvatarWithStatus(
        statusColor = statusColor,
            avatarSize = avatarSize,
            statusPulsing = statusPulsing
        ) {
            RaftAppAvatar(
                avatarWireValue = avatarWireValue,
                label = avatarLabel,
                avatarSize = avatarSize,
                cacheSubject = cacheSubject,
                cacheServerId = cacheServerId,
                fallbackFill = avatarFill
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = RaftType.Body.withRaftFontWeight(FontWeight.Medium).copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = if (enabled) RaftColor.TextBlack else RaftColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                titleBadge?.invoke()
                val badge = sourceBadgeLabel?.takeIf { it.isNotBlank() }
                if (badge != null) {
                    RaftBadge(
                        text = badge,
                        modifier = Modifier.widthIn(max = 88.dp),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Information
                    )
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = mutedInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        roleBadgeLabel?.takeIf { it.isNotBlank() }?.let { label ->
            RaftBadge(text = label, appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Muted)
        }
        when {
            trailingIcon != null && onTrailingClick != null -> {
                // A ghost icon button; a muted remove keeps its grey ink but still fires.
                RaftModalSquareActionButton(
                    icon = trailingIcon,
                    contentDescription = trailingContentDescription ?: stringResource(Res.string.common_remove),
                    onClick = onTrailingClick,
                    enabled = enabled,
                    iconColor = if (trailingMuted) "#FF777777" else RaftLegacyIconInkArgb
                )
            }
            trailingIcon != null -> RaftLucideIcon(
                trailingIcon,
                modifier = Modifier.size(15.dp),
                color = if (trailingMuted) "#FF777777" else raftLegacyIconInk()
            )
            trailingText.isNotBlank() -> Text(text = trailingText, style = RaftType.Caption, color = mutedInk)
            else -> Spacer(modifier = Modifier.size(0.dp))
        }
    }

// Black icon ink in light (unchanged); in dark the themed ink, or sticker ink on a sticker fill.
internal fun raftLegacyIconInk(onSticker: Boolean = false): String =
    when {
        !RaftAppThemeState.isDark -> "#FF000000"
        onSticker -> raftArgbHex(RaftColor.StickerInk)
        else -> RaftColor.TextBlackArgb
    }
