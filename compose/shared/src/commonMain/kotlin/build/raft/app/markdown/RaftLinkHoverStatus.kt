package build.raft.app.markdown

import build.raft.app.links.ParsedRaftPermalink
import build.raft.app.links.toParsedRaftPermalink
import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftAppScopeApiEnvironment
import build.raft.app.model.ConversationKind
import build.raft.app.raftArgbHex
import build.raft.app.raftMarkdownUrlOpen
import build.raft.designsystem.components.raftPointerHoverPosition
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftSpacing
import androidx.compose.runtime.withFrameMillis

/**
 * What the link status (a browser's status bar) says for one hovered link: [text], and whether the
 * click leaves the app ([external]: drawn with ↗).
 */
internal data class RaftLinkHoverLabel(val text: String, val external: Boolean)

/** The kind words after a conversation's name: "#proj-mobile · message". */
internal data class RaftLinkHoverTerms(val message: String, val thread: String)

@Composable
internal fun raftLinkHoverLocalizedTerms(): RaftLinkHoverTerms =
    RaftLinkHoverTerms(
        message = stringResource(Res.string.link_hover_status_message),
        thread = stringResource(Res.string.link_hover_status_thread),
    )

/**
 * The status of a message-body link, classified as a click on it opens ([raftMarkdownUrlOpen],
 * ThreadPage `openMarkdownUrl`): a channel / DM message or thread link opens in the app and reads
 * "#proj-mobile · message" (the name from [conversationName], the message-link chip's resolver), or
 * its web link while the name is not known; a Raft Desktop link (`raft://…`) reads as its web link
 * and is never external.
 * Anything else opens in the browser and shows its full URL, [RaftLinkHoverLabel.external].
 * [webUrl] is the Developer environment's web URL (blank or null: production).
 */
internal fun raftLinkHoverLabel(
    url: String,
    webUrl: String?,
    conversationName: (ParsedRaftPermalink) -> String?,
    terms: RaftLinkHoverTerms,
): RaftLinkHoverLabel? {
    val raw = url.trim().takeIf(String::isNotEmpty) ?: return null
    val open = raftMarkdownUrlOpen(raw, webUrl)
    // A Raft Desktop link stays with Raft even where it is not a message (the app's own scheme).
    val scheme = raw.substringBefore("://", missingDelimiterValue = "").lowercase()
    val raftScheme = scheme == "raft" || scheme.startsWith("raft-")
    if (open.target == null) return RaftLinkHoverLabel(text = open.url, external = !raftScheme)
    val permalink = open.url.toParsedRaftPermalink(configuredWebUrl = webUrl)
    val name =
        permalink?.let(conversationName)
            ?.trim()
            ?.removePrefix("#")
            ?.removePrefix("@")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    if (permalink == null || name == null) return RaftLinkHoverLabel(text = open.url, external = false)
    val prefix = if (permalink.routeKind == ConversationKind.DirectMessage) "@" else "#"
    val kind = if (permalink.threadParentMessageId != null) terms.thread else terms.message
    return RaftLinkHoverLabel(text = "$prefix$name · $kind", external = false)
}

/**
 * [text] cut in the middle to [maxChars] characters with "…" (a long URL keeps its host and its
 * end). Unchanged when it fits; a [maxChars] below 3 keeps at least one character each side.
 */
internal fun raftMiddleEllipsis(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val kept = (maxChars - 1).coerceAtLeast(2)
    val head = (kept + 1) / 2
    val tail = kept - head
    return text.take(head) + RaftEllipsis + text.takeLast(tail)
}

/** The longest middle-cut of [text] ([raftMiddleEllipsis]) that [fits]; [text] itself when it does. */
internal fun raftFitMiddleEllipsis(text: String, fits: (String) -> Boolean): String {
    if (text.length <= MinMiddleEllipsisChars || fits(text)) return text
    var low = MinMiddleEllipsisChars
    var high = text.length - 1
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (fits(raftMiddleEllipsis(text, mid))) low = mid else high = mid - 1
    }
    return raftMiddleEllipsis(text, low)
}

private const val RaftEllipsis = "…"
private const val MinMiddleEllipsisChars = 12

