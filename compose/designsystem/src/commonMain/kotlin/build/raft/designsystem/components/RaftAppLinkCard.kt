package build.raft.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

// raft-ui AppLinkCard (raft-design #1010, packages/ui/src/components/app-link-card): an App link's
// card drawn in the message itself, after the link's paragraph, where the App's answer lists a
// `card` with `placement: "block"` (protocol version 3, RFC 076 section 14). Raft's header names
// the App, the body is the hover card's, and the buttons work by touch. The card never replaces the
// link. On the web it is 400px wide and never wider than the message, and the whole message width
// on a phone (below 768px); here the caller passes the cap it wants ([RaftAppLinkCardDefaults]).

object RaftAppLinkCardDefaults {
    /** The width on a wide layout (the hover card's `PreviewCardContent size="item"` width). */
    val MaxWidth: Dp = 400.dp

    /** The window width below which the web's card takes the message's full width (`max-md`). */
    val PhoneBelow: Dp = 768.dp

    /** The app icon in the header: 16px. */
    val IconSize: Dp = 16.dp

    /** The most characters of a host the header keeps before it loses its start. */
    const val HostMaxChars: Int = 40
}

/**
 * raft-ui `AppLinkCard`: the frame of a link card ([RaftMessageEmbed]: `layer-card`, hairline,
 * container radius) holding [RaftAppLinkCardHeader], [RaftAppLinkCardBody] and
 * [RaftAppLinkCardActions]. [maxWidth] is the wide layout's 400dp; pass [Dp.Unspecified] (no cap) for a phone,
 * where the card is as wide as the message. [label] names the group for a screen reader (the
 * item's title), so a button in it is heard with the item it acts on.
 */
@Composable
fun RaftAppLinkCard(
    modifier: Modifier = Modifier,
    maxWidth: Dp = RaftAppLinkCardDefaults.MaxWidth,
    label: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    RaftMessageEmbed(
        modifier = modifier.then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        maxWidth = maxWidth,
    ) {
        content()
        // `last:pb-4`: the last part's 12dp and 4dp more.
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Raft's row at the top, under a hairline: [RaftAppLinkCardIcon], [RaftAppLinkCardName], then
 * [RaftAppLinkCardHost] (`px-4 py-2`, caption, `foreground-muted`). Required: it says whose card
 * this is, and Raft draws it from what it stores for the App, never from the card's content.
 */
@Composable
fun RaftAppLinkCardHeader(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        RaftSeparator()
    }
}

/** The App's registered icon, 16dp (the image Raft stores and serves: one child). */
@Composable
fun RaftAppLinkCardIcon(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.size(RaftAppLinkCardDefaults.IconSize), contentAlignment = Alignment.Center) { content() }
}

