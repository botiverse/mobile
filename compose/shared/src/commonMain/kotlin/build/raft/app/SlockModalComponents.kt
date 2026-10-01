package build.raft.app

import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalActionSheetRow
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalListSectionHeader
import build.raft.app.components.SlockBrutalListShell
import build.raft.app.components.SlockBrutalModalHeader
import build.raft.app.components.SlockBrutalModalLayer
import build.raft.app.components.SlockActionSheetRowModel
import build.raft.app.components.SlockModalShellModel
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
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.awaitEachGesture
import com.tencent.kuikly.compose.foundation.gestures.awaitFirstDown
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.RowScope
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListScope
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Rect
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.ui.zIndex
import com.tencent.kuikly.core.views.KeyboardParams
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Job

private val SlockUnifiedDialogShadowOffset = 4.dp

/**
 * Keeps the dialog card on Compose's pointer hit path without advertising the card itself as an
 * accessibility action.
 *
 * A no-op `clickable` used to provide this barrier, but `clickable` also creates a merging
 * semantics node. UIKit treats an accessible parent view as a leaf, so that wrapper hid every
 * independently accessible action below it. Merely observing the gesture is sufficient here:
 * the topmost card stays in the hit path while descendant controls remain free to consume it.
 */
internal fun Modifier.slockDialogContentPointerBarrier(): Modifier =
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
internal fun Modifier.slockModalDismissPointerBarrier(
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

internal class SlockUnifiedDialogHostKey

internal const val SlockUnifiedDialogGenericHostRoute = "generic"

internal data class SlockUnifiedDialogHostEntry(
    val key: SlockUnifiedDialogHostKey,
    val route: String,
    val content: @Composable () -> Unit
)

internal class SlockUnifiedDialogHostState {
    var entries by mutableStateOf<List<SlockUnifiedDialogHostEntry>>(emptyList())
        private set

    fun upsert(key: SlockUnifiedDialogHostKey, route: String, content: @Composable () -> Unit) {
        val nextEntry = SlockUnifiedDialogHostEntry(key, route, content)
        val currentEntries = entries
        val existingIndex = currentEntries.indexOfFirst { it.key === key }
        entries =
            if (existingIndex >= 0) {
                currentEntries.toMutableList().also { it[existingIndex] = nextEntry }
            } else {
                currentEntries + nextEntry
            }
    }

    fun remove(key: SlockUnifiedDialogHostKey) {
        entries = entries.filterNot { it.key === key }
    }
}

private val LocalSlockUnifiedDialogHostState = compositionLocalOf<SlockUnifiedDialogHostState?> { null }
private val LocalSlockUnifiedDialogHostBottomInset = compositionLocalOf { 0.dp }

internal data class SlockUnifiedDialogOuterClearance(
    val start: Dp,
    val top: Dp,
    val end: Dp,
    val bottom: Dp
)

internal fun slockUnifiedDialogOuterClearance(
    outerPadding: Dp,
    topInset: Dp,
    bottomInset: Dp
): SlockUnifiedDialogOuterClearance =
    SlockUnifiedDialogOuterClearance(
        start = outerPadding,
        top = outerPadding + topInset,
        end = outerPadding,
        bottom = outerPadding + bottomInset
    )

internal fun slockUnifiedDialogEffectiveBottomInset(
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
internal fun slockUnifiedDialogFocusedEditorScrollDelta(
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

internal data class SlockUnifiedDialogListScrollPosition(
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
internal class SlockFocusedEditorScrollRequestCoordinator {
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
internal suspend fun slockUnifiedDialogKeepFocusedEditorVisible(
    keyboardHeight: Float,
    awaitRelayoutFrame: suspend () -> Unit,
    focusedBounds: () -> Rect?,
    viewportBounds: () -> Rect?,
    currentScrollPosition: () -> SlockUnifiedDialogListScrollPosition,
    scrollToItem: suspend (index: Int, scrollOffset: Int) -> Unit,
    isScrollInProgress: () -> Boolean,
    isRequestCurrent: () -> Boolean
) {
    if (keyboardHeight <= 0f || !isRequestCurrent() || isScrollInProgress()) return
    awaitRelayoutFrame()
    if (!isRequestCurrent() || isScrollInProgress()) return
    awaitRelayoutFrame()
    if (!isRequestCurrent() || isScrollInProgress()) return

    val focused = focusedBounds() ?: return
    val viewport = viewportBounds() ?: return
    val delta =
        slockUnifiedDialogFocusedEditorScrollDelta(
            focusedTop = focused.top,
            focusedBottom = focused.bottom,
            viewportTop = viewport.top,
            viewportBottom = viewport.bottom
        )
    if (delta == 0f || !isRequestCurrent() || isScrollInProgress()) return

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
    if (!isRequestCurrent() || isScrollInProgress()) return
    scrollToItem(current.index, targetOffset)
}

@Composable
internal fun rememberSlockUnifiedDialogHostState(): SlockUnifiedDialogHostState =
    remember { SlockUnifiedDialogHostState() }

@Composable
internal fun SlockUnifiedDialogHostProvider(
    state: SlockUnifiedDialogHostState,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalSlockUnifiedDialogHostState provides state, content = content)
}

@Composable
internal fun SlockUnifiedDialogHost(
    state: SlockUnifiedDialogHostState,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp
) {
    if (state.entries.isEmpty()) {
        return
    }
    Box(
        modifier =
            modifier
                .zIndex(SlockGlobalModalHostZIndex)
                .fillMaxSize()
    ) {
        CompositionLocalProvider(LocalSlockUnifiedDialogHostBottomInset provides bottomInset) {
            state.entries.forEach { entry ->
                key(entry.key) {
                    entry.content()
                }
            }
        }
    }
}

@Composable
internal fun SlockModalLayer(
    title: String,
    subtitle: String = "",
    onDismiss: () -> Unit,
    overlayAlpha: Float = 0.60f,
    maxWidth: Dp = 384.dp,
    content: @Composable () -> Unit
) {
    SlockBrutalModalLayer(
        model =
            SlockModalShellModel(
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
internal fun SlockUnifiedDialog(
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
    outerPadding: Dp = SlockSpacing.Md,
    contentPadding: Dp = 20.dp,
    // Horizontal inset for the title/close header. Defaults to contentPadding so
    // ordinary dialogs are unchanged; dialogs that set contentPadding = 0 to let
    // an edge-to-edge list bleed (e.g. Forward) still keep the header aligned
    // with their own 16dp-inset content instead of flush against the border.
    headerPadding: Dp = contentPadding,
    titleFontSize: Int = 22,
    titleLineHeight: Int = 26,
    titleMaxLines: Int = 1,
    contentSpacing: Dp = 16.dp,
    keyboardAvoidanceEnabled: Boolean = false,
    hostRoute: String = SlockUnifiedDialogGenericHostRoute,
    content: @Composable ColumnScope.() -> Unit
) {
    // Resolve and capture the pager inset before entering Kuikly's native ModalView. Reading a
    // composition local from inside the hosted Modal content is too late: ModalView rehosts that
    // content in a separate native window, where the page host's top-inset local is not retained.
    val topInset = slockTopSafeAreaPadding()
    val hostState = LocalSlockUnifiedDialogHostState.current
    if (hostState == null) {
        Modal(onWillDismiss = { if (dismissEnabled) onDismiss() }) {
            SlockUnifiedDialogSurface(
                title = title,
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
                hostBottomInset = 0.dp,
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
                SlockUnifiedDialogSurface(
                    title = title,
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
                    hostBottomInset = LocalSlockUnifiedDialogHostBottomInset.current,
                    handleBack = false,
                    content = content
                )
            }
        }
    val hostKey = remember { SlockUnifiedDialogHostKey() }
    DisposableEffect(hostState, hostKey, hostRoute) {
        hostState.upsert(hostKey, hostRoute) {
            currentDialogContent.value()
        }
        onDispose {
            hostState.remove(hostKey)
        }
    }
}

@Composable
private fun SlockUnifiedDialogSurface(
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
        SlockBackHandler(enabled = dismissEnabled, label = "unified_dialog", onBack = onDismiss)
    }
    var keyboardHeightDp by remember { mutableStateOf(0f) }
    val effectiveBottomInset =
        slockUnifiedDialogEffectiveBottomInset(
            hostBottomInset = hostBottomInset,
            keyboardHeightDp = keyboardHeightDp,
            keyboardAvoidanceEnabled = keyboardAvoidanceEnabled
        )
    val outerClearance =
        slockUnifiedDialogOuterClearance(
            outerPadding = outerPadding,
            topInset = topInset,
            bottomInset = effectiveBottomInset
        )
    Box(
        modifier =
            Modifier
                .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        SlockModalDismissBarrier(
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
                    .padding(end = SlockUnifiedDialogShadowOffset, bottom = SlockUnifiedDialogShadowOffset)
                    .widthIn(min = minWidth, max = maxWidth)
                    .slockDialogContentPointerBarrier()
        ) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = SlockUnifiedDialogShadowOffset, y = SlockUnifiedDialogShadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .padding(contentPadding),
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
                                start = (headerPadding - contentPadding).coerceAtLeast(0.dp),
                                end = (headerPadding - contentPadding).coerceAtLeast(0.dp),
                                top = (headerPadding - contentPadding).coerceAtLeast(0.dp)
                            ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        overline?.takeIf { it.isNotBlank() }?.let { overlineText ->
                            Text(
                                text = overlineText,
                                style = SlockType.Caption.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    letterSpacing = 0.6.sp
                                ),
                                color = SlockColor.TextMutedGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 4.dp).fillMaxWidth()
                            )
                        }
                        Text(
                            text = title,
                            style =
                                SlockType.PageTitle.copy(
                                    fontSize = titleFontSize.sp,
                                    lineHeight = titleLineHeight.sp,
                                    color = SlockColor.TextBlack
                                ),
                            color = SlockColor.TextBlack,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = titleMaxLines,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    SlockBrutalIconButton(
                        enabled = dismissEnabled,
                        iconName = SlockLucideIconName.X,
                        onClick = onDismiss,
                        size = 34.dp,
                        shadowOffset = 3.dp
                    )
                }
                CompositionLocalProvider(
                    LocalSlockDialogKeyboardHeightChange provides { params: KeyboardParams ->
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
private fun SlockModalHeader(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit
) {
    SlockBrutalModalHeader(
        model = SlockModalShellModel(title = title, subtitle = subtitle),
        onDismiss = onDismiss
    )
}

@Composable
internal fun SlockModalIconButton(
    icon: SlockLucideIconName,
    onClick: () -> Unit
) {
    SlockBrutalIconButton(iconName = icon, onClick = onClick, size = 34.dp, shadowOffset = 0.dp)
}

@Composable
internal fun SlockActionSheetRow(
    title: String,
    subtitle: String = "",
    icon: SlockLucideIconName,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    SlockBrutalActionSheetRow(
        model =
            SlockActionSheetRowModel(
                title = title,
                subtitle = subtitle,
                icon = icon,
                destructive = destructive
            ),
        onClick = onClick
    )
}

@Composable
internal fun SlockModalActionButton(
    label: String,
    icon: SlockLucideIconName? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = SlockColor.SurfaceWhite,
    enabled: Boolean = true,
    iconSpinning: Boolean = icon == SlockLucideIconName.Loader || icon == SlockLucideIconName.LoaderCircle
) {
    SlockBrutalActionButton(
        label = label,
        leadingIcon = icon,
        leadingIconSpinning = iconSpinning,
        onClick = onClick,
        modifier = modifier,
        backgroundColor = color,
        enabled = enabled,
        textStyle = SlockType.ButtonBold
    )
}

@Composable
internal fun SlockModalFullWidthActionButton(
    label: String,
    icon: SlockLucideIconName,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = SlockColor.SurfaceWhite,
    enabled: Boolean = true,
    iconSpinning: Boolean = icon == SlockLucideIconName.Loader || icon == SlockLucideIconName.LoaderCircle
) {
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
                    .background(SlockColor.ShadowBlack)
        )
        Button(
            enabled = enabled,
            onClick = onClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(if (enabled) color else SlockColor.SurfaceCream)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockLucideIcon(icon, modifier = Modifier.size(13.dp), color = "#FF000000", spinning = iconSpinning)
                Text(
                    text = label,
                    // React .btn-brutal is font-bold; regular weight here made
                    // the danger buttons visibly lighter than web (task #348).
                    style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                    color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun SlockModalSquareActionButton(
    icon: SlockLucideIconName,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = SlockColor.SurfaceWhite,
    enabled: Boolean = true,
    size: Dp = 28.dp,
    iconColor: String = "#FF000000",
    iconSpinning: Boolean = icon == SlockLucideIconName.Loader || icon == SlockLucideIconName.LoaderCircle
) {
    SlockBrutalIconButton(
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
internal fun SlockModalListShell(
    height: Dp,
    content: LazyListScope.() -> Unit
) {
    SlockBrutalListShell(height = height, content = content)
}

@Composable
internal fun SlockModalSectionHeader(label: String) {
    SlockBrutalListSectionHeader(label = label)
}

@Composable
internal fun SlockModalCompactMemberRow(
    title: String,
    subtitle: String,
    avatarWireValue: String?,
    avatarLabel: String,
    statusColor: Color?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    clickEnabled: Boolean = enabled,
    avatarSize: Dp = 30.dp,
    statusPulsing: Boolean = false,
    sourceBadgeLabel: String? = null,
    trailingText: String = "",
    trailingIcon: SlockLucideIconName? = null,
    onTrailingClick: (() -> Unit)? = null,
    // Greys out the trailing affordance (e.g. a cross-server follower's
    // remove-X) while keeping it tappable — the click still fires so the
    // caller can surface a hint. Distinct from `enabled`, which gates clicks.
    trailingMuted: Boolean = false,
    roleBadgeLabel: String? = null,
    cacheSubject: build.raft.app.data.SlockAvatarCacheSubject? = null,
    cacheServerId: String? = null,
    onClick: () -> Unit = {}
) {
    val rowBackground =
        when {
            !enabled -> SlockColor.SurfaceCream
            selected -> SlockColor.BrandYellow
            else -> SlockColor.SurfaceWhite
        }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBackground)
            .clickable(enabled = enabled && clickEnabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Task #258 (artin msg=ed597ee6): the same member-row visuals are reused by
        // the task detail assignee popover (inside a native Button for dispatch).
        SlockModalCompactMemberRowContent(
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
            cacheSubject = cacheSubject,
            cacheServerId = cacheServerId
        )
    }
}

/**
 * Row children of SlockModalCompactMemberRow (avatar + texts + trailing) so the
 * exact channel add-member visuals can be embedded in other row shells (e.g. a
 * native Button for the task detail assignee popover, where plain-`clickable`
 * rows lose to the outer dismiss barrier).
 */
@Composable
internal fun RowScope.SlockModalCompactMemberRowContent(
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
    trailingIcon: SlockLucideIconName?,
    onTrailingClick: (() -> Unit)?,
    trailingMuted: Boolean = false,
    roleBadgeLabel: String? = null,
    cacheSubject: build.raft.app.data.SlockAvatarCacheSubject?,
    cacheServerId: String?
) {
    SlockAvatarWithStatus(
        statusColor = statusColor,
            avatarSize = avatarSize,
            statusSize = if (avatarSize >= 40.dp) 10.dp else 8.dp,
            statusPulsing = statusPulsing
        ) {
            SlockAvatar(
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
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Medium).copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
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
                                .background(SlockColor.AccentCyan.copy(alpha = 0.24f))
                                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                                .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = badge.uppercase(),
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 9.sp, lineHeight = 16.sp),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        roleBadgeLabel?.takeIf { it.isNotBlank() }?.let { label ->
            Box(Modifier.background(SlockColor.SurfaceCream).padding(horizontal = 5.dp, vertical = 2.dp)) {
                Text(text = label, style = SlockType.Caption, color = SlockColor.TextMutedGray,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            trailingIcon != null && onTrailingClick != null -> {
                if (trailingIcon == SlockLucideIconName.X) {
                    SlockModalMemberRemoveButton(
                        icon = trailingIcon,
                        onClick = onTrailingClick,
                        enabled = enabled,
                        muted = trailingMuted
                    )
                } else {
                    SlockModalSquareActionButton(
                        icon = trailingIcon,
                        onClick = onTrailingClick,
                        color = if (selected) SlockColor.SurfaceWhite else Color(0xFFFFF2F2),
                        enabled = enabled,
                        iconColor = if (trailingMuted) "#FF777777" else "#FF000000"
                    )
                }
            }
            trailingIcon != null -> SlockLucideIcon(
                trailingIcon,
                modifier = Modifier.size(15.dp),
                color = if (trailingMuted) "#FF777777" else "#FF000000"
            )
            trailingText.isNotBlank() -> Text(text = trailingText, style = SlockType.Caption, color = SlockColor.TextMutedGray)
            else -> Spacer(modifier = Modifier.size(0.dp))
        }
    }

@Composable
private fun SlockModalMemberRemoveButton(
    icon: SlockLucideIconName,
    onClick: () -> Unit,
    enabled: Boolean,
    muted: Boolean = false
) {
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .background(if (enabled && !muted) Color(0xFFFFE5E5) else SlockColor.SurfaceCream)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(
            icon,
            modifier = Modifier.size(12.dp),
            color = if (enabled && !muted) "#FF000000" else "#FF777777"
        )
    }
}
