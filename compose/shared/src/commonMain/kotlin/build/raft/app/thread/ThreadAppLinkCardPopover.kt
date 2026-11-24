package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkResult
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftPreviewCardDetail

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
    val preferredServerId: String?,
    val openLink: (String) -> Unit,
    val openAppEntry: (String, RaftAppEntryRequest) -> Unit,
    val openInSystemBrowser: (String) -> Unit,
    val lookupInstalledApp: ((origin: String) -> RaftInstalledAppSyncLookup)? = null,
    val resolveInstalledAppAsync: (
        (
            address: String,
            origin: String,
            onMatched: (RaftInstalledAppOriginMatch) -> Unit,
            onUnmatched: () -> Unit,
        ) -> Boolean
    )? = null,
) {
    private val refs = mutableMapOf<String, RaftLiveRootAnchorRef>()

    var opened by mutableStateOf<ThreadAppLinkOpenCard?>(null)
        private set

    /**
     * The links whose card was opened in this message, in that order: each keeps the pressing of
     * its buttons ([ThreadAppLinkCardPress]) while the message is on screen, so closing the card
     * (or opening another) never drops an action on its way or an Undo window.
     */
    private val openedUrls = mutableStateListOf<String>()
    val pressed: List<String> get() = openedUrls

    fun anchor(url: String): Modifier = Modifier.raftCaptureLiveRootAnchor(ref(url))

    fun resolve(url: String): RaftPopoverAnchor? = ref(url).resolve(RaftPopoverLocalPoint.BottomStart)

    fun open(card: AppLinkResult.Card) {
        val anchor = resolve(card.url)
        if (anchor == null) {
            openThreadAppLinkAddress(
                address = card.connectUrl ?: card.url,
                card = card,
                preferredServerId = preferredServerId,
                openLink = openLink,
                openAppEntry = openAppEntry,
                openInSystemBrowser = openInSystemBrowser,
                lookupInstalledApp = lookupInstalledApp,
                resolveInstalledAppAsync = resolveInstalledAppAsync,
            )
            return
        }
        if (card.url !in openedUrls) openedUrls += card.url
        opened = ThreadAppLinkOpenCard(card.url, anchor)
    }

    fun close() {
        opened = null
    }

    private fun ref(url: String): RaftLiveRootAnchorRef = refs.getOrPut(url) { RaftLiveRootAnchorRef("app-link-card:$messageId:$url") }
}

