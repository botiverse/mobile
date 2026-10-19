package build.raft.app.components

import build.raft.app.SlockColor
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockMonoFamily
import build.raft.app.SlockRadius
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.slockArgbHex
import build.raft.app.withSlockFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.ExperimentalLayoutApi
import com.tencent.kuikly.compose.foundation.layout.FlowRow
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.RowScope
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

/*
 * raft-ui 0.7.1 data-display primitives for pages: Card, the inset list of ListRows,
 * DescriptionList, the soft/outline Badge ("tag"), the code / read-only value block and
 * the profile section. Pages compose these instead of hand-drawing borders, so the
 * chrome (radius, hairline, layer, type) lives in one place.
 */

/** raft text roles used below (styles.css `--text-*`). */
internal object SlockRaftText {
    /** `text-body` 16/24. */
    val Body = 16.sp
    val BodyLine = 24.sp
    /** `text-subhead` 15/20. */
    val Subhead = 15.sp
    val SubheadLine = 20.sp
    /** `text-callout` 14/20. */
    val Callout = 14.sp
    val CalloutLine = 20.sp
    /** `text-footnote` 13/18. */
    val Footnote = 13.sp
    val FootnoteLine = 18.sp
    /** `text-caption` 12/16. */
    val Caption = 12.sp
    val CaptionLine = 16.sp
    /** `text-mono-code` 13/20. */
    val MonoCode = 13.sp
    val MonoCodeLine = 20.sp
}

/**
 * raft-ui `Card` (`variant="default"`): container radius, `--layer-card`, 1dp
 * `--line-muted` hairline, no shadow, `p-4` content. [fill] takes a soft state wash
 * for a tinted card (Banner-like notices keep [SlockRaftUiBanner]).
 */
@Composable
internal fun SlockRaftCard(
    modifier: Modifier = Modifier,
    fill: Color = SlockColor.Card,
    radius: Dp = SlockRadius.Container,
    contentPadding: Dp = 16.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth().slockRaftCardSurface(fill = fill, radius = radius).padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content
    )
}

/**
 * raft-ui inset list (`rf-list--inset`): ONE card holding a run of [SlockRaftListRow]s.
 * The rows draw the hairline between siblings themselves (`divider = index > 0`), starting
 * at the text column, so the card has no padding of its own.
 */
@Composable
internal fun SlockRaftInsetList(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(SlockRadius.Container)
    Column(
        modifier = modifier.fillMaxWidth().clip(shape).slockRaftCardSurface(),
        content = content
    )
}

/**
 * raft-ui `ListRow` (`rf-row`): 12dp block / 16dp inline padding, a 12dp gap, the
 * optional [leading] slot (avatar / tile, [leadingSize] wide), the title at the list-row
 * scale (`text-body` 16/24 semibold ink) with an optional mono [meta] on the same line
 * (`text-mono-meta`, hint ink), the [description] on its own line (`text-callout` 14/20,
 * muted ink) and a vertically centred [trailing] slot. With [divider] the row draws the
 * sibling hairline at its top edge, starting at the text column (16 + leading + 12).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SlockRaftListRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    meta: String? = null,
    leading: (@Composable () -> Unit)? = null,
    leadingSize: Dp = 0.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    divider: Boolean = false,
    selected: Boolean = false,
    titleColor: Color = SlockColor.TextBlack,
    descriptionMaxLines: Int = 2,
    minHeight: Dp = 44.dp
) {
    val textStart = if (leading != null) 16.dp + leadingSize + 12.dp else 16.dp
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(if (selected) SlockColor.InfoSoft else Color.Transparent)
                .let { base ->
                    if (onClick != null) base.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else base
                }
    ) {
        if (divider) {
            SlockRaftHairline(modifier = Modifier.padding(start = textStart))
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Text(
                        text = title,
                        style =
                            SlockType.Body
                                .copy(fontSize = SlockRaftText.Body, lineHeight = SlockRaftText.BodyLine, letterSpacing = 0.sp)
                                .withSlockFontWeight(FontWeight.SemiBold),
                        color = if (enabled) titleColor else SlockColor.ForegroundDisabled,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    meta?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            modifier = Modifier.align(Alignment.CenterVertically),
                            style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                            color = SlockColor.ForegroundHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = SlockType.Body.copy(fontSize = SlockRaftText.Callout, lineHeight = SlockRaftText.CalloutLine, letterSpacing = 0.sp)
                            .withSlockFontWeight(FontWeight.Normal),
                        color = SlockColor.TextMuted,
                        maxLines = descriptionMaxLines,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            trailing?.let {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = it
                )
            }
        }
    }
}

/** raft-ui `Separator` / list hairline: 1dp `--line-muted`. */
@Composable
internal fun SlockRaftHairline(modifier: Modifier = Modifier, color: Color = SlockColor.BorderHairline) {
    Box(modifier = modifier.fillMaxWidth().height(SlockStroke.Thin).background(color))
}

