package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.app.components.RaftUiMenuItem
import build.raft.app.components.RaftUiMenuSurface
import build.raft.app.components.raftMenuWidth
import build.raft.app.components.RaftMetrics
import build.raft.app.base.Utils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import build.raft.app.compat.ui.imageResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import build.raft.designsystem.components.RaftMessageItemToolbarDefaults
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toIntSize

private object ThreadReactionGlyphDiagnostics {
    private val loggedKeys = mutableSetOf<String>()

    fun logOnce(path: String, emoji: String, detail: String) {
        val key = "$path:$emoji:$detail"
        if (loggedKeys.add(key)) {
            Utils.logToNative("thread_reaction_glyph path=$path emoji=$emoji $detail")
        }
    }

    // task #379: one-shot readiness signal for the visual-testing capture harness.
    // The reaction sprite atlas decodes asynchronously; in the headless capture
    // environment the snapshot can be taken before it finishes, so every reaction
    // renders as a raw-emoji fallback. Emitting this line once (when the hoisted
    // atlas ImageBitmap first becomes non-null) gives the capture side a logcat
    // token to wait on before snapshotting reaction-bearing cases.
    fun logAtlasReadyOnce(width: Int, height: Int) {
        if (loggedKeys.add("thread_reaction_atlas_ready")) {
            Utils.logToNative("thread_reaction_atlas_ready imageWidth=$width imageHeight=$height")
        }
    }
}

