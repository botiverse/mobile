package build.raft.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * raft-ui 0.8.0 TextLink `variant`. The variant follows where the link sits, not how
 * important the action is.
 */
enum class RaftTextLinkVariant(internal val weight: FontWeight) {
    /** A link on its own (Forgot Password, Log Out, See All): `foreground-muted`, 500, ink when pressed. */
    Muted(FontWeight.Medium),

    /**
     * A link inside a sentence ("Don't have an account? Create One", a banner's "Manage
     * Machines"): ink, 600, the underline turns pink (`attention`) when pressed.
     */
    Strong(FontWeight.SemiBold),
}

/** text-link.tsx `underline decoration-[1.5px] underline-offset-[3px]`, `rounded-[2px]`. */
internal object RaftTextLinkMetrics {
    val UnderlineThickness = 1.5.dp

    /** From the alphabetic baseline to the top of the underline (Chrome's length offset). */
    val UnderlineOffset = 3.dp
    val FocusRadius = 2.dp
}

/**
 * raft-ui `TextLinkExternalIcon` (text-link.tsx, raft-design patterns/navigation.md "Links that
 * leave Raft"): Lucide `ArrowUpRight` at 0.857em of the text (12px at 14px), stroke 2, 2px after
 * the text, in the text's colour, centred on the cap height (0.7em/2 above the baseline). The
 * link's underline stops at the text and never runs under it.
 */
internal object RaftTextLinkExternalMark {
    const val SizeEm = 0.857f
    val Gap = 2.dp

    /** Centre of the mark above the baseline, in em (`align-[calc(0.7em/2-0.857em/2)]`). */
    const val CentreAboveBaselineEm = 0.35f

    /** Joins the mark to the last word (no line break between them) and stands in for it. */
    const val Chars = "\u2060\uFFFC"

    /** Inline content key of link [index]'s mark in [RaftLinkedText]. */
    fun id(index: Int) = "raft-text-link-external:$index"

    /** BasicText's inline-content annotation tag (androidx `appendInlineContent`). */
    const val InlineContentTag = "androidx.compose.foundation.text.inlineContent"
}

/**
 * The placeholder of the external mark for text in [style]: [RaftTextLinkExternalMark.Gap] plus
 * the mark wide, the mark tall, standing on the baseline (the mark itself is drawn lower, see
 * [RaftTextLinkExternalMarkContent]).
 */
internal fun raftTextLinkExternalPlaceholder(style: TextStyle, density: Density): Placeholder {
    val fontSize = if (style.fontSize.isSp) style.fontSize.value else 14f
    val gapSp = with(density) { RaftTextLinkExternalMark.Gap.toSp().value }
    return Placeholder(
        width = (fontSize * RaftTextLinkExternalMark.SizeEm + gapSp).sp,
        height = (fontSize * RaftTextLinkExternalMark.SizeEm).sp,
        placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
    )
}

/**
 * Draws the mark in its placeholder: after the gap, moved down so its centre sits
 * [RaftTextLinkExternalMark.CentreAboveBaselineEm] above the baseline (the placeholder stands on it).
 */
@Composable
internal fun RaftTextLinkExternalMarkContent(color: Color, fontSize: Float) {
    val density = LocalDensity.current
    val markPx = with(density) { (fontSize * RaftTextLinkExternalMark.SizeEm).sp.toPx() }
    val dropPx = with(density) { (fontSize * (RaftTextLinkExternalMark.SizeEm / 2f - RaftTextLinkExternalMark.CentreAboveBaselineEm)).sp.toPx() }
    val gapPx = with(density) { RaftTextLinkExternalMark.Gap.toPx() }
    Box(Modifier.offset { IntOffset(gapPx.roundToInt(), dropPx.roundToInt()) }) {
        RaftGlyphIcon(RaftGlyph.ArrowUpRight, size = with(density) { markPx.toDp() }, color = color, strokeWidth = 2f)
    }
}

