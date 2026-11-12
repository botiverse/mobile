package build.raft.app.assistant

import build.raft.designsystem.components.LocalRaftBarButtons
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import build.raft.designsystem.components.RaftPopoverSurface
import build.raft.designsystem.components.RaftDismissOnPageCover
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import build.raft.designsystem.components.RaftIconButtonSize
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.RaftThreadMessageItem
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.resources.*
import build.raft.app.threadActionCard
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.expiredAsOf
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDrawer
import build.raft.designsystem.components.RaftDrawerFill
import build.raft.designsystem.components.RaftDrawerSnapPoint
import build.raft.designsystem.components.raftDrawerDragArea
import build.raft.designsystem.components.rememberRaftDrawerState
import build.raft.app.RaftBackHandler
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource

// The assistant opened from its otter (web assistant/AssistantOtterPanel.tsx, in AssistantOtter's
// phone `Drawer`): its head and the owner's DM with it, over the page.

/** The meta line under the name (web AssistantOtterPanel `metaId`): what it is doing, else what it can read. */
internal fun assistantPanelMeta(mood: RaftOtterMood, readsDms: Boolean): StringResource =
    when {
        mood == RaftOtterMood.Working -> Res.string.assistant_otter_meta_working
        mood == RaftOtterMood.Confirm -> Res.string.assistant_otter_meta_confirm
        readsDms -> Res.string.assistant_header_meta_with_dms
        else -> Res.string.assistant_header_meta
    }

private val WaitingActionCardStates = setOf("prepared", "reconfirm_required")

/**
 * Whether the assistant's own action card in these messages still waits for its owner (web
 * assistantOtterState.ts `hasWaitingActionCard`): a card sent by [agentId] in `prepared` or
 * `reconfirm_required`, and not past its `expiresAt`.
 */
internal fun hasWaitingAssistantActionCard(
    messages: List<RaftThreadMessageItem>,
    agentId: String,
    nowMillis: Long = defaultNowMillis(),
): Boolean =
    messages.any { message ->
        // A card past its `expiresAt` waits on nobody any more.
        message.authorId == agentId && message.threadActionCard()?.expiredAsOf(nowMillis)?.state in WaitingActionCardStates
    }

/**
 * Given to the conversation the panel embeds: it reports the messages it holds, so the otter
 * knows of a card waiting there (web reads the DM's messages from its message store; the app
 * holds them only while the panel shows the conversation).
 */
internal class ThreadAssistantPanelEmbed(
    val agentId: String,
    val onWaitingActionCard: (Boolean) -> Unit,
)

/**
 * Set inside the panel's conversation: that conversation page is the assistant's DM in the
 * panel (web `ChatPanel hideHeader allowTasks={false}`): no page head and no tabs, and it
 * reports its messages here. Null on every other page.
 */
internal val LocalThreadAssistantPanelEmbed = compositionLocalOf<ThreadAssistantPanelEmbed?> { null }

/** The panel's conversation reporting what it holds (see [ThreadAssistantPanelEmbed]). */
@Composable
internal fun ThreadAssistantPanelMessagesReport(messages: List<RaftThreadMessageItem>) {
    val embed = LocalThreadAssistantPanelEmbed.current ?: return
    LaunchedEffect(embed, messages) {
        embed.onWaitingActionCard(hasWaitingAssistantActionCard(messages, embed.agentId))
    }
}

/**
 * The assistant's panel (web AssistantOtterPanel): the head, then [conversation] filling the
 * rest. The head is raft-ui's panel head: 56dp on the canvas with a seam under it; the otter's
 * head as the assistant's face (the image is cut at its chest, so it sits on the 32dp box's
 * bottom edge); the name in `text-headline` with the paused chip beside it; the meta line in
 * caption on `foreground-hint`, up to two lines (web `ASSISTANT_SUBTITLE_CLASS`); collapse (a
 * chevron down, as in a bottom sheet) at the end.
 */