/**
 * The page's link status. Each hovered text reports under its own [owner] key, so a text the
 * pointer left after another took over (events from two texts can interleave) never clears it.
 */
@Stable
internal class RaftLinkHoverStatusState {
    var label: RaftLinkHoverLabel? by mutableStateOf(null)
        private set
    private var owner: Any? = null

    fun show(owner: Any, label: RaftLinkHoverLabel) {
        this.owner = owner
        if (this.label != label) this.label = label
    }

    fun hide(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        label = null
    }
}

/** The page's link status (BasePager's link-status layer); null: links show no status here. */
internal val LocalRaftLinkHoverStatus = staticCompositionLocalOf<RaftLinkHoverStatusState?> { null }

/** Hover delay before the status appears, and the grace that keeps it while moving to the next link. */
internal const val RaftLinkHoverStatusShowDelayMs = 300L
internal const val RaftLinkHoverStatusHideGraceMs = 120L

internal const val RaftLinkHoverStatusTestTag = "raft-link-hover-status"

/**
 * A page with a link status: the destination of the link under a mouse or trackpad pointer at the
 * page's bottom-left, as a browser's status bar (it fills the window: Compose 版, iPad, an Android
 * window). It draws over the page and takes no input, so clicks and hovers reach the page below.
 */
@Composable
internal fun RaftLinkHoverStatusHost(
    state: RaftLinkHoverStatusState = remember { RaftLinkHoverStatusState() },
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalRaftLinkHoverStatus provides state) {
        // No propagateMinConstraints: the page keeps the loose constraints it had before this
        // layer, so a page smaller than the window stays its own size at the top-left instead
        // of being stretched to the whole window (and its content re-centred within it).
        Box(Modifier.fillMaxSize()) {
            content()
            RaftLinkHoverStatusOverlay(state)
        }
    }
}

@Composable
private fun RaftLinkHoverStatusOverlay(state: RaftLinkHoverStatusState) {
    val target = state.label
    var shown by remember { mutableStateOf<RaftLinkHoverLabel?>(null) }
    LaunchedEffect(target) {
        when {
            target == null -> {
                awaitFrameTime(RaftLinkHoverStatusHideGraceMs)
                shown = null
            }
            // Already up: it follows the pointer from link to link at once.
            shown != null -> shown = target
            else -> {
                awaitFrameTime(RaftLinkHoverStatusShowDelayMs)
                shown = target
            }
        }
    }
    val label = shown ?: return
    RaftDesignSystemTheme {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val colors = RaftTheme.colors
            val style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium)
            val padX = RaftSpacing.Sm + RaftSpacing.Xxs
            val iconSize = 14.dp
            val bubbleMax = min(maxWidth * 0.6f, 640.dp)
            val textMax = bubbleMax - padX * 2 - if (label.external) iconSize + RaftSpacing.Xs else 0.dp
            val textMaxPx = with(LocalDensity.current) { textMax.roundToPx() }
            val measurer = rememberTextMeasurer()
            val text =
                remember(label.text, textMaxPx, style) {
                    raftFitMiddleEllipsis(label.text) { candidate ->
                        measurer.measure(AnnotatedString(candidate), style, maxLines = 1, softWrap = false).size.width <= textMaxPx
                    }
                }
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(RaftSpacing.Sm)
                        .widthIn(max = bubbleMax)
                        .background(colors.layerHud, RoundedCornerShape(RaftRadius.Chip))
                        .padding(horizontal = padX, vertical = RaftSpacing.Xs)
                        .testTag(RaftLinkHoverStatusTestTag),
                horizontalArrangement = Arrangement.spacedBy(RaftSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftText(
                    text = text,
                    modifier = Modifier.weight(1f, fill = false),
                    style = style,
                    color = colors.foregroundOnHud,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (label.external) {
                    RaftLucideIcon(
                        RaftLucideIconName.ArrowUpRight,
                        modifier = Modifier.size(iconSize),
                        color = raftArgbHex(colors.foregroundOnHudMuted),
                    )
                }
            }
        }
    }
}

/**
 * Waits [millis] on the frame clock, not `delay`: a UI test's clock then drives it (the composition's
 * effect dispatcher takes `delay` from the wall clock). Only runs for the short hover delays.
 */