/** Resolved colours of one variant x state. [underline] is null when there is none (disabled). */
@Immutable
internal data class RaftTextLinkLook(val ink: Color, val weight: FontWeight, val underline: Color?)

/**
 * text-link.tsx (raft-ui 0.9.4+): no underline at rest; on hover it underlines in its own
 * colour (`currentColor`) and never turns pink. Muted is `foreground-muted` 500 and turns
 * `foreground` on hover; strong is `foreground` 600. Disabled: `foreground-disabled`, no
 * underline. [active] is a mouse or trackpad hover, or a press: touch has no hover, so a
 * press takes the hover look.
 */
internal fun raftTextLinkLook(variant: RaftTextLinkVariant, enabled: Boolean, active: Boolean, colors: RaftUiThemeColors): RaftTextLinkLook =
    when {
        !enabled -> RaftTextLinkLook(colors.foregroundDisabled, variant.weight, underline = null)
        variant == RaftTextLinkVariant.Muted -> {
            val ink = if (active) colors.foreground else colors.foregroundMuted
            RaftTextLinkLook(ink, variant.weight, underline = ink.takeIf { active })
        }
        else -> RaftTextLinkLook(colors.foreground, variant.weight, underline = colors.foreground.takeIf { active })
    }

/**
 * raft-ui 0.8.0 TextLink on its own: underlined text, the bottom step of the button
 * ladder, below a frameless [RaftButton]. Use it where even a ghost button is too much
 * weight: Forgot Password on a sign-in page, Log Out under a server list, See All beside a
 * section title. For a link inside a sentence, put it in the sentence's text with
 * [appendRaftTextLink] (it is [RaftTextLinkVariant.Strong] there).
 *
 * - No height, padding, border or box of its own: it takes the size and line height of
 *   the text around it, so pass that text's [style] (default `body`, as [RaftText]).
 * - No underline at rest. Hovered by a mouse or trackpad, or pressed (touch has no hover):
 *   a 1.5dp underline 3dp under the baseline in the text colour, never pink; muted also
 *   turns ink.
 * - Focus (keyboard only): the focus ring on a 2dp radius, outside the text.
 * - Disabled: `foreground-disabled`, no underline, not focusable.
 * - Title Case, starting with a verb or naming the place. It is a button for
 *   accessibility (raft-ui renders `button type="button"`).
 *
 * Its target is only its text: don't make it the only thing a finger must hit on a phone;
 * that needs a [RaftButton].
 */
@Composable
fun RaftTextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: RaftTextLinkVariant = RaftTextLinkVariant.Muted,
    style: TextStyle = RaftTheme.typography.body,
    enabled: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    interactionSource: MutableInteractionSource? = null,
    /** It takes someone to another site: it ends in the ↗ mark ([RaftTextLinkExternalMark]). */
    leavesRaft: Boolean = false,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val colors = RaftTheme.colors
    val isPressed = raftIsPressed(source)
    val isHovered = raftIsHovered(source)
    val look = raftTextLinkLook(variant, enabled, active = enabled && (isPressed || isHovered), colors)
    val isFocused = raftIsFocused(source)
    val focused = enabled && isFocused
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val resolved = style.merge(TextStyle(color = look.ink, fontWeight = look.weight))
    val density = LocalDensity.current
    val shown = remember(text, leavesRaft) {
        buildAnnotatedString {
            append(text)
            if (leavesRaft) {
                append(RaftTextLinkExternalMark.Chars)
                addStringAnnotation(RaftTextLinkExternalMark.InlineContentTag, RaftTextLinkExternalMark.id(0), text.length + 1, text.length + 2)
            }
        }
    }
    val marks =
        if (!leavesRaft) {
            emptyMap()
        } else {
            val fontSize = if (resolved.fontSize.isSp) resolved.fontSize.value else 14f
            mapOf(
                RaftTextLinkExternalMark.id(0) to
                    InlineTextContent(raftTextLinkExternalPlaceholder(resolved, density)) { RaftTextLinkExternalMarkContent(look.ink, fontSize) },
            )
        }
    BasicText(
        text = shown,
        inlineContent = marks,
        modifier =
            modifier
                .raftFocusRing(focused, RaftTextLinkMetrics.FocusRadius, colors)
                .raftPointerHover(source)
                .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    if (look.underline != null) drawRaftTextLinkUnderline(result, 0, text.length, look.underline)
                },
        style = resolved,
        onTextLayout = { layout = it },
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
    )
}

