package build.raft.app

import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftBrutalActionSheetRow
import build.raft.app.components.RaftBrutalIconButton
import build.raft.app.components.RaftBrutalListSectionHeader
import build.raft.app.components.RaftBrutalListShell
import build.raft.app.components.RaftBrutalModalHeader
import build.raft.app.components.RaftBrutalModalLayer
import build.raft.app.components.RaftActionSheetRowModel
import build.raft.app.components.RaftModalShellModel
import build.raft.app.components.raftActive
import build.raft.app.components.raftLookOverlaySurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import build.raft.app.compat.ui.KeyboardParams
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Job

private val RaftUnifiedDialogShadowOffset = 4.dp
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

@Composable
internal fun RaftModalLayer(
    title: String,
    subtitle: String = "",
    onDismiss: () -> Unit,
    overlayAlpha: Float = 0.60f,
    maxWidth: Dp = 384.dp,
    content: @Composable () -> Unit
) {
    RaftBrutalModalLayer(
        model =
            RaftModalShellModel(
                title = title,
                subtitle = subtitle,
                overlayAlpha = overlayAlpha,
                maxWidth = maxWidth
            ),
        onDismiss = onDismiss,
        content = content
    )
}

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
    content: @Composable ColumnScope.() -> Unit
) {
    // raft-ui dialog titles keep the source case (never ALL CAPS).
    val displayTitle = if (uppercaseTitle && !raftActive()) title.raftDisplayUppercase() else title
    // Resolve and capture the pager inset before entering Kuikly's native ModalView. Reading a
    // composition local from inside the hosted Modal content is too late: ModalView rehosts that
    // content in a separate native window, where the page host's top-inset local is not retained.
    val topInset = raftTopSafeAreaPadding()
    // Read here for the same reason as topInset (ModalView rehosts the content).
    val bottomSafeArea = raftBottomSafeAreaPadding()
    val hostState = LocalRaftUnifiedDialogHostState.current
    if (hostState == null) {
        Modal(onWillDismiss = { if (dismissEnabled) onDismiss() }) {
            RaftUnifiedDialogSurface(
                title = displayTitle,
                overline = overline,
                onDismiss = onDismiss,
                modifier = modifier,
                dismissEnabled = dismissEnabled,
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
                handleBack = false,
                content = content
            )
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
            Modal(onWillDismiss = { if (dismissEnabled) onDismiss() }) {
                RaftUnifiedDialogSurface(
                    title = displayTitle,
                    overline = overline,
                    onDismiss = onDismiss,
                    modifier = modifier,
                    dismissEnabled = dismissEnabled,
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
                    handleBack = false,
                    content = content
                )
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
 * like web ConfirmDialog / DialogCard footers (`flex justify-end gap-3`).
 */
@Composable
internal fun RaftDialogFooter(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
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
    onDismiss: () -> Unit,
    modifier: Modifier,
    dismissEnabled: Boolean,
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
    handleBack: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    if (handleBack) {
        RaftBackHandler(enabled = dismissEnabled, label = "unified_dialog", onBack = onDismiss)
    }
    var keyboardHeightDp by remember { mutableStateOf(0f) }
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
    // source-case title and a ghost 32dp close. The brutal register keeps its offset slab.
    val raft = raftActive()
    val shadowReserve = if (raft) 0.dp else RaftUnifiedDialogShadowOffset
    val resolvedContentPadding = if (raft && contentPadding == RaftUnifiedDialogLegacyPadding) 20.dp else contentPadding
    val resolvedHeaderPadding = if (raft && headerPadding == RaftUnifiedDialogLegacyPadding) 20.dp else headerPadding
    val raftDefaultTitle = raft && titleFontSize == RaftUnifiedDialogLegacyTitleSize
    Box(
        modifier =
            Modifier
                .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        RaftModalDismissBarrier(
            overlayAlpha = overlayAlpha,
            onDismiss = onDismiss,
            dismissEnabled = dismissEnabled
        )
        Box(
            modifier =
                modifier
                    .padding(
                        start = outerClearance.start,
                        top = outerClearance.top,
                        end = outerClearance.end,
                        bottom = outerClearance.bottom
                    )
                    .padding(end = shadowReserve, bottom = shadowReserve)
                    .widthIn(min = minWidth, max = maxWidth)
                    .raftDialogContentPointerBarrier()
        ) {
            if (!raft) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .offset(x = RaftUnifiedDialogShadowOffset, y = RaftUnifiedDialogShadowOffset)
                            .background(RaftColor.ShadowBlack)
                )
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (raft) {
                                Modifier.raftLookOverlaySurface(radius = RaftAppRadius.Dialog)
                            } else {
                                Modifier
                                    .background(RaftColor.SurfaceWhite)
                                    .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                            }
                        )
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
                                    fontSize = if (raft) 12.sp else 11.sp,
                                    lineHeight = if (raft) 16.sp else 14.sp,
                                    letterSpacing = if (raft) 0.sp else 0.6.sp
                                ),
                                color = if (raft) RaftColor.ForegroundHint else RaftColor.TextMutedGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 4.dp).fillMaxWidth()
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
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = titleMaxLines,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    RaftBrutalIconButton(
                        enabled = dismissEnabled,
                        iconName = RaftLucideIconName.X,
                        onClick = onDismiss,
                        backgroundColor = if (raft) Color.Transparent else RaftColor.SurfaceWhite,
                        size = if (raft) 32.dp else 34.dp,
                        iconSize = if (raft) 18.dp else 17.dp,
                        shadowOffset = 3.dp
                    )
                }
                CompositionLocalProvider(
                    LocalRaftDialogKeyboardHeightChange provides { params: KeyboardParams ->
                        if (keyboardAvoidanceEnabled) {
                            keyboardHeightDp = params.height.coerceAtLeast(0f)
                        }
                    }
                ) {
                    this@Column.content()
                }
            }
        }
    }
}