private suspend fun awaitFrameTime(millis: Long) {
    val start = withFrameMillis { it }
    while (withFrameMillis { it } - start < millis) Unit
}

/** One link of a rendered text: its characters [start] until [end], and where it goes. */
internal data class RaftHoverableLink(val start: Int, val end: Int, val url: String)

/**
 * The links of a rendered text a status describes: URL links (plain links, message-link and App
 * link chips) and App link card chips (their URL is the hover-card annotation). A rich-text tag
 * (@mention, #channel, task) and the body's own click are not links to a place.
 */
internal fun AnnotatedString.raftHoverableLinks(): List<RaftHoverableLink> {
    if (isEmpty()) return emptyList()
    val cardUrls = getStringAnnotations(RaftAppLinkHoverTag, 0, length)
    return getLinkAnnotations(0, length)
        .mapNotNull { range ->
            if (range.end <= range.start) return@mapNotNull null
            val url =
                when (val link = range.item) {
                    is LinkAnnotation.Url -> link.url
                    is LinkAnnotation.Clickable ->
                        if (link.tag == RaftAppLinkCardTag) {
                            cardUrls.firstOrNull { it.start < range.end && range.start < it.end }?.item
                        } else {
                            null
                        }
                    else -> null
                }
            url?.takeIf(String::isNotBlank)?.let { RaftHoverableLink(range.start, range.end, it) }
        }
        .distinct()
}

/**
 * Feeds one text's hovered link to the page's link status. [layout] is the text's last layout (set
 * from its onTextLayout); [describe] turns a URL into its status.
 */
@Stable
internal class RaftMarkdownLinkHoverStatus(
    private val links: List<RaftHoverableLink>,
    private val status: RaftLinkHoverStatusState,
    private val describe: (String) -> RaftLinkHoverLabel?,
) {
    var layout: TextLayoutResult? = null
    private var hoveredUrl: String? = null

    fun hover(position: Offset?) {
        val url = position?.let { at -> layout?.let { linkAt(it, at) } }
        if (url == hoveredUrl) return
        hoveredUrl = url
        val label = url?.let(describe)
        if (label == null) status.hide(this) else status.show(this, label)
    }

    fun leave() = hover(null)

    private fun linkAt(layout: TextLayoutResult, position: Offset): String? {
        val textLength = layout.layoutInput.text.length
        if (textLength == 0) return null
        val caret = layout.getOffsetForPosition(position)
        // The caret is the nearest gap: the character under the pointer is the one after or before it.
        for (offset in intArrayOf(caret, caret - 1)) {
            if (offset !in 0 until textLength) continue
            val link = links.firstOrNull { offset >= it.start && offset < it.end } ?: continue
            if (layout.getBoundingBox(offset).contains(position)) return link.url
        }
        return null
    }
}

/**
 * The link status of [text]'s links, null when the page has no status or the text no link.
 * Resolves the conversation names with the message-link chip's resolver.
 */
@Composable
internal fun rememberRaftMarkdownLinkHoverStatus(text: AnnotatedString): RaftMarkdownLinkHoverStatus? {
    val status = LocalRaftLinkHoverStatus.current ?: return null
    val links = remember(text) { text.raftHoverableLinks() }
    if (links.isEmpty()) return null
    val conversationName = LocalRaftMessagePermalinkLabelResolver.current
    val terms = raftLinkHoverLocalizedTerms()
    val tracker =
        remember(links, status, conversationName, terms) {
            RaftMarkdownLinkHoverStatus(links, status) { url ->
                raftLinkHoverLabel(url, raftLinkHoverWebUrl(), conversationName, terms)
            }
        }
    DisposableEffect(tracker) { onDispose { tracker.leave() } }
    return tracker
}

/** Reports a mouse or trackpad pointer over the text to [tracker]; consumes nothing. */
internal fun Modifier.raftMarkdownLinkHoverStatus(tracker: RaftMarkdownLinkHoverStatus): Modifier =
    raftPointerHoverPosition(onHover = tracker::hover)

private fun raftLinkHoverWebUrl(): String? =
    runCatching { RaftAppScopeApiEnvironment.store().current().webUrl }.getOrNull()?.takeIf(String::isNotBlank)
