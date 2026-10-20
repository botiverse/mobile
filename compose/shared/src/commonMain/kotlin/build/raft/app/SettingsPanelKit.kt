package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The web Settings panel (`SettingsPanel.tsx`, raft-ui 0.10) drawn natively: section heads,
 * cards, the profile identity card, compact form fields and the small action buttons, with
 * the web's metrics (1dp field frames, 10dp card radius, 16dp card padding).
 *
 * The design-system field and button still carry raft-ui 0.8 metrics (1.5dp frames); the
 * settings pages use these until the design system catches up, so the native pages line up
 * with the web panel they mirror.
 */
internal object SettingsPanelKit {
    val CardRadius: Dp = 10.dp
    val ControlRadius: Dp = 8.dp
    val CardPadding: Dp = 16.dp
    val SectionGap: Dp = 24.dp
    val SectionHeadGap: Dp = 12.dp
    val FieldHeight: Dp = 44.dp
    val FieldGap: Dp = 12.dp
}

/** A Space Grotesk text style at an exact size, line height and weight (CSS line box). */
internal fun settingsPanelText(size: TextUnit, lineHeight: TextUnit, weight: FontWeight = FontWeight.Normal): TextStyle =
    RaftType.Body.copy(fontSize = size, lineHeight = lineHeight, letterSpacing = 0.sp).withRaftFontWeight(weight)

/** A Space Mono text style at an exact size and line height. */
internal fun settingsPanelMono(size: TextUnit, lineHeight: TextUnit, weight: FontWeight = FontWeight.Normal): TextStyle =
    RaftType.Body.copy(fontFamily = RaftMonoFamily, fontSize = size, lineHeight = lineHeight, letterSpacing = 0.sp, fontWeight = weight)

/**
 * Web `SectionHeader`: a 16dp icon in `foreground-hint`, 8dp, then the group label
 * (13/18 bold, `foreground-hint`), 12dp above the content it heads.
 */
@Composable
internal fun SettingsPanelSectionHeader(
    iconName: RaftLucideIconName?,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconName != null) {
            RaftLucideIcon(iconName, modifier = Modifier.size(16.dp), color = raftArgbHex(RaftColor.ForegroundHint))
        }
        Text(
            label,
            style = settingsPanelText(13.sp, 18.sp, FontWeight.Bold),
            color = RaftColor.ForegroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** raft-ui `Card`: `layer-card`, a 1dp hairline edge, 10dp corners, 16dp inside. */
@Composable
internal fun SettingsPanelCard(
    modifier: Modifier = Modifier,
    padding: Dp = SettingsPanelKit.CardPadding,
    verticalSpacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(SettingsPanelKit.CardRadius)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(RaftColor.Card, shape)
                .border(1.dp, RaftColor.BorderHairline, shape)
                .padding(padding),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content,
    )
}

/** raft-ui `Separator`: a 1dp hairline rule. */
@Composable
internal fun SettingsPanelSeparator(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(RaftColor.BorderHairline))
}

/**
 * Web `SettingsProfileCard` head: the avatar centred in a 64dp slot, 16dp, then the
 * identity (17/22 bold title, 2dp, 13/20 Space Mono subtitle in `foreground-muted`) 8dp
 * down; a 16dp gap, the hairline, and 16dp more before the form.
 */
@Composable
internal fun SettingsPanelProfileHead(
    title: String,
    subtitle: String,
    avatar: @Composable () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { avatar() }
        Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = settingsPanelText(17.sp, 22.sp, FontWeight.Bold),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = settingsPanelMono(13.sp, 20.sp),
                    color = RaftColor.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The server / account sticker in the profile head: a 51dp ink-edged tile, sticker radius. */
@Composable
internal fun SettingsPanelInitialTile(
    initial: String,
    fill: Color,
    ink: Color,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier.size(51.dp).background(fill, shape).border(1.dp, RaftColor.StickerInk, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            style = settingsPanelText(22.sp, 22.sp, FontWeight.Bold).copy(letterSpacing = (-0.22).sp),
            color = ink,
            maxLines = 1,
        )
    }
}

/** Web compact `FormField`: the 12/16 semibold `foreground-muted` label, 6dp, the control. */
@Composable
internal fun SettingsPanelField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = settingsPanelText(12.sp, 16.sp, FontWeight.SemiBold), color = RaftColor.TextMuted, maxLines = 1)
        content()
    }
}

/** raft-ui 0.10 `Input`: 44dp, a 1dp `line-field` frame, 8dp corners, 12dp in, 16/24 text. */
@Composable
internal fun SettingsPanelInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    mono: Boolean = false,
) {
    val shape = RoundedCornerShape(SettingsPanelKit.ControlRadius)
    val textStyle = if (mono) settingsPanelMono(16.sp, 24.sp) else settingsPanelText(16.sp, 24.sp)
    val textColor = if (enabled) RaftColor.TextBlack else RaftColor.ForegroundDisabled
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = textStyle.copy(color = textColor),
        cursorBrush = SolidColor(RaftColor.TextBlack),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(SettingsPanelKit.FieldHeight)
                        .background(if (enabled) RaftColor.Card else RaftColor.FillMuted, shape)
                        .border(1.dp, if (enabled) RaftColor.LineField else RaftColor.BorderHairline, shape)
                        .padding(horizontal = 13.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = textStyle, color = RaftColor.ForegroundHint, maxLines = 1)
                }
                inner()
            }
        },
    )
}

/**
 * Web `SlugInput` / `PrefixedInput`: a fixed prefix in its own 12dp-padded Space Mono cell
 * (`foreground-hint` on `fill-muted`, a hairline after it) and the field text. [readOnly]
 * is the settings read-only look: `fill-muted` with a hairline frame and muted text.
 */