/** Marks a [LinkAnnotation] as a [RaftTextLink] for [RaftText]: `raft-text-link:<variant>:<enabled>`. */
private const val RaftTextLinkTag = "raft-text-link"

/** A TextLink inside a run of text, found by [raftTextLinks]. */
@Immutable
internal data class RaftInlineTextLink(
    val start: Int,
    val end: Int,
    val variant: RaftTextLinkVariant,
    val enabled: Boolean,
    val annotation: LinkAnnotation.Clickable,
    /** It ends in the ↗ mark: its last [RaftTextLinkExternalMark.Chars] are the mark, not words. */
    val leavesRaft: Boolean = false,
) {
    /** Where its words end (the underline stops here; the mark after them is still part of the target). */
    val wordsEnd: Int get() = if (leavesRaft) end - RaftTextLinkExternalMark.Chars.length else end
}

/**
 * Appends [text] as a TextLink inside a sentence, for [RaftText]:
 *
 * ```
 * RaftText(
 *     buildAnnotatedString {
 *         append("Don't have an account? ")
 *         appendRaftTextLink("Create One", onClick = onCreate)
 *     },
 *     style = RaftTheme.typography.footnote,
 *     color = RaftTheme.colors.foregroundMuted,
 * )
 * ```
 *
 * The link takes the sentence's size and line height, so it never makes the line taller,
 * and sits on its baseline. It is raft-ui's in-sentence TextLink (`nativeButton={false}`,
 * raft-ui 0.102.3, raft-design #1306): it wraps with the words around it, so a link at the
 * end of a sentence ("Don't have an account? Create One") never jumps whole to the next line
 * and leaves the line before it short. Each line it wraps onto gets its own underline, focus
 * ring and tap target. It is [RaftTextLinkVariant.Strong] by default, as a link inside a
 * sentence should be. Looks and states are those of [RaftTextLink]. Its target is only its words.
 */
fun AnnotatedString.Builder.appendRaftTextLink(
    text: String,
    onClick: () -> Unit,
    variant: RaftTextLinkVariant = RaftTextLinkVariant.Strong,
    enabled: Boolean = true,
    /** It takes someone to another site (docs, terms, a runtime's page): it ends in ↗ ([RaftTextLinkExternalMark]). */
    leavesRaft: Boolean = false,
) {
    val link =
        LinkAnnotation.Clickable(
            tag = "$RaftTextLinkTag:${variant.name}:$enabled" + if (leavesRaft) ":$RaftTextLinkLeavesRaft" else "",
            linkInteractionListener = { if (enabled) onClick() },
        )
    pushLink(link)
    // Plain spaces: the link breaks between its words as the sentence does (raft-design #1306).
    append(text)
    // The mark is part of the link (a tap on it opens it) and is glued to its last word.
    if (leavesRaft) append(RaftTextLinkExternalMark.Chars)
    pop()
}

private const val RaftTextLinkLeavesRaft = "leaves-raft"


/** The [appendRaftTextLink] links of [text], in order. */
internal fun raftTextLinks(text: AnnotatedString): List<RaftInlineTextLink> =
    text.getLinkAnnotations(0, text.length).mapNotNull { range ->
        val link = range.item as? LinkAnnotation.Clickable ?: return@mapNotNull null
        val parts = link.tag.split(':')
        if (parts.size !in 3..4 || parts[0] != RaftTextLinkTag) return@mapNotNull null
        if (parts.size == 4 && parts[3] != RaftTextLinkLeavesRaft) return@mapNotNull null
        val variant = RaftTextLinkVariant.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
        RaftInlineTextLink(range.start, range.end, variant, enabled = parts[2] == "true", annotation = link, leavesRaft = parts.size == 4)
    }

