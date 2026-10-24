package build.raft.app.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppThemeState
import build.raft.app.RaftCachedImage
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkResult
import build.raft.app.data.appLinkDomain
import build.raft.app.data.appLinkPreviewHref
import build.raft.app.data.appLinkShortAddress
import build.raft.app.raftArgbHex
import build.raft.app.raftMonoFamilyForWeight
import build.raft.app.raftSpanStyleForWeight
import coil3.compose.AsyncImagePainter

/**
 * The App link results of the message being rendered (web `AppLinkResultsProvider`), and what a
 * tap on a grey chip does: ask the viewer first. Null outside a message that reads them.
 */
@Immutable
internal data class RaftAppLinkChipHost(
    val results: Map<String, AppLinkResult>,
    val onAsk: (AppLinkResult.Consent) -> Unit,
)

internal val LocalRaftAppLinkChips = staticCompositionLocalOf<RaftAppLinkChipHost?> { null }

internal data class RaftAppLinkChipContent(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
)

/**
 * Turns each bare App link of a message into its chip (protocol v3.2.3 addendum 5; raft-ui
 * MessageItem › App links; web `AppLinkChip`). A link the author gave words to stays as written.
 *
 * - [AppLinkResult.Card]: `MessageReferenceChip variant="info" capped`, a cyan chip with the
 *   App's icon, its title and, since a finger opens it without a hover card first, the link's
 *   domain as `MessageReferenceSuffix`. A tap opens the link.
 * - [AppLinkResult.Consent]: `variant="muted"`, a grey chip with the App's icon and the
 *   shortened address. A tap asks first ([RaftAppLinkChipHost.onAsk]); nothing is opened.
 * - [AppLinkResult.Pending]: `MessageReferencePending`, the Link icon and the shortened
 *   address in muted ink, not pressable.
 *
 * `capped` (24em, the label truncating) is a character budget here: the chip is inline text,
 * so the label is cut with an ellipsis before it is laid out, and a domain that doesn't fit
 * loses its start, never its end.
 */
internal fun AnnotatedString.withRaftAppLinkChipContent(
    host: RaftAppLinkChipHost?,
    openUri: (String) -> Unit,
): RaftAppLinkChipContent {
    if (host == null || host.results.isEmpty()) return RaftAppLinkChipContent(this, emptyMap())
    val chips =
        getLinkAnnotations(0, length)
            .mapNotNull { range ->
                val link = range.item as? LinkAnnotation.Url ?: return@mapNotNull null
                val key = appLinkPreviewHref(link.url) ?: return@mapNotNull null
                // Only a bare link becomes a chip: its text is its own address.
                if (appLinkPreviewHref(text.substring(range.start, range.end).trim()) != key) return@mapNotNull null
                val result = host.results[key] ?: return@mapNotNull null
                range to result
            }
            .sortedBy { (range, _) -> range.start }
    if (chips.isEmpty()) return RaftAppLinkChipContent(this, emptyMap())

    val builder = AnnotatedString.Builder(length)
    val inlineContent = linkedMapOf<String, InlineTextContent>()
    var cursor = 0
    chips.forEach { (range, result) ->
        if (range.start < cursor || range.end <= range.start) return@forEach
        val domain = appLinkDomain(result.url) ?: return@forEach
        builder.append(this, cursor, range.start)
        val prefix = "raft-app-link-${range.start}-${range.end}"
        val iconId = "$prefix-icon"
        val iconGapId = "$prefix-icon-gap"
        val suffixGapId = "$prefix-suffix-gap"
        val box = raftAppLinkChipBoxStyle(result)
        val label: String
        var suffix: String? = null
        when (result) {
            is AppLinkResult.Card -> {
                suffix = raftAppLinkKeepEnd(domain, RaftAppLinkDomainMaxChars)
                label = raftAppLinkCap(result.title, (RaftAppLinkChipMaxChars - suffix.length).coerceAtLeast(RaftAppLinkTitleMinChars))
            }
            else -> label = raftAppLinkCap(appLinkShortAddress(result.url) ?: domain, RaftAppLinkChipMaxChars)
        }
        val annotation: LinkAnnotation? =
            when (result) {
                is AppLinkResult.Card ->
                    LinkAnnotation.Url(
                        url = result.url,
                        styles = raftAppLinkChipLinkStyles(),
                        linkInteractionListener = LinkInteractionListener { openUri(result.url) },
                    )
                is AppLinkResult.Consent ->
                    LinkAnnotation.Clickable(
                        tag = RaftAppLinkConsentTag,
                        styles = raftAppLinkChipLinkStyles(),
                        linkInteractionListener = LinkInteractionListener { host.onAsk(result) },
                    )
                is AppLinkResult.Pending -> null
            }
        builder.withRaftInlineChipGaps(box) {
            withRaftInlineBox(box) {
                val chip: AnnotatedString.Builder.() -> Unit = {
                    withStyle(SpanStyle(fontSize = RAFT_RICHTEXT_INLINE_CHIP_FONT_SIZE_EM.em)) {
                        appendInlineContent(iconId, "￼")
                        appendInlineContent(iconGapId, "￼")
                        val ink = if (result is AppLinkResult.Pending) RaftColor.ForegroundMuted else RaftColor.TextBlack
                        withStyle(raftSpanStyleForWeight(FontWeight.SemiBold).copy(color = ink)) {
                            append(label)
                        }
                        suffix?.let { domainText ->
                            appendInlineContent(suffixGapId, " ")
                            withStyle(
                                SpanStyle(
                                    color = RaftColor.ForegroundHint,
                                    // raft-ui MessageReferenceSuffix: `font-mono text-label font-normal`.
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal,
                                    fontFamily = raftMonoFamilyForWeight(FontWeight.Normal),
                                ),
                            ) {
                                append(domainText)
                            }
                        }
                    }
                }
                if (annotation != null) withLink(annotation) { chip() } else chip()
            }
        }
        inlineContent[iconId] = raftAppLinkIconInlineContent(result)
        inlineContent[iconGapId] = raftAppLinkSpacerInlineContent(4.sp)
        if (suffix != null) inlineContent[suffixGapId] = raftAppLinkSpacerInlineContent(4.sp)
        cursor = range.end
    }
    builder.append(this, cursor, length)
    return RaftAppLinkChipContent(builder.toAnnotatedString(), inlineContent)
}