internal enum class SlockRaftDescriptionDirection {
    /** Term above details, items 8dp apart, details `min-h-7`. */
    Vertical,

    /**
     * The profile property list (`direction="horizontal"`). Under 640px raft collapses it to
     * one column (`max-sm:grid-cols-1 gap-y-0.5`) and phones are always under that, so it
     * stacks with a 2dp rhythm and `min-h-8` details.
     */
    Horizontal
}

private val LocalSlockRaftDescriptionDirection = staticCompositionLocalOf { SlockRaftDescriptionDirection.Vertical }

/** raft-ui `DescriptionList`: a run of [SlockRaftDescriptionItem]s. */
@Composable
internal fun SlockRaftDescriptionList(
    modifier: Modifier = Modifier,
    direction: SlockRaftDescriptionDirection = SlockRaftDescriptionDirection.Vertical,
    content: @Composable ColumnScope.() -> Unit
) {
    CompositionLocalProvider(LocalSlockRaftDescriptionDirection provides direction) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (direction == SlockRaftDescriptionDirection.Vertical) 8.dp else 2.dp),
            content = content
        )
    }
}

/**
 * raft-ui `DescriptionItem` = `DescriptionTerm` (13/18 medium hint ink, source case, any
 * [termAccessory] icons after the label) over `DescriptionDetails` (callout 14/20 ink,
 * wrapping row with a 6dp gap).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SlockRaftDescriptionItem(
    term: String,
    modifier: Modifier = Modifier,
    termAccessory: (@Composable RowScope.() -> Unit)? = null,
    details: @Composable () -> Unit
) {
    val direction = LocalSlockRaftDescriptionDirection.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = term,
                style =
                    SlockType.Body
                        .copy(fontSize = SlockRaftText.Footnote, lineHeight = SlockRaftText.FootnoteLine, letterSpacing = 0.sp)
                        .withSlockFontWeight(FontWeight.Medium),
                color = SlockColor.ForegroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            termAccessory?.invoke(this)
        }
        FlowRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (direction == SlockRaftDescriptionDirection.Horizontal) 32.dp else 28.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.align(Alignment.CenterVertically)) { details() }
        }
    }
}

/** [SlockRaftDescriptionItem] with a plain text value (`—` when blank). */
@Composable
internal fun SlockRaftDescriptionItem(
    term: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    maxLines: Int = 3,
    termAccessory: (@Composable RowScope.() -> Unit)? = null
) {
    SlockRaftDescriptionItem(term = term, modifier = modifier, termAccessory = termAccessory) {
        SlockRaftDescriptionValue(value = value, mono = mono, maxLines = maxLines)
    }
}

