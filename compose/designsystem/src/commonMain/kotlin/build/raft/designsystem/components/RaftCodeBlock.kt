package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/**
 * A read-only command or value people copy (raft-ui CopyableCode, the web's setup
 * steps): Space Mono `text-mono-code` 13/20 in `foreground` on `layer-code`, a 1dp
 * `line-hairline` edge, control radius ([RaftRadius.Container] via [containerRadius] for
 * the longer "other agents" instruction), `px-3 py-2`.
 *
 * The mobile pages wrap long commands (`whitespace-pre-wrap break-all`); pass
 * [wrap] = false for raft-ui's one-line rule. [action] is the trailing copy button (a
 * ghost [RaftIconButton] with [RaftGlyph.Copy], named "Copy <what>"); the clipboard is
 * the caller's.
 */
@Composable
fun RaftCodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    wrap: Boolean = true,
    containerRadius: Boolean = false,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(if (containerRadius) RaftRadius.Container else RaftRadius.Control)
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.layerCode, shape)
            .border(RaftStroke.Hairline, colors.lineHairline, shape)
            .padding(start = 12.dp, end = if (action != null) 4.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RaftText(
            text,
            modifier = Modifier.weight(1f),
            // `break-all`: a long token breaks anywhere rather than overflowing.
            style = RaftTheme.typography.monoCode.copy(lineBreak = LineBreak.Simple),
            color = colors.foreground,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            softWrap = wrap,
            overflow = if (wrap) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
        if (action != null) {
            // Centre the action on the first 20dp line, without growing the block.
            Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                Row(Modifier.wrapContentHeight(unbounded = true), verticalAlignment = Alignment.CenterVertically, content = action)
            }
        }
    }
}

/**
 * A value shown in a field's place that can't be edited: the disabled field look. 44dp,
 * control radius, `fill-muted`, a 1.5dp `line-hairline` edge, 15/20 `foreground-muted`
 * text, `px-3`. [mono] sets machine values in Space Mono.
 */
@Composable
fun RaftReadOnlyField(
    text: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Control)
    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(colors.fillMuted, shape)
            .border(RaftStroke.Control, colors.lineHairline, shape)
            .padding(horizontal = 12.dp + RaftStroke.Control),
        contentAlignment = Alignment.CenterStart,
    ) {
        RaftText(
            text,
            style = if (mono) RaftTheme.typography.monoCode else RaftTheme.typography.subhead,
            color = colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