internal const val RaftAppLinkConsentTag = "raft.build.markdown.appLinkConsent"

/** About 24em of 14sp semibold on a phone (336dp): the whole chip's words. */
internal const val RaftAppLinkChipMaxChars = 40
internal const val RaftAppLinkDomainMaxChars = 24
internal const val RaftAppLinkTitleMinChars = 12

/** The label cut at the end with an ellipsis. */
internal fun raftAppLinkCap(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else text.take((maxChars - 1).coerceAtLeast(1)).trimEnd() + "…"

/** A domain too long for its place loses its start, never its end (`…review.attacker.example`). */
internal fun raftAppLinkKeepEnd(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else "…" + text.takeLast((maxChars - 1).coerceAtLeast(1))

// raft-ui inline chip with a leading mark: 4.5px at each end, `rounded-sticker`, no edge. A
// preview is `info-soft` (cyan); one that asks first, or waits, `inactive-soft` (grey).
internal fun raftAppLinkChipBoxStyle(result: AppLinkResult): RaftInlineBoxStyle =
    RaftInlineBoxStyle(
        backgroundColor = if (result is AppLinkResult.Card) RaftColor.InfoSoft else RaftColor.InactiveSoft,
        paddingStart = 4.5.dp,
        paddingEnd = 4.5.dp,
        paddingTop = RaftRichTextInlineChipVerticalPaddingDp.dp,
        paddingBottom = RaftRichTextInlineChipVerticalPaddingDp.dp,
        marginStart = RaftRichTextInlineChipMarginDp.dp,
        marginEnd = RaftRichTextInlineChipMarginDp.dp,
        cornerRadius = RaftAppRadius.Sticker,
    )

private fun raftAppLinkChipLinkStyles(): TextLinkStyles {
    val base =
        raftSpanStyleForWeight(FontWeight.SemiBold).copy(
            textDecoration = null,
            background = Color.Unspecified,
        )
    // raft-ui `active:overlay-medium`, as a wash over the chip's words (see the permalink chip).
    return TextLinkStyles(style = base, pressedStyle = base.copy(background = RaftAppThemeState.colors.fillPressed))
}

private fun raftAppLinkIconInlineContent(result: AppLinkResult): InlineTextContent {
    val app: AppLinkApp? =
        when (result) {
            is AppLinkResult.Card -> result.app
            is AppLinkResult.Consent -> AppLinkApp(result.app.name, result.app.icon)
            is AppLinkResult.Pending -> null
        }
    // `MessageReferenceIcon`: an App's image in a 14px box with 2px corners; an svg is 0.857em.
    val edge = if (app?.icon != null) 14.sp else 12.sp
    return InlineTextContent(
        placeholder = Placeholder(width = edge, height = edge, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
    ) {
        val icon = app?.icon
        var broken by remember(icon) { mutableStateOf(false) }
        if (icon != null && !broken) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
                RaftCachedImage(
                    source = icon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onImageStateChange = { state -> if (state is AsyncImagePainter.State.Error) broken = true },
                )
            }
        } else {
            Box(Modifier.size(if (icon != null) 14.dp else 12.dp), contentAlignment = Alignment.Center) {
                RaftLucideIcon(
                    name = if (app == null) RaftLucideIconName.Link else RaftLucideIconName.AppWindow,
                    modifier = Modifier.size(12.dp),
                    // raft-ui reference chip: `[&_svg]:text-foreground-muted`.
                    color = raftArgbHex(RaftColor.ForegroundMuted),
                    strokeWidth = 2f,
                )
            }
        }
    }
}

private fun raftAppLinkSpacerInlineContent(width: TextUnit): InlineTextContent =
    InlineTextContent(
        placeholder = Placeholder(width = width, height = 1.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
    ) {}