/**
 * [RaftText] with [appendRaftTextLink] links: the text is drawn once (its links in their
 * look, their underlines and focus rings drawn under it), and each link gets a clickable
 * node over its words. Other link annotations and string annotations are dropped; the
 * span and paragraph styles stay. [style] is already resolved (colour included).
 */
@Composable
internal fun RaftLinkedText(
    text: AnnotatedString,
    links: List<RaftInlineTextLink>,
    modifier: Modifier,
    style: TextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    softWrap: Boolean,
    onTextLayout: ((TextLayoutResult) -> Unit)?,
) {
    val colors = RaftTheme.colors
    val sources = remember(links.size) { List(links.size) { MutableInteractionSource() } }
    val looks = ArrayList<RaftTextLinkLook>(links.size)
    val focused = ArrayList<Boolean>(links.size)
    links.forEachIndexed { i, link ->
        key(i) {
            val isPressed = raftIsPressed(sources[i])
            val isHovered = raftIsHovered(sources[i])
            val active = link.enabled && (isPressed || isHovered)
            looks += raftTextLinkLook(link.variant, link.enabled, active, colors)
            val isFocused = raftIsFocused(sources[i])
            focused += link.enabled && isFocused
        }
    }
    val display =
        AnnotatedString(
            text.text,
            spanStyles = text.spanStyles + links.mapIndexed { i, link -> AnnotatedString.Range(SpanStyle(color = looks[i].ink, fontWeight = looks[i].weight), link.start, link.end) },
            paragraphStyles = text.paragraphStyles,
        ).let { base ->
            if (links.none { it.leavesRaft }) {
                base
            } else {
                buildAnnotatedString {
                    append(base)
                    links.forEachIndexed { i, link ->
                        if (link.leavesRaft) addStringAnnotation(RaftTextLinkExternalMark.InlineContentTag, RaftTextLinkExternalMark.id(i), link.end - 1, link.end)
                    }
                }
            }
        }
    val density = LocalDensity.current
    val markFontSize = if (style.fontSize.isSp) style.fontSize.value else 14f
    val marks =
        links.withIndex().filter { it.value.leavesRaft }.associate { (i, _) ->
            RaftTextLinkExternalMark.id(i) to
                InlineTextContent(raftTextLinkExternalPlaceholder(style, density)) { RaftTextLinkExternalMarkContent(looks[i].ink, markFontSize) }
        }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val result = layout
    // One target per line of a link (a link that wraps has one per line), so a tap on
    // plain words beside a wrapped link, or on an earlier link, never lands on it.
    val targets =
        if (result == null) {
            emptyList()
        } else {
            links.flatMapIndexed { i, link ->
                raftTextLinkSegments(result, link.start, link.end).mapIndexed { line, bounds -> RaftTextLinkTarget(i, line, bounds) }
            }
        }
    Layout(
        modifier = modifier,
        content = {
            BasicText(
                text = display,
                modifier =
                    Modifier.drawBehind {
                        val drawn = layout ?: return@drawBehind
                        links.forEachIndexed { i, link ->
                            if (focused[i]) {
                                raftTextLinkSegments(drawn, link.start, link.end).forEach { box ->
                                    drawRaftFocusRing(box, RaftTextLinkMetrics.FocusRadius, colors.layerCanvas, colors.lineStrong)
                                }
                            }
                            looks[i].underline?.let { drawRaftTextLinkUnderline(drawn, link.start, link.wordsEnd, it) }
                        }
                    },
                style = style,
                inlineContent = marks,
                onTextLayout = {
                    layout = it
                    onTextLayout?.invoke(it)
                },
                overflow = overflow,
                softWrap = softWrap,
                maxLines = maxLines,
            )
            targets.forEach { target ->
                val link = links[target.index]
                val source = sources[target.index]
                val onClick = { link.annotation.linkInteractionListener?.onClick(link.annotation); Unit }
                if (target.line == 0) {
                    // The link's one node for focus, keyboard and screen readers.
                    val words = text.text.substring(link.start, link.wordsEnd)
                    Box(
                        Modifier
                            .semantics { this.text = AnnotatedString(words) }
                            .raftPointerHover(source)
                            .clickable(interactionSource = source, indication = null, enabled = link.enabled, role = Role.Button, onClick = onClick),
                    )
                } else {
                    // Later lines take taps (and press the same link) without a node of their own.
                    key(target.index, target.line) {
                        val latest by rememberUpdatedState(onClick)
                        Box(Modifier.raftPointerHover(source).raftTapTarget(link.enabled, source) { latest() })
                    }
                }
            }
        },
    ) { measurables, constraints ->
        // The text takes the incoming constraints as they are (a fillMaxWidth caller's
        // min width included), so TextAlign.Center / End align within that width.
        val text = measurables.first().measure(constraints)
        val overlays =
            measurables.drop(1).mapIndexed { k, measurable ->
                val bounds = targets[k].bounds
                measurable.measure(Constraints.fixed(bounds.width.roundToInt().coerceAtLeast(0), bounds.height.roundToInt().coerceAtLeast(0)))
            }
        layout(text.width, text.height) {
            text.place(0, 0)
            overlays.forEachIndexed { k, overlay ->
                val bounds = targets[k].bounds
                overlay.place(bounds.left.roundToInt(), bounds.top.roundToInt())
            }
        }
    }
}