@Composable
internal fun rememberThreadAppLinkCards(
    messageId: String,
    preferredServerId: String? = null,
    onOpenLink: (String) -> Unit,
    onOpenAppEntry: (String, RaftAppEntryRequest) -> Unit = { url, _ -> onOpenLink(url) },
    onOpenInSystemBrowser: (String) -> Unit = rememberRaftSystemBrowserOpener(),
    lookupInstalledApp: ((origin: String) -> RaftInstalledAppSyncLookup)? = null,
    resolveInstalledAppAsync: (
        (
            address: String,
            origin: String,
            onMatched: (RaftInstalledAppOriginMatch) -> Unit,
            onUnmatched: () -> Unit,
        ) -> Boolean
    )? = null,
): ThreadAppLinkCards {
    val openLink by rememberUpdatedState(onOpenLink)
    val openAppEntry by rememberUpdatedState(onOpenAppEntry)
    val openInSystemBrowser by rememberUpdatedState(onOpenInSystemBrowser)
    val currentLookupInstalledApp by rememberUpdatedState(lookupInstalledApp)
    val currentResolveInstalledAppAsync by rememberUpdatedState(resolveInstalledAppAsync)
    return remember(messageId, preferredServerId) {
        ThreadAppLinkCards(
            messageId = messageId,
            preferredServerId = preferredServerId,
            openLink = { openLink(it) },
            openAppEntry = { url, request -> openAppEntry(url, request) },
            openInSystemBrowser = { openInSystemBrowser(it) },
            lookupInstalledApp = { origin ->
                currentLookupInstalledApp?.invoke(origin) ?: RaftInstalledAppSyncLookup.KnownNotInstalled
            },
            resolveInstalledAppAsync = { address, origin, onMatched, onUnmatched ->
                currentResolveInstalledAppAsync?.invoke(address, origin, onMatched, onUnmatched) == true
            },
        )
    }
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
 *
 * [actions] runs the card's buttons as a block card's are run ([ThreadAppLinkCardPress]: Raft's
 * confirmation or an Undo window for an action, Refresh reads the card again); null (a message
 * read without the batched read): the App's links only.
 */
@Composable
internal fun ThreadAppLinkCardPopoverHost(
    results: Map<String, AppLinkResult>,
    cards: ThreadAppLinkCards,
    actions: ThreadAppLinkCardActionHost? = null,
) {
    val fixture = LocalThreadAppLinkCardFixture.current
    // Before every return below: the presses live with the message row, not with the open card.
    val presses = rememberThreadAppLinkChipCardPresses(results, cards, actions)
    if (fixture != null) {
        val card = (results[fixture.openUrl] as? AppLinkResult.Card)?.takeIf { it.hasCardBody }
        LaunchedEffect(card) {
            if (card == null) return@LaunchedEffect
            // Two frames: the chip's inline content is placed after the text is laid out.
            withFrameNanos {}
            withFrameNanos {}
            cards.resolve(card.url)?.let { fixture.shown = card to it }
        }
        return
    }
    val opened = cards.opened
    val card = opened?.let { (results[it.url] as? AppLinkResult.Card)?.takeIf { shown -> shown.hasCardBody } }
    // A confirmation asked from a card that was closed meanwhile is still asked, over the thread.
    for ((url, press) in presses) {
        if (url != card?.url && press.confirmation != null) {
            key(url) { RaftDesignSystemTheme { press.Confirmation() } }
        }
    }
    if (opened == null) return
    LaunchedEffect(opened, card == null) {
        if (card == null) cards.close()
    }
    if (card == null) return
    val openInSystemBrowser = rememberRaftSystemBrowserOpener()
    val press = presses[card.url]
    // A link of the card (the App's, or Raft's Open Link) closes the card and opens its address.
    val openAddress: (String) -> Unit = { address ->
        cards.close()
        openThreadAppLinkAddress(
            address = address,
            card = card,
            preferredServerId = cards.preferredServerId,
            openLink = cards.openLink,
            openAppEntry = cards.openAppEntry,
            openInSystemBrowser = openInSystemBrowser,
            lookupInstalledApp = cards.lookupInstalledApp,
            resolveInstalledAppAsync = cards.resolveInstalledAppAsync,
        )
    }
    Modal(onWillDismiss = { cards.close() }) {
        RaftDesignSystemTheme {
            ThreadAppLinkCardPopover(
                card = card,
                anchor = opened.anchor,
                onDismiss = cards::close,
                onOpenLink = { openAddress(card.connectUrl ?: card.url) },
                buttons = { openLink ->
                    card.chipCardButtons(
                        openUri = openAddress,
                        openLink = openLink,
                        read = { button -> press?.readButton(button) },
                    ) { button, action -> press?.button(button, action) }
                },
            )
            // In the card's window, so Raft's confirmation opens over the card.
            press?.Confirmation()
        }
    }
}

/**
 * The pressing of the buttons of each chip card opened in this message ([ThreadAppLinkCards.pressed]),
 * by link: only a card with the App's v2 tree has buttons to press (a block card keeps its own
 * press, [ThreadAppLinkBlockCard]).
 */
@Composable
private fun rememberThreadAppLinkChipCardPresses(
    results: Map<String, AppLinkResult>,
    cards: ThreadAppLinkCards,
    actions: ThreadAppLinkCardActionHost?,
): Map<String, ThreadAppLinkCardPress> {
    val presses = LinkedHashMap<String, ThreadAppLinkCardPress>()
    for (url in cards.pressed) {
        val card = (results[url] as? AppLinkResult.Card)?.takeIf { it.cardV2 != null } ?: continue
        presses[url] = key(url) { rememberThreadAppLinkCardPress(card, actions) }
    }
    return presses
}

/**
 * The buttons of the card a chip opens, as the web's hover card has them (botiverse/slock
 * `LinkCardActions.tsx`) and as a block card's are made ([blockCardButtons]): the App's buttons in
 * its order, its links ([openUri]), Refresh ([read]) and its actions ([execute]; null: not drawn),
 * then Raft's [openLink] unless one of the App's links already goes to the link. A card without
 * the App's v2 tree (a v1 card, `connect_account`) has [openLink] alone.
 * [build.raft.designsystem.components.RaftPreviewCardItemActions] lays them out (web
 * `linkCardRowLayout`): the first two actions on the row, or the first link when the card has no
 * action; everything else in the More menu.
 */
internal fun AppLinkResult.Card.chipCardButtons(
    openUri: (String) -> Unit,
    openLink: RaftPreviewCardButton,
    read: (RaftLinkCardV2Element.Button) -> RaftPreviewCardButton? = { null },
    execute: (RaftLinkCardV2Element.Button, RaftLinkCardButtonAction.Execute) -> RaftPreviewCardButton? = { _, _ -> null },
): List<RaftPreviewCardButton> {
    val tree = cardV2 ?: return listOf(openLink)
    return tree.blockCardButtons(url, openUri, openLink, read, execute)
}

/**
 * An App link's item card by its chip (web `AppLinkChip` `PreviewCardContent size="item"`: 400
 * wide, start aligned with the chip, 6 below it, or above it when it doesn't fit below; artin
 * chose a popover on the phone, #proj-raft-ui). On a phone it takes the window's width less 8 each
 * side. [RaftAppLinkCardView] with [buttons]: what the card offers given Raft's Open Link (a
 * bordered link out of Raft, ↗; [chipCardButtons] puts the App's buttons before it), laid out as
 * raft-design #939 says: the App's first two actions on the row, or the first link; the rest in
 * the More menu. Taller than the window, its body scrolls and its footer, where the link goes,
 * stays. A tap outside or Back closes it. v1 cards have no buttons of their own: an action is
 * only logged.
 */
@Composable
internal fun ThreadAppLinkCardPopover(
    card: AppLinkResult.Card,
    anchor: RaftPopoverAnchor,
    onDismiss: () -> Unit,
    onOpenLink: () -> Unit,
    buttons: (openLink: RaftPreviewCardButton) -> List<RaftPreviewCardButton> = { listOf(it) },
    eyebrow: String? = null,
    details: List<RaftPreviewCardDetail> = emptyList(),
) {
    // onOpenLink opens the card's address: the link, or a connect_account card's connect URL.
    val openLink =
        if (card.connectUrl != null) {
            raftAppLinkCardOpenButton(card) { onOpenLink() }
        } else {
            raftAppLinkOpenLinkButton(onOpenLink)
        }
    ThreadAppLinkCardPopoverFrame(key = card.url, anchor = anchor, onDismiss = onDismiss) { modifier ->
        RaftAppLinkCardView(
            card = card,
            onAction = { elementId -> raftLogInfo(ThreadAppLinkCardLogTag, "app_link_card_action_ignored element=$elementId") },
            modifier = modifier,
            buttons = buttons(openLink),
            eyebrow = eyebrow,
            details = details,
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
                    // raw-click: tap sink, keeps taps on its blank space from reaching what is under it; not a control
                    .pointerInput(Unit) { detectTapGestures {} },
            )
        }
    }
}



/** raft-ui `PreviewCardContent size="item"`: 400px (raft-design #939); narrower windows give less. */
private val ThreadAppLinkCardWidth = RaftPreviewCardDefaults.ItemWidth
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