@Composable
internal fun ThreadAssistantPanel(
    name: String,
    mood: RaftOtterMood,
    readsDms: Boolean,
    paused: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    conversation: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    Column(modifier.fillMaxSize().background(colors.layerCanvas).testTag(ThreadAssistantPanelTestTag)) {
        // The head's icon-lg buttons are bar buttons: 28dp under a mouse or trackpad (sizing v12).
        CompositionLocalProvider(LocalRaftBarButtons provides true) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(ThreadAssistantPanelHeadHeight)
                    // The collapse button's 18dp chevron ends on the 16dp line: 3dp out for the 44dp
                    // button, 11dp for the 28dp bar button under a mouse or trackpad (sizing v12).
                    .padding(start = 16.dp, end = 16.dp - (RaftTheme.controls.barButtonSize - 18.dp) / 2)
                    // In the phone's drawer the head drags the sheet, as its grab handle does.
                    .raftDrawerDragArea()
                    .testTag(ThreadAssistantPanelHeaderTestTag),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).clipToBounds(), contentAlignment = Alignment.BottomCenter) {
                    Image(
                        painter = painterResource(Res.drawable.assistant_otter_curious),
                        contentDescription = null,
                        modifier = Modifier.requiredSize(38.dp, 26.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RaftText(
                            name,
                            Modifier.weight(1f, fill = false).semantics { heading() },
                            style = RaftTheme.typography.headline,
                            color = colors.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (paused) {
                            // Web AssistantPausedChip: the agent state chip with the offline light and "Paused".
                            RaftBadge(
                                text = stringResource(Res.string.assistant_paused_chip),
                                modifier = Modifier.testTag(ThreadAssistantPanelPausedTestTag),
                                appearance = RaftBadgeAppearance.Soft,
                                variant = RaftBadgeVariant.Muted,
                                icon = { RaftStatusDot(presence = RaftPresence.Offline) },
                            )
                        }
                    }
                    RaftText(
                        stringResource(assistantPanelMeta(mood, readsDms)),
                        style = RaftTheme.typography.caption,
                        color = colors.foregroundHint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // A finger's 44dp (`Lg`, the phone top bar's icon button): the 32dp `Md` left most of
                // the head's height round the chevron dead, and taps there did nothing.
                RaftIconButton(
                    onClick = onCollapse,
                    tooltip = stringResource(Res.string.assistant_otter_collapse),
                    modifier = Modifier.testTag(ThreadAssistantPanelCollapseTestTag),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Lg,
                ) {
                    RaftGlyphIcon(RaftGlyph.ChevronDown, size = 18.dp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
        conversation()
    }
}

/**
 * The assistant's DM could not be opened (web `assistant-dm-missing`): one line in the body's
 * middle.
 */
@Composable
internal fun ColumnScope.ThreadAssistantPanelConversationMissing(name: String) {
    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        RaftText(
            stringResource(Res.string.assistant_conversation_missing, name),
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foregroundMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The otter's panel over the page while it is open, as web AssistantOtter shows it:
 *
 * - A phone window (narrower than [ThreadAssistantPanelWideMinWidth], web `PHONE_MEDIA_QUERY`):
 *   the `Drawer`, `--drawer-content-height: 82vh`, a [RaftDrawer] holding [ThreadAssistantPanel]
 *   with [conversation] as its body, on the canvas from its grab handle down (one opaque layer,
 *   #proj-mobile:#134). It has snap points: it opens at 82% ([initialSnapPoint]), the grab handle
 *   and the panel's head drag it, up to the whole window, back down to 82%, and further down
 *   closes it. Back from the whole window goes back to 82%; Back there, a tap on the scrim and
 *   collapse close it; it slides up as it opens and back down as it closes (the drawer stays
 *   composed until that ends). The
 *   drawer is its own window, so the keyboard is that window's (no host IME report).
 *   [windowOverlay] is drawn over the whole drawer window: the page's notification banner while
 *   the panel is open (see [threadForegroundBannerOnPage]).
 * - A wider window: the `Popover` ([ThreadAssistantPanelPopover]), anchored to where the otter
 *   rests on its composer; no scrim, and it appears and goes at once (raft-ui Popover has no
 *   motion). The page keeps its own banner.
 *
 * [onCoversPageChange] reports whether a drawer is over the page now (the page then hands its
 * banner to [windowOverlay]).
 */
@Composable
internal fun ThreadAssistantPanelSheet(
    otter: ThreadAssistantOtter,
    windowOverlay: @Composable BoxScope.() -> Unit = {},
    onCoversPageChange: (Boolean) -> Unit = {},
    initialSnapPoint: RaftDrawerSnapPoint = RaftDrawerSnapPoint.Default,
    conversation: @Composable ColumnScope.() -> Unit,
) {
    val wide = threadAssistantPanelWide()
    // The drawer is composed from opening until its slide down has ended; the popover only while open.
    var drawerShown by remember { mutableStateOf(false) }
    if (!wide && otter.panelOpen && !drawerShown) drawerShown = true
    val coversPage = !wide && otter.panelOpen
    val coversPageChange by rememberUpdatedState(onCoversPageChange)
    SideEffect { coversPageChange(coversPage) }
    if (if (wide) !otter.panelOpen else !drawerShown) return
    val close by rememberUpdatedState { otter.onPanelOpenChange(false) }
    val panel: @Composable () -> Unit = {
        CompositionLocalProvider(LocalRaftHostImeInsets provides null) {
            ThreadAssistantPanel(
                name = otter.assistantName,
                mood = otter.mood,
                readsDms = otter.readsDms,
                paused = otter.paused,
                onCollapse = { close() },
                conversation = {
                    CompositionLocalProvider(
                        LocalThreadAssistantPanelEmbed provides otter.panelEmbed,
                        // The otter's own composer is not in the panel: the DM has none.
                        LocalThreadAssistantOtter provides null,
                    ) {
                        conversation()
                    }
                },
            )
        }
    }
    if (wide) {
        ThreadAssistantPanelPopover(rest = otter.rest.bounds, onDismissRequest = { close() }) { panel() }
    } else {
        // One per opening: the drawer leaves composition once it has slid down.
        val drawerState = rememberRaftDrawerState(initialSnapPoint)
        val scope = rememberCoroutineScope()
        RaftDrawer(
            onDismissRequest = { close() },
            modifier = Modifier.testTag(ThreadAssistantPanelSheetTestTag),
            clearNavigationBar = false,
            visible = otter.panelOpen,
            onHidden = { drawerShown = false },
            overlay = windowOverlay,
            state = drawerState,
            fill = RaftDrawerFill.Canvas,
        ) {
            // Composed in the drawer's window, after its own Back (which closes it): pulled up to
            // the whole window, Back first brings it back down to 82%.
            RaftBackHandler(
                enabled = otter.panelOpen && drawerState.snapPoint == RaftDrawerSnapPoint.Full,
                label = "assistant-panel-full",
            ) {
                scope.launch { drawerState.animateTo(RaftDrawerSnapPoint.Default) }
            }
            panel()
        }
    }
}

/** Web `PHONE_MEDIA_QUERY` `(max-width: 767px)`: from 768dp the panel is a popover. */
internal val ThreadAssistantPanelWideMinWidth = 768.dp

/** Whether this window shows the panel as a popover (wider than a phone, web `!phone`). */
@Composable
internal fun threadAssistantPanelWide(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    val width = with(LocalDensity.current) { size.width.toDp() }
    return width >= ThreadAssistantPanelWideMinWidth
}

/** Web AssistantOtter `PopoverContent`: `w-[380px] h-[540px]`, `sideOffset={8}`, `collisionPadding={8}`. */
internal object ThreadAssistantPanelPopoverDefaults {
    val Width = 380.dp
    val Height = 540.dp
    val SideOffset = 8.dp
    val CollisionPadding = 8.dp
}

/**
 * Where the popover goes in a [window] (web `side="top" align="end"` on the otter's rest box,
 * Floating UI's `size` and `shift`): above [rest], [ThreadAssistantPanelPopoverDefaults.SideOffset]
 * off it, its end on the rest box's end; as tall as there is room above (at most 540dp) and
 * kept [ThreadAssistantPanelPopoverDefaults.CollisionPadding] inside the window. Without a rest
 * box (the otter not measured yet) it sits at the window's bottom end. Pixels in, pixels out.
 */
internal fun threadAssistantPanelPopoverBounds(
    rest: Rect?,
    window: IntSize,
    width: Float,
    height: Float,
    sideOffset: Float,
    padding: Float,
): Rect {
    val w = width.coerceAtMost(window.width - 2 * padding).coerceAtLeast(0f)
    val anchorTop = rest?.top ?: (window.height - padding + sideOffset)
    val anchorEnd = rest?.right ?: (window.width - padding)
    val bottom = anchorTop - sideOffset
    val h = height.coerceAtMost(bottom - padding).coerceAtLeast(0f)
    val left = (anchorEnd - w).coerceIn(padding, (window.width - padding - w).coerceAtLeast(padding))
    return Rect(left, bottom - h, left + w, bottom)
}

/**
 * The panel in raft-ui's Popover chrome ([RaftPopoverSurface]: popover layer, container radius,
 * seam ring, float shadow) at [threadAssistantPanelPopoverBounds]. It is focusable (the DM's
 * composer types in it); a press outside and Back call [onDismissRequest].
 */
@Composable
private fun ThreadAssistantPanelPopover(
    rest: Rect?,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val bounds =
        with(density) {
            threadAssistantPanelPopoverBounds(
                rest = rest,
                window = window,
                width = ThreadAssistantPanelPopoverDefaults.Width.toPx(),
                height = ThreadAssistantPanelPopoverDefaults.Height.toPx(),
                sideOffset = ThreadAssistantPanelPopoverDefaults.SideOffset.toPx(),
                padding = ThreadAssistantPanelPopoverDefaults.CollisionPadding.toPx(),
            )
        }
    val positioner =
        remember(bounds) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset = IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt())
            }
        }
    // A page pushed over this one (a thread ref in the panel's DM) closes the popover first: the
    // covered page stops composing, so the close on its `pageActive` came too late.
    RaftDismissOnPageCover(onDismissRequest)
    Popup(
        popupPositionProvider = positioner,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        with(density) { ThreadAssistantPanelPopoverSurface(bounds.width.toDp(), bounds.height.toDp(), content) }
    }
}

/** [ThreadAssistantPanelPopover]'s surface without its popup window (fixtures draw it in place). */
@Composable
internal fun ThreadAssistantPanelPopoverSurface(
    width: Dp,
    height: Dp,
    content: @Composable () -> Unit,
) {
    RaftPopoverSurface(
        modifier = Modifier.height(height).testTag(ThreadAssistantPanelPopoverTestTag),
        width = width,
        contentPadding = PaddingValues(0.dp),
    ) {
        content()
    }
}

/**
 * Whether a conversation page draws the foreground notification banner itself. One banner shows,
 * on top of everything: the assistant's DM inside the panel ([inAssistantPanel]) draws none (it
 * drew a second one, placed from the panel's top), and while a page's panel is open
 * ([assistantPanelOpen]) the page hands its banner to the panel's window
 * ([ThreadAssistantPanelSheet] `windowOverlay`), since its own would sit under the scrim.
 */
internal fun threadForegroundBannerOnPage(inAssistantPanel: Boolean, assistantPanelOpen: Boolean): Boolean =
    !inAssistantPanel && !assistantPanelOpen

// 56 with a finger, 44 under a mouse or trackpad (sizing v12, raft-ui 0.65 desktop density).
private val ThreadAssistantPanelHeadHeight: Dp
    @Composable @ReadOnlyComposable
    get() = RaftTheme.controls.headHeight

/** Web `data-testid`s: `assistant-otter-sheet`, `assistant-otter-popover`, `assistant-otter-panel`, its header and collapse. */
internal const val ThreadAssistantPanelPopoverTestTag = "assistant-otter-popover"
internal const val ThreadAssistantPanelSheetTestTag = "assistant-otter-sheet"
internal const val ThreadAssistantPanelTestTag = "assistant-otter-panel"
internal const val ThreadAssistantPanelHeaderTestTag = "assistant-otter-panel-header"
internal const val ThreadAssistantPanelCollapseTestTag = "assistant-otter-collapse"
internal const val ThreadAssistantPanelPausedTestTag = "assistant-paused-chip"