/** The click target of [line] of link [index]: its words on that line ([bounds], px). */
private class RaftTextLinkTarget(val index: Int, val line: Int, val bounds: Rect)

/**
 * A tap target with no semantics or focus: a press shows on [source] (so the link's
 * pressed look covers every line) and a tap runs [onClick] (read when tapped: pass a
 * lambda reading an updated state).
 */
private fun Modifier.raftTapTarget(enabled: Boolean, source: MutableInteractionSource, onClick: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        // Keyed on the source only: a press recomposes the link, which must not restart this.
        pointerInput(source) {
            detectTapGestures(
                onPress = { offset ->
                    val press = PressInteraction.Press(offset)
                    source.emit(press)
                    source.emit(if (tryAwaitRelease()) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                },
                onTap = { onClick() },
            )
        }
    }

/** Ink-box of the characters [start] until [end], one rect per line (px). Empty when none is laid out. */
internal fun raftTextLinkSegments(layout: TextLayoutResult, start: Int, end: Int): List<Rect> {
    val visibleEnd = min(end, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    if (start >= visibleEnd) return emptyList()
    val first = layout.getLineForOffset(start)
    val last = layout.getLineForOffset(visibleEnd - 1)
    return (first..last).mapNotNull { line ->
        val segStart = max(start, layout.getLineStart(line))
        var segEnd = min(visibleEnd, layout.getLineEnd(line, visibleEnd = true))
        // A wrapped line can end in a (no-break) space: not part of the underline or target.
        val chars = layout.layoutInput.text.text
        while (segEnd > segStart && chars[segEnd - 1].isWhitespace()) segEnd--
        if (segStart >= segEnd) return@mapNotNull null
        val a = layout.getBoundingBox(segStart)
        val b = layout.getBoundingBox(segEnd - 1)
        Rect(min(a.left, b.left), layout.getLineTop(line), max(a.right, b.right), layout.getLineBottom(line))
    }
}

/** The 1.5dp underline of characters [start] until [end], 3dp under each line's baseline. */
private fun DrawScope.drawRaftTextLinkUnderline(layout: TextLayoutResult, start: Int, end: Int, color: Color) {
    val thickness = RaftTextLinkMetrics.UnderlineThickness.toPx()
    val offset = RaftTextLinkMetrics.UnderlineOffset.toPx()
    raftTextLinkSegments(layout, start, end).forEach { segment ->
        val line = layout.getLineForVerticalPosition(segment.center.y)
        val top = layout.getLineBaseline(line) + offset
        drawRect(color, topLeft = Offset(segment.left, top), size = Size(segment.width, thickness))
    }
}