/** The App's name as Raft stores it: semibold, ink, one line. */
@Composable
fun RaftAppLinkCardName(
    text: String,
    modifier: Modifier = Modifier,
) {
    RaftText(
        text = text,
        modifier = modifier,
        style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
        color = RaftTheme.colors.foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The link's host after a "·"; too long, it loses its start, never its end ([raftAppLinkCardHostText]). */
@Composable
fun RaftAppLinkCardHost(
    host: String,
    modifier: Modifier = Modifier,
) {
    val caption = RaftTheme.typography.caption
    val muted = RaftTheme.colors.foregroundMuted
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftText(text = "·", style = caption, color = muted, maxLines = 1)
        RaftText(text = raftAppLinkCardHostText(host), style = caption, color = muted, maxLines = 1, softWrap = false)
    }
}

/** [host] as the header shows it: past [RaftAppLinkCardDefaults.HostMaxChars] it loses its start (`…review.attacker.example`). */
fun raftAppLinkCardHostText(host: String, maxChars: Int = RaftAppLinkCardDefaults.HostMaxChars): String =
    if (host.length <= maxChars) host else "…" + host.takeLast((maxChars - 1).coerceAtLeast(1))

/** The item, 16dp from the card's sides, 6dp between its parts: [RaftAppLinkCardEyebrow], [RaftAppLinkCardTitle], [RaftAppLinkCardMeta]. */
@Composable
fun RaftAppLinkCardBody(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** The App's id for the item, in muted Space Mono, one line. */
@Composable
fun RaftAppLinkCardEyebrow(
    text: String,
    modifier: Modifier = Modifier,
) = RaftPreviewCardEyebrow(text, modifier)

/**
 * The App's title, headline bold in ink, up to [RaftPreviewCardDefaults.ItemTitleMaxLines] lines
 * then an ellipsis; the whole title in a tooltip only when it is cut. The title opens the item
 * ([onClick]), so a plain Open link goes in the More menu: it keeps the title's ink, not link blue.
 */
@Composable
fun RaftAppLinkCardTitle(
    text: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
) {
    var cut by remember(text) { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val title: @Composable (Modifier) -> Unit = { titleModifier ->
        Box(
            titleModifier.fillMaxWidth().then(
                if (onClick != null) Modifier.clickable(source, indication = null, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier,
            ),
        ) {
            RaftText(
                text = text,
                style = RaftTheme.typography.headline,
                color = RaftTheme.colors.foreground,
                maxLines = RaftPreviewCardDefaults.ItemTitleMaxLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { cut = it.hasVisualOverflow },
            )
        }
    }
    if (cut) RaftTooltip(text = text, modifier = modifier) { title(Modifier) } else title(modifier)
}

/** One state in a neutral Badge, then the muted details; wraps when it runs out of room ([RaftPreviewCardMeta]). */
@Composable
fun RaftAppLinkCardMeta(
    details: List<RaftPreviewCardDetail>,
    modifier: Modifier = Modifier,
) = RaftPreviewCardMeta(details, modifier)

/**
 * The App's buttons at the card's bottom end ([raftAppLinkCardButtonLayout], web `LinkCardActions`
 * with `placement="block"`): the App's first two buttons on the row whatever they do (an action,
 * Refresh, a link), the frameless ones first and the bordered one last, and a More menu (⋯) to
 * their left for the rest in the App's order. The title opens the item, so a link to it
 * ([RaftPreviewCardButton.opensItem], Raft's Open Link included) is always in the menu and never
 * takes the row's place.
 */
@Composable
fun RaftAppLinkCardActions(
    buttons: List<RaftPreviewCardButton>,
    modifier: Modifier = Modifier,
    moreLabel: String = RaftTheme.strings.moreActions,
) {
    if (buttons.isEmpty()) return
    val layout = remember(buttons) { raftAppLinkCardButtonLayout(buttons) }
    RaftPreviewCardLaidOutActions(layout, modifier, moreLabel = moreLabel)
}

/**
 * The card's size while Raft fetches an answer it already knows lists a block card: put the
 * [RaftAppLinkCardHeader] in [header] when the App is known; the item's lines and a button
 * follow as gray bars the height of the parts they stand for (a two-line title and a row of
 * buttons), so the card that takes its place moves the message as little as it can.
 */
@Composable
fun RaftAppLinkCardPlaceholder(
    modifier: Modifier = Modifier,
    maxWidth: Dp = RaftAppLinkCardDefaults.MaxWidth,
    header: (@Composable () -> Unit)? = null,
) {
    val typography = RaftTheme.typography
    val density = androidx.compose.ui.platform.LocalDensity.current
    RaftMessageEmbed(modifier, maxWidth = maxWidth) {
        header?.invoke()
        Column(
            Modifier.fillMaxWidth().clearAndSetSemantics { }.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            fun lineOf(style: androidx.compose.ui.text.TextStyle, fallback: Dp): Dp =
                if (style.lineHeight.isSp) with(density) { style.lineHeight.toDp() } else fallback
            val eyebrow = lineOf(typography.monoMeta, 16.dp)
            val headline = lineOf(typography.headline, 24.dp)
            val callout = lineOf(typography.callout, 20.dp)
            RaftAppLinkCardPlaceholderLine(eyebrow, Modifier.width(112.dp))
            RaftAppLinkCardPlaceholderLine(headline, Modifier.fillMaxWidth())
            RaftAppLinkCardPlaceholderLine(headline, Modifier.fillMaxWidth(0.66f))
            RaftAppLinkCardPlaceholderLine(callout, Modifier.width(160.dp))
        }
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics { }.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            RaftSkeleton(Modifier.width(112.dp).height(32.dp))
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun RaftAppLinkCardPlaceholderLine(height: Dp, modifier: Modifier) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.CenterStart) {
        RaftSkeleton(modifier, variant = RaftSkeletonVariant.Line)
    }
}
