package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkResult
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.artifactWebUrl
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant

/** The card open in one message: its link and where its chip was when it was tapped. */
internal data class ThreadAppLinkOpenCard(val url: String, val anchor: RaftPopoverAnchor)

/**
 * One message's App link cards: each chip that opens a card marks its leading mark as the
 * card's anchor ([anchor], live root pixels, `docs/kuikly-overlay-coordinate-contract.md`), and
 * a tap opens the card there ([open]). A chip whose anchor can't be resolved (detached) opens
 * its link, as a chip without a card does.
 */
internal class ThreadAppLinkCards(
    private val messageId: String,
    private val openLink: (String) -> Unit,
) {
    private val refs = mutableMapOf<String, RaftLiveRootAnchorRef>()

    var opened by mutableStateOf<ThreadAppLinkOpenCard?>(null)
        private set

    fun anchor(url: String): Modifier = Modifier.raftCaptureLiveRootAnchor(ref(url))

    fun resolve(url: String): RaftPopoverAnchor? = ref(url).resolve(RaftPopoverLocalPoint.BottomStart)

    fun open(card: AppLinkResult.Card) {
        val anchor = resolve(card.url)
        if (anchor == null) {
            openLink(card.url)
            return
        }
        opened = ThreadAppLinkOpenCard(card.url, anchor)
    }

    /** An Artifact chip's tap: its item card by the chip, or its page ([webUrl]) when the chip is detached. */
    fun openArtifact(target: ArtifactLinkTarget, webUrl: String) {
        val key = threadArtifactCardKey(target.key)
        val anchor = resolve(key)
        if (anchor == null) {
            openLink(webUrl)
            return
        }
        opened = ThreadAppLinkOpenCard(key, anchor)
    }

    fun close() {
        opened = null
    }

    private fun ref(url: String): RaftLiveRootAnchorRef = refs.getOrPut(url) { RaftLiveRootAnchorRef("app-link-card:$messageId:$url") }
}

@Composable
internal fun rememberThreadAppLinkCards(messageId: String): ThreadAppLinkCards {
    val uriHandler = LocalUriHandler.current
    val openLink by rememberUpdatedState<(String) -> Unit> { uriHandler.openUri(it) }
    return remember(messageId) { ThreadAppLinkCards(messageId) { openLink(it) } }
}

/**
 * Screenshot fixtures: opens [openUrl]'s card once its chip is laid out and hands it to the
 * fixture ([shown]), which draws [ThreadAppLinkCardPopover] in the page (a window can't be
 * captured with the page under it). Never provided in the app.
 */
internal class ThreadAppLinkCardFixture(val openUrl: String) {
    var shown by mutableStateOf<Pair<AppLinkResult.Card, RaftPopoverAnchor>?>(null)
}

internal val LocalThreadAppLinkCardFixture = staticCompositionLocalOf<ThreadAppLinkCardFixture?> { null }

/**
 * The card [cards] has open, while the message still has it (a card read again without its
 * body, or a link no longer previewed, closes it), in its own window over the thread.
 */
@Composable
internal fun ThreadAppLinkCardPopoverHost(
    results: Map<String, AppLinkResult>,
    cards: ThreadAppLinkCards,
    artifacts: Map<String, ArtifactSummary> = emptyMap(),
    artifactWebBaseUrl: String = "",
) {
    val fixture = LocalThreadAppLinkCardFixture.current
    val openedArtifact = cards.opened?.url?.let(::threadArtifactRefKeyOrNull)
    if (fixture == null && openedArtifact != null) {
        val opened = cards.opened ?: return
        val summary = artifacts[openedArtifact]
        LaunchedEffect(opened, summary == null) {
            if (summary == null) cards.close()
        }
        if (summary == null) return
        val uriHandler = LocalUriHandler.current
        val target = ArtifactLinkTarget(summary.id, openedArtifact.substringAfter(";version=", "").toIntOrNull())
        Modal(onWillDismiss = { cards.close() }) {
            RaftDesignSystemTheme {
                ThreadAppLinkCardPopoverFrame(
                    key = opened.url,
                    anchor = opened.anchor,
                    onDismiss = cards::close,
                ) { modifier ->
                    RaftArtifactLinkCardView(
                        summary = summary,
                        modifier = modifier,
                        actions = {
                            RaftButton(
                                text = stringResource(Res.string.thread_link_preview_open_link),
                                onClick = {
                                    cards.close()
                                    uriHandler.openUri(artifactWebUrl(artifactWebBaseUrl, target))
                                },
                                variant = RaftButtonVariant.Default,
                            )
                        },
                    )
                }
            }
        }
        return
    }
    if (fixture != null) {
        val card = (results[fixture.openUrl] as? AppLinkResult.Card)?.takeIf { it.card != null }
        LaunchedEffect(card) {
            if (card == null) return@LaunchedEffect
            // Two frames: the chip's inline content is placed after the text is laid out.
            withFrameNanos {}
            withFrameNanos {}
            cards.resolve(card.url)?.let { fixture.shown = card to it }
        }
        return
    }
    val opened = cards.opened ?: return
    val card = (results[opened.url] as? AppLinkResult.Card)?.takeIf { it.card != null }
    LaunchedEffect(opened, card == null) {
        if (card == null) cards.close()
    }
    if (card == null) return
    val uriHandler = LocalUriHandler.current
    Modal(onWillDismiss = { cards.close() }) {
        RaftDesignSystemTheme {
            ThreadAppLinkCardPopover(
                card = card,
                anchor = opened.anchor,
                onDismiss = cards::close,
                onOpenLink = {
                    cards.close()
                    uriHandler.openUri(card.url)
                },
            )
        }
    }
}

