package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import build.raft.app.RaftAppThemeState
import build.raft.app.RaftAppTypeScale
import build.raft.app.RaftColor
import build.raft.app.RaftType
import build.raft.app.withRaftUserContentLocale

// Message text is user content: Han characters in Simplified Chinese forms, like the Web.
internal fun raftMessageBodyTextStyle(preferenceKey: String?): TextStyle =
    raftMessageBodyStep(preferenceKey).withRaftUserContentLocale()

// The style bakes the theme foreground (RaftColor.TextBlack is read once, at build time), so a
// remembered copy must be keyed on the theme too: keyed on the font size alone, pages already open
// across a light/dark switch kept painting message bodies in the old theme's text colour. The key
// is the resolved colours (mode and server theme), so a server theme switch rebuilds it as well.
// It is keyed on the type scale for the same reason: the body size (and every tier, which sits
// relative to it) changes when the window crosses the wide width with a mouse, or the input changes there.
@Composable
internal fun rememberRaftMessageBodyTextStyle(preferenceKey: String?): TextStyle {
    val themeColors = RaftAppThemeState.colors
    val typeScale = RaftAppTypeScale.scale
    return remember(preferenceKey, themeColors, typeScale) { raftMessageBodyTextStyle(preferenceKey) }
}

// Web appearanceStore (MESSAGE_BODY_FONT_SIZE_VALUES): the tiers are relative to `text-body`, so
// one stored choice reads right at both scales. Medium is the body size; Small and Large sit 2sp
// either side of it, with the line 4sp shorter / taller. Phone (body 16/24): 14/20, 16/24, 18/28.
// Wide (body 14/22, the desktop type scale: a wide window with a mouse): 12/18, 14/22, 16/26.
private val MessageBodyTierFontStep = 2.sp
private val MessageBodyTierLineStep = 4.sp

private fun raftMessageBodyStep(preferenceKey: String?): TextStyle {
    val body = RaftType.Body.copy(color = RaftColor.TextBlack)
    return when (preferenceKey?.trim()?.lowercase()) {
        "sm" -> body.stepped(-1)
        "lg" -> body.stepped(+1)
        else -> body
    }
}

private fun TextStyle.stepped(direction: Int): TextStyle =
    copy(
        fontSize = fontSize.plusSp(MessageBodyTierFontStep.value * direction),
        lineHeight = lineHeight.plusSp(MessageBodyTierLineStep.value * direction)
    )

private fun TextUnit.plusSp(delta: Float): TextUnit = (value + delta).sp
