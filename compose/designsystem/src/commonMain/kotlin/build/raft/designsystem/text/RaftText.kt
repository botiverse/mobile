package build.raft.designsystem.text

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme

/**
 * The text primitive of the design system, on `BasicText` rather than Material `Text`:
 * no `LocalTextStyle` / `LocalContentColor` from a Material theme can shift its colour
 * or line height. Colour resolves as [color], else `style.color`, else
 * [LocalRaftContentColor] (CSS `currentColor`); [style] defaults to [RaftTheme]'s
 * `body` (`text-body`, 16/24).
 */
@Composable
fun RaftText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = RaftTheme.typography.body,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.resolve(color, textAlign),
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
    )
}

/** [RaftText] for styled spans. */
@Composable
fun RaftText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = RaftTheme.typography.body,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.resolve(color, textAlign),
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
    )
}

@Composable
private fun TextStyle.resolve(color: Color, textAlign: TextAlign?): TextStyle {
    val ink =
        when {
            color.isSpecified -> color
            this.color.isSpecified -> this.color
            else -> LocalRaftContentColor.current
        }
    return merge(TextStyle(color = ink, textAlign = textAlign ?: TextAlign.Unspecified))
}