/**
 * An App link's item card by its chip (web `AppLinkChip` `PreviewCardContent`: 300 wide, start
 * aligned with the chip, 6 below it, or above it when it doesn't fit below; artin chose a
 * popover on the phone, #proj-raft-ui). [RaftAppLinkCardView] with Open Link in
 * PreviewCardActions (an outline, ink-bordered button, never pink). Taller than the window, its
 * body scrolls and its footer, where the link goes, stays. A tap outside or Back closes it.
 * v1 cards have no buttons of their own: an action is only logged.
 */
@Composable
internal fun ThreadAppLinkCardPopover(
    card: AppLinkResult.Card,
    anchor: RaftPopoverAnchor,
    onDismiss: () -> Unit,
    onOpenLink: () -> Unit,
) {
    ThreadAppLinkCardPopoverFrame(key = card.url, anchor = anchor, onDismiss = onDismiss) { modifier ->
        RaftAppLinkCardView(
            card = card,
            onAction = { elementId -> raftLogInfo(ThreadAppLinkCardLogTag, "app_link_card_action_ignored element=$elementId") },
            modifier = modifier,
            actions = {
                RaftButton(
                    text = stringResource(Res.string.thread_link_preview_open_link),
                    onClick = onOpenLink,
                    variant = RaftButtonVariant.Default,
                )
            },
        )
    }
}

/**
 * The item card's popover frame by its chip (see [ThreadAppLinkCardPopover]); [content] draws the
 * card with the modifier that sizes, measures and shields it.
 */
@Composable
internal fun ThreadAppLinkCardPopoverFrame(
    key: String,
    anchor: RaftPopoverAnchor,
    onDismiss: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = ThreadAppLinkCardViewportGap
        val width = min(ThreadAppLinkCardWidth, maxWidth - gap * 2)
        val maxHeight = (maxHeight - gap * 2).coerceAtLeast(0.dp)
        var measured by remember(key) { mutableStateOf(Size.Zero) }
        val contentSize = if (measured != Size.Zero) measured else with(density) { Size(width.toPx(), 1f) }
        RaftAnchoredPopover(
            anchor = anchor,
            contentSize = contentSize,
            onDismiss = onDismiss,
            viewportGap = with(density) { gap.toPx() },
            horizontalOffset = with(density) { -ThreadAppLinkChipStartInset.toPx() },
            verticalOffset = with(density) { ThreadAppLinkCardBelowMark.toPx() },
            verticalOffsetAbove = with(density) { ThreadAppLinkCardAboveMark.toPx() },
            verticalPlacement = RaftFloatingVerticalPlacement.Auto,
            dismissActivationDelayMillis = 0L,
        ) {
            content(
                Modifier
                    .width(width)
                    .heightIn(max = maxHeight)
                    .onSizeChanged { measured = Size(it.width.toFloat(), it.height.toFloat()) }
                    // Placed once measured, so it never shows at a guessed height.
                    .graphicsLayer { alpha = if (measured == Size.Zero) 0f else 1f }
                    // A tap on the card is not a tap outside it.
                    .raftNativeDispatchCapture()
                    .pointerInput(Unit) { detectTapGestures {} },
            )
        }
    }
}

/** The open-card key of an Artifact chip (by [ArtifactLinkTarget.key]); never an http(s) URL. */
internal fun threadArtifactCardKey(refKey: String): String = "artifact:$refKey"

internal fun threadArtifactRefKeyOrNull(openKey: String): String? = openKey.takeIf { it.startsWith("artifact:") }?.removePrefix("artifact:")

/** raft-ui PreviewCardContent: 300px. */
private val ThreadAppLinkCardWidth = 300.dp
private val ThreadAppLinkCardViewportGap = 8.dp

/** The chip starts this far before its leading mark (the inline chip's start padding). */
private val ThreadAppLinkChipStartInset = 4.5.dp

/**
 * From the bottom of the chip's 14dp mark: the chip (about 22dp, the mark centred) ends 4dp
 * lower and starts 18dp higher; the card keeps raft-ui's `sideOffset` 6 from it.
 */
private val ThreadAppLinkCardBelowMark = 10.dp
private val ThreadAppLinkCardAboveMark = 24.dp

private const val ThreadAppLinkCardLogTag = "RaftAppLinkCard"