@Composable
internal fun SettingsPanelPrefixedInput(
    prefix: String,
    value: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = true,
    onValueChange: (String) -> Unit = {},
    placeholder: String? = null,
    monoValue: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(SettingsPanelKit.ControlRadius)
    val textStyle = if (monoValue) settingsPanelMono(16.sp, 24.sp) else settingsPanelText(16.sp, 24.sp)
    val fill = if (readOnly) RaftColor.FillMuted else RaftColor.Card
    val edge = if (readOnly) RaftColor.BorderHairline else RaftColor.LineField
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(SettingsPanelKit.FieldHeight)
                .background(fill, shape)
                .border(1.dp, edge, shape)
                .padding(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.fillMaxHeight().background(RaftColor.FillMuted), verticalAlignment = Alignment.CenterVertically) {
            Text(
                prefix,
                style = settingsPanelMono(13.sp, 20.sp),
                color = RaftColor.ForegroundHint,
                modifier = Modifier.padding(horizontal = 12.dp),
                maxLines = 1,
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(RaftColor.BorderHairline))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            enabled = enabled,
            singleLine = true,
            textStyle = textStyle.copy(color = if (readOnly) RaftColor.TextMuted else RaftColor.TextBlack),
            cursorBrush = SolidColor(RaftColor.TextBlack),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = textStyle, color = RaftColor.ForegroundHint, maxLines = 1)
                    }
                    inner()
                }
            },
        )
    }
}

internal enum class SettingsPanelButtonVariant { Accent, Warning, Danger, Default, Ghost }

internal enum class SettingsPanelButtonSize(val height: Dp, val paddingX: Dp, val textSize: TextUnit, val lineHeight: TextUnit) {
    /** `size="sm"`: 32dp, 12dp in, 13/18 bold. */
    Sm(32.dp, 12.dp, 13.sp, 18.sp),

    /** Dialog footer `lg`: 44dp, 16dp in, 15/20 bold. */
    Lg(44.dp, 16.dp, 15.sp, 20.sp),
}

/**
 * raft-ui 0.10 `Button` as the settings panel draws it: a 1dp edge, 8dp corners, a 16dp
 * icon 6dp before the label. Disabled is `fill-muted`, a hairline edge and
 * `foreground-disabled` type.
 */
@Composable
internal fun SettingsPanelButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: SettingsPanelButtonVariant = SettingsPanelButtonVariant.Default,
    size: SettingsPanelButtonSize = SettingsPanelButtonSize.Sm,
    enabled: Boolean = true,
    leadingIcon: RaftLucideIconName? = null,
) {
    val shape = RoundedCornerShape(SettingsPanelKit.ControlRadius)
    val (fill, edge, ink) =
        if (!enabled) {
            if (variant == SettingsPanelButtonVariant.Ghost) {
                Triple(Color.Transparent, Color.Transparent, RaftColor.ForegroundDisabled)
            } else {
                Triple(RaftColor.FillMuted, RaftColor.BorderHairline, RaftColor.ForegroundDisabled)
            }
        } else {
            when (variant) {
                SettingsPanelButtonVariant.Accent -> Triple(RaftColor.Attention, RaftColor.StickerInk, RaftColor.StickerInk)
                SettingsPanelButtonVariant.Warning -> Triple(RaftColor.Danger, RaftColor.StickerInk, RaftColor.StickerInk)
                SettingsPanelButtonVariant.Danger -> Triple(RaftColor.Danger, RaftColor.StickerInk, RaftColor.StickerInk)
                SettingsPanelButtonVariant.Default -> Triple(RaftColor.Card, RaftColor.LineField, RaftColor.TextBlack)
                SettingsPanelButtonVariant.Ghost -> Triple(Color.Transparent, Color.Transparent, RaftColor.TextBlack)
            }
        }
    Row(
        modifier =
            modifier
                .height(size.height)
                .background(fill, shape)
                .border(1.dp, edge, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = size.paddingX + 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            RaftLucideIcon(leadingIcon, modifier = Modifier.size(16.dp), color = raftArgbHex(ink))
        }
        Text(
            label,
            style = settingsPanelText(size.textSize, size.lineHeight, FontWeight.Bold),
            color = ink,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * raft-ui `DialogContent` surface: `layer-popover`, 16dp corners, the 1dp `line-seam` ring
 * outside the box, a 1dp lip and the float shadow (`0 12px 32px -12px` ink at 32%).
 */
internal fun Modifier.settingsPanelDialogSurface(): Modifier {
    val shape = RoundedCornerShape(16.dp)
    val ring = RoundedCornerShape(17.dp)
    return this
        .dropShadow(
            shape = shape,
            shadow =
                Shadow(
                    radius = 32.dp,
                    color = RaftColor.OverlayShadow,
                    spread = (-12).dp,
                    offset = DpOffset(0.dp, 12.dp),
                ),
        )
        .drawBehind {
            val grow = 1.dp.toPx()
            val outline = ring.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this)
            translate(-grow, -grow) { drawOutline(outline, RaftColor.BorderSeam) }
        }
        .background(RaftColor.Popover, shape)
}

/**
 * Web `Banner intent="destructive"`: `danger-soft` with a 1dp inset hairline ring, 10dp
 * corners, 12dp / 14dp inside, the 20dp OctagonAlert, 10dp, then the content.
 */
@Composable
internal fun SettingsPanelDangerBanner(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(SettingsPanelKit.CardRadius)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.DangerSoft, shape)
                .border(1.dp, RaftColor.BorderHairline, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RaftLucideIcon(RaftLucideIconName.OctagonAlert, modifier = Modifier.size(20.dp), color = raftArgbHex(RaftColor.TextBlack))
        Column(Modifier.weight(1f), content = content)
    }
}