@Composable
private fun RaftModalHeader(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit
) {
    RaftBrutalModalHeader(
        model = RaftModalShellModel(title = title, subtitle = subtitle),
        onDismiss = onDismiss
    )
}

@Composable
internal fun RaftModalIconButton(
    icon: RaftLucideIconName,
    onClick: () -> Unit
) {
    RaftBrutalIconButton(iconName = icon, onClick = onClick, size = 34.dp, shadowOffset = 0.dp)
}

@Composable
internal fun RaftActionSheetRow(
    title: String,
    subtitle: String = "",
    icon: RaftLucideIconName,
    destructive: Boolean = false,
    menuItemPadding: Boolean = false,
    onClick: () -> Unit
) {
    RaftBrutalActionSheetRow(
        model =
            RaftActionSheetRowModel(
                title = title,
                subtitle = subtitle,
                icon = icon,
                destructive = destructive
            ),
        onClick = onClick,
        menuItemPadding = menuItemPadding
    )
}

@Composable
internal fun RaftModalActionButton(
    label: String,
    icon: RaftLucideIconName? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = RaftColor.SurfaceWhite,
    enabled: Boolean = true,
    iconSpinning: Boolean = icon == RaftLucideIconName.Loader || icon == RaftLucideIconName.LoaderCircle
) {
    RaftBrutalActionButton(
        label = label,
        leadingIcon = icon,
        leadingIconSpinning = iconSpinning,
        onClick = onClick,
        modifier = modifier,
        backgroundColor = color,
        enabled = enabled,
        textStyle = RaftType.ButtonBold
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
    iconSpinning: Boolean = icon == RaftLucideIconName.Loader || icon == RaftLucideIconName.LoaderCircle
) {
    if (raftActive()) {
        // raft: a full-width `lg` Button (44dp) with the variant mapped from [color].
        RaftBrutalActionButton(
            label = label,
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            backgroundColor = color,
            minHeight = 44.dp,
            leadingIcon = icon,
            leadingIconSpinning = iconSpinning
        )
        return
    }
    val shadowOffset = 3.dp
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(40.dp + shadowOffset)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .offset(x = shadowOffset, y = shadowOffset)
                    .background(RaftColor.ShadowBlack)
        )
        Button(
            enabled = enabled,
            onClick = onClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(if (enabled) color else RaftColor.SurfaceCream)
                    .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RaftLucideIcon(icon, modifier = Modifier.size(13.dp), color = "#FF000000", spinning = iconSpinning)
                Text(
                    text = label,
                    // React .btn-brutal is font-bold; regular weight here made
                    // the danger buttons visibly lighter than web (task #348).
                    style = RaftType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp, color = RaftColor.TextBlack),
                    color = if (enabled) RaftColor.TextBlack else RaftColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun RaftModalSquareActionButton(
    icon: RaftLucideIconName,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = RaftColor.SurfaceWhite,
    enabled: Boolean = true,
    size: Dp = 28.dp,
    iconColor: String = "#FF000000",
    iconSpinning: Boolean = icon == RaftLucideIconName.Loader || icon == RaftLucideIconName.LoaderCircle
) {
    RaftBrutalIconButton(
        iconName = icon,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        backgroundColor = color,
        size = size,
        shadowOffset = 3.dp,
        iconColor = iconColor,
        disabledIconColor = iconColor,
        iconSpinning = iconSpinning
    )
}

@Composable
internal fun RaftModalListShell(
    height: Dp,
    modifier: Modifier = Modifier,
    listModifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    RaftBrutalListShell(
        height = height,
        modifier = modifier,
        listModifier = listModifier,
        content = content
    )
}

@Composable
internal fun RaftModalSectionHeader(label: String) {
    RaftBrutalListSectionHeader(label = label)
}

@Composable
internal fun RaftModalCompactMemberRow(
    title: String,
    subtitle: String,
    avatarWireValue: String?,
    avatarLabel: String,
    statusColor: Color?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionChecked: Boolean? = null,
    enabled: Boolean = true,
    clickEnabled: Boolean = enabled,
    avatarSize: Dp = 30.dp,
    statusPulsing: Boolean = false,
    sourceBadgeLabel: String? = null,
    trailingText: String = "",
    trailingIcon: RaftLucideIconName? = null,
    onTrailingClick: (() -> Unit)? = null,
    // Greys out the trailing affordance (e.g. a cross-server follower's
    // remove-X) while keeping it tappable — the click still fires so the
    // caller can surface a hint. Distinct from `enabled`, which gates clicks.
    trailingMuted: Boolean = false,
    roleBadgeLabel: String? = null,
    channelPageStyle: Boolean = false,
    cacheSubject: build.raft.app.data.RaftAvatarCacheSubject? = null,
    cacheServerId: String? = null,
    onClick: () -> Unit = {}
) {
    val rowBackground =
        when {
            !enabled -> RaftColor.SurfaceCream
            selected -> RaftColor.BrandYellow
            else -> RaftColor.SurfaceWhite
        }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBackground)
            .clickable(enabled = enabled && clickEnabled, onClick = onClick)
            .padding(horizontal = if (channelPageStyle) 0.dp else 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        selectionChecked?.let { checked ->
            build.raft.app.components.RaftAppCheckbox(checked = checked,
                onCheckedChange = { onClick() }, enabled = enabled && clickEnabled)
        }
        // Task #258 (artin msg=ed597ee6): the same member-row visuals are reused by
        // the task detail assignee popover (inside a native Button for dispatch).
        RaftModalCompactMemberRowContent(
            title = title,
            subtitle = subtitle,
            avatarWireValue = avatarWireValue,
            avatarLabel = avatarLabel,
            statusColor = statusColor,
            statusPulsing = statusPulsing,
            avatarSize = avatarSize,
            sourceBadgeLabel = sourceBadgeLabel,
            enabled = enabled,
            selected = selected,
            trailingText = trailingText,
            trailingIcon = trailingIcon,
            onTrailingClick = onTrailingClick,
            trailingMuted = trailingMuted,
            roleBadgeLabel = roleBadgeLabel,
            channelPageStyle = channelPageStyle,
            cacheSubject = cacheSubject,
            cacheServerId = cacheServerId
        )
    }
}

/**
 * Row children of RaftModalCompactMemberRow (avatar + texts + trailing) so the
 * exact channel add-member visuals can be embedded in other row shells (e.g. a
 * native Button for the task detail assignee popover, where plain-`clickable`
 * rows lose to the outer dismiss barrier).
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
    trailingMuted: Boolean = false,
    roleBadgeLabel: String? = null,
    channelPageStyle: Boolean = false,
    cacheSubject: build.raft.app.data.RaftAvatarCacheSubject?,
    cacheServerId: String?
) {
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
                cacheServerId = cacheServerId
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
                val badge = sourceBadgeLabel?.takeIf { it.isNotBlank() }
                if (badge != null) {
                    Box(
                        modifier =
                            Modifier
                                .height(16.dp)
                                .widthIn(max = 88.dp)
                                // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1065
                                .background(RaftColor.AccentCyan.copy(alpha = 0.24f))
                                .border(RaftAppStroke.Thin, RaftColor.BorderBlack)
                                .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (channelPageStyle) badge else badge.raftDisplayUppercase(),
                            style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 9.sp, lineHeight = 16.sp),
                            color = RaftColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = RaftColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        roleBadgeLabel?.takeIf { it.isNotBlank() }?.let { label ->
            Box(Modifier.background(if (channelPageStyle) Color(0xFFE7E5E4) else RaftColor.SurfaceCream)
                .then(if (channelPageStyle) Modifier.border(1.dp, RaftColor.BorderBlack) else Modifier)
                .padding(horizontal = 5.dp, vertical = 2.dp)) {
                Text(text = label, style = RaftType.Caption.copy(fontSize = if (channelPageStyle) 10.sp else 12.sp), color = RaftColor.TextMutedGray,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            trailingIcon != null && onTrailingClick != null -> {
                if (trailingIcon == RaftLucideIconName.X || channelPageStyle) {
                    RaftModalMemberRemoveButton(
                        icon = trailingIcon,
                        onClick = onTrailingClick,
                        enabled = enabled,
                        muted = trailingMuted
                    )
                } else {
                    RaftModalSquareActionButton(
                        icon = trailingIcon,
                        onClick = onTrailingClick,
                        color = if (selected) RaftColor.SurfaceWhite else Color(0xFFFFF2F2),
                        enabled = enabled,
                        iconColor = if (trailingMuted) "#FF777777" else "#FF000000"
                    )
                }
            }
            trailingIcon != null -> RaftLucideIcon(
                trailingIcon,
                modifier = Modifier.size(15.dp),
                color = if (trailingMuted) "#FF777777" else "#FF000000"
            )
            trailingText.isNotBlank() -> Text(text = trailingText, style = RaftType.Caption, color = RaftColor.TextMutedGray)
            else -> Spacer(modifier = Modifier.size(0.dp))
        }
    }

@Composable
private fun RaftModalMemberRemoveButton(
    icon: RaftLucideIconName,
    onClick: () -> Unit,
    enabled: Boolean,
    muted: Boolean = false
) {
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .background(if (enabled && !muted) Color(0xFFFFE5E5) else RaftColor.SurfaceCream)
                .border(RaftAppStroke.Thin, RaftColor.BorderBlack)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        RaftLucideIcon(
            icon,
            modifier = Modifier.size(12.dp),
            color = if (enabled && !muted) "#FF000000" else "#FF777777"
        )
    }
}