/** The details text of a description item: callout 14/20 ink, or mono 12/16 for machine values. */
@Composable
internal fun SlockRaftDescriptionValue(
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    maxLines: Int = 3,
    color: Color = SlockColor.TextBlack
) {
    val blank = value.isBlank()
    Text(
        text = if (blank) "—" else value,
        modifier = modifier,
        style =
            if (mono) {
                SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp)
            } else {
                SlockType.Body
                    .copy(fontSize = SlockRaftText.Callout, lineHeight = SlockRaftText.CalloutLine, letterSpacing = 0.sp)
                    .withSlockFontWeight(FontWeight.Normal)
            },
        color = if (blank) SlockColor.ForegroundHint else color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/** raft-ui Badge colour roles (`variant`). */
internal enum class SlockRaftTagTone {
    Default,
    Muted,
    Primary,
    Information,
    Accent,
    Success,
    Warning,
    Danger
}

internal enum class SlockRaftTagAppearance {
    /** `appearance="soft"`: 22dp chip, the role's `-soft` wash, ink type, no edge. */
    Soft,

    /** `appearance="outline"`: 22dp chip, transparent, 1dp `--line-muted` ring, mono meta type. */
    Outline
}

private val SlockRaftTagTone.softFill: Color
    get() =
        when (this) {
            SlockRaftTagTone.Default -> SlockColor.FillMuted
            SlockRaftTagTone.Muted -> SlockColor.InactiveSoft
            SlockRaftTagTone.Primary -> SlockColor.BrandSoft
            SlockRaftTagTone.Information -> SlockColor.InfoSoft
            SlockRaftTagTone.Accent -> SlockColor.AttentionSoft
            SlockRaftTagTone.Success -> SlockColor.SuccessSoft
            SlockRaftTagTone.Warning -> SlockColor.WarningSoft
            SlockRaftTagTone.Danger -> SlockColor.DangerSoft
        }

private val SlockRaftTagTone.outlineInk: Color
    get() =
        when (this) {
            SlockRaftTagTone.Default,
            SlockRaftTagTone.Muted -> SlockColor.TextMuted
            SlockRaftTagTone.Primary -> SlockColor.BrandStrong
            SlockRaftTagTone.Information -> SlockColor.InfoStrong
            SlockRaftTagTone.Accent -> SlockColor.AttentionStrong
            SlockRaftTagTone.Success -> SlockColor.SuccessStrong
            SlockRaftTagTone.Warning -> SlockColor.WarningStrong
            SlockRaftTagTone.Danger -> SlockColor.DangerStrong
        }

/**
 * raft-ui `Badge` in the soft / outline appearances (the solid sticker is
 * [SlockRaftBadgeChrome]): a 22dp chip with chip radius 6dp, 6dp side padding, a 4dp gap,
 * 14dp icons and the label in its source case.
 */
@Composable
internal fun SlockRaftTag(
    label: String,
    modifier: Modifier = Modifier,
    tone: SlockRaftTagTone = SlockRaftTagTone.Default,
    appearance: SlockRaftTagAppearance = SlockRaftTagAppearance.Soft,
    leadingIcon: SlockLucideIconName? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(SlockRadius.Chip)
    val soft = appearance == SlockRaftTagAppearance.Soft
    val ink = if (soft) SlockColor.TextBlack else tone.outlineInk
    Row(
        modifier =
            modifier
                .height(22.dp)
                .widthIn(min = 0.dp)
                .clip(shape)
                .let { if (soft) it.background(tone.softFill, shape) else it.border(SlockStroke.Thin, SlockColor.BorderHairline, shape) }
                .let { base -> if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base }
                .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        leadingIcon?.let { SlockLucideIcon(it, modifier = Modifier.size(14.dp), color = slockArgbHex(ink)) }
        Text(
            text = label,
            style =
                if (soft) {
                    SlockType.Caption
                        .copy(fontSize = SlockRaftText.Caption, lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp)
                        .withSlockFontWeight(FontWeight.SemiBold)
                } else {
                    SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp)
                },
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Read-only value / command block (web `rounded-control border border-line-muted
 * bg-layer-code px-3 py-2 font-mono text-mono-code`): a value the user reads or copies,
 * never edits. [mono] = false sets it in callout sans for prose values.
 */
@Composable
internal fun SlockRaftValueBlock(
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    color: Color = SlockColor.TextBlack,
    fill: Color = SlockColor.Code,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .slockRaftCardSurface(fill = fill, radius = SlockRadius.Control)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style =
                if (mono) {
                    SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = SlockRaftText.MonoCode, lineHeight = SlockRaftText.MonoCodeLine)
                } else {
                    SlockType.Body
                        .copy(fontSize = SlockRaftText.Callout, lineHeight = SlockRaftText.CalloutLine, letterSpacing = 0.sp)
                        .withSlockFontWeight(FontWeight.Normal)
                },
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
        trailing?.invoke(this)
    }
}

/**
 * A profile / detail section (web `Separator` + `px-5 py-4` + `SectionEyebrow` + body):
 * an optional full-width hairline above, 16dp block padding, the source-case section
 * title with optional [titleAccessory] (edit pencil, help) and [trailing] action, then
 * [content] 12dp below the title. Horizontal padding stays with the page.
 */
@Composable
internal fun SlockRaftSection(
    title: String?,
    modifier: Modifier = Modifier,
    separator: Boolean = true,
    verticalPadding: Dp = 16.dp,
    titleAccessory: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    contentSpacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (separator) SlockRaftHairline()
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing)
        ) {
            if (title != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SlockSectionEyebrow(label = title)
                        titleAccessory?.invoke(this)
                    }
                    trailing?.invoke(this)
                }
            }
            content()
        }
    }
}