@Composable
internal fun ThreadReactionChip(
    reaction: RaftThreadReactionItem,
    interactionIdentity: Any = "reaction:${reaction.emoji}",
    onClick: (() -> Unit)? = null,
    showReactors: Boolean = false
) {
    // raft-ui MessageReactionChip (gap 6): the design-system chip draws; the gestures stay here.
    val hoverInteractionSource = remember(interactionIdentity) { MutableInteractionSource() }
    val chipContent: @Composable (onChipClick: (() -> Unit)?) -> Unit = { onChipClick ->
        build.raft.designsystem.components.RaftMessageReaction(
            count = reaction.count,
            mine = reaction.selected,
            glyph = {
                ThreadQuickReactionGlyph(
                    threadDisplayReactionEmoji(reaction.emoji),
                    modifier = Modifier.size(build.raft.designsystem.components.RaftMessageReactionGlyphSize)
                )
            },
            onClick = onChipClick,
            interactionSource = hoverInteractionSource
        )
    }
    if (showReactors) {
        // task #569: tap keeps toggling the reaction; a long press (or a mouse hover) shows who
        // reacted in raft-ui's reaction tooltip under the chip, as web MessageItem's hover tooltip.
        // Both live on one gesture detector so the long press cannot fire a toggle.
        var tooltipOpen by remember(interactionIdentity) { mutableStateOf(false) }
        val reactors =
            threadReactionTooltipNames(
                reaction = reaction,
                // The session's user id (the action-card host already carries it; no new capture).
                viewerUserId = LocalThreadActionCardSpecHost.current.viewerUserId,
                youLabel = stringResource(Res.string.thread_reaction_reactor_you)
            )
        val gestureOwner =
            rememberRaftGestureOwner(
                surface = "thread-reaction-chip",
                semanticIdentity = interactionIdentity,
                callbacks = ThreadReactionChipGestureCallbacks(onClick) { tooltipOpen = true }
            )
        build.raft.designsystem.components.RaftMessageReactionTooltip(
            names = reactors.visibleNames,
            moreLabel =
                reactors.hiddenCount.takeIf { it > 0 }?.let { hidden ->
                    stringResource(Res.string.thread_reaction_hidden_more, hidden)
                },
            open = tooltipOpen,
            onDismissRequest = { tooltipOpen = false },
            emptyContent = {
                // web: the bare emoji when no reactor is known.
                ThreadQuickReactionGlyph(
                    threadDisplayReactionEmoji(reaction.emoji),
                    modifier = Modifier.size(build.raft.designsystem.components.RaftMessageReactionGlyphSize)
                )
            }
        ) {
            Box(
                modifier = Modifier
                    .raftGesturePointerInput(gestureOwner, onClick != null) { owner ->
                        var gesture: RaftGestureSession<ThreadReactionChipGestureCallbacks>? = null
                        // raw-click: gesture-owner chip (tap toggles, long press lists who reacted, on one
                        // detector that survives lazy-slot reuse); raftClickable can't hold the owner token.
                        // The design-system chip still draws hover and press from hoverInteractionSource.
                        detectTapGestures(
                            onPress = { offset ->
                                gesture = owner.beginGesture()
                                // The chip's press (web MessageReactionChip `:active`): into the source the
                                // design-system chip draws from, as its own toggle would; hover is already its.
                                val press = PressInteraction.Press(offset)
                                hoverInteractionSource.tryEmit(press)
                                var released = false
                                try {
                                    released = tryAwaitRelease()
                                } finally {
                                    hoverInteractionSource.tryEmit(
                                        if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press)
                                    )
                                }
                            },
                            onTap = {
                                (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onClick?.invoke()
                            },
                            onLongPress = {
                                (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onLongPress?.invoke()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                chipContent(null)
            }
        }
    } else if (onClick != null) {
        // The design-system chip owns the tap (a toggle, with its hover / press overlay).
        chipContent(onClick)
    } else {
        chipContent(null)
    }
}

private data class ThreadReactionChipGestureCallbacks(
    val onClick: (() -> Unit)?,
    val onLongPress: () -> Unit
)

// raft-ui MessageReactionAddButton (gap 6): the design-system chip draws and owns the tap.
@Composable
internal fun ThreadReactionAddChip(
    messageId: String,
    onClick: (RaftPopoverAnchor) -> Unit
) {
    val addReactionDescription = stringResource(Res.string.thread_action_add_reaction)
    val currentOnClick by rememberUpdatedState(onClick)
    // The picker / action menu anchors where the finger landed, owned by the message's menu
    // identity (the same contract as the message long-press menu).
    val anchorIdentity = threadMessageMenuOwnerIdentity(messageId)
    val rootAnchor = remember(anchorIdentity) { RaftLiveRootAnchorRef(identity = anchorIdentity) }
    val pressPoint = remember(anchorIdentity) { arrayOfNulls<Offset>(1) }
    build.raft.designsystem.components.RaftMessageReactionAdd(
        label = addReactionDescription,
        modifier =
            Modifier
                .raftCaptureLiveRootAnchor(rootAnchor)
                // Observes (never consumes) the down so the design-system chip keeps the tap.
                .pointerInput(rootAnchor) {
                    awaitEachGesture {
                        pressPoint[0] =
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
                    }
                },
        onClick = {
            // A keyboard / accessibility click has no finger point: anchor under the chip.
            val localPoint =
                pressPoint[0]?.let { RaftPopoverLocalPoint.Exact(it) } ?: RaftPopoverLocalPoint.BottomStart
            pressPoint[0] = null
            rootAnchor.resolve(localPoint)?.let(currentOnClick)
        }
    )
}

private val ThreadMessageMenuReactionGlyphSize = 22.dp
/** The quick reaction bar's distance from the finger that double tapped (web: 24px). */
internal val ThreadMessageQuickReactionBarFingerGap = 24.dp

internal fun threadMessageQuickReactionBarHeightDp(): Float =
    ThreadMessageMenuReactionRowHeight.value + RaftMetrics.PopupPadding.value * 2

/**
 * The quick reaction bar a double tap shows (stdrc, 2026-10-29, #proj-raft-ui:911ccc08): the
 * long-press menu's popup and quick reaction row ([ThreadMessageReactionRow], same emojis and
 * glyphs) without its action rows, above the second tap (below when there is no room), and
 * without the menu row's trailing Add Reaction (web's hover reaction strip has none). It also
 * opens from the "+" beside a message's reactions (web `handleMobileReactionAddClick`). Picking an
 * emoji toggles that reaction and closes the bar; a tap outside closes it.
 */
@Composable
internal fun ThreadMessageQuickReactionBar(
    messageId: String,
    anchor: RaftPopoverAnchor,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit = {},
    fromHoverToolbar: Boolean = false
) {
    Modal(onWillDismiss = { onDismiss() }) {
        ThreadMessageQuickReactionBarPopover(messageId, anchor, onDismiss, onReact, diagnosticsLogger, fromHoverToolbar)
    }
}

/**
 * [ThreadMessageQuickReactionBar] without its window: the popover over the page (screenshot
 * tests draw it in the page, as a window can't be captured with the page under it).
 */
@Composable
internal fun ThreadMessageQuickReactionBarPopover(
    messageId: String,
    anchor: RaftPopoverAnchor,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit = {},
    /** [anchor] is the bottom end of the hover toolbar's Add Reaction (web `positionReactionPickerFromRect`). */
    fromHoverToolbar: Boolean = false
) {
    val density = LocalDensity.current
    val barWidthDp = threadMessageMenuWidthDp(showReactionActions = true)
    val contentSize =
        with(density) {
            Size(
                width = barWidthDp.dp.toPx(),
                height = threadMessageQuickReactionBarHeightDp().dp.toPx()
            )
        }
    val viewportGapPx = with(density) { ThreadMessageMenuViewportGap.toPx() }
    val fingerGapPx = with(density) { ThreadMessageQuickReactionBarFingerGap.toPx() }
    val toolbarGapPx = with(density) { ThreadMessageHoverToolbarPopupGap.toPx() }
    val toolbarButtonPx = with(density) { RaftMessageItemToolbarDefaults.ButtonSize.toPx() }
    // Like the mobile web (slock #11851): above the second tap, 24dp clear of the finger, so it
    // covers neither the line that was tapped nor the finger; below only when there is no room
    // above. Centred on the finger and kept on screen. From the hover toolbar, like the web's
    // picker: 4dp under the button with its end on the button's end, above it when there is no
    // room below.
    RaftAnchoredPopover(
        anchor = anchor,
        contentSize = contentSize,
        onDismiss = onDismiss,
        viewportGap = viewportGapPx,
        horizontalOffset = if (fromHoverToolbar) -contentSize.width else -contentSize.width / 2f,
        verticalOffset = if (fromHoverToolbar) toolbarGapPx else fingerGapPx,
        verticalOffsetAbove = if (fromHoverToolbar) toolbarButtonPx + toolbarGapPx else fingerGapPx,
        verticalPlacement =
            if (fromHoverToolbar) RaftFloatingVerticalPlacement.Auto else RaftFloatingVerticalPlacement.AbovePreferred,
        horizontalPlacement = RaftFloatingHorizontalPlacement.Anchor
    ) {
        LaunchedEffect(messageId, anchor) {
            diagnosticsLogger("thread_message_quick_reaction_bar_positioned messageId=$messageId")
        }
        RaftMenuSurface(
            modifier = Modifier.raftFloatingContentClickTarget(),
            width = raftMenuWidth(barWidthDp.dp)
        ) {
            ThreadMessageReactionRow(
                reactionAtlasImage = LocalThreadReactionAtlasImage.current,
                onReact = onReact
            )
        }
    }
}

@Composable
internal fun ThreadMessageMenuChrome(
    actions: List<ThreadMessageMenuAction>,
    showReactionActions: Boolean,
    reactionAtlasImage: ImageBitmap?,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Ends the reaction header with Add Reaction (web `reaction-add-any`); null: no such button. */
    onAddReaction: (() -> Unit)? = null,
    /** A long press on a link opened the menu: that link's section leads the header (ThreadMessageLinkMenu.kt). */
    link: ThreadMessageLinkMenuSection? = null
) {
    // task #11 (artin: 统一两套菜单): the action rows render through the shared
    // design-system menu surface ([RaftUiMenuSurface]) — press wash, row metrics and icon specs
    // come from the one component instead of a hand-rolled Button stack. The
    // reaction bar rides in the surface's header slot; group boundaries map to
    // dividerBefore.
    val menuItems = actions.mapIndexed { index, action ->
        RaftUiMenuItem(
            id = index.toString(),
            label = action.label.localized(),
            icon = action.icon,
            enabled = action.enabled,
            dividerBefore = index > 0 && actions.getOrNull(index - 1)?.group != action.group,
            description = action.description?.localized(),
            trailingLoading = action.trailingLoading,
            dismissOnSelect = action.dismissOnSelect,
            testTag = action.testTag,
        )
    }
    val baseWidth = threadMessageMenuWidthDp(showReactionActions, showAddReactionAction = onAddReaction != null).dp
    // Keep a fixed width when a truncated link URL or wrapping item description is present;
    // otherwise grow from baseWidth so longer localized labels stay on one line up to viewport width.
    val pinWidth = link != null || menuItems.any { it.description != null }
    RaftUiMenuSurface(
        items = menuItems,
        onSelect = { id -> actions.getOrNull(id.toIntOrNull() ?: -1)?.let { if (it.enabled) it.onClick() } },
        modifier = modifier,
        minWidth = baseWidth,
        width = if (pinWidth) baseWidth else null,
        header =
            if (showReactionActions || link != null) {
                {
                    Column(Modifier.fillMaxWidth()) {
                        if (link != null) {
                            ThreadMessageLinkMenuRows(link)
                            if (showReactionActions) RaftMenuSeparator()
                        }
                        if (showReactionActions) {
                            ThreadMessageReactionRow(
                                reactionAtlasImage = reactionAtlasImage,
                                onReact = onReact,
                                glyphSize = ThreadMessageMenuReactionGlyphSize,
                                onAddReaction = onAddReaction
                            )
                        }
                    }
                }
            } else {
                null
            }
    )
}

@Composable
internal fun ThreadMessageReactionRow(
    reactionAtlasImage: ImageBitmap?,
    onReact: (String) -> Unit,
    rowHeight: Dp = ThreadMessageMenuReactionRowHeight,
    buttonSize: Dp = ThreadMessageMenuReactionButtonSize,
    glyphSize: Dp = 18.dp,
    horizontalPadding: Dp = ThreadMessageMenuReactionPaddingHorizontal,
    enabled: Boolean = true,
    // The context menu packs web's `gap-1`; the wider compact menu spreads the buttons.
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(ThreadMessageMenuReactionGap),
    /**
     * Web's `reaction-add-any` (a SmilePlus "Add Reaction" after the quick emojis): opens the full
     * emoji picker where the menu was. Null: no such button (the double-tap quick reaction bar,
     * which mirrors web's hover strip, has none).
     */
    onAddReaction: (() -> Unit)? = null
) {
    // web `reaction-quick-row` (raft-ui MessageReactionQuickRow): `gap-0.5 px-0.75`, `size-9` buttons.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement
    ) {
        ThreadQuickReactionEmojis.forEach { reaction ->
            RaftPanelAction(
                onClick = { onReact(reaction) },
                enabled = enabled,
                modifier = Modifier
                    .alpha(if (enabled) 1f else 0.45f)
                    .size(buttonSize)
            ) {
                ThreadQuickReactionGlyph(
                    reaction,
                    modifier = Modifier.size(glyphSize),
                    reactionAtlasImage = reactionAtlasImage
                )
            }
        }
        if (onAddReaction != null) {
            val addReactionLabel = stringResource(Res.string.thread_action_add_reaction)
            RaftPanelAction(
                onClick = onAddReaction,
                enabled = enabled,
                modifier = Modifier
                    .alpha(if (enabled) 1f else 0.45f)
                    .size(buttonSize)
                    .semantics { contentDescription = addReactionLabel }
            ) {
                // web: lucide SmilePlus 18, the button's muted foreground.
                RaftLucideIcon(
                    name = RaftLucideIconName.SmilePlus,
                    modifier = Modifier.size(ThreadMessageReactionAddIconSize),
                    color = RaftColor.TextMutedGrayArgb
                )
            }
        }
    }
}

private val ThreadMessageReactionAddIconSize = 18.dp

private fun threadDisplayReactionEmoji(emoji: String): String =
    emoji

// task #376 (Hands 389d855b): the decoded reaction sprite atlas is hoisted to
// a stable ThreadPage mount point and provided here so glyphs consume the ready
// ImageBitmap instead of each starting their own async `imageResource` load. A
// null value means "not decoded yet" (temporary) and updates to the ready bitmap
// via recomposition once the long-lived root finishes decoding — it must never
// harden into a permanent raw-emoji fallback.
internal val LocalThreadReactionAtlasImage = compositionLocalOf<ImageBitmap?> { null }

/** Whether `feedback:submit` cards open the native feedback form ([raftFeedbackCardEntryEnabled]). */
internal val LocalThreadFeedbackCardEntryEnabled = compositionLocalOf { false }

/**
 * The server the thread is viewed in: an action card prepared on another server (a joint
 * channel's peer) is read-only here (web `isActionCardReadOnlyInServer`). Null when unknown.
 */
internal val LocalThreadViewerServerId = compositionLocalOf<String?> { null }

/**
 * Public entry point so the Android host stack root ([build.raft.app.shared.RaftAndroidNav3StackContent])
 * can hoist and share one decoded reaction sprite atlas across all pushed conversations without
 * running bitmap decode inside low-level navigation tests.
 */
@Composable
fun RaftReactionAtlasProvider(content: @Composable () -> Unit) {
    ThreadReactionAtlasProvider(content)
}

@Composable
internal fun ThreadReactionAtlasProvider(content: @Composable () -> Unit) {
    // task #376/#380: decode the reaction sprite atlas once at a stable root and
    // provide the ready ImageBitmap down so both Thread content and standalone
    // visual fixtures resolve glyphs from the shared bitmap instead of each
    // starting a short-lived async `imageResource` load. The visual fixtures also
    // emit task #379's ready token so the capture harness can wait for the atlas
    // before snapshotting reaction-bearing cases.
    // task #421: the atlas DrawableResource is null on iOS (asset not bundled),
    // so only load it where available; otherwise provide a null atlas and let
    // reaction rendering fall back to emoji glyphs.
    // An atlas an ancestor already holds ready (a host that decoded it, or an outer provider) is
    // reused as is: no second async load that may or may not have finished.
    // [content] has one call site whichever atlas is used: an ancestor's atlas turning ready (or
    // going away) must not rebuild the content, e.g. a thread message list under a LazyListState
    // that outlives it (Hands 9e3ca082).
    val inheritedAtlas = LocalThreadReactionAtlasImage.current
    val atlasDrawable = if (inheritedAtlas == null) ThreadReactionSpriteAtlasDrawableResource else null
    val ownAtlasImage = if (atlasDrawable != null) imageResource(atlasDrawable) else null
    val ownReadyAtlas = ownAtlasImage?.takeIf { it.width > 0 && it.height > 0 }
    if (ownReadyAtlas != null) {
        SideEffect {
            ThreadReactionGlyphDiagnostics.logAtlasReadyOnce(
                ownReadyAtlas.width,
                ownReadyAtlas.height
            )
        }
    }
    CompositionLocalProvider(LocalThreadReactionAtlasImage provides (inheritedAtlas ?: ownReadyAtlas)) {
        content()
    }
}

@Composable
internal fun ThreadQuickReactionGlyph(
    emoji: String,
    modifier: Modifier = Modifier,
    reactionAtlasImage: ImageBitmap? = null
) {
    // task #421 / #423: use the sprite path only when the atlas bitmap has
    // actually decoded. The atlas file resolves on every platform (so
    // `spriteAtlasAvailable` is true there), but the atlas ImageBitmap may
    // still fail to decode in some environments — gating on mere bundle
    // availability then drew a *blank* atlas rect instead of falling back to the
    // emoji glyph. Gate on the decoded image (the same value the sprite glyph
    // draws), so an undecoded atlas falls through to the emoji, never blank.
    val atlasImage = reactionAtlasImage ?: LocalThreadReactionAtlasImage.current
    val sprite = if (atlasImage != null) ThreadReactionSpriteCatalogV1.resolve(emoji) else null
    if (sprite != null) {
        ThreadReactionSpriteGlyph(
            sprite = sprite,
            modifier = modifier,
            reactionAtlasImage = atlasImage
        )
        return
    }
    ThreadReactionGlyphDiagnosticLog(
        path = "legacy_no_sprite",
        emoji = emoji,
        detail = if (atlasImage == null) "reason=atlas_not_decoded" else "reason=no_manifest_match"
    )
    ThreadTextReactionGlyph(emoji = emoji, modifier = modifier)
}

@Composable
private fun ThreadReactionSpriteGlyph(
    sprite: ThreadReactionSprite,
    modifier: Modifier = Modifier,
    reactionAtlasImage: ImageBitmap? = null
) {
    // task #376 (Hands 389d855b): consume the atlas image hoisted to the
    // ThreadPage root via LocalThreadReactionAtlasImage instead of starting a
    // fresh async `imageResource` load here. In a short-lived composition
    // subtree (the long-press action-menu overlay) a per-glyph `imageResource`
    // returned a 0x0 first frame and stuck on the raw-emoji fallback; reading
    // the provided ready bitmap avoids that timing hole. A null value here is
    // only a transient "not decoded yet" state — the root keeps the atlas
    // mounted and this consumer recomposes to the sprite once it is ready.
    val image = reactionAtlasImage ?: LocalThreadReactionAtlasImage.current
    if (image == null) {
        ThreadReactionGlyphDiagnosticLog(
            path = "sprite_atlas_not_ready",
            emoji = sprite.value,
            detail = "sprite=${sprite.name} imageWidth=0 imageHeight=0"
        )
        Canvas(modifier = modifier) {}
        return
    }
    ThreadReactionGlyphDiagnosticLog(
        path = "sprite_atlas_draw",
        emoji = sprite.value,
        detail = "sprite=${sprite.name} atlas=${ThreadReactionSpriteCatalogV1.atlas.imageName}"
    )
    val rect = sprite.rect
    Canvas(modifier = modifier) {
        drawImage(
            image = image,
            srcOffset = IntOffset(rect.x, rect.y),
            srcSize = IntSize(rect.width, rect.height),
            dstOffset = IntOffset.Zero,
            dstSize = size.toIntSize()
        )
    }
}

@Composable
private fun ThreadReactionGlyphDiagnosticLog(
    path: String,
    emoji: String,
    detail: String
) {
    SideEffect {
        ThreadReactionGlyphDiagnostics.logOnce(path = path, emoji = emoji, detail = detail)
    }
}

@Composable
private fun ThreadTextReactionGlyph(
    emoji: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = emoji,
            style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(
                fontSize = if (emoji.length > 1) 10.sp else 13.sp,
                lineHeight = 12.sp
            ),
            color = RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Visible
        )
    }
}
